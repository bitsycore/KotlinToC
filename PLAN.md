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
## Where the work stands (2026-11-12)
---------------------------------------------------------------------------

For whoever picks this up next. `CLAUDE.md` says how to build and test, `DESIGN.md` how it
works; this is what those do not say.

**State.** Everything is on `master`. The suite passes on macOS (Apple clang, ARM64) and on
Windows (mingw gcc 15, x64): 104 programs. AddressSanitizer (`tools/asan.sh`) is clean on the
ones it was run on, on both systems (the showcase, and the tests of counting and of the
frame: `Borrowing`, `LastUse`, `Walking`, `OwnedParameters`, `HeldInFrame`, `Deinit`,
`Cleaners`, `Collections`, `ConcurrentModification`, `DispatchedResults`, `Generics`).

**Windows, 2026-11-12.** What the Mac session wrote without running it works there: the
suite with gcc; ten programs through CMake with MSVC 19.51 (`Walking`, `Deinit`, `Cleaners`,
`HeldInFrame`, `LastUse`, `OwnedParameters`, `Time`, `ConcurrentModification`,
`ThreadBorrowing`, `StringSearch`) and three with clang 23, all printing what gcc's build
prints. That covers the `QueryPerformanceCounter` clock, the MSVC branch of
`ktc2_frame_address` / `KTC2_ALWAYS_INLINE` / `ktc2_seldom`, and the destruction of a long
list. The work of that day after the pull (results through a dispatcher) has in turn not
been run on macOS (its test, `DispatchedResults`, passes with gcc and with MSVC).

**Not run anywhere.** Linux: the POSIX half of the runtime is the one macOS uses, except the
hand-written prototype of `clock_gettime` in `ktc2_ticksNanos`, which strict C11 hides there.

**Decided by the owner on 2026-11-12, and done:**
- the enum that is a number is `@SimpleEnum`, a real C `enum`, with `@EnumValue` on the
  constructor parameter that gives the numbers, and an object per entry where Kotlin needs
  one (`Key?`, `List<Key>`, `Any`); what cannot be a number is a compile error (part 8);
- `deinit` stays an ordinary `override` that calls `super.deinit()`, the Kotlin way; ktc2
  warns when an override does not (part 0);
- the package of the SDL3 module is `ktc2.sdl3` (part 8).

**Waiting for a decision of the owner** (do not build these unasked):
- any addition to the `ktc2.` package: propose it here first. The model is `ktc2.concurrent`
  and `createCleaner`: one API that is plain Kotlin on the JVM and the native thing in C.

**Not run on macOS yet**, all of 2026-11-12 on Windows (the whole suite with gcc, the two new tests also with MSVC): the rooms of a
variable given a new object in a loop, `@SimpleEnum`, and the compiler process that stays
(`Daemon.kt`: it starts a process and talks to it over a local port; the paths of its state
file and the way the process is detached are the parts that depend on the system).

**How the work went, and should go on.** One item at a time: a program under `integration/`
that shows it, the change, the whole suite, `tools/asan.sh` on the programs it touches,
`tools/bench.py --compare`, the documents, a commit. Every optimization of counting or of the
frame got a test that also holds the cases where it must *not* apply (the value that gets out,
the place that is overwritten): those are what caught the mistakes. Three were made and found
that way, and are worth knowing:
- an object created inside a constructor and stored into `this` was put in the constructor's
  own frame: held by an object that was passed in means it outlives the call;
- letting a `var` borrow from a temporary kept `var x = T(); x = null` alive to the end of the
  block;
- borrowing in `node = node.next` when the walk starts from a variable at its last use made
  the benchmark slower: the list was then given up whole at the end. A count removed is not
  always time saved: measure.

---------------------------------------------------------------------------
## Where the benchmarks stand
---------------------------------------------------------------------------

`python tools/bench.py`, Apple clang on an ARM64 Mac, best of five runs. "Before" is commit
`2a16110`, built `-O2`; "now" is the same build (at commit `4773f24`); "now, LTO" is what
`--build` does by default.

