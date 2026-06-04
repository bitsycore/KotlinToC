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

### General closures via explicit `capture(...)` (generalizes the thread closure)
Rule: `inline fun` + lambda param = inlined (no capture); plain lambda value / non-inline function-typed
param = closure. Functor model (DECIDED): each lambda → its OWN struct (capture fields) + a generated
`R Closure_N_invoke(Closure_N* self, params…)`; the closure VALUE is that struct (real data, by value or
`&`), NOT a fat `{fn,ctx}` pointer and NOT type-erased; `f(args)` → `Closure_N_invoke(&f, args)`. Bare C
function pointers stay separate (C interop / thread ABI). Shares the thread closure's capture machinery
(`expression/ThreadClosure.kt`).

**Phase 1 — SHIPPED (frame-bound; green on unit + integration):**
- Value-position lambda on a function-typed `val` → functor + invoke; `f(x)` dispatches via the struct.
- **Higher-order params via per-closure-type monomorphization** — `F(lambda…)` for a same-package
  non-inline top-level F → `F__Closure_N` with each function-typed param retyped to its functor struct.
  Handles N closure params, closure-typed-var args (`foo(g)`), and overloaded callees (findOverload).
- **Capture modes** — `capture(x)` by value (snapshot) / shares an existing `Ref<T>`; `capture(x.asRef())`
  by reference (binds `x` as `Ref<T>`, via `.refValue`). (Also fixed `p.refValue = v` for `Ref<primitive>`.)
- **Closure-type inference without annotation** — `val f = { x: Int -> … }` infers the functor from typed
  params + body result; parser now reads typed lambda params; `it` shorthand when the expected type is
  1-param.
- **`noinline` honored** — a `noinline` parameter of an `inline fun` opts that one lambda out of inline
  expansion: it becomes a frame-bound capture closure (functor built at the call site) the inlined body
  can call / move to a local / pass on, while the other params still inline in place. `f(x)` on a
  closure-typed local dispatches through its `_invoke` via the variable's C name (lookupCName).
- **Heap closures — `Ref<(P) -> R>`** — `closure.copyWith(allocator)` heap-promotes a frame-bound functor
  to a `Ref<(Int)->Int>`: the function type IS the closure type (as in Kotlin), `Ref<>` marks the heap form
  (like `Ref<Interface>` → `ktc_IfacePtr`). One heap block = the `ktc_Closure` fat pointer with its captures
  folded in; `freeMem(g)` frees it whole. Nameable and **escapes** — returnable / storable / collection
  element. `g(x)` casts the erased invoke (`Closure_N_invoke_erased`, void* env) to the signature. Refused
  for a stack-by-ref capture; a bare `(Int)->Int` return is refused (E023 → use `Ref<(Int)->Int>`).
  Internally `Ref<(P)->R>` → `Ptr(Closure(sig))` (`KtcType.Closure(sig)` carries F) → C type `ktc_Closure*`;
  no user-facing `Closure<>` type. Frame-bound functors unchanged for non-escaping use.
- Deferred-emission flushes to a fixpoint and snapshots-and-clears each pending list (a body can queue more
  closures / chained higher-order calls — iterating the live list threw ConcurrentModificationException).
- E023 already rejects returning a function type from a non-inline fn (the escape boundary for returns).
- Tests: ClosureTest (+ chained), ClosureHigherOrderTest, CaptureRefTest, ClosureInferTest,
  NoinlineClosureTest, HeapClosureTest.

**Phase 1 — still open (each falls through to normal dispatch / a clear error today):**
- Higher-order: cross-**package** callees (same-package files are merged so they already work), receiver
  (extension/member) closure params, generic higher-order functions, closure passed by named/defaulted arg.
- Escape guards (W/E): storing a frame-bound closure in a heap field / ctor param, or passing it to a
  function that stores it. (Returns are already E023; the rest can produce a dangling capture — currently
  a C-level type mismatch in the field case, undefined behaviour in the store-and-call case.)
