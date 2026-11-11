#include "ktc2_core.h"

#include <inttypes.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

/* ================== */
/* MARK: Failures     */
/* ================== */

static _Noreturn void fail(const char* exception, const char* message)
{
	fflush(stdout);
	fprintf(stderr, "Exception in thread \"main\" %s%s%s\n", exception, message ? ": " : "", message ? message : "");
	exit(1);
}

_Noreturn void ktc2_fail_div_zero(void)
{
	fail("java.lang.ArithmeticException", "/ by zero");
}

_Noreturn void ktc2_fail_null(void)
{
	fail("java.lang.NullPointerException", NULL);
}

_Noreturn void ktc2_fail_cast(void)
{
	fail("java.lang.ClassCastException", NULL);
}

_Noreturn void ktc2_fail_dispatch(void)
{
	fail("java.lang.AbstractMethodError", NULL);
}

_Noreturn void ktc2_fail_when(void)
{
	fail("kotlin.NoWhenBranchMatchedException", NULL);
}

void ktc2_check_step(int64_t step)
{
	if (step > 0) return;
	fflush(stdout);
	fprintf(stderr, "Exception in thread \"main\" java.lang.IllegalArgumentException: Step must be positive, was: %" PRId64 ".\n", step);
	exit(1);
}

/* ================== */
/* MARK: Objects      */
/* ================== */

#ifdef KTC2_THREADS
#ifdef _WIN32
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#define thread_yield() Sleep(0)
#else
#include <pthread.h>
#include <sched.h>
#define thread_yield() sched_yield()
#endif

/** Guards the runtime's own tables (weak cells, leak counts): held for a few instructions only. */
static char runtime_lock;

static void runtime_enter(void)
{
	while (__atomic_test_and_set(&runtime_lock, __ATOMIC_ACQUIRE)) thread_yield();
}

static void runtime_leave(void)
{
	__atomic_clear(&runtime_lock, __ATOMIC_RELEASE);
}

#define KTC2_RC_OR(object, bits) ((void)__atomic_fetch_or(&(object)->rc, (bits), __ATOMIC_ACQ_REL))
#define KTC2_RC_AND(object, bits) ((void)__atomic_fetch_and(&(object)->rc, (bits), __ATOMIC_ACQ_REL))
#else
#define runtime_enter() ((void)0)
#define runtime_leave() ((void)0)
#define KTC2_RC_OR(object, bits) ((void)((object)->rc |= (bits)))
#define KTC2_RC_AND(object, bits) ((void)((object)->rc &= (bits)))
#endif

#ifdef KTC2_LEAK_CHECK
/** Live objects per type id. */
static int64_t* live_counts;

static void count_live(uint32_t typeId, int delta)
{
	runtime_enter();
	if (!live_counts) live_counts = calloc(ktc2_type_count, sizeof *live_counts);
	if (live_counts && typeId < ktc2_type_count) live_counts[typeId] += delta;
	runtime_leave();
}
#else
#define count_live(typeId, delta) ((void)0)
#endif

#ifdef KTC2_ARC_STATS
uint64_t ktc2_stat_retains;
uint64_t ktc2_stat_releases;
static uint64_t stat_allocs;
#endif

void* ktc2_alloc(size_t size, uint32_t typeId)
{
#ifdef KTC2_ARC_STATS
	KTC2_STAT_INC(stat_allocs);
#endif
	ktc2_Obj* object = calloc(1, size);
	if (!object) fail("java.lang.OutOfMemoryError", NULL);
	object->rc = 1;
	object->typeId = typeId;
	count_live(typeId, 1);
	return object;
}

static void weak_drop(ktc2_Obj* target);

void ktc2_destroy(ktc2_Obj* object)
{
	if (object->rc & KTC2_WEAK) weak_drop(object);
	void (*destroy)(ktc2_Obj*) = ktc2_types[object->typeId].destroy;
	if (destroy) destroy(object);
	/* A stack object only gives up what it refers to; its memory belongs to a frame. */
	if (object->rc & KTC2_STACK) return;
	count_live(object->typeId, -1);
	free(object);
}

static void threads_wait(void);

int ktc2_exit(void)
{
	/* As on the JVM, the program ends when its last thread does. */
	threads_wait();
	fflush(stdout);
#ifdef KTC2_ARC_STATS
	fprintf(stderr, "ktc2: arc: allocs=%" PRIu64 " retains=%" PRIu64 " releases=%" PRIu64 "\n", stat_allocs, ktc2_stat_retains, ktc2_stat_releases);
#endif
#ifdef KTC2_LEAK_CHECK
	for (uint32_t id = 0; live_counts && id < ktc2_type_count; id++) {
		if (live_counts[id] != 0) {
			fprintf(stderr, "ktc2: leak: %" PRId64 " object(s) of type %s\n", live_counts[id], ktc2_types[id].name);
		}
	}
	free(live_counts);
#endif
	return 0;
}

/* ================== */
/* MARK: Arrays       */
/* ================== */

_Noreturn void ktc2_fail_index(int32_t index, int32_t length)
{
	fflush(stdout);
	fprintf(stderr, "Exception in thread \"main\" java.lang.ArrayIndexOutOfBoundsException: Index %" PRId32 " out of bounds for length %" PRId32 "\n", index, length);
	exit(1);
}

