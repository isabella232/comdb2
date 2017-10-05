(ns comdb2.core
 "Tests for Comdb2"
 (:require
   [clojure.tools.logging :refer :all]
   [clojure.core.reducers :as r]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.pprint :refer [pprint]]
   [jepsen.checker.timeline  :as timeline]
   [jepsen [client :as client]
    [core :as jepsen]
    [db :as db]
    [tests :as tests]
    [control :as c]
    [checker :as checker]
    [nemesis :as nemesis]
    [independent :as independent]
    [util :as util :refer [timeout meh]]
    [generator :as gen]
    [reconnect :as rc]]
   [knossos.op :as op]
   [knossos.model :as model]
   [clojure.java.jdbc :as j])
 (:import [knossos.model Model]))

(java.sql.DriverManager/registerDriver (com.bloomberg.comdb2.jdbc.Driver.))

; TODO: break out tests into separate namespaces

(defn cluster-nodes
  "A vector of nodes in the cluster; taken from the CLUSTER env variable."
  []
  (-> (System/getenv "CLUSTER")
      (or "m1 m2 m3 m4 m5")
      (str/split #"\s+")
      vec))

;; JDBC wrappers

(def timeout-delay "Default timeout for operations in ms" 5000)

(defmacro with-prepared-statement-with-timeout
  "Takes a DB conn and a [symbol sql-statement] pair. Constructs a prepared
  statement with a default timeout out of sql-statement, and binds it to
  `symbol`, then evaluates body. Finally, closes the prepared statement."
  [conn [symbol sql] & body]
  `(let [~symbol (j/prepare-statement (j/db-find-connection ~conn)
                                 ~sql
                                 {:timeout (/ timeout-delay 1000)})]
     (try
       ~@body
       (finally
         (.close ~symbol)))))

(defn execute!
  "Like jdbc execute, but includes a default timeout in ms."
  ([conn sql-params]
   (execute! conn sql-params {}))
  ([conn [sql & params] opts]
   (with-prepared-statement-with-timeout conn [s sql]
     (j/execute! conn (into [s] params) opts))))

(defn query
  "Like jdbc query, but includes a default timeout in ms."
  ([conn expr]
   (query conn expr {}))
  ([conn [sql & params] opts]
   (with-prepared-statement-with-timeout conn [s sql]
     (j/query conn (into [s] params) opts))))

(defn insert!
  "Like jdbc insert!, but includes a default timeout."
  [conn table values]
  (j/insert! conn table values {:timeout timeout-delay}))

(defn update!
  "Like jdbc update!, but includes a default timeout."
  [conn table values where]
  (j/update! conn table values where {:timeout timeout-delay}))

;; Connection handling

(defn conn-spec
  "JDBC connection spec for a given node."
  [node]
  (info "connecting to" node)
  {:classname   "com.bloomberg.comdb2.jdbc.Driver"
   :subprotocol "comdb2"
   ; One valid subname has a DB name and DB stage: "NAME:STAGE"
   ; Another format is "//NODE/NAME"
   ; I don't know how to do node, name, and stage together.
   ;   :subname (str (System/getenv "COMDB2_DBNAME") ":"
   ;                 (System/getenv "COMDB2_DBSTAGE"))})
   :subname     (str "//" (name node)
                     "/"  (System/getenv "COMDB2_DBNAME"))})

(defn close-conn!
  "Given a JDBC connection, closes it and returns the underlying spec."
  [conn]
  (when-let [c (j/db-find-connection conn)]
    (.close c))
  (dissoc conn :connection))

(defn wait-for-conn
  "I have a hunch connection state is asynchronous in the comdb2 driver, so we
  may need to block a bit for a connection to become ready."
  [conn]
  (util/with-retry [tries 30]
    (info "Waiting for conn")
    (query conn ["set hasql on"])
    ; I know it says "nontransient" but maaaybe it is???
    (catch java.sql.SQLNonTransientConnectionException e
      (when (neg? tries)
        (throw e))

      (info "Conn not yet available; waiting\n" (with-out-str (pprint conn)))
      (Thread/sleep 1000)
      (retry (dec tries))))
  conn)

(defn connect
  "Constructs and opens a reconnectable JDBC client for a given node."
  [node]
  (rc/open!
    (rc/wrapper
      {:name [:comdb2 node]
       :open (fn open []
               (timeout 5000
                        (throw (RuntimeException.
                                 (str "Timed out connecting to " node)))
                        (let [spec  (conn-spec node)
                              conn  (j/get-connection spec)
                              spec' (j/add-connection spec conn)]
                          (assert spec')
                          spec')))
                          ;(wait-for-conn spec'))))
       :close close-conn!
       :log? true})))

(defmacro with-conn
  "Like jepsen.reconnect/with-conn, but also asserts that the connection has
  not been closed. If it has, throws an ex-info with :type :conn-not-ready.
  Delays by 1 second to allow time for the DB to recover."
  [[c client] & body]
  `(rc/with-conn [~c ~client]
     (when (.isClosed (j/db-find-connection ~c))
       (Thread/sleep 1000)
       (throw (ex-info "Connection not yet ready."
                       {:type :conn-not-ready})))
     ~@body))



;; Error handling

(defmacro with-timeout
  "Like util/timeout, but throws (RuntimeException. \"timeout\") for timeouts.
  Throwing means that when we time out inside a with-conn, the connection state
  gets reset, so we don't accidentally hand off the connection to a later
  invocation with some incomplete transaction."
  [& body]
  `(util/timeout timeout-delay
                 (throw (RuntimeException. "timeout"))
                 ~@body))

; TODO: can we unify this with errorCodes?
(defn retriable-transaction-error
  "Given an error string, identifies whether the error is safely retriable."
  [e]
  (or (re-find #"not serializable" e)
      (re-find #"unable to update record rc = 4" e)
      (re-find #"selectv constraints" e)
      (re-find #"Maximum number of retries done." e)))

(defmacro capture-txn-abort
  "Converts aborted transactions to an ::abort keyword"
  [& body]
  `(try ~@body
        (catch java.sql.SQLException e#
         (println "error is " (.getMessage e#))
         (if (retriable-transaction-error (.getMessage e#))
          ::abort
          (throw e#)))))

(defmacro with-txn-retries
  "Retries body on rollbacks."
  [& body]
  `(loop []
     (let [res# (capture-txn-abort ~@body)]
       (if (= ::abort res#)
        (do
         (info "RETRY")
         (recur))
         res#))))

(defmacro with-txn
  "Executes body in a transaction, with a timeout, automatically retrying
  conflicts and handling common errors."
  [op c node & body]
  `(with-txn-retries
      ~@body))

(defrecord BankClient [node n starting-balance]
  client/Client

  (setup! [this test node]
    ; (println "n " (:n this) "test " test "node " node)
    (j/with-db-connection [c (conn-spec node)]
      ; Create initial accts
      (dotimes [i n]
        (try
         (insert! c :accounts {:id i, :balance starting-balance})
         (catch java.sql.SQLException e
          (if (.contains (.getMessage e) "add key constraint duplicate key")
            nil
            (throw e))))))

    (assoc this :node node))

  (invoke! [this test op]
    (with-txn op (conn-spec node) nil
     (j/with-db-transaction [connection (conn-spec node) :isolation :serializable]
      (query connection ["set hasql on"])
      (query connection ["set max_retries 100000"])

      (try
        (case (:f op)
          :read (->> (query connection ["select * from accounts"])
                     (mapv :balance)
                     (assoc op :type :ok, :value))

          :transfer
          (let [{:keys [from to amount]} (:value op)
                b1 (-> connection
                       (query ["select * from accounts where id = ?" from]
                         :row-fn :balance)
                       first
                       (- amount))
                b2 (-> connection
                       (query ["select * from accounts where id = ?" to]
                         :row-fn :balance)
                       first
                       (+ amount))]
            (cond (neg? b1)
                  (assoc op :type :fail, :value [:negative from b1])

                  (neg? b2)
                  (assoc op :type :fail, :value [:negative to b2])

                  true
                    (do (execute! connection ["update accounts set balance = balance - ? where id = ?" amount from])
                        (execute! connection ["update accounts set balance = balance + ? where id = ?" amount to])
                        (assoc op :type :ok)))))))))

  (teardown! [_ test]))

(defn bank-client
  "Simulates bank account transfers between n accounts, each starting with
  starting-balance."
  [n starting-balance]
  (BankClient. nil n starting-balance))

(defn bank-read
  "Reads the current state of all accounts without any synchronization."
  [_ _]
  {:type :invoke, :f :read})

(defn bank-transfer
  "Transfers a random amount between two randomly selected accounts."
  [test process]
  (let [n (-> test :client :n)]
    {:type  :invoke
     :f     :transfer
     :value {:from   (rand-int n)
             :to     (rand-int n)
             :amount (rand-int 5)}}))

(def bank-diff-transfer
  "Like transfer, but only transfers between *different* accounts."
  (gen/filter (fn [op] (not= (-> op :value :from)
                             (-> op :value :to)))
              bank-transfer))

(defn bank-checker
  "Balances must all be non-negative and sum to the model's total."
  []
  (reify checker/Checker
    (check [this test model history opts]
      (let [bad-reads (->> history
                           (r/filter op/ok?)
                           (r/filter #(= :read (:f %)))
                           (r/map (fn [op]
                                  (let [balances (:value op)]
                                    (cond (not= (:n model) (count balances))
                                          {:type :wrong-n
                                           :expected (:n model)
                                           :found    (count balances)
                                           :op       op}

                                         (not= (:total model)
                                               (reduce + balances))
                                         {:type :wrong-total
                                          :expected (:total model)
                                          :found    (reduce + balances)
                                          :op       op}))))
                           (r/filter identity)
                           (into []))]
        {:valid? (empty? bad-reads)
         :bad-reads bad-reads}))))

(defn with-nemesis
  "Wraps a client generator in a nemesis that induces failures and eventually
  stops."
  [client]
  (gen/phases
    (gen/phases
      (->> client
           (gen/nemesis
             (gen/seq (cycle [(gen/sleep 0)
                              {:type :info, :f :start}
                              (gen/sleep 10)
                              {:type :info, :f :stop}])))
           (gen/time-limit 30))
      (gen/nemesis (gen/once {:type :info, :f :stop}))
      (gen/sleep 5))))

(defn basic-test
  [opts]
  (merge tests/noop-test
         {:name "comdb2-bank"
          :nemesis (nemesis/partition-random-halves)
          :nodes (cluster-nodes)
          :ssh {:username "root"
                :password "shadow"
                :strict-host-key-checking false}}
          (dissoc opts :name :version)))

(def nkey
  "A globally unique integer counter"
  (atom 0))

(defn next-key
  "Obtain a new, globally unique integer counter"
  []
  (swap! nkey inc))

(defn set-client
  [node]
  (reify client/Client
    (setup! [this test node]
     (set-client node))

    (invoke! [this test op]
     (println op)
     ; TODO: Open our own connection instead of using the global conn-spec?
      (j/with-db-transaction [connection (conn-spec node) :isolation :serializable]

        (query connection ["set hasql on"])
        (query connection ["set transaction serializable"])
        (when (System/getenv "COMDB2_DEBUG")
          (query connection ["set debug on"]))
;        (query connection ["set debug on"])
        (query connection ["set max_retries 100000"])
        (with-txn op (conn-spec node) node
          (try
            (case (:f op)
              :add  (do (execute! connection [(str "insert into jepsen(id, value) values(" (next-key) ", " (:value op) ")")])
                        (assoc op :type :ok))

              :read (->> (query connection ["select * from jepsen"])
                         (mapv :value)
                         (into (sorted-set))
                         (assoc op :type :ok, :value)))))))

    (teardown! [_ test])))

(defn sets-test-nemesis
 []
 (basic-test
   {:name "set"
    :client (set-client nil)
    :generator (gen/phases
                 (->> (range)
                      (map (partial array-map
                                    :type :invoke
                                    :f :add
                                    :value))
                      gen/seq
                      (gen/delay 1/10)
                      with-nemesis)
                 (->> {:type :invoke, :f :read, :value nil}
                      gen/once
                      gen/clients))
    :checker (checker/compose
               {:perf (checker/perf)
                :set checker/set})}))

(defn sets-test
  []
  (basic-test
    {:name "set"
     :client (set-client nil)
     :generator (gen/phases
                  (->> (range)
                       (map (partial array-map
                                     :type :invoke
                                     :f :add
                                     :value))
                       gen/seq
                       (gen/delay 1/10)
                       )
                  (->> {:type :invoke, :f :read, :value nil}
                       gen/once
                       gen/clients))
     :checker (checker/compose
                {:perf (checker/perf)
                 :set checker/set})}))




(defn bank-test-nemesis
  [n initial-balance]
  (basic-test
    {:name "bank"
     :concurrency 10
     :model  {:n n :total (* n initial-balance)}
     :client (bank-client n initial-balance)
     :generator (gen/phases
                  (->> (gen/mix [bank-read bank-diff-transfer])
                       (gen/clients)
                       (gen/stagger 1/10)
                       (gen/time-limit 100)
                       with-nemesis)
                  (gen/log "waiting for quiescence")
                  (gen/sleep 10)
                  (gen/clients (gen/once bank-read)))
     :nemesis (nemesis/partition-random-halves)
     :checker (checker/compose
                {:perf (checker/perf)
                 :linearizable (independent/checker checker/linearizable)
                 :bank (bank-checker)})}))


(defn bank-test
  [n initial-balance]
  (basic-test
    {:name "bank"
     :concurrency 10
     :model  {:n n :total (* n initial-balance)}
     :client (bank-client n initial-balance)
     :generator (gen/phases
                  (->> (gen/mix [bank-read bank-diff-transfer])
                       (gen/clients)
                       (gen/stagger 1/10)
                       (gen/time-limit 100))
                  (gen/log "waiting for quiescence")
                  (gen/sleep 10)
                  (gen/clients (gen/once bank-read)))
     :nemesis nemesis/noop
     :checker (checker/compose
                {:perf (checker/perf)
                 :linearizable (independent/checker checker/linearizable)
                 :bank (bank-checker)})}))

; This is the dirty reads test for Galera
; TODO: Kyle, review the dirty reads test for galera and figure out what this
; was supposed to do

(defrecord DirtyReadsClient [node n]
  client/Client
  (setup! [this test node]
   (warn "setup")
   (j/with-db-connection [c (conn-spec node)]
    ; Create table
    (dotimes [i n]
     (try ; TODO: no catch?
      (with-txn-retries
       (Thread/sleep (rand-int 10))
       (insert! c :dirty {:id i, :x -1})))))

    (assoc this :node node))

  (invoke! [this test op]
   (try
    (j/with-db-transaction [c (conn-spec node) :isolation :serializable]
     (try ; TODO: no catch?
      (case (:f op)
       ; skip initial records - not all threads are done initial writing, and
       ; the initial writes aren't a single transaction so we won't see
       ; consistent reads
       :read (->> (query c ["select * from dirty where x != -1"])
                  (mapv :x)
                  (assoc op :type :ok, :value))

       :write (let [x (:value op)
                    order (shuffle (range n))]
                ; TODO: why is there a discarded read here?
                (doseq [i order]
                  (query c ["select * from dirty where id = ?" i]))
                (doseq [i order]
                  (update! c :dirty {:x x} ["id = ?" i]))
                (assoc op :type :ok)))))
    (catch java.sql.SQLException e
     ; TODO: why do we know this is a definite failure?
     (assoc op :type :fail :reason (.getMessage e)))))

  (teardown! [_ test]))

(defmacro with-txn-prep!
  "Takes a connection, an op, and a body.

  1. Turns on hasql
  2. Sets transaction to serializable
  3. May turn on debug mode
  4. Sets maximum retries

  If this fails because of a connection error, sleeps briefly (to avoid
  hammering a down node with reconnects), and returns the op with :type fail.
  If it succeeds, moves on to execute body.

  TODO: what... should this actually be called? I don't exactly understand how
  these four commands work together, and what their purpose is"
  [c op & body]
  `(try (query ~c ["set hasql on"])
        (query ~c ["set transaction serializable"])
        (when (System/getenv "COMDB2_DEBUG")
          (query ~c ["set debug on"]))
        (query ~c ["set max_retries 100000"])

        ~@body

        (catch java.sql.SQLNonTransientConnectionException e#
          (when-not (re-find #"Can't connect to db\." (.getMessage e#))
            (throw e#))

          (Thread/sleep 5000) ; Give node a chance to wake up again
          (assoc ~op :type :fail, :error :can't-connect))))

(defmacro with-sql-exceptions-as-errors
  "Takes an op and a body. Executes body, catching SQLExceptions and converting
  common error codes to failing or info ops."
  [op & body]
  `(try
     ~@body
     (catch java.sql.SQLException e#
       (condp = (.getErrorCode e#)
         2  (assoc ~op :type :fail, :error (.getMessage e#))
            (throw e#)))))

(defn comdb2-cas-register-client
  "Comdb2 register client"
  ([]
   (comdb2-cas-register-client nil nil))
  ([conn uids]
   (reify client/Client
     (setup! [this test node]
       (let [conn (connect node)]
         (with-conn [c conn]
           ; TODO: why don't we create a table here?
           (j/delete! c :register ["1 = 1"]))
         (comdb2-cas-register-client conn (atom -1))))

     (invoke! [this test op]
       (with-conn [c conn]
         (with-txn-prep! c op
           (with-sql-exceptions-as-errors op
             (case (:f op)
               :read
               (let [[id val'] (:value op)
                     [val uid] (second (query c ["select val,uid from register where id = ?" id] {:as-arrays? true}))]
                 (assoc op
                        :type  :ok
                        :uid   uid
                        :value (independent/tuple id val)))

               :write
               (j/with-db-transaction [c c]
                 (let [[id val'] (:value op)
                       [val uid] (second
                                   (query c ["select val,uid from register where id = ?" id]
                                          {:as-arrays? true}))
                       uid' (swap! uids inc)
                       updated (first
                                 (if val
                                   ; We have an existing row
                                   ; TODO: why "where 1"?
                                   (execute! c [(str "update register set val=" val' ",uid=" uid' " where id=" id)])
                                   ; No existing row; insert
                                   (execute! c [(str "insert into register (id, val, uid) values (" id "," val' "," uid' ")")])))]
                   (assert (<= 0 updated 1))
                   (if (zero? updated)
                     (assoc op :type :fail)
                     (assoc op
                            :type  :ok,
                            :uid   uid'))))

               :cas
               (j/with-db-transaction [c c]
                 (let [[id [expected-val val']] (:value op)
                       [val uid] (second
                                   (query c ["select val,uid from register where id = ?" id] {:as-arrays? true}))
                       uid' (swap! uids inc)
                       updated (first
                                 (execute! c [(str "update register set val=" val' ",uid=" uid' " where id=" id " and val=" expected-val " -- old-uid is " uid)]))]
                   (assert (<= 0 updated 1))
                   (if (zero? updated)
                     (assoc op :type :fail)
                     (assoc op
                            :type  :ok
                            :uid   uid')))))))))

     ; TODO: disconnect
     (teardown! [_ test]
       (rc/close! conn)))))

; Test on only one register for now
(defn r   [_ _] {:type :invoke, :f :read, :value nil})
(defn w   [_ _] {:type :invoke, :f :write, :value (rand-int 5)})
(defn cas [_ _] {:type :invoke, :f :cas, :value [(rand-int 5) (rand-int 5)]})

; TODO: better name for this, move it near the dirty-reads client
(defn client
  [n]
  (DirtyReadsClient. nil n))

(defn dirty-reads-checker
  "We're looking for a failed transaction whose value became visible to some
  read."
  []
  (reify checker/Checker
    (check [this test model history opts]
      (let [failed-writes
            ; Add a dummy failed write so we have something to compare to in the
            ; unlikely case that there's no other write failures
                               (merge  {:type :fail, :f :write, :value -12, :process 0, :time 0}
                                (->> history 
                                 (r/filter op/fail?)
                                 (r/filter #(= :write (:f %)))
                                 (r/map :value)
                                 (into (hash-set))))

            reads (->> history
                       (r/filter op/ok?)
                       (r/filter #(= :read (:f %)))
                       (r/map :value))

            inconsistent-reads (->> reads
                                    (r/filter (partial apply not=))
                                    (into []))
            filthy-reads (->> reads
                              (r/filter (partial some failed-writes))
                              (into []))]
        {:valid?              (empty? filthy-reads)
         :inconsistent-reads  inconsistent-reads
         :dirty-reads         filthy-reads}))))

(def dirty-reads-reads {:type :invoke, :f :read, :value nil})

(def dirty-reads-writes (->> (range)
                 (map (partial array-map
                               :type :invoke,
                               :f :write,
                               :value))
                 gen/seq))

(defn dirty-reads-basic-test
  [opts]
  (merge tests/noop-test
         {:name "dirty-reads"
          :nodes (cluster-nodes)
          :ssh {
            :username "root"
            :password "shadow"
            :strict-host-key-checking false
          }
          :nemesis (nemesis/partition-random-halves)}
         (dissoc opts :name :version)))

; TODO: unused, delete?
(defn minutes [seconds] (* 60 seconds))

(defn dirty-reads-tester
  [version n]
  (dirty-reads-basic-test
    {:name "dirty reads"
     :concurrency 1
     :version version
     :client (client n)
     :generator (->> (gen/mix [dirty-reads-reads dirty-reads-writes])
                     gen/clients
                     (gen/time-limit 10))
     :nemesis nemesis/noop
     :checker (checker/compose
                {:perf (checker/perf)
                 :dirty-reads (dirty-reads-checker)
                 :linearizable (independent/checker checker/linearizable)})}))

; TODO: just change the nemesis, no need for two copies with only slightly
; different schedules, right?
(defn register-tester
  [opts]
  (basic-test
    (merge
      {:name        "register"
       :client      (comdb2-cas-register-client)
       :concurrency 10

       :generator   (gen/phases
                      (->> (gen/mix [w cas cas r])
                           (gen/clients)
                           (gen/stagger 1/10)
                           (gen/time-limit 10))
                      (gen/log "waiting for quiescence")
                      (gen/sleep 10))
       :model       (model/cas-register)
       :time-limit   100
       :recovery-time  30
       :checker     (checker/compose
                      {:perf  (checker/perf)
                       :linearizable (independent/checker
                                       (checker/linearizable))}) }
      opts)))

(defn register-tester-nemesis
  [opts]
  (basic-test
    (merge
      {:name        "register"
       :client      (comdb2-cas-register-client)
       :concurrency 10

       :generator   (->> (independent/concurrent-generator
                           10
                           (range)
                           (fn [k]
                             (->> (gen/reserve 5 (gen/mix [w cas cas]) r)
                                  (gen/stagger 1/10)
                                  (gen/limit 200))))
                           with-nemesis)
       :model       (model/cas-register)
       :time-limit   180
       :recovery-time  30
       :checker     (checker/compose
                      {:perf  (checker/perf)
                       :linearizable (independent/checker
                                       (checker/linearizable))}) }
      opts)))
