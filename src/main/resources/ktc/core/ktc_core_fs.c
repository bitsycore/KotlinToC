// ktc_core_fs.c — POSIX / Win32 split for ktc.std.FileSystem.
//
// Strategy: every path arrives as (bytes, len), gets NUL-terminated into a
// stack scratch buffer (KTC_FS_PATH_MAX), then handed to libc/Win32. This keeps
// the Kotlin caller from having to allocate a C-string.

#define _CRT_SECURE_NO_WARNINGS   // silence MSVC fopen/strncpy nags

#include "ktc_core_fs.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>

#ifdef _WIN32
    #include <direct.h>
    #include <io.h>
    #include <windows.h>
    #ifndef S_ISREG
        #define S_ISREG(m) (((m) & _S_IFMT) == _S_IFREG)
    #endif
    #ifndef S_ISDIR
        #define S_ISDIR(m) (((m) & _S_IFMT) == _S_IFDIR)
    #endif
    typedef struct _stat64 ktc_stat_s;
    #define ktc_stat_call(p, s) _stat64((p), (s))
    #define ktc_mkdir_call(p)   _mkdir(p)
    #define ktc_rmdir_call(p)   _rmdir(p)
#else
    #include <unistd.h>
    #include <dirent.h>
    typedef struct stat ktc_stat_s;
    #define ktc_stat_call(p, s) stat((p), (s))
    #define ktc_mkdir_call(p)   mkdir((p), 0755)
    #define ktc_rmdir_call(p)   rmdir(p)
#endif

// Copy [bytes,len] into [out, out_cap] and NUL-terminate. Returns 1 on success,
// 0 if the path would overflow the scratch buffer (caller must refuse the op).
static int ktc_fs_to_cstr(const char* bytes, int len, char* out, int out_cap) {
    if (len < 0 || len >= out_cap) return 0;
    memcpy(out, bytes, (size_t)len);
    out[len] = '\0';
    return 1;
}

// ── Metadata ───────────────────────────────────────────────

int ktc_core_fs_exists(const char* path_bytes, int path_len) {
    char buf[KTC_FS_PATH_MAX];
    if (!ktc_fs_to_cstr(path_bytes, path_len, buf, sizeof(buf))) return 0;
    ktc_stat_s st;
    return ktc_stat_call(buf, &st) == 0 ? 1 : 0;
}

int ktc_core_fs_metadata(const char* path_bytes, int path_len, ktc_core_fs_meta* out) {
    char buf[KTC_FS_PATH_MAX];
    if (!ktc_fs_to_cstr(path_bytes, path_len, buf, sizeof(buf))) return 0;
    ktc_stat_s st;
    if (ktc_stat_call(buf, &st) != 0) return 0;
    out->is_regular_file  = S_ISREG(st.st_mode) ? 1 : 0;
    out->is_directory     = S_ISDIR(st.st_mode) ? 1 : 0;
    out->size             = (long)st.st_size;
    out->last_modified_ms = (long)st.st_mtime * 1000L;
    return 1;
}

// ── Mutation ───────────────────────────────────────────────

int ktc_core_fs_delete(const char* path_bytes, int path_len) {
    char buf[KTC_FS_PATH_MAX];
    if (!ktc_fs_to_cstr(path_bytes, path_len, buf, sizeof(buf))) return 0;
    // remove() handles regular files on every platform. For directories Windows
    // needs _rmdir(); POSIX accepts both. Try remove() first, fall back to rmdir.
    if (remove(buf) == 0) return 1;
    return ktc_rmdir_call(buf) == 0 ? 1 : 0;
}

int ktc_core_fs_rename(const char* from_bytes, int from_len, const char* to_bytes, int to_len) {
    char from_buf[KTC_FS_PATH_MAX];
    char to_buf  [KTC_FS_PATH_MAX];
    if (!ktc_fs_to_cstr(from_bytes, from_len, from_buf, sizeof(from_buf))) return 0;
    if (!ktc_fs_to_cstr(to_bytes,   to_len,   to_buf,   sizeof(to_buf)))   return 0;
    return rename(from_buf, to_buf) == 0 ? 1 : 0;
}

int ktc_core_fs_mkdir(const char* path_bytes, int path_len) {
    char buf[KTC_FS_PATH_MAX];
    if (!ktc_fs_to_cstr(path_bytes, path_len, buf, sizeof(buf))) return 0;
    return ktc_mkdir_call(buf) == 0 ? 1 : 0;
}

