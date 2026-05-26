# KotlinToC (KTC)

Source-to-source transpiler: **Kotlin → C11**. No runtime, no GC, no deps beyond libc.
Targets embedded/game/systems code.

## Build / Run / Test
- Build JAR: `./gradlew jar` → `build/libs/KotlinToC-1.0-SNAPSHOT.jar`
- Transpile: `java -jar <jar> file.kt -o out/ --name app`
- Unit tests: `./gradlew test` (fast; `TranspilerTestBase` transpiles snippets and asserts on emitted C)
- Full suite (builds JAR + transpile/compile/run integration tests, parallel):
  `python3 run_tests.py`  | single: `python3 run_tests.py --run TestName`  | skip unit: `--skip unit`
- Integration tests live in `integration/<Category>/<Name>/*.kt` (each compiled with gcc/cmake and executed).
- Each test has a `module.ktc.toml` for config (dependencies, executable name, main entry, etc.).

## Architecture
- Pipeline: `Lexer` → `Parser` → `CCodeGen.generate()` (`src/main/kotlin`).
- Codegen split under `codegen/`: `expression/`, `statement/`, `emit/` (class/enum/iface/object), type inference in `TypeInfer*.kt`.
- Built-in call dispatch: `expression/CallBuiltins.kt` (arrayOf/StringBuffer), `CallAlloc.kt` (allocWith/ctors), `CallMethodBuiltins.kt` (resizeWith/copyWith/get/set).
- Std library is Kotlin source in resources: `src/main/resources/ktc/` (core: Any, Arrays, Allocator, Arena…) and `src/main/resources/modules/ktc.std/` (Collections, Map, Random…). These are transpiled, not hand-written C.
- Generics via monomorphization; interfaces via fat-pointer vtables `{obj, vt}`.

## Memory model
- Values are by-value on the stack by default. `Ref<T>` = non-null reference (`T*` in C), `Ref<T?>` = nullable reference (can be NULL).
- `Ref<T>?` is accepted but warns — prefer `Ref<T?>` (nullability belongs on the inner type).
- Heap allocation is allocator-based (no GC):
  - `T(ctorArgs...).allocWith(Heap)` → `Ref<T>` (fused alloc + ctor, no stack temp)
  - `Array<T>(n).allocWith(Heap)` / `RawArray<T>(n).allocWith(Heap)`
  - `arr.resizeWith(allocator, n)`, `arr.copyWith(allocator)`, `dataClass.copyWith(...)`
  - `Array<T>.fill(value)` / `RawArray<T>.fill(count, value)` → memset when byte-sized or zero-literal, else loop
  - `Array<T>.asRaw()` → `RawArray<T>` (bare data ptr); `RawArray<T>.asArray(n)` → `Ref<Array<T>>` (VarArr) — both alias, no copy
  - `allocator.freeMem(ref)` (e.g. `Heap.freeMem(p)`); `Heap` is the default `object : Allocator`.
  - `Arena` is a bump allocator implementing `Allocator`.
- Note: `allocWith` returns a non-null `Ref<T>`. To null-check, declare the var `Ref<T?>` explicitly.
- `RawArray<T>` is inherently a reference type (always `T*` in C) — no `Ref<>` wrapper needed.

## Reference access
- Read through a `Ref<T>`: `p.refValue` → `*p` (property-style access).
- Write through a `Ref<T>`: `p.refValue = x` → `*p = x;`.
- `p?.refValue = x` is supported and expands to `if (p) *p = x;`.
- Take a reference: `val.asRef()` → `&val` (returns `Ref<T>`).
- Crossing the `Ref<T>` ↔ `T` boundary at var-decl/assignment requires explicit `.asRef()` or `.refValue`. Implicit conversions are rejected with a fix-it pointing at the right form. Null literals, interface receivers (already wrapped in `ktc_IfacePtr`), and array-element pointers are exempt.

## String return safety
- Non-inline functions returning bare `String` are refused when the body builds the result at runtime (concat, template). The buffer would die at function exit; require `Ref<String>`, `@Size(N) String`, or mark the function `inline`. Literal-only bodies (every yield is a `StrLit`) and `String?` returns are allowed.

## Module system
- Project config is `module.ktc.toml` (replaces the old `deps.ktc.toml` + `config.ktc.toml`):
  ```toml
  name         = "My App"
  version      = "1.0"
  description  = "What this module does"
  authors      = ["name"]
  license      = "MIT"
  url          = "https://project-home"
  repository   = "https://github.com/user/repo.git#path/to/module"
  executable   = "myapp"          # output binary name
  main         = "pkg.funcName"   # explicit entry point (like --main)
  autoFindMain = true             # auto-detect single main function
  interactive  = true             # requires user interaction; --skip-interaction passed in test mode
  dependencies = ["ktc.std", "./../LocalMod", "https://github.com/user/repo.git#module"]
  ```
- Dependency sources: named (bundled JAR), relative (`./`), or URL (git clone to `~/.ktc/cache/`).
- Bundled modules: `src/main/resources/modules/<name>/` with `module.ktc.toml` + `.kt` files + optional `module.cmake`.

## Conventions
- For Kotlin and KTC use standard kotlin conventions.
- Generated C: K&R style. Doc comments with `/** */`.
- Kotlin codegen source: section markers `// ====` + `// MARK: Name` between major groups; concise comments only when non-obvious.
- Commits: no Co-Authored-By / AI attribution.
