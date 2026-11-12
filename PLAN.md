# ktc2 - plan

What is left to do, in the order I would do it within each part. `DESIGN.md` describes what
exists; `HISTORY.md` is the record of how it got there.

Gain and effort are estimates from the code and from the counts measured so far. Nothing here
has been prototyped unless it says so.

How to work on an item: write a program under `integration/<group>/` that shows it (it must
print the same on the JVM), make the change, run `./gradlew test --rerun`, commit when green.
For anything about speed, measure before and after; see "Measuring" at the end.

`[x]` is done, `[~]` is done in part: what is left is said next to it.

---------------------------------------------------------------------------
## Where the benchmarks stand
---------------------------------------------------------------------------

`python tools/bench.py`, Apple clang on an ARM64 Mac, best of five runs. "Before" is commit
`2a16110`, built `-O2`; "now" is the same build; "now, LTO" is what `--build` does by default.

| | Before | Now | Now, LTO | Allocations | Retains |
|---|---|---|---|---|---|
| Collections | 0.527 s | 0.344 s | 0.258 s | 4.50 M, unchanged | 11.9 M -> 8.8 M |
| Dispatch | 0.508 s | 0.371 s | 0.205 s | 6.52 M -> 6.50 M | 5.0 M, unchanged |
| Numeric | 0.279 s | 0.278 s | 0.268 s | 44 | 0 |
| Objects | 0.473 s | 0.194 s | 0.146 s | 26.0 M, unchanged | 58.0 M -> 32.0 M |
| Strings | 1.207 s | 0.319 s | 0.275 s | 1.20 M, unchanged | 3.9 M, unchanged |
| Threads | 0.352 s | 0.263 s | 0.136 s | 5.54 M, unchanged | 17.0 M -> 11.7 M |

Programs are also 30 to 45 % smaller with LTO. What these numbers say about what is left:
the number of allocations did not move in the benchmarks (the iterators that left the heap
are few next to the objects the programs make); each one got cheaper instead, since small
objects no longer go through `malloc`. Not making them at all is what part 2 still offers.
Over the test programs allocations went from 320,970 to 303,664. Numeric has nothing left
for these passes: it is the C compiler's.

---------------------------------------------------------------------------
## 0. Before the optimizations
---------------------------------------------------------------------------

- [x] **Benchmarks.** `benchmarks/*.kt` and `tools/bench.py` (see "Measuring"). Not there: a
  comparison with the JVM and with Kotlin/Native on the same programs, and with the first
  transpiler (in the git history before commit `0d1da61`).
- [~] **Linux and macOS.** macOS is done: the whole suite and the SDL3 showcase pass with Apple
  clang on ARM64. What it took: the bindings of `platform.posix` (Apple declares the C library
  in private headers), `-ffp-contract=off` (ARM64 fuses a product and a sum), two test lines
  that printed the last digit of `exp` and `log`, the tool's JVM running out of code cache,
  and a double release that Windows had been hiding (below). Linux is still untried, and
  there is still no CI: a job per system would keep both true.
- [x] **A use of freed memory is reported on every system.** A local that handed its
  reference over and was assigned again later was released twice; the fill of freed objects
  made that pass on Windows. Test builds now keep freed objects aside, filled, and end the
  program on a retain or release of one. AddressSanitizer on the generated C finds the rest
  and is worth a second suite target (`-fsanitize=address`), which is not there yet.
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
| [x] Do not rewrite C files whose content did not change, so the C build is incremental (`CEmitter.write` already did) | faster rebuilds through CMake | low |

---------------------------------------------------------------------------
## 2. Allocation
---------------------------------------------------------------------------

| | Gain | Effort |
|---|---|---|
| [~] `for` over an `ArrayList`, an array or a range as an index loop when the type is known. Arrays and ranges were index loops already. Over a list whose class is known (`Devirtualize`) the iterator is now in the frame and `hasNext` / `next` are direct calls, which LTO inlines. Left: writing the index loop in the importer, which also needs the `modCount` test of part 8 | medium now: one inlined call per element | medium |
| [~] Iterators in the frame when the loop cannot be an index loop. Done where the class of the collection is known (a local, a parameter every caller passes the same class to, a property that only ever receives one class). Left: a parameter that receives several classes (`List<T>` given an `ArrayList` here and a `listOf()` there): needs a copy of the function per class, or the iterator built in a slot the dispatcher is handed | high where it applies | medium |
| [ ] An object that never leaves its function becomes plain locals | medium | medium |
| [ ] Closures in the frame when the lambda does not outlive the call it is passed to | medium | medium |
| [ ] Small strings inside the header, no block of their own | medium for string-heavy code | medium |
| [x] A size-class allocator in the runtime for small objects: those of a class or a box, up to 128 bytes, come from per-thread lists of freed blocks of their size (`KTC2_NO_POOL` turns it off) | Objects 0.39 s -> 0.19 s; 10 to 15 % elsewhere | low to medium |
| [ ] The same for small strings and arrays, whose size is not that of their type: the size has to be found in the object when it is freed | medium for string-heavy code | low |
| [ ] Reuse the storage of an object freed just before one of the same class is made | medium in loops of short-lived objects | medium |
| [x] A cache of boxes for small integers: -128..127 as `Int` and `Long`, characters below 128, the Booleans, static and immortal as on the JVM | low to medium | low |
| [ ] Escape analysis through dispatchers, not only direct calls | medium: more objects stay in the frame | high |

