# KTC plan

Working backlog. Rebuilt 2026-05-30 from a full multi-agent review of the
transpiler (140 findings, 19 bugs independently verified). Shipped items have
been pruned. Each item carries a size estimate: **S** ≈ one commit, **M** ≈ a
few commits, **L** ≈ multi-session refactor. `[x]` = done this pass.

Axes (per the standing objectives):
- **Correctness** — emitted C must compile and match Kotlin semantics.
- **Codegen** — quality, size, speed of the emitted C.
- **Type-safety / DRY** — push `KtcType`, kill string round-trips, factor duplicates.
- **Ease-of-use** — std-lib + CLI gaps that make KTC hard to use.
- **Diagnostics** — memory-safety lints (carried over from the prior backlog).

## Done this pass (2026-05-30)
Shipped + green (unit + all integration tests pass): **B1, B2, B3, B4, B5, B11, B13** (correctness);
**C1, C2, C3, C4, C5** (CLI/diagnostics); **O1, O6, O7** (codegen); **R1, R4, R5, R6, R7, R8, R9, R18**
(type-safety/DRY); **U3, U9, U10** (ease-of-use, Char predicates in `ktc/Primitives.kt` + `CharPredicateTest`).
Each item below is marked `[x]` when shipped this pass.

### Discovered while implementing (new findings, not yet fixed)
- **D1 — inline extension fun on a PRIMITIVE receiver was silently dropped in a
  `@file:DocumentationOnly` file.** ✅ FIXED (per-decl `@DocumentationOnly`, no hard error — user decision)
  Decision: a doc-only file is legitimate, so no hard error. The refinement is to mark
  documentation-only at the *declaration* granularity, not the whole file. `Primitives.kt` is now a
  normal collected file whose intrinsic stub classes each carry `@DocumentationOnly` (the same
  mechanism already proven by `class String` in `Strings.kt`); real `inline` extensions now live
  alongside their type instead of needing a separate side file. The ASCII Char predicates moved from
  the old `Chars.kt` into `Primitives.kt` next to `class Char` (`Chars.kt` deleted), and `Math.kt`'s
  "must live in a real file" note was removed. `Arrays.kt` is still `@file:DocumentationOnly`
  (100% stubs today — convert it the same way the first time it needs a real helper, e.g. U7).
- **D2 — value-nullable `if`-expression with a `null` branch mis-lowered (uncompilable / wrong).** ✅ FIXED
  Root cause was broader than the original inline framing: an `if (c) <value> else null` returning a
  value-type `T?` (Int?/Char?/String?/…) didn't push the Optional wrapping into its branches. The whole
  ternary was wrapped once → the `else null` branch became `SOME(0)` (so `x ?: d` returned 0 instead of
  the default) in non-inline functions, and the inline-extension form emitted `cond ? value : NULL`
  into a `ktc_T$Opt` → didn't compile at all. Fix (Control.kt): `genIfExpr` now lowers each branch into
  the Optional (`value`→`KTC_SOME`, `null`→`KTC_NONE`) for value-nullable results; `inferIfExprType`
  promotes a null-branch `if` to nullable (with a scoped re-inference so a block branch's own locals
  resolve); `emitBlockIntoTemp` coerces the hoisted temp. Covers simple/reversed/`String?`/complex-block/
  inline-ext forms. Test: `intrinsic/NullableIfTest`.
- **D4 — `when`-expression with a `null` branch has the same value-nullable mis-lowering as D2.**
  `genWhenExpr` / `inferWhenExprType` don't yet push Optional wrapping into branches, so
  `when { … -> value; else -> null }` returning `T?` repeats the D2 bug. Mirror the D2 fix: promote to
  nullable on a null branch, coerce each branch via the shared `coerceBranchToOpt`, size the hoisted
  temp as the Optional. (S–M, mechanical given D2's helpers)
- **D3 — top-level `var` write emits an unprefixed LHS inside a same-package function.** ✅ FIXED
  `genLValue`'s NameExpr case now applies the package prefix for a top-level prop (mirrors the read
  path); the write side was emitting a bare, undeclared C name. Test: `TopVarWriteTest`.

