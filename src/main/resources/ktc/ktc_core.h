//ktc_core.h — KotlinToC compiler intrinsics
#pragma once

#include "ktc_mangle.h"
#include "ktc_types.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdbool.h>
#include <stdint.h>
#include <inttypes.h>
#include <errno.h>
#if defined(_WIN32)
    #include <windows.h>
#endif
#include <time.h>

// ══════════════════════════════════════════════════════════════════
// MARK: Types
// ══════════════════════════════════════════════════════════════════

typedef int8_t   ktc_Byte;
typedef int16_t  ktc_Short;
typedef int32_t  ktc_Int;
typedef int64_t  ktc_Long;
typedef float    ktc_Float;
typedef double   ktc_Double;
typedef bool     ktc_Bool;
typedef char     ktc_Char;
typedef int32_t  ktc_Rune;    // Unicode code point (0x0000..0x10FFFF)
typedef uint8_t  ktc_UByte;
typedef uint16_t ktc_UShort;
typedef uint32_t ktc_UInt;
typedef uint64_t ktc_ULong;

// ══════════════════════════════════════════════════════════════════
// MARK: Initialization
// ══════════════════════════════════════════════════════════════════

void ktc_core_mainInit(void);

// ══════════════════════════════════════════════════════════════════
// MARK: Stack Trace
// ══════════════════════════════════════════════════════════════════

/** Print a Java-style stack trace to stderr, then return.
 * The caller (error()) exits afterwards.
 * Compile with -g (MinGW/GCC) for function names and file:line. */
void ktc_core_stacktrace_print(const char* message, int message_len);

// ══════════════════════════════════════════════════════════════════
// MARK: Time
// ══════════════════════════════════════════════════════════════════

ktc_ULong  ktc_core_time_ms(void);
ktc_Double ktc_core_time_seconds(void);
void       ktc_core_time_sleep_ms(ktc_UInt ms);
void       ktc_core_time_sleep_seconds(ktc_Double seconds);

// ══════════════════════════════════════════════════════════════════
// MARK: Rand
// ══════════════════════════════════════════════════════════════════

#define KTC_RAND_MAX UINT32_MAX

void     ktc_core_srand(ktc_ULong* state, ktc_ULong* inc, ktc_ULong seed);
ktc_UInt ktc_core_rand(ktc_ULong* state, ktc_ULong* inc);
ktc_UInt ktc_core_rand_range(ktc_ULong* state, ktc_ULong* inc, ktc_UInt bound);

// ══════════════════════════════════════════════════════════════════
// MARK: Compat
// ══════════════════════════════════════════════════════════════════

#if defined(_MSC_VER)
    #include <malloc.h>
    #define ktc_core_alloca(size) _alloca(size)
#elif defined(__clang__) || defined(__GNUC__)
    #define ktc_core_alloca(size) __builtin_alloca(size)
#elif defined(__has_builtin) && __has_builtin(__builtin_alloca)
    #define ktc_core_alloca(size) __builtin_alloca(size)
#else
    #include <alloca.h>
    #define ktc_core_alloca(size) alloca(size)
#endif

/* Portable thread-local storage specifier. */
#ifndef ktc_core_tls
    #if defined(__STDC_VERSION__) && (__STDC_VERSION__ >= 201112L)
        #define ktc_core_tls _Thread_local
    #elif defined(__cplusplus) && (__cplusplus >= 201103L)
        #define ktc_core_tls thread_local
    #elif defined(_MSC_VER)
        #define ktc_core_tls __declspec(thread)
    #elif defined(__GNUC__) || defined(__clang__) || defined(__INTEL_COMPILER)
        #define ktc_core_tls __thread
    #else
        #define ktc_core_tls
        #warning "Thread-local storage not supported on this compiler."
    #endif
#endif

// ══════════════════════════════════════════════════════════════════
// MARK: Types System
// ══════════════════════════════════════════════════════════════════

/** Pass-by-value for variable-size arrays; functions copy data to a local stack buffer. */
typedef struct { ktc_Int __array_type_id; ktc_Int size; void* data; } ktc_ArrayTrampoline;

/** Base "supertype" embedded at the start of every class/object/interface struct.
 *  Mirrors Kotlin's implicit `Any` superclass. */
typedef struct {
    ktc_Int typeId;
} ktc_core_AnySupertype;

/** @Ptr interface trampoline — fat pointer for interface references.
 *  Uses __base.typeId for is-checks, vt for dispatch, obj for concrete data.
 *  One unified struct for all @Ptr InterfaceType regardless of which interface. */
