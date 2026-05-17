# PLAN: Auto-@Size inference for arrays and strings

## Summary

Two-phase plan to relax the "can't return raw array" restriction for inline functions,
and auto-infer `@Size(N)` annotations for array/string constructors with known compile-time sizes.

---

## Phase 1 — Array @Size inference

### 1a. Inline function exemption

**Problem:** `collectDecls` rejects raw array returns for ALL functions. But inline functions
expand at the call site — the array lives on the caller's stack, so returning it is safe.

**Fix:** Add `!d.isInline` to the `isRawArrayTypeRef` guard at each location.

| File          | Line | Check                     | Change                                              |
|---------------|------|---------------------------|-----------------------------------------------------|
| `CCodeGen.kt` | 1170 | ctor val/var prop         | Not inline-relevant (no `isInline` on props) — skip |
| `CCodeGen.kt` | 1176 | class member props        | Same — skip                                         |
| `CCodeGen.kt` | 1213 | class method return       | Add `!m.isInline &&`                                |
| `CCodeGen.kt` | 1283 | object property           | Same — skip                                         |
| `CCodeGen.kt` | 1301 | object method return      | Add `!m.isInline &&`                                |
| `CCodeGen.kt` | 1333 | top-level function return | Add `!d.isInline &&`                                |

### 1b. Auto-@Size for array constructors

**Problem:** `arrayOf(1,2,3)` and `IntArray(5)` already allocate with known size,
but the inferred return type doesn't carry `@Size(N)`, so the caller can't return
the result from a non-inline function.

**Approach:** Store auto-inferred @Size in a map keyed by call expression identity,
checked during `isSizedArrayReturningCall` and `isRawArrayTypeRef`.

| Constructor         | Size derivation     | Always inferable? |
|---------------------|---------------------|-------------------|
| `intArrayOf(1,2,3)` | `e.args.size`       | Yes (variadic)    |
| `floatArrayOf(...)` | `e.args.size`       | Yes (variadic)    |
| `arrayOf(a,b,c)`    | `e.args.size`       | Yes (variadic)    |
| `IntArray(5)`       | `sizeArg is IntLit` | Only if literal   |
| `Array<Int>(5)`     | `sizeArg is IntLit` | Only if literal   |
| `Array<Int>(n)`     | variable            | No — keep raw     |

**Implementation:**

1. Add `autoSizedArrayCalls: MutableMap<CallExpr, Int>` to `CCodeGen` state
2. In `tryArrayOfInit`, when processing sized constructors, record: `autoSizedArrayCalls[init] = size`
3. Extend `isSizedArrayReturningCall` to check the map
4. Extend `isRawArrayTypeRef` logic OR the `collectDecls` check to skip when the return
   expression is an auto-sized call

**Edge cases:**
- `IntArray(n)` where `n` is a parameter → cannot infer → keep raw (error for non-inline)
- `arrayOf()` (empty) → size 0 → auto `@Size(0)` (valid in C as flexible array)
- `arrayOf<T?>(a, null, b)` → nullable elements → still has fixed size → infer

---

## Phase 2 — String @Size inference

### 2a. Current state

`ktc_String` is a value-type struct `{ char* ptr; ktc_Int len; }`. It can ALREADY be
returned from functions without `@Ptr` or `@Size` — unlike arrays, C allows returning
structs by value. There is currently NO restriction on returning `String`.

### 2b. Motivation for @Size on String

Even though String is already returnable, inferring `@Size(N)` provides:
1. **Stack allocation optimization** — string buffers can use fixed stack arrays instead of heap
2. **ABI simplification** — callers know the max buffer size at compile time
3. **Consistency** — same compile-time size inference as arrays
4. **Future-proofing** — if String ever gets a return restriction, the annotation is already in place

### 2c. Auto-@Size for string expressions

| Expression                             | Size derivation             | Action                        |
|----------------------------------------|-----------------------------|-------------------------------|
| `"hello"` (StrLit)                     | `value.length`              | Auto `@Size(length)`          |
| `"a = $x"` (template, all parts known) | `templateMaxLen(expr)`      | Auto `@Size(maxLen)`          |
| `"a = $x"` (template, unknown part)    | null                        | Skip                          |
| `someObj.toString()`                   | uses StringBuffer → dynamic | **Exclude**                   |
| `a + b` (concat of strings)            | `len(a) + len(b)`           | Auto if both have known sizes |
| `a + b` (concat, unknown)              | null                        | Skip                          |

