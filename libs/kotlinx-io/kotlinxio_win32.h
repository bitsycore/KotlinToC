/* The few functions of Windows kotlinxio.h calls, declared here rather than by <windows.h>. */
#ifndef KOTLINXIO_WIN32_H
#define KOTLINXIO_WIN32_H

#include <direct.h>
#include <stdlib.h>

#ifndef _WINDOWS_
int __stdcall MoveFileExA(const char* existing, const char* replacement, unsigned long flags);
unsigned long __stdcall GetLastError(void);
unsigned long __stdcall GetTempPathA(unsigned long length, char* buffer);
#endif

#endif
