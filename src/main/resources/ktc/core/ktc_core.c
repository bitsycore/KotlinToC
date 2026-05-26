#define KTC_MEM_TRACK
#include "ktc_core.h"
#include <math.h>

// ══════════════════════════════════════════════════════════════════
// MARK: Rand
// ══════════════════════════════════════════════════════════════════

void ktc_core_srand(
    ktc_ULong* state,
    ktc_ULong* inc,
    ktc_ULong seed
) {
    *state = 0;
    *inc = (seed << 1u) | 1u;

    ktc_core_rand(state, inc);

    *state += seed;

    ktc_core_rand(state, inc);
}

ktc_UInt ktc_core_rand(
    ktc_ULong* state,
    ktc_ULong* inc
) {
    ktc_ULong oldstate = *state;

    *state = oldstate * 6364136223846793005ULL + *inc;

    ktc_UInt xorshifted =
        (ktc_UInt)(((oldstate >> 18u) ^ oldstate) >> 27u);

    ktc_UInt rot = oldstate >> 59u;

    return (xorshifted >> rot) |
           (xorshifted << ((-rot) & 31));
}

ktc_UInt ktc_core_rand_range(
    ktc_ULong* state,
    ktc_ULong* inc,
    ktc_UInt bound
) {
    if (bound == 0) return 0;

    ktc_UInt threshold = -bound % bound;

    for (;;) {
        ktc_UInt r = ktc_core_rand(state, inc);

        if (r >= threshold)
            return r % bound;
    }
}

// ══════════════════════════════════════════════════════════════════
// MARK: Time
// ══════════════════════════════════════════════════════════════════

ktc_Double ktc_core_time_seconds(void)
{
#if defined(_WIN32)
    static LARGE_INTEGER freq;
    static int initialized = 0;

    if (!initialized) {
        QueryPerformanceFrequency(&freq);
        initialized = 1;
    }

    LARGE_INTEGER counter;
    QueryPerformanceCounter(&counter);

    return (ktc_Double)counter.QuadPart / (ktc_Double)freq.QuadPart;

#else
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);

    return (ktc_Double)ts.tv_sec + (ktc_Double)ts.tv_nsec / 1e9;
#endif
}

ktc_ULong ktc_core_time_ms(void)
{
#if defined(_WIN32)
    static LARGE_INTEGER freq;
    static int initialized = 0;

    if (!initialized) {
        QueryPerformanceFrequency(&freq);
        initialized = 1;
    }

    LARGE_INTEGER counter;
    QueryPerformanceCounter(&counter);

    return (ktc_ULong)((counter.QuadPart * 1000ULL) / freq.QuadPart);

#else
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);

    return (ktc_ULong)ts.tv_sec * 1000ULL +
           (ktc_ULong)ts.tv_nsec / 1000000ULL;
#endif
}

void ktc_core_time_sleep_ms(ktc_UInt ms)
{
#if defined(_WIN32)

    Sleep(ms);

#else

    struct timespec req;
    req.tv_sec  = ms / 1000;
    req.tv_nsec = (ms % 1000) * 1000000L;
    while (nanosleep(&req, &req) == -1 && errno == EINTR) {}

#endif
}

void ktc_core_time_sleep_seconds(ktc_Double seconds)
{
#if defined(_WIN32)
    ktc_core_time_sleep_ms((ktc_UInt)(seconds * 1000.0));
#else
    struct timespec req;
    req.tv_sec  = (time_t)seconds;
    req.tv_nsec = (long)((seconds - (ktc_Double)req.tv_sec) * 1e9);
    while (nanosleep(&req, &req) == -1 && errno == EINTR) {}
#endif
}

// ══════════════════════════════════════════════════════════════════
// MARK: Stack Trace
// ══════════════════════════════════════════════════════════════════

/* Maximum number of frames to capture */
#define KTC_ST_MAX_FRAMES  32
/* Frames to skip: ktc_core_stacktrace_print itself + the generated error() wrapper */
#define KTC_ST_SKIP_FRAMES  2

#if defined(_WIN32)

#include <dbghelp.h>

/* Return just the filename from a full path (handles both / and \). */
static const char* ktc_st_basename(const char* inPath)
{
    const char* p = inPath;
    const char* last = inPath;
    while (*p) {
        if (*p == '/' || *p == '\\') last = p + 1;
        p++;
    }
    return last;
}

/* Format addr2line location "full/path/file.c:42" → "file.c:42". */
static void ktc_st_format_loc(const char* inRaw, char* outBuf, int inBufSize)
{
    /* Find the last colon that precedes digits — the line-number separator. */
    const char* vColon = NULL;
    for (const char* p = inRaw; *p; p++) {
        if (*p == ':' && *(p + 1) >= '0' && *(p + 1) <= '9')
            vColon = p;
    }
    if (!vColon) { snprintf(outBuf, inBufSize, "%s", inRaw); return; }

    char vPath[512];
    int  vN = (int)(vColon - inRaw);
    if (vN >= (int)sizeof(vPath)) vN = (int)sizeof(vPath) - 1;
    memcpy(vPath, inRaw, vN);
    vPath[vN] = '\0';
    snprintf(outBuf, inBufSize, "%s%s", ktc_st_basename(vPath), vColon);
}

