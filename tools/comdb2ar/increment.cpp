#include "comdb2ar.h"

#include "increment.h"
#include "glue.h"
#include "db_wrap.h"
#include "serialiseerror.h"
#include "error.h"
#include "tar_header.h"
#include "fdostream.h"
#include "file_info.h"

#include <sys/stat.h>
#include <fstream>
#include <cstring>
#include <sstream>
#include <iostream>
#include <cassert>
#include <memory>

#include <vector>
#include <set>
#include <map>
#include <utility>

#include <fcntl.h>
#include <unistd.h>


bool is_not_incr_file(std::string filename){
    return(filename.substr(filename.length() - 5) != ".incr");
}

bool assert_cksum_lsn(uint8_t *new_pagep, uint8_t *old_pagep, size_t pagesize) {
    uint32_t new_lsn_file = LSN(new_pagep).file;
    uint32_t new_lsn_offs = LSN(new_pagep).offset;
    bool verify_ret;
    uint32_t new_cksum;
    verify_checksum(new_pagep, pagesize, false, false, &verify_ret, &new_cksum);

    uint8_t cmp_arr[12];
    for (int i = 0; i < 4; ++i){
        cmp_arr[i] = ((uint8_t *) &new_lsn_file)[i];
        cmp_arr[4 + i] = ((uint8_t *) &new_lsn_offs)[i];
        cmp_arr[8 + i] = ((uint8_t *) &new_cksum)[i];
    }

    return (memcmp(cmp_arr, old_pagep, 12) != 0);
}


bool compare_checksum(FileInfo file, const std::string& incr_path,
                        std::vector<uint32_t>& pages, ssize_t *data_size,
                        std::set<std::string>& incr_files) {
    std::string filename = file.get_filename();
    std::string incr_file_name = incr_path + "/" + filename + ".incr";

    std::set<std::string>::const_iterator it = incr_files.find(filename + ".incr");
    if(it == incr_files.end()){
        std::ostringstream ss;
        std::clog << "new incr file found mid backup of file: " << filename << std::endl;
        // throw SerialiseError(filename, ss.str());
    } else {
        incr_files.erase(it);
    }

    struct stat sb;
    bool ret = false;

    int flags = O_RDONLY | O_LARGEFILE;
    int new_fd = open(file.get_filepath().c_str(), flags);
    int old_fd = open(incr_file_name.c_str(), O_RDWR);

    struct stat new_st;
    if(fstat(new_fd, &new_st) == -1) {
        std::ostringstream ss;
        ss << "cannot stat file: " << std::strerror(errno);
        throw SerialiseError(filename, ss.str());
    }

    if(stat(incr_file_name.c_str(), &sb) != 0) {
        std::clog << "New File: " << filename << std::endl;
        *data_size = new_st.st_size;
        return true;
    } else {


        size_t pagesize = file.get_pagesize();
        if(pagesize == 0) {
            pagesize = 4096;
        }

        uint8_t *new_pagebuf = NULL;
        uint8_t old_pagebuf[12];

#if ! defined  ( _SUN_SOURCE ) && ! defined ( _HP_SOURCE )
        posix_memalign((void**) &new_pagebuf, 512, pagesize);
#else
        new_pagebuf = (uint8_t*) memalign(512, pagesize);
#endif

        off_t bytesleft = new_st.st_size;

        while(bytesleft >= pagesize) {
            ssize_t new_bytesread = read(new_fd, &new_pagebuf[0], pagesize);
            ssize_t old_bytesread = read(old_fd, &old_pagebuf[0], 12);
            if(new_bytesread <= 0 || old_bytesread <= 0) {
                std::ostringstream ss;
                ss << "read error after " << bytesleft << " bytes";
                throw SerialiseError(filename, ss.str());
            }

            if (assert_cksum_lsn(new_pagebuf, old_pagebuf, pagesize)) {
                pages.push_back(PGNO(new_pagebuf));
                *data_size += pagesize;
                ret = true;
            }

            bytesleft -= pagesize;
        }

        if(new_pagebuf) free(new_pagebuf);
    }
    return ret;
}



