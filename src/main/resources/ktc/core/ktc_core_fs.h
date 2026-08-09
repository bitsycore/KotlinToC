// ktc_core_fs.h - cross-platform filesystem helpers backing ktc.std.FileSystem.
//
// All `path` arguments are (bytes, len) pairs (NOT NUL-terminated) so KTC strings
// can be passed straight through. Each helper stack-buffers the path with a NUL
// terminator before invoking libc. PATH_MAX bytes max; longer paths are refused.
//
// Return convention: int 0/1 for success/failure (booleans), or count for reads.

#pragma once

#include "ktc_core.h"

#define KTC_FS_PATH_MAX 4096

typedef struct ktc_core_fs_meta {
    int  is_regular_file;    // 1 if regular file
    int  is_directory;       // 1 if directory
    long size;               // file size in bytes (0 for dirs)
    long last_modified_ms;   // mtime as ms since epoch (-1 if unavailable)
} ktc_core_fs_meta;

// Metadata / existence ────────────────────────────────────────
int  ktc_core_fs_exists  (const char* path_bytes, int path_len);
int  ktc_core_fs_metadata(const char* path_bytes, int path_len, ktc_core_fs_meta* out);

// Mutation ───────────────────────────────────────────────────
// All return 1 on success, 0 on failure.
int  ktc_core_fs_delete (const char* path_bytes, int path_len);
int  ktc_core_fs_rename (const char* from_bytes, int from_len, const char* to_bytes, int to_len);
int  ktc_core_fs_mkdir  (const char* path_bytes, int path_len);

// File I/O ───────────────────────────────────────────────────
// Returns a FILE* (as void*) or NULL.
// `mode` is a C-string literal - "rb", "wb", "ab" etc.
void* ktc_core_fs_fopen (const char* path_bytes, int path_len, const char* mode);
int   ktc_core_fs_fclose(void* fp);
int   ktc_core_fs_fread (void* fp, void* buf, int byte_count);   // bytes read; 0 at EOF, -1 on error
int   ktc_core_fs_fwrite(void* fp, const void* buf, int byte_count);  // bytes written
int   ktc_core_fs_fflush(void* fp);

// Directory listing ──────────────────────────────────────────
// open: returns opaque handle or NULL.
// next: writes the next entry's filename (without the . and .. entries) into
//       name_out (NUL-terminated, capped at name_cap-1 bytes) and returns the
//       written length. Returns 0 at end of directory, -1 on error.
// close: frees the handle (safe to call with NULL).
void* ktc_core_fs_listdir_open (const char* path_bytes, int path_len);
int   ktc_core_fs_listdir_next (void* handle, char* name_out, int name_cap);
void  ktc_core_fs_listdir_close(void* handle);