---------------------------------------------------------------------------
## 3. Reference counting
---------------------------------------------------------------------------

Where it stands: 1.90 million retains for 305,000 allocations over the test programs (the
Unicode test alone is 1.36 million of them); the benchmarks are in the table at the top. Of
the calls whose result goes into a local, 172 of 729 borrowed when last counted; 450 return
an object of their own; 62 pass an argument that is itself a temporary; 43 are followed by a
write to an array of references.

| | Gain | Effort |
|---|---|---|
| [ ] Tell arrays apart in `Effects`, so a loop that adds to one list can borrow from another | unblocks those 43 loops | high |
| [ ] Owned parameters for constructors and setters: the caller hands its count over | medium: common when objects are built | medium |
| [ ] No release for locals known to be null or immortal (literals, statics) | low: removes many calls that do nothing | low |
| [ ] A "shared" bit per object, so counts are atomic only for objects another thread can reach | high for threaded programs | high; a mistake is a data race. Needs a transitive mark when an object is shared and a barrier on stores into shared objects |
| [x] Merge a retain with a later release of the same object: a local hands its reference over at its last read (`t = T(); init(t); x.f = t`), not only when it is read once | Objects: retains -45 % | medium |
| [x] Borrow up to the last use when the place is overwritten after it (`x.f = g(x.f)`) | part of the same -45 % | medium |
| [ ] The same last-use rule for the result of a borrowed call (`BorrowedReturns`): today it needs the whole rest of the block to leave the place alone | low to medium | low |
| [ ] A `var` local that walks a structure (`node = node.next`) borrows when nothing in the loop writes the fields it follows | medium for linked structures: one retain and release per step today | medium |

---------------------------------------------------------------------------
## 4. Calls and dispatch
---------------------------------------------------------------------------

| | Gain | Effort |
|---|---|---|
| [x] Call the implementation directly when only one class implements the method, or the type is known at the call (`Devirtualize`: the class is followed from the allocation through locals, parameters, fields and results) | Dispatch -17 %, -48 % with LTO; 375 calls in the showcase | medium |
| [ ] Follow the other arguments through dispatchers too, and the element class of a collection (`List<Shape>` that only holds circles) | medium | medium |
| [ ] Stop sharing the code of a local class between the instantiations of the generic class around it (the importer reads an `X<Int>` through the type `X<Any>`): `Devirtualize` has to distrust static types wherever it sees this, and so will every later pass that reasons about classes | removes a trap more than it gains speed | medium, in the importer |
| [ ] Fold `is` and `as` when the class is known | low | low |
| [ ] Small generated functions as `static inline` in the headers | medium: inlining across files needs LTO today | low |
| [ ] Instantiate generic classes for hot reference types too, not only for numbers | medium: no casts, more direct calls | high, more code |
| [ ] Dispatcher cases ordered by how often each class is created | low | low |

---------------------------------------------------------------------------
## 5. Exceptions and checks
---------------------------------------------------------------------------

| | Gain | Effort |
|---|---|---|
| [x] A whole-program "cannot throw" answer per function, so calls to such functions are not followed by a test (`ExceptionLowering.mMayThrow` was there; direct calls and proven array accesses now make more functions qualify) | medium to high | medium |
| [ ] The same answer for the runtime's functions and for dispatchers' defaults, one by one: a call to an `external` function that can park an exception is trusted not to today | correctness more than speed: check each | low |
| [ ] The failure in the return value, not in a thread-local slot | medium in threaded programs | high: changes how every function is called |
| [~] No bounds check in a loop whose index is known to be in range (`RangeChecks`): the loops over an array, its `indices`, `0 until size` and `Array(n) { }`. With clang at `-O2` the time does not change (it removed those tests itself); the code is smaller and such functions no longer count as throwing. Left: `while (i < a.size)`, `i + 1` under `i < size - 1`, a `downTo` loop, `list[i]` under `i < list.size`, strings | medium for array code | medium |
| [x] An array store is tested once: `ArcInsert` dropped the "already tested" mark, so every store was tested by the generated code and again by the runtime | small, everywhere | low |
| [ ] The first-use test of an object or a file taken out of loops | low | low |

