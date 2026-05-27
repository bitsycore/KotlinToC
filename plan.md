# KTC plan

Working backlog of proposed improvements across three axes:

- **Kotlin features** — Kotlin source-language compatibility gaps.
- **Codegen** — quality, size, and speed of the emitted C.
- **CLI / tooling** — transpiler flags and the `run_tests.py` harness.

Each item has a size estimate: **S** ≈ one commit, **M** ≈ a few commits, **L** ≈ multi-session refactor. Items are not strictly ordered within sections — see *Recommended order* at the end for the next 8.

## Kotlin features

### Quick wins

- [x] **`tailrec` keyword** (S) — Lower a tail call into a `goto`-loop. Error if the marked function isn't actually tail-recursive; warn if an unmarked function could be. Works for free fns, extension fns, and methods. Arity-checked to avoid false positives on overloads. Integration test: `TailrecTest`.
- [ ] **`when` exhaustiveness** (S–M) — Warning when a `when` on a sealed/enum subject has no `else` and doesn't cover every case. Promote to error later. Unblocks #4.
- [ ] **`reified` type parameters** (S) — Generics already monomorphize, so `reified T` is mostly syntactic. Expose the concrete type name via a `T::class.simpleName`-like intrinsic.
- [x] **Raw string literals `"""..."""`** (S) — Already worked in the lexer; covered with unit tests in `StringUnitTest.rawString*`.
- [x] **Destructuring in `for` loops** (S) — `for ((k, v) in map) { ... }` lands a synthetic `$ditem_<names>` temp + a per-iteration destructuring-decl. Covered in `DestructuringUnitTest.forDestructuring*` and `ForLoopTest`.

### Medium effort, high value

- [ ] **`inline value class`** (M) — Zero-cost wrappers (`value class UserId(val raw: Int)`). KTC's by-value default makes this nearly free — emit the underlying primitive, treat the class as a phantom type. Big win for domain-model type safety.
- [x] **`typealias`** (M) — Parser recognizes the `typealias` keyword; AST gets `TypeAliasDecl`; collector populates `CCodeGen.typeAliases`; `CTypes.expandTypeAlias` resolves the chain transitively (cycle-detected). Worked with primitives, classes, nullable targets (`MaybeInt = Int?`), and chained aliases. Tests in `TypeAliasUnitTest`. As a side-effect, fixed a latent bug in `Var.kt`'s nullable-strip logic — it only stripped `?` when the type was *inferred* nullable, not when an explicit type resolved to nullable.
- [x] **`by lazy { ... }`** (M) — Local lazy vals: `bool $inited + T $cache` with init-check before first access. Top-level lazy vals: `ktc_thread_once_t` + `ktc_thread_call_once()` for thread-safe initialization (SYNCHRONIZED mode by default). Supports type inference and multi-statement bodies. Tests in `LazyTest`.
- [x] **Range operators on `Char` / `Long`** (S–M) — `'a'..'z'` and `1L..10L` (plus `until` / `downTo` / mixed-with-step) now emit the right loop-variable C type. `For.kt#rangeElementType` infers the kind from operands. Tests in `ControlFlowUnitTest.for*Char` / `for*Long` and integration coverage in `ForLoopTest`.

### Larger

- [ ] **Sealed-subclass exhaustiveness in type inference** (M) — Once `when` exhaustiveness is in, `when` expression types can be inferred without an `else` branch.
- [ ] **`object : Interface` (anonymous object expressions)** (M) — Synthetic class with vtable at the call site.
- [ ] **`Result<T>` stdlib type** (M) — `Result<Vec2>` for fallible operations. Needs a convention for `runCatching`.
- [ ] **Cross-class private visibility enforcement** (M) — Currently declared but not policed. Track `currentClass` and check at field/method access sites.

### Out of scope

- Coroutines (`suspend`, `async`) — conflicts with the no-runtime / no-GC contract.
- Full reflection (`KClass`, `::class`, annotations at runtime) — same.

## Codegen

### Build & toolchain

- [ ] **`-j N` in direct-gcc path** (S) — Compile .c files in parallel when there are many; cmake path already does.
- [ ] **ccache integration in `run_tests.py`** (S) — Detect `ccache` on PATH and prefix the C compiler. Combined with the write-if-changed transpiler, makes warm-cache rebuilds essentially instant.
- [ ] **`-G Ninja` for the user-cmake path** (M) — Faster than make on multi-file projects; also emit a standalone `build.ninja` for the no-cmake path.