| | Before | Now | Now, LTO | Allocations | Retains |
|---|---|---|---|---|---|
| Collections | 0.527 s | 0.290 s | 0.275 s | 4.50 M, unchanged | 11.9 M -> 8.7 M |
| Dispatch | 0.508 s | 0.365 s | 0.211 s | 6.52 M -> 6.50 M | 5.0 M, unchanged |
| Numeric | 0.279 s | 0.264 s | 0.271 s | 44 | 0 |
| Objects | 0.473 s | 0.193 s | 0.155 s | 26.0 M, unchanged | 58.0 M -> 16.0 M |
| Strings | 1.207 s | 0.298 s | 0.260 s | 1.20 M, unchanged | 3.9 M -> 2.7 M |
| Threads | 0.352 s | 0.189 s | 0.129 s | 5.54 M, unchanged | 17.0 M -> 6.2 M |

Programs are also 30 to 45 % smaller with LTO. What these numbers say about what is left:
the number of allocations did not move in the benchmarks (the iterators that left the heap
are few next to the objects the programs make); each one got cheaper instead, since small
objects no longer go through `malloc`. Not making them at all is what part 2 still offers.
Over the test programs allocations went from 320,970 to 303,664. Numeric has nothing left
for these passes: it is the C compiler's.

On Windows (gcc 15, x64, `-O2`, no LTO), before and after results through a dispatcher were
built in the caller's frame. `Ordinary` is the benchmark of ordinary code, added that day.

| | Before | After | Allocations | Retains |
|---|---|---|---|---|
| Collections | 0.460 s | 0.446 s | 4.50 M, unchanged | 8.7 M |
| Dispatch | 0.482 s | 0.467 s | 6.50 M -> 1,014 | 5.0 M |
| Numeric | 0.246 s | 0.241 s | 44 | 0 |
| Objects | 0.235 s | 0.234 s | 26.0 M, unchanged | 16.0 M |
| Ordinary | 0.514 s | 0.509 s | 13.78 M -> 12.56 M | 5.5 M |
| Strings | 0.262 s | 0.260 s | 1.20 M, unchanged | 2.7 M |
| Threads | 0.716 s | 0.707 s | 5.54 M, unchanged | 6.2 M |

The times hardly move: an allocation from the pool costs about as much as clearing a slot
of the frame. What is left in `Ordinary`, by what makes it (counted from the program):
- 4.0 M: `place = place + step`, a new object assigned to a variable of an enclosing block
  on every turn of a loop (part 2, "a variable that is given a new object in a loop");
- 4.0 M: the `Pair`s of `zip`, kept in a list: nothing to gain;
- 2.0 M: the array inside a local `ArrayList` that is itself in the frame (part 2);
- about 2.5 M: the strings and lists of `split` and of a template.

---------------------------------------------------------------------------
## 0. Before the optimizations
---------------------------------------------------------------------------

- [x] **Benchmarks.** `benchmarks/*.kt` and `tools/bench.py` (see "Measuring"); `Ordinary.kt`
  is the one whose objects mostly stay in their function. Not there: a
  comparison with the JVM and with Kotlin/Native on the same programs, and with the first
  transpiler (in the git history before commit `0d1da61`).
- [~] **Linux and macOS.** macOS is done: the whole suite and the SDL3 showcase pass with Apple
  clang on ARM64. What it took: the bindings of `platform.posix` (Apple declares the C library
  in private headers), `-ffp-contract=off` (ARM64 fuses a product and a sum), two test lines
  that printed the last digit of `exp` and `log`, the tool's JVM running out of code cache,
  and a double release that Windows had been hiding (below). Linux is still untried, and
  there is still no CI: a job per system would keep both true. Windows was run again after
  the macOS work (see "Where the work stands").
- [x] **A use of freed memory is reported on every system.** A local that handed its
  reference over and was assigned again later was released twice; the fill of freed objects
  made that pass on Windows. Test builds now keep freed objects aside, filled, and end the
  program on a retain or release of one. AddressSanitizer on the generated C finds the rest
  and is worth a second suite target (`-fsanitize=address`), which is not there yet.