typedef struct { ktc_core_AnySupertype __base; const void* vt; void* obj; } ktc_IfacePtr;

/** Vtable for Any methods — one static instance per class.
 *  All methods take void* for type-erased dispatch. */
typedef struct ktc_core_AnyVt {
    void      (*toString)(void* $self, void* sb);
    ktc_Int   (*hashCode)(void* $self);
    ktc_Bool  (*equals)(void* $self, void* other);
    void      (*dispose)(void* $self);
    void*     (*copyWith)(void* $self, void* alloc);  // allocate + copy via Allocator
} ktc_core_AnyVt;

/** Type-erased fat pointer for `Any` — identity checks + vtable dispatch. */
typedef struct { ktc_core_AnySupertype __base; void* data; const ktc_core_AnyVt* vt; } ktc_Any;

typedef enum { ktc_NONE = 0, ktc_SOME = 1 } ktc_OptionalTag;

/* Backwards-compat: codegen still emits KTC_OPTIONAL_GENERIC until the $Opt rename step.
   Will be removed once CCodeGenEmit is updated to use KTC_OPT_GENERIC_TYPE. */
#define KTC_OPTIONAL_GENERIC_NAME(Base, TypeArg) Base##$Optional_##TypeArg
#define KTC_OPTIONAL_GENERIC(T, Base, TypeArg) \
    typedef struct KTC_OPTIONAL_GENERIC_NAME(Base, TypeArg) { ktc_OptionalTag tag; T value; } \
    KTC_OPTIONAL_GENERIC_NAME(Base, TypeArg)

/** No-op dispose used by vtables when a class has no custom dispose. */
static void ktc_core_noop_dispose(void* obj) { (void)obj; }

KTC_DEFINE_OPT(ktc_Byte);
KTC_DEFINE_OPT(ktc_Short);
KTC_DEFINE_OPT(ktc_Int);
KTC_DEFINE_OPT(ktc_Long);
KTC_DEFINE_OPT(ktc_Float);
KTC_DEFINE_OPT(ktc_Double);
KTC_DEFINE_OPT(ktc_Bool);
KTC_DEFINE_OPT(ktc_Char);
KTC_DEFINE_OPT(ktc_UByte);
KTC_DEFINE_OPT(ktc_UShort);
KTC_DEFINE_OPT(ktc_UInt);
KTC_DEFINE_OPT(ktc_ULong);

// ══════════════════════════════════════════════════════════════════
// MARK: Types IDs
// ══════════════════════════════════════════════════════════════════

#define ktc_Byte_TYPE_ID    0
#define ktc_Short_TYPE_ID   1
#define ktc_Int_TYPE_ID     2
#define ktc_Long_TYPE_ID    3
#define ktc_Float_TYPE_ID   4
#define ktc_Double_TYPE_ID  5
#define ktc_Boolean_TYPE_ID 6
#define ktc_Char_TYPE_ID    7
#define ktc_UByte_TYPE_ID   8
#define ktc_UShort_TYPE_ID  9
#define ktc_UInt_TYPE_ID    10
#define ktc_ULong_TYPE_ID   11
#define ktc_String_TYPE_ID  12
#define ktc_Any_TYPE_ID     13
/* nextTypeId starts at 14 */

// ══════════════════════════════════════════════════════════════════
// MARK: Memory Tracking
// ══════════════════════════════════════════════════════════════════

/**
 * Define KTC_MEM_TRACK before including this header to intercept
 * malloc/calloc/realloc/free. Call ktc_core_mem_report() at exit to
 * print the alloc/free summary and any leaked allocations.
 *
 * ktc_core_malloc / ktc_core_free / ktc_core_realloc are ALWAYS available
 * (declared here, defined in ktc_core.c). When KTC_MEM_TRACK is defined,
 * they record every allocation in a shared global table.
 */
void* ktc_core_malloc(ktc_ULong sz, const ktc_Char* file, ktc_Int line);
void  ktc_core_free(void* p, const ktc_Char* file, ktc_Int line);
void* ktc_core_realloc(void* old, ktc_ULong sz, const ktc_Char* file, ktc_Int line);
void* ktc_core_calloc(ktc_ULong n, ktc_ULong sz, const ktc_Char* file, ktc_Int line);

void  ktc_core_mem_report(void);

#ifdef KTC_MEM_TRACK
#define malloc(sz)     ktc_core_malloc(sz, __FILE__, __LINE__)
#define calloc(n, sz)  ktc_core_calloc(n, sz, __FILE__, __LINE__)
#define realloc(p, sz) ktc_core_realloc(p, sz, __FILE__, __LINE__)
#define free(p)        ktc_core_free(p, __FILE__, __LINE__)
#endif /* KTC_MEM_TRACK */