ktc2_Array* ktc2_array_new(int32_t length, size_t elementSize, uint32_t typeId)
{
	if (length < 0) {
		char text[16];
		snprintf(text, sizeof text, "%" PRId32, length);
		fail("java.lang.NegativeArraySizeException", text);
	}
	ktc2_Array* array = ktc2_alloc(sizeof(ktc2_Array) + (size_t)length * elementSize, typeId);
	array->length = length;
	array->elementSize = (int32_t)elementSize;
	return array;
}

void ktc2_array_assign(ktc2_Array* slot, int32_t length, uint32_t typeId, size_t elementSize, const ktc2_Array* source)
{
	if (slot == source) return;
	if (source->length != length) {
		char text[96];
		snprintf(text, sizeof text, "an array of %" PRId32 " elements assigned to inline storage of %" PRId32, source->length, length);
		fail("java.lang.IllegalArgumentException", text);
	}
	void** incoming = (void**)(source + 1);
	void** current = (void**)(slot + 1);
	if (typeId == KTC2_TYPE_ARRAY_REF) {
		for (int32_t i = 0; i < length; i++) ktc2_retain(incoming[i]);
		for (int32_t i = 0; i < slot->length; i++) ktc2_release(current[i]);
	}
	memcpy(current, incoming, (size_t)length * elementSize);
	slot->header.rc = KTC2_IMMORTAL;
	slot->header.typeId = typeId;
	slot->length = length;
	slot->elementSize = (int32_t)elementSize;
}

void ktc2_array_destroy(ktc2_Obj* object)
{
	ktc2_Array* array = (ktc2_Array*)object;
	void** elements = (void**)(array + 1);
	for (int32_t i = 0; i < array->length; i++) ktc2_release(elements[i]);
}

/* ================== */
/* MARK: Strings      */
/* ================== */

/** Decodes one code point at [*at] (valid UTF-8 assumed) and advances. */
static uint32_t next_code_point(const char** at)
{
	const unsigned char* p = (const unsigned char*)*at;
	uint32_t c = p[0];
	if (c < 0x80) {
		*at += 1;
	} else if (c < 0xE0) {
		c = ((c & 0x1F) << 6) | (p[1] & 0x3F);
		*at += 2;
	} else if (c < 0xF0) {
		c = ((c & 0x0F) << 12) | ((p[1] & 0x3F) << 6) | (p[2] & 0x3F);
		*at += 3;
	} else {
		c = ((c & 0x07) << 18) | ((p[1] & 0x3F) << 12) | ((p[2] & 0x3F) << 6) | (p[3] & 0x3F);
		*at += 4;
	}
	return c;
}

/** Walks a string as UTF-16 code units. */
typedef struct {
	const char* at;
	const char* end;
	uint16_t pending; /* low surrogate still to deliver, or 0 */
} UnitCursor;

static UnitCursor cursor_of(const ktc2_String* s)
{
	return (UnitCursor){ s->bytes, s->bytes + s->size, 0 };
}

static bool cursor_has(const UnitCursor* cursor)
{
	return cursor->pending || cursor->at < cursor->end;
}

static uint16_t cursor_next(UnitCursor* cursor)
{
	if (cursor->pending) {
		uint16_t low = cursor->pending;
		cursor->pending = 0;
		return low;
	}
	uint32_t c = next_code_point(&cursor->at);
	if (c < 0x10000) return (uint16_t)c;
	c -= 0x10000;
	cursor->pending = (uint16_t)(0xDC00 | (c & 0x3FF));
	return (uint16_t)(0xD800 | (c >> 10));
}

uint16_t ktc2_str_get(const ktc2_String* s, int32_t index)
{
	if (index < 0 || index >= s->length) {
		fflush(stdout);
		fprintf(stderr, "Exception in thread \"main\" java.lang.StringIndexOutOfBoundsException: Index %" PRId32 " out of bounds for length %" PRId32 "\n", index, s->length);
		exit(1);
	}
	if (s->size == s->length) return (unsigned char)s->bytes[index];
	UnitCursor cursor = cursor_of(s);
	uint16_t unit = 0;
	for (int32_t i = 0; i <= index; i++) unit = cursor_next(&cursor);
	return unit;
}

bool ktc2_str_equals(const ktc2_String* a, const ktc2_String* b)
{
	if (a == b) return true;
	if (!a || !b) return false;
	return a->size == b->size && memcmp(a->bytes, b->bytes, (size_t)a->size) == 0;
}

int32_t ktc2_str_compare(const ktc2_String* a, const ktc2_String* b)
{
	UnitCursor ca = cursor_of(a);
	UnitCursor cb = cursor_of(b);
	while (cursor_has(&ca) && cursor_has(&cb)) {
		int32_t ua = cursor_next(&ca);
		int32_t ub = cursor_next(&cb);
		if (ua != ub) return ua - ub;
	}
	return a->length - b->length;
}

int32_t ktc2_str_hashCode(const ktc2_String* s)
{
	uint32_t hash = 0;
	UnitCursor cursor = cursor_of(s);
	while (cursor_has(&cursor)) hash = 31u * hash + cursor_next(&cursor);
	return (int32_t)hash;
}

_Noreturn void ktc2_fail_enum_value(const ktc2_String* name)
{
	fflush(stdout);
	fprintf(stderr, "Exception in thread \"main\" java.lang.IllegalArgumentException: No enum constant %s\n", name->bytes);
	exit(1);
}

