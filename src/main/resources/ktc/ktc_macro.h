#ifndef KTC_TYPES_H
#define KTC_TYPES_H

/* =========================================================
 * Optional / Array type constructors
 * ========================================================= */

#define KTC_OPT_TYPE_IMPL_(T)  T##$Opt
#define KTC_OPT_TYPE(T) KTC_OPT_TYPE_IMPL_(T)

#define KTC_ARRAY_TYPE_IMPL_(T, N) ktc_Array_##T##_##N
#define KTC_ARRAY_TYPE(T, N) KTC_ARRAY_TYPE_IMPL_(T, N)

#define KTC_OPT_ARRAY_TYPE_IMPL_(T, N) ktc_Array$Opt$_##T##_##N
#define KTC_OPT_ARRAY_TYPE(T, N) KTC_OPT_ARRAY_TYPE_IMPL_(T, N)

/* =========================================================
 * Generic type mangling, Macro removed, handled by Transpiler
 * Map<String, Int>
 * pkg_Map_ktc_String_ktc_Int
 * package_typename_arg1_arg2_argn
 * Map<String, Int>?
 * pkg_Map$Opt_ktc_String_ktc_Int
 * package_typename$Opt_arg1_arg2_argn
 * arg expand to the same rules
 * ========================================================= */

/* =========================================================
 * Methods & Related to Type Name
 * ========================================================= */

#define __IMPL_KTC_RELATED(T, NAME) \
	T##_##NAME

#define KTC_RELATED(NAME) \
	__IMPL_KTC_RELATED(CLS, NAME)

#define __IMPL_KTC_METHOD(RETURN, T, NAME) \
	RETURN T##_##NAME

#define KTC_METHOD(RETURN, NAME) \
	__IMPL_KTC_METHOD(RETURN, CLS, NAME)

/* =========================================================
 * Optional helpers
 * ========================================================= */

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

// ===================================================================
// DEFINITIONS MACROS
// ===================================================================

/**
 * Optional type definition <br>
 * T? — requires ktc_OptionalTag to be declared (from ktc_core.h)
 */
#define KTC_DEFINE_OPT(T)                 \
	typedef struct KTC_OPT_TYPE(T) {  \
		ktc_OptionalTag tag;              \
		T value;                          \
	} KTC_OPT_TYPE(T)

/**
 * Fixed array definition <br>
 * @Size(N) Array<T>
 */
#define KTC_DEFINE_ARRAY(T, N)        \
	typedef struct KTC_ARRAY_TYPE(T, N) { \
		T arr[N];                     \
	} KTC_ARRAY_TYPE(T, N)

/**
 * Optional fixed array definition <br>
 * @Size(N) Array<T>? — KTC_DEFINE_ARRAY(T, N) must come first
 */
#define KTC_DEFINE_OPT_ARRAY(T, N)                 \
	typedef struct KTC_OPT_ARRAY_TYPE(T, N) {   \
		ktc_OptionalTag tag;                       \
		KTC_ARRAY_TYPE(T, N) value;                \
	} KTC_OPT_ARRAY_TYPE(T, N)

/**
 * Optional type definition with inlined named type and type opt
 */
#define KTC_DEFINE_OPT_NAMED(NOT_OPT, OPT) \
	typedef struct {         \
		ktc_OptionalTag tag; \
		NOT_OPT value;       \
	} OPT

/**
 * Define Type and it's Optional wrapper
 */
#define KTC_TYPE(TYPE, TYPE_OPT, BODY) \
    typedef struct TYPE { BODY } TYPE; \
    KTC_DEFINE_OPT_NAMED(TYPE, TYPE_OPT)

#endif

// Example of expected result using the macro utilities
// /* ═══════════════════════════════════════════════════════════
//  * class ListIterator<Float?> : Iterator<Float?>
//  * package: ktc.std
//  * file: Collections.kt
//  * mangled: ktc_std_ListIterator_Float$Opt
//  * typeid: 49
//  * ═══════════════════════════════════════════════════════════ */
// #define CLS ktc_std_ListIterator_ktc_Float$Opt
// #define CLS_OPT ktc_std_ListIterator$Opt_ktc_Float$Opt
// #define ktc_std_ListIterator_Float$Opt_TYPE_ID 49
//
// typedef struct {
// 	ktc_core_AnySupertype __base;
// 	/*VAL*/ ktc_Float$Opt * buf; /** notnull */
// 	ktc_Int buf$len;
// 	/*VAL*/ ktc_Int size;
// 	/*VAR*/ ktc_Int idx;
// } CLS;
//
// typedef struct {
// 	ktc_OptionalTag tag;
// 	CLS value;
// } CLS_OPT;
//
// // ════ constructors ════
// KTC_METHOD(CLS, primaryConstructor)(ktc_Float$Opt* buf, ktc_Int buf$len, ktc_Int size);
//
// // ════ implements Iterator<Float?> (Iterator.kt) ════
// KTC_METHOD(ktc_Bool, hasNext)(CLS* $self);
// KTC_METHOD(ktc_Float$Opt, next)(CLS* $self);
//
// // ════ implements Any ════
// KTC_METHOD(ktc_Int, hashCode)(CLS $self);
// KTC_METHOD(ktc_Bool, equals)(CLS a, CLS b);
// KTC_METHOD(void, toString)(CLS* $self, ktc_StrBuf* sb); // max output: 34 chars
// #define ktc_std_ListIterator_Float$Opt_dispose(self) ((void)(self))
//
// // ════ Any cast ════
// KTC_METHOD(ktc_Any, as_Any)(CLS* $self);
// extern const ktc_core_AnyVt KTC_RELATED(AnyVt);
//
// #undef CLS
// #undef CLS_OPT
// /* ═══════════════════════════════════════════════════════════
//  * END class ListIterator<Float?> : Iterator<Float?>
//  * ═══════════════════════════════════════════════════════════ */