#ifndef WIDECHARS_H
#define WIDECHARS_H

#include <stdint.h>

/* As <windows.h> declares them, on every system. */
typedef unsigned short WCHAR;
/* 32 bits, as Windows' unsigned long is (64 on Linux and macOS). */
typedef uint32_t DWORD;
typedef struct HANDLE__ { int unused; } *HANDLE;

#define WIDE_INVALID ((DWORD)0xFFFFFFFF)
#define WIDE_ROOT ((HANDLE)(uintptr_t)0x80000002)
#define WideLength wide_length

/** The number of 16-bit units before the terminating zero. */
static inline DWORD wide_length(const WCHAR* text)
{
	DWORD count = 0;
	while (text[count] != 0) count++;
	return count;
}

/** Writes "héllo" in UTF-16 into [buffer] (room for [size] units): the units written. */
static inline DWORD wide_hello(WCHAR* buffer, DWORD size)
{
	static const WCHAR hello[] = { 'h', 0xE9, 'l', 'l', 'o', 0 };
	DWORD i = 0;
	for (; i + 1 < size && hello[i] != 0; i++) buffer[i] = hello[i];
	buffer[i] = 0;
	return i;
}

static inline int wide_is_root(HANDLE handle)
{
	return handle == WIDE_ROOT;
}

#endif