int32_t ktc2_hash_float(float v)
{
	if (v != v) return 0x7FC00000;
	uint32_t bits;
	memcpy(&bits, &v, sizeof bits);
	return (int32_t)bits;
}

int32_t ktc2_hash_double(double v)
{
	uint64_t bits = UINT64_C(0x7FF8000000000000);
	if (v == v) memcpy(&bits, &v, sizeof bits);
	return (int32_t)(bits ^ (bits >> 32));
}

static ktc2_String* new_string(const char* bytes, int32_t size, int32_t length)
{
	ktc2_String* s = ktc2_alloc(sizeof(ktc2_String) + (size_t)size + 1, KTC2_TYPE_STRING);
	s->size = size;
	s->length = length;
	memcpy(s->bytes, bytes, (size_t)size);
	return s;
}

/* ================== */
/* MARK: Formatting   */
/* ================== */

/** Encodes one UTF-16 code unit as UTF-8; a lone surrogate becomes '?'. Returns the byte count. */
static int format_char(char* out, uint16_t v)
{
	if (v < 0x80) {
		out[0] = (char)v;
		return 1;
	}
	if (v < 0x800) {
		out[0] = (char)(0xC0 | (v >> 6));
		out[1] = (char)(0x80 | (v & 0x3F));
		return 2;
	}
	if (v >= 0xD800 && v <= 0xDFFF) {
		out[0] = '?';
		return 1;
	}
	out[0] = (char)(0xE0 | (v >> 12));
	out[1] = (char)(0x80 | ((v >> 6) & 0x3F));
	out[2] = (char)(0x80 | (v & 0x3F));
	return 3;
}

/**
 * Formats like Double.toString / Float.toString: the shortest digits that
 * read back to the same value, plain notation for 1e-3 <= |v| < 1e7 and
 * "d.dddE±n" otherwise. Returns the length.
 */
static int format_real(char* out, double v, bool is_float)
{
	if (v != v) return sprintf(out, "NaN");
	if (isinf(v)) return sprintf(out, v > 0 ? "Infinity" : "-Infinity");
	if (v == 0) return sprintf(out, signbit(v) ? "-0.0" : "0.0");

	char buf[48];
	int max_digits = is_float ? 9 : 17;
	for (int prec = 1; prec <= max_digits; prec++) {
		snprintf(buf, sizeof buf, "%.*e", prec - 1, v);
		if (is_float ? strtof(buf, NULL) == (float)v : strtod(buf, NULL) == v) break;
	}

	char digits[24];
	int n = 0;
	const char* p = buf;
	bool negative = *p == '-';
	if (negative) p++;
	for (; *p != 'e'; p++) {
		if (*p != '.') digits[n++] = *p;
	}
	int exp = atoi(p + 1);
	while (n > 1 && digits[n - 1] == '0') n--;

	char* o = out;
	if (negative) *o++ = '-';
	if (exp >= -3 && exp < 7) {
		if (exp < 0) {
			*o++ = '0';
			*o++ = '.';
			for (int i = 0; i < -exp - 1; i++) *o++ = '0';
			for (int i = 0; i < n; i++) *o++ = digits[i];
		} else {
			for (int i = 0; i <= exp; i++) *o++ = i < n ? digits[i] : '0';
			*o++ = '.';
			if (n > exp + 1) {
				for (int i = exp + 1; i < n; i++) *o++ = digits[i];
			} else {
				*o++ = '0';
			}
		}
		*o = 0;
	} else {
		*o++ = digits[0];
		*o++ = '.';
		if (n > 1) {
			for (int i = 1; i < n; i++) *o++ = digits[i];
		} else {
			*o++ = '0';
		}
		o += sprintf(o, "E%d", exp);
	}
	return (int)(o - out);
}

/* ================== */
/* MARK: String builder */
/* ================== */

void ktc2_sb_init(ktc2_Sb* sb)
{
	sb->data = sb->local;
	sb->size = 0;
	sb->capacity = (int32_t)sizeof sb->local;
	sb->length = 0;
}

void ktc2_sb_text(ktc2_Sb* sb, const char* text, int32_t size, int32_t length)
{
	if (sb->size + size > sb->capacity) {
		int32_t capacity = sb->capacity * 2;
		while (capacity < sb->size + size) capacity *= 2;
		char* data = malloc((size_t)capacity);
		if (!data) fail("java.lang.OutOfMemoryError", NULL);
		memcpy(data, sb->data, (size_t)sb->size);
		if (sb->data != sb->local) free(sb->data);
		sb->data = data;
		sb->capacity = capacity;
	}
	memcpy(sb->data + sb->size, text, (size_t)size);
	sb->size += size;
	sb->length += length;
}

void ktc2_sb_string(ktc2_Sb* sb, const ktc2_String* s)
{
	if (s) ktc2_sb_text(sb, s->bytes, s->size, s->length); else ktc2_sb_text(sb, "null", 4, 4);
}

ktc2_String* ktc2_sb_finish(ktc2_Sb* sb)
{
	ktc2_String* s = new_string(sb->data, sb->size, sb->length);
	if (sb->data != sb->local) free(sb->data);
	return s;
}