═══════════════════════════════════════════════════════════════════════
## 1. Correctness bugs (verified — produce wrong or uncompilable C)
═══════════════════════════════════════════════════════════════════════

### [x] B1 — Data-class `hashCode` hashes interface-`Ref` fields that `equals` excludes (S)
`emitClassEquals` (ClassAny.kt:20-23) skips `Ref<Interface>` fields; `emitImplicitHashCode`
(ClassAny.kt:95-107) does not, then `hashFieldExprKtc` (:245 `Ptr` arm) emits
`(uintptr_t)(ktc_IfacePtr struct)` → **non-compilable C** for any data class with an
interface-Ref field. Fix: factor the equals field-selection predicate into a shared
`ClassInfo.hashEqFields(interfaces)` helper used by both emitters.

### [x] B2 — `arr.get(i)` / `arr.set(i,v)` builtins skip the bounds check `arr[i]` applies (S)
CallMethodBuiltins.kt:501-510 builds `$inRecv.ptr[$vIdx]` from a raw index, never routing
through `wrapBoundsIdx` — silently defeats the default-ON bounds net for the method spelling
of `operator[]`. Fix: compute the length expr like the sibling `fill` block (481-485) and
route the index through `wrapBoundsIdx` + optional `staticBoundsCheck`. RawArray stays unchecked.

### [x] B3 — Unknown enum method falls through to a bare C identifier instead of E050 (S)
CallMethod.kt:359-360 returns `"${vEnumInfo.flatName}_$method"` (no call, no args) for any
unresolved enum method, emitting e.g. `Op_foo;` → confusing C error. Fix: try
`extensionFuns[enum]` first, else `codegenError("E050", …)` mirroring the class branch.

### [x] B4 — `Array.get()` element-type inference via string-suffix is wrong for value-element arrays (S)
TypeInferCall.kt:276-279: `endsWith("Array*")` is dead (internal form has no `*`), and
`removeSuffix("*")` leaves `Vec2Array` unchanged → returns the array type as its own element type.
Fix: replace the whole branch with `arrayElementKtTypeKtc(recvKtc)` (already used 2 lines below).

### [x] B5 — `mapOf(allocator)` with zero pairs builds a capacity-0 HashMap → modulo-by-zero (S)
Map.kt:174-180 passes `pairs.size` straight in; 0 pairs → `% 0` SIGFPE on first get/containsKey.
`mutableMapOf` already guards. Fix: `val cap = if (pairs.size == 0) 8 else pairs.size * 2`, and
clamp `capacity >= 1` (better `>= 8`) inside the HashMap init for defence in depth.

### [x] B6 — Range-loop right endpoint re-evaluated every iteration (S)
For.kt:77/85/93 inline `genExpr(rangeExpr.right)` into the loop condition, so `for (i in 0..f())`
calls `f()` N+1 times (semantics divergence + perf). Fix: hoist the endpoint (and `step`) into a
temp before the loop, `flushPreStmts` first; skip the temp only for literals / immutable names.

### [x] B7 — Collection for-loop receiver: preStmts not flushed → use-before-declaration (M)
For.kt:143 (`arrExpr = genExpr(rangeExpr)`) never flushes preStmts before splicing `.len`/`.ptr`
into the header. A receiver that spills (e.g. `@Size(N)`-array-returning call) emits temp decls
*inside* the loop body, after use → **gcc rejects it**. Fix: `flushPreStmts(ind)` + spill the
receiver to one temp (skip temp for NameExpr / trampolined param). Mirror the iterator path (:106).

### [ ] B8 — String-template / print double-evaluate side-effecting non-String interpolations (M)
Two-pass (count then fill) paths emit each part's `sbAppend` twice: `genStrTemplate` (String.kt:196,
268-283) and `emitPrintTemplateViaStrBuf` (Print.kt:213-230). Side-effecting `${f()}` runs twice
(verified: `counter=2`). Fix: spill *any* non-simple ExprPart value into a typed temp before the
passes so both reference the temp; same fix inside `genSbAppendKtc`'s nullable branch (StringToString.kt:298-309).

