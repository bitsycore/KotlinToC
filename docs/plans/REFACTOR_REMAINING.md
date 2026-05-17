# REFACTOR_REMAINING.md — What's Left

## Status: ~700 lines eliminated. ~60 inferExprTypeKtc added alongside inferExprType. All 33 integration + 570 unit tests pass.

## Hard floor: 4 string checks that must stay

These are in `parseResolvedTypeName` (the KtcType parser itself) and one AST check.

| File              | Count | What                                                                                                                                |
|-------------------|-------|-------------------------------------------------------------------------------------------------------------------------------------|
| CCodeGenCTypes.kt | 3     | `parseResolvedTypeName` internals: `endsWith("*?")`, `endsWith("*")`, `endsWith("Array")` — the string→KtcType boundary. Must stay. |
| CCodeGen.kt       | 1     | `d.returnType.name == "Any"` — raw AST check before type resolution. Must stay.                                                     |

---

## ✅ Done this session

### ArraysMapping.kt
- Removed `arrayElementCType()` and `arrayElementKtType()` string functions (~90 lines)
- All callers now use `arrayElementCTypeKtc()` and `arrayElementKtTypeKtc()`

### tryArrayOfInit
- `recvType.endsWith("Array")` → `inferExprTypeKtc(dot.obj)?.isArrayLike`
- `t.endsWith("OptArray")` → KtcType structural check via `parseResolvedTypeName(t)`
- `arrayElementCType(t)` → `arrayElementCTypeKtc(tKtcCore)`

### inferIndexType
- `t.dropLast(1)` + `isArrayType(base)` → `tKtcCore.inner.toInternalStr`
- `arrayElementKtType(t)` fallback → `arrayElementKtTypeKtc(tKtcCore)`

### inferDotType
- `.ptr`/`.toHeap` array string manipulation → `recvTypeCoreKtc.asArr?.elem` with `KtcType.Ptr(arr.elem)`

### inferExprTypeKtc planted
- ~60 `inferExprTypeKtc` calls added alongside existing `inferExprType` calls across 4 files
- Files: `CCodeGenStmts.kt` (+16), `CCodeGenExpr.kt` (+12), `CCodeGenScan.kt` (+5), `CCodeGenEmit.kt` (+1)
- These enable future conversion of string-based type checks to KtcType

---

## Remaining

### inferExprType → String? (96 call sites → ~35 remaining without KtcType counterpart)

Most string-only call sites are inside the inference functions themselves (`inferDotType`, `inferCallType`, `inferMethodReturnType`, etc.) or in guarded expressions where adding a statement is not straightforward.

**Remaining callers without KtcType:**

| File             | Count | Notes                                                     |
|------------------|-------|-----------------------------------------------------------|
| CCodeGenInfer.kt | ~16   | Inside inference functions — needs pipeline rewrite       |
| CCodeGenStmts.kt | ~7    | In guarded/conditional expressions, mid-expression chains |
| CCodeGenExpr.kt  | ~8    | In guarded expressions, `.map {}` lambdas, mid-expression |
| CCodeGenScan.kt  | ~3    | In scan phase — needs pipeline rewrite                    |

### `inferExprType` return type migration plan

| Step | What                                                                                                           | Status      |
|------|----------------------------------------------------------------------------------------------------------------|-------------|
| 1    | Add `inferExprTypeKtc` alongside every `inferExprType` call                                                    | ✅ ~70% done |
| 2    | Convert consumers of the string result to use KtcType                                                          | Ongoing     |
| 3    | Change `inferExprType` return type to `KtcType?`                                                               | Deferred    |
| 4    | Rewrite `inferDotType`, `inferIndexType`, `inferMethodReturnType`, `inferCallType` to produce KtcType natively | Deferred    |