#if defined(__MINGW32__) || defined(__MINGW64__)
/*
 * MinGW: DbgHelp only reads PDB symbols; MinGW/GCC produces DWARF.
 * addr2line (shipped with MinGW binutils) reads DWARF natively.
 * Compile with -g for function names and file:line to be available.
 *
 * ASLR: Windows updates the in-memory PE header's ImageBase to the runtime
 * address, so we must read ImageBase from the on-disk file to get the static
 * value needed for: static_addr = runtime_addr - runtime_base + static_base.
 */

/** Read the static ImageBase from the exe file on disk (not in-memory PE, which
 *  Windows rewrites to the runtime load address under ASLR). */
static ULONGLONG ktc_st_disk_image_base(const char* inExe)
{
    HANDLE hf = CreateFileA(inExe, GENERIC_READ, FILE_SHARE_READ,
                            NULL, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, NULL);
    if (hf == INVALID_HANDLE_VALUE) return 0;

    IMAGE_DOS_HEADER dos;
    DWORD n;
    if (!ReadFile(hf, &dos, sizeof(dos), &n, NULL) || dos.e_magic != IMAGE_DOS_SIGNATURE)
        { CloseHandle(hf); return 0; }

    SetFilePointer(hf, dos.e_lfanew, NULL, FILE_BEGIN);
    IMAGE_NT_HEADERS nt;
    if (!ReadFile(hf, &nt, sizeof(nt), &n, NULL) || nt.Signature != IMAGE_NT_SIGNATURE)
        { CloseHandle(hf); return 0; }

    CloseHandle(hf);
    return (ULONGLONG)nt.OptionalHeader.ImageBase;
}

void ktc_core_stacktrace_print(const char* message, int messageLen, const char* fileName, int fileNameLen, int line)
{
    fprintf(stderr,
        "Exception in %.*s:%d kotlin.Error: %.*s\n",
        fileNameLen, fileName, line,
        messageLen, message
    );

    void* vFrames[KTC_ST_MAX_FRAMES];
    int   vFrameCount = (int)CaptureStackBackTrace(
        KTC_ST_SKIP_FRAMES, KTC_ST_MAX_FRAMES, vFrames, NULL
    );
    if (vFrameCount == 0) return;

    char vExe[MAX_PATH];
    GetModuleFileNameA(NULL, vExe, sizeof(vExe));

    ULONGLONG runtimeBase = (ULONGLONG)GetModuleHandleA(NULL);
    ULONGLONG staticBase  = ktc_st_disk_image_base(vExe);
    if (staticBase == 0) staticBase = runtimeBase; /* fallback: no correction */

    /* imageSize from the in-memory header — still valid for range check. */
    IMAGE_DOS_HEADER* dos  = (IMAGE_DOS_HEADER*)GetModuleHandleA(NULL);
    IMAGE_NT_HEADERS* nt   = (IMAGE_NT_HEADERS*)((BYTE*)dos + dos->e_lfanew);
    ULONGLONG imageSize    = (ULONGLONG)nt->OptionalHeader.SizeOfImage;

    /* Only include frames inside the main exe; apply ASLR correction. */
    int vExeCount = 0;
    char vCmd[4096];
    int  vPos = snprintf(vCmd, sizeof(vCmd), "addr2line -f -e \"%s\"", vExe);

    for (int i = 0; i < vFrameCount; i++) {
        ULONGLONG frameAddr = (ULONGLONG)vFrames[i];
        if (frameAddr >= runtimeBase && frameAddr < runtimeBase + imageSize) {
            ULONGLONG corrected = frameAddr - runtimeBase + staticBase;
            if (vPos < (int)sizeof(vCmd) - 24)
                vPos += snprintf(vCmd + vPos, sizeof(vCmd) - vPos, " %llx",
                                 (unsigned long long)corrected);
            vExeCount++;
        }
    }

    if (vExeCount == 0) { fprintf(stderr, "\tat ?...(Unknown Source)\n"); return; }

    if (vPos < (int)sizeof(vCmd) - 8)
        strncat(vCmd, " 2>NUL", sizeof(vCmd) - (size_t)vPos - 1);

    /* _popen/_pclose must be used explicitly: popen is hidden by __STRICT_ANSI__
     * which -std=c11 activates. */
    FILE* vFp = _popen(vCmd, "r");
    if (!vFp) { fprintf(stderr, "\tat ?...(Unknown Source)\n"); return; }

    char vFunc[256], vFileLine[512], vLoc[256];
    for (int i = 0; i < vExeCount; i++) {
        if (!fgets(vFunc,     sizeof(vFunc),     vFp)) break;
        if (!fgets(vFileLine, sizeof(vFileLine), vFp)) break;
        vFunc[strcspn(vFunc, "\r\n")]         = '\0';
        vFileLine[strcspn(vFileLine, "\r\n")] = '\0';

        /* Skip frames with no symbol info — they are CRT/startup internals. */
        if (vFunc[0] == '?' && vFunc[1] == '?') continue;

        if (vFileLine[0] == '?' && vFileLine[1] == '?')
            fprintf(stderr, "\tat %s(Unknown Source)\n", vFunc);
        else {
            ktc_st_format_loc(vFileLine, vLoc, sizeof(vLoc));
            fprintf(stderr, "\tat %s(%s)\n", vFunc, vLoc);
        }
    }
    _pclose(vFp);
}

