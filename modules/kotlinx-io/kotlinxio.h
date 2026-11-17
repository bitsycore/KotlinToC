/* What ktc2's part of kotlinx-io needs of the system, the same on every one (kotlinxio.def). */
#ifndef KOTLINXIO_H
#define KOTLINXIO_H

#include <errno.h>
#include <fcntl.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#ifdef _WIN32
#include "kotlinxio_win32.h"
#else
#include <stdio.h>
#include <unistd.h>
#endif

/** Moves the file `from` to `to`, replacing what is there: 0, or the system's code of the error. */
static inline int kotlinxio_move(const char* from, const char* to)
{
#ifdef _WIN32
	return MoveFileExA(from, to, 1 /* MOVEFILE_REPLACE_EXISTING */) ? 0 : (int)GetLastError();
#else
	return rename(from, to) == 0 ? 0 : errno;
#endif
}

/** Makes the directory `path`, open to all (the umask applies): 0, or errno. */
static inline int kotlinxio_mkdir(const char* path)
{
#ifdef _WIN32
	return _mkdir(path) == 0 ? 0 : errno;
#else
	return mkdir(path, 0777) == 0 ? 0 : errno;
#endif
}

/** The absolute form of `path`, from malloc (the caller frees it); NULL when there is none. */
static inline char* kotlinxio_realpath(const char* path)
{
#ifdef _WIN32
	return _fullpath(NULL, path, 0);
#else
	return realpath(path, NULL);
#endif
}

/** The directory of temporary files, from malloc (the caller frees it), as kotlinx-io finds it on each system. */
static inline char* kotlinxio_temporary_directory(void)
{
	const char* given = getenv("TMPDIR");
	if (given && *given) return strdup(given);
#ifdef _WIN32
	char* buffer = malloc(32768);
	unsigned long length = buffer ? GetTempPathA(32768, buffer) : 0;
	if (length == 0 || length >= 32768) {
		free(buffer);
		return NULL;
	}
	return buffer;
#else
	given = getenv("TMP");
	return strdup(given && *given ? given : "/tmp");
#endif
}

/** What every file is opened with besides: not inherited by a child process, and on Windows as bytes. */
static inline int kotlinxio_open_flags(void)
{
#ifdef _WIN32
	return _O_BINARY | _O_NOINHERIT;
#else
	return O_CLOEXEC;
#endif
}

/** 1 on Windows, 0 elsewhere. */
static inline int kotlinxio_is_windows(void)
{
#ifdef _WIN32
	return 1;
#else
	return 0;
#endif
}

#endif
