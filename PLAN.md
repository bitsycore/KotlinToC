# ktc2 - plan

What is left to do, in the order I would do it within each part. `DESIGN.md` describes what
exists; `HISTORY.md` is the record of how it got there.

Gain and effort are estimates from the code and from the counts measured so far. Nothing here
has been prototyped unless it says so.

How to work on an item: write a program under `integration/<group>/` that shows it (it must
print the same on the JVM), make the change, run `./gradlew test --rerun`, commit when green.
For anything about speed, measure before and after; see "Measuring" at the end.

---------------------------------------------------------------------------
## 0. Before the optimizations
---------------------------------------------------------------------------

- [ ] **Benchmarks.** There are none. A handful of fixed programs (object-heavy, collection
  loops, string building, numeric, threads) with a script that builds them at `-O2` and
  reports time and the allocation and count figures. Without them every item below is a guess.
  The first transpiler is in the git history (before commit `0d1da61`) if a comparison with it
  is wanted.
- [ ] **Linux and macOS.** Never compiled there. The POSIX half of `ktc2_core.c` (threads,
  monitors, sleeping, the clock) has only been read. Run the suite on both; a CI job would
  keep it true.
- [ ] **`Deinit` is not implemented.** `ktc2.Deinit` is declared and documented as called when
  the last reference goes, but the C build never calls `deinit()`. Either call it from the
  generated `destroy` of classes that implement it (and then treat a release as something
  that can run the program's code, in `Effects`), or remove the interface.

---------------------------------------------------------------------------
## 1. Compile time
---------------------------------------------------------------------------

A small program takes about 11 s, nearly all of it the Kotlin front end reading the library
sources (`KTC2_TIMING=1`).

| | Gain | Effort |
|---|---|---|
| [ ] A compiler process kept running between compilations | 11 s -> probably 2-3 s | medium |
| [ ] Cache the front end's result for the library sources | large if the Kotlin compiler allows it; unknown | high |
| [ ] Hand the compiler only the library files a program can reach | 1-2 s; tried once for the unsigned arrays and dropped as fragile | medium |
| [ ] Do not rewrite C files whose content did not change, so the C build is incremental | faster rebuilds through CMake | low |

---------------------------------------------------------------------------
## 2. Allocation
---------------------------------------------------------------------------

| | Gain | Effort |
|---|---|---|
| [ ] `for` over an `ArrayList`, an array or a range as an index loop when the type is known | high: no iterator, no dispatched `hasNext` / `next` per element | medium |
| [ ] Iterators in the frame when the loop cannot be an index loop | high: every collection loop allocates one today | medium |
| [ ] An object that never leaves its function becomes plain locals | medium | medium |
| [ ] Closures in the frame when the lambda does not outlive the call it is passed to | medium | medium |
| [ ] Small strings inside the header, no block of their own | medium for string-heavy code | medium |
| [ ] A size-class allocator in the runtime for small objects | medium | low to medium |
| [ ] Reuse the storage of an object freed just before one of the same class is made | medium in loops of short-lived objects | medium |
| [ ] A cache of boxes for small integers | low to medium | low |
| [ ] Escape analysis through dispatchers, not only direct calls | medium: more objects stay in the frame | high |

---------------------------------------------------------------------------
## 3. Reference counting
---------------------------------------------------------------------------

Where it stands: about 15,500 retains for 9,900 allocations over the test programs. Of the
calls whose result goes into a local, 172 of 729 borrow; 450 return an object of their own;
62 pass an argument that is itself a temporary; 43 are followed by a write to an array of
references.

| | Gain | Effort |
|---|---|---|
| [ ] Tell arrays apart in `Effects`, so a loop that adds to one list can borrow from another | unblocks those 43 loops | high |
| [ ] Owned parameters for constructors and setters: the caller hands its count over | medium: common when objects are built | medium |
| [ ] No release for locals known to be null or immortal (literals, statics) | low: removes many calls that do nothing | low |
| [ ] A "shared" bit per object, so counts are atomic only for objects another thread can reach | high for threaded programs | high; a mistake is a data race. Needs a transitive mark when an object is shared and a barrier on stores into shared objects |
| [ ] Merge a retain with a later release of the same object across blocks | low to medium | medium |

---------------------------------------------------------------------------
## 4. Calls and dispatch
---------------------------------------------------------------------------

| | Gain | Effort |
|---|---|---|
| [ ] Call the implementation directly when only one class implements the method, or the type is known at the call | high: the C compiler can then inline it | medium |
| [ ] Small generated functions as `static inline` in the headers | medium: inlining across files needs LTO today | low |
| [ ] Instantiate generic classes for hot reference types too, not only for numbers | medium: no casts, more direct calls | high, more code |
| [ ] Dispatcher cases ordered by how often each class is created | low | low |

---------------------------------------------------------------------------
## 5. Exceptions and checks
---------------------------------------------------------------------------

| | Gain | Effort |
|---|---|---|
| [ ] A whole-program "cannot throw" answer per function, so calls to such functions are not followed by a test | medium to high | medium. `ExceptionLowering.callThrows` is where it goes |
| [ ] The failure in the return value, not in a thread-local slot | medium in threaded programs | high: changes how every function is called |
| [ ] No bounds check in a loop whose index is known to be in range | medium for array code | medium |
| [ ] The first-use test of an object or a file taken out of loops | low | low |

---------------------------------------------------------------------------
## 6. Strings and the library
---------------------------------------------------------------------------

| | Gain | Effort |
|---|---|---|
| [ ] Remember the last index and byte position in a non-ASCII string, so `s[i]` in a loop is not a scan each time | high for loops over such text | low |
| [ ] Build a template straight into where it goes, without strings in between | medium | medium |
| [ ] Platform versions of hot library paths (`HashMap` probing, `StringBuilder.append`, sorting) | medium | medium, one at a time |
| [ ] `Regex`: keep the match state between searches; a literal start of the pattern as a `memchr` | medium for Regex-heavy code | medium |

---------------------------------------------------------------------------
## 7. Code size and the C build
---------------------------------------------------------------------------

| | Gain | Effort |
|---|---|---|
| [ ] Link-time optimization by default for `--build` without `--debug` | free where the compiler has it; tried by hand with gcc (`-DCMAKE_INTERPROCEDURAL_OPTIMIZATION=ON`) and it works | low |
| [ ] One C file per package, not per class | faster C builds, inlining without LTO | low to medium |
| [ ] One implementation for instantiations that compile to the same code | smaller programs | medium |

---------------------------------------------------------------------------
## 8. Features
---------------------------------------------------------------------------

Decided, not started:

- [ ] **Files, through kotlinx.io or okio.** Not an API of ktc2's own: compile the library's
  common sources and write the platform part for ktc2, as was done for the standard library.
  Later the same for kotlinx.serialization and other kotlinx libraries. Nothing of `java.*`.
- [ ] **Unit tests of the passes.** The ones of the first transpiler were thrown away on
  purpose. To be written fresh, on KIR: `EscapeAnalysis`, `ArcInsert`, `Effects`,
  `BorrowedReturns` are where a small wrong answer costs most.

Open:

- [ ] **Coroutines.** `sequence { }` and `iterator { }` first: they are what the library
  itself uses (`chunked` and `windowed` on things that are not lists fail at run time today).
- [ ] **Android.** The C should compile with the NDK's clang. Missing: `println` to the log
  and not to a console; a program as a shared library entered from Java and not a `main`; a
  clock that works below API 29; the build driven by the NDK's CMake toolchain file.
- [ ] **MSVC without gcc.** The bindings generator runs gcc or clang to read headers. With
  `cl /E` it would need neither.
- [ ] **Paths and arguments on Windows** are taken as bytes: right for ASCII only. Use the
  wide-character functions.
- [ ] **A monotonic clock** behind `kotlin.time`.
- [ ] `Clock` and `Instant`; the `U` flag of `Regex`; case of characters beyond U+FFFF.
- [ ] **A `.klib`-like way to ship a module.** Today a module is its sources (a directory or a
  git repository). The Kotlin compiler ktc2 runs is the JVM one and cannot read klibs, and
  published klibs are built against Kotlin/Native's own library, so this means either a
  format of ktc2's own or moving the front end; not small.
- [ ] **The package of the SDL3 module's Kotlin API is `ktc.sdl3`**, a name left from the
  first transpiler. Rename it (to what is the question: `ktc2.` is kept for what changes
  storage only), with the two examples that import it.
- [ ] **A dump of KIR**, before and after each pass: today a pass's result is only visible in
  the generated C.
- [ ] **A test that must fail to compile.** The harness cannot assert an error, so
  `JavaUseCheck` and the other refusals are only checked by hand.

---------------------------------------------------------------------------
## What I would do first
---------------------------------------------------------------------------

1. Benchmarks (0), since everything else is measured against them.
2. Linux and macOS (0): cheap to find out, and it decides how portable the claim is.
3. Index loops and iterators in the frame (2): the largest cost in ordinary code.
4. Direct calls where the type is known (4): cheap for what it gives.
5. "Cannot throw" (5): removes a test after a large share of calls.
6. The compiler kept running (1): the biggest change to how ktc2 feels.

---------------------------------------------------------------------------
## Measuring
---------------------------------------------------------------------------

- `build/integration/arc-stats.txt` holds allocations, retains and releases of every test
  program after a run of the suite; `python tools/arc_compare.py before.txt after.txt`
  compares two runs. Copy the file before a change.
- `tools/retain_profile.sh <group/Name>` lists the lines of one program that retain most.
- `KTC2_DEBUG_BORROW=1` prints why a function's result cannot be borrowed and why a caller did
  not borrow.
- `--warn-heap` lists what stayed on the heap and why.
- `KTC2_TIMING=1` prints where compile time goes.