---------------------------------------------------------------------------
## 6. Strings and the library
---------------------------------------------------------------------------

| | Gain | Effort |
|---|---|---|
| [x] Remember the last index and byte position in a non-ASCII string, so `s[i]` in a loop is not a scan each time (per thread; forgotten when a non-ASCII string is destroyed) | high for loops over such text | low |
| [ ] Build a template straight into where it goes, without strings in between | medium | medium |
| [~] Platform versions of hot library paths. Done: `indexOf`, `lastIndexOf`, `regionMatches` (so `contains`, `split`, `startsWith`, `endsWith`, `replace`) on ASCII strings work on bytes in the runtime: Strings -75 %. Left: the same for non-ASCII strings (UTF-8 can be searched as bytes when neither string holds a lone surrogate), `HashMap` probing, `StringBuilder.append`, sorting, `String.hashCode` kept in the string | medium | medium, one at a time |
| [ ] `Regex`: keep the match state between searches; a literal start of the pattern as a `memchr` | medium for Regex-heavy code | medium |

---------------------------------------------------------------------------
## 7. Code size and the C build
---------------------------------------------------------------------------

| | Gain | Effort |
|---|---|---|
| [x] Link-time optimization by default for `--build` without `--debug`, and through CMake where the compiler has it (`KTC2_LTO=0`, `-DKTC2_LTO=OFF`). Not with clang on Windows, which needs lld: to be tried there | 10 to 48 % faster, 30 to 45 % smaller | low |
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

- [ ] **`ConcurrentModificationException`.** Never thrown: `ArrayList` and `HashMap` have no
  modification count, so a list changed while it is iterated goes on. A difference from the
  JVM, found while testing the iterators. One compare per `next()`.
- [ ] **The last digit of `exp`, `ln`, `pow`, `sin`.** It is the C library's. To print what
  the JVM prints on every system, the runtime would carry fdlibm's versions (what
  `StrictMath` is); a few hundred lines of C. Until then DESIGN.md lists it as a difference.
- [ ] **An enum that is a number.** Proposed, to be decided: `@ktc2.Flat enum class Key(val
  code: Int)` (the name is open) whose values are their ordinal in C, with no objects: `when`
  is a `switch`, `name` and the properties come from constant tables, and a value is boxed
  only where it is seen as `Any` or `Enum<T>`. On the JVM it is a plain enum, so it follows
  the second rule. With a `code` taken from a C constant it would replace the
  `object { const val ... }` namespaces of the SDL3 module by types the compiler checks.
  For flags that are or-ed together, a `value class` over the number with the constants in
  its companion does the same today and costs nothing.
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

1. Objects that never leave their function as plain locals, and closures in the frame (2):
   the number of allocations is what did not move.
2. Owned parameters for constructors and setters (3): an argument stored by the callee and
   released by the caller is a retain and a release that cancel. To be counted first
   (`tools/retain_profile.sh`) on Objects and Collections.
3. Iterators for parameters that receive several classes, and the index loop over lists (2).
4. Linux, and a CI job per system with an AddressSanitizer run (0).
5. `ConcurrentModificationException` (8): the one known place where a program that runs
   differs from the JVM in what it does.
6. The compiler kept running (1): the biggest change to how ktc2 feels.
7. Decide `Deinit` (0) and the enum that is a number (8).

---------------------------------------------------------------------------
## Measuring
---------------------------------------------------------------------------

- `python tools/bench.py [name part...] [--runs N] [--copt option] [--save file] [--compare file]`
  translates `benchmarks/*.kt` with the installed tool (`./gradlew installDist` first), builds
  each at `-O2`, and prints the best time, the allocations, retains and releases, and the size
  of the program; the table goes to `build/bench/results.txt`. `--compare` shows the change
  against a table saved before; `KTC2_TOOL` names an installed tool of another state of the
  sources, to measure both on the same day. Do not run it while the suite runs.
- `KTC2_SKIP=devirtualize,ranges,handover,brief` leaves optimizations out, one or several, to
  measure what each gives or to find which one a wrong program comes from;
  `KTC2_DEBUG_PASSES=1` prints how many calls were made direct and how many array accesses
  were proven in range.
- `build/integration/arc-stats.txt` holds allocations, retains and releases of every test
  program after a run of the suite; `python tools/arc_compare.py before.txt after.txt`
  compares two runs. Copy the file before a change.
- `tools/retain_profile.sh <group/Name>` lists the lines of one program that retain most.
- `KTC2_DEBUG_BORROW=1` prints why a function's result cannot be borrowed and why a caller did
  not borrow.
- `--warn-heap` lists what stayed on the heap and why.
- `KTC2_TIMING=1` prints where compile time goes.
