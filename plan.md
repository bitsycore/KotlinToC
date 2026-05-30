# KTC plan

Working backlog from a full multi-agent transpiler review (2026-05-30; 140 findings, 19 verified
bugs). Shipped items are condensed into the **Shipped** list — detail is in git history; only open
work keeps a full description. Size: **S** ≈ one commit, **M** ≈ a few commits, **L** ≈ multi-session.

Axes: **Correctness** (emitted C compiles & matches Kotlin) · **Codegen** (quality/size/speed) ·
**Type-safety/DRY** (canonical `KtcType`, kill string round-trips, factor duplicates) ·
**Ease-of-use** (std-lib + CLI gaps) · **Diagnostics** (memory-safety lints).

═══════════════════════════════════════════════════════════════════════
## Shipped (green on unit + all integration tests)
═══════════════════════════════════════════════════════════════════════
- **Correctness:** B1 (data-class hashCode/equals field parity), B2 (`arr.get/set` bounds check),
  B3 (unknown-enum-method → E050), B4 (Array.get element-type infer), B5 (`mapOf` 0-pairs capacity),
  B6 (range-loop endpoint hoist), B7 (collection for-loop preStmt flush), B8 (template/print eval-once),
  B9 (generic ctor-arg inference → E045 instead of silent T=Int), B10 (function-pointer signature
  round-trip), B11 (inline-overload return-type infer), B12 (member `infix fun` parse + dispatch),
  B13 (lexer column).
- **Discovered & fixed:** D1 (per-decl `@DocumentationOnly` — see note), D2 (value-nullable `if`
  branch-wise Optional), D3 (top-level `var` write prefix), D4 (value-nullable `when` branch-wise Optional),
  D7 (inline lambda-return type-param inference — a generic `inline fun <R>(block: () -> R): R` now binds
  `R` from the lambda body instead of emitting the raw type-param name `Pkg_R`; covers value/extension/
  statement positions), D8 (Unit-valued `val` bindings — `val x = <Unit expr>` emits the side effects and
  a value-less Unit local instead of an invalid `void x = ;`; `"Unit"` now round-trips to `KtcType.Void`).
  D7+D8 enable value-returning `run`/`with`/`let` and a generic `Mutex.withLock { … }` (returns the block result).
- **CLI/diagnostics:** C1 (`[Exxx]` prefixes), C2 (`-Wno-` list sync), C3 (unknown-flag reject),
  C4 (`--disposed`/`--double-dispose` validation), C5 (`--help`/`-h` + flexible `--version`/`--explain`).
- **Codegen:** O1 (`String +` derived alloca size), O6 (Win32 includes out of `ktc_core.h`),
  O7 (shared `ktc_core_noop_dispose`).
- **Type-safety/DRY:** R1 (`genBin` infer-once), R4 (`elemCTypeStr` reuse), R5 (`defaultVal` no string
  round-trip), R6 (structural array check), R7 (real range KtcType for `in`), R8 (`CastExpr` resolved
  infer), R9 (hoisted `kBooleanResultOps`), R2 (`ifacePtrLiteral` helper), R3 (`resolveAllocatorIface`
  helper), R11 (`parseTypeParamList` dedup), R14 (`scanAll` dedup), R18 (stale `stringToKtc` doc /
  Boolean sizing), R20-partial (CmakeGen `module.ktc.toml` comment).
- **Ease-of-use:** U3 (Char predicates), U9/U10 (Random range bias + threshold), Thread API
  (`ktc.std` kotlin-like `Thread(::entry, arg).start()`/`join()`/`isAlive` + `Thread.sleep`/`Thread.yield`
  companion + kotlin-shape `thread(start, name, priority) { … }` closure form + `Mutex.lock`/`unlock`/
  `destroy`/`withLock { }`, over a cross-platform `ktc_core_thread_*` / `ktc_core_mutex_*` C layer — Win32 /
  pthread). U4/U5 partial (see §4).
- **Closures (thread):** `thread { capture(a, b); … }` — explicit-capture closure for the OS-thread body
  (KTC has no implicit capture). Captures marshal like KTC fn args (value copied, `Ref<T>` by pointer)
  into a context struct on the *spawning frame's stack* (alloca, no heap/free — join() before return,
  C-style). Lowers to a generated entry fn + the `Thread(::entry, ctx).start()` path. `capture(...)` is a
  no-op marker and is MANDATORY — an enclosing local used but not captured is a hard error (E054). See §7
  for the general-closure roadmap (noinline params + escaping/returned lambdas).
- **Tests added:** CharPredicateTest, NullableIfTest, TemplateEvalTest, FunRefTest, MemberInfixTest,
  GenericInferUnitTest, ThreadTest, TopVarWriteTest, MathTest, StringViewTest.

