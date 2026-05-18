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
 * KTC_TYPE_NAME directly to a macro that uses T##_##NAME would paste the literal
 * token "KTC_TYPE_NAME" instead of its expansion. The extra indirection level
 * (__KTC_RELATED_ / __KTC_METHOD_) forces KTC_TYPE_NAME to be fully expanded as
 * a normal argument before it reaches the ##.
 */

#define __IMPL_KTC_RELATED(T, NAME) \
	T##_##NAME

#define __KTC_RELATED_(T, NAME) \
	__IMPL_KTC_RELATED(T, NAME)

#define KTC_RELATED(NAME) \
	__KTC_RELATED_(KTC_TYPE_NAME, NAME)

#define __IMPL_KTC_METHOD(RETURN, T, NAME) \
	RETURN T##_##NAME

#define __KTC_METHOD_(RETURN, T, NAME) \
	__IMPL_KTC_METHOD(RETURN, T, NAME)

#define KTC_METHOD(RETURN, NAME) \
	__KTC_METHOD_(RETURN, KTC_TYPE_NAME, NAME)

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
 * indirection, KTC_TYPE_NAME_##_vt would paste the literal token "KTC_TYPE_NAME"
 * instead of the expanded type name like "ktc_std_Allocator".
 */
#define __KTC_IFACE_VT_IMPL(T) T##_vt
#define __KTC_IFACE_VT(T) __KTC_IFACE_VT_IMPL(T)

/*
 * Two-step helper so KTC_TYPE_NAME and KTC_OPT_TYPE_NAME are fully expanded before ## pasting.
 * CLS_ and CLS_OPT_ receive the already-expanded values of KTC_TYPE_NAME / KTC_OPT_TYPE_NAME.
 * __KTC_IFACE_VT(CLS_) forces CLS_ to be expanded before the _vt paste.
 */
#define __KTC_INTERFACE_IMPL(CLS_, CLS_OPT_, VTABLE_BODY, CONCRETE_TYPES) \
	typedef struct __KTC_IFACE_VT(CLS_) VTABLE_BODY __KTC_IFACE_VT(CLS_); \
	typedef struct CLS_ { \
		ktc_core_AnyData __base; \
		union { CONCRETE_TYPES(KTC_UNION_MEMBER) } data; \
		const __KTC_IFACE_VT(CLS_)* vt; \
	} CLS_; \
	KTC_DEFINE_OPT_NAMED(CLS_, CLS_OPT_)

/*
 * KTC_INTERFACE(VTABLE_BODY, CONCRETE_TYPES): defines a complete interface type.
 * Requires KTC_TYPE_NAME and KTC_OPT_TYPE_NAME to be #defined before invocation.
 * VTABLE_BODY is the { ... } struct body with function-pointer declarations (;-separated).
 * CONCRETE_TYPES is an X-macro listing every concrete implementor: #define CLS_TYPES(X) X(T1) X(T2)
 */
#define KTC_INTERFACE(VTABLE_BODY, CONCRETE_TYPES) \
	__KTC_INTERFACE_IMPL(KTC_TYPE_NAME, KTC_OPT_TYPE_NAME, VTABLE_BODY, CONCRETE_TYPES)

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
 * KTC_TYPE_NAME_##_t would paste the literal token "KTC_TYPE_NAME" instead of its expanded value.
 * Two levels of indirection force full expansion before the ## paste.
 */
#define __KTC_OBJ_T_IMPL(T) T##_t
#define __KTC_OBJ_T(T) __KTC_OBJ_T_IMPL(T)

/*
 * KTC_OBJECT(BODY): defines a singleton object struct (KTC_TYPE_NAME_t) and its extern instance.
 * Requires KTC_TYPE_NAME to be #defined before invocation.
 * BODY is the { ... } struct body.
 */
#define __KTC_OBJECT_IMPL(CLS_, BODY) \
	typedef struct __KTC_OBJ_T(CLS_) { BODY } __KTC_OBJ_T(CLS_); \
	extern __KTC_OBJ_T(CLS_) CLS_

#define KTC_OBJECT(BODY) \
	__KTC_OBJECT_IMPL(KTC_TYPE_NAME, BODY)

/*
 * KTC_TLS_OBJECT(BODY): same as KTC_OBJECT but the extern instance is thread-local.
 */
#define __KTC_TLS_OBJECT_IMPL(CLS_, BODY) \
	typedef struct __KTC_OBJ_T(CLS_) { BODY } __KTC_OBJ_T(CLS_); \
	extern ktc_core_tls __KTC_OBJ_T(CLS_) CLS_

#define KTC_TLS_OBJECT(BODY) \
	__KTC_TLS_OBJECT_IMPL(KTC_TYPE_NAME, BODY)

/*
 * KTC_TYPE_ID(ID): defines KTC_TYPE_NAME_TYPE_ID as an enum constant.
 * Requires KTC_TYPE_NAME to be #defined before invocation.
 * Uses enum instead of #define so the constant is scoped and type-safe.
 * Three levels of indirection are required: ## suppresses pre-expansion of its
 * operands, so KTC_TYPE_NAME must be fully expanded before reaching the ## paste step.
 * Level 1 (KTC_TYPE_ID): passes KTC_TYPE_NAME as a non-parameter token so it expands during rescan.
 * Level 2 (__IMPL_KTC_TYPE_ID): receives the expanded name, forwards without ##.
 * Level 3 (__IMPL_KTC_TYPE_ID_2): performs the ## paste on the already-expanded name.
 */