#else
/*
 * MSVC: DbgHelp reads PDB symbols. Load dynamically so no .lib is needed
 * at link time — dbghelp.dll is always present on Windows.
 */
typedef BOOL (WINAPI *PFN_SymInitialize)(HANDLE, PCSTR, BOOL);
typedef BOOL (WINAPI *PFN_SymFromAddr)(HANDLE, DWORD64, PDWORD64, PSYMBOL_INFO);
typedef BOOL (WINAPI *PFN_SymGetLineFromAddr64)(HANDLE, DWORD64, PDWORD, PIMAGEHLP_LINE64);
typedef BOOL (WINAPI *PFN_SymCleanup)(HANDLE);

void ktc_core_stacktrace_print(const char* message, int messageLen, const char* fileName, int fileNameLen, int line)
{
    fprintf(stderr,
        "Exception in %.*s:%d kotlin.Error: %.*s\n",
        fileNameLen, fileName, line,
        messageLen, message
    );

    void*  vFrames[KTC_ST_MAX_FRAMES];
    USHORT vFrameCount = CaptureStackBackTrace(
        KTC_ST_SKIP_FRAMES, KTC_ST_MAX_FRAMES, vFrames, NULL
    );

    HMODULE vDbgHelp = LoadLibraryA("dbghelp.dll");
    if (!vDbgHelp) {
        for (USHORT i = 0; i < vFrameCount; i++)
            fprintf(stderr, "\tat ?...(Unknown Source)\n");
        return;
    }

    PFN_SymInitialize        vSymInit  = (PFN_SymInitialize)       GetProcAddress(vDbgHelp, "SymInitialize");
    PFN_SymFromAddr          vSymFrom  = (PFN_SymFromAddr)         GetProcAddress(vDbgHelp, "SymFromAddr");
    PFN_SymGetLineFromAddr64 vSymLine  = (PFN_SymGetLineFromAddr64)GetProcAddress(vDbgHelp, "SymGetLineFromAddr64");
    PFN_SymCleanup           vSymClean = (PFN_SymCleanup)          GetProcAddress(vDbgHelp, "SymCleanup");

    HANDLE vProcess = GetCurrentProcess();
    if (vSymInit) vSymInit(vProcess, NULL, TRUE);

    char vSymBuf[sizeof(SYMBOL_INFO) + 255];
    SYMBOL_INFO* vSym = (SYMBOL_INFO*)vSymBuf;
    memset(vSymBuf, 0, sizeof(vSymBuf));
    vSym->SizeOfStruct = sizeof(SYMBOL_INFO);
    vSym->MaxNameLen   = 255;

    IMAGEHLP_LINE64 vLine;
    memset(&vLine, 0, sizeof(vLine));
    vLine.SizeOfStruct = sizeof(IMAGEHLP_LINE64);

    for (USHORT i = 0; i < vFrameCount; i++) {
        DWORD64 vDisp64 = 0;
        DWORD   vDisp32 = 0;
        BOOL vHasSym  = vSymFrom && vSymFrom(vProcess, (DWORD64)vFrames[i], &vDisp64, vSym);
        BOOL vHasLine = vSymLine  && vSymLine(vProcess, (DWORD64)vFrames[i], &vDisp32, &vLine);

        const char* vFunc = (vHasSym && vSym->Name[0]) ? vSym->Name : "??";
        if (vHasLine)
            fprintf(stderr, "\tat %s(%s:%lu)\n", vFunc, ktc_st_basename(vLine.FileName), vLine.LineNumber);
        else
            fprintf(stderr, "\tat %s(Unknown Source)\n", vFunc);
    }

    if (vSymClean) vSymClean(vProcess);
    FreeLibrary(vDbgHelp);
}

#endif /* MinGW vs MSVC */

#elif defined(__GNUC__) || defined(__clang__)
/* POSIX: Linux and macOS.
 * Requires -rdynamic (Linux) or default export policy (macOS) for symbol names.
 * Requires -ldl on Linux for dladdr(). */

#include <execinfo.h>
#include <dlfcn.h>

void ktc_core_stacktrace_print(const char* message, int messageLen, const char* fileName, int fileNameLen, int line)
{
    fprintf(stderr,
        "Exception in %.*s:%d kotlin.Error: %.*s\n",
        fileNameLen, fileName, line,
        messageLen, message
    );

    void* frames[KTC_ST_MAX_FRAMES];
    int   frame_count = backtrace(frames, KTC_ST_MAX_FRAMES);
    /* backtrace_symbols is a fallback when dladdr finds nothing */
    char** syms = backtrace_symbols(frames, frame_count);

    int start = KTC_ST_SKIP_FRAMES;
    if (start >= frame_count) start = 0;

    for (int i = start; i < frame_count; i++) {
        Dl_info info;
        /* dladdr gives the cleanest function name on both Linux and macOS */
        if (dladdr(frames[i], &info) && info.dli_sname && info.dli_sname[0])
            fprintf(stderr, "\tat %s(Unknown Source)\n", info.dli_sname);
        else if (syms && syms[i])
            fprintf(stderr, "\tat %s\n", syms[i]);
        else
            fprintf(stderr, "\tat ?...(Unknown Source)\n");
    }

    if (syms) free(syms);
}

