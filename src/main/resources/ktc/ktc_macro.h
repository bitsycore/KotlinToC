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

/*
 * Two-step expansion for KTC_RELATED and KTC_METHOD:
 * The ## operator suppresses pre-expansion of its operands, so passing
 * CLS directly to a macro that uses T##_##NAME would paste the literal
 * token "CLS" instead of its expansion. The extra indirection level
 * (__KTC_RELATED_ / __KTC_METHOD_) forces CLS to be fully expanded as
 * a normal argument before it reaches the ##.
 */

#define __IMPL_KTC_RELATED(T, NAME) \
	T##_##NAME

#define __KTC_RELATED_(T, NAME) \
	__IMPL_KTC_RELATED(T, NAME)

#define KTC_RELATED(NAME) \
	__KTC_RELATED_(CLS, NAME)

#define __IMPL_KTC_METHOD(RETURN, T, NAME) \
	RETURN T##_##NAME

#define __KTC_METHOD_(RETURN, T, NAME) \
	__IMPL_KTC_METHOD(RETURN, T, NAME)

#define KTC_METHOD(RETURN, NAME) \
	__KTC_METHOD_(RETURN, CLS, NAME)

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

/* =========================================================
 * Interface macros
 * ========================================================= */

/*
 * KTC_UNION_MEMBER(TYPE, NAME): emits one union member.
 * TYPE is the C struct type (e.g. ktc_std_Heap_t for object singletons).
 * NAME is the base used for the field name: NAME##_data (e.g. ktc_std_Heap_data).
 * The two differ for object singletons where the type carries a _t suffix.
 */
#define KTC_UNION_MEMBER(TYPE, NAME) \
	TYPE NAME##_data;

/*
 * Paste helper: expands T fully (since it arrives without ##) then appends _vt.
 * Needed because ## suppresses pre-expansion of its operands — without this
 * indirection, CLS_##_vt would paste the literal token "CLS" instead of
 * the expanded type name like "ktc_std_Allocator".
 */
#define __KTC_IFACE_VT_IMPL(T) T##_vt
#define __KTC_IFACE_VT(T) __KTC_IFACE_VT_IMPL(T)

/*
 * Two-step helper so CLS and CLS_OPT are fully expanded before ## pasting.
 * CLS_ and CLS_OPT_ receive the already-expanded values of CLS / CLS_OPT.
 * __KTC_IFACE_VT(CLS_) forces CLS_ to be expanded before the _vt paste.
 */
#define __KTC_INTERFACE_IMPL(CLS_, CLS_OPT_, VTABLE_BODY, CONCRETE_TYPES) \
	typedef struct __KTC_IFACE_VT(CLS_) VTABLE_BODY __KTC_IFACE_VT(CLS_); \
	typedef struct CLS_ { \
		ktc_core_AnySupertype __base; \
		union { CONCRETE_TYPES(KTC_UNION_MEMBER) } data; \
		const __KTC_IFACE_VT(CLS_)* vt; \
	} CLS_; \
	KTC_DEFINE_OPT_NAMED(CLS_, CLS_OPT_)

/*
 * KTC_INTERFACE(VTABLE_BODY, CONCRETE_TYPES): defines a complete interface type.
 * Requires CLS and CLS_OPT to be #defined before invocation.
 * VTABLE_BODY is the { ... } struct body with function-pointer declarations (;-separated).
 * CONCRETE_TYPES is an X-macro listing every concrete implementor: #define CLS_TYPES(X) X(T1) X(T2)
 */
#define KTC_INTERFACE(VTABLE_BODY, CONCRETE_TYPES) \
	__KTC_INTERFACE_IMPL(CLS, CLS_OPT, VTABLE_BODY, CONCRETE_TYPES)

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
	typedef struct OPT {     \
		ktc_OptionalTag tag; \
		NOT_OPT value;       \
	} OPT

/**
 * Define Type and it's Optional wrapper
 */
#define KTC_CLASS(TYPE, TYPE_OPT, BODY) \
    typedef struct TYPE { BODY } TYPE; \
    KTC_DEFINE_OPT_NAMED(TYPE, TYPE_OPT)

/*
 * Paste helper for the _t suffix used by object singleton struct types.
 * The ## operator suppresses pre-expansion of its operand, so a single-level
 * CLS_##_t would paste the literal token "CLS" instead of its expanded value.
 * Two levels of indirection force full expansion before the ## paste.
 */
#define __KTC_OBJ_T_IMPL(T) T##_t
#define __KTC_OBJ_T(T) __KTC_OBJ_T_IMPL(T)

/*
 * KTC_OBJECT(BODY): defines a singleton object struct (CLS_t) and its extern instance.
 * Requires CLS to be #defined before invocation.
 * BODY is the { ... } struct body.
 */
#define __KTC_OBJECT_IMPL(CLS_, BODY) \
	typedef struct __KTC_OBJ_T(CLS_) { BODY } __KTC_OBJ_T(CLS_); \
	extern __KTC_OBJ_T(CLS_) CLS_

#define KTC_OBJECT(BODY) \
	__KTC_OBJECT_IMPL(CLS, BODY)

/*
 * KTC_TLS_OBJECT(BODY): same as KTC_OBJECT but the extern instance is thread-local.
 */
#define __KTC_TLS_OBJECT_IMPL(CLS_, BODY) \
	typedef struct __KTC_OBJ_T(CLS_) { BODY } __KTC_OBJ_T(CLS_); \
	extern ktc_core_tls __KTC_OBJ_T(CLS_) CLS_

#define KTC_TLS_OBJECT(BODY) \
	__KTC_TLS_OBJECT_IMPL(CLS, BODY)

/*
 * KTC_TYPE_ID(ID): defines CLS_TYPE_ID as an enum constant.
 * Requires CLS to be #defined before invocation.
 * Uses enum instead of #define so the constant is scoped and type-safe.
 * Three levels of indirection are required: ## suppresses pre-expansion of its
 * operands, so CLS must be fully expanded before reaching the ## paste step.
 * Level 1 (KTC_TYPE_ID): passes CLS as a non-parameter token so it expands during rescan.
 * Level 2 (__IMPL_KTC_TYPE_ID): receives the expanded name, forwards without ##.
 * Level 3 (__IMPL_KTC_TYPE_ID_2): performs the ## paste on the already-expanded name.
 */
#define __IMPL_KTC_TYPE_ID_2(NAME, ID) \
	enum { NAME##_TYPE_ID = ID };
#define __IMPL_KTC_TYPE_ID(NAME, ID) __IMPL_KTC_TYPE_ID_2(NAME, ID)
#define KTC_TYPE_ID(ID) __IMPL_KTC_TYPE_ID(CLS, ID)

#endif