### [ ] B9 — Fallback-to-`Int` masks generic ctor-arg inference failure → mis-monomorphization (M)
TypeInferCall.kt:119 and its codegen twin CallAlloc.kt:263 do `inferExprType(arg) ?: "Int"`, then
`recordGenericInstantiation` with the wrong type-arg → struct materialized with `T=ktc_Int`, silent
wrong-size C, no diagnostic. Fix: both sites must use the same resolution; on inference failure fall
back to declared/recorded type args, else `codegenError`. Propagate `null` (not `"Int"`) at
TypeInferCall.kt:132, TypeInferDot.kt:112, ArraysMapping.kt:67.

### [ ] B10 — `Func` type loses all params/return on the string round-trip → uncompilable C (M)
parseResolvedTypeName (CTypes.kt:309) does `Fun(...)` → `Func(emptyList(), Void)`, discarding the
encoded signature. `val f = ::add; f(2,3)` emits `void (*f)(void)` → gcc errors. Fix: reconstruct
`Func` from the `Fun(recv|p1,p2)->R` string (extend `parseFuncType` to split the receiver), or route
function-type inference through `resolveTypeName`/TypeRef so it never hits the string branch.

### [x] B11 — Inline-overload return-type inference compares resolved args vs raw param `.name` (S)
TypeInferCall.kt:188-193 compares `inferExprType(arg)` (resolved, e.g. `"Foo*"`/`"IntArray"`) against
`decl.params[i].type.name` (raw spelling, e.g. `"Foo"`/`"Array"`) → only bare value params ever match;
Ref/array/generic params silently fall back to the first candidate's return type. Fix: compare against
`resolveTypeRefStr(decl.params[i].type)`; long-term share one `pickInlineOverload(name,args)` with Call.kt.

### [ ] B12 — Member `infix fun` mis-parsed (regex misses it) AND not dispatched in codegen (M)
Main.kt:492 regex requires a receiver dot → member infix funcs never enter `INFIX_IDS`, so `a combine b`
mis-parses into 3 statements (only the hard-coded seed names work). Even when parsed, `genBin` only
dispatches extension/arithmetic operators, so a member `infix and` lowers to raw `(a & b)` on structs.
Fix: (1) derive `INFIX_IDS` from parsed `FunDecl.isInfix` over all files (drop the regex); (2) in
`genBin`, route an op that matches a member infix method through `genMethodCall`.

### [x] B13 — Lexer drops column in 6 of 7 error sites (S)
Lexer.kt:119,139,158,159,361,364 emit `"… at line $line"` while col is tracked (only :314 includes it).
Fix: append `col $col` to all six to match. (The larger "no recovery / ParseException / multi-error"
item is M and tracked under §6.)

═══════════════════════════════════════════════════════════════════════
## 2. CLI / diagnostics correctness
═══════════════════════════════════════════════════════════════════════

### [x] C1 — 9 error codes defined in catalog but emitted WITHOUT the `[Exxx]` prefix (M)
E024/E052/E053/E080/E081/E090/E091/E100/E101 all use the no-code `codegenError(msg)` overload, so
`--explain` is undiscoverable for them. Sites: Assign.kt:259, CallSafe.kt:121/133, Tailrec.kt:110/112/164,
Statements.kt:100/104, CCodeGenCollect.kt:286/287/294/470. Fix: route each through `codegenError(code,msg)`;
add a guard test asserting every catalog code is referenced by ≥1 emit site.

### [x] C2 — `-Wno-<name>` help list out of sync with emitted warnings (S)
Main.kt:196-199 lists 15 names; codegen emits 5 more (`unreachable`, `discarded-alloc`, `no-effect-expr`,
`unused-local`, `could-be-val`). Fix: stopgap add the 5; better, derive the list from ErrorCatalog
(`warnName` field) so the three-way drift can't recur.