#else

/* Unsupported platform: print message only, no frames. */
void ktc_core_stacktrace_print(const char* message, int messageLen, const char* fileName, int fileNameLen, int line) {
    fprintf(stderr,
        "Exception in %.*s:%d kotlin.Error: %.*s\n"
        "\t(c stack trace unavailable on this platform)\n",
        fileNameLen, fileName, line,
        messageLen, message
    );
}

#endif

// ══════════════════════════════════════════════════════════════════
// MARK: String
// ══════════════════════════════════════════════════════════════════

ktc_String ktc_core_string_cat(
    ktc_Char* buf,
    ktc_Int   bufsz,
    ktc_String a,
    ktc_String b
) {
    bufsz--;
    ktc_Int vALen = a.len < bufsz     ? a.len     : bufsz;
    ktc_Int vBLen = b.len < bufsz - vALen ? b.len : bufsz - vALen;
    memcpy(buf,          a.ptr, (size_t)vALen);
    memcpy(buf + vALen,  b.ptr, (size_t)vBLen);
    buf[vALen + vBLen] = '\0';
    return (ktc_String){buf, vALen + vBLen};
}

static ktc_Int ktc_core_f2s(ktc_Float  v, ktc_Char* buf, ktc_Int bufsz); // defined in Conversion
static ktc_Int ktc_core_d2s(ktc_Double v, ktc_Char* buf, ktc_Int bufsz); // defined in Conversion

// ══════════════════════════════════════════════════════════════════
// MARK: StrBuf
// ══════════════════════════════════════════════════════════════════

void ktc_core_sb_append_str(ktc_StrBuf* sb, ktc_String s) {
    if (!sb->ptr) { sb->len += s.len; return; }
    ktc_Int vRoom = sb->cap - sb->len;
    ktc_Int vCopy = s.len < vRoom ? s.len : vRoom;
    memcpy(sb->ptr + sb->len, s.ptr, (size_t)vCopy);
    sb->len += vCopy;
}

void ktc_core_sb_append_cstr(ktc_StrBuf* sb, const ktc_Char* s) {
    ktc_core_sb_append_str(sb, (ktc_String){s, (ktc_Int)strlen(s)});
}

void ktc_core_sb_append_int(ktc_StrBuf* sb, ktc_Int v) {
    ktc_Char vBuf[24]; // enough for any int32
    ktc_Int  vLen = snprintf(vBuf, sizeof(vBuf), "%" PRId32, v);
    ktc_core_sb_append_str(sb, (ktc_String){vBuf, vLen});
}

void ktc_core_sb_append_long(ktc_StrBuf* sb, ktc_Long v) {
    ktc_Char vBuf[24]; // enough for any int64
    ktc_Int  vLen = snprintf(vBuf, sizeof(vBuf), "%" PRId64, v);
    ktc_core_sb_append_str(sb, (ktc_String){vBuf, vLen});
}

void ktc_core_sb_append_double(ktc_StrBuf* sb, ktc_Double v) {
    ktc_Char vBuf[32];
    ktc_Int  vLen = ktc_core_d2s(v, vBuf, (ktc_Int)sizeof(vBuf));
    ktc_core_sb_append_str(sb, (ktc_String){vBuf, vLen});
}

void ktc_core_sb_append_byte(ktc_StrBuf* sb, ktc_Byte v) {
    ktc_core_sb_append_int(sb, (ktc_Int)v);
}

void ktc_core_sb_append_short(ktc_StrBuf* sb, ktc_Short v) {
    ktc_core_sb_append_int(sb, (ktc_Int)v);
}

void ktc_core_sb_append_ubyte(ktc_StrBuf* sb, ktc_UByte v) {
    ktc_Char vBuf[8];
    ktc_Int  vLen = snprintf(vBuf, sizeof(vBuf), "%" PRIu8, v);
    ktc_core_sb_append_str(sb, (ktc_String){vBuf, vLen});
}

void ktc_core_sb_append_ushort(ktc_StrBuf* sb, ktc_UShort v) {
    ktc_Char vBuf[8];
    ktc_Int  vLen = snprintf(vBuf, sizeof(vBuf), "%" PRIu16, v);
    ktc_core_sb_append_str(sb, (ktc_String){vBuf, vLen});
}

void ktc_core_sb_append_uint(ktc_StrBuf* sb, ktc_UInt v) {
    ktc_Char vBuf[16];
    ktc_Int  vLen = snprintf(vBuf, sizeof(vBuf), "%" PRIu32, v);
    ktc_core_sb_append_str(sb, (ktc_String){vBuf, vLen});
}

void ktc_core_sb_append_ulong(ktc_StrBuf* sb, ktc_ULong v) {
    ktc_Char vBuf[24];
    ktc_Int  vLen = snprintf(vBuf, sizeof(vBuf), "%" PRIu64, v);
    ktc_core_sb_append_str(sb, (ktc_String){vBuf, vLen});
}

