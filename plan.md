# KTC plan

Working backlog of proposed improvements across three axes:

- **Kotlin features** — Kotlin source-language compatibility gaps.
- **Codegen** — quality, size, and speed of the emitted C.
- **CLI / tooling** — transpiler flags and the `run_tests.py` harness.

Each item has a size estimate: **S** ≈ one commit, **M** ≈ a few commits, **L** ≈ multi-session refactor. Items are not strictly ordered within sections — see *Recommended order* at the end for the next 8.

## Kotlin features

### Quick wins

- [x] **`tailrec` keyword** (S) — Lower a tail call into a `goto`-loop. Error if the marked function isn't actually tail-recursive; warn if an unmarked function could be. Works for free fns, extension fns, and methods. Arity-checked to avoid false positives on overloads. Integration test: `TailrecTest`.
- [x] **`when` exhaustiveness** (S–M) — Warning when a `when` on an enum subject has no `else` and doesn't cover every case. Works for both statement and expression `when`. Tests in `WhenExhaustUnitTest`.
- [x] **`reified` type parameters** (S) — Parser accepts `reified`, `out`, `in` modifiers on type parameters. `T::class.simpleName` resolves to concrete type name via `typeSubst`. Fixes explicit type arg handling on inline generic calls. Tests in `ReifiedUnitTest`.
- [x] **Raw string literals `"""..."""`** (S) — Already worked in the lexer; covered with unit tests in `StringUnitTest.rawString*`.
- [x] **Destructuring in `for` loops** (S) — `for ((k, v) in map) { ... }` lands a synthetic `$ditem_<names>` temp + a per-iteration destructuring-decl. Covered in `DestructuringUnitTest.forDestructuring*` and `ForLoopTest`.

### Medium effort, high value

- [x] **`inline value class`** (M) — Zero-cost wrappers (`value class UserId(val raw: Int)`). Parses `value class`, `inline value class`, and `@JvmInline value class`. Erases to underlying primitive at every level: variable declarations, function parameters/returns, constructor calls, property access, toString/println dispatch. Validates exactly one val property, no body properties. Tests in `ValueClassUnitTest` and `ValueClassTest`.
- [x] **`typealias`** (M) — Parser recognizes the `typealias` keyword; AST gets `TypeAliasDecl`; collector populates `CCodeGen.typeAliases`; `CTypes.expandTypeAlias` resolves the chain transitively (cycle-detected). Worked with primitives, classes, nullable targets (`MaybeInt = Int?`), and chained aliases. Tests in `TypeAliasUnitTest`. As a side-effect, fixed a latent bug in `Var.kt`'s nullable-strip logic — it only stripped `?` when the type was *inferred* nullable, not when an explicit type resolved to nullable.
- [x] **`by lazy { ... }`** (M) — Local lazy vals: `bool $inited + T $cache` with init-check before first access. Top-level lazy vals: `ktc_thread_once_t` + `ktc_thread_call_once()` for thread-safe initialization (SYNCHRONIZED mode by default). Supports type inference and multi-statement bodies. Tests in `LazyTest`.
- [x] **Range operators on `Char` / `Long`** (S–M) — `'a'..'z'` and `1L..10L` (plus `until` / `downTo` / mixed-with-step) now emit the right loop-variable C type. `For.kt#rangeElementType` infers the kind from operands. Tests in `ControlFlowUnitTest.for*Char` / `for*Long` and integration coverage in `ForLoopTest`.

### Larger

- [ ] **Sealed-subclass exhaustiveness in type inference** (M) — Once `when` exhaustiveness is in, `when` expression types can be inferred without an `else` branch.
- [ ] **`object : Interface` (anonymous object expressions)** (M) — Synthetic class with vtable at the call site.
- [ ] **`Result<T>` stdlib type** (M) — `Result<Vec2>` for fallible operations. Needs a convention for `runCatching`.
- [x] **Cross-class private visibility enforcement** (M) — `currentClass` tracked at codegen; private fields, methods, and `private set` are rejected at access sites. Unit tests in `PrivateUnitTest`.

### Out of scope

- Coroutines (`suspend`, `async`) — conflicts with the no-runtime / no-GC contract.
- Full reflection (`KClass`, `::class`, annotations at runtime) — same.

## Codegen

### Build & toolchain

- [ ] **`-j N` in direct-gcc path** (S) — Compile .c files in parallel when there are many; cmake path already does.
- [x] **ccache integration in `run_tests.py`** (S) — Auto-detects `ccache` on PATH; prefixes the C compiler in direct-gcc mode, passes `CMAKE_C_COMPILER_LAUNCHER` for cmake.
- [ ] **`-G Ninja` for the user-cmake path** (M) — Faster than make on multi-file projects; also emit a standalone `build.ninja` for the no-cmake path.

### Emitted C quality

