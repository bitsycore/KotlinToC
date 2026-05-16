# MACRO_PLAN — ktc_mangle.h + ktc_types.h integration

## What was added

Two new runtime headers alongside `ktc_core.h`:

- `src/main/resources/ktc/ktc_mangle.h` — naming macros, variadic generic dispatch, optional access helpers
- `src/main/resources/ktc/ktc_types.h`  — struct-definition macros (`KTC_DEFINE_OPT`, `KTC_DEFINE_ARRAY`, `KTC_DEFINE_OPT_ARRAY`)

Both are included at the top of `ktc_core.h`. `Main.kt` copies all four ktc files on transpile.

---

## Naming conventions (final)

| Kotlin type | C name |
|-------------|--------|
| `T?` | `T$Opt` |
| `Array<T, N>` | `ktc_Array_T_N` |
| `Array<T, N>?` | `ktc_Array$Opt$_T_N` |
| `Foo<A>` | `Foo$1_A` |
| `Foo<A, B>` | `Foo$2_A_B` |
| `Foo<A>?` | `Foo$Opt$1_A` |
| `Foo<A, B>?` | `Foo$Opt$2_A_B` |

---

## Macro reference

### Naming / type construction

```c
KTC_OPT_TYPE(T)                  // T$Opt
KTC_ARRAY_TYPE(T, N)             // ktc_Array_T_N
KTC_OPT_ARRAY_TYPE(T, N)         // ktc_Array$Opt$_T_N
KTC_GENERIC_TYPE(BASE, ...)      // BASE$N_A1_..._AN  (N = arg count, variadic)
KTC_OPT_GENERIC_TYPE(BASE, ...)  // BASE$Opt$N_A1_..._AN
KTC_FUNCTION_NAME(T, NAME)       // T_NAME
```

### Struct definitions (in generated headers)

```c
KTC_DEFINE_OPT(T)                // typedef struct T$Opt { ktc_OptionalTag tag; T value; } T$Opt
KTC_DEFINE_ARRAY(T, N)           // typedef struct ktc_Array_T_N { T arr[N]; } ktc_Array_T_N
KTC_DEFINE_OPT_ARRAY(T, N)       // typedef struct ktc_Array$Opt$_T_N { ktc_OptionalTag tag; ktc_Array_T_N value; } ...
```

### Optional value helpers (in generated .c code)

```c
KTC_SOME(T, v)    // (T$Opt){ .tag = ktc_SOME, .value = (v) }
KTC_NONE(T)       // (T$Opt){ .tag = ktc_NONE }
KTC_IS_SOME(v)    // (v).tag == ktc_SOME
KTC_IS_NONE(v)    // (v).tag == ktc_NONE
KTC_UNWRAP(v)     // (v).value
```

---

## ✅ Completed steps

### Step 1 — ktc_core.h migration
- [x] Add `#include "ktc_mangle.h"` and `#include "ktc_types.h"`
- [x] Replace all `KTC_OPTIONAL(T)` callsites → `KTC_DEFINE_OPT(T)` (12 primitives + String)
- [x] `KTC_OPTIONAL_GENERIC_NAME` / `KTC_OPTIONAL_GENERIC` kept as backwards-compat stubs
- [x] `Main.kt` copies `ktc_mangle.h`, `ktc_types.h`, `ktc_core.h`, `ktc_core.c`

### Step 2 — `$Opt` rename in codegen + `$N_` arity markers
- [x] `CCodeGen.kt` — `optCTypeName`, `mangledGenericName`, `genericOptionalCName`, `optTypeArgComponent`
- [x] `CCodeGenEmit.kt` — replaced `KTC_OPTIONAL_GENERIC` calls with direct typedef emit
- [x] `CoreTypes.kt` — `Nullable.toCType()` → `$Opt` suffix
- [x] `CCodeGenExpr.kt` — hardcoded `$Optional` → `$Opt` for Int/Long/Double/Float
- [x] All `startsWith("name_")` checks updated to `startsWith("name$")` throughout codegen
- [x] `matchTypeParam` updated for `$N_A1_A2` format in scan and expr phases
- [x] Dead `Pair_A_B` / `Triple_A_B_C` parsing removed from `CoreTypes.kt`
- [x] Unit tests and integration tests all passing (35/35 integration, 566 unit)

### Step 3 — Optional access macros in generated code ✅
- [x] `optNone()` → `KTC_NONE(innerType)` for simple types; raw struct for generic opt
- [x] `optSome()` → `KTC_SOME(innerType, expr)` for simple types; raw struct for generic opt
- [x] null guards → `KTC_IS_SOME(v)` in `nullGuardExpr`, `hashFieldExprKtc`, equals emit
- [x] value access → `KTC_UNWRAP(v)` in `genName`, `genExpr(ThisExpr)`, `genBin`, safe-call recv, hash
- [x] `KTC_DEFINE_OPT_GENERIC` macro added to `ktc_types.h` and used in emit for generic opt structs
- [x] Generic class structs use `struct KTC_GENERIC_TYPE(BASE, args) { ... }` form
- [x] `genericOptionalCName` and `optCTypeName` use internal type arg names (consistent with struct names)
- [x] Type system cheat sheet written to `docs/KTC_TYPE_SYSTEM.md`
- [x] 35/35 integration + all unit tests passing

---

## Remaining steps

### Step 4 — Fixed-array struct types
Switch `@Size(N) Array<T>` from raw C arrays to `ktc_Array_T_N` wrapped structs:
- `Arr.toCType()` in `CoreTypes.kt`: `sized != null -> "ktc_Array_${elem.toCType()}_${sized}"`
- Track used `(T,N)` pairs; emit `KTC_DEFINE_ARRAY(T,N)` in generated headers
- Struct fields: `T field[N]` → `ktc_Array_T_N field` (drop `$len` companion)
- Constructor init: `memcpy(x.field.arr, expr, N*sizeof(T))`
- Return type: remove `void + $out` pattern; return `ktc_Array_T_N` directly
- Element access: `name[i]` → `name.arr[i]` in `CCodeGenExpr.kt`
- Optional: `Nullable(Arr(elem, sized=N))` → `ktc_Array$Opt$_T_N`; emit `KTC_DEFINE_OPT_ARRAY`

### Step 5 — BoxedPrimitive vtables
Add per-primitive `ktc_core_AnyVt` impls to `ktc_core.c` (Byte, Short, Int, Long,
Float, Double, Bool, Char, UByte, UShort, UInt, ULong, String).

Update boxing in `CCodeGenStmts.kt`:
```c
// current: ktc_Any x = {{2}, (void*)&tVal};
// new:     ktc_Any x = {{2}, (void*)&tVal, &ktc_core_BoxedInt_vt};
```

---

## Execution order for remaining

3. Optional access macros (Step 3) — cosmetic, low risk, pure string substitution
4. Fixed-array structs (Step 4) — most invasive, requires $out pattern removal
5. BoxedPrimitive vtables (Step 5) — self-contained runtime addition
