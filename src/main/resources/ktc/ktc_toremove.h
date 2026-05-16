#ifndef KTC_MANGLE_H
#define KTC_MANGLE_H

/* ========================================================= */
/* Basic concatenation helpers                                */
/* ========================================================= */

#define KTC_CAT(a, b) a##b
#define KTC_CAT3(a, b, c) a##b##c
#define KTC_EXPAND(x) x

#define KTC_DEFINE_OPT_GENERIC(BASE, ...)                      \
    typedef struct KTC_OPT_GENERIC_TYPE(BASE, __VA_ARGS__) {   \
        ktc_OptionalTag tag;                                   \
        KTC_GENERIC_TYPE(BASE, __VA_ARGS__) value;             \
    } KTC_OPT_GENERIC_TYPE(BASE, __VA_ARGS__)

/* ========================================================= */
/* Optional / array type constructors                         */
/* ========================================================= */

/*
	Two-step expansion: the _IMPL_ level is the one that uses ##.
	The public macro forces T to be fully expanded first, so that
	KTC_OPT_TYPE(KTC_GENERIC_TYPE(...)) works correctly.
	Without this indirection ## suppresses pre-expansion and the
	last token of the inner call's token sequence (")") would be
	pasted with "$Opt", producing invalid output.
*/
#define KTC_OPT_TYPE_IMPL_(T)  T##$Opt
#define KTC_OPT_TYPE(T)        KTC_OPT_TYPE_IMPL_(T)

#define KTC_ARRAY_TYPE_IMPL_(T, N)  ktc_Array_##T##_##N
#define KTC_ARRAY_TYPE(T, N)        KTC_ARRAY_TYPE_IMPL_(T, N)

#define KTC_OPT_ARRAY_TYPE_IMPL_(T, N)  ktc_Array$Opt$_##T##_##N
#define KTC_OPT_ARRAY_TYPE(T, N)        KTC_OPT_ARRAY_TYPE_IMPL_(T, N)


/* ========================================================= */
/* Variadic arg counting                                      */
/* ========================================================= */

#define KTC_VA_NARGS_IMPL( \
	_1, _2, _3, _4, _5, \
	_6, _7, _8, _9, _10, \
	N, ...) N

#define KTC_VA_NARGS(...) \
	KTC_EXPAND( \
		KTC_VA_NARGS_IMPL( \
			__VA_ARGS__, \
			10, 9, 8, 7, 6, \
			5, 4, 3, 2, 1 \
		) \
	)


/* ========================================================= */
/* Generic type mangling                                      */
/* ========================================================= */

#define KTC_GENERIC_TYPE_1(BASE, A1) \
	BASE##$1_##A1

#define KTC_GENERIC_TYPE_2(BASE, A1, A2) \
	BASE##$2_##A1##_##A2

#define KTC_GENERIC_TYPE_3(BASE, A1, A2, A3) \
	BASE##$3_##A1##_##A2##_##A3

#define KTC_GENERIC_TYPE_4(BASE, A1, A2, A3, A4) \
	BASE##$4_##A1##_##A2##_##A3##_##A4

#define KTC_GENERIC_TYPE_5(BASE, A1, A2, A3, A4, A5) \
	BASE##$5_##A1##_##A2##_##A3##_##A4##_##A5

#define KTC_GENERIC_TYPE_6(BASE, A1, A2, A3, A4, A5, A6) \
	BASE##$6_##A1##_##A2##_##A3##_##A4##_##A5##_##A6

#define KTC_GENERIC_TYPE_7(BASE, A1, A2, A3, A4, A5, A6, A7) \
	BASE##$7_##A1##_##A2##_##A3##_##A4##_##A5##_##A6##_##A7

#define KTC_GENERIC_TYPE_8(BASE, A1, A2, A3, A4, A5, A6, A7, A8) \
	BASE##$8_##A1##_##A2##_##A3##_##A4##_##A5##_##A6##_##A7##_##A8

#define KTC_GENERIC_TYPE_9(BASE, A1, A2, A3, A4, A5, A6, A7, A8, A9) \
	BASE##$9_##A1##_##A2##_##A3##_##A4##_##A5##_##A6##_##A7##_##A8##_##A9

#define KTC_GENERIC_TYPE_10(BASE, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10) \
	BASE##$10_##A1##_##A2##_##A3##_##A4##_##A5##_##A6##_##A7##_##A8##_##A9##_##A10


/* ========================================================= */
/* Generic dispatch                                           */
/* ========================================================= */

