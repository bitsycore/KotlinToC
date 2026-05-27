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

## String and Array return safety
`String` is `{ const ktc_Char* ptr; ktc_Int len; }`; `Array<T>` is `{ T* ptr; ktc_Int len; }`. **Both are passed by value as a struct copy — the struct (ptr+len) is duplicated, the underlying bytes are shared**. Behaves like C++'s `string_view` / Rust's `&str`: ~16-byte copy, no allocation, callee sees the same backing buffer.

Because the backing buffer is shared, **returning a freshly-built one from a non-inline function would dangle**. Same rule for both:
- **`String`**: bare returns are refused unless the function is `inline`, named `toString`, or its body returns only `StrLit`s (those point at `.rodata`). Otherwise use `Ref<String>` or `@Size(N) String`. `String?` returns are allowed.
- **`Array<T>` / `IntArray` / etc.**: bare returns are refused unless the function is `inline` (the inline body's stack array lands in the caller's frame, same logic as inline String concat). Otherwise use `Ref<Array<T>>` or `@Size(N) Array<T>`.

Conceptually: both types are slice-views. Passing them is cheap, but the callee never owns the data.

## Strings
- `String` is a value struct `{ const ktc_Char* ptr; ktc_Int len; }` — immutable view, no NUL-termination assumed. Literals point at `.rodata`.
- `s.length`, `s[i]`, `s.substring(a, b)`, `s.startsWith(...)`, `s.contains(...)`, `s == t` are supported and lower to `memcmp` / `ktc_core_string_*`. `==` uses `ktc_core_string_eq`, not byte-equality of the struct.
- `s + t` concatenation allocates a buffer via `alloca` (function-scoped) — safe for inline functions and immediate use, dangerous to return. See "String return safety" above.
- `s.toInt()` / `toLong()` / `toFloat()` / `toDouble()` parse on demand; `*OrNull()` variants return `Int?` etc.
- `StringBuffer` (mutable: `{ ktc_Char* ptr; ktc_Int len; ktc_Int cap; }`) requires a caller-provided backing buffer: `StringBuffer(charArray.ptr(), 0)`. With `ptr=NULL` it runs in counting-only mode (no writes, just `len` accumulates) so you can size a real buffer in a second pass.

## Arrays
- `Array<T>` is a `VarArr` struct `{ T* ptr; ktc_Int len; }` — variable-length, header carries the count. `arr.size` reads `.len`, `arr[i]` reads `.ptr[i]`.
- `RawArray<T>` is bare `T*` — a reference type with no length, no `.size`. Use when you already track the length elsewhere (FFI, fixed-size protocols). **Not bounds-checked** — there's no length to check against.
- `@Size(N) Array<T>` / `@Size(N) IntArray` is a fixed-size struct `{ T arr[N]; }` — lives entirely on the caller's stack, no heap. Pass by value. Assigning a larger source warns and inserts an implicit `.copyOf(N)` truncation (data loss).
- `Array<T>.asRaw()` → `RawArray<T>` (alias, no copy); `RawArray<T>.asArray(n)` → `Ref<Array<T>>` (alias plus length tag).
- Functions can return `@Size(N) T` arrays by value (struct return), but cannot return bare `Array<T>` or `RawArray<T>` — the underlying buffer would dangle. Use `Ref<Array<T>>` (heap-backed) or `@Size(N)` (stack struct).

## Runtime safety checks
Two safety nets default-ON, each with an opt-out flag. They emit calls to `ktc_core_*_check` helpers that print a Kotlin-style stack trace and exit on violation.

**Bounds check** — every `arr[i]` / `s[i]` for `Array<T>`, `String`, or `@Size(N)` routes through `ktc_core_bounds_check(file, line, idx, len)`. Static-warning sibling fires at transpile time when the index is a literal AND length is statically known (`@Size(N)`, string literals). Opt out with `--no-check-bounds`. `RawArray<T>` (bare `T*`) carries no length and is never bounds-checked.

**Null-deref check** — every `p.refValue` / `p.refValue = x` is preceded by `ktc_core_null_check(p, file, line)` which exits with `NullPointerException` if `p` is NULL. The static analysis already refuses bare `.refValue` on a statically-nullable `Ref<T?>` (forces `?.refValue`), so this catches the harder cases — allocator failure returning NULL, dangling pointer becomes NULL, etc. Opt out with `--no-check-null`.

Both flags are accepted as `--check-bounds` / `--check-null` too (no-op since default is already ON) for explicit-intent clarity.

## Operator overloading
- `operator fun` arities are enforced at transpile time: `plus`/`minus`/`times`/`div`/`rem` take 1 arg, unaries (`unaryPlus`/`unaryMinus`/`not`/`inc`/`dec`) take 0, `compareTo`/`contains`/`rangeTo` take 1, iterator protocol (`iterator`/`hasNext`/`next`) takes 0, `equals` takes 1.
- `get`/`set`/`invoke` have free arity (Kotlin allows multi-index access like `m[i, j]`).

## Lambdas and functional types
- Lambdas are **inline-only**: there is no closure heap allocation, no escaping function-pointer-with-captures. A function returning a function type (`(Int) -> Int`) must be marked `inline` so the body is expanded at the call site; non-inline lambda returns are rejected at transpile.
- Lambda parameters of inline functions are expanded in place — `let`, `apply`, `with`, `run`, `also`, `takeIf`, `repeat` all work through this mechanism.
- Standalone lambda expressions outside of inline-function argument position are not supported.

## Limitations vs Kotlin (intentional)
- No coroutines (`suspend`, `async`), no reflection (no `KClass`, `::class`), no `java.lang.*`.
- No `inner class` with implicit outer-instance capture. Nested classes are fine; they're emitted as `Outer$Inner`.
- No general property delegation (`by`). `by lazy { ... }` is supported for local and top-level vals (thread-safe via `ktc_thread_call_once` for top-level).
- No `inline value class`.
- No variance modifiers (`in`/`out`) enforced; generic substitution is purely positional.
- Exhaustiveness for `when` is warned on enums (each entry covered) and on sealed classes / sealed interfaces (each direct subclass covered via `is` branches). Adding an `else` branch silences the warning.
- Enums default to the full Kotlin-style form (struct with ctor-param fields + `.ordinal` + `.name`) and support primary ctor params, per-entry args, body methods, and `.values()` / `.valueOf()`. `@SimpleEnum` opts into the zero-overhead C-int form (asserted at parse time to have no ctor/body). Per-entry method overrides (`PLUS { override fun apply(...) }`) are not yet supported — tracked as Phase 3 in plan.md.
- `RawArray<T>` is never bounds-checked (no length carried). `Array<T>` / `String` / `@Size(N)` indexing IS checked by default — see "Bounds checking" above.
- `private` fields and methods are enforced across class boundaries (codegen error on access from outside). `internal` on classes and top-level functions is enforced per-package (cross-package access is rejected with E044).
- Direct self-recursive class layouts (`class Node(val next: Node)`) are rejected — must indirect through `Ref<Node>`, `Array<Node>`, or `RawArray<Node>`.

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
