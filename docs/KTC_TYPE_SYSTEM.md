# KTC Type System — Macro Cheat Sheet

# IMPORTANT
# Deprecate generic macro support and just process it on transpiller side
# follow @Desired_Header.h design style

## Naming conventions

| Kotlin type    | C name               |
|----------------|----------------------|
| `T?`           | `T$Opt`              |
| `Array<T, N>`  | `ktc_Array_T_N`      |
| `Array<T, N>?` | `ktc_Array$Opt$_T_N` |
| `Foo<A>`       | `Foo_A`              |
| `Foo<A, B>`    | `Foo_A_B`            |
| `Foo<A>?`      | `Foo$Opt_A`          |
| `Foo<A, B>?`   | `Foo$Opt_A_B`        |

> Non-generic user types are always `package_TypeName`  
> e.g. `Vec2` defined in package `game` → `game_Vec2`

---

## Macros — type construction
# DONT USE GENERIC MACROS, JUST PROCESS IT ON TRANSPILLER SIDE THEY WILL BE REMOVED
# follow @Desired_Header.h design style
```c
/* T?  →  T$Opt */
KTC_OPT_TYPE(T)
// KTC_OPT_TYPE(ktc_Int) → ktc_Int$Opt

/* Array<T, N>  →  ktc_Array_T_N */
KTC_ARRAY_TYPE(T, N)
// KTC_ARRAY_TYPE(ktc_Int, 4) → ktc_Array_ktc_Int_4

/* Array<T, N>?  →  ktc_Array$Opt$_T_N */
KTC_OPT_ARRAY_TYPE(T, N)
// KTC_OPT_ARRAY_TYPE(ktc_Int, 4) → ktc_Array$Opt$_ktc_Int_4

/* Base<A, B>  →  Base_A_B  (variadic, any arity) */
KTC_GENERIC_TYPE(Base, ...)
// KTC_GENERIC_TYPE(ktc_Map, ktc_String, ktc_Int) → ktc_Map_ktc_String_ktc_Int

/* Base<A, B>?  →  Base$Opt_A_B  (variadic) */
KTC_OPT_GENERIC_TYPE(Base, ...)
// KTC_OPT_GENERIC_TYPE(ktc_Map, ktc_String, ktc_Int) → ktc_Map$Opt_ktc_String_ktc_Int

/* Type_funcName */
KTC_FUNCTION_NAME(Type, name)
// KTC_FUNCTION_NAME(ktc_Map_ktc_String_ktc_Int, get) → ktc_Map_ktc_String_ktc_Int_get
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
// KTC_DEFINE_OPT_GENERIC(ktc_Map, ktc_String, ktc_Int)
// → typedef struct ktc_Map$Opt_ktc_String_ktc_Int { ktc_OptionalTag tag; ktc_Map_ktc_String_ktc_Int value; } ...
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
/* → ktc_Map_ktc_Int$Opt_List_ktc_String */
```

---

## Generated code pattern (generic class)

```c
/* Forward declarations */
typedef struct KTC_GENERIC_TYPE(pkg_Base, ArgType) KTC_GENERIC_TYPE(pkg_Base, ArgType);
typedef struct pkg_Base$Opt_ArgType pkg_Base$Opt_ArgType;

/* Struct definition */
struct KTC_GENERIC_TYPE(pkg_Base, ArgType) {
    ktc_core_AnySupertype __base;
    /* fields with concrete types */
};
KTC_DEFINE_OPT_GENERIC(pkg_Base, ArgType);

/* Constructor */
KTC_GENERIC_TYPE(pkg_Base, ArgType) KTC_FUNCTION_NAME(KTC_GENERIC_TYPE(pkg_Base, ArgType), primaryConstructor)(ArgType item);
```