#define __IMPL_KTC_TYPE_ID_2(NAME, ID) \
	enum { NAME##_TYPE_ID = ID };
#define __IMPL_KTC_TYPE_ID(NAME, ID) __IMPL_KTC_TYPE_ID_2(NAME, ID)
#define KTC_TYPE_ID(ID) __IMPL_KTC_TYPE_ID(KTC_TYPE_NAME, ID)

/* =========================================================
 * Type-ID constants and read helper
 *
 * typeId is ktc_UInt (uint32_t) so bit 31 is the disposed flag
 * and all valid IDs fit cleanly in bits 0-30.
 *
 * KTC_UNDEFINED_TYPE_ID — sentinel for uninitialized instances.
 *   Value 0xFFFFFFFE (UINT32_MAX-1) is outside the valid ID range
 *   and outside any disposed-flag combination.
 *
 * KTC_GET_TYPEID — strips bit 31 before comparisons so is/as
 *   checks work even on disposed objects if needed.
 * ========================================================= */
#define KTC_UNDEFINED_TYPE_ID ((ktc_UInt)0xFFFFFFFEU)
#define KTC_GET_TYPEID(x)     ((ktc_UInt)(x) & (ktc_UInt)0x7FFFFFFFU)

/* =========================================================
 * Dispose-tracking helpers
 *
 * Define one or both mode flags before the generated header to
 * activate runtime dispose tracking.  The MSB of typeId (bit 31)
 * is the disposed flag; KTC_GET_TYPEID strips it for type checks.
 *
 * Use-after-dispose — controls KTC_ASSERT_NOT_DISPOSED:
 *   KTC_DISPOSED_ASSERT  abort with stacktrace (ASSERT mode)
 *   KTC_DISPOSED_LOG     print stacktrace and continue (LOG mode)
 *   (neither)            silent no-op (NO / default)
 *
 * Double-dispose — controls the guard inside KTC_MARK_DISPOSED:
 *   KTC_DOUBLE_DISPOSE_ASSERT  abort with stacktrace
 *   KTC_DOUBLE_DISPOSE_LOG     print stacktrace and continue
 *   (neither)                  silent no-op (NO / default)
 *
 * ktc_core_stacktrace_print is declared in ktc_core.h which is
 * always included before user code expands these macros.
 * ========================================================= */
#if defined(KTC_DISPOSED_ASSERT) || defined(KTC_DISPOSED_LOG) || \
    defined(KTC_DOUBLE_DISPOSE_ASSERT) || defined(KTC_DOUBLE_DISPOSE_LOG)

#define KTC_DISPOSED_FLAG ((ktc_UInt)0x80000000)

/* KTC_ASSERT_NOT_DISPOSED — inserted at the top of every non-dispose method */
#if defined(KTC_DISPOSED_ASSERT)
#define KTC_ASSERT_NOT_DISPOSED(self) \
    do { \
        if ((self)->__base.typeId & KTC_DISPOSED_FLAG) { \
            static const char _ktc_msg[] = "This instance is already disposed"; \
            ktc_core_stacktrace_print(_ktc_msg, (int)(sizeof(_ktc_msg) - 1)); \
            exit(1); \
        } \
    } while (0)
#elif defined(KTC_DISPOSED_LOG)
#define KTC_ASSERT_NOT_DISPOSED(self) \
    do { \
        if ((self)->__base.typeId & KTC_DISPOSED_FLAG) { \
            static const char _ktc_msg[] = "Warning: method called on disposed instance"; \
            ktc_core_stacktrace_print(_ktc_msg, (int)(sizeof(_ktc_msg) - 1)); \
        } \
    } while (0)
#else
#define KTC_ASSERT_NOT_DISPOSED(self) ((void)(self))
#endif

/* _KTC_DOUBLE_DISPOSE_HANDLE — inline action on double-dispose */
#if defined(KTC_DOUBLE_DISPOSE_ASSERT)
#define _KTC_DOUBLE_DISPOSE_HANDLE \
    do { \
        static const char _ktc_msg[] = "Already Disposed"; \
        ktc_core_stacktrace_print(_ktc_msg, (int)(sizeof(_ktc_msg) - 1)); \
        exit(1); \
    } while (0)
#elif defined(KTC_DOUBLE_DISPOSE_LOG)
#define _KTC_DOUBLE_DISPOSE_HANDLE \
    do { \
        static const char _ktc_msg[] = "Warning: dispose() called on already-disposed instance"; \
        ktc_core_stacktrace_print(_ktc_msg, (int)(sizeof(_ktc_msg) - 1)); \
    } while (0)
#else
#define _KTC_DOUBLE_DISPOSE_HANDLE do { (void)0; } while (0)
#endif

/* KTC_MARK_DISPOSED — inserted at the top of dispose(); sets bit 31 */
#define KTC_MARK_DISPOSED(self) \
    do { \
        if ((self)->__base.typeId & KTC_DISPOSED_FLAG) { \
            _KTC_DOUBLE_DISPOSE_HANDLE; \
        } \
        (self)->__base.typeId |= KTC_DISPOSED_FLAG; \
    } while (0)

#else
/* No dispose tracking (NO mode / default) */
#define KTC_MARK_DISPOSED(self)       ((void)(self))
#define KTC_ASSERT_NOT_DISPOSED(self) ((void)(self))
#endif

#endif