ssize_t write_incr_file(const FileInfo& file, std::vector<uint32_t> pages,
                            std::string incr_path){
    const std::string& filename = file.get_filename();

    int flags;
    bool skip_iomap = false;
    std::ostringstream ss;

    // Ensure large file support
    assert(sizeof(off_t) == 8);

    struct stat st;
    if(stat(filename.c_str(), &st) == -1) {
        ss << "cannot stat file: " << std::strerror(errno);
        throw SerialiseError(filename, ss.str());
    }

    size_t pagesize = file.get_pagesize();
    if(pagesize == 0) {
        pagesize = 4096;
    }

    std::ifstream ifs (filename, std::ifstream::in | std::ifstream::binary);

    char *char_pagebuf = NULL;
    uint8_t *pagebuf = NULL;

#if ! defined  ( _SUN_SOURCE ) && ! defined ( _HP_SOURCE )
    posix_memalign((void**) &char_pagebuf, 512, pagesize);
    posix_memalign((void**) &pagebuf, 512, pagesize);
#else
    char_pagebuf = (char*) memalign(512, pagesize);
    pagebuf = (uint8_t*) memalign(512, pagesize);
#endif

    ssize_t total_read = 0;
    ssize_t total_written = 0;

    std::string incrFilename = incr_path + "/" + filename + ".incr";

    // if(stat(incrFilename.c_str(), &st) == -1) {
        // ss << "cannot stat file: " << std::strerror(errno);
        // throw SerialiseError(incrFilename, ss.str());
    // }


    std::ofstream incrFile(incrFilename, std::ofstream::out |
                            std::ofstream::in | std::ofstream::binary);

    for(std::vector<uint32_t>::const_iterator
            it = pages.begin();
            it != pages.end();
            ++it){

        ifs.seekg(pagesize * *it, ifs.beg);
        ifs.read(&char_pagebuf[0], pagesize);
        ssize_t bytes_read = ifs.gcount();
        if(bytes_read < pagesize) {
            std::ostringstream ss;
            ss << "read error";
            throw SerialiseError(filename, ss.str());
        }

        std::memcpy(pagebuf, char_pagebuf, pagesize);

        uint32_t cksum;
        bool cksum_verified;
        verify_checksum(pagebuf, pagesize, false, false,
                            &cksum_verified, &cksum);
        if(!cksum_verified){
            std::ostringstream ss;
            ss << "serialise_file:page failed checksum verification";
            throw SerialiseError(filename, ss.str());
        }


        incrFile.seekp(12 * *it, incrFile.beg);
        PAGE * pagep = (PAGE *) pagebuf;

        incrFile.write((char *) &(LSN(pagep).file), 4);
        incrFile.write((char *) &(LSN(pagep).offset), 4);
        incrFile.write((char *) &cksum, 4);

        ssize_t bytes_written = writeall(1, pagebuf, pagesize);
        if(bytes_written != pagesize){
            std::ostringstream ss;
            ss << "error writing text data: " << std::strerror(errno);
            throw SerialiseError(filename, ss.str());
        }

        total_read += bytes_read;
        total_written += bytes_written;
        if(total_written != total_read){
            std::ostringstream ss;
            ss << "different amounts written and read. wrote " << total_written
                << " bytes and read " << total_read << " bytes "
                << std::strerror(errno);
            throw SerialiseError(filename, ss.str());
        }
    }

    if (pagebuf)
        free(pagebuf);

    return total_read;
}


std::string getDTString() {
    time_t rawtime;
    struct tm *timeinfo;
    char buffer[80];

    time(&rawtime);
    timeinfo = localtime(&rawtime);

    strftime(buffer, sizeof(buffer), "%Y%m%d%I%M%S", timeinfo);
    std::string str(buffer);

    return str;
}

void incr_deserialise_database(
    const std::string lrldestdir,
    const std::string datadestdir,
    std::set<std::string>& table_set,
    unsigned percent_full,
    bool force_mode,
    bool legacy_mode,
    bool& is_disk_full,
    const std::string& incr_path
)
{
    static const char zero_head[512] = {0};

    while(true) {

        // Read the tar block header
        tar_block_header head;
        size_t bytes_read = readall(0, head.c, sizeof(head.c));
        if(bytes_read == 0){
            // Lazy check that all increments are done (ie STDIN has no additional input)
            break;
        } else if(bytes_read != sizeof(head.c)) {

            // Failed to read a full block header
            std::ostringstream ss;
            ss << "Error reading tar block header: "
                << errno << " " << strerror(errno);
            throw Error(ss);
        }

        std::clog << bytes_read << std::endl;

        // If the block is entirely blank then we're done with the increment
        if(std::memcmp(head.c, zero_head, 512) == 0) {
            std::clog << "blank" << std::endl;
            continue;
        }

        // Get the file name
        if(head.h.filename[sizeof(head.h.filename) - 1] != '\0') {
            throw Error("Bad block: filename is not null terminated");
        }
        const std::string filename(head.h.filename);
        std::clog << filename << ": ";

        // Get the file size
        unsigned long long filesize;
        if(!read_octal_ull(head.h.size, sizeof(head.h.size), filesize)) {
            throw Error("Bad block: bad size");
        }
        unsigned long long nblocks = (filesize + 511ULL) >> 9;
        std::clog << filesize << std::endl;

        bool is_manifest = false;
        if(filename == "INCR_MANIFEST") {
            is_manifest = true;
        }

        std::string ext;
        size_t dot_pos = filename.find_first_of('.');
        if(dot_pos != std::string::npos) {
            ext = filename.substr(dot_pos + 1);
        }
        bool is_data = false;
        if(ext == "data") {
            is_data = true;
        }

        // Gather the text of the file for lrls and manifests

        std::unique_ptr<fdostream> of_ptr;

        std::map<std::string, FileInfo> new_files;
        std::map<std::string, std::pair<FileInfo, std::vector<uint32_t>>> updated_files;
        std::set<std::string> deleted_files;
        std::vector<std::string> file_order;
        std::vector<std::string> options;

        if(is_manifest) {
            std::string manifest_text = read_incr_manifest(filesize);
            if(!process_incr_manifest(manifest_text, datadestdir,
                    updated_files, new_files, deleted_files,
                    file_order, options)){
                // Failed to read a coherant manifest
                std::ostringstream ss;
                ss << "Error reading manifest";
                throw Error(ss);
            }
            for(std::vector<std::string>::const_iterator
                    it = file_order.begin();
                    it != file_order.end();
                    ++it){
                std::clog << *it << " ";
            }
            std::clog << std::endl;

        } else {
            // TODO: UNPACK
            // unupack_incr_data(file_order,
        }
    }
}