void ktc_core_sb_append_rune(ktc_StrBuf* sb, ktc_Rune r) {
    ktc_Char vBuf[4]; // max 4 bytes for a UTF-8 code point
    ktc_Int  vLen = ktc_core_utf8_encode(r, vBuf);
    ktc_core_sb_append_str(sb, (ktc_String){vBuf, vLen});
}

// ══════════════════════════════════════════════════════════════════
// MARK: UTF-8
// ══════════════════════════════════════════════════════════════════

ktc_Rune ktc_core_utf8_decode(const ktc_Char* p, ktc_Int* byteLen) {
    unsigned char vC = (unsigned char)*p;
    if (vC < 0x80) { *byteLen = 1; return (ktc_Rune)vC; }
    if (vC < 0xC0) { *byteLen = 1; return 0xFFFD; } // continuation byte, invalid start
    if (vC < 0xE0) {
        *byteLen = 2;
        if ((unsigned char)p[1] < 0x80) return 0xFFFD;
        return ((ktc_Rune)(vC & 0x1F) << 6) | (p[1] & 0x3F);
    }
    if (vC < 0xF0) {
        *byteLen = 3;
        if ((unsigned char)p[1] < 0x80 || (unsigned char)p[2] < 0x80) return 0xFFFD;
        return ((ktc_Rune)(vC & 0x0F) << 12) | ((p[1] & 0x3F) << 6) | (p[2] & 0x3F);
    }
    *byteLen = 4;
    if ((unsigned char)p[1] < 0x80 || (unsigned char)p[2] < 0x80 || (unsigned char)p[3] < 0x80)
        return 0xFFFD;
    return ((ktc_Rune)(vC & 0x07) << 18) | ((p[1] & 0x3F) << 12) |
           ((p[2] & 0x3F) <<  6) | (p[3] & 0x3F);
}

ktc_Int ktc_core_utf8_encode(ktc_Rune r, ktc_Char* out) {
    if (r < 0x80)    { out[0] = (ktc_Char)r; return 1; }
    if (r < 0x800)   { out[0] = (ktc_Char)(0xC0 | (r >> 6));
                        out[1] = (ktc_Char)(0x80 | (r & 0x3F)); return 2; }
    if (r < 0x10000) { out[0] = (ktc_Char)(0xE0 | (r >> 12));
                        out[1] = (ktc_Char)(0x80 | ((r >> 6) & 0x3F));
                        out[2] = (ktc_Char)(0x80 | (r & 0x3F)); return 3; }
    out[0] = (ktc_Char)(0xF0 | (r >> 18));
    out[1] = (ktc_Char)(0x80 | ((r >> 12) & 0x3F));
    out[2] = (ktc_Char)(0x80 | ((r >>  6) & 0x3F));
    out[3] = (ktc_Char)(0x80 | (r & 0x3F));
    return 4;
}

ktc_Int ktc_core_str_runeLen(ktc_String s) {
    ktc_Int vCount = 0;
    ktc_Int vI     = 0;
    while (vI < s.len) {
        ktc_Int vBLen;
        ktc_core_utf8_decode(s.ptr + vI, &vBLen);
        vI += vBLen;
        vCount++;
    }
    return vCount;
}

// ══════════════════════════════════════════════════════════════════
// MARK: Conversion
// ══════════════════════════════════════════════════════════════════

ktc_String ktc_core_int_to_string(ktc_Char* buf, ktc_Int bufsz, ktc_Int v) {
    ktc_Int vLen = snprintf(buf, (size_t)bufsz, "%" PRId32, v);
    if (vLen < 0 || vLen >= bufsz) vLen = bufsz - 1;
    return (ktc_String){buf, vLen};
}

ktc_String ktc_core_long_to_string(ktc_Char* buf, ktc_Int bufsz, ktc_Long v) {
    ktc_Int vLen = snprintf(buf, (size_t)bufsz, "%" PRId64, v);
    if (vLen < 0 || vLen >= bufsz) vLen = bufsz - 1;
    return (ktc_String){buf, vLen};
}

/* Kotlin-like float formatting — same rules as double but uses float precision
 * (up to 9 significant digits) and strtof for round-trip checking. */