### [x] C3 — Unknown flags silently swallowed as input file paths (S)
Main.kt:314-317 terminal `else` appends ANY token to `inputPaths`; `--no-check-bound` (typo) → generic
"file not found" with the flag silently dropped. Fix: in the else, if `startsWith("-")` and not a known
flag → `"unknown option"` + exit(2).

### [x] C4 — `--disposed` / `--double-dispose` accept any value unvalidated (S)
Main.kt:279-284 sets the mode from raw input with no `ASSERT|LOG|NO` check → half-configured silent state.
Fix: validate both against the set right after parsing; error + exit(1) otherwise.

### [x] C5 — `--version`/`--explain` only work as sole/first args; no `--help`/`-h` (S)
Main.kt:218/225 gate on exact `args.size`; `--help` falls through to "file not found". Fix: scan for these
in a pre-pass; factor `printUsage()` and trigger on `--help`/`-h`/empty-args/unknown-flag.

═══════════════════════════════════════════════════════════════════════
## 3. Generated-C optimization
═══════════════════════════════════════════════════════════════════════

### [x] O1 — `String +` concat hard-codes `alloca(512)`: truncates long, wastes short (M)
genStringConcat (Binary.kt:405-412) always `alloca(512)` + `ktc_core_string_cat(buf,512,…)` →
silent truncation >511 bytes, 512 B of frame per `+` node. Fix: size from operands —
`alloca((a).len + (b).len + 1)` at runtime (both are `{ptr,len}`), or reuse the StrBuf machinery the
template path uses. Add a `bufsz<=1` guard in `ktc_core_string_cat` (ktc_core.c:411) for the derived-size case.

### [ ] O2 — String builtins duplicate a non-lvalue receiver (side effects + 2-4× bloat) (M)
CallMethodBuiltins.kt:163-206 (startsWith/endsWith/contains/indexOf/substring/…) interpolate `inRecv`
2-4×; a computed-getter / method receiver is evaluated repeatedly. Fix: when `inRecv` isn't a stable
lvalue (reuse `kStableExprRx`), spill once into a `ktc_String` temp.

### [ ] O3 — `when` on Int/enum subject emits an if/else-if chain instead of a C `switch` (L)
Control.kt (genWhenCond / emitWhenStmt) lowers constant-Int/enum branches to an O(n) `==` chain.
Fix: when subject present and every non-else branch is a single constant Int/Char/enum equality on it,
emit `switch (subject) { case K: … default: … }`; fall back to the chain otherwise. Covers stmt + expr forms.

### [ ] O4 — Constant index on statically-known-length String/Array still emits a runtime bounds check (M)
`isStaticallySafe` (Expression.kt:388-398) only elides for `@Size(N)` and `StrLit[k]`. `val s="abc"; s[0]`
and let-bound array literals still pay a runtime check. Fix: track an optional const length on `LocalVar`
and elide when index < known length.

### [ ] O5 — `const`-correctness on read-only receivers; large value `equals` by-value (M)
`*_toString(T* $self)`, getters, `hashCode` never mark `$self` `const T*`; `equals(T a, T b)` copies two
full structs. Fix: emit `const T*` for non-mutating method receivers; pass `equals` operands as `const T*`
above a size threshold.

### [x] O6 — `ktc_core.h` drags `<windows.h>`/`<dbghelp.h>` into every TU on Windows (S)
ktc_core.h:13-15 unconditionally includes windows.h; only ktc_core.c uses Win32. Fix: move the includes
into ktc_core.c (with `WIN32_LEAN_AND_MEAN`); header keeps only the `__declspec` TLS macro.

### [x] O7 — Per-class no-op `_dispose_any` emitted instead of reusing `ktc_core_noop_dispose` (S)
ClassAny.kt emits a fresh `{ (void)$self; }` dispose trampoline per class; the shared
`ktc_core_noop_dispose` already exists (and iface vtables use it). Fix: point the Any-vtable dispose slot
at it when the class has no dispose override.