- [x] **Static-bounds elision for proven-safe literal indices** (S) — When index is a non-negative literal within a statically-known array size, the runtime `bounds_check` call is elided.
- [ ] **Static-null elision after `?.let { ... }` / `if (p != null) { ... }`** (S–M) — Inside the smart-cast block the pointer is non-null; skip the runtime null check.
- [x] **Skip null-check when source is `.asRef()` of a local** (S) — `isProvablyNonNull()` detects `.asRef()` origins and skips the runtime null check. Unit tests in `ElisionUnitTest`.
- [x] **String literal deduplication** (M) — Post-processing pass in `generate()`: scans each `.c` file for `ktc_core_str("...")` used 2+ times, promotes them to `#define $pkg_sN ktc_core_str("...")` in the package header. Package-prefixed names avoid cross-header collisions. `#define` avoids MSVC compound-literal-at-file-scope issue.
- [x] **Collapse trivial `$ir` for single-expression inline bodies** (M) — `tryGenInlineExpr` splices single-expression inline bodies directly without `$ir` temp var. Tests in `ElisionUnitTest`.
- [x] **Constant-fold trivial integer arithmetic at transpile** (S) — `IntLit op IntLit` for `+`, `-`, `*`, `/`, `%` folded at transpile time. Tests in `ElisionUnitTest`.

### Whole-program

- [ ] **Dead-function elimination for unused generic instantiations** (M) — `genericFunInstantiations` records every requested mangling, but some are never called. Walk the call graph and prune.
- [ ] **Combine package files when total LOC is small** (M) — One .c per package can mean many tiny TUs. Merge below a threshold to reduce linker work.

## CLI / tooling

### Transpiler flags

- [x] **`--version`** (S) — Prints `ktc <version>` from JAR manifest.
- [x] **`--check`** (S) — Lex + parse + collect for each package, skip code emission. Prints `OK` or error.
- [x] **Colored error messages with source caret** (M) — `Stmt` now carries `col`; `sourceSnippet` renders a `^` caret at the column position; `locationPrefix` includes `file:line:col:` when column is available.
- [x] **`--diagnostics=json`** (M) — `--diagnostics=json` outputs errors/warnings as a JSON array with severity, message, file, line, col. Suppresses human-readable output.
- [ ] **`--explain <error-code>`** (M) — Stable error codes (e.g., `E0042`: "Cannot return value-type String") with a longer explanation. Discoverability.
- [x] **`-W<name>` / `-Wno-<name>`** (M) — Per-warning controls. Named warnings: `shadow`, `nullable-ref`, `tailrec-inline`, `tailrec-suggestion`, `const-condition`, `exhaustive-when`, `null-check`, `safe-call`. Default all-on; `-Wno-<name>` suppresses.
- [x] **`--strict`** (S) — `codegenWarning()` promotes to `codegenError()` when `--strict` is on. Passed through `run_tests.py --strict`.

### `run_tests.py`

- [x] **`--list`** (S) — Prints all discovered tests (respects `--filter`/`--exclude`).
- [x] **`--filter <glob>`** / **`--exclude <glob>`** (S) — Glob matching against test relPath or name. Works with `--list` and full runs.
- [x] **`--fail-fast`** (S) — Stops after first failure; cancels pending futures in parallel mode.
- [x] **`--bench <test> [-n N]`** (M) — `--bench TestName -n 10` runs a test N times and reports min/median/p95/mean of ktc/compile/run timings.
- [ ] **`--watch`** (M) — Re-run tests when a `.kt` file changes. Drives a fast inner-loop dev workflow.
- [x] **Shell completion script** (S) — `--completions bash|zsh|fish` prints a completion script. Completes flags + test names for `--run`/`--bench`/`--filter`.
- [x] **`NO_COLOR` env var support** (S) — `_gNoColor` flag disables all ANSI escapes and live progress when `NO_COLOR` is set or stdout is not a tty.
- [ ] **HTML test report** (M) — `--report html` writes a summary in `build/test-report.html` with timings, errors, and captured stdout/stderr.
- [x] **`init <name>` subcommand** (M) — `python run_tests.py init MyTest` creates `integration/intrinsic/MyTest/` with `module.ktc.toml` and a starter `.kt` file.

## Recommended order

The next items, picked for value/effort ratio:

1. ~~**`tailrec`**~~ — done.
2. ~~**`inline value class`**~~ — done.
3. ~~**Static null/bounds elision**~~ — done.
4. ~~**Colored error messages with caret**~~ — done. Column tracking on `Stmt`, caret display in `sourceSnippet`.
5. ~~**`--check` flag**~~ — done.
6. ~~**ccache integration**~~ — done.
7. ~~**`when` exhaustiveness**~~ — done.
8. ~~**`--filter` + `--list`**~~ — done.

Defer the rest until usage patterns make them worth it.