**Design note (D1):** doc-only is now per-declaration `@DocumentationOnly`, not whole-file
`@file:DocumentationOnly` — a file may mix intrinsic stubs (marked per class/fun) with real `inline`
extensions, as `Primitives.kt` now does after folding in the old `Chars.kt`. No hard error for doc-only
files (by design). `Arrays.kt` stays `@file:DocumentationOnly` until it first needs a real helper (U7).

═══════════════════════════════════════════════════════════════════════
## 1. Correctness bugs (verified — produce wrong or uncompilable C)
═══════════════════════════════════════════════════════════════════════

All review-found verified correctness bugs are shipped (B1–B13, D1–D4 — see **Shipped**).
Residual smaller `?: "Int"` inference fallbacks remain at TypeInferCall.kt:132 / TypeInferDot.kt:112 /
ArraysMapping.kt:67 (the `arrayOf` element-type path, not the generic-ctor path B9 fixed); these can
mis-type an array whose element inference fails — tighten them the same way (E045-style) if they bite.

### D5 — top-level `val`/`var` with a non-constant initializer emits invalid C (M)
A top-level `val m = Mutex()` (or any initializer that isn't a C compile-time constant — a constructor
call, a `C.fn()` call) is emitted as a file-scope C initializer → gcc `error: initializer element is
not constant`. Such initializers must be deferred to `ktc_core_mainInit()` (as object/@Tls init already
is). Workaround: initialize inside a function and pass the value via a context. Surfaced building the
Thread API (the ThreadTest uses a heap context instead).

### D6 — `expr.cast<T>()` return type inferred as `Int` ✅ FIXED
The cast codegen was already correct (`(T_ctype)(expr)`), but `inferCallType` didn't recognise `cast`,
so `val x = e.cast<T>()` mistyped `x` as `Int`. Fixed: `inferCallType` now returns the (last) type
argument for a `cast` call, mirroring the codegen — `val x = e.cast<T>()` infers as `T` with no
annotation. ThreadTest relies on it (`arg.cast<Ref<Ctx>>()`, `ctx.cast<AnyPtr>()`).

═══════════════════════════════════════════════════════════════════════
## 2. Generated-C optimization
═══════════════════════════════════════════════════════════════════════

### O2 — String builtins duplicate a non-lvalue receiver (side effects + 2-4× bloat) (M)
CallMethodBuiltins.kt:163-206 (startsWith/endsWith/contains/indexOf/substring/…) interpolate `inRecv`
2-4×; a computed-getter / method receiver is evaluated repeatedly. Fix: when `inRecv` isn't a stable
lvalue (reuse `kStableExprRx`), spill once into a `ktc_String` temp. (Same family as B8/O8 — could reuse
a shared spill helper.)

### O3 — `when` on Int/enum subject emits an if/else-if chain instead of a C `switch` (L)
Control.kt (genWhenCond / emitWhenStmt) lowers constant-Int/enum branches to an O(n) `==` chain.
Fix: when subject present and every non-else branch is a single constant Int/Char/enum equality on it,
emit `switch (subject) { case K: … default: … }`; fall back to the chain otherwise. Covers stmt + expr forms.

### O4 — Constant index on statically-known-length String/Array still emits a runtime bounds check (M)
`isStaticallySafe` (Expression.kt:388-398) only elides for `@Size(N)` and `StrLit[k]`. `val s="abc"; s[0]`
and let-bound array literals still pay a runtime check. Fix: track an optional const length on `LocalVar`
and elide when index < known length.

### O5 — `const`-correctness on read-only receivers; large value `equals` by-value (M)
`*_toString(T* $self)`, getters, `hashCode` never mark `$self` `const T*`; `equals(T a, T b)` copies two
full structs. Fix: emit `const T*` for non-mutating method receivers; pass `equals` operands as `const T*`
above a size threshold.

### O8 — copyWith alloc-failure path leaves `{NULL, len>0}` (bounds check then NULL-deref) (S)
CallMethodBuiltins.kt:443-465: on alloc failure the VarArr keeps a nonzero len with a NULL ptr. Fix:
`${ptr} ? $vSrcLen : 0` so a failed copy is an empty array, matching the `{NULL,0}` convention.

### O9 — `repeat()` alloca size can overflow `ktc_Int` before the 64 KB clamp (S)
CallMethodBuiltins.kt:231-239 computes `len*n+1` in `ktc_Int` (can wrap negative) then tests `> 65536`.
Fix: compute in `size_t` / guard `n<=0` before the multiply.

### O10 — Cross-pkg interface cast designated-init zeroes the union then memcpy overwrites it (S)
Vtable.kt:192-198/238-244. Minor: set header fields after the memcpy on an uninitialized local.

