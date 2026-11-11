/**
 * ktc2 core runtime — primitive semantics that differ between Kotlin and C.
 *
 * Kotlin integer arithmetic wraps, shifts mask their count, and float-to-int
 * conversion saturates; plain C operators are undefined or different in those
 * cases, so generated code goes through these helpers.
 */
#ifndef KTC2_CORE_H
#define KTC2_CORE_H

#include <math.h>
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

/* ================== */
/* MARK: Objects      */
/* ================== */

/** Prefix of every reference object. */
typedef struct ktc2_Obj {
	uint32_t rc;      /* reference count, or KTC2_IMMORTAL for static objects */
	uint32_t typeId;  /* index into ktc2_types[] */
} ktc2_Obj;

#define KTC2_IMMORTAL UINT32_MAX

/** Per-type data. `destroy` releases the references an instance holds; NULL when it holds none. */
typedef struct ktc2_TypeInfo {
	const char* name;
	void (*destroy)(ktc2_Obj* object);
} ktc2_TypeInfo;

enum {
	KTC2_TYPE_NONE = 0,
	KTC2_TYPE_STRING = 1,
	KTC2_TYPE_ARRAY_BOOLEAN,
	KTC2_TYPE_ARRAY_BYTE,
	KTC2_TYPE_ARRAY_SHORT,
	KTC2_TYPE_ARRAY_INT,
	KTC2_TYPE_ARRAY_LONG,
	KTC2_TYPE_ARRAY_CHAR,
	KTC2_TYPE_ARRAY_FLOAT,
	KTC2_TYPE_ARRAY_DOUBLE,
	KTC2_TYPE_ARRAY_UBYTE,
	KTC2_TYPE_ARRAY_USHORT,
	KTC2_TYPE_ARRAY_UINT,
	KTC2_TYPE_ARRAY_ULONG,
	KTC2_TYPE_ARRAY_REF,    /* elements are references, released with the array */
	KTC2_TYPE_ARRAY_VALUE,  /* elements are plain values (nullable primitives, structs) */
	KTC2_TYPE_BOX_BOOLEAN,  /* a primitive held as an object (an Int seen as Any) */
	KTC2_TYPE_BOX_BYTE,
	KTC2_TYPE_BOX_SHORT,
	KTC2_TYPE_BOX_INT,
	KTC2_TYPE_BOX_LONG,
	KTC2_TYPE_BOX_CHAR,
	KTC2_TYPE_BOX_FLOAT,
	KTC2_TYPE_BOX_DOUBLE,
	KTC2_TYPE_BOX_UBYTE,
	KTC2_TYPE_BOX_USHORT,
	KTC2_TYPE_BOX_UINT,
	KTC2_TYPE_BOX_ULONG,
	KTC2_TYPE_FIRST_USER
};

struct ktc2_Obj;
void ktc2_array_destroy(struct ktc2_Obj* object);