static ktc_Int ktc_core_f2s(ktc_Float v, ktc_Char* buf, ktc_Int bufsz) {
    if (isnan(v))   return snprintf(buf, (size_t)bufsz, "NaN");
    if (isinf(v))   return snprintf(buf, (size_t)bufsz, v > 0 ? "Infinity" : "-Infinity");
    if (v == 0.0f) {
        ktc_UInt vBits;
        memcpy(&vBits, &v, sizeof(vBits));
        return snprintf(buf, (size_t)bufsz, (vBits >> 31) ? "-0.0" : "0.0");
    }

    // Find shortest precision that round-trips through strtof
    // Format as double (superset) so snprintf doesn't lose bits, then check via strtof
    ktc_Char vTmp[64];
    ktc_Int  vPrec;
    for (vPrec = 1; vPrec <= 9; vPrec++) {
        snprintf(vTmp, sizeof(vTmp), "%.*e", vPrec - 1, (double)v);
        if (strtof(vTmp, NULL) == v) break;
    }

    ktc_Char* vEp  = strchr(vTmp, 'e');
    ktc_Int   vExp = atoi(vEp + 1);
    *vEp = '\0';

    ktc_Char* vDot = strchr(vTmp, '.');
    if (vDot) {
        ktc_Char* vEnd = vTmp + strlen(vTmp) - 1;
        while (vEnd > vDot + 1 && *vEnd == '0') vEnd--;
        *(vEnd + 1) = '\0';
    } else {
        size_t vTl = strlen(vTmp);
        vTmp[vTl] = '.'; vTmp[vTl + 1] = '0'; vTmp[vTl + 2] = '\0';
    }

    if (vExp >= 7 || vExp <= -4) {
        ktc_Int vN = snprintf(buf, (size_t)bufsz, "%sE%d", vTmp, vExp);
        return vN < bufsz ? vN : bufsz - 1;
    }

    ktc_Int vDec = vPrec - (vExp + 1);
    if (vDec < 1) vDec = 1;
    snprintf(vTmp, sizeof(vTmp), "%.*f", (int)vDec, (double)v);

    vDot = strchr(vTmp, '.');
    if (vDot) {
        ktc_Char* vEnd = vTmp + strlen(vTmp) - 1;
        while (vEnd > vDot + 1 && *vEnd == '0') vEnd--;
        *(vEnd + 1) = '\0';
    }

    ktc_Int vN = snprintf(buf, (size_t)bufsz, "%s", vTmp);
    return vN < bufsz ? vN : bufsz - 1;
}

ktc_String ktc_core_float_to_string(ktc_Char* buf, ktc_Int bufsz, ktc_Float v) {
    ktc_Int vLen = ktc_core_f2s(v, buf, bufsz);
    return (ktc_String){buf, vLen};
}

void ktc_core_sb_append_float(ktc_StrBuf* sb, ktc_Float v) {
    ktc_Char vBuf[24];
    ktc_Int  vLen = ktc_core_f2s(v, vBuf, (ktc_Int)sizeof(vBuf));
    ktc_core_sb_append_str(sb, (ktc_String){vBuf, vLen});
}

/** Kotlin-like double formatting:
 * - shortest representation that round-trips
 * - always has a decimal digit (1.0 not 1)
 * - strips trailing zeros (3.5 not 3.500000)
 * - E-notation (uppercase, no + sign) when exponent >= 7 or <= -4
 * - NaN, Infinity, -Infinity match Kotlin names
 * - -0.0 preserved */
static ktc_Int ktc_core_d2s(ktc_Double v, ktc_Char* buf, ktc_Int bufsz) {
    if (isnan(v))
        return snprintf(buf, (size_t)bufsz, "NaN");
    if (isinf(v))
        return snprintf(buf, (size_t)bufsz, v > 0 ? "Infinity" : "-Infinity");
    if (v == 0.0) {
        // Detect -0.0 via sign bit without <math.h> signbit dependency conflicts
        ktc_ULong vBits;
        memcpy(&vBits, &v, sizeof(vBits));
        return snprintf(buf, (size_t)bufsz, (vBits >> 63) ? "-0.0" : "0.0");
    }

    // Find shortest precision that round-trips through %e / strtod
    ktc_Char vTmp[64];
    ktc_Int  vPrec;
    for (vPrec = 1; vPrec <= 17; vPrec++) {
        snprintf(vTmp, sizeof(vTmp), "%.*e", vPrec - 1, v);
        if (strtod(vTmp, NULL) == v) break;
    }

    // Parse exponent from the %e result, then split mantissa / exp
    ktc_Char* vEp  = strchr(vTmp, 'e');
    ktc_Int   vExp = atoi(vEp + 1);
    *vEp = '\0';

    // Strip trailing zeros from mantissa, keep at least one decimal digit
    ktc_Char* vDot = strchr(vTmp, '.');
    if (vDot) {
        ktc_Char* vEnd = vTmp + strlen(vTmp) - 1;
        while (vEnd > vDot + 1 && *vEnd == '0') vEnd--;
        *(vEnd + 1) = '\0';
    } else {
        // No decimal point in mantissa — append .0
        size_t vTl = strlen(vTmp);
        vTmp[vTl] = '.'; vTmp[vTl + 1] = '0'; vTmp[vTl + 2] = '\0';
    }

    // Kotlin uses E-notation when exp >= 7 or <= -4
    if (vExp >= 7 || vExp <= -4) {
        ktc_Int vN = snprintf(buf, (size_t)bufsz, "%sE%d", vTmp, vExp);
        return vN < bufsz ? vN : bufsz - 1;
    }

    // Fixed notation — re-format with %f using just enough decimal places
    ktc_Int vDec = vPrec - (vExp + 1);
    if (vDec < 1) vDec = 1;
    snprintf(vTmp, sizeof(vTmp), "%.*f", (int)vDec, v);

    // Strip trailing zeros, keep at least one decimal digit
    vDot = strchr(vTmp, '.');
    if (vDot) {
        ktc_Char* vEnd = vTmp + strlen(vTmp) - 1;
        while (vEnd > vDot + 1 && *vEnd == '0') vEnd--;
        *(vEnd + 1) = '\0';
    }

    ktc_Int vN = snprintf(buf, (size_t)bufsz, "%s", vTmp);
    return vN < bufsz ? vN : bufsz - 1;
}