#define KTC_GENERIC_SELECT(N) \
	KTC_CAT(KTC_GENERIC_TYPE_, N)

/*
	Usage:

	KTC_GENERIC_TYPE(pkg_Map, ktc_String, ktc_Int)

	=>
	pkg_Map$2_ktc_String_ktc_Int
*/

#define KTC_GENERIC_TYPE(BASE, ...) \
	KTC_EXPAND( \
		KTC_GENERIC_SELECT( \
			KTC_VA_NARGS(__VA_ARGS__) \
		)(BASE, __VA_ARGS__) \
	)


/* ========================================================= */
/* Optional generic mangling                                  */
/* ========================================================= */

#define KTC_OPT_GENERIC_TYPE_1(BASE, A1) \
	BASE##$Opt$1_##A1

#define KTC_OPT_GENERIC_TYPE_2(BASE, A1, A2) \
	BASE##$Opt$2_##A1##_##A2

#define KTC_OPT_GENERIC_TYPE_3(BASE, A1, A2, A3) \
	BASE##$Opt$3_##A1##_##A2##_##A3

#define KTC_OPT_GENERIC_TYPE_4(BASE, A1, A2, A3, A4) \
	BASE##$Opt$4_##A1##_##A2##_##A3##_##A4

#define KTC_OPT_GENERIC_TYPE_5(BASE, A1, A2, A3, A4, A5) \
	BASE##$Opt$5_##A1##_##A2##_##A3##_##A4##_##A5

#define KTC_OPT_GENERIC_TYPE_6(BASE, A1, A2, A3, A4, A5, A6) \
	BASE##$Opt$6_##A1##_##A2##_##A3##_##A4##_##A5##_##A6

#define KTC_OPT_GENERIC_TYPE_7(BASE, A1, A2, A3, A4, A5, A6, A7) \
	BASE##$Opt$7_##A1##_##A2##_##A3##_##A4##_##A5##_##A6##_##A7

#define KTC_OPT_GENERIC_TYPE_8(BASE, A1, A2, A3, A4, A5, A6, A7, A8) \
	BASE##$Opt$8_##A1##_##A2##_##A3##_##A4##_##A5##_##A6##_##A7##_##A8

#define KTC_OPT_GENERIC_TYPE_9(BASE, A1, A2, A3, A4, A5, A6, A7, A8, A9) \
	BASE##$Opt$9_##A1##_##A2##_##A3##_##A4##_##A5##_##A6##_##A7##_##A8##_##A9

#define KTC_OPT_GENERIC_TYPE_10(BASE, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10) \
	BASE##$Opt$10_##A1##_##A2##_##A3##_##A4##_##A5##_##A6##_##A7##_##A8##_##A9##_##A10


#define KTC_OPT_GENERIC_SELECT(N) \
	KTC_CAT(KTC_OPT_GENERIC_TYPE_, N)

/*
	Usage:

	KTC_OPT_GENERIC_TYPE(
		pkg_Map,
		ktc_String,
		ktc_Int
	)

	=>
	pkg_Map$Opt$2_ktc_String_ktc_Int
*/

#define KTC_OPT_GENERIC_TYPE(BASE, ...) \
	KTC_EXPAND( \
		KTC_OPT_GENERIC_SELECT( \
			KTC_VA_NARGS(__VA_ARGS__) \
		)(BASE, __VA_ARGS__) \
	)


/* ========================================================= */
/* Function mangling                                          */
/* ========================================================= */

/*
	T_name

	Example:

	KTC_FUNCTION_NAME(
		KTC_GENERIC_TYPE(pkg_Map, ktc_String, ktc_Int),
		get
	)

	=>
	pkg_Map$2_ktc_String_ktc_Int_get
*/

#define KTC_FUNCTION_NAME(T, NAME) \
	T##_##NAME


/* ========================================================= */
/* Optional helpers                                           */
/* ========================================================= */

#define KTC_SOME(T, v) \
	((KTC_OPT_TYPE(T)){ \
		.tag = ktc_SOME, \
		.value = (v) \
	})

#define KTC_NONE(T) \
	((KTC_OPT_TYPE(T)){ \
		.tag = ktc_NONE \
	})

#define KTC_IS_SOME(v) \
	((v).tag == ktc_SOME)

#define KTC_IS_NONE(v) \
	((v).tag == ktc_NONE)

#define KTC_UNWRAP(v) \
	((v).value)


#endif