- [x] **`Deinit` is implemented**, as Swift's `deinit` (`DESIGN.md`, section 5). With it:
  `Effects` treats giving up a reference as something that can run the program's code,
  temporaries end with their statement, and `ArrayList` and `HashMap.remove` let go of what is
  removed at once. `createCleaner` is the portable form (Kotlin/Native's API, the JVM's cleaner
  behind it on the JVM); in a private property it is folded into the class and costs no object, and no field either
  when its resource is a `val` of the class.
  Left:
  - [ ] a `HashMap` entry removed through an iterator is held until the map is rebuilt
    (the entry handed out reads the map's storage). Looked at and left: the entry would need a
    copy of its key and value, two retains and releases for every entry of every
    `for ((k, v) in map)`, to cover a rare case.
  - [x] `deinit` through the superclasses: decided against chaining by the compiler. It is
    an `override`, which calls `super.deinit()` itself; ktc2 warns when one that overrides the
    `deinit` of a superclass does not (`checkSuperDeinit` in `KirImport.kt`,
    `integration/values/DeinitSuper.kt` with its `.warnings` file).
  - [ ] a variable could give up its object after its last use, as Swift does in optimized
    builds, instead of at the end of its block: frees earlier, but a guard object
    (`val lock = Guard()`) would then end at once. Not planned unless asked for.

---------------------------------------------------------------------------
## 1. Compile time
---------------------------------------------------------------------------

A small program takes about 13 s in a process of its own, nearly all of it the Kotlin front
end reading the library sources (`KTC2_TIMING=1`), and 3.6 s through the process that stays.

| | Gain | Effort |
|---|---|---|
| [x] A compiler process kept running between compilations (`Daemon.kt`): `ktc2` starts it on first use and hands it each command; it ends after 30 minutes without one, and is replaced when ktc2 or an environment variable it reads changed. `KTC2_DAEMON=0` does without, `ktc2 --stop-daemon` ends it | 12.9 s -> 3.6 s on Windows | medium |
| [ ] Cache the front end's result for the library sources | large if the Kotlin compiler allows it; unknown | high |
| [ ] Hand the compiler only the library files a program can reach | 1-2 s; tried once for the unsigned arrays and dropped as fragile | medium |
| [x] Do not rewrite C files whose content did not change, so the C build is incremental (`CEmitter.write` already did) | faster rebuilds through CMake | low |

---------------------------------------------------------------------------
## 2. Allocation
---------------------------------------------------------------------------

| | Gain | Effort |
|---|---|---|
| [~] `for` over an `ArrayList`, an array or a range as an index loop when the type is known. Arrays and ranges were index loops already. Over a list whose class is known (`Devirtualize`) the iterator is now in the frame and `hasNext` / `next` are direct calls, which LTO inlines. Left: writing the index loop in the importer, which also needs the `modCount` test of part 8 | medium now: one inlined call per element | medium |
| [~] Iterators in the frame when the loop cannot be an index loop. Done where the class of the collection is known (a local, a parameter every caller passes the same class to, a property that only ever receives one class). A parameter that receives several classes (`List<T>` given an `ArrayList` here and a `listOf()` there) is covered by the next line | high where it applies | medium |
| [x] The result of a call through a dispatcher is built in the caller's frame when it stays there: the dispatcher has an `__into` form that hands the caller's room (a `union` of the classes the implementations build) to those that can build in place. With it an argument is no longer "kept" just because the call is dispatched: it is held as the implementations hold it, so a local list handed to a function that takes a `List` stays in the frame (`integration/objects/DispatchedResults.kt`, `KTC2_SKIP=slots`) | Dispatch: 6.5 M allocations -> 1,014; every `for` over a `List`, `Collection` or `Map` parameter | medium |
| [x] A variable that is given a new object on every turn of a loop (`place = place + step`): the function has two rooms for it, and the call builds in the one that holds no object in use (`KStackSlot.rooms`, `ktc2_slot_reuse`); when something else still holds an older object, that one comes from the heap. `integration/objects/Reassigned.kt`, `KTC2_SKIP=rooms` | Ordinary: 12.56 M allocations -> 8.56 M; the time does not move | medium |
| [ ] The array inside an `ArrayList` (or `StringBuilder`, `HashMap`) that is itself in the frame: the constructor stores it into `this`, which was passed in. Needs room in the caller for a first small array, as `ktc2_array_local` has for arrays | 2 M of `Ordinary`; every local list | medium to high |
| [ ] An object that never leaves its function becomes plain locals | medium | medium |
| [x] Closures in the frame when the lambda does not outlive the call it is passed to. The closure was there already; what it captured was not, because storing into a field counted as leaving. An object or the cell of a `var` stored into an object that stays in the frame now stays too, unless some read of that field lets it out | every capturing lambda that is only called: no allocation left | medium |
| [x] Destroying a long structure does not use a frame of the C stack per object: past 96 KB of stack the object is put aside and destroyed later. A list of a million cells crashed before | correctness | low |
| [ ] Small strings inside the header, no block of their own | medium for string-heavy code | medium |
| [x] A size-class allocator in the runtime for small objects: those of a class or a box, up to 128 bytes, come from per-thread lists of freed blocks of their size (`KTC2_NO_POOL` turns it off) | Objects 0.39 s -> 0.19 s; 10 to 15 % elsewhere | low to medium |
| [x] The same for small strings and arrays, whose size is not that of their type: the size is found in the object when it is freed (`pool_size_of`) | Strings -4 % | low |
| [x] A list that is only filled and gone over with `for` stays in the frame: `iterator()` stores the list in the iterator it returns, which is "held by the result" and no longer an escape. The caller follows the local that receives the result | common in ordinary code | low |
| [x] What a constructor or a setter keeps stays in the frame with the object that keeps it (`Keeper(Item())` as a local: neither is allocated). A parameter stored into a field of the object of another parameter is "held" by it, not escaped; the caller decides by where its own object goes | every object built around others | medium |
| [~] Reuse the storage of an object freed just before one of the same class is made. The pool does this already: its lists are last in, first out, so the block just freed is the next one handed out. Left: skipping the pool altogether when the compiler can see the pair, which saves a few instructions | low now | medium |
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
| [x] Owned parameters for constructors and setters: a function that stores a parameter as it is (`this.next = next`) has a form `name__o` that takes the caller's reference over, called where the caller is at its last use of what it passes. A local that is assigned again before anything reads it (`head = Node(i, head)`) counts as at its last use, in a loop too. Direct calls only: not through a dispatcher | Objects: retains -50 % | medium |
| [~] No release for locals known to be null or immortal (literals, statics). Done: a way out of a block skips the locals nothing assigned yet (outside loops). A local that only ever holds literals or statics borrowed already unless it is reassigned; that case is left | smaller code; the C compiler removed most of these itself | low |
| [ ] A "shared" bit per object, so counts are atomic only for objects another thread can reach | high for threaded programs | high; a mistake is a data race. Needs a transitive mark when an object is shared and a barrier on stores into shared objects |
| [x] Merge a retain with a later release of the same object: a local hands its reference over at its last read (`t = T(); init(t); x.f = t`), not only when it is read once | Objects: retains -45 % | medium |
| [x] Borrow up to the last use when the place is overwritten after it (`x.f = g(x.f)`) | part of the same -45 % | medium |
| [x] The same last-use rule for the result of a borrowed call (`BorrowedReturns`): when the place is written later in the block but not before the local's last use, the result is borrowed that long. Not for the `__m` form, whose result may be the caller's own | low to medium | low |
| [x] A `var` local that walks a structure (`node = node.next`) borrows when nothing in the rest of the block writes the fields it follows. Not when it starts from a local at its last use (the walk then consumes the structure, which measured faster than giving it up whole: Objects 0.20 s against 0.25 s) | one retain and release less per step | medium |

---------------------------------------------------------------------------
## 4. Calls and dispatch
---------------------------------------------------------------------------

| | Gain | Effort |
|---|---|---|
| [x] Call the implementation directly when only one class implements the method, or the type is known at the call (`Devirtualize`: the class is followed from the allocation through locals, parameters, fields and results) | Dispatch -17 %, -48 % with LTO; 375 calls in the showcase | medium |
| [ ] Follow the other arguments through dispatchers too, and the element class of a collection (`List<Shape>` that only holds circles) | medium | medium |
| [ ] Stop sharing the code of a local class between the instantiations of the generic class around it (the importer reads an `X<Int>` through the type `X<Any>`): `Devirtualize` has to distrust static types wherever it sees this, and so will every later pass that reasons about classes | removes a trap more than it gains speed | medium, in the importer |
| [x] Fold `is` and `as` when the class is known: 20 tests in the showcase | low | low |
| [ ] Small generated functions as `static inline` in the headers | medium: inlining across files needs LTO today | low |
| [ ] Instantiate generic classes for hot reference types too, not only for numbers | medium: no casts, more direct calls | high, more code |
| [x] Dispatcher cases ordered by how often each class is created: not needed. A dispatcher is a `switch` on the type id, which the C compiler turns into a table or a search of its own; the order of the cases changes nothing | none | - |

---------------------------------------------------------------------------
## 5. Exceptions and checks
---------------------------------------------------------------------------

| | Gain | Effort |
|---|---|---|
| [x] A whole-program "cannot throw" answer per function, so calls to such functions are not followed by a test (`ExceptionLowering.mMayThrow` was there; direct calls and proven array accesses now make more functions qualify) | medium to high | medium |
| [x] The same answer for the runtime's functions and for dispatchers' defaults. Checked one by one: three could leave with an exception and were trusted not to (`ktc2_cs_get`, `ktc2_cs_subSequence`, `ktc2_stringSubstring`), so a bad index on a `String` seen as a `CharSequence` was not caught, and `substring` with a bad range ended the program. They are listed in `kRaisingRuntime` (`Kir.kt`); the others end the program on purpose (`AbstractMethodError`, a cast that cannot happen) | correctness | low |
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
| [x] Link-time optimization by default for `--build` without `--debug`, and through CMake where the compiler has it (`KTC2_LTO=0`, `-DKTC2_LTO=OFF`). With clang on Windows it needs LLVM's linker: used when `lld-link` is on the path (`-fuse-ld=lld`), Objects 0.27 s -> 0.21 s there | 10 to 48 % faster, 30 to 45 % smaller | low |
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

- [x] **`ConcurrentModificationException`.** `ArrayList` and `HashMap` (and what is built on
  it: `LinkedHashMap`, the sets) count their changes, and their iterators fail as on the JVM,
  the cases the JVM does not notice included, and `sort()` counts as a change. One compare per
  `next()`. Left: the lists built on `AbstractMutableList` do not check.
- [ ] **The last digit of `exp`, `ln`, `pow`, `sin`.** It is the C library's. To print what
  the JVM prints on every system, the runtime would carry fdlibm's versions (what
  `StrictMath` is); a few hundred lines of C. Until then DESIGN.md lists it as a difference.