ktc2_String* ktc2_sb_finish_into(ktc2_Sb* sb, void* storage, size_t capacity)
{
	if (sizeof(ktc2_String) + (size_t)sb->size + 1 > capacity) return ktc2_sb_finish(sb);
	ktc2_String* s = storage;
	s->header.rc = KTC2_STACK | 1;
	s->header.typeId = KTC2_TYPE_STRING;
	s->size = sb->size;
	s->length = sb->length;
	memcpy(s->bytes, sb->data, (size_t)sb->size);
	s->bytes[sb->size] = 0;
	if (sb->data != sb->local) free(sb->data);
	return s;
}

/* Each value kind is formatted once, into a small buffer, for both the builder and stdout. */
#define KTC2_FORMATTED(name, type, format_call) \
	void ktc2_sb_##name(ktc2_Sb* sb, type v) \
	{ \
		char text[48]; \
		int size = format_call; \
		ktc2_sb_text(sb, text, size, size); \
	} \
	void ktc2_print_##name(type v) \
	{ \
		char text[48]; \
		int size = format_call; \
		fwrite(text, 1, (size_t)size, stdout); \
	}

KTC2_FORMATTED(bool, bool, sprintf(text, "%s", v ? "true" : "false"))
KTC2_FORMATTED(int, int32_t, sprintf(text, "%" PRId32, v))
KTC2_FORMATTED(long, int64_t, sprintf(text, "%" PRId64, v))
KTC2_FORMATTED(uint, uint32_t, sprintf(text, "%" PRIu32, v))
KTC2_FORMATTED(ulong, uint64_t, sprintf(text, "%" PRIu64, v))
KTC2_FORMATTED(float, float, format_real(text, v, true))
KTC2_FORMATTED(double, double, format_real(text, v, false))

void ktc2_sb_char(ktc2_Sb* sb, uint16_t v)
{
	char text[4];
	ktc2_sb_text(sb, text, format_char(text, v), 1);
}

void ktc2_print_char(uint16_t v)
{
	char text[4];
	fwrite(text, 1, (size_t)format_char(text, v), stdout);
}

void ktc2_print_text(const char* text, int32_t size, int32_t length)
{
	(void)length;
	fwrite(text, 1, (size_t)size, stdout);
}

void ktc2_print_string(const ktc2_String* s)
{
	if (s) fwrite(s->bytes, 1, (size_t)s->size, stdout); else fputs("null", stdout);
}

/* ================== */
/* MARK: Any defaults */
/* ================== */

int32_t ktc2_any_hashCode(ktc2_Obj* object)
{
	const ktc2_Box* box = (const ktc2_Box*)object;
	switch (object->typeId) {
	case KTC2_TYPE_STRING: return ktc2_str_hashCode((ktc2_String*)object);
	case KTC2_TYPE_BOX_BOOLEAN: return ktc2_hash_boolean(box->value.boolean_);
	case KTC2_TYPE_BOX_BYTE: return box->value.byte_;
	case KTC2_TYPE_BOX_SHORT: return box->value.short_;
	case KTC2_TYPE_BOX_INT: return box->value.int_;
	case KTC2_TYPE_BOX_LONG: return ktc2_hash_long(box->value.long_);
	case KTC2_TYPE_BOX_CHAR: return box->value.char_;
	case KTC2_TYPE_BOX_FLOAT: return ktc2_hash_float(box->value.float_);
	case KTC2_TYPE_BOX_DOUBLE: return ktc2_hash_double(box->value.double_);
	case KTC2_TYPE_BOX_UBYTE: return box->value.ubyte_;
	case KTC2_TYPE_BOX_USHORT: return box->value.ushort_;
	case KTC2_TYPE_BOX_UINT: return (int32_t)box->value.uint_;
	case KTC2_TYPE_BOX_ULONG: return ktc2_hash_ulong(box->value.ulong_);
	default: {
		uintptr_t address = (uintptr_t)object;
		return (int32_t)((address >> 3) ^ (address >> 35));
	}
	}
}

bool ktc2_any_equals(ktc2_Obj* a, ktc2_Obj* b)
{
	if (a == b) return true;
	if (!a || !b || a->typeId != b->typeId) return false;
	const ktc2_Box* x = (const ktc2_Box*)a;
	const ktc2_Box* y = (const ktc2_Box*)b;
	switch (a->typeId) {
	case KTC2_TYPE_STRING: return ktc2_str_equals((ktc2_String*)a, (ktc2_String*)b);
	case KTC2_TYPE_BOX_BOOLEAN: return x->value.boolean_ == y->value.boolean_;
	case KTC2_TYPE_BOX_BYTE: return x->value.byte_ == y->value.byte_;
	case KTC2_TYPE_BOX_SHORT: return x->value.short_ == y->value.short_;
	case KTC2_TYPE_BOX_INT: return x->value.int_ == y->value.int_;
	case KTC2_TYPE_BOX_LONG: return x->value.long_ == y->value.long_;
	case KTC2_TYPE_BOX_CHAR: return x->value.char_ == y->value.char_;
	/* Boxed floating-point values compare by bits, as on the JVM: NaN equals NaN, 0.0 differs from -0.0. */
	case KTC2_TYPE_BOX_FLOAT: return ktc2_hash_float(x->value.float_) == ktc2_hash_float(y->value.float_);
	case KTC2_TYPE_BOX_DOUBLE: return memcmp(&x->value.double_, &y->value.double_, sizeof(double)) == 0 || (x->value.double_ != x->value.double_ && y->value.double_ != y->value.double_);
	case KTC2_TYPE_BOX_UBYTE: return x->value.ubyte_ == y->value.ubyte_;
	case KTC2_TYPE_BOX_USHORT: return x->value.ushort_ == y->value.ushort_;
	case KTC2_TYPE_BOX_UINT: return x->value.uint_ == y->value.uint_;
	case KTC2_TYPE_BOX_ULONG: return x->value.ulong_ == y->value.ulong_;
	default: return false;
	}
}

