# ktc2 - how it works

ktc2 translates Kotlin to C11. This document describes what is built, as it is. What is left
to do is in `PLAN.md`; why things are as they are, and what was tried on the way, is in
`HISTORY.md`.

---------------------------------------------------------------------------
## 1. The two rules
---------------------------------------------------------------------------

1. **Plain Kotlin behaves as on the JVM.** The front end is the Kotlin compiler itself (K2),
   so resolution and typing are Kotlin's. Every test program is run as C and on the JVM and
   must print the same. A difference is a bug in ktc2.
2. **A ktc2 feature changes storage and cost, never behavior.** Everything ktc2 adds is in
   the `ktc2.` package and has a plain definition for the JVM (`src/main/kotlin/ktc2/`), so a
   program that uses it still runs on the JVM and prints the same.

What follows from them: no dialect, no annotations that change meaning, and nothing of the
JDK (a program has no `java.*`, as in Kotlin/Native; `JavaUseCheck` reports uses).

The exceptions to rule 1 that exist today are listed in section 12.

---------------------------------------------------------------------------
## 2. Pipeline
---------------------------------------------------------------------------

```
sources + library sources + generated bindings
  -> Kotlin K2 compiler, in process, with the Ktc2Extension IR plugin   frontend/K2Frontend.kt
  -> JavaUseCheck            no java.* in the program                    frontend/JavaUseCheck.kt
  -> KirImporter/BodyBuilder IR -> KIR, from main, monomorphizing        frontend/KirImport.kt, BodyLowering.kt
  -> CycleLint               warns about classes that can form cycles    backend/CycleLint.kt
  -> ExceptionLowering       throw/try -> pending value + tests          backend/ExceptionLowering.kt
  -> EscapeAnalysis          objects to the frame, results in place      backend/EscapeAnalysis.kt
  -> ArcInsert               retain/release, minus what is unobservable  backend/ArcInsert.kt (+ Effects, BorrowedReturns)
  -> CEmitter                one .h/.c per class, CMakeLists.txt         backend/CEmit.kt
```

- The plugin receives the module's IR after the compiler's front end and before any JVM
  lowering; when it is done it empties the module, so no JVM code is generated.
- **KIR** (`kir/Kir.kt`) is a small typed form: statements (`KLet`, `KSet`, `KFieldSet`,
  `KIf`, `KLoop`, `KReturn`...) over expressions. Calls and allocations are only ever the
  whole right-hand side of a statement, every temporary has a name, control flow is explicit.
- The importer starts at `main` and translates only what is reached. Generic code is
  instantiated per set of type arguments, where every reference type counts as `Any?`: one
  `ArrayList<Any>` serves every list of objects, `ArrayList<Int>` holds ints unboxed. A
  `@Struct` without references gets its own instantiation and is stored in place; one that
  holds references is kept in a box inside generic code and shares the `Any?` instantiation.
- Compile time is the front end reading the library sources: about 11 s for a small program.
  `KTC2_TIMING=1` prints the split.

---------------------------------------------------------------------------
## 3. Types and where values live
---------------------------------------------------------------------------

| Kotlin | C |
|---|---|
| `Int`, `Double`, `Boolean`, `Char` (16-bit)... | the C scalar |
| `Int?` and other nullable numbers | a two-field struct: flag and value (`ktc2_Opt_...`) |
| a class, `Any`, an interface | a pointer to an object that starts with the 8-byte header |
| `String` | a pointer to `{ header, size, length, bytes[] }`: UTF-8, NUL-terminated |
| `Array<T>`, `IntArray`... | a pointer to `{ header, length, elementSize, elements[] }` |
| a `@Struct` class, a final non-generic `value class` | a C struct, by value |
| a lambda, a function reference | an object with an `invoke`; captured variables that are written live in a cell |
| `CPointer<T>` and the other interop pointers | `void*`, never counted |

**The object header** is two 32-bit words: a count with flag bits (`KTC2_STACK`, `KTC2_WEAK`,
`KTC2_ARENA`; `KTC2_IMMORTAL` for static objects) and a type id, the index into the generated
table of type names and `destroy` functions.

**Strings** keep Kotlin's meaning of `length` and `s[i]` (UTF-16 code units): constant time
when the string is all ASCII, a scan otherwise.

**Where an object is stored**, from cheapest:

| Place | When |
|---|---|
| static | string literals, `object`s, enum entries |
| the frame | escape analysis shows it does not leave its block: objects, arrays of constant size up to 64 elements, arrays of run-time size up to 256 bytes, strings built from templates up to 128 bytes |
| inside its owner | a `@Value` or `@Size(n)` field; a `@Struct` anywhere |
| the caller's frame | a function that returns a new object has a second form, `name__into(slot, ...)`, that builds it in a slot of the caller |
| an arena | allocated inside `arena(size) { }` |
| the heap | everything else |

`--warn-heap` lists every allocation left on the heap, with the reason.

---------------------------------------------------------------------------
## 4. The `ktc2.` package
---------------------------------------------------------------------------

| | |
|---|---|
| `@Struct` on a class | held by value everywhere. Boxed when used as `Any`, an interface or nullable. May hold references. |
| `@Value` on a type | a field with it holds the object in place. A local with it must stay in the frame: it is an error if the object could outlive the block. |
| `@Size(n)` on an array | a field with it holds the `n` elements in place; a local with it must stay in the frame. |
| `ValueArray<T>` | an array whose elements are in place. Exists because `Array<@Value T>` cannot be made safe: the annotation does not change the Kotlin type. |
| `arena(size) { }` | allocations in the block come from blocks of `size` bytes. Objects are still counted; one that outlives the arena keeps its block alive. |
| `@Weak` on a property, `WeakRef<T>` | references that do not keep their target alive; they read as null once it is gone. |
| `@Acyclic` on a property | tells the cycle warning this reference never closes a cycle. |
| `ktc2.concurrent` | `Thread`, `thread { }`, `synchronized(lock) { }`, `sleep(ms)`. Kotlin has these on the JVM only. |
| `Deinit` | declared, **not implemented in C**: see `PLAN.md`. |

---------------------------------------------------------------------------
## 5. Reference counting
---------------------------------------------------------------------------

The rules `ArcInsert` applies:

- Parameters are borrowed: the caller keeps the object alive for the call.
- A call, an allocation or a string concatenation hands its result to the caller, counted.
- A local of reference type owns what it holds: declared null at the start of its block,
  released on every way out of it.
- Storing into a local, field, top-level property or array element takes a count and
  releases what was there.
- An argument that is not a local is first held in a temporary.

What is left out because nobody can observe it:

- A local read exactly once, by a store or a return, hands its count over.
- A local that is another name for something held at least as long (a parameter, a local
  never reassigned, a `val` field of either) borrows: no retain, no release.
- The same for what is read from a `var` field, an array element or a top-level property,
  when nothing in the rest of the block can overwrite that place. "Can overwrite" comes from
  `Effects`: for each function, what it and everything it calls may write (each field of each
  class, elements of reference arrays as one, each top-level property). C written by the
  program may write anything; so may starting or joining a thread and entering or leaving a
  monitor, since another thread's writes come through there.
- A function that only hands out what something else keeps (`list[i]`, a getter,
  `iterator.next()`) has a second form whose result is not counted: `name__b`, or `name__m`
  for a dispatcher where only some implementations qualify. The `__m` form takes one more
  argument, the address of a variable of the caller: `x = List_get__m(list, i, &keep)`. When
  the implementation behind the call borrows, `keep` stays null; when it returns an object of
  its own, that object is also put in `keep`, which the caller releases with the block. See
  `BorrowedReturns`.

Counts are plain until the program starts its first thread, atomic after, and always plain
for objects in a frame.

**Cycles** are not collected. `CycleLint` warns at compile time about classes that can reach
themselves through strong references; `@Weak`, `WeakRef` or `@Acyclic` answer the warning.

Checking it: test builds define `KTC2_LEAK_CHECK` (a report at exit of objects still alive,
and freed objects are overwritten so a wrong borrow fails) and `KTC2_ARC_STATS` (counts of
allocations, retains and releases, written to `build/integration/arc-stats.txt`).
`KTC2_DEBUG_BORROW=1` explains why something was or was not borrowed.

---------------------------------------------------------------------------
## 6. Calls, classes, exceptions
---------------------------------------------------------------------------

- **Inheritance**: a subclass struct starts with its superclass struct.
- **Virtual and interface calls** go through a generated dispatcher: a `switch` on the type id
  that calls the implementation of that class directly. Only classes the program creates
  appear in it. `is` and `as` are tests of the same id. There are no vtables.
- **Exceptions** are checked propagation: `throw` parks the exception in a thread-local slot
  and the function leaves; after a call that can throw, the caller tests the slot and leaves
  in turn, releasing what it holds. A `try` is a `KTryRegion` in KIR, which `ExceptionLowering`
  turns into a loop that runs once: leaving it is a `break`. There is no `setjmp`.