#define KTC2_BUILTIN_TYPES \
	[KTC2_TYPE_STRING] = { "String", NULL }, \
	[KTC2_TYPE_ARRAY_BOOLEAN] = { "BooleanArray", NULL }, \
	[KTC2_TYPE_ARRAY_BYTE] = { "ByteArray", NULL }, \
	[KTC2_TYPE_ARRAY_SHORT] = { "ShortArray", NULL }, \
	[KTC2_TYPE_ARRAY_INT] = { "IntArray", NULL }, \
	[KTC2_TYPE_ARRAY_LONG] = { "LongArray", NULL }, \
	[KTC2_TYPE_ARRAY_CHAR] = { "CharArray", NULL }, \
	[KTC2_TYPE_ARRAY_FLOAT] = { "FloatArray", NULL }, \
	[KTC2_TYPE_ARRAY_DOUBLE] = { "DoubleArray", NULL }, \
	[KTC2_TYPE_ARRAY_UBYTE] = { "UByteArray", NULL }, \
	[KTC2_TYPE_ARRAY_USHORT] = { "UShortArray", NULL }, \
	[KTC2_TYPE_ARRAY_UINT] = { "UIntArray", NULL }, \
	[KTC2_TYPE_ARRAY_ULONG] = { "ULongArray", NULL }, \
	[KTC2_TYPE_ARRAY_REF] = { "Array", ktc2_array_destroy }, \
	[KTC2_TYPE_ARRAY_VALUE] = { "Array", NULL }, \
	[KTC2_TYPE_BOX_BOOLEAN] = { "Boolean", NULL }, \
	[KTC2_TYPE_BOX_BYTE] = { "Byte", NULL }, \
	[KTC2_TYPE_BOX_SHORT] = { "Short", NULL }, \
	[KTC2_TYPE_BOX_INT] = { "Int", NULL }, \
	[KTC2_TYPE_BOX_LONG] = { "Long", NULL }, \
	[KTC2_TYPE_BOX_CHAR] = { "Char", NULL }, \
	[KTC2_TYPE_BOX_FLOAT] = { "Float", NULL }, \
	[KTC2_TYPE_BOX_DOUBLE] = { "Double", NULL }, \
	[KTC2_TYPE_BOX_UBYTE] = { "UByte", NULL }, \
	[KTC2_TYPE_BOX_USHORT] = { "UShort", NULL }, \
	[KTC2_TYPE_BOX_UINT] = { "UInt", NULL }, \
	[KTC2_TYPE_BOX_ULONG] = { "ULong", NULL }

/** Generated in ktc2_main.c. */
extern const ktc2_TypeInfo ktc2_types[];
extern const uint32_t ktc2_type_count;

/** A zeroed object of [size] bytes with a count of 1. */
void* ktc2_alloc(size_t size, uint32_t typeId);

/** Runs the type's destroy and frees the memory. */
void ktc2_destroy(ktc2_Obj* object);

/** Null-safe; returns its argument so it can wrap an expression. */
static inline void* ktc2_retain(void* pointer)
{
	ktc2_Obj* object = pointer;
	if (object && object->rc != KTC2_IMMORTAL) object->rc++;
	return pointer;
}

/** Null-safe; destroys the object when the last reference goes. */
static inline void ktc2_release(void* pointer)
{
	ktc2_Obj* object = pointer;
	if (object && object->rc != KTC2_IMMORTAL && --object->rc == 0) ktc2_destroy(object);
}

/**
 * Stores an owned reference into a slot and releases what the slot held. The new value is
 * evaluated first: computing it may itself change the slot.
 */
#define KTC2_ASSIGN(slot, value) 	do { 		void* ktc2_new = (value); 		void* ktc2_old = (slot); 		(slot) = ktc2_new; 		ktc2_release(ktc2_old); 	} while (0)

/** Called at the end of main: reports objects still alive when built with KTC2_LEAK_CHECK. Returns the exit code. */
int ktc2_exit(void);

_Noreturn void ktc2_fail_null(void);
_Noreturn void ktc2_fail_cast(void);
_Noreturn void ktc2_fail_dispatch(void);

/* Nullable primitives: a presence flag and the value. */
#define KTC2_OPT(type) typedef struct { bool has; type v; } ktc2_Opt_##type
KTC2_OPT(bool);
KTC2_OPT(int8_t);
KTC2_OPT(int16_t);
KTC2_OPT(int32_t);
KTC2_OPT(int64_t);
KTC2_OPT(uint8_t);
KTC2_OPT(uint16_t);
KTC2_OPT(uint32_t);
KTC2_OPT(uint64_t);
KTC2_OPT(float);
KTC2_OPT(double);

_Noreturn void ktc2_fail_when(void);

/* ================== */
/* MARK: Boxes        */
/* ================== */

/** A primitive held as an object, for when it is used as `Any`. */
typedef struct ktc2_Box {
	ktc2_Obj header;
	union {
		bool boolean_;
		int8_t byte_;
		int16_t short_;
		int32_t int_;
		int64_t long_;
		uint16_t char_;
		float float_;
		double double_;
		uint8_t ubyte_;
		uint16_t ushort_;
		uint32_t uint_;
		uint64_t ulong_;
	} value;
} ktc2_Box;

