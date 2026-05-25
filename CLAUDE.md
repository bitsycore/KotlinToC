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
  - `T.allocWith(Heap, ctorArgs...)` → `@Ptr T`
  - `Array<T>.allocWith(Heap, n)` / `RawArray<T>.allocWith(Heap, n)`
  - `arr.resizeWith(allocator, n)`, `arr.copyWith(allocator)`, `dataClass.copyWith(...)`
  - `allocator.freeMem(ptr)` (e.g. `Heap.freeMem(p)`); `Heap` is the default `object : Allocator`.
  - `Arena` is a bump allocator implementing `Allocator`.
- The old `HeapAlloc`/`HeapArrayZero`/`HeapArrayResize`/`HeapFree` intrinsics were removed — use the allocator API.
- Note: `allocWith` returns a non-null `@Ptr`. To null-check, declare the var `@Ptr T?` explicitly.

## Conventions
- Generated C: K&R style. Doc comments `/** */` with Spirtech prefix convention (`fField` fields, SCREAMING_SNAKE constants).
- Kotlin codegen source: section markers `// ====` + `// MARK: Name` between major groups; concise comments only when non-obvious.
- Commits: no Co-Authored-By / AI attribution.