- Minor: dead-code emission of the original un-monomorphized function when only ever called with closures.

**Phase 2 — SHIPPED (heap closures escape via `Ref<(P)->R>`, see above).** Done in polish:
- **Direct call on a stored closure** — `obj.field(x)` / `arr[i](x)`: a non-name callee whose resolved type
  is a closure (`Ptr(Closure)`/`Closure`) is spilled to a temp and dispatched through the cast-invoke
  (genCall top). Covers a heap closure stored in a class field, called back with `h.f(x)`.
- Tests: HeapClosureTest (promote, call, loop reuse, returned/escaped closure, field-stored closure call).

Done in polish:
- **Frame-bound→heap-ref guidance** — assigning a frame-bound functor to a `Ref<(P)->R>` (var/field/param)
  is caught by the Ref↔value boundary (E070); the fix-it is now closure-aware (`.copyWith(allocator)`, not
  the dangling `.asRef()`). (Frame-bound→Ref ctor/fn args that slip the boundary still hit a C type mismatch
  — a clearer KTC pre-check there is the remaining nicety.)

Done in polish:
- **Array of heap closures** — `arr[i](x)` on `Array<Ref<(P)->R>>` dispatches. Root cause was the element
  KtcType garbling: `resolveTypeNameInnerStr` (which the Array branch calls on its element) had no `Ref<func>`
  case, so the element collapsed to `"Ref"`→`"RefArray"`→`Arr(User("Ref"))`; and `parseResolvedTypeName`'s
  Array branch flattened a composite element via `userType(elemName)` instead of recursing. Both fixed
  (KtcType-correct): the array element now resolves to `Ptr(Closure)`, so the genCall index-callee hook
  fires. (`List<…>` would follow once the list factories carry the element type.)

Remaining polish:
- A KTC-level pre-check for a frame-bound functor passed to a `Ref<(P)->R>` ctor/function arg (today: a
  C-compiler type mismatch rather than an E070-style message).
- Broader: store vars' KtcType directly (`defineVarKtc`) instead of the string round-trip at definition —
  part of the A1 inference refactor (canonical KtcType, no string round-trips).

═══════════════════════════════════════════════════════════════════════
## 8. No implicit copy of value types — `copy()`/`copyWith()`-gated (L) ✅ SHIPPED
═══════════════════════════════════════════════════════════════════════

**STATUS: complete & green — 74/74 integration + full unit suite.** P0–P4 + P6 shipped (E071 gate at
var-decl / call-arg / reassign / return; `val b = a` → `Ref<T>` alias; `.copy()` on any class; generic
copy-transparency; `@Size(N)` binding gate; std-lib + all tests migrated). Only P5 (optional enum-singleton
representation, orthogonal — enums are already exempt) is deferred. Detail below.

Make implicit copies of *managed value types* illegal, C++-"deleted copy-assignment" style. The only
ways to materialise a copy are an **explicit** `.copy()` (value copy → `T`) or `.copyWith(allocator)`
(heap copy → `Ref<T>`). Both lower through existing machinery and **may be user-overridden** — the
checker treats *any* `.copy()` / `.copyWith()` call as the explicit-copy marker regardless of body.
Goal: kill accidental large struct copies; binding/passing defaults to a reference, copying is opt-in.

This is the value-target counterpart of the existing Ref↔value boundary (`E070`,
`checkPtrValueBoundary` @ statement/Statements.kt:18) and reuses the same auto-deref that already makes
a `Ref<T>` ergonomic (`recv.field` → `recv->field`, Dot.kt:147-150; method receivers via `addressOfRecv`).

### Decided semantics (locked with the user — 2026-06-01)
- **`val b = a`** (un-annotated; `a` a managed-value lvalue) → infers **`Ref<T>`**, emits `T* b = &a;`
  (an alias — NO copy). This auto-`&` is the *sole* implicit address-of, and fires ONLY for an
  un-annotated binding. Implementation: desugar to `b = a.asRef()` and reuse the `.asRef()` path
  (CallMethod.kt:214) + existing Ptr emission (Var.kt:190/:359).