ktc2_String* ktc2_any_toString(ktc2_Obj* object)
{
	if (object->typeId == KTC2_TYPE_STRING) return ktc2_retain(object);
	const ktc2_Box* box = (const ktc2_Box*)object;
	ktc2_Sb sb;
	ktc2_sb_init(&sb);
	switch (object->typeId) {
	case KTC2_TYPE_BOX_BOOLEAN: ktc2_sb_bool(&sb, box->value.boolean_); break;
	case KTC2_TYPE_BOX_BYTE: ktc2_sb_int(&sb, box->value.byte_); break;
	case KTC2_TYPE_BOX_SHORT: ktc2_sb_int(&sb, box->value.short_); break;
	case KTC2_TYPE_BOX_INT: ktc2_sb_int(&sb, box->value.int_); break;
	case KTC2_TYPE_BOX_LONG: ktc2_sb_long(&sb, box->value.long_); break;
	case KTC2_TYPE_BOX_CHAR: ktc2_sb_char(&sb, box->value.char_); break;
	case KTC2_TYPE_BOX_FLOAT: ktc2_sb_float(&sb, box->value.float_); break;
	case KTC2_TYPE_BOX_DOUBLE: ktc2_sb_double(&sb, box->value.double_); break;
	case KTC2_TYPE_BOX_UBYTE: ktc2_sb_uint(&sb, box->value.ubyte_); break;
	case KTC2_TYPE_BOX_USHORT: ktc2_sb_uint(&sb, box->value.ushort_); break;
	case KTC2_TYPE_BOX_UINT: ktc2_sb_uint(&sb, box->value.uint_); break;
	case KTC2_TYPE_BOX_ULONG: ktc2_sb_ulong(&sb, box->value.ulong_); break;
	default: {
		char text[160];
		int size = snprintf(text, sizeof text, "%s@%" PRIx32, ktc2_types[object->typeId].name, (uint32_t)ktc2_any_hashCode(object));
		ktc2_sb_text(&sb, text, size, size);
	}
	}
	return ktc2_sb_finish(&sb);
}

int32_t ktc2_any_compareTo(ktc2_Obj* a, ktc2_Obj* b)
{
	if (a->typeId != b->typeId) ktc2_fail_cast();
	const ktc2_Box* x = (const ktc2_Box*)a;
	const ktc2_Box* y = (const ktc2_Box*)b;
	switch (a->typeId) {
	case KTC2_TYPE_STRING: return ktc2_str_compare((ktc2_String*)a, (ktc2_String*)b);
	case KTC2_TYPE_BOX_BOOLEAN: return KTC2_CMP(x->value.boolean_, y->value.boolean_);
	case KTC2_TYPE_BOX_BYTE: return KTC2_CMP(x->value.byte_, y->value.byte_);
	case KTC2_TYPE_BOX_SHORT: return KTC2_CMP(x->value.short_, y->value.short_);
	case KTC2_TYPE_BOX_INT: return KTC2_CMP(x->value.int_, y->value.int_);
	case KTC2_TYPE_BOX_LONG: return KTC2_CMP(x->value.long_, y->value.long_);
	case KTC2_TYPE_BOX_CHAR: return KTC2_CMP(x->value.char_, y->value.char_);
	case KTC2_TYPE_BOX_FLOAT: return KTC2_CMP(x->value.float_, y->value.float_);
	case KTC2_TYPE_BOX_DOUBLE: return KTC2_CMP(x->value.double_, y->value.double_);
	case KTC2_TYPE_BOX_UBYTE: return KTC2_CMP(x->value.ubyte_, y->value.ubyte_);
	case KTC2_TYPE_BOX_USHORT: return KTC2_CMP(x->value.ushort_, y->value.ushort_);
	case KTC2_TYPE_BOX_UINT: return KTC2_CMP(x->value.uint_, y->value.uint_);
	case KTC2_TYPE_BOX_ULONG: return KTC2_CMP(x->value.ulong_, y->value.ulong_);
	default: ktc2_fail_dispatch();
	}
}

/* ================== */
/* MARK: CharSequence */
/* ================== */

int32_t ktc2_cs_length(ktc2_Obj* object)
{
	if (object->typeId != KTC2_TYPE_STRING) ktc2_fail_dispatch();
	return ((ktc2_String*)object)->length;
}

uint16_t ktc2_cs_get(ktc2_Obj* object, int32_t index)
{
	if (object->typeId != KTC2_TYPE_STRING) ktc2_fail_dispatch();
	return ktc2_str_get((ktc2_String*)object, index);
}

_Noreturn void ktc2_fail_throw(const ktc2_String* exception, const ktc2_String* message)
{
	fflush(stdout);
	fprintf(stderr, "Exception in thread \"main\" %s%s%s\n", exception->bytes, message ? ": " : "", message ? message->bytes : "");
	exit(1);
}