// ══════════════════════════════════════════════════════════════════
// MARK: String
// ══════════════════════════════════════════════════════════════════

typedef struct {
    const ktc_Char* ptr;
    ktc_Int         len;
} ktc_String;
KTC_DEFINE_OPT(ktc_String);

/** String from a string literal — zero-cost, points into static storage. */
#define ktc_core_str(s) ((ktc_String){(s), (ktc_Int)(sizeof(s) - 1)})

/** String from pointer + length — no copy. */
#define ktc_core_string_wrap(p, n) ((ktc_String){(p), (ktc_Int)(n)})

static inline ktc_Bool ktc_core_string_eq(ktc_String a, ktc_String b) {
    return a.len == b.len && memcmp(a.ptr, b.ptr, (size_t)a.len) == 0;
}

/** Lexicographic comparison — returns <0, 0, or >0. */
static inline ktc_Int ktc_core_string_cmp(ktc_String a, ktc_String b) {
    ktc_Int minlen = a.len < b.len ? a.len : b.len;
    ktc_Int r = memcmp(a.ptr, b.ptr, (size_t)minlen);
    if (r != 0) return r;
    return (a.len > b.len) - (a.len < b.len);
}

/** Substring as a view — no copy. Clamps out-of-range indices. */
static inline ktc_String ktc_core_string_substring(ktc_String s, ktc_Int from, ktc_Int to) {
    if (from < 0) from = 0;
    if (to > s.len) to = s.len;
    if (from >= to) return (ktc_String){"", 0};
    return (ktc_String){s.ptr + from, to - from};
}

/** Concatenate into caller-provided buffer. Clamps each half independently. */
ktc_String ktc_core_string_cat(ktc_Char* buf, ktc_Int bufsz, ktc_String a, ktc_String b);

// ══════════════════════════════════════════════════════════════════
// MARK: StrBuf
// ══════════════════════════════════════════════════════════════════

typedef struct {
    ktc_Char* ptr;
    ktc_Int   len;
    ktc_Int   cap;
} ktc_StrBuf;

/*
 * Stack-backed: ktc_Char buf[256]; ktc_StrBuf sb = {buf, 0, 256};
 * Counting mode: {NULL, 0, 0} — counts required length without writing.
 */

/** View of current buffer contents — no copy. */
#define ktc_core_sb_to_string(sb) ((ktc_String){(sb)->ptr, (sb)->len})

static inline void ktc_core_sb_append_char(ktc_StrBuf* sb, ktc_Char c) {
    if (!sb->ptr) { sb->len++; return; }
    if (sb->len < sb->cap) sb->ptr[sb->len++] = c;
}

#define ktc_core_sb_append_bool(sb, v) \
    ktc_core_sb_append_str(sb, (v) ? ktc_core_str("true") : ktc_core_str("false"))

void ktc_core_sb_append_str(ktc_StrBuf* sb, ktc_String s);
void ktc_core_sb_append_cstr(ktc_StrBuf* sb, const ktc_Char* s);
void ktc_core_sb_append_int(ktc_StrBuf* sb, ktc_Int v);
void ktc_core_sb_append_long(ktc_StrBuf* sb, ktc_Long v);
void ktc_core_sb_append_float(ktc_StrBuf* sb, ktc_Float v);
void ktc_core_sb_append_double(ktc_StrBuf* sb, ktc_Double v);
void ktc_core_sb_append_byte(ktc_StrBuf* sb, ktc_Byte v);
void ktc_core_sb_append_short(ktc_StrBuf* sb, ktc_Short v);
void ktc_core_sb_append_ubyte(ktc_StrBuf* sb, ktc_UByte v);
void ktc_core_sb_append_ushort(ktc_StrBuf* sb, ktc_UShort v);
void ktc_core_sb_append_uint(ktc_StrBuf* sb, ktc_UInt v);
void ktc_core_sb_append_ulong(ktc_StrBuf* sb, ktc_ULong v);
void ktc_core_sb_append_rune(ktc_StrBuf* sb, ktc_Rune r);

// ══════════════════════════════════════════════════════════════════
// MARK: UTF-8
// ══════════════════════════════════════════════════════════════════

/** Decode one UTF-8 code point. Sets *byteLen to bytes consumed (1-4).
 * Invalid sequences return U+FFFD with *byteLen = 1. */