- **`val c = a.copy()`** → `c : T` (value), emits `T c = a;`. `.copy()`/`.copyWith()` are CallExprs →
  rvalues → never themselves a copy-of-lvalue.
- **By-value targets reject an lvalue source unless wrapped in `.copy()`/`.copyWith()`** — error (new
  `E071`, see Diagnostics): explicit `val d: T = a`; reassignment `x = a` (x already `T`);
  `return a` (return type by-value `T` — **returns are strict, by user's choice**); a by-value `T`
  parameter `f(a)`; and **ctor/field-init args** `Foo(bar)` (a class field stores by value → `Foo(bar.copy())`).
  Fix-it: "use `.copy()` (value) or `.copyWith(allocator)` (heap, returns `Ref<T>`)".
- **Explicit `Ref<T>` targets stay strict** — still require `.asRef()` (E070 unchanged). `val e: Ref<T> = a`
  and a `Ref<T>` param `g(a)` are errors → `a.asRef()`. (Asymmetry by design: no annotation ⇒ helpful
  Ref default; explicit annotation ⇒ spell the conversion. The `var b = a` (Ref) reassignment `b = c`
  rebinds the pointer and so also needs `c.asRef()`.)
- **Scope of "managed value type":** user `class` / `data class` values; `@Size(N)` arrays
  (`{ T arr[N]; }` — copies as costly as a class; fix-it `.copyOf(N)`/`.copy()`); ctor/field storage of
  the above. **Exempt** (stay copy-by-value): primitives, `String`, `Array<T>`/`RawArray<T>`/typed arrays
  (slice views), `Ref<T>`, closures, interfaces.
- **Enums:** full (Kotlin-form) enums become **reference-to-singleton** — one `static const` instance per
  entry; `Color.RED` → `&…RED`; the value type is `Ref<Enum>`, so `val y = x` aliases (no `?`, matches
  Kotlin `===` identity). `@SimpleEnum` stays `ktc_Int` (cheap value, copy-by-value fine). Separable
  workstream (P5) — touches emit/Enum.kt, the Dot.kt enum branches (:69/:125-133), `when`/switch,
  `.values()`/`.valueOf()`, equality. Confirm before building.
- **Rollout: hard switch.** No flag. Change the default and migrate the std-lib + every integration test
  in lockstep, test-gated by `run_tests.py`.

### Core mechanism — two predicates + one check (shared with E070)
- `KtcType.isUserValueType()` (add to **types/CoreTypes.kt** near `KtcType.User` @:102-110; `kind` is
  reachable as `decl.kind: UserKind`) = `this is User && kind in {Class, DataClass}`. Excludes
  ValueClass/Object/Interface/Enum and all non-User types — the `@Size` Arr arm and the P5 singleton-enum
  arm are handled separately, not by this predicate.
- `isLValueExpr(expr)` + `checkImplicitCopy(targetKtc, srcExpr, where)` — add to **CCodeGen.kt** by the
  predicate-helper home (`isValueNullableKtc` @:654-660). `isLValueExpr` = true for `NameExpr` /
  field `DotExpr` / element `IndexExpr` / `ThisExpr`; **false for any `CallExpr`** — crucially a
  `CallExpr` whose callee is `DotExpr(name = "copy" | "copyWith")` is an rvalue (already explicit), as are
  ctors / fn returns / `ObjectExpr` / literals / `BinExpr` / lambdas. `checkImplicitCopy`: if target is a
  by-value managed type AND source `isLValueExpr` of a managed type → `E071`.

### Change-map (anchors verified against the source by the map workflow)
1. **types/CoreTypes.kt:102-110** + **CCodeGen.kt:654-660** — the predicates above (foundation; do first).
2. **statement/Var.kt** — (a) `vKtc = when{…}` @:135-141 (`else` arm @:140): un-annotated user-value-lvalue
   init ⇒ wrap inferred `vKtc` in `KtcType.Ptr(...)`, desugaring init to `.asRef()`; **guard against
   double-wrap** when the init is already `Ptr` (`val b = a` where `a : Ref<Some>`). Existing `isPointer`
   (:190) + non-nullable emit (~:426) then produce `T* b = &a;` — but inject `&` at the value-emission
   site since `genExpr` yields the bare name. (b) extend the boundary call @:166 with `checkImplicitCopy`
   for the explicit by-value annotation (`val d: Some = a` — E070 stays silent here since both sides are
   non-`Ptr`, Statements.kt:38). (c) `@Size(N)` truncate logic @:169 gains the gate.
   (d) **destructuring** (@:32-61) routes through synthetic `VarDeclStmt`s → inherits (a) for free;
   **`emitLazyLocalDecl`** (~:467) infers separately → replicate the (a) wrap if its body tail is an lvalue.
3. **statement/Assign.kt** — `emitAssign` @:133-145 (after the `varKtc` lookup @:135-136): run
   `checkImplicitCopy` for reassignment `x = a`. `emitReturn` (non-nullable path ~:373-465): gate against
   `currentFnReturnKtcType` (**CCodeGen.kt:761-765**) — `return a` is **strict (E071)** per the locked
   decision; today it emits a silent struct copy with zero validation. (Note E120 already covers
   `return x.asRef()` dangling-Ref; E071 is the value-copy counterpart.)
4. **expression/CallArgs.kt:356-364** — insert a branch *before* the `else { parts += expr }` (@:363):
   by-value user-value param + lvalue arg → `E071`. Ctor calls route through `expandCallArgs`, so this is
   also the **field-storage** gate (`Foo(bar)` → `Foo(bar.copy())`). **Do NOT auto-`&` for `Ref<T>`
   params** — the scan recommends it (its "D2"), but the locked decision keeps explicit `Ref<T>` targets
   strict: a class lvalue passed to a `Ref<T>` param stays an E070 fix → `.asRef()`.
5. **expression/CallMethod.kt** — generalise `.copy()` beyond `isData` (@:213 / Ref form @:130): a no-arg
   `.copy()` on ANY class ⇒ `genDataClassCopy` already returns `src` (@:458) so C's struct `=` does the
   copy; drop the `isData` guard for the no-arg form (keep `copy(field=…)` override only where ctor props
   exist). `.copy()`/`.copyWith()` are **user-overridable** — the checker keys on the call, not the body.
   Confirm `.copyWith(allocator)` heap-promotes any class → `Ref<T>`. **Receivers unchanged**: method
   receivers already pass by address via `addressOfRecv` (@:274) — reference-semantic, not a copy site;
   do not apply the gate to them (prevents a false positive on every `a.method()`).
6. **codegen/ErrorCatalog.kt** — new **E071** (free; deliberately adjacent to E070, the Ref↔value boundary,
   rather than the strictly-next E102) + `--explain` text; hard error, house-style fix-it pointing at
   `.copy()` / `.copyWith(allocator)`.
7. **emit/Enum.kt** + Dot.kt enum branches (:69/:125-133) — P5 singleton-ref enums (see above).
8. **Migration** — resources/ktc/**, resources/modules/**, integration/**: insert `.copy()`/`.copyWith()`
   or convert intended aliases to `Ref<T>`/`.asRef()`.

### Phased plan (each step independently `run_tests.py`-gated)
- **P0 (S) ✅ SHIPPED** — `isLValueExpr` + `checkImplicitCopy` + `E071` (CoreTypes.kt `isUserValueType`,
  Statements.kt), wired into the explicit-annotation var-decl site. P0 scope narrowed to the unambiguous
  lvalues (NameExpr/`this`); DotExpr/IndexExpr deferred (computed-getter + `.refValue` distinction needed).
  CopyValueUnitTest added.
- **P1 (M) ✅ SHIPPED** — un-annotated `val/var b = lvalue` desugars to `.asRef()` ⇒ `T* b = &a;` alias,
  member access auto-derefs. Destructuring's internal tmp now aliases too. Unit + integration green.
- **P3 (S) ✅ SHIPPED** — `.copy()` generalised: data classes synthesize early (incl. `copy(field=…)`),
  plain classes get a no-arg value copy as a post-resolution **fallback** so a user-defined `copy(...)`
  always wins (the early-interception version regressed SDL3 `FRect` — fixed).
- **P2 (M) ✅ SHIPPED (hard error)** — E071 now also fires on by-value call args (incl. ctor/field
  storage), reassignment, and returns (returns use `inAllowAlias=false`: a returned `&local` would
  dangle, E120). **Generic copy-transparency:** a value whose type is a current type-parameter
  substitution target (T→Vec2) is exempt — a generic author can't `.copy()` a maybe-primitive T
  (C++-template semantics). Measured break: **20 of 74** integration tests (not ~82%; KTC already
  favours Ref/allocator idioms), now migrated.
- **P4 (S) ✅ SHIPPED** — `@Size(N)` arrays gated at **bindings/assignments** (same-size `@Size(N)`→`@Size(N)`
  named-lvalue copy; source size via `lookupArraySize`); fix-it `.copyOf(N)`/`.copy()`. **Exempt:** params
  & returns (a `@Size(N)` signature makes the copy cost visible, unlike a plain class param; `@Size` return
  -by-value is the safe array-return idiom), and truncating conversions (different/unknown source size →
  the existing implicit-`.copyOf` truncate warning still applies).
- **P5 (L) — DEFERRED, optional & orthogonal** — full enums → singleton-backed `Ref<Enum>`. **Key finding:
  enums are ALREADY exempt from E071** (`isUserValueType` = Class|DataClass only), so the no-copy rule is
  already complete and correct for them — they copy a small struct cheaply, and `@SimpleEnum` is a plain
  `int`. P5 is therefore *not required by the rule*; it's a separate representation overhaul to get Kotlin
  `===` identity + zero enum-struct copies (`Color.RED` → `&singleton` — the `const` singletons already
  exist at Enum.kt:90; re-type every enum value to `Color*`; touch `when`/switch, equality, `.values()`/
  `.valueOf()`, params/returns/fields, per-entry vtables). High regression risk to a core feature for
  marginal benefit — recommend a dedicated, separately-confirmed pass.
- **P6 (L) — IN PROGRESS: 73/74 integration + full unit green.** Migration decisions taken:
  - **Read-only class params → `Ref<T>`** (caller `.asRef()`): std-lib `FileSystem` (9 methods → `Ref<Path>`).
  - **Deliberate value-passing / field-storage / retaining → `.copy()`** at the call site: the intrinsic
    fixtures (DataClass, Pointer, MemberInfix, MultiFile, TypeAlias, StringBuffer), AdvancedGenerics
    value calls, and the affected unit snippets. `.copy()` on a class lowers to the same C (struct
    pass-by-value), so it never changes behaviour or output assertions.
  - **Single-field wrappers can't be value classes** (E031: value classes forbid body properties), so
    `Path` and the SDL3 handle types stay regular classes and migrate via `Ref<T>` / `.copy()` rather
    than the zero-overhead value-class exemption.
  - **Remaining: `external/Sdl3Test`** (decided: `Ref<T>` the SDL3 demo API). The SDL3 *module* funcs are
    all `inline` → never trip E071 (inline args bypass `expandCallArgs`); only the demo's **non-inline**
    render functions take value structs by value. **Validated:** converting a value-struct param to
    `Ref<T>` works — incl. `Ref<Renderer>` used as a *receiver* for the value-receiver SDL3 extension
    calls (auto-deref handles it). But it's a **large mechanical refactor**: the demo threads
    `FRect`/`Color`/`FPoint`/`Texture` values through its whole render pipeline (renderBox/renderLeash/
    renderCrosshair/renderAtlasHud/render/applyMods/…), ~dozens of params + `.asRef()` call sites. Left as
    a dedicated mechanical pass; suite is **73/74 + full unit green** without it.

### Open implementation details / risks
- **Nullable managed values** (`Some?`) interact with the Optional-struct lowering — start conservative:
  auto-Ref only for non-nullable lvalues; nullable ⇒ require explicit `.asRef()`/`.copy()`.
- **Generics/monomorphization** — apply the rule on the *substituted* type (a type param bound to a class).
- **Aliasing hazard** — `val b = a` then mutating `a` is visible through `b` (documented semantic; the
  whole point is "want independence ⇒ `.copy()`").
- **Interaction with E070** — both checks coexist on the shared helper; E070 = value→Ref/Ref→value
  mismatch at an explicit annotation, E071 = lvalue→by-value implicit copy.

### Blast radius (measured by the scan) + rollout tension
- **Std-lib:** ~100+ function signatures across `ktc` / `ktc.std` / `ktc.sdl3` take class-typed params by
  value — each needs a manual `Ref<T>`-vs-`.copy()` decision.
- **Integration:** **70+ of 85 test files (~82%)** would fail to transpile under the hard error.
- **Honest tension with the locked "hard switch, no flag" choice:** a P2 that lands E071 as a *hard error*
  turns `run_tests.py` red until *all* ~85 tests are migrated — which breaks the "each phase independently
  test-gated" invariant. Pragmatic reconciliation that still reaches the no-flag end state: land the gate
  first as a **transition warning `W0xx`** (green tests, migrate file-by-file), then **flip warning→error
  E071 in the final commit**. That is migration scaffolding, not a permanent flag. (Both blast agents
  independently recommended a flag/staged rollout; flagging here for visibility, but honoring the decision.)
- Sequence the work so **P2 and P6 run as one tight loop** (the call-arg gate is what breaks everything).

═══════════════════════════════════════════════════════════════════════
## 9. String rework — stack-owned, NUL-terminated, Array-like ownership (L) — IN PROGRESS
═══════════════════════════════════════════════════════════════════════

Design agreed with the user (2026-06-04). `String` behaves like `Array<T>`: a stack-owned, **NUL-terminated**
value; copying is explicit; escape is via `Ref<String>`.

**Key realization (from the map workflow):** most of the "owned" model is ALREADY how KTC works — templates /
concat / `toString()` already `alloca` into the caller frame and NUL-terminate (`ktc_core_sb_to_string`,
ktc_core.h:342); E020/E022/E120 already enforce frame-bound lifetimes; literals via `ktc_core_str` already point
at NUL-terminated `.rodata`. `Ref<Array<T>>` is the bare `ktc_VarArr` struct (ptr+len), heap-backed —
**`Ref<String>` mirrors this: C type stays `ktc_String`, ptr→heap, `Ref<>` marks heap-owned/escapable;
`freeMem(ref)` frees `ref.ptr`.** The genuinely-new work is narrow.

**Relationship to U5:** the rework SUPERSEDES U5's "pure-view" claim — substring copies, so the inline view
extensions (take/drop/trim/removePrefix/substringBefore…) inherit copy+NUL (they compose substring).

### Steps (each: `./gradlew test` + `python run_tests.py` green → commit, no AI attribution)
- **S1a (S) ✅ DONE** — `s.copy()` (alloca len+1 + memcpy + NUL → owned String via `ktc_core_string_copy`).
- **S1b (S) ✅ DONE** — `s.asRef()` → `&s` (frame-bound; `return s.asRef()` E120-refused); `s.copyWith(alloc)` /
  `s.allocWith(alloc)` → one heap block (ktc_String header + NUL bytes inline) → `Ref<String>`; freeMem frees the block.
  **Representation note:** `Ref<String>` = `ktc_String*` (a real pointer), NOT a value struct like `Ref<Array<T>>` —
  because `RawArray<String>` and `Ref<String>` are both `Ptr(Str)`; the value form collided with `RawArray<String>`
  storage in HashMap/MapIterator. `refValue` → `*ref` (natural deref). 75/75 green.
- **S1c (S) ✅ DONE** — deprecate `.ptr` on String AND Array (hard error **E055**), add `.cPtr` (raw C pointer:
  `RawArray<Char>` for String, `T*` for Array — same `.ptr` field emission). Migrated stdlib (FileSystem, Allocator,
  Error, CInterop) + the `ktc.sdl3` module. `cPtr`/`.ptr`-refused unit tests. (User-requested mid-rework.)
- **S1d (S) ✅ DONE** — fully remove `.ptr` as a KTC accessor (no array/String/address-of meaning left in
  TypeInferDot / CallSafe / Call routing). `.cPtr` is the only raw-pointer member; a bare `.ptr` falls through to
  plain field resolution so a genuine C-struct `ptr` field still works. (`copy()` doc reframed as explicit pass-by-value.)
- **S3 (M) ✅ DONE — literal interning pool:** the per-file dedup pass now emits a named, read-only static array
  `static const ktc_Char ktc_str_<pfx>_<n>[] = "…";` + a `#define` referencing it (instead of inlining
  `ktc_core_str(...)`), making the `.rodata` + NUL guarantee explicit. 75/75 green.
- **S2 (M) ✅ DONE — substring copies + NUL (was deferred to last as the most disruptive):** `ktc_core_string_substring_copy(buf,s,from,to)`;
  substring allocas `recv.len+1` and copies+NUL → an owned String. Flips the inline view family
  (take/drop/trim/removePrefix/substringBefore…) to copy via composition; `Path.parent`/`pathParent` made `inline` so the
  parent's backing lands in the caller. **Also fixed a latent bug it exposed:** top-level `inline fun`s returning a
  nullable value-type now declare an `$Opt` result var (Call.kt), mirroring the inline-extension path — `pathParent(): Path?`
  needed it. Doc comments + StringUnit/View substring assertions updated. 75/75 green.
- **S4 (S) ✅ DONE — sizing intrinsics:** `value.toStringMaxLen()` (compile-time constant, refused if unbounded) and
  `value.toStringComputeLen()` (counting StrBuf pass) — both Int.
- **S5 (L) ✅ DONE — `Template` type:** `templateOf("$a")` binds a frame-local, compile-time-only handle (stored on
  `LocalVar.template`, sentinel C type `COpaque("__template")`, no C value emitted → cannot escape). Operations expand
  the stored template at the call site: `.maxLen` → static constant (refused if unbounded), `.computeLen()` → counting
  StrBuf pass, `.toString()` → owned String, `.toString(sb)` → renders into the caller's StringBuffer (returns an
  sb-backed bare String — the `Ref<String>` form has the same value-vs-pointer wrinkle as sb-render). 75/75 green.
- **S6 (M) ✅ DONE — `sb."$a"` syntax:** parser lowers `.`-followed-by-a-string to a synthetic
  `recv.__sbtmpl(<template>)` call (additive — `.`+string was previously invalid); `CallMethodBuiltins` renders it
  into the StringBuffer receiver via `genStrTemplateToSb` and returns the rendered (sb-backed) String. 75/75 green.
- **S7 (S) ✅ DONE — docs:** CLAUDE.md "String and Array return safety" + "Strings" sections rewritten for the owned
  model (copy/asRef/copyWith/allocWith, `Ref<String>`=`ktc_String*`, `.cPtr` not `.ptr`, substring copies,
  toStringMaxLen/computeLen, templateOf, `sb."…"`). (No memory note — the facts now live in CLAUDE.md/plan.md.)

**STATUS: §9 String rework COMPLETE — S1a/b/c/d, S2, S3, S4, S5, S6, S7 all shipped; 75/75 green at every step.**

### Open consequence (flagged)
S2 flips ALL inline view-extensions to copy+NUL (they compose substring) — rewrites `StringViewTest` view→copy and
adds a per-call alloca. Direct result of the agreed "everything copies, no zero-copy view type" decision.