- [x] **An enum that is a number: `@SimpleEnum`** (`DESIGN.md`, section 4;
  `integration/values/SimpleEnums.kt`, `integration/errors/SimpleEnumState.kt`). A C `enum`;
  `@EnumValue` on an `Int` parameter of the constructor gives the numbers. Left:
  - [ ] `name`, `ordinal`, `toString()` and `compareTo` of a value go through the object kept
    for its entry (a chain of compares, then a field): right, not as cheap as a table. A
    lookup of their own, as the constructor's properties have, is a few lines in the call
    lowering.
  - [ ] the SDL3 module's `object { const val ... }` namespaces (scancodes, event types) as
    `@SimpleEnum` classes with the C constants as `@EnumValue`, so the compiler checks them.
    A value given to a C function still has to be converted by hand (`.code`): the bindings
    could accept the enum where C declares the `enum`.
  - [ ] flags that are or-ed together are not an enum: a `value class` over the number with
    the constants in its companion does that today and costs nothing.
- [x] **A failing `valueOf` throws** `IllegalArgumentException("No enum constant ...")`, for
  every enum: it ended the program before.
- [ ] **Coroutines.** `sequence { }` and `iterator { }` first: they are what the library
  itself uses (`chunked` and `windowed` on things that are not lists fail at run time today).