- **The checks of the language** (array and string index, integer division by zero, null
  assertion, casts) throw the Kotlin exceptions. `--checks=abort` makes index and division
  failures end the program instead.
- **An assignment whose right-hand side fails** leaves its target as it was.
- **Objects, enum classes and the top-level properties of a file** are initialized on first
  use, as Kotlin says. When an initializer only computes and stores, it runs at startup
  instead and costs nothing later. First use from several threads is synchronized.

---------------------------------------------------------------------------
## 7. The standard library
---------------------------------------------------------------------------

The library is the real one, compiled from source with every program:

- `src/main/resources/ktc2/stdlib/files.txt` lists the files of the Kotlin stdlib sources
  that are used unmodified (Gradle fetches the sources jar; the installed tool carries the
  files it needs).
- The `Ktc2*.kt` files next to that list are the **platform layer**: what the JVM normally
  provides (`String`, arrays, `ArrayList`, `HashMap`, `StringBuilder`, `Regex`, `Char`
  classification, math, time, threads), written in Kotlin over a few runtime functions
  (`ktc2.internal`, implemented in `ktc2_core.c`).
- The stdlib jar is still on the compiler's class path, for what is not compiled from source.
  Two mechanisms keep it from getting in the way. Where a file of `files.txt` is compiled from
  source, the jar's class holding the same top-level declarations is hidden automatically, and
  `hidden.txt` lists further classes to hide (JVM-only `actual`s, typealiases to `java.util`):
  otherwise a call would see two candidates and fail to compile as ambiguous. Where a call
  still resolves to a function of the jar that also has a source in the module, the importer
  takes the source (`sourceOf`). A call to a jar function with no source at all is reported
  as "not supported yet: call to ...".

What is there: collections and sequences (not `sequence { }`; a library function that uses
it on one of its paths, like `chunked` on something that is not a list, compiles and fails
when that path is taken), strings, `Char` with Unicode
for the 16-bit range, `Regex` with Java's syntax (not the `U` flag), ranges, `kotlin.math`,
`kotlin.time` (`Duration`, `TimeSource`, `measureTime`; not `Clock` and `Instant`),
`kotlin.random`, `Result`, the property delegates, `readln`.

Several files are generated; change the generator, not the file:

| File | Generator |
|---|---|
| `Ktc2Arrays.kt` | `tools/gen_arrays.py` |
| `Ktc2Math.kt` | `tools/gen_math.py` |
| `Ktc2Comparisons.kt` | `tools/gen_comparisons.py` |
| `Ktc2Unicode.kt` | `tools/GenUnicode.java`, run with the JDK 21 of the build so the tables match what the tests compare with |

---------------------------------------------------------------------------
## 8. C interop
---------------------------------------------------------------------------

Kotlin/Native's model and syntax. A `.def` file names headers; `interop/Bindings.kt` runs
the C preprocessor (gcc or clang) over them, `CHeaderParser` reads the declarations, and a
Kotlin file of bindings is generated and compiled with the program:

- functions, including variadic ones; structs (fields as properties, by value as `CValue<T>`);
  typedefs; constants and simple macros; global variables;
- enums: a Kotlin enum class when the constants are simply listed, integer constants when
  they are given values (`strictEnums` / `nonStrictEnums` in the `.def` decide otherwise);
- callbacks: `staticCFunction`, also with structs by value (a small C function is generated
  in front of the Kotlin one); `StableRef` to pass an object through C.

A generated binding is an inline function whose body is the C call itself (`cExpr`), so there
is no bridge code. `import platform.posix.*` generates the bindings of the C library on demand.

---------------------------------------------------------------------------
## 9. Modules and building
---------------------------------------------------------------------------

A directory with a `module.ktc2.toml` is a module (`Project.kt`):

```toml
name         = "game"
executable   = "game"                     # default: the name
main         = "game.start"               # default: the one `main`
sources      = ["src"]                    # default: the directory
dependencies = ["sdl3", "../engine", "https://github.com/user/repo.git#dir@tag"]
fetch        = ["SDL3=https://github.com/libsdl-org/SDL.git@release-3.4.8"]
compilerOpts = ["-I${SDL3}/include"]
linkerOpts   = ["-L/opt/lib"]
cmake        = "module.cmake"
files        = ["Api.kt", "lib.def"]      # only in a module shipped with ktc2: its files, since resources cannot be listed
```