### [ ] O8 — copyWith alloc-failure path leaves `{NULL, len>0}` (bounds check then NULL-deref) (S)
CallMethodBuiltins.kt:443-465: on alloc failure the VarArr keeps a nonzero len with a NULL ptr. Fix:
`${ptr} ? $vSrcLen : 0` so a failed copy is an empty array, matching the `{NULL,0}` convention.

### [ ] O9 — `repeat()` alloca size can overflow `ktc_Int` before the 64 KB clamp (S)
CallMethodBuiltins.kt:231-239 computes `len*n+1` in `ktc_Int` (can wrap negative) then tests `> 65536`.
Fix: compute in `size_t` / guard `n<=0` before the multiply.

### [ ] O10 — Cross-pkg interface cast designated-init zeroes the union then memcpy overwrites it (S)
Vtable.kt:192-198/238-244. Minor: set header fields after the memcpy on an uninitialized local.

═══════════════════════════════════════════════════════════════════════
## 4. Type-safety & DRY refactors
═══════════════════════════════════════════════════════════════════════

### [x] R1 — `genBin` re-infers the same operand type 5+ times (M)
Binary.kt:21/149/196/233/342 each call `inferExprTypeKtc(e.left)` (a full recursive walk, not a lookup),
plus `inferExprType` at 25/31. Fix: compute `ltKtc`/`rtKtc` once at the top and thread through; derive the
string form via `.toInternalStr` where still needed.

### [ ] R2 — `ktc_IfacePtr` literal construction copy-pasted across 5 sites (M)
CallAlloc.kt:45, CallMethod.kt:151/153, CallArgs.kt:214, CallMethodBuiltins.kt:414/459 — already drifted.
Fix: one `ifacePtrLiteral(typeId, cConcrete, ifaceName, objExpr, nullable)` helper; all sites call it.

### [ ] R3 — Allocator→IfacePtr resolution duplicated in resizeWith/copyWith vs CallAlloc (M)
CallMethodBuiltins.kt:404-417 / 450-462 vs CallAlloc.kt:57-117. Fix: extract one
`resolveAllocatorIface(argExpr, evaledExpr)` (the `AllocResolution` class is 90% of it).

### [x] R4 — Four hand-inlined copies of `elemCTypeStr` in CTypesParams (S)
CTypesParams.kt:38-40/47-48/84-86/102-104 reimplement the existing `elemCTypeStr` (CTypes.kt:406). Fix:
call it. Removes another `toInternalStr` detour per site.

### [x] R5 — `defaultVal` round-trips User/Nullable/Arr through a string (S)
CTypes.kt:421-424 `cTypeStr(t.toInternalStr.removeSuffix("?"))` inside a function holding the KtcType.
Fix: `cTypeStr(t.stripNullable)`; handle `Nullable(Ptr)` → `NULL` explicitly.

### [x] R6 — `isArrayType()` string match where the KtcType is already in hand (S)
CallArgs.kt:261, Var.kt:189 use `endsWith("Array")` while `paramTypeKtc.asArr`/`isArrayLike` is computed.
Fix: switch to the structural check (also fixes misclassifying user types ending in "Array").

### [x] R7 — `in`/`!in` range fallback uses `rt.endsWith("Range")` (S)
Binary.kt:288 — a user `DateRange` is misclassified as an int range → wrong C. Fix: match the actual
range KtcType / only take the lo-hi path when `e.right` is a genuine `rangeTo` BinExpr.

### [x] R8 — `CastExpr` inference uses raw `e.type.name` (no alias/nested/Ref resolution) (S)
TypeInfer.kt:89. Fix: `resolveTypeRefStr(e.type)` (and `…Nullable` for safe casts).

### [x] R9 — `kBooleanOps` set rebuilt on every BinExpr inference (S)
TypeInfer.kt:62 allocates a fresh `setOf(...)` per call on a hot path. Fix: hoist to a file-level `val`.