- [ ] **Android.** The C should compile with the NDK's clang. Missing: `println` to the log
  and not to a console; a program as a shared library entered from Java and not a `main`; a
  clock that works below API 29; the build driven by the NDK's CMake toolchain file.
- [ ] **MSVC without gcc.** The bindings generator runs gcc or clang to read headers. With
  `cl /E` it would need neither.
- [ ] **Paths and arguments on Windows** are taken as bytes: right for ASCII only. Use the
  wide-character functions.
- [x] **A monotonic clock** behind `kotlin.time` and `measureTimeMillis`
  (`ktc2_ticksNanos`: `QueryPerformanceCounter`, `clock_gettime(CLOCK_MONOTONIC)`). The
  Windows and Linux branches are written and not yet run.
- [ ] `Clock` and `Instant`; the `U` flag of `Regex`; case of characters beyond U+FFFF.
- [ ] **A `.klib`-like way to ship a module.** Today a module is its sources (a directory or a
  git repository). The Kotlin compiler ktc2 runs is the JVM one and cannot read klibs, and
  published klibs are built against Kotlin/Native's own library, so this means either a
  format of ktc2's own or moving the front end; not small.
- [x] **The package of the SDL3 module's Kotlin API is `ktc2.sdl3`** (it was `ktc.sdl3`, a
  name left from the first transpiler).
