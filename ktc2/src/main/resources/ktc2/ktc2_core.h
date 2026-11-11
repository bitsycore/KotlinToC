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
#include <stdint.h>

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
void ktc2_print_str(const char* text);
void ktc2_print_bool(bool v);
void ktc2_print_int(int32_t v);
void ktc2_print_long(int64_t v);
void ktc2_print_uint(uint32_t v);
void ktc2_print_ulong(uint64_t v);
void ktc2_print_char(uint16_t v);
void ktc2_print_float(float v);
void ktc2_print_double(double v);

#endif