// ── File I/O ───────────────────────────────────────────────

void* ktc_core_fs_fopen(const char* path_bytes, int path_len, const char* mode) {
    char buf[KTC_FS_PATH_MAX];
    if (!ktc_fs_to_cstr(path_bytes, path_len, buf, sizeof(buf))) return NULL;
    return (void*)fopen(buf, mode);
}

int ktc_core_fs_fclose(void* fp) {
    if (!fp) return 0;
    return fclose((FILE*)fp) == 0 ? 1 : 0;
}

int ktc_core_fs_fread(void* fp, void* buf, int byte_count) {
    if (!fp || byte_count <= 0) return 0;
    size_t n = fread(buf, 1, (size_t)byte_count, (FILE*)fp);
    if (n > 0) return (int)n;
    if (feof((FILE*)fp)) return 0;
    return -1;
}

int ktc_core_fs_fwrite(void* fp, const void* buf, int byte_count) {
    if (!fp || byte_count <= 0) return 0;
    return (int)fwrite(buf, 1, (size_t)byte_count, (FILE*)fp);
}

int ktc_core_fs_fflush(void* fp) {
    if (!fp) return 0;
    return fflush((FILE*)fp) == 0 ? 1 : 0;
}

// ── Directory listing ──────────────────────────────────────

#ifdef _WIN32

typedef struct {
    HANDLE           h;
    WIN32_FIND_DATAA d;
    int              first_consumed;   // 0 until the first entry has been returned
} ktc_dirhandle;

void* ktc_core_fs_listdir_open(const char* path_bytes, int path_len) {
    char buf[KTC_FS_PATH_MAX];
    if (!ktc_fs_to_cstr(path_bytes, path_len, buf, sizeof(buf))) return NULL;
    // FindFirstFile needs a glob — append \* (or /*) to the directory.
    char pattern[KTC_FS_PATH_MAX + 4];
    snprintf(pattern, sizeof(pattern), "%s\\*", buf);
    ktc_dirhandle* dh = (ktc_dirhandle*)malloc(sizeof(*dh));
    if (!dh) return NULL;
    dh->h = FindFirstFileA(pattern, &dh->d);
    if (dh->h == INVALID_HANDLE_VALUE) { free(dh); return NULL; }
    dh->first_consumed = 0;
    return dh;
}

int ktc_core_fs_listdir_next(void* handle, char* name_out, int name_cap) {
    if (!handle || name_cap <= 0) return -1;
    ktc_dirhandle* dh = (ktc_dirhandle*)handle;
    for (;;) {
        if (dh->first_consumed) {
            if (!FindNextFileA(dh->h, &dh->d)) return 0;
        }
        dh->first_consumed = 1;
        const char* name = dh->d.cFileName;
        if (name[0] == '.' && (name[1] == '\0' ||
            (name[1] == '.' && name[2] == '\0'))) continue;
        int n = (int)strlen(name);
        if (n >= name_cap) n = name_cap - 1;
        memcpy(name_out, name, (size_t)n);
        name_out[n] = '\0';
        return n;
    }
}

void ktc_core_fs_listdir_close(void* handle) {
    if (!handle) return;
    ktc_dirhandle* dh = (ktc_dirhandle*)handle;
    FindClose(dh->h);
    free(dh);
}

#else  // POSIX

void* ktc_core_fs_listdir_open(const char* path_bytes, int path_len) {
    char buf[KTC_FS_PATH_MAX];
    if (!ktc_fs_to_cstr(path_bytes, path_len, buf, sizeof(buf))) return NULL;
    return (void*)opendir(buf);
}

int ktc_core_fs_listdir_next(void* handle, char* name_out, int name_cap) {
    if (!handle || name_cap <= 0) return -1;
    DIR* d = (DIR*)handle;
    struct dirent* e;
    while ((e = readdir(d)) != NULL) {
        const char* name = e->d_name;
        if (name[0] == '.' && (name[1] == '\0' ||
            (name[1] == '.' && name[2] == '\0'))) continue;
        int n = (int)strlen(name);
        if (n >= name_cap) n = name_cap - 1;
        memcpy(name_out, name, (size_t)n);
        name_out[n] = '\0';
        return n;
    }
    return 0;
}

void ktc_core_fs_listdir_close(void* handle) {
    if (handle) closedir((DIR*)handle);
}

#endif