- [x] **A test of a warning.** A program with a `<Name>.warnings` file has to be accepted
  with a message that contains each line of that file.
- [x] **A dump of KIR**, after the importer and after each pass: `--dump-ir` writes
  `kir/0-import.txt` to `kir/4-arcinsert.txt` (`KirDump`), the same functions in the same
  order, so `diff` of two of them shows what a pass did.
- [x] **A test that must fail to compile.** A program with a `<Name>.errors` file next to it
  has to be refused, with a message that contains each line of that file
  (`integration/errors/`). Two so far: a use of `java.*`, and `Deinit` on a `@Struct`. The
  other refusals (inline storage that would need a copy, `@Value` that cannot stay in the
  frame) still want one each.

---------------------------------------------------------------------------
## What I would do first
---------------------------------------------------------------------------

1. Run the suite on macOS again (the work on Windows of 2026-11-12 was not run there), and
   on Linux for the first time. Then a CI job per system with an AddressSanitizer run.
2. Where the time of `Ordinary` is, now that allocation is not it (measured by timing its
   parts, with LTO): the library's operators on lists (`filter`, `zip`, `sumOf`: a third),
   a `for` over a `List` parameter (two dispatched calls per element: a quarter), a local
   `ArrayList` (its array, `add`, the checks after each call: a fifth), text (`split`, a
   template: a sixth). So: the index loop over lists and direct calls where a parameter only
   ever receives one class per call site (4), then templates built in place (6).
3. More shapes for `RangeChecks` (5).
4. `name` and `ordinal` of a `@SimpleEnum` without the object; the SDL3 constants as such
   enums (8).
5. The array inside a local list (2): 2 M allocations of `Ordinary`, but removing 4 M others
   did not change its time; measure before building it.

---------------------------------------------------------------------------
## Measuring
---------------------------------------------------------------------------

- `python tools/bench.py [name part...] [--runs N] [--copt option] [--save file] [--compare file]`
  translates `benchmarks/*.kt` with the installed tool (`./gradlew installDist` first), builds
  each at `-O2`, and prints the best time, the allocations, retains and releases, and the size
  of the program; the table goes to `build/bench/results.txt`. `--compare` shows the change
  against a table saved before; `KTC2_TOOL` names an installed tool of another state of the
  sources, to measure both on the same day. Do not run it while the suite runs.
- `KTC2_SKIP=devirtualize,ranges,handover,brief,walk,owned,slots,rooms` leaves optimizations out, one or several, to
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
