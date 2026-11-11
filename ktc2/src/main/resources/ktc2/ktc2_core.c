#include "ktc2_core.h"

#include <inttypes.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

_Noreturn void ktc2_fail_div_zero(void)
{
	fflush(stdout);
	fputs("Exception in thread \"main\" java.lang.ArithmeticException: / by zero\n", stderr);
	exit(1);
}

void ktc2_check_step(int64_t step)
{
	if (step > 0) return;
	fflush(stdout);
	fprintf(stderr, "Exception in thread \"main\" java.lang.IllegalArgumentException: Step must be positive, was: %" PRId64 ".\n", step);
	exit(1);
}

void ktc2_print_str(const char* text)
{
	fputs(text, stdout);
}

void ktc2_print_bool(bool v)
{
	fputs(v ? "true" : "false", stdout);
}

void ktc2_print_int(int32_t v)
{
	printf("%" PRId32, v);
}

void ktc2_print_long(int64_t v)
{
	printf("%" PRId64, v);
}

void ktc2_print_uint(uint32_t v)
{
	printf("%" PRIu32, v);
}

void ktc2_print_ulong(uint64_t v)
{
	printf("%" PRIu64, v);
}

/** Writes one UTF-16 code unit as UTF-8; a lone surrogate prints as '?'. */
void ktc2_print_char(uint16_t v)
{
	if (v < 0x80) {
		putchar(v);
	} else if (v < 0x800) {
		putchar(0xC0 | (v >> 6));
		putchar(0x80 | (v & 0x3F));
	} else if (v >= 0xD800 && v <= 0xDFFF) {
		putchar('?');
	} else {
		putchar(0xE0 | (v >> 12));
		putchar(0x80 | ((v >> 6) & 0x3F));
		putchar(0x80 | (v & 0x3F));
	}
}

/**
 * Formats like Double.toString / Float.toString: the shortest digits that
 * read back to the same value, plain notation for 1e-3 <= |v| < 1e7 and
 * "d.dddE±n" otherwise.
 */
static void format_real(char* out, double v, bool is_float)
{
	if (v != v) {
		strcpy(out, "NaN");
		return;
	}
	if (isinf(v)) {
		strcpy(out, v > 0 ? "Infinity" : "-Infinity");
		return;
	}
	if (v == 0) {
		strcpy(out, signbit(v) ? "-0.0" : "0.0");
		return;
	}

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
		sprintf(o, "E%d", exp);
	}
}

void ktc2_print_float(float v)
{
	char text[48];
	format_real(text, v, true);
	fputs(text, stdout);
}

void ktc2_print_double(double v)
{
	char text[48];
	format_real(text, v, false);
	fputs(text, stdout);
}