- A dependency is a module shipped with ktc2 (`src/main/resources/ktc2/modules/`, today
  `sdl3`), a directory, or a place in a git repository. Repositories are cloned once into
  `~/.ktc2/cache` (`KTC2_CACHE`).
- Output goes to `<module>/build` (or `./out`, or `-o`): `c/` with one `.h`/`.c` per unit
  under its package (a unit is a class, the top-level declarations of one file as `<File>Kt`,
  a lambda, or the box of a `@Struct`), `ktc2_core.h/.c`, `ktc2_config.h` (what this program
  needs of the runtime: threads, checks), `ktc2_main.c`, `ktc2_types.h`, `KtDispatch.c`; a
  `ktc2_build.txt` listing the C files and options; a `CMakeLists.txt`.
- The C compiler is the one `KTC2_CC` names, else the first of gcc, clang and cc on the path,
  for `--build` and for reading headers when bindings are generated. The test suite uses gcc
  unless `KTC2_CC` says otherwise, and its options are gcc's.
- `--build` calls the C compiler directly. `--cmake`, or any
  module with a CMake file, builds through CMake (`KTC2_CMAKE_ARGS` adds arguments). A
  module's CMake file sees the program as `${KTC2_TARGET}` and a fetched repository as
  `${KTC2_FETCH_<NAME>}`.

---------------------------------------------------------------------------
## 10. The C that comes out
---------------------------------------------------------------------------

C11, nothing beyond libc and the system's threads. Everything specific to one compiler is in
one block at the top of `ktc2_core.h` (atomics, bit counts, `alloca`).

| | State |
|---|---|
| gcc on Windows (mingw) | the whole suite, on every change |
| clang on Windows (MSVC ABI) | the showcase's tests |
| MSVC through CMake | the showcase's tests, threads included; one harmless warning (C4116) per array kept in the frame |
| gcc, clang on Linux and macOS | written for, never compiled |
| Android NDK | not there: no console, no `main`; see `PLAN.md` |

---------------------------------------------------------------------------
## 11. Tests
---------------------------------------------------------------------------

`./gradlew test --rerun` (`src/test/kotlin/.../IntegrationTest.kt`): every `integration/**/*.kt` is
translated, built with gcc (`-O1 -Wall -Werror`, leak check, counts) and run; the same file
is compiled and run on the JVM; the outputs must be equal and nothing may leak.

- `<Name>.expected` replaces the JVM for programs that use C (`integration/interop/`);
  `<Name>.def` is the C library of the test; `<Name>.in` its standard input.
- `examples/showcase/core` is run the same way as a program of several modules.
- `integration/ported/` are the plain-Kotlin tests of the first transpiler.
- There are no unit tests of the passes: everything is tested through whole programs.

`examples/showcase` is the program that uses a bit of everything, with an SDL3 window. It is
not in the suite (it fetches and builds SDL3). `--headless` runs its tests with no window;
`--auto` opens the window and clicks by itself, which with `SDL_VIDEODRIVER=dummy` needs no
screen, and `--shot file.bmp` saves what it drew.

There is no CI: the only gate is running the suite, on Windows.

---------------------------------------------------------------------------
## 12. Known differences and gaps
---------------------------------------------------------------------------

Not Kotlin, by design:
- no coroutines (`sequence { }`, `iterator { }`, `suspend`), no reflection. A call into them
  from the library compiles and throws `UnsupportedOperationException` when it is reached;
- no JDK;
- destruction is immediate, not deferred: visible only through weak references.

Kotlin, but not done:
- `Deinit.deinit()` is never called in C;
- `Clock`, `Instant`; the `U` flag and four-letter script codes of `Regex`;
- case conversion of characters beyond U+FFFF;
- `isDaemon`, names and priorities of threads;
- files (to come from kotlinx.io or okio, through a ktc2 compatibility layer).

Known to differ from the JVM:
- the clock behind `kotlin.time` and `measureTimeMillis` is the clock of the day, not a
  monotonic counter: setting the system clock during a measurement shows in the result;
- paths and program arguments are UTF-8, which on Windows is right for ASCII only.

---------------------------------------------------------------------------
## 13. Inside the code
---------------------------------------------------------------------------

Most fixes of a difference from Kotlin land in the importer, which is also the largest part
(`BodyLowering.kt` and `KirImport.kt` are four tenths of the code). Its sources have KDoc on
nearly every function; this is the map.

**`KirImporter`** (`frontend/KirImport.kt`) owns everything that exists once per program:

