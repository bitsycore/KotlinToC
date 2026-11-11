# ktc2 — Kotlin to C

Source-to-source transpiler: **Kotlin → C11**, with real Kotlin semantics (the front end is the
Kotlin K2 compiler) on automatic reference counting, no GC. `DESIGN.md` is the design and the
record of what was built, phase by phase; read its end for the latest state.

## Build / Run / Test
- Build the tool: `./gradlew installDist` → `build/install/ktc2/bin/ktc2`
- Translate, build, run: `ktc2 <module dir | file.kt | lib.def>... [-o dir] [--build | --cmake | --run [-- args]] [--debug]`
- Tests: `./gradlew test` runs every `integration/**/*.kt` as C (gcc, `-Wall -Werror`, leak check) and on the JVM, and compares the output. `--rerun` forces it when only an environment variable changed.
  - `KTC2_TEST_FILTER=<path part>` runs a subset (`objects/`, `stdlib/Regex`, `projects/`).
  - `python tools/failures.py` summarizes the failures of the last run.
  - `tools/try.sh <group/Name>` runs one program; `tools/try_cmake.sh <group/Name> [cmake args]` builds it through CMake (with no arguments on Windows: MSVC).
  - A test with a `<Name>.expected` is compared with that file instead of the JVM (C interop); `<Name>.def` is its C library; `<Name>.in` its standard input.
  - `examples/showcase/core` is run by the suite as a program of several modules (`projects/…`).
- `KTC2_CC` names the C compiler (default: gcc, else clang, else cc); `KTC2_CMAKE_ARGS` adds CMake arguments; `KTC2_TIMING=1` prints where compile time goes; `KTC2_DEBUG_BORROW=1` explains reference-count decisions.

## Architecture (`src/main/kotlin/com/bitsycore/ktc2/`)
- Pipeline: Kotlin K2 compiler (embeddable, pinned in `build.gradle.kts`) with the `Ktc2Extension` IR plugin (`frontend/K2Frontend.kt`) → `JavaUseCheck` → `KirImporter` + `BodyBuilder` (`frontend/KirImport.kt`, `BodyLowering.kt`: IR → KIR, monomorphizing from `main`) → `CycleLint` → `ExceptionLowering` → `EscapeAnalysis` → `ArcInsert` (with `Effects`, `BorrowedReturns`) → `CEmitter` (`backend/`).
- KIR, the intermediate form, is `kir/Kir.kt`. The C runtime is `src/main/resources/ktc2/ktc2_core.{h,c}`; everything specific to one C compiler is in one block at the top of the header.
- The standard library is the real Kotlin stdlib sources (listed in `resources/ktc2/stdlib/files.txt`, fetched by Gradle) plus a platform layer, the `Ktc2*.kt` files next to that list. `hidden.txt` lists the classes of the stdlib jar hidden from the compilation.
- C interop (`interop/`): `.def` files → generated bindings over `kotlinx.cinterop`; `platform.posix` is generated on demand.
- Modules (`Project.kt`): `module.ktc2.toml` with `name`, `executable`, `main`, `sources`, `dependencies` (a path, a bundled module, or a git URL `url#dir@tag`), `fetch`, `compilerOpts`, `linkerOpts`, `cmake`. Bundled modules are under `resources/ktc2/modules/` (`sdl3`, which fetches SDL3 and builds it with CMake). Each compilation writes a `CMakeLists.txt` next to the C.

## Rules of the design
- Plain Kotlin behaves as on the JVM; the tests enforce it. A difference is a bug.
- ktc2-only features live in the `ktc2.` package (`@Struct`, `@Value`, `@Size`, `arena`, `ValueArray`, `@Weak`, `WeakRef`, and `ktc2.concurrent`: `Thread`, `thread`, `synchronized`, `sleep`). Each changes storage and cost, never behavior, and has a JVM side under `src/main/kotlin/ktc2` so programs also run on the JVM.
- As in Kotlin/Native a program has no JDK: nothing of `java.*` (reported by `JavaUseCheck`). Do not add platform classes under `java.*`; files and similar are to come from kotlinx libraries (kotlinx.io or okio) through ktc2 compatibility layers.
- Memory: objects are references, counted; `EscapeAnalysis` moves them to the frame, `ArcInsert` leaves out the counting nobody can observe. Cycles are reported at compile time (`@Weak`, `WeakRef`, `@Acyclic`); there is no cycle collector.
- Exceptions are a pending value tested after each call that can throw; no `setjmp`.
- Not supported: coroutines (`sequence { }`, `iterator { }`), reflection.
- Generated sources — `Ktc2Arrays.kt`, `Ktc2Math.kt`, `Ktc2Comparisons.kt`, `Ktc2Unicode.kt` (by `tools/GenUnicode.java`, run with the JDK 21 of the build so the tables match what the tests compare with): change the script in `tools/`, not the file.

## Conventions
- Kotlin: standard Kotlin conventions, tabs, KDoc on classes and functions; section markers `// ====` + `// MARK: Name` between major groups; concise comments only when non-obvious.
- Names in the transpiler's source: locals `v…`, parameters `in…` (not on overrides of library members, nor on the public API of the `ktc2.` package, where names are used as named arguments), private members `m…`, constants `k…`.
- Generated C and the runtime: K&R style, doc comments with `/** */`.
- Commits: no Co-Authored-By / AI attribution.
