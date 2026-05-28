# KTC plan

Working backlog of proposed improvements across three axes:

- **Kotlin features** — Kotlin source-language compatibility gaps.
- **Codegen** — quality, size, and speed of the emitted C.
- **CLI / tooling** — transpiler flags and the `run_tests.py` harness.
- **Diagnostics** — new compile-time warnings/errors. Memory-safety checks first.

Each item has a size estimate: **S** ≈ one commit, **M** ≈ a few commits, **L** ≈ multi-session refactor.

## Diagnostics — memory safety (high priority)

KTC has no GC and no borrow checker. The checks below catch the most common
ways a hand-rolled allocator-based program rots: dangling pointers, leaks,
use-after-free, double-free. None require flow-sensitive whole-program
analysis — each is a local pattern match on the AST plus the existing scope
tables, in the spirit of `clang -Wreturn-stack-address`.

Numbering continues from the current `W015` / `E101`.

### ~~E120 — Returning Ref<T> derived from a frame-local~~ ✅ shipped
Implemented for the obvious `return local.asRef()` / `return param.asRef()`
case. Chained-through-local (`val p = x.asRef(); return p`) is left for a
future flow-sensitive pass.

### W016 — Storing local.asRef() into a longer-lived location (M)
Warn on assigning `local.asRef()` (or `localArr[i].asRef()`) into:
- a field of an object whose receiver is `Ref<T>` (heap-allocated),
- a top-level (object/global) property,
- a `MutableList` / collection element.
Heuristic, not sound — but catches the obvious patterns without inventing
lifetimes. Suppress with `-Wno-escaping-ref`.

### W017 — Returning a String built by concatenation from a non-inline fn (S)
E020 already rejects bare `String` returns from non-inline functions. Extend
the check: when an inline function returns `s + t` and that inline is called
from a non-inline that *also* returns it, the alloca buffer escapes one
frame further than the user expects. Warn on the inner inline so users see
the right call site. Suppress: `-Wno-alloca-escape`.

### ~~W018 — discarded allocator result on `@RequireFree` allocator~~ ✅ shipped
Generalized to any allocator object/class tagged `@RequireFree`. `Heap` is
marked; arena-style allocators (bulk-free on reset) are intentionally not
marked and therefore exempt.

### W019 — Overwriting a Heap-allocated var without `freeMem` (M)
For `var p = X.allocWith(Heap); ...; p = Y.allocWith(Heap)`: if no
`Heap.freeMem(p)` (or equivalent) appears between the two assignments,
the first allocation is unreachable. Requires a per-block linear pass on
locals tagged "currently holds owning Heap pointer." Limit scope to a
single function body to keep it tractable. Suppress: `-Wno-realloc-leak`.

### W020 / E122 — Use after `freeMem` (M)
After `Heap.freeMem(p)`, any read of `p` (`p.refValue`, `p.field`, passing
`p` as an arg, calling a method on it) in the same block is a UAF. Treat
intervening reassignment to `p` as "reset." Promote to **E122** when the
use is statically in the same basic block as the free with no branches
between — that case is unambiguous.

### E123 — Double `freeMem` (S)
Two `Heap.freeMem(p)` calls on the same local in the same block with no
reassignment of `p` between them. Hard error — the second free is UB.

### W021 — `freeMem` on a non-Heap reference (S)
`Heap.freeMem(local.asRef())` or `Heap.freeMem(stackRef)` passes a stack
pointer to the allocator. Detect when the argument expression is an
`asRef` of a local, a parameter that wasn't allocated via `allocWith`,
or any `Ref<T>` that the function received as an in-parameter (we can't
prove ownership, but the user almost certainly didn't mean to free a
borrowed pointer). Suppress: `-Wno-free-non-heap`.

### W022 — `Arena` declared but never `.reset()` / `.dispose()` (S)
Per-function `Arena` locals that are never reset or disposed leak the
whole bump region on return (unless the arena's backing buffer is itself
stack-allocated, which we can detect). Heuristic warning; suppress with
`-Wno-arena-unfreed`.

### W023 — Heap pointer leaves scope without escape (M)
Local `val p = X.allocWith(Heap)` that is neither returned, passed to a
function, stored in a field, nor freed before the end of its scope. Same
flavor as W019 but for the end-of-scope case. Will have false positives
(intentionally one-shot allocations), so warning-only.

### E124 — `freeMem` on a `Ref<T>` that came from `&array[i]` (S)
Indexing into a heap-allocated array and freeing the element pointer
corrupts the allocator. Detect when the freed expression is
`arr[i].asRef()`. Hard error — there is no legitimate use.

## Diagnostics — correctness / dead code (medium priority)

### ~~W024 — Unreachable code~~ — already shipped (as a hard error)
Existing `codegenError("Unreachable code after '...'")` in
`statement/Statements.kt:emitBlock` already rejects this case at error
severity. No additional work needed unless we want to downgrade to a
warning.

### ~~W025 — Unused local variable~~ ✅ shipped
AST-walk in `UnusedLocals.kt`. Skips `_`-prefixed names. Suppress with
`-Wno-unused-local`.

### W026 — Unused parameter (S)
Skip parameters of `override fun` (signature is fixed), `it` in lambdas,
and names starting with `_`. Otherwise warn. Suppress: `-Wno-unused-param`.