### 2d. Excluding toString()

`toString()` for user types uses `StringBuffer` (the `ktc_StrBuf` type), which is a
dynamic buffer. Its output size depends on the object graph and can't be determined
at compile time. Therefore:

- `toString()` calls → NEVER infer @Size
- `"literal $x"` templates where `x.toString()` is embedded → `templateMaxLen` already
  returns null for types without known max length (via `toStringMaxLen` returning null)

### 2e. Implementation approach

Same pattern as Phase 1b — a map for auto-sized call expressions:

1. Add `autoSizedStringExprs: MutableMap<Expr, Int>` to `CCodeGen` state
2. In `genExpr` for `StrLit`: record `autoSizedStringExprs[expr] = value.length`
3. In `genExpr` for `StrTemplateExpr`: call `templateMaxLen`, if non-null record it
4. In `genBin` for `+` with two String operands: if both have known sizes, record sum
5. Exclude `toString()` calls — check via `funSigs` or method dispatch
6. Extend `isSizedArrayTypeRef` → rename to `isSizedTypeRef` and add `t.name == "String"`
7. When a function returns an auto-sized string expression, the `inferredTypeRef` produces
   `TypeRef("String", annotations = [Annotation("Size", [IntLit(size)])])`

### 2f. Effect on C output

Current (no @Size):
```c
ktc_String test_hello(void) {
    return ktc_core_str("hello");  // points to .rodata, len=5
}
```

With @Size(5):
```c
void test_hello(ktc_Char* $out) {
    memcpy($out, "hello", 5);
    // caller allocates: ktc_Char buf[5]; test_hello(buf);
}
```

**Decision needed:** Should @Size(N) String use out-parameter ABI like arrays, or just
keep the struct return? The struct return is simpler and already works. The out-parameter
ABI would be an optimization for stack-allocated buffers.

**Recommendation:** Keep struct return for now. The @Size annotation is informational.
Future optimization can switch to out-parameter ABI without breaking the annotation.

---

## Implementation order

| Step | What                                                                | Risk   | Depends on |
|------|---------------------------------------------------------------------|--------|------------|
| 1    | `!d.isInline` / `!m.isInline` for raw array check                   | Low    | —          |
| 2    | Rename `isSizedArrayTypeRef` → `isSizedTypeRef`, add String support | Low    | —          |
| 3    | `autoSizedArrayCalls` map + population in `tryArrayOfInit`          | Medium | 2          |
| 4    | `autoSizedStringExprs` map + population in `genExpr`                | Medium | 2          |
| 5    | Wire maps into `collectDecls` / `isRawArrayTypeRef` check           | Medium | 3, 4       |
| 6    | Integration tests for inline array return + auto-@Size              | Low    | 3, 5       |
| 7    | Integration tests for string auto-@Size                             | Low    | 4, 5       |

---

## Files affected

| File                | Changes                                                                                         |
|---------------------|-------------------------------------------------------------------------------------------------|
| `CCodeGen.kt`       | `!d.isInline`/`!m.isInline` guards, `autoSizedArrayCalls`/`autoSizedStringExprs` maps           |
| `CCodeGenStmts.kt`  | Record auto-sized arrays in `tryArrayOfInit`                                                    |
| `CCodeGenExpr.kt`   | Record auto-sized strings in `genExpr` for StrLit/StrTemplateExpr/BinExpr                       |
| `CCodeGenCTypes.kt` | Rename `isSizedArrayTypeRef` → `isSizedTypeRef`, add String; extend `isSizedArrayReturningCall` |
| `CCodeGenEmit.kt`   | Updated checks for `isSizedTypeRef`                                                             |

---

## Open questions

1. **Should @Size(N) String use out-parameter ABI?** Struct return is simpler and works already. Recommend keeping struct return and treating @Size as informational for now.

2. **Should concat `a + b` where only one operand has known size be inferred?** Safer to require both operands to have known sizes. If one is dynamic, the result is dynamic.

3. **What about functions that take a String parameter and return it?** E.g., `fun prefix(s: String): String = "pre_" + s`. `s` has unknown size → result has unknown size → no @Size inference. Correct behavior.
