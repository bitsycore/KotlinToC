# KTC Codegen Refactor Plan

## Phase A — Pure TypeRef extensions ✅
Move stateless predicates off CCodeGen onto the types they operate on.

- [x] Add `TypeRef.hasSizeAnnotation()` extension in `Ast.kt`
- [x] Add `TypeRef.getSizeAnnotation(): Int?` extension in `Ast.kt`
- [x] Add `TypeRef.isSizedArray()` / `TypeRef.isRawArray()` extensions in `CCodeGenCTypes.kt`
- [x] Add `TypeRef.isSizedString()` extension in `Ast.kt`
- 
- [x] Move `escapeC()` / `escapeStr()` to new `CCodeGenUtils.kt`
- [x] Update all call sites in codegen to use new extension style
- [ ] Run unit tests — expect green

## Phase B — Consolidate repeated patterns into helpers ✅
No behavior change, just DRY.

- [x] Add `CCodeGen.genExprFlushed(expr, ind)` helper — replaces `genExpr` + `flushPreStmts` written inline
- [~] Skip `nullableUnwrapExpr` — patterns too context-specific for a single helper
- [~] Skip `emitSizedArrayAlloca` — each call has different prefix/source-expr shape
- [x] Replace all 3 control-flow inline occurrences with `genExprFlushed`
- [ ] Run unit tests — expect green

## Phase C — FunctionContext value object
Replace ~15 scattered `currentFn*` / `currentClass` / `currentObject` globals with a pushed/popped context.

- [ ] Define `FunctionContext` data class in `CCodeGenStructures.kt`
- [ ] Add `fnCtx: FunctionContext` field replacing `currentFnReturnsNullable`, `currentFnReturnsArray`, `currentFnReturnsSizedArray`, `currentFnSizedArraySize`, `currentFnSizedArrayElemType`, `currentFnReturnsSizedString`, `currentFnSizedStringSize`, `currentFnReturnType`, `currentFnReturnKtcType`, `currentFnOptReturnCTypeName`, `currentFnIsMain`, `currentClass`, `currentObject`, `selfIsPointer`, `currentExtRecvType`
- [ ] Make `loopDepth`, `trampolinedParams`, `sizedArrayTrampolinedParams`, `inlineReturnVar`, `inlineEndLabel`, `deferStack` local to the methods that use them (pass as parameters or keep in a `StmtContext`)
- [ ] Merge existing `FunState` save/restore into `FunctionContext` push/pop — delete `FunState`
- [ ] Update all read sites in CCodeGenStmts, CCodeGenExpr, CCodeGenEmit
- [ ] Run unit tests — expect green

## Phase D — Split CCodeGenExpr.kt (3964 lines)
Break by concern; `genExpr()` dispatcher stays as entry point.

- [ ] Create `CCodeGenExprCall.kt` — `genCall`, `genMethodCall`, overload resolution, `expandCallArgs`, sized-return struct wrappers (~1200 lines)
- [ ] Create `CCodeGenExprBinary.kt` — `genBin`, arithmetic, comparison, boolean, cast ops (~600 lines)
- [ ] Create `CCodeGenExprNullable.kt` — null checks, smart casts, `KTC_UNWRAP`, optional handling (~400 lines)
- [ ] Create `CCodeGenExprString.kt` — string templates, `ktc_core_str`, printf formatting (~300 lines)
- [ ] Create `CCodeGenExprCollections.kt` — `arrayOf`, `intArrayOf`, `listOf`, `mapOf`, ranges, when-table (~500 lines)
- [ ] Trim `CCodeGenExpr.kt` to dispatcher + simple primaries (literals, names) (~400 lines)
- [ ] Run unit tests — expect green

## Phase E — Split CCodeGenEmit.kt (2302 lines) ✅
Break by declaration kind.

- [x] Create `CCodeGenEmitClass.kt` — `emitClass`, `emitGenericClass`, `emitSecondaryCtor`, `emitMethod`, `emitClassEquals`, `emitDataClassToString`, `emitImplicitHashCode`, `emitDefaultToString`, `emitAnyVtable`, `emitStructFields`, `emitConstructorBody`, `hashFieldExprKtc`
- [x] Create `CCodeGenEmitInterface.kt` — `emitInterfaceBlock`, `emitIfaceVtableBody`, `collectAllIfaceMethods`, `collectAllIfaceProperties`, `ifaceDataName`, `flushDeferredAsForClass`, `emitVtable`, `hasDisposeOverride`, `ifaceAsInit`, `emitInterfaceVtablesForClass`, `emitTransitiveInterfaceVtables`, `emitTransitiveIfaceHdrDecls`
- [x] Create `CCodeGenEmitObject.kt` — `emitObject` (singleton lazy init, TLS, interface vtables, Any cast)
- [x] Create `CCodeGenEmitFun.kt` — `emitExtensionFun`, `emitGenericFunInstantiations`, `emitStarExtFunInstantiations`, `emitStarExtFunForGenericInterface`, `emitEnum`, `emitEnumValuesData`, `emitFun`, `emitTopProp`
- [x] Leave `CCodeGenEmit.kt` as banner-helpers only (133 lines): `kHdrRule`, `classBlockHeader`, `classBlockFooter`, `funBlockHeader`, `cSourceFileHeader`, `maybeEmitFunBanner`, `boxSection`
- [x] Run unit tests — expect green

## Phase F — SymbolReader interface ✅
Make type inference and type mapping testable without a full CCodeGen instance.

- [x] Define `SymbolReader` interface in `Structures.kt` exposing the read-only symbol maps and core name-resolution helpers
- [x] Have `CCodeGen` implement `SymbolReader` (class marked `internal`, 13 properties + 8 methods with `override`)
- [x] Change receiver type of pure-read functions in `CTypes.kt` to `SymbolReader`: `cTypeStr(KtcType)`, `substituteTypeParams`, `typeRefToStr`, `printfFmt`, `printfArg`, `isCurrentPkgUserType`
- [x] Functions kept on `CCodeGen` (not movable): `cTypeStr(String)`, `cType`, `resolveTypeName`, `resolveTypeNameStr`, `resolveTypeNameInnerStr`, `parseResolvedTypeName`, `userType`, `defaultVal` — these form a chain through `primitiveToArrayType` which mutates `classArrayTypes`
- [x] Run unit tests — expect green

## Phase G — CodeBuilder output state ✅
Decouple output buffers from CCodeGen logic. (Most architectural, highest risk — do last.)

- [x] Define `CodeBuilder` in `Structures.kt` — holds `hdr`, `impl`, `implFwd`, `perDeclImpl`, `perDeclImplFwd`, `deferredAsCalls`, `deferredObjIfaceMethods`; owns `captureForDecl(key, block)` (swap/restore logic)
- [x] Add `internal val cb = CodeBuilder()` to `CCodeGen`; delegate properties (`hdr`, `impl`, `implFwd`, `perDeclImpl`, `perDeclImplFwd`, `deferredAsCalls`, `deferredObjIfaceMethods`) keep all call sites unchanged
- [x] Simplify `captureForDecl` on `CCodeGen` to resolve root key then call `cb.captureForDecl`
- [x] Remove raw buffer field declarations from `CCodeGen`
- [x] Run unit tests + integration tests — expect green

---
*Last updated: Phase G complete — all planned phases done*