ktc_String ktc_core_double_to_string(ktc_Char* buf, ktc_Int bufsz, ktc_Double v) {
    ktc_Int vLen = ktc_core_d2s(v, buf, bufsz);
    return (ktc_String){buf, vLen};
}

// ══════════════════════════════════════════════════════════════════
// MARK: Number Parsing
// ══════════════════════════════════════════════════════════════════

ktc_Int ktc_core_str_toInt(ktc_String s) {
    ktc_Int vOut = 0;
    ktc_core_str_toIntOrNull(s, &vOut);
    return vOut;
}

ktc_Long ktc_core_str_toLong(ktc_String s) {
    ktc_Long vOut = 0;
    ktc_core_str_toLongOrNull(s, &vOut);
    return vOut;
}

ktc_Double ktc_core_str_toDouble(ktc_String s) {
    ktc_Double vOut = 0.0;
    ktc_core_str_toDoubleOrNull(s, &vOut);
    return vOut;
}

ktc_Bool ktc_core_str_toIntOrNull(ktc_String s, ktc_Int* out) {
    if (s.len == 0) return false;
    // strtol needs a null-terminated string; copy if needed
    ktc_Char  vBuf[24];
    ktc_Int   vLen = s.len < 23 ? s.len : 23;
    memcpy(vBuf, s.ptr, (size_t)vLen);
    vBuf[vLen] = '\0';
    ktc_Char* vEnd;
    errno      = 0;
    long vVal  = strtol(vBuf, &vEnd, 10);
    if (vEnd == vBuf || *vEnd != '\0' || errno == ERANGE) return false;
    *out = (ktc_Int)vVal;
    return true;
}

ktc_Bool ktc_core_str_toLongOrNull(ktc_String s, ktc_Long* out) {
    if (s.len == 0) return false;
    ktc_Char  vBuf[24];
    ktc_Int   vLen = s.len < 23 ? s.len : 23;
    memcpy(vBuf, s.ptr, (size_t)vLen);
    vBuf[vLen] = '\0';
    ktc_Char* vEnd;
    errno          = 0;
    long long vVal = strtoll(vBuf, &vEnd, 10);
    if (vEnd == vBuf || *vEnd != '\0' || errno == ERANGE) return false;
    *out = (ktc_Long)vVal;
    return true;
}

ktc_Bool ktc_core_str_toDoubleOrNull(ktc_String s, ktc_Double* out) {
    if (s.len == 0) return false;
    ktc_Char vBuf[64];
    ktc_Int  vLen = s.len < 63 ? s.len : 63;
    memcpy(vBuf, s.ptr, (size_t)vLen);
    vBuf[vLen] = '\0';
    ktc_Char* vEnd;
    errno            = 0;
    double    vVal   = strtod(vBuf, &vEnd);
    if (vEnd == vBuf || *vEnd != '\0' || errno == ERANGE) return false;
    *out = (ktc_Double)vVal;
    return true;
}

// ══════════════════════════════════════════════════════════════════
// MARK: Memory Tracking (shared across all translation units)
// ══════════════════════════════════════════════════════════════════

#ifdef KTC_MEM_TRACK

#ifndef KTC_MEM_MAX
#define KTC_MEM_MAX 8192
#endif

typedef struct {
    void*           ptr;
    ktc_ULong       size;
    const ktc_Char* file;
    ktc_Int         line;
    ktc_Bool        active;
} ktc_MemRecord;

static ktc_MemRecord ktc_core_mem_records[KTC_MEM_MAX];
static ktc_Int   ktc_core_mem_count  = 0;
static ktc_Int   ktc_core_mem_allocs = 0;
static ktc_Int   ktc_core_mem_frees  = 0;
static ktc_ULong ktc_core_mem_bytes  = 0;

static void ktc_core_mem_record_alloc(void* p, ktc_ULong sz, const ktc_Char* file, ktc_Int line) {
    ktc_core_mem_allocs++;
    ktc_core_mem_bytes += sz;
    if (ktc_core_mem_count < KTC_MEM_MAX)
        ktc_core_mem_records[ktc_core_mem_count++] = (ktc_MemRecord){p, sz, file, line, true};
}

void* ktc_core_malloc(ktc_ULong sz, const ktc_Char* file, ktc_Int line) {
    void* p = (malloc)(sz);
    ktc_core_mem_record_alloc(p, sz, file, line);
    return p;
}

void* ktc_core_calloc(ktc_ULong n, ktc_ULong sz, const ktc_Char* file, ktc_Int line) {
    void* p = (calloc)(n, sz);
    ktc_core_mem_record_alloc(p, n * sz, file, line);
    return p;
}

