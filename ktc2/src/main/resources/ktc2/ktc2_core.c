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

#ifdef KTC2_LEAK_CHECK
/** Live objects per type id. */
static int64_t* live_counts;

static void count_live(uint32_t typeId, int delta)
{
	if (!live_counts) live_counts = calloc(ktc2_type_count, sizeof *live_counts);
	if (live_counts && typeId < ktc2_type_count) live_counts[typeId] += delta;
}
#else
#define count_live(typeId, delta) ((void)0)
#endif

void* ktc2_alloc(size_t size, uint32_t typeId)
{
	ktc2_Obj* object = calloc(1, size);
	if (!object) fail("java.lang.OutOfMemoryError", NULL);
	object->rc = 1;
	object->typeId = typeId;
	count_live(typeId, 1);
	return object;
}

void ktc2_destroy(ktc2_Obj* object)
{
	void (*destroy)(ktc2_Obj*) = ktc2_types[object->typeId].destroy;
	if (destroy) destroy(object);
	count_live(object->typeId, -1);
	free(object);
}

int ktc2_exit(void)
{
	fflush(stdout);
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

static ktc2_String* new_string(const char* bytes, int32_t size, int32_t length)
{
	ktc2_String* s = ktc2_alloc(sizeof(ktc2_String) + (size_t)size + 1, KTC2_TYPE_STRING);
	s->size = size;
	s->length = length;
	memcpy(s->bytes, bytes, (size_t)size);
	return s;
}

int32_t ktc2_any_hashCode(ktc2_Obj* object)
{
	if (object->typeId == KTC2_TYPE_STRING) return ktc2_str_hashCode((ktc2_String*)object);
	uintptr_t address = (uintptr_t)object;
	return (int32_t)((address >> 3) ^ (address >> 35));
}

bool ktc2_any_equals(ktc2_Obj* a, ktc2_Obj* b)
{
	if (a == b) return true;
	if (!a || !b) return false;
	if (a->typeId == KTC2_TYPE_STRING && b->typeId == KTC2_TYPE_STRING) {
		return ktc2_str_equals((ktc2_String*)a, (ktc2_String*)b);
	}
	return false;
}

ktc2_String* ktc2_any_toString(ktc2_Obj* object)
{
	if (object->typeId == KTC2_TYPE_STRING) return ktc2_retain(object);
	char text[160];
	int size = snprintf(text, sizeof text, "%s@%" PRIx32, ktc2_types[object->typeId].name, (uint32_t)ktc2_any_hashCode(object));
	return new_string(text, size, size);
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
