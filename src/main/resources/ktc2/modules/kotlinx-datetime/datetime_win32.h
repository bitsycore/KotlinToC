/* The part of <windows.h> that kotlinx-datetime's windowsMain uses (datetime_win32.def), with its names and types. */
#ifndef KTC2_DATETIME_WIN32_H
#define KTC2_DATETIME_WIN32_H

#include <stdint.h>
#include <wchar.h>

typedef unsigned long DWORD;
typedef unsigned short WORD;
typedef unsigned char BYTE;
typedef unsigned char BOOLEAN;
typedef long LONG;
typedef wchar_t WCHAR;
typedef struct HKEY__ { int unused; } *HKEY;

typedef struct _SYSTEMTIME {
	WORD wYear;
	WORD wMonth;
	WORD wDayOfWeek;
	WORD wDay;
	WORD wHour;
	WORD wMinute;
	WORD wSecond;
	WORD wMilliseconds;
} SYSTEMTIME;

typedef struct _TIME_DYNAMIC_ZONE_INFORMATION {
	LONG Bias;
	WCHAR StandardName[32];
	SYSTEMTIME StandardDate;
	LONG StandardBias;
	WCHAR DaylightName[32];
	SYSTEMTIME DaylightDate;
	LONG DaylightBias;
	WCHAR TimeZoneKeyName[128];
	BOOLEAN DynamicDaylightTimeDisabled;
} DYNAMIC_TIME_ZONE_INFORMATION;

#define ERROR_SUCCESS 0
#define ERROR_MORE_DATA 234
#define ERROR_NO_MORE_ITEMS 259
#define KEY_READ 0x20019
#define TIME_ZONE_ID_INVALID ((DWORD)0xFFFFFFFF)
#define FORMAT_MESSAGE_ALLOCATE_BUFFER 0x00000100
#define FORMAT_MESSAGE_IGNORE_INSERTS 0x00000200
#define FORMAT_MESSAGE_FROM_SYSTEM 0x00001000

LONG __stdcall RegOpenKeyExW(HKEY key, const WCHAR* subKey, DWORD options, DWORD desired, HKEY* result);
LONG __stdcall RegEnumKeyExW(HKEY key, DWORD index, WCHAR* name, DWORD* nameLength, DWORD* reserved, WCHAR* className, DWORD* classLength, void* lastWrite);
LONG __stdcall RegQueryValueExW(HKEY key, const WCHAR* valueName, DWORD* reserved, DWORD* type, BYTE* data, DWORD* dataLength);
LONG __stdcall RegCloseKey(HKEY key);
DWORD __stdcall GetLastError(void);
DWORD __stdcall GetDynamicTimeZoneInformation(DYNAMIC_TIME_ZONE_INFORMATION* information);
DWORD __stdcall FormatMessageW(DWORD flags, const void* source, DWORD messageId, DWORD languageId, WCHAR* buffer, DWORD size, void* arguments);
void* __stdcall LocalFree(void* memory);

/* Macros of <windows.h> that Kotlin/Native gives as values: the key, and FormatMessage as a pointer to its function. */
typedef unsigned long long ULONG_PTR;
#define HKEY_LOCAL_MACHINE ((HKEY)(ULONG_PTR)((LONG)0x80000002))
#define FormatMessage FormatMessageW

#endif