- `classFor`, `structFor`, `functionFor`, `globalFor` turn an IR declaration, with its type
  arguments, into its KIR counterpart, creating it on first request. That is the
  monomorphization: the key is the declaration and the `canonical` form of the arguments.
  A function's body is not built at once but queued, so recursion and cycles are no problem.
- `kTypeOf` maps a Kotlin type to a KIR type (`KPrimType`, `KOptType`, `KClassType`,
  `KObjType`, `KArrayType`, `KStringType`, `KStructType`, `KPtrType`, `KUnitType`, `KNothingType`).
- Names in C: package and class joined by `_`, then the function; overloads get `__` and the
  mangled parameter types, instantiations get `__` and the mangled type arguments
  (`kotlin_collections_ArrayList__Any_get`); `uniqueName` settles what is left.
- `dispatcherFor` makes the function that switches on the type id for a virtual or interface
  call; closures and `fun interface` objects are registered so that `invoke` dispatches to them.
- `sourceOf` swaps a function of the stdlib jar for its source; `internalFunction` finds a
  function of the platform layer's `ktc2.internal` package by name; `runtime` declares a
  function implemented in `ktc2_core.c`.
- Initialization on first use (`ensure` functions) is decided at the end, in
  `settleInitialization`.
- `unsupported` records an error with its file and line. Errors do not stop the import: all
  of them are printed, and the count goes back to the driver through a system property.

**`BodyBuilder`** (`frontend/BodyLowering.kt`) builds the KIR of one function body:

- `lowerStatement` and `lowerRhs` walk the IR. An expression is lowered to a KIR expression;
  whatever it needs done first (a call, an allocation) is appended to the current block.
- The invariant it keeps: a call or an allocation is the whole right-hand side of a
  statement. `spill` names a value in a temporary; `lowerPure` lowers and spills what is not
  pure; `lowerOperands` and `protect` name earlier operands when a later one needed
  statements, so evaluation order is Kotlin's.
- `coerce` adapts a value to the type a place wants: casts between reference types, boxing,
  wrapping and unwrapping nullable numbers, struct to box and back. The IR leaves these implicit.
- `lowerCall` is the dispatch for calls: interop intrinsics, `inline` functions
  (`expandInline`: the body is lowered in place, lambdas passed to it are bound and expanded
  by `expandLambda`, never created), members (`memberTarget`), and for what has no source,
  `lowerLibraryCall`: the operations the emitter knows directly (arithmetic, comparisons,
  array and string members, `println`).
- Lambdas that are values become classes with an `invoke` (`makeClosure`). `Captures.kt`
  answers which variables a lambda uses from around it and which of them must live in a
  shared cell because the lambda writes them.

**What each pass may assume of KIR**

| Node | Exists |
|---|---|
| `KThrow`, `KTryRegion` | from the importer until `ExceptionLowering`, which removes them |
| `KAlloc.onStack`, `KArrayNew.onStack`, `KConcat.onStack`, `KStackSlot`, calls to `name__into` | set or added by `EscapeAnalysis` |
| `KRetain`, `KRelease`, `releasesOld` on stores, calls to `name__b` / `name__m` | added by `ArcInsert`; before it no counting exists |
| `KIndexRead.isChecked`, `KIndexSet.isChecked` | set by `ExceptionLowering` when it put the test in front of the statement |

There is no dump of KIR. What a pass produced is read in the generated C, which follows KIR
closely; `--dump-ir` writes the Kotlin IR the importer received (`ir.txt`, `ir.kt.txt`).

**Where a small mistake costs most**

- `ArcInsert`, `Effects`, `BorrowedReturns`, `EscapeAnalysis`: a wrong "this can be borrowed"
  or "this stays in the frame" is a use of freed memory. Test builds overwrite freed objects
  to make that fail; the cases known to be delicate are in `integration/objects/Borrowing.kt`
  and `ThreadBorrowing.kt`.
- `hidden.txt` and `files.txt`: a class hidden too much or too little shows as an unresolved
  or an ambiguous call inside library sources, far from the cause.
- The copy of the stdlib jar without the hidden classes is cached in the system's temporary
  directory (`ktc2/kotlin-stdlib-<key>.jar`), keyed by the jar and the lists; if a compile
  error in library code makes no sense, delete it.
- Options travel from the driver to the compiler plugin as Java system properties
  (`ktc2.outDir`, `ktc2.main`, `ktc2.checks`...): the compiler is not re-entrant, and a
  property left set leaks into the next compilation in the same process, as in the tests.
- The compiler plugin API of Kotlin is experimental and the version is pinned to a release
  candidate (`2.5.0-RC`): an upgrade is a task of its own.