ktc2_String* ktc2_stringOfChars(ktc2_Array* chars, int32_t start, int32_t length)
{
	const uint16_t* units = (const uint16_t*)(chars + 1);
	if (start < 0 || length < 0 || start + length > chars->length) ktc2_fail_index(start + length, chars->length);
	ktc2_Sb sb;
	ktc2_sb_init(&sb);
	for (int32_t i = start; i < start + length; i++) {
		uint16_t unit = units[i];
		/* A surrogate pair is one code point: four UTF-8 bytes, two units of Kotlin length. */
		if (unit >= 0xD800 && unit <= 0xDBFF && i + 1 < start + length && units[i + 1] >= 0xDC00 && units[i + 1] <= 0xDFFF) {
			uint32_t point = 0x10000 + (((uint32_t)unit - 0xD800) << 10) + (units[i + 1] - 0xDC00);
			char text[4] = {
				(char)(0xF0 | (point >> 18)),
				(char)(0x80 | ((point >> 12) & 0x3F)),
				(char)(0x80 | ((point >> 6) & 0x3F)),
				(char)(0x80 | (point & 0x3F)),
			};
			ktc2_sb_text(&sb, text, 4, 2);
			i++;
		} else {
			ktc2_sb_char(&sb, unit);
		}
	}
	return ktc2_sb_finish(&sb);
}

/** The byte offset of UTF-16 unit [index] in a non-ASCII string (at a code point boundary). */
static int32_t byte_offset_of_unit(const ktc2_String* s, int32_t index)
{
	const char* at = s->bytes;
	const char* end = s->bytes + s->size;
	int32_t unit = 0;
	while (at < end && unit < index) {
		uint32_t point = next_code_point(&at);
		unit += point >= 0x10000 ? 2 : 1;
	}
	return (int32_t)(at - s->bytes);
}

ktc2_String* ktc2_stringSubstring(ktc2_String* s, int32_t start, int32_t end)
{
	if (start < 0 || end > s->length || start > end) {
		fflush(stdout);
		fprintf(stderr, "Exception in thread \"main\" java.lang.StringIndexOutOfBoundsException: begin %" PRId32 ", end %" PRId32 ", length %" PRId32 "\n", start, end, s->length);
		exit(1);
	}
	if (start == 0 && end == s->length) return ktc2_retain(s);
	if (s->size == s->length) return new_string(s->bytes + start, end - start, end - start);
	int32_t from = byte_offset_of_unit(s, start);
	int32_t to = byte_offset_of_unit(s, end);
	return new_string(s->bytes + from, to - from, end - start);
}

bool ktc2_stringToDouble(ktc2_String* s, ktc2_Array* result)
{
	const char* at = s->bytes;
	const char* last = s->bytes + s->size;
	while (at < last && (unsigned char)*at <= ' ') at++;
	while (last > at && (unsigned char)last[-1] <= ' ') last--;
	if (at == last) return false;
	/* Java accepts a trailing type suffix. */
	if (last - at > 1 && (last[-1] == 'd' || last[-1] == 'D' || last[-1] == 'f' || last[-1] == 'F')) last--;
	char text[400];
	size_t size = (size_t)(last - at);
	if (size >= sizeof text) return false;
	memcpy(text, at, size);
	text[size] = 0;
	/* strtod also takes "inf" and "nan"; Java only "Infinity" and "NaN". */
	const char* digits = text + (text[0] == '-' || text[0] == '+');
	if ((digits[0] < '0' || digits[0] > '9') && digits[0] != '.' && strcmp(digits, "Infinity") != 0 && strcmp(digits, "NaN") != 0) return false;
	char* stop = NULL;
	double value = strtod(text, &stop);
	if (stop == text || *stop != 0) return false;
	((double*)(result + 1))[0] = value;
	return true;
}

ktc2_Array* ktc2_stringUtf8(ktc2_String* s)
{
	ktc2_Array* bytes = ktc2_array_new(s->size, 1, KTC2_TYPE_ARRAY_BYTE);
	memcpy(bytes + 1, s->bytes, (size_t)s->size);
	return bytes;
}

ktc2_String* ktc2_stringOfUtf8(ktc2_Array* bytes, int32_t start, int32_t end)
{
	if (start < 0 || end > bytes->length || start > end) ktc2_fail_index(end, bytes->length);
	const char* data = (const char*)(bytes + 1) + start;
	int32_t size = end - start;
	int32_t length = 0;
	for (int32_t i = 0; i < size; i++) {
		unsigned char byte = (unsigned char)data[i];
		/* Count code units: one per lead byte, two for a four-byte sequence. */
		if ((byte & 0xC0) != 0x80) length += byte >= 0xF0 ? 2 : 1;
	}
	return new_string(data, size, length);
}

ktc2_Obj* ktc2_cs_subSequence(ktc2_Obj* object, int32_t start, int32_t end)
{
	if (object->typeId != KTC2_TYPE_STRING) ktc2_fail_dispatch();
	return (ktc2_Obj*)ktc2_stringSubstring((ktc2_String*)object, start, end);
}

/* ================== */
/* MARK: Weak         */
/* ================== */

