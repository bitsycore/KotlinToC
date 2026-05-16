# KTC Type System — Macro Cheat Sheet

# IMPORTANT
# Deprecate generic macro support and just process it on transpiller side
# follow @Desired_Header.h design type

## Naming conventions

| Kotlin type    | C name               |
|----------------|----------------------|
| `T?`           | `T$Opt`              |
| `Array<T, N>`  | `ktc_Array_T_N`      |
| `Array<T, N>?` | `ktc_Array$Opt$_T_N` |
| `Foo<A>`       | `Foo$1_A`            |
| `Foo<A, B>`    | `Foo$2_A_B`          |
| `Foo<A>?`      | `Foo$Opt$1_A`        |
| `Foo<A, B>?`   | `Foo$Opt$2_A_B`      |

> Non-generic user types are always `package_TypeName`  
> e.g. `Vec2` defined in package `game` → `game_Vec2`

---

## Macros — type construction

```c
/* T?  →  T$Opt */
KTC_OPT_TYPE(T)
// KTC_OPT_TYPE(Int) → Int$Opt

/* Array<T, N>  →  ktc_Array_T_N */
KTC_ARRAY_TYPE(T, N)
// KTC_ARRAY_TYPE(Int, 4) → ktc_Array_Int_4

/* Array<T, N>?  →  ktc_Array$Opt$_T_N */
KTC_OPT_ARRAY_TYPE(T, N)
// KTC_OPT_ARRAY_TYPE(Int, 4) → ktc_Array$Opt$_Int_4

/* Base<A, B>  →  Base$2_A_B  (variadic, any arity) */
KTC_GENERIC_TYPE(Base, ...)
// KTC_GENERIC_TYPE(Map, String, Int) → Map$2_String_Int

/* Base<A, B>?  →  Base$Opt$2_A_B  (variadic) */
KTC_OPT_GENERIC_TYPE(Base, ...)
// KTC_OPT_GENERIC_TYPE(Map, String, Int) → Map$Opt$2_String_Int

/* Type_funcName */
KTC_FUNCTION_NAME(Type, name)
// KTC_FUNCTION_NAME(Map$2_String_Int, get) → Map$2_String_Int_get
```

---

## Macros — struct definitions (generated headers)

```c
/* T? struct */
KTC_DEFINE_OPT(T)
// → typedef struct T$Opt { ktc_OptionalTag tag; T value; } T$Opt

/* @Size(N) Array<T> struct */
KTC_DEFINE_ARRAY(T, N)
// → typedef struct ktc_Array_T_N { T arr[N]; } ktc_Array_T_N

/* @Size(N) Array<T>? struct  — KTC_DEFINE_ARRAY must come first */
KTC_DEFINE_OPT_ARRAY(T, N)
// → typedef struct ktc_Array$Opt$_T_N { ktc_OptionalTag tag; ktc_Array_T_N value; } ...

/* Base<...>? struct  — plain struct must come first */
KTC_DEFINE_OPT_GENERIC(Base, ...)
// KTC_DEFINE_OPT_GENERIC(Map, String, Int)
// → typedef struct Map$Opt$2_String_Int { ktc_OptionalTag tag; Map$2_String_Int value; } ...
```

---

## Macros — optional value helpers

```c
KTC_SOME(T, v)    /* wrap value  → (T$Opt){ .tag = ktc_SOME, .value = (v) } */
KTC_NONE(T)       /* empty       → (T$Opt){ .tag = ktc_NONE } */
KTC_IS_SOME(v)    /* check some  → (v).tag == ktc_SOME */
KTC_IS_NONE(v)    /* check none  → (v).tag == ktc_NONE */
KTC_UNWRAP(v)     /* get value   → (v).value */
```

---

## Composition rule

Any type arg can itself be a composed type:

```c
KTC_GENERIC_TYPE(
    Map,
    KTC_OPT_TYPE(Int),          /* Int? */
    KTC_GENERIC_TYPE(List, String)   /* List<String> */
)
/* → Map$2_Int$Opt_List$1_String */
```

---

## Generated code pattern (generic class)

```c
/* Forward declarations */
typedef struct KTC_GENERIC_TYPE(pkg_Base, ArgType) KTC_GENERIC_TYPE(pkg_Base, ArgType);
typedef struct pkg_Base$Opt$1_ArgType pkg_Base$Opt$1_ArgType;

/* Struct definition */
struct KTC_GENERIC_TYPE(pkg_Base, ArgType) {
    ktc_core_AnySupertype __base;
    /* fields with concrete types */
};
KTC_DEFINE_OPT_GENERIC(pkg_Base, ArgType);

/* Constructor */
KTC_GENERIC_TYPE(pkg_Base, ArgType) KTC_FUNCTION_NAME(KTC_GENERIC_TYPE(pkg_Base, ArgType), primaryConstructor)(ArgType item);
```