ktc_Rune ktc_core_utf8_decode(const ktc_Char* p, ktc_Int* byteLen);

/** Encode a code point into out[4]. Returns byte count (1-4). */
ktc_Int ktc_core_utf8_encode(ktc_Rune r, ktc_Char* out);

/** Count Unicode code points — O(n) scan. */
ktc_Int ktc_core_str_runeLen(ktc_String s);

/** Decode the code point at byte offset pos. Returns U+FFFD if out of range. */
static inline ktc_Rune ktc_core_str_runeAt(ktc_String s, ktc_Int pos) {
    if (pos < 0 || pos >= s.len) return 0xFFFD;
    ktc_Int blen;
    return ktc_core_utf8_decode(s.ptr + pos, &blen);
}

// ══════════════════════════════════════════════════════════════════
// MARK: Hash
// ══════════════════════════════════════════════════════════════════

static inline ktc_Int ktc_core_hash_i8(ktc_Byte v)    { return (ktc_Int)v; }
static inline ktc_Int ktc_core_hash_i16(ktc_Short v)  { return (ktc_Int)v; }
static inline ktc_Int ktc_core_hash_i32(ktc_Int v)    { return v; }
static inline ktc_Int ktc_core_hash_i64(ktc_Long v)   { ktc_ULong u = (ktc_ULong)v; return (ktc_Int)(ktc_UInt)(u ^ (u >> 32)); }
static inline ktc_Int ktc_core_hash_f32(ktc_Float v)  { ktc_UInt  b; memcpy(&b, &v, 4); return (ktc_Int)b; }
static inline ktc_Int ktc_core_hash_f64(ktc_Double v) { ktc_ULong b; memcpy(&b, &v, 8); return (ktc_Int)(ktc_UInt)(b ^ (b >> 32)); }
static inline ktc_Int ktc_core_hash_bool(ktc_Bool v)  { return v ? 1 : 0; }
static inline ktc_Int ktc_core_hash_char(ktc_Char v)  { return (ktc_Int)(unsigned char)v; }
static inline ktc_Int ktc_core_hash_u8(ktc_UByte v)   { return (ktc_Int)(ktc_UInt)v; }
static inline ktc_Int ktc_core_hash_u16(ktc_UShort v) { return (ktc_Int)(ktc_UInt)v; }
static inline ktc_Int ktc_core_hash_u32(ktc_UInt v)   { return (ktc_Int)v; }
static inline ktc_Int ktc_core_hash_u64(ktc_ULong v)  { return (ktc_Int)(ktc_UInt)(v ^ (v >> 32)); }
static inline ktc_Int ktc_core_hash_str(ktc_String s) {
    ktc_UInt h = 2166136261u;
    for (ktc_Int i = 0; i < s.len; i++) { h ^= (ktc_UByte)s.ptr[i]; h *= 16777619u; }
    return (ktc_Int)h;
}

/** Murmur3-style finalisation mix. */
static inline ktc_UInt ktc_core_fmix32(ktc_UInt h) {
    h ^= h >> 16; h *= 0x85ebca6bU;
    h ^= h >> 13; h *= 0xc2b2ae35U;
    h ^= h >> 16;
    return h;
}

// ══════════════════════════════════════════════════════════════════
// MARK: Conversion
// ══════════════════════════════════════════════════════════════════

#define ktc_core_bool_to_string(v) ((v) ? ktc_core_str("true") : ktc_core_str("false"))
ktc_String ktc_core_int_to_string(ktc_Char* buf, ktc_Int bufsz, ktc_Int v);
ktc_String ktc_core_long_to_string(ktc_Char* buf, ktc_Int bufsz, ktc_Long v);
ktc_String ktc_core_float_to_string(ktc_Char* buf, ktc_Int bufsz, ktc_Float v);
ktc_String ktc_core_double_to_string(ktc_Char* buf, ktc_Int bufsz, ktc_Double v);

// ══════════════════════════════════════════════════════════════════
// MARK: Parsing
// ══════════════════════════════════════════════════════════════════

ktc_Int    ktc_core_str_toInt(ktc_String s);
ktc_Long   ktc_core_str_toLong(ktc_String s);
ktc_Double ktc_core_str_toDouble(ktc_String s);

/** Returns false on parse failure (empty, non-numeric, or overflow). */
ktc_Bool ktc_core_str_toIntOrNull(ktc_String s, ktc_Int* out);
ktc_Bool ktc_core_str_toLongOrNull(ktc_String s, ktc_Long* out);
ktc_Bool ktc_core_str_toDoubleOrNull(ktc_String s, ktc_Double* out);