### [ ] R10 — `Parser.INFIX_IDS` is process-global mutable state (M)
Parser.kt:1285 companion `var`, mutated from Main.kt — leaks across files, not re-entrant. Fix: make it a
constructor `val` instance field (folds into B12's AST-driven registration).

### [ ] R11 — Type-parameter list parse duplicated across fun/class/interface (S)
Parser.kt:152-160/229-237/419-427 verbatim. Fix: `parseTypeParamList()`.

### [ ] R12 — `emitClass` and `emitGenericClass` are ~90% duplicate and have drifted (M)
Class.kt:78-133 vs ClassGeneric.kt:17-71 — diverged Any-member ordering + data-class toString coverage.
Fix: extract one `emitClassBody(ci, decl, isGeneric, displayName, optName, typeArgsForFooter)`.

### [ ] R13 — Any-vtable trampoline emission duplicated between ClassAny.kt and Object.kt (M)
ClassAny.kt:154-224 vs Object.kt:388-432 — char-identical AnyVt literal + `as_Any`. Fix: extract
`emitAnyVtableLiteralAndCast(cName, selfTypeExpr)` + a 5-stub builder parameterized by per-method body.

### [ ] R14 — `collectAndScan()` sequence duplicated verbatim inside `generate()` (S)
CCodeGenGenerate.kt:9-16 vs 76-80 — drift risk between `--check` and emit. Fix: one `scanAll()` helper.

### [ ] R15 — Inline-return state (5 fields) hand-saved/restored on CCodeGen (M)
CCodeGen.kt:126-130; Inline.kt:78-87/145-149. Fix: move into `FunctionContext` + delegate props, fold the
manual save/restore into `saveFunState`/`restoreFunState`.

### [ ] R16 — Trampolined-param ptr/len + receiver mem-op/.ptr/.len resolution duplicated (M)
CallMethodBuiltins.kt:378-385/432-439/476-485/361-366/503-506; Dot.kt:118-150, Name.kt:108-122,
Expression.kt:154-176 (genSafeDot already drifted). Fix: `arrayDataPtrFor`/`arrayLenFor` + `memOp(ktc)`
helpers used everywhere.

### [ ] R17 — Four near-identical recursive AST walkers across the scan files (L)
ScanClasses/ScanSubst/ScanFunctions each re-walk the full Expr/Stmt hierarchy and have drifted (missing
LambdaExpr / node kinds). Fix: one generic `walkExpr`/`walkStmt` visitor; express the passes as callbacks.

### [x] R18 — Stale `stringToKtc` doc references; `estimateTypeSizeAlign` matches "Bool" not "Boolean" (S)
TypeInfer.kt:106 + CoreTypes.kt:181 reference a non-existent `stringToKtc`. CCodeGen.kt:426 sizes Boolean
as 8/8 (matches "Bool", but `toInternalStr` yields "Boolean") → over-sized cross-pkg buffer. Fix both.

### [ ] R19 — Overload/secondary-ctor/generic-fun mangling strips `*`/`?` from strings → Ref vs value collide (M)
CCodeGenCollect.kt:628 (`methodName`), Class.kt:138 (`secondaryCtorName`), FunGeneric.kt:29-33 — a `Foo` and
`Ref<Foo>` param mangle to the same C symbol. Fix: include a pointer/ref marker; drive off KtcType structure.

### [ ] R20 — Smaller DRY wins (S each, batch)
parseDeclBody (Parser object/anon/companion loops), finishExprOrAssign + ASSIGN_OPS set, maybeTrailingLambda
helper, collapse skipNL/skipTerminator, npeStmt helper (Dot.kt genNotNull ×6), topLevelSrcKey `|`-sentinel
helper, Math/array name-builder de-dup (ArraysMapping ↔ PrimKind), CmakeGen comment `deps.ktc.toml`→`module.ktc.toml`.

═══════════════════════════════════════════════════════════════════════
## 5. Ease-of-use / missing std-lib + features
═══════════════════════════════════════════════════════════════════════

### [ ] U1 — No functional collection ops (the biggest usability gap) (M)
List/MutableList have only size/get/set/add/remove/contains/indexOf/iterator. Add inline extensions
(zero-cost, lambdas are inline-only): forEach, map, filter, any/all/none, count, sum/sumOf, min/maxOrNull,
first/last/firstOrNull, fold/reduce, joinToString (allocator/StringBuffer param), in-place sort().

### [ ] U2 — Collection/Map factories force an explicit allocator at every call site (M)
`listOf(Heap.asRef(), 1, 2, 3)` everywhere. Add a default `allocator = Heap.asRef()` (or no-allocator
overloads). If default-arg-to-global-object isn't expressible yet, that codegen feature unblocks this win.

### [x] U3 — `Char` lacks isDigit/isLetter/isWhitespace/digitToInt/case predicates (S)
Primitives.kt:31-63. Add inline ASCII-fast-path predicates — every tokenizer needs them.

### [~] U4 — numeric/math helpers (partial) (M)
Shipped `ktc/Math.kt`: inline `maxOf`/`minOf`/`abs`/`coerceIn`/`coerceAtLeast`/`coerceAtMost` for
Int/Long/Float/Double (+ `MathTest`). Required fixing inline free-function overload selection to be
type-aware (Call.kt was arity-only, so `maxOf(2.5,1.5)` picked the Int overload and truncated — the
expansion-side twin of B11; both halves now prefer the type-matching candidate). STILL TODO: the
transcendental layer (sqrt/pow/floor/ceil/sin/cos) — needs `<math.h>` available in user TUs (either
add the include to ktc_core.h, or provide `ktc_core_*` wrappers); deferred pending that decision.

### [~] U5 — String view ops (partial) (M)
Shipped pure-view inline extensions in Strings.kt: `removePrefix`/`removeSuffix`/`substringBefore`/
`substringAfter`/`substringBeforeLast`/`substringAfterLast` (Char delimiter; + `StringViewTest`).
STILL TODO: `split(delim){…}` / `lines{…}` (zero-alloc inline iterator yielding views) and
`replace(String,String)` (needs a StringBuffer). Char-vs-String delimiter overloads are blocked until
`findInlineExtFun` disambiguates extension overloads by arg type (today: receiver + arity only).

### [ ] U6 — No `Set` type; Map missing getOrPut/getOrDefault/keys/values/forEach (M)
Add `HashSet<T>` (thin over HashMap or dedicated) + `Map.forEach`, `getOrPut` (single-probe member),
`getOrDefault`.

### [ ] U7 — No Array transform/search helpers (indexOf/contains/sort/forEachIndexed) (M)
Arrays.kt has only the memory ops. Add inline query/transform extensions (in-place / read-only, no alloc).

### [ ] U8 — `Result` carries only an Int errorCode; thin combinator surface (M)
Generalize to a message/typed payload; add inline getOrElse/getOrDefault/map/fold/onSuccess/onFailure.

### [x] U9 — Random range methods: modulo bias + `% 0` / wrong sign on inverted range (S)
Random.kt:22-59 — guard `until>from`, mask to non-negative, document/avoid bias.

### [x] U10 — `ktc_core_rand_range` threshold math fragile; `-bound` on unsigned (S)
ktc_core.c:41-56 — write `(ktc_UInt)(0u - bound) % bound` with a comment, early-return for `bound==1`.

═══════════════════════════════════════════════════════════════════════
## 6. Larger architectural roadmap (L — deliberate, multi-session)
═══════════════════════════════════════════════════════════════════════

- **A1 — Make `KtcType` the canonical inference output.** Invert `inferExprType`/`inferExprTypeKtc`
  so the KtcType core is canonical and `inferExprType(e) = inferExprTypeKtc(e)?.toInternalStr`
  (the Dot path already does this). Removes the @Size-loss workarounds, the Func-loss (B10), and the
  structural double-inference. Then collapse the three TypeRef→type pipelines (resolveTypeName /
  resolveTypeNameStr / parseResolvedTypeName) into one, deleting the dead `KtcType.from` companion or
  promoting it to the single resolver.
- **A2 — Memoize inference** keyed on (Expr identity, scope/subst generation), bumped on scope push/pop
  and `withTypeSubst`. 181 call-sites re-infer the same nodes per `genExpr` pass.
- **A3 — Parser error recovery:** `ParseException`/`LexException` + panic-mode skip to NEWLINE/RBRACE +
  multi-error aggregation in Main (today the first error aborts the file). Replace the exception-driven
  function-type backtracking (Parser.kt:1199-1242) with non-throwing lookahead.
- **A4 — Operator domain as a sealed `BinOp`/`UnOp`/`AssignOp`** instead of raw `String` on AST nodes.

═══════════════════════════════════════════════════════════════════════
## 7. Diagnostics — memory-safety lints (carried over, not yet shipped)
═══════════════════════════════════════════════════════════════════════

Local AST + scope-table pattern matches (clang `-Wreturn-stack-address` spirit). Numbering continues
from the current `W015`/`E101`. Common helper: "does this expression name a local/param of the current fn?".

- **W016** (M) — storing `local.asRef()` into a longer-lived location (object field on a heap receiver,
  top-level prop, collection element). `-Wno-escaping-ref`.
- **W017** (S) — String built by concat returned from a non-inline fn one frame further than expected.
  `-Wno-alloca-escape`.
- **W019** (M) — overwriting a Heap-allocated `var` without `freeMem` between the two assignments. `-Wno-realloc-leak`.
- **W020 / E122** (M) — use after `freeMem` in the same block (promote to E122 when unambiguous straight-line).
- **E123** (S) — double `freeMem` on the same local with no reassignment between.
- **W021** (S) — `freeMem` on a non-Heap reference (asRef of a local/param/borrowed Ref). `-Wno-free-non-heap`.
- **W022** (S) — `Arena` local never `.reset()`/`.dispose()`. `-Wno-arena-unfreed`.
- **W023** (M) — Heap pointer leaves scope without escape (not returned/passed/stored/freed).
- **E124** (S) — `freeMem(arr[i].asRef())` (freeing an array-element pointer).
- **W026** (S) — unused parameter (skip `override`, `it`, `_`-prefixed). `-Wno-unused-param`.
- **W027** (S) — unused private function / property (reuse the call-graph from monomorphization).
- **W029** (S) — dead store (`x=a; x=b;` no read between, single block).
- **W030** (S) — duplicate `when` branch / overlapping `is` branch (subtype shadowed by supertype).
- **W031** (S) — redundant `else` on an exhaustive `when`. `-Wno-redundant-else`.
- **W032** (S) — implicit narrowing (`Long`→`Int`, `Double`→`Float`) without explicit `.toX()`.
- **W034** (S) — `!!` on a value the compiler can prove non-null.

Implementation notes: each new code needs an `ErrorCatalog.kt` entry; each `-Wno-xxx` must be wired in
Main.kt + the help list (see C2); each gets a `TranspilerTestBase` snippet test asserting the message.

═══════════════════════════════════════════════════════════════════════
## 8. Codegen items carried from the prior backlog
═══════════════════════════════════════════════════════════════════════

- **Member `inline fun` not actually inlined** (M) — `class Foo { inline fun bar() = … }` emits a regular
  function. Extension inline funcs work; `Path.child`/`listDir` live as top-level inline extensions to dodge
  this (see U-note). Honor `f.isInline` for members in the function-emit path and expand the body at the
  call site (bind `this` and bare-field refs).
- **Smart-cast across `&&` in an `if` condition** (M) — `if (x != null && x.field == …)` doesn't narrow `x`
  in the RHS. Needs lazy emission of the RHS operand (`extractSmartCasts` already handles `&&` for the THEN
  body but not the condition itself).
