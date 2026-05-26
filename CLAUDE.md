# KotlinToC (KTC)

Source-to-source transpiler: **Kotlin → C11**. No runtime, no GC, no deps beyond libc.
Targets embedded/game/systems code.

## Build / Run / Test
- Build JAR: `./gradlew jar` → `build/libs/KotlinToC-1.0-SNAPSHOT.jar`
- Transpile: `java -jar <jar> file.kt -o out/ --name app`
- Unit tests: `./gradlew test` (fast; `TranspilerTestBase` transpiles snippets and asserts on emitted C)
- Full suite (builds JAR + transpile/compile/run integration tests, parallel):
  `./run_tests.ps1`  | single: `./run_tests.ps1 -Run TestName`  | skip unit: `-Skip unit`
- Integration tests live in `integration/<Name>/*.kt` (each compiled with gcc/cmake and executed).

## Architecture
- Pipeline: `Lexer` → `Parser` → `CCodeGen.generate()` (`src/main/kotlin`).
- Codegen split under `codegen/`: `expression/`, `statement/`, `emit/` (class/enum/iface/object), type inference in `TypeInfer*.kt`.
- Built-in call dispatch: `expression/CallBuiltins.kt` (arrayOf/StringBuffer), `CallAlloc.kt` (allocWith/ctors), `CallMethodBuiltins.kt` (resizeWith/copyWith/get/set).
- Std library is Kotlin source in resources: `src/main/resources/ktc/` (core: Any, Arrays, Allocator, Arena…) and `src/main/resources/modules/ktc.std/` (Collections, Map, Random…). These are transpiled, not hand-written C.
- Generics via monomorphization; interfaces via fat-pointer vtables `{obj, vt}`.

## Memory model
- Values are by-value on the stack by default. `@Ptr T` = pointer, `@Ptr T?` = nullable pointer (NULL).
- Heap allocation is allocator-based (no GC):
  - `T(ctorArgs...).allocWith(Heap)` → `@Ptr T` (fused alloc + ctor, no stack temp)
  - `Array<T>(n).allocWith(Heap)` / `RawArray<T>(n).allocWith(Heap)`
  - `arr.resizeWith(allocator, n)`, `arr.copyWith(allocator)`, `dataClass.copyWith(...)`
  - `Array<T>.fill(value)` / `RawArray<T>.fill(count, value)` → memset when byte-sized or zero-literal, else loop
  - `Array<T>.asRaw()` → `@Ptr RawArray<T>` (bare data ptr); `RawArray<T>.asArray(n)` → `@Ptr Array<T>` (VarArr) — both alias, no copy
  - `allocator.freeMem(ptr)` (e.g. `Heap.freeMem(p)`); `Heap` is the default `object : Allocator`.
  - `Arena` is a bump allocator implementing `Allocator`.
- The old `HeapAlloc`/`HeapArrayZero`/`HeapArrayResize`/`HeapFree` intrinsics were removed — use the allocator API.
- Note: `allocWith` returns a non-null `@Ptr`. To null-check, declare the var `@Ptr T?` explicitly.

## Pointer access
- Read through a `@Ptr T`: `p.value()` → `*p` (no parens variant `p.value` reserved for assignment LHS).
- Write through a `@Ptr T`: `p.value = x` → `*p = x;` (the old `p.set(x)` form is rejected — name was confusing).
- `p?.value = x` is supported and expands to `if (p) *p = x;`.
- Crossing the `@Ptr T` ↔ `T` boundary at var-decl/assignment requires explicit `.ptr()` or `.value()`. Implicit conversions are rejected with a fix-it pointing at the right form. Null literals, interface receivers (already wrapped in `ktc_IfacePtr`), and array-element pointers are exempt.

## String return safety
- Non-inline functions returning bare `String` are refused when the body builds the result at runtime (concat, template). The buffer would die at function exit; require `@Ptr String`, `@Size(N) String`, or mark the function `inline`. Literal-only bodies (every yield is a `StrLit`) and `String?` returns are allowed.

## Conventions
- Generated C: K&R style. Doc comments `/** */`.
- Kotlin codegen source: section markers `// ====` + `// MARK: Name` between major groups; concise comments only when non-obvious.
- Commits: no Co-Authored-By / AI attribution.