/**
 * Weak references go through a cell. The cell is an ordinary counted object, owned by the
 * weak references; the target does not know it, apart from the KTC2_WEAK bit in its count.
 * A table from target to cell lets the dying target find its cell and clear it, so objects
 * that are never weakly referenced pay nothing.
 */
typedef struct WeakCell {
	ktc2_Obj header;
	ktc2_Obj* target;        /* NULL once the target is destroyed */
	struct WeakCell* next;   /* next cell of the same bucket */
} WeakCell;

static WeakCell** weak_buckets;
static size_t weak_bucket_count;
static size_t weak_cell_count;

static size_t weak_hash(const ktc2_Obj* target, size_t bucketCount)
{
	return (size_t)((((uint64_t)(uintptr_t)target >> 3) * UINT64_C(0x9E3779B97F4A7C15)) >> 32) & (bucketCount - 1);
}

/** The link that holds the cell of [target]; the cell is known to be in the table. */
static WeakCell** weak_link(const ktc2_Obj* target)
{
	WeakCell** link = &weak_buckets[weak_hash(target, weak_bucket_count)];
	while ((*link)->target != target) link = &(*link)->next;
	return link;
}

static void weak_insert(WeakCell* cell)
{
	if (weak_cell_count >= weak_bucket_count) {
		size_t count = weak_bucket_count ? weak_bucket_count * 2 : 64;
		WeakCell** buckets = calloc(count, sizeof *buckets);
		if (!buckets) fail("java.lang.OutOfMemoryError", NULL);
		for (size_t i = 0; i < weak_bucket_count; i++) {
			for (WeakCell* moved = weak_buckets[i]; moved;) {
				WeakCell* next = moved->next;
				size_t slot = weak_hash(moved->target, count);
				moved->next = buckets[slot];
				buckets[slot] = moved;
				moved = next;
			}
		}
		free(weak_buckets);
		weak_buckets = buckets;
		weak_bucket_count = count;
	}
	size_t slot = weak_hash(cell->target, weak_bucket_count);
	cell->next = weak_buckets[slot];
	weak_buckets[slot] = cell;
	weak_cell_count++;
}

static void weak_remove(const ktc2_Obj* target)
{
	WeakCell** link = weak_link(target);
	*link = (*link)->next;
	weak_cell_count--;
}

/** The target is being destroyed: its cell reads NULL from now on. */
static void weak_drop(ktc2_Obj* target)
{
	runtime_enter();
	WeakCell* cell = *weak_link(target);
	weak_remove(target);
	cell->target = NULL;
	runtime_leave();
}

/**
 * Takes a reference to [object] unless its count already reached zero — another thread is
 * then about to destroy it, and is waiting for the runtime lock the caller holds.
 */
static ktc2_Obj* retain_if_alive(ktc2_Obj* object)
{
	if (!object) return NULL;
	uint32_t rc = KTC2_RC_LOAD(object);
	if (rc == KTC2_IMMORTAL) return object;
#ifdef KTC2_THREADS
	while ((rc & KTC2_COUNT_MASK) != 0) {
		if (__atomic_compare_exchange_n(&object->rc, &rc, rc + 1, true, __ATOMIC_ACQ_REL, __ATOMIC_RELAXED)) return object;
	}
	return NULL;
#else
	object->rc++;
	return object;
#endif
}

ktc2_Obj* ktc2_weakCell(ktc2_Obj* target)
{
	if (!target) return NULL;
	if (target->rc == KTC2_IMMORTAL) {
		/* Never destroyed: the cell needs no tracking. */
		WeakCell* cell = ktc2_alloc(sizeof *cell, KTC2_TYPE_WEAK_CELL);
		cell->target = target;
		return &cell->header;
	}
	WeakCell* fresh = NULL;
	runtime_enter();
	WeakCell* cell = NULL;
	if (KTC2_RC_LOAD(target) & KTC2_WEAK) {
		WeakCell* existing = *weak_link(target);
		cell = (WeakCell*)retain_if_alive(&existing->header);
		if (!cell) {
			/* The last weak reference is going away right now: its cell is replaced. */
			weak_remove(target);
			existing->target = NULL;
		}
	}
	runtime_leave();
	if (cell) return &cell->header;
	fresh = ktc2_alloc(sizeof *fresh, KTC2_TYPE_WEAK_CELL);
	fresh->target = target;
	runtime_enter();
	if (KTC2_RC_LOAD(target) & KTC2_WEAK) {
		/* Another thread made the cell in the meantime. */
		cell = (WeakCell*)retain_if_alive(&(*weak_link(target))->header);
	}
	if (!cell) {
		if (KTC2_RC_LOAD(target) & KTC2_WEAK) {
			WeakCell* dying = *weak_link(target);
			weak_remove(target);
			dying->target = NULL;
		}
		weak_insert(fresh);
		KTC2_RC_OR(target, KTC2_WEAK);
		cell = fresh;
		fresh = NULL;
	}
	runtime_leave();
	if (fresh) {
		fresh->target = NULL;
		ktc2_release(fresh);
	}
	return &cell->header;
}

ktc2_Obj* ktc2_weakGet(ktc2_Obj* object)
{
	WeakCell* cell = (WeakCell*)object;
	if (!cell) return NULL;
	runtime_enter();
	ktc2_Obj* target = retain_if_alive(cell->target);
	runtime_leave();
	return target;
}