void* ktc_core_realloc(void* old, ktc_ULong sz, const ktc_Char* file, ktc_Int line) {
    for (ktc_Int i = ktc_core_mem_count - 1; i >= 0; i--) {
        if (ktc_core_mem_records[i].ptr == old && ktc_core_mem_records[i].active) {
            ktc_core_mem_records[i].active = false;
            ktc_core_mem_bytes -= ktc_core_mem_records[i].size;
            ktc_core_mem_frees++;
            break;
        }
    }
    void* p = (realloc)(old, sz);
    ktc_core_mem_record_alloc(p, sz, file, line);
    return p;
}

void ktc_core_free(void* p, const ktc_Char* file, ktc_Int line) {
    if (!p) return;
    for (ktc_Int i = ktc_core_mem_count - 1; i >= 0; i--) {
        if (ktc_core_mem_records[i].ptr == p && ktc_core_mem_records[i].active) {
            ktc_core_mem_records[i].active = false;
            ktc_core_mem_bytes -= ktc_core_mem_records[i].size;
            ktc_core_mem_frees++;
            (free)(p);
            return;
        }
    }
    printf("[mem] WARNING: free(%p) unknown pointer at %s:%d\n", p, file, line);
    ktc_core_mem_frees++;
    (free)(p);
}

void ktc_core_mem_report(void) {
    ktc_Int   leaks        = 0;
    ktc_ULong leaked_bytes = 0;
    printf("\n====== ktc memory report ======\n");
    printf("  total allocs : %d\n", ktc_core_mem_allocs);
    printf("  total frees  : %d\n", ktc_core_mem_frees);
    printf("  balance      : %d\n", ktc_core_mem_allocs - ktc_core_mem_frees);
    for (ktc_Int i = 0; i < ktc_core_mem_count; i++) {
        if (ktc_core_mem_records[i].active) {
            if (leaks == 0) printf("\n  LEAKS:\n");
            printf("    %p  %6zu bytes  %s:%d\n",
                ktc_core_mem_records[i].ptr, (size_t)ktc_core_mem_records[i].size,
                ktc_core_mem_records[i].file, ktc_core_mem_records[i].line);
            leaks++;
            leaked_bytes += ktc_core_mem_records[i].size;
        }
    }
    if (leaks == 0)
        printf("  status       : OK, no leaks\n");
    else
        printf("  leaked       : %d allocs, %zu bytes\n", leaks, (size_t)leaked_bytes);
    printf("===============================\n");
}

#else

void* ktc_core_malloc(ktc_ULong sz, const ktc_Char* file, ktc_Int line) {
    (void)file; (void)line;
    return (malloc)(sz);
}

void ktc_core_free(void* p, const ktc_Char* file, ktc_Int line) {
    (void)file; (void)line;
    (free)(p);
}

void* ktc_core_realloc(void* old, ktc_ULong sz, const ktc_Char* file, ktc_Int line) {
    (void)file; (void)line;
    return (realloc)(old, sz);
}

void* ktc_core_calloc(ktc_ULong n, ktc_ULong sz, const ktc_Char* file, ktc_Int line) {
    (void)file; (void)line;
    void* p = (calloc)(n, sz);
    return p;
}

void ktc_core_mem_report(void) {}

#endif /* KTC_MEM_TRACK */

/* ════════════════════════════════════════════════════════════════════
 * MARK: Bounds check (--check-bounds runtime helper)
 * ════════════════════════════════════════════════════════════════════
 * Inserted around each array/string subscript when --check-bounds is on.
 * On out-of-range access, prints a Kotlin-style stack trace then exits.
 * Returns the index unchanged on success so the surrounding C expression
 * `(arr).ptr[ktc_core_bounds_check(...)]` stays a clean rvalue. */
ktc_Int ktc_core_bounds_check(const char* fileName, int fileNameLen, int line, ktc_Int idx, ktc_Int len)
{
    if (idx >= 0 && idx < len) return idx;
    char vMsg[128];
    int  vMsgLen = snprintf(vMsg, sizeof vMsg,
        "ArrayIndexOutOfBoundsException: index %lld out of bounds for length %lld",
        (long long)idx, (long long)len);
    if (vMsgLen < 0) vMsgLen = 0;
    if (vMsgLen > (int)sizeof vMsg - 1) vMsgLen = (int)sizeof vMsg - 1;
    ktc_core_stacktrace_print(vMsg, vMsgLen, fileName, fileNameLen, line);
    exit(1);
}

/* Null-pointer guard called before every `p.refValue` / `p.refValue = x`
 * lowering when --check-null is on. Returns silently on a valid pointer,
 * prints a stack trace and exits on NULL. */
void ktc_core_null_check(const void* p, const char* fileName, int fileNameLen, int line)
{
    if (p) return;
    const char* vMsg = "NullPointerException: cannot dereference a null Ref<T?>";
    ktc_core_stacktrace_print(vMsg, (int)strlen(vMsg), fileName, fileNameLen, line);
    exit(1);
}

// ══════════════════════════════════════════════════════════════════
// MARK: Initialization
// ══════════════════════════════════════════════════════════════════

static void ktc_console_init(void) {
#if defined(_WIN32)
    SetConsoleOutputCP(CP_UTF8);
    SetConsoleCP(CP_UTF8);
#endif
}

void ktc_core_mainInit(void) {
    ktc_console_init();
}