/* ktc2_box_<kind>(v) returns a new box; ktc2_unbox_<kind>(o) fails unless o is a box of that kind.
   The _opt_ variants convert between a nullable primitive and a box-or-NULL. */
#define KTC2_BOX(kind, type, id) \
	static inline ktc2_Obj* ktc2_box_##kind(type v) \
	{ \
		ktc2_Box* box = ktc2_alloc(sizeof(ktc2_Box), id); \
		box->value.kind##_ = v; \
		return &box->header; \
	} \
	static inline type ktc2_unbox_##kind(ktc2_Obj* object) \
	{ \
		if (!object) ktc2_fail_null(); \
		if (object->typeId != id) ktc2_fail_cast(); \
		return ((ktc2_Box*)object)->value.kind##_; \
	} \
	static inline ktc2_Obj* ktc2_box_opt_##kind(ktc2_Opt_##type v) \
	{ \
		return v.has ? ktc2_box_##kind(v.v) : NULL; \
	} \
	static inline ktc2_Opt_##type ktc2_unbox_opt_##kind(ktc2_Obj* object) \
	{ \
		return object ? (ktc2_Opt_##type){ true, ktc2_unbox_##kind(object) } : (ktc2_Opt_##type){ false }; \
	}

KTC2_BOX(boolean, bool, KTC2_TYPE_BOX_BOOLEAN)
KTC2_BOX(byte, int8_t, KTC2_TYPE_BOX_BYTE)
KTC2_BOX(short, int16_t, KTC2_TYPE_BOX_SHORT)
KTC2_BOX(int, int32_t, KTC2_TYPE_BOX_INT)
KTC2_BOX(long, int64_t, KTC2_TYPE_BOX_LONG)
KTC2_BOX(char, uint16_t, KTC2_TYPE_BOX_CHAR)
KTC2_BOX(float, float, KTC2_TYPE_BOX_FLOAT)
KTC2_BOX(double, double, KTC2_TYPE_BOX_DOUBLE)
KTC2_BOX(ubyte, uint8_t, KTC2_TYPE_BOX_UBYTE)
KTC2_BOX(ushort, uint16_t, KTC2_TYPE_BOX_USHORT)
KTC2_BOX(uint, uint32_t, KTC2_TYPE_BOX_UINT)
KTC2_BOX(ulong, uint64_t, KTC2_TYPE_BOX_ULONG)

/* hashCode() of the primitives whose hash is not the value itself (the JVM's formulas). */
static inline int32_t ktc2_hash_boolean(bool v) { return v ? 1231 : 1237; }
static inline int32_t ktc2_hash_long(int64_t v) { return (int32_t)((uint64_t)v ^ ((uint64_t)v >> 32)); }
static inline int32_t ktc2_hash_ulong(uint64_t v) { return (int32_t)(v ^ (v >> 32)); }
int32_t ktc2_hash_float(float v);
int32_t ktc2_hash_double(double v);

/* ================== */
/* MARK: Arrays       */
/* ================== */

/** Header of every array; the elements follow it. */
typedef struct ktc2_Array {
	ktc2_Obj header;
	int32_t length;
	int32_t elementSize;
} ktc2_Array;

/** A zeroed array; fails on a negative length. */
ktc2_Array* ktc2_array_new(int32_t length, size_t elementSize, uint32_t typeId);

_Noreturn void ktc2_fail_index(int32_t index, int32_t length);

static inline int32_t ktc2_array_length(const ktc2_Array* array) { return array->length; }

/** Returns [index] after checking it against the array's bounds. */
static inline int32_t ktc2_array_index(const ktc2_Array* array, int32_t index)
{
	if ((uint32_t)index >= (uint32_t)array->length) ktc2_fail_index(index, array->length);
	return index;
}

/** The bounds-checked element, as an lvalue. */
#define KTC2_ARRAY_AT(array, type, index) (((type*)((array) + 1))[ktc2_array_index((array), (index))])

/* ================== */
/* MARK: Strings      */
/* ================== */

/**
 * An immutable string: UTF-8 bytes, NUL-terminated. `length` is the Kotlin length (UTF-16
 * code units); when it equals `size` the string is ASCII and indexing is direct.
 */
typedef struct ktc2_String {
	ktc2_Obj header;
	int32_t size;
	int32_t length;
	char bytes[];
} ktc2_String;

/** Defines a static, immortal string object. */
#define KTC2_STRING_LIT(name, size, length, text) 	static const struct { ktc2_Obj header; int32_t size_; int32_t length_; char bytes[sizeof(text)]; } name = 		{ { KTC2_IMMORTAL, KTC2_TYPE_STRING }, size, length, text }

#define KTC2_STR(name) ((ktc2_String*)&name)

static inline int32_t ktc2_str_length(const ktc2_String* s) { return s->length; }

/** `s[index]`: the UTF-16 code unit at [index]. */
uint16_t ktc2_str_get(const ktc2_String* s, int32_t index);
/** Null-safe content equality. */
bool ktc2_str_equals(const ktc2_String* a, const ktc2_String* b);
int32_t ktc2_str_compare(const ktc2_String* a, const ktc2_String* b);
int32_t ktc2_str_hashCode(const ktc2_String* s);

/** A new string from [length] UTF-16 code units of a CharArray, starting at [start]. */
ktc2_String* ktc2_stringOfChars(ktc2_Array* chars, int32_t start, int32_t length);

/** `Enum.valueOf` found no entry called [name]. */
_Noreturn void ktc2_fail_enum_value(const ktc2_String* name);

/** Defaults of kotlin.Any, also correct for strings. `toString` returns an owned reference. */
ktc2_String* ktc2_any_toString(ktc2_Obj* object);
bool ktc2_any_equals(ktc2_Obj* a, ktc2_Obj* b);
int32_t ktc2_any_hashCode(ktc2_Obj* object);
/** `CharSequence` members for the built-in implementation, String. */
int32_t ktc2_cs_length(ktc2_Obj* object);
uint16_t ktc2_cs_get(ktc2_Obj* object, int32_t index);
/** Reports a thrown exception and exits (until exceptions can be caught). [message] may be NULL. */
_Noreturn void ktc2_fail_throw(const ktc2_String* exception, const ktc2_String* message);
/** `Comparable.compareTo` for the built-in comparable types: strings and boxed primitives. */
int32_t ktc2_any_compareTo(ktc2_Obj* a, ktc2_Obj* b);

/** Builds a string piece by piece; starts on the stack and moves to the heap when it grows. */
typedef struct ktc2_Sb {
	char* data;
	int32_t size;
	int32_t capacity;
	int32_t length;
	char local[104];
} ktc2_Sb;

void ktc2_sb_init(ktc2_Sb* sb);
/** Appends UTF-8 bytes whose Kotlin length is [length]. */
void ktc2_sb_text(ktc2_Sb* sb, const char* text, int32_t size, int32_t length);
/** Appends a string, or "null". */
void ktc2_sb_string(ktc2_Sb* sb, const ktc2_String* s);
void ktc2_sb_bool(ktc2_Sb* sb, bool v);
void ktc2_sb_int(ktc2_Sb* sb, int32_t v);
void ktc2_sb_long(ktc2_Sb* sb, int64_t v);
void ktc2_sb_uint(ktc2_Sb* sb, uint32_t v);
void ktc2_sb_ulong(ktc2_Sb* sb, uint64_t v);
void ktc2_sb_char(ktc2_Sb* sb, uint16_t v);
void ktc2_sb_float(ktc2_Sb* sb, float v);
void ktc2_sb_double(ktc2_Sb* sb, double v);
/** Returns the built string (owned by the caller) and releases the builder. */
ktc2_String* ktc2_sb_finish(ktc2_Sb* sb);

/* ================== */
/* MARK: Primitives   */
/* ================== */

/** Three-way comparison, the result of Kotlin's `compareTo`. */
#define KTC2_CMP(a, b) (((a) > (b)) - ((a) < (b)))

/** Reports `ArithmeticException: / by zero` and exits. */
_Noreturn void ktc2_fail_div_zero(void);

static inline int32_t ktc2_iadd(int32_t a, int32_t b) { return (int32_t)((uint32_t)a + (uint32_t)b); }
static inline int32_t ktc2_isub(int32_t a, int32_t b) { return (int32_t)((uint32_t)a - (uint32_t)b); }
static inline int32_t ktc2_imul(int32_t a, int32_t b) { return (int32_t)((uint32_t)a * (uint32_t)b); }
static inline int32_t ktc2_ineg(int32_t a) { return (int32_t)(0u - (uint32_t)a); }
static inline int32_t ktc2_ishl(int32_t a, int32_t n) { return (int32_t)((uint32_t)a << (n & 31)); }
static inline int32_t ktc2_ishr(int32_t a, int32_t n) { return a >> (n & 31); }
static inline int32_t ktc2_iushr(int32_t a, int32_t n) { return (int32_t)((uint32_t)a >> (n & 31)); }

static inline int32_t ktc2_idiv(int32_t a, int32_t b)
{
	if (b == 0) ktc2_fail_div_zero();
	return b == -1 ? ktc2_ineg(a) : a / b;
}

static inline int32_t ktc2_irem(int32_t a, int32_t b)
{
	if (b == 0) ktc2_fail_div_zero();
	return b == -1 ? 0 : a % b;
}

static inline int64_t ktc2_ladd(int64_t a, int64_t b) { return (int64_t)((uint64_t)a + (uint64_t)b); }
static inline int64_t ktc2_lsub(int64_t a, int64_t b) { return (int64_t)((uint64_t)a - (uint64_t)b); }
static inline int64_t ktc2_lmul(int64_t a, int64_t b) { return (int64_t)((uint64_t)a * (uint64_t)b); }
static inline int64_t ktc2_lneg(int64_t a) { return (int64_t)(0u - (uint64_t)a); }
static inline int64_t ktc2_lshl(int64_t a, int32_t n) { return (int64_t)((uint64_t)a << (n & 63)); }
static inline int64_t ktc2_lshr(int64_t a, int32_t n) { return a >> (n & 63); }
static inline int64_t ktc2_lushr(int64_t a, int32_t n) { return (int64_t)((uint64_t)a >> (n & 63)); }

static inline int64_t ktc2_ldiv(int64_t a, int64_t b)
{
	if (b == 0) ktc2_fail_div_zero();
	return b == -1 ? ktc2_lneg(a) : a / b;
}

static inline int64_t ktc2_lrem(int64_t a, int64_t b)
{
	if (b == 0) ktc2_fail_div_zero();
	return b == -1 ? 0 : a % b;
}

static inline uint32_t ktc2_uadd(uint32_t a, uint32_t b) { return a + b; }
static inline uint32_t ktc2_usub(uint32_t a, uint32_t b) { return a - b; }
static inline uint32_t ktc2_umul(uint32_t a, uint32_t b) { return a * b; }
static inline uint32_t ktc2_ushl(uint32_t a, int32_t n) { return a << (n & 31); }
static inline uint32_t ktc2_ushr(uint32_t a, int32_t n) { return a >> (n & 31); }

static inline uint32_t ktc2_udiv(uint32_t a, uint32_t b)
{
	if (b == 0) ktc2_fail_div_zero();
	return a / b;
}

static inline uint32_t ktc2_urem(uint32_t a, uint32_t b)
{
	if (b == 0) ktc2_fail_div_zero();
	return a % b;
}

static inline uint64_t ktc2_uladd(uint64_t a, uint64_t b) { return a + b; }
static inline uint64_t ktc2_ulsub(uint64_t a, uint64_t b) { return a - b; }
static inline uint64_t ktc2_ulmul(uint64_t a, uint64_t b) { return a * b; }
static inline uint64_t ktc2_ulshl(uint64_t a, int32_t n) { return a << (n & 63); }
static inline uint64_t ktc2_ulshr(uint64_t a, int32_t n) { return a >> (n & 63); }

static inline uint64_t ktc2_uldiv(uint64_t a, uint64_t b)
{
	if (b == 0) ktc2_fail_div_zero();
	return a / b;
}

static inline uint64_t ktc2_ulrem(uint64_t a, uint64_t b)
{
	if (b == 0) ktc2_fail_div_zero();
	return a % b;
}

/* Last element of a stepped progression (kotlin.internal.getProgressionLastElement). */

/** Reports `IllegalArgumentException: Step must be positive` and exits when step <= 0. */
void ktc2_check_step(int64_t step);

static inline int32_t ktc2_imod(int32_t a, int32_t b)
{
	int32_t m = a % b;
	return m >= 0 ? m : m + b;
}

/** For start <= end and step > 0: the last value reached from start that is <= end. */
static inline int32_t ktc2_irange_last_up(int32_t start, int32_t end, int32_t step)
{
	return end - ktc2_imod(ktc2_imod(end, step) - ktc2_imod(start, step), step);
}

/** For start >= end and step > 0: the last value reached downward from start that is >= end. */
static inline int32_t ktc2_irange_last_down(int32_t start, int32_t end, int32_t step)
{
	return end + ktc2_imod(ktc2_imod(start, step) - ktc2_imod(end, step), step);
}

static inline int64_t ktc2_lmod(int64_t a, int64_t b)
{
	int64_t m = a % b;
	return m >= 0 ? m : m + b;
}

static inline int64_t ktc2_lrange_last_up(int64_t start, int64_t end, int64_t step)
{
	return end - ktc2_lmod(ktc2_lmod(end, step) - ktc2_lmod(start, step), step);
}

static inline int64_t ktc2_lrange_last_down(int64_t start, int64_t end, int64_t step)
{
	return end + ktc2_lmod(ktc2_lmod(start, step) - ktc2_lmod(end, step), step);
}

/** `Double.toInt()`: NaN is 0, out-of-range values saturate. */
static inline int32_t ktc2_d2i(double v)
{
	if (v != v) return 0;
	if (v >= 2147483647.0) return INT32_MAX;
	if (v <= -2147483648.0) return INT32_MIN;
	return (int32_t)v;
}

/** `Double.toLong()`: NaN is 0, out-of-range values saturate. */
static inline int64_t ktc2_d2l(double v)
{
	if (v != v) return 0;
	if (v >= 9223372036854775808.0) return INT64_MAX;
	if (v <= -9223372036854775808.0) return INT64_MIN;
	return (int64_t)v;
}

/** `Double.toUInt()`: NaN and negatives are 0, large values saturate. */
static inline uint32_t ktc2_d2u(double v)
{
	if (!(v > 0)) return 0;
	if (v >= 4294967295.0) return UINT32_MAX;
	return (uint32_t)v;
}

/** `Double.toULong()`: NaN and negatives are 0, large values saturate. */
static inline uint64_t ktc2_d2ul(double v)
{
	if (!(v > 0)) return 0;
	if (v >= 18446744073709551616.0) return UINT64_MAX;
	return (uint64_t)v;
}

/* Output, formatted exactly as Kotlin's print / println. */
void ktc2_print_text(const char* text, int32_t size, int32_t length);
/** Prints a string, or "null". */
void ktc2_print_string(const ktc2_String* s);
void ktc2_print_bool(bool v);
void ktc2_print_int(int32_t v);
void ktc2_print_long(int64_t v);
void ktc2_print_uint(uint32_t v);
void ktc2_print_ulong(uint64_t v);
void ktc2_print_char(uint16_t v);
void ktc2_print_float(float v);
void ktc2_print_double(double v);

#endif