═══════════════════════════════════════════════════════════════════════
## 3. Type-safety & DRY refactors
═══════════════════════════════════════════════════════════════════════

### R10 — `Parser.INFIX_IDS` is process-global mutable state (M)
Parser.kt:1285 companion `var`, mutated from Main.kt — leaks across files, not re-entrant. Fix: make it a
constructor `val` instance field (folds into B12's AST-driven registration).

### R12 — `emitClass` and `emitGenericClass` are ~90% duplicate and have drifted (M)
Class.kt:78-133 vs ClassGeneric.kt:17-71 — diverged Any-member ordering + data-class toString coverage.
Fix: extract one `emitClassBody(ci, decl, isGeneric, displayName, optName, typeArgsForFooter)`.

### R13 — Any-vtable trampoline emission duplicated between ClassAny.kt and Object.kt (M)
ClassAny.kt:154-224 vs Object.kt:388-432 — char-identical AnyVt literal + `as_Any`. Fix: extract
`emitAnyVtableLiteralAndCast(cName, selfTypeExpr)` + a 5-stub builder parameterized by per-method body.

### R15 — Inline-return state (5 fields) hand-saved/restored on CCodeGen (M)
CCodeGen.kt:126-130; Inline.kt:78-87/145-149. Fix: move into `FunctionContext` + delegate props, fold the
manual save/restore into `saveFunState`/`restoreFunState`.

### R16 — Trampolined-param ptr/len + receiver mem-op/.ptr/.len resolution duplicated (M)
CallMethodBuiltins.kt:378-385/432-439/476-485/361-366/503-506; Dot.kt:118-150, Name.kt:108-122,
Expression.kt:154-176 (genSafeDot already drifted). Fix: `arrayDataPtrFor`/`arrayLenFor` + `memOp(ktc)`
helpers used everywhere.

### R17 — Four near-identical recursive AST walkers across the scan files (L)
ScanClasses/ScanSubst/ScanFunctions each re-walk the full Expr/Stmt hierarchy and have drifted (missing
LambdaExpr / node kinds). Fix: one generic `walkExpr`/`walkStmt` visitor; express the passes as callbacks.

### R19 — Overload/secondary-ctor/generic-fun mangling strips `*`/`?` from strings → Ref vs value collide (M)
CCodeGenCollect.kt:628 (`methodName`), Class.kt:138 (`secondaryCtorName`), FunGeneric.kt:29-33 — a `Foo` and
`Ref<Foo>` param mangle to the same C symbol. Fix: include a pointer/ref marker; drive off KtcType structure.

### R20 — Smaller DRY wins (S each, batch) — partially done
Remaining: parseDeclBody (Parser object/anon/companion loops), finishExprOrAssign + ASSIGN_OPS set,
maybeTrailingLambda helper, collapse skipNL/skipTerminator, topLevelSrcKey `|`-sentinel helper,
Math/array name-builder de-dup (ArraysMapping ↔ PrimKind).
(Done: CmakeGen `module.ktc.toml` comment; `npeGuard` helper for Dot.kt genNotNull ×6.)

═══════════════════════════════════════════════════════════════════════
## 4. Ease-of-use / missing std-lib + features
═══════════════════════════════════════════════════════════════════════

### U1 — No functional collection ops (the biggest usability gap) (M)
List/MutableList have only size/get/set/add/remove/contains/indexOf/iterator. Add inline extensions
(zero-cost, lambdas are inline-only): forEach, map, filter, any/all/none, count, sum/sumOf, min/maxOrNull,
first/last/firstOrNull, fold/reduce, joinToString (allocator/StringBuffer param), in-place sort().

### U2 — Collection/Map factories force an explicit allocator at every call site (M)
`listOf(Heap.asRef(), 1, 2, 3)` everywhere. Add a default `allocator = Heap.asRef()` (or no-allocator
overloads). If default-arg-to-global-object isn't expressible yet, that codegen feature unblocks this win.

### [~] U4 — numeric/math helpers (partial) (M)
Shipped `ktc/Math.kt`: inline `maxOf`/`minOf`/`abs`/`coerceIn`/`coerceAtLeast`/`coerceAtMost` for
Int/Long/Float/Double (+ `MathTest`); fixed inline free-function overload selection to be type-aware.
STILL TODO: the transcendental layer (sqrt/pow/floor/ceil/sin/cos) — needs `<math.h>` available in user
TUs (either add the include to ktc_core.h, or provide `ktc_core_*` wrappers); deferred pending that decision.

### [~] U5 — String view ops (partial) (M)
Shipped pure-view inline extensions in Strings.kt: `removePrefix`/`removeSuffix`/`substringBefore`/
`substringAfter`/`substringBeforeLast`/`substringAfterLast` (Char delimiter; + `StringViewTest`).
STILL TODO: `split(delim){…}` / `lines{…}` (zero-alloc inline iterator yielding views) and
`replace(String,String)` (needs a StringBuffer). Char-vs-String delimiter overloads are blocked until
`findInlineExtFun` disambiguates extension overloads by arg type (today: receiver + arity only).

### U6 — No `Set` type; Map missing getOrPut/getOrDefault/keys/values/forEach (M)
Add `HashSet<T>` (thin over HashMap or dedicated) + `Map.forEach`, `getOrPut` (single-probe member),
`getOrDefault`.

### U7 — No Array transform/search helpers (indexOf/contains/sort/forEachIndexed) (M)
Arrays.kt has only the memory ops. Add inline query/transform extensions (in-place / read-only, no alloc).
First real helper here should also convert `Arrays.kt` off `@file:DocumentationOnly` to per-decl (see D1 note).

### U8 — `Result` carries only an Int errorCode; thin combinator surface (M)
Generalize to a message/typed payload; add inline getOrElse/getOrDefault/map/fold/onSuccess/onFailure.

═══════════════════════════════════════════════════════════════════════
## 5. Larger architectural roadmap (L — deliberate, multi-session)
═══════════════════════════════════════════════════════════════════════

- **A1 — Make `KtcType` the canonical inference output.** Invert `inferExprType`/`inferExprTypeKtc`
  so the KtcType core is canonical and `inferExprType(e) = inferExprTypeKtc(e)?.toInternalStr`
  (the Dot path already does this). Removes the @Size-loss workarounds, the Func-loss (B10's naive
  string parse), and the structural double-inference. Then collapse the three TypeRef→type pipelines
  (resolveTypeName / resolveTypeNameStr / parseResolvedTypeName) into one, deleting the dead
  `KtcType.from` companion or promoting it to the single resolver.
- **A2 — Memoize inference** keyed on (Expr identity, scope/subst generation), bumped on scope push/pop
  and `withTypeSubst`. 181 call-sites re-infer the same nodes per `genExpr` pass.
- **A3 — Parser error recovery:** `ParseException`/`LexException` + panic-mode skip to NEWLINE/RBRACE +
  multi-error aggregation in Main (today the first error aborts the file). Replace the exception-driven
  function-type backtracking (Parser.kt:1199-1242) with non-throwing lookahead.
- **A4 — Operator domain as a sealed `BinOp`/`UnOp`/`AssignOp`** instead of raw `String` on AST nodes.

═══════════════════════════════════════════════════════════════════════
## 6. Diagnostics — memory-safety lints (not yet shipped)
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
## 7. Codegen items carried from the prior backlog
═══════════════════════════════════════════════════════════════════════

- **Member `inline fun` not actually inlined** (M) — `class Foo { inline fun bar() = … }` emits a regular
  function. Extension inline funcs work; `Path.child`/`listDir` live as top-level inline extensions to dodge
  this. Honor `f.isInline` for members in the function-emit path and expand the body at the call site
  (bind `this` and bare-field refs).
- **Smart-cast across `&&` in an `if` condition** (M) — `if (x != null && x.field == …)` doesn't narrow `x`
  in the RHS. Needs lazy emission of the RHS operand (`extractSmartCasts` already handles `&&` for the THEN
  body but not the condition itself).

### General closures via explicit `capture(...)` (L — generalizes the thread closure)
Rule: `inline fun` + lambda param = inlined (no capture); `inline fun` + `noinline` param = closure;
plain lambda value = closure. Today `thread { capture(...) }` is a name-keyed special case
(`expression/ThreadClosure.kt`) — the capture analysis / context-struct / entry-fn / `-Wuncaptured`
machinery is reusable; what must generalize:
- **Trigger:** fire on any escaping-lambda position (a `noinline` param, a lambda used as a function-typed
  value), not the callee name `thread`. `thread.block` then becomes a normal escaping param.
- **`noinline` keyword** on inline-fun params (parse + the inline-vs-closure decision per param).
- **Closure representation:** a function-typed *value* must become a fat pointer `{fn, ctx}`; calls lower
  to `clo.fn(clo.ctx, …)`. Interacts with C interop (a bare `void(*)()` sink needs a thunk). The OS-thread
  ABI `(fn, void*)` is why `thread` avoided this.
- **Lifetime:** Phase 1 — frame-bound closures use the thread model (alloca ctx in the defining frame, must
  not escape it). Phase 2 — escaping/returned closures (the E023 `return (Int)->Int` case) need a heap ctx +
  explicit free, C-style. This is the one genuinely new decision.