### W027 — Unused private function / property (S)
A `private` top-level function or class private method/property with zero
references after the whole-file collect pass. We already build the call
graph for monomorphization — same data answers this.

### ~~W028 — `var` never reassigned → suggest `val`~~ ✅ shipped
Same scanner as W025. Counts `++`/`--`, `=`, compound-assign, and method
calls on the receiver as writes; passing as a function argument does NOT
count (could be a Ref<T> out-param, but most arg passes are by-value
reads). Suppress: `-Wno-could-be-val`.

### W029 — Dead store (S)
`x = a; x = b;` with no read of `x` between — first store is dead. Restrict
to single-block straight-line cases to avoid false positives.

### W030 — Duplicate `when` branch / overlapping `is` branches (S)
- `when (x) { 1 -> ...; 1 -> ... }` — second unreachable.
- `when (a) { is Animal -> ...; is Dog -> ... }` where `Dog : Animal` —
  the `Dog` branch is shadowed by the earlier supertype branch.

### W031 — Redundant `else` on exhaustive `when` (S)
Complement to existing `W006` (non-exhaustive when). When all enum entries
or sealed subclasses are covered, the `else` is dead. Suppress:
`-Wno-redundant-else`.

### W032 — Implicit narrowing in arithmetic / assignment (S)
Assigning `Long` → `Int`, `Int` → `Byte`, `Double` → `Float` without an
explicit `.toInt()` / `.toByte()` / etc. Kotlin already rejects this; KTC
currently accepts some forms silently. At minimum warn on assignment and
on argument passing where the parameter type is narrower than the arg.

### ~~W033 — Side-effect-free expression statement~~ ✅ shipped
Conservative: anything containing a call, `!!`, `++`/`--` is treated as
having a side effect. Suppress: `-Wno-no-effect-expr`.

### W034 — `!!` on a value the compiler can prove non-null (S)
Complement to `W012` (`!!` on literal null). If the receiver is a
non-nullable type, `!!` is a no-op — flag it. Sibling of `W007` / `W008`.

## Diagnostics — implementation notes

- All new codes need an entry in `codegen/ErrorCatalog.kt`.
- Suppression flag name (`-Wno-xxx`) must be wired in `Options.kt` /
  warning-emit helpers.
- Tests: each diagnostic gets a `src/test/kotlin/.../W0xxTest.kt` snippet
  using `TranspilerTestBase` that asserts the code appears in compiler
  output.
- For lifetime/escape checks (E120, E121, W016, W019, W020/E122, E123,
  W021, W023, E124) the common helper is "does this expression name a
  local/parameter declared in the current function?" — a tiny visitor on
  top of the existing `scopes` chain.

## Codegen — known limitations (hit by ktc.std.FileSystem development)

These don't block daily work but did force workarounds in the FileSystem module.
Fix-when-touched, in rough priority order:

### Member `inline fun` not actually inlined (M)
`class Foo { inline fun bar(x: X): Y = ... }` emits a regular function and
ignores the inline modifier. Extension `inline fun Foo.bar(...)` works
correctly — the path-join code currently lives as an extension to dodge this.
Hook: the function-emit path needs to honor `f.isInline` for member methods,
and the call-site dispatch needs to expand the body the same way it does for
extension methods.

### `!!` on a value-type Optional doesn't unwrap (S)
`val x: Foo = nullableFoo()!!` emits `Foo x = nullableFoo();` without
`KTC_UNWRAP(...)`. Codegen path for `NotNullExpr` on a value-type Optional
needs to extract `.value`. Currently sources / sinks return non-nullable
sentinels (`FileSource.isOpen` check) to dodge this.

### `?.` on a function-result that's nullable (S)
`fn().nullableProp?.foo` evaluates `fn()` twice and concatenates the result
into broken C. Codegen needs to spill the LHS into a temp before the safe-
access guard. Workaround: bind to a local first.

### Chained struct-method calls take `&` of an rvalue (S)
`Path("a").child("b").child("c")` emits `child(&child(&Path(...), ...), ...)`
where the middle `&` is applied to a returned rvalue (illegal in C). Codegen
needs to materialize a temp variable for the intermediate result.

### Smart-cast across `&&` in if condition (M)
`if (x != null && x.field == ...)` doesn't narrow `x` in the RHS of `&&`.
Requires lazy emission of the right-hand operand (emit `x != null` first,
then narrow, then emit `x.field`). Existing `extractSmartCasts` already
handles `&&` for the THEN body but not for the condition itself.

### Operator overload `div` doesn't dispatch with multiple overloads (S)
`Path / String` and `Path / Path` both declared as `operator fun div` — the
codegen emitted literal `dir / "name"` in the C output instead of calling
`Path_divWith*`. Current workaround: a single `.child(String)` method.

### `inline val foo: T get() = expr` uses literal `$self` (S)
The getter body is expanded with `$self.X` references that aren't
substituted at the user call site, causing "undeclared identifier `$self`"
in the C output. Workaround: top-level helper functions called from the
getter.

## C-interop

### `.cast<T>()` for pointer reinterpretation (S)
Needed to bypass `const char*` ↔ `void*` conversions, `FILE*` ↔ `void*`,
and similar C-side reinterpretations that the current type system makes
into warnings or errors.

## Kotlin features

(none queued)

## CLI / tooling

(none queued)