### Emitted C quality

- [ ] **Static-bounds elision for proven-safe literal indices** (S) — `arr[0]` on a known-size array of ≥1 elements already passes the static check; also skip the runtime wrap to drop the helper call.
- [ ] **Static-null elision after `?.let { ... }` / `if (p != null) { ... }`** (S–M) — Inside the smart-cast block the pointer is non-null; skip the runtime null check.
- [ ] **Skip null-check when source is `.asRef()` of a local** (S) — Address-of a stack variable is never NULL; mark such expressions "non-null origin" through the codegen.
- [ ] **String literal deduplication** (M) — Every `ktc_core_str("foo")` builds a fresh `{ptr, len}`. Promote to `static const ktc_String` at file scope and reuse. Smaller code, better cache locality.
- [ ] **Collapse trivial `$ir` for single-expression inline bodies** (M) — Splice the body directly instead of declaring a result var.
- [ ] **Constant-fold trivial integer arithmetic at transpile** (S) — Not a perf win (C compiler does it) but the emitted C is more readable.

### Whole-program

- [ ] **Dead-function elimination for unused generic instantiations** (M) — `genericFunInstantiations` records every requested mangling, but some are never called. Walk the call graph and prune.
- [ ] **Combine package files when total LOC is small** (M) — One .c per package can mean many tiny TUs. Merge below a threshold to reduce linker work.

## CLI / tooling

### Transpiler flags

- [ ] **`--version`** (S) — Print transpiler version + commit SHA in `--help` header. Currently no way to identify which build is running.
- [ ] **`--check`** (S) — Lex + parse + type-infer + collect, skip code emission. Fast feedback for "is my code valid".
- [ ] **Colored error messages with source caret** (M) — Upgrade `file:line` + 3-line snippet to clang/rustc style with column underline.
- [ ] **`--diagnostics=json`** (M) — Machine-readable error output for editor / LSP integration.
- [ ] **`--explain <error-code>`** (M) — Stable error codes (e.g., `E0042`: "Cannot return value-type String") with a longer explanation. Discoverability.
- [ ] **`-W<name>` / `-Wno-<name>`** (M) — Per-warning controls so the user can opt out of specific warnings without going binary on `--no-check-bounds`.
- [ ] **`--strict`** (S) — Promote all warnings to errors. Useful for CI.

### `run_tests.py`

- [ ] **`--list`** (S) — Print all discovered tests without running. Helpful for shell completion and discovery.
- [ ] **`--filter <glob>`** / **`--exclude <glob>`** (S) — `--filter "stdlib/*"` to run only stdlib tests. More flexible than the comma list.
- [ ] **`--fail-fast`** (S) — Stop after the first failure. Speeds up CI bisects.
- [ ] **`--bench <test> [-n N]`** (M) — Run a test N times, report min/median/p95 of ktc/comp/run timings. Catches codegen perf regressions.
- [ ] **`--watch`** (M) — Re-run tests when a `.kt` file changes. Drives a fast inner-loop dev workflow.
- [ ] **Shell completion script** (S) — Generate bash/zsh completion for `--run <test-name>` from the discovered test list.
- [ ] **`NO_COLOR` env var support** (S) — Already partially handled via tty detection; respect the standard env var too for CI logs.
- [ ] **HTML test report** (M) — `--report html` writes a summary in `build/test-report.html` with timings, errors, and captured stdout/stderr.
- [ ] **`init <name>` subcommand** (M) — `python run_tests.py init MyApp` scaffolds a new test directory with `module.ktc.toml` and a starter `.kt`.

## Recommended order

The next 8 items, picked for value/effort ratio:

1. **`tailrec`** (S) — missing Kotlin idiom, trivial codegen.
2. **`inline value class`** (M) — big type-safety win, KTC's by-value semantics make it nearly free.
3. **Static null/bounds elision for proven-safe cases** (S–M) — measurable perf, builds on the recent runtime-check infrastructure.
4. **Colored error messages with caret** (M) — DX win, every subsequent task benefits.
5. **`--check` flag** (S) — fast feedback for editing loops.
6. **ccache integration in `run_tests.py`** (S) — dev iteration speed, free with the recent write-if-changed change.
7. **`when` exhaustiveness** (S–M) — type-safety, unblocks several follow-ups.
8. **`--filter` + `--list` for `run_tests.py`** (S) — ergonomics.

Defer the rest until usage patterns make them worth it.