void ktc2_weak_cell_destroy(ktc2_Obj* object)
{
	WeakCell* cell = (WeakCell*)object;
	runtime_enter();
	if (cell->target && KTC2_RC_LOAD(cell->target) != KTC2_IMMORTAL) {
		weak_remove(cell->target);
		KTC2_RC_AND(cell->target, ~KTC2_WEAK);
	}
	runtime_leave();
}

/* ================== */
/* MARK: Concurrency  */
/* ================== */

#ifdef KTC2_THREADS

/** `ktc2.internal.threadEntry`, generated with the program: calls `run` of the Thread object. */
void ktc2_internal_threadEntry(ktc2_Obj* thread);

/** Threads started and not yet ended. */
static uint32_t threads_running;

static void thread_body(ktc2_Obj* thread)
{
	ktc2_internal_threadEntry(thread);
	if (ktc2_has_pending()) ktc2_uncaught();
	ktc2_release(thread);
	__atomic_fetch_sub(&threads_running, 1, __ATOMIC_RELEASE);
}

static void threads_wait(void)
{
	while (__atomic_load_n(&threads_running, __ATOMIC_ACQUIRE) != 0) thread_yield();
}

#ifdef _WIN32

static DWORD WINAPI thread_main(LPVOID argument)
{
	thread_body(argument);
	return 0;
}

int64_t ktc2_threadStart(ktc2_Obj* thread)
{
	ktc2_retain(thread);
	__atomic_fetch_add(&threads_running, 1, __ATOMIC_RELEASE);
	HANDLE handle = CreateThread(NULL, 0, thread_main, thread, 0, NULL);
	if (!handle) fail("java.lang.OutOfMemoryError", "unable to create native thread");
	return (int64_t)(intptr_t)handle;
}

void ktc2_threadJoin(int64_t handle)
{
	WaitForSingleObject((HANDLE)(intptr_t)handle, INFINITE);
	CloseHandle((HANDLE)(intptr_t)handle);
}

#else

static void* thread_main(void* argument)
{
	thread_body(argument);
	return NULL;
}

int64_t ktc2_threadStart(ktc2_Obj* thread)
{
	pthread_t* handle = malloc(sizeof *handle);
	ktc2_retain(thread);
	__atomic_fetch_add(&threads_running, 1, __ATOMIC_RELEASE);
	if (!handle || pthread_create(handle, NULL, thread_main, thread) != 0) fail("java.lang.OutOfMemoryError", "unable to create native thread");
	return (int64_t)(intptr_t)handle;
}

void ktc2_threadJoin(int64_t handle)
{
	pthread_t* thread = (pthread_t*)(intptr_t)handle;
	pthread_join(*thread, NULL);
	free(thread);
}

#endif

/* The thread holding the monitor (the address of one of its thread-locals), and how many times. */
static KTC2_TLS char monitor_self;
static uintptr_t monitor_owner;
static uint32_t monitor_depth;

void ktc2_monitorEnter(ktc2_Obj* lock)
{
	if (!lock) ktc2_fail_null();
	uintptr_t self = (uintptr_t)&monitor_self;
	if (__atomic_load_n(&monitor_owner, __ATOMIC_ACQUIRE) != self) {
		uintptr_t nobody = 0;
		while (!__atomic_compare_exchange_n(&monitor_owner, &nobody, self, false, __ATOMIC_ACQ_REL, __ATOMIC_RELAXED)) {
			nobody = 0;
			thread_yield();
		}
	}
	monitor_depth++;
}

void ktc2_monitorExit(ktc2_Obj* lock)
{
	(void)lock;
	if (--monitor_depth == 0) __atomic_store_n(&monitor_owner, 0, __ATOMIC_RELEASE);
}

#else

static void threads_wait(void) {}

/* Without threads nothing can contend for a monitor. */
void ktc2_monitorEnter(ktc2_Obj* lock)
{
	if (!lock) ktc2_fail_null();
}

void ktc2_monitorExit(ktc2_Obj* lock)
{
	(void)lock;
}

#endif

/* ================== */
/* MARK: Exceptions   */
/* ================== */

KTC2_TLS ktc2_Obj* ktc2_pending;

void ktc2_throw(ktc2_Obj* exception)
{
	if (!exception) ktc2_fail_null();
	ktc2_retain(exception);
	ktc2_release(ktc2_pending);
	ktc2_pending = exception;
}

ktc2_Obj* ktc2_take_pending(void)
{
	ktc2_Obj* exception = ktc2_pending;
	ktc2_pending = NULL;
	return exception;
}

ktc2_String* ktc2_throwable_toString(ktc2_Obj* exception, ktc2_String* message)
{
	ktc2_Sb sb;
	ktc2_sb_init(&sb);
	const char* name = ktc2_types[exception->typeId].name;
	int32_t size = (int32_t)strlen(name);
	ktc2_sb_text(&sb, name, size, size);
	if (message) {
		ktc2_sb_text(&sb, ": ", 2, 2);
		ktc2_sb_string(&sb, message);
	}
	return ktc2_sb_finish(&sb);
}

_Noreturn void ktc2_uncaught(void)
{
	const ktc2_Throwable* exception = (const ktc2_Throwable*)ktc2_pending;
	fail(ktc2_types[exception->header.typeId].name, exception->message ? exception->message->bytes : NULL);
}
