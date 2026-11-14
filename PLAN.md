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
## Where the work stands (2026-11-14)
---------------------------------------------------------------------------

For whoever picks this up next, on any machine. `CLAUDE.md` says how to build and test,
`DESIGN.md` how it works, `tests/README.md` how the common tests work; this is what those do
not say. Start with `git pull`, then `./gradlew test --rerun`.

**State.** Everything is on `master` and pushed; the working tree is clean. The suite is 301
tests: 143 programs under `integration/` (compared with the JVM or an `.expected` file, or
that must be refused), 157 files of common tests under `tests/` (one of ktc2's own and 156 of
the Kotlin standard library's, some 2230 `@Test` functions, with the helpers of the library's
tests under `tests/support/`), and the showcase as a program of several modules. It passes
on Windows (mingw gcc 15) in about five minutes (the library's large test files, `ArraysTest`
and `CollectionTest`, take most of it), on Linux (Debian in WSL2, gcc 14) and, by the CI job,
on macOS. AddressSanitizer is clean on every program on
Linux (the CI job's last step, `tools/asan_all.sh`).

**The CI job** (`.github/workflows/test.yml`, on every push; see it with
`gh run list --repo bitsycore/KotlinToC`) runs the suite on Linux, macOS and Windows:
- the suite passes on all three since `c556bea` (2026-11-13, late). What Linux needed: the
  GNU C library declares `FILE` under a private name first (`__FILE`), and the bindings kept
  the first name of a type; and `stdin`, `stdout`, `stderr` are variables there, which met
  the ones written by hand for the systems where they are macros (`interop/Geometry` now has
  both shapes, so every system tests them);
- the AddressSanitizer step on Linux ran for the first time on that commit and found two
  things, fixed in the commit after it: a thread nobody joins left the block of its handle
  (Linux's sanitizer looks for leaks, macOS's does not; the handle is now the `pthread_t`
  itself), and `tools/asan_all.sh` took a note of gcc's for a failure. Look at the run of the
  latest commit to see that step green;
- Linux can also be run on the Windows machine: it has WSL2 with Debian, gcc, git and CMake
  (`wsl -e bash -lc "cd /mnt/c/Dev/KotlinToC && ./gradlew test --rerun"`; about 4 minutes
  from the Windows checkout, whose `build/` it shares, so never at the same time as a run on
  Windows). `.gitattributes` keeps the shell scripts with Unix line ends for that;
- MSVC is in the job since 2026-11-13: on Windows, after the suite, `tools/msvc_all.sh`
  compiles the one C file of every program (`ktc2_all.c`) with MSVC and compares what it
  prints with gcc's build. Run by hand that day: 166 of 168 programs the same, the two others
  left out with their reason in `tools/msvc_known.txt` (the last digit of `pow` in MSVC's C
  library, and a test whose own C uses a gcc attribute).

**What that day did**, in the order of `git log 3a04274..`: speed (text that is not ASCII
searched as bytes, `ArrayList` sorted in its storage, integers as text without `printf`,
`get`, `set` and `size` of an `ArrayList` written out, short functions in the headers), then
correctness, which is where the work is now. The benchmarks did not move after the speed
work (table below).

**The owner's rule** (2026-11-13): ktc2 does what Kotlin does on the JVM and on
Kotlin/Native, everywhere, and is made fast on top of that. The only place something may be
limited or different is behind an annotation of the `ktc2.` package (`@Struct`, `@Value`...),
which is the program saying it accepts that in exchange for storage closer to the machine. A
difference found in plain Kotlin is fixed in general, not written down as a gap with the
common case covered. Where the JVM and Kotlin/Native themselves differ because of their
platform, either is accepted, and Kotlin/Native is the one to look at first.

**Decided by the owner** (all on 2026-11-13 unless said):
- `ktc2.Deinit` is removed: what runs when an object ends is a cleaner (`createCleaner`),
  which the JVM and Kotlin/Native have too, and which costs nothing in a private property.
  The runtime's hook is still there under it (`ktc2.internal.Deinit`, for the cleaner that is
  kept as an object);
- coroutines are `suspend` functions done in full, by the transformation into a state
  machine, not a stack of their own for each coroutine (done 2026-11-14: `DESIGN.md`,
  "Coroutines"). The library's own functions written with `sequence { }` (`zipWithNext`,
  `chunked`, `windowed`, `scan`, `shuffled` of a sequence...) stay plain iterators of the
  platform layer: measured, they are a quarter faster than the coroutine;
- a class as a value is in: `x::class`, `Name::class`, compared, as keys, `simpleName`,
  `qualifiedName`, `isInstance`, `cast` for a class, and `typeOf<T>()` (2026-11-14), which is
  what Kotlin/Native has. The rest of reflection is not;
- what only the JDK has is not added (`toSortedMap`, `String.format`, `getOrDefault`,
  `setCharAt`...): a program has no JDK, as on Kotlin/Native. A compatibility layer in the
  `ktc2.` package may come later. `removeFirst` and `removeLast` of a list stay: they are
  Kotlin's own extensions, which a program compiled for the JVM reaches as the members Java 21
  gave lists;
- the standard exceptions have their Kotlin names (`kotlin.IllegalStateException`), as on
  Kotlin/Native; the suite takes that or the JVM's `java.lang.` name when it compares;
- tests are also written as Kotlin Multiplatform's common tests are (`tests/`), and the
  tests of the Kotlin standard library itself are taken as they are (`tests/kotlin/`);
- the enum that is a number is `@SimpleEnum`, with `@EnumValue` and `ktc2.enumOf`; the
  package of the SDL3 module is `ktc2.sdl3`; nothing of `java.*` for programs; files and the
  like through kotlinx.io or okio (part 8);
- any addition to the `ktc2.` package is proposed first, not built unasked. The model is
  `ktc2.concurrent` and `createCleaner`: one API that is plain Kotlin on the JVM and the
  native thing in C. The flags of the SDL3 module as value classes wait for the same reason.

**How differences are found**, which is the work to go on with:
1. *Tests of the Kotlin standard library* (`tests/kotlin/`, from `libraries/stdlib/test` of
   github.com/JetBrains/kotlin at the tag of the library's version, unchanged). 153 files are
   in and pass (some 2200 tests), with the helpers of the library's tests under
   `tests/support/`; `tests/README.md` lists what they found (some fifty differences, all
   fixed). What is left: 2 tests in `<Name>.skip` files, each with the reason; `tests/pending/` is
   empty since coroutines came. Every file of `libraries/stdlib/test` but JavaScript's is in.
   Next: the tests of `libraries/stdlib/jvm/test` that are not about the JDK, and those of
   `kotlin-native/runtime/test` (its coroutine tests among them).
2. *Programs of ordinary code compared with the JVM* (`generics/ErasedViews`,
   `objects/OrdinaryClasses`, `stdlib/OrdinaryCollections`, `stdlib/OrdinaryStrings`,
   `basic/OrdinaryControl`, `objects/ClassReferences`): write what ordinary code does with one
   part of the language, run it with `KTC2_TEST_FILTER`, read `tools/jvmdiff.sh <Name>`. They
   found some twenty differences. Not looked at yet: inheritance with generics in depth,
   inner and local classes, interfaces with properties, labels and non-local returns in depth,
   threads, `kotlin.time`, `Regex` in depth, `Random`, arrays of arrays, value classes,
   delegation of properties (`by map`), `vararg` with spread in depth, `Double` as text at its
   limits, `Comparable` and `Comparator` in depth.
3. *Kotlin/Native as a third opinion* (`tools/native.sh`, after a run of the suite; it needs a
   Kotlin/Native compiler under `~/.konan` or named by `KTC2_KONAN`, which a Mac with a
   multiplatform project has and another machine may not). With Kotlin/Native 2.4.21, of 97
   programs that are plain Kotlin 73 print the same on all three, 18 differ and 6 do not
   compile there; in the 18 it is the JVM and Kotlin/Native that differ (the text of
   exceptions, what a collection notices of a change while it is gone over, `Float` as text,
   the order in which a template turns its parts into text, the Unicode tables), ktc2 being
   with the JVM. Of the 7 files of common tests 4 pass there and 3 need the compiler of
   Kotlin 2.5. Not part of the suite or of the CI job.

**What the fixes of that day changed in the design**, to know before touching the importer
(each is in `DESIGN.md`, "Where a small mistake costs most"):
- a generic class is one class in Kotlin and several here. A member of an object whose
  instantiation the type does not settle (`Box<*>`, a `Pair<Int?, Int?>`, what a cast to a
  generic class gives) is reached through a dispatcher; `Devirtualize` makes it direct again
  where the class is known;
- `Unit` is an object of the runtime where one is asked for; `Number` is treated as an
  interface the boxed numbers implement; a string holds half of a surrogate pair;
- `==` on a `T` that stands for `Double` is `equals`; `compareTo` of `Double` is a total order.

**Known and not done**, besides what the parts below list:
- a value of the wrong kind that reaches a function of a concrete class through an unchecked
  cast (a `Comparator<Int>` handed a `String`) is answered with -1, false or null where the
  JVM throws `ClassCastException`;
- `KClass.isInstance` answers false for an interface;
- two entries a `HashMap` hands out for one key are equal, not the same object as on the JVM;
- the `.warnings` kind of test has no test since the one for `Deinit` went.

**How the owner works.** They follow a run of the suite in a terminal (`./gradlew test
--rerun`, a line per program with `KTC2_TEST_VERBOSE=1`). Never two runs of the suite at
once: they share `build/integration`. A targeted run (`KTC2_TEST_FILTER`) after each change
and the whole suite after two or three, the whole suite before a commit that touches a pass.
They push to `master` directly, from several machines: pull first. Commits are by
`Bitsy <bitsydoge@proton.me>` with no mention of an assistant (`CLAUDE.md`, "Conventions"):
on a new machine set `user.name` and `user.email` in the checkout before the first commit.

**How the work went, and should go on.** One item at a time: a test that shows it (a common
test under `tests/` when it is about what must hold everywhere, a program under
`integration/` otherwise), the change, the whole suite, `tools/asan.sh` on the programs it
touches, `tools/bench.py --compare` when a pass or the library changed, the documents, a
commit. Every optimization of counting or of the frame got a test that also holds the cases
where it must *not* apply (the value that gets out, the place that is overwritten): those are
what caught the mistakes. Three were made and found that way, and are worth knowing:
- an object created inside a constructor and stored into `this` was put in the constructor's
  own frame: held by an object that was passed in means it outlives the call;
- letting a `var` borrow from a temporary kept `var x = T(); x = null` alive to the end of the
  block;
- borrowing in `node = node.next` when the walk starts from a variable at its last use made
  the benchmark slower: the list was then given up whole at the end. A count removed is not
  always time saved: measure.

If the suite misbehaves on a system, `KTC2_TEST_FORKS=1 KTC2_TEST_JOBS=1 KTC2_KEEP_LIBRARY=0`
is the suite as it ran before it was made parallel: start from that, then turn the three back
on one by one. `ktc2` itself: `KTC2_DAEMON=0` is the command as it was. The process that
stays keeps the environment it was started with: only the `KTC2_` variables and `PATH` make a
command start another one, so a changed `SDKROOT` or `CPATH` is not seen until it is stopped
(`ktc2 --stop-daemon`). `KTC2_SKIP=inline` puts the short functions back in the C files if a
C compiler objects to them in the headers.

---------------------------------------------------------------------------
## Where the benchmarks stand
---------------------------------------------------------------------------

`python tools/bench.py`, Apple clang on an ARM64 Mac, best of seven runs. "Before" is commit
`2a16110`, built `-O2`; "now" is the same build (on 2026-11-13, at the end of the day); "now, LTO" is what
`--build` does by default.

| | Before | Now | Now, LTO | Allocations | Retains |
|---|---|---|---|---|---|
| Collections | 0.527 s | 0.128 s | 0.149 s | 4.50 M, unchanged | 11.9 M -> 8.5 M |
| Dispatch | 0.508 s | 0.136 s | 0.115 s | 6.52 M -> 1,013 | 5.0 M -> 1,275 |
| Numeric | 0.279 s | 0.269 s | 0.284 s | 44 | 0 |
| Objects | 0.473 s | 0.169 s | 0.151 s | 26.0 M, unchanged | 58.0 M -> 16.0 M |
| Strings | 1.207 s | 0.241 s | 0.201 s | 1.20 M, unchanged | 3.9 M -> 2.6 M |
| Threads | 0.352 s | 0.049 s | 0.045 s | 5.54 M, unchanged | 17.0 M -> 6.2 M |
| Ordinary (added later) | | 0.223 s | 0.203 s | 8.56 M | 2.2 M |

The benchmarks did not show the largest things that day; a program of small loops over what
ordinary code does (`build/scratch`, not kept) did. Each line is the same work before and after:

| | Before | After |
|---|---|---|
| 200,000 `indexOf` and `contains` in a text of 650 characters with accents | 37 s | 0.11 s |
| a template with three numbers, 2 M times | 162 ms | 50 ms |
| three `StringBuilder.append` (a string, a number, a character), 5 M times | 234 ms | 39 ms |
| `sort()` of an `ArrayList` of a million `Int` | 169 ms | 35 ms |
| `for (i in 0 until list.size) sum += list[i]`, 20 M elements | 37 ms | 5 ms |
| a `HashMap` lookup with a key of 35 characters, 5 M times | 109 ms | 75 ms |
| `Regex("item[0-9]+").find` in a text of 350 characters, 20,000 times | 46 ms | 7 ms |

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

At the end of that day on the same machine, after the rooms, the copies, the unchecked
storage of `ArrayList`, the borrowed returned calls and `listOf`: Collections 0.384 s,
Dispatch 0.424 s, Numeric 0.241 s, Objects 0.226 s, Ordinary 0.425 s (8.56 M allocations),
Strings 0.256 s, Threads 0.661 s. Retains are what the table says.

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
  and a double release that Windows had been hiding (below). Linux runs in the CI job since
  2026-11-13 and passes but for `interop/Posix` (the same bindings, with the GNU C library:
  see "Where the work stands"). The CI job runs the three systems on every push.
- [x] **A use of freed memory is reported on every system.** A local that handed its
  reference over and was assigned again later was released twice; the fill of freed objects
  made that pass on Windows. Test builds now keep freed objects aside, filled, and end the
  program on a retain or release of one. AddressSanitizer on the generated C finds the rest
  and is worth a second suite target (`-fsanitize=address`), which is not there yet.
- [x] **What runs when an object ends** is a cleaner (`DESIGN.md`, section 5):
  `createCleaner`, Kotlin/Native's API, the JVM's cleaner behind it on the JVM; in a private
  property it is folded into the class and costs no object, and no field either when its
  resource is a `val` of the class. With it: `Effects` treats giving up a reference as
  something that can run the program's code, temporaries end with their statement, and
  `ArrayList` and `HashMap.remove` let go of what is removed at once. It began as a `Deinit`
  interface as Swift's `deinit`, removed since: `integration/values/CleanerLifetimes.kt` is
  the test of when objects end.
  Left:
  - [x] a `HashMap` entry removed through an iterator is let go at once: an entry that is
    handed out has its own key and value (`HashMapEntry`), which a `for ((k, v) in map)` no
    longer makes (`ListLoops`). `integration/stdlib/MapEntries.kt`.
  - [ ] a variable could give up its object after its last use, as Swift does in optimized
    builds, instead of at the end of its block: frees earlier, but a guard object
    (`val lock = Guard()`) would then end at once. Not planned unless asked for.

---------------------------------------------------------------------------
## 1. Compile time
---------------------------------------------------------------------------

A small program takes about 13 s in a process of its own, nearly all of it the Kotlin front
end reading the library sources (`KTC2_TIMING=1`). Through the process that stays it took
3.6 s, and takes about 1 s since that process keeps the front end's work on the library
(0.45 s inside the process, the rest is starting the command).

The suite, on a 16-core Windows machine: about 10 minutes with one program after another,
2 min 40 s now. Of a program's time 64 % was translating it (4.1 s in a warm process) and
32 % compiling its C (2.1 s); running it is nothing. What was done: the programs are shared
between processes (Gradle forks, one test class each, dealt out in the order of the class names: 8 gave 3 min 17 s), the C files of a
program are compiled by four compilers at once (2 min 52 s), and 12 processes instead of 8
(2 min 41 s). The dumps are no longer written in a run of the whole suite (they turned out
not to cost time, only 2.7 GB of disk). What limits it now: eight Kotlin compilers at once
each take twice as long as one alone, with the machine half idle (memory, not processors),
and each process pays for a first compilation. The next gain was in the translation itself,
the first line below: with the library kept, the whole suite takes 71 s with 8 processes
(74 s with 6, 90 s with 4, 81 s with 12). What is left of a program's time is then, in
about equal parts, the one analysis of the library per process (12 s, more when several
start together) and the C compiler.

| | Gain | Effort |
|---|---|---|
| [x] A compiler process kept running between compilations (`Daemon.kt`): `ktc2` starts it on first use and hands it each command; it ends after 30 minutes without one, and is replaced when ktc2 or an environment variable it reads changed. `KTC2_DAEMON=0` does without, `ktc2 --stop-daemon` ends it | 12.9 s -> 3.6 s on Windows | medium |
| [x] The front end's work on the library is kept in the process (`frontend/PhasedCompiler.kt`): the compiler is run phase by phase; the library is resolved once, and a program is one more part on top of it (a source session that depends on the library's, as one source set of a multiplatform module on another), the only one resolved each time. The conversion to IR is done for the whole each time (0.4 s), told not to empty what it converted. In the process `ktc2` keeps running and in the suite; `KTC2_KEEP_LIBRARY=0` goes back to one whole compilation per program | 3.1 s -> 0.45 s per program in a warm process; the suite 2 min 41 s -> 71 s | high |
| [ ] The same across processes: the 12 s of a first compilation are the library resolved from nothing. The compiler has no form of its resolved tree that can be written and read back; the nearest is the library compiled once to a jar with its IR serialized (`-Xserialize-ir`) and function bodies read from there on demand, which would also need the importer to accept IR that is loaded lazily | 12 s -> perhaps 2 s for a first compilation | high, unknown |
| [ ] Keep the library's IR too, not only its analysis: the 0.4 s left per program are mostly the conversion of the library to IR again. The importer does not change the IR it reads, but the conversion registers every declaration in tables that would have to be shared between programs | 0.45 s -> perhaps 0.15 s | medium to high |
| [~] The C of a test program: 1 to 2 s, as much as the translation now. One C file that includes all the others is written (`ktc2_all.c`; the `static` names are numbered by unit), and `KTC2_TEST_UNITY=1` has the suite compile it. Measured on a Mac: one compiler on that file takes what four take on the files (1.4 s against 4.4 s for one after another), and the suite the same 46 s. Not tried on Windows, where starting gcc costs more; and not the default, because a C file that lacks an include is only found when each is compiled by itself | none on a Mac; the suite on Windows: perhaps 71 s -> 55 s | done, to be measured |
| [x] The suite in several processes, and the C of a program by several compilers (`IntegrationTest.kt`, `build.gradle.kts`: `KTC2_TEST_FORKS`, `KTC2_TEST_JOBS`) | 10 min -> 2 min 40 s | low |
| [ ] Hand the compiler only the library files a program can reach | 1-2 s; tried once for the unsigned arrays and dropped as fragile | medium |
| [x] Do not rewrite C files whose content did not change, so the C build is incremental (`CEmitter.write` already did) | faster rebuilds through CMake | low |

---------------------------------------------------------------------------
## 2. Allocation
---------------------------------------------------------------------------

| | Gain | Effort |
|---|---|---|
| [x] `for` over an `ArrayList`, an array or a range as an index loop when the type is known. Arrays and ranges were index loops already. Over a list whose class is known to be `ArrayList` (`Devirtualize`), `ListLoops` writes the loop over the storage itself: no iterator, the size and the count of changes compared once per round, the element read without a test. It is the platform iterator written out, so it ends and fails as that one does (`integration/stdlib/ListLoops.kt`, `KTC2_SKIP=listloops`) | Collections 0.26 s -> 0.15 s, Dispatch 0.31 -> 0.22, Ordinary 0.35 -> 0.28, Threads 0.17 -> 0.05 | medium |
| [x] The same for a `HashMap`: `for ((k, v) in map)`, `map.keys`, `map.values`, and what is built on it (`LinkedHashMap`, the sets). The loop is known by the class of its iterator, which is still made once; the map is read from it, and the loop goes over the entries itself. For the entries, the entry object is not made when the loop only asks it for its key and its value (`integration/stdlib/MapLoops.kt`) | 3.7 ns per entry -> 0.5 ns (LTO, 30 M entries) | medium |
| [~] Iterators in the frame when the loop cannot be an index loop. Done where the class of the collection is known (a local, a parameter every caller passes the same class to, a property that only ever receives one class). A parameter that receives several classes (`List<T>` given an `ArrayList` here and a `listOf()` there) is covered by the next line | high where it applies | medium |
| [x] The result of a call through a dispatcher is built in the caller's frame when it stays there: the dispatcher has an `__into` form that hands the caller's room (a `union` of the classes the implementations build) to those that can build in place. With it an argument is no longer "kept" just because the call is dispatched: it is held as the implementations hold it, so a local list handed to a function that takes a `List` stays in the frame (`integration/objects/DispatchedResults.kt`, `KTC2_SKIP=slots`) | Dispatch: 6.5 M allocations -> 1,014; every `for` over a `List`, `Collection` or `Map` parameter | medium |
| [x] A variable that is given a new object on every turn of a loop (`place = place + step`): the function has two rooms for it, and the call builds in the one that holds no object in use (`KStackSlot.rooms`, `ktc2_slot_reuse`); when something else still holds an older object, that one comes from the heap. `integration/objects/Reassigned.kt`, `KTC2_SKIP=rooms` | Ordinary: 12.56 M allocations -> 8.56 M; the time does not move | medium |
| [ ] The array inside an `ArrayList` (or `StringBuilder`, `HashMap`) that is itself in the frame: the constructor stores it into `this`, which was passed in. Needs room in the caller for a first small array, as `ktc2_array_local` has for arrays. Measured before building it: a local list that is given six numbers and summed takes 22 ns, of which making and freeing its array is 5 (the same loop over a local `IntArray` takes 1.4 ns: the rest is the list itself, its `add` and its counters). So about a quarter of such a function at best, for a change to the most delicate pass; an object that never leaves its function as plain locals (below) is where the other 15 ns are | 5 ns per local list; perhaps 5 % of `Ordinary` | medium to high |
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
| [x] A function called with objects of several classes for one parameter is copied for the calls that pass a known class (`Devirtualize.copyFor`, `KirCopy.kt`): `total(values: List<Int>)` given an `ArrayList` goes to `total__c0`, where the iterator and its `hasNext` and `next` are direct calls. At most 4 copies of a function of at most 80 statements, three rounds. `integration/objects/Copies.kt`, `KTC2_SKIP=copies` | the `for` over a list parameter in `Ordinary`: 87 ms -> 60 ms with LTO (half its calls pass an `ArrayList`); Ordinary -7 % with LTO, -3 % without; programs under 1 % larger | medium |
| [x] `listOf(a, b, c)` is of a known class: with at least one element and no spread the call is lowered as `elements.asList()`, which is what `listOf` does then (`lowerCall` in `BodyLowering.kt`) | with the two lines below: Collections -18 %, Ordinary -17 %, Dispatch -12 % | low |
| [x] `ArrayList` does not test the index of its own storage: `Storage.at` and `Storage.put` (`Ktc2Internal.kt`) are `get` and `set` without the test, for the platform layer where it has made sure of the index. The storage of an `ArrayList` never shrinks and every index was below its length. So `add` cannot throw, and no caller tests for an exception after it | part of the above | low |
| [x] A function that returns the result of a call directly (`fun first() = at(0)`) can have its result borrowed: `ExceptionLowering` gives every returned call a local, the shape it already gave the ones that can throw and the only one `BorrowedReturns` looked at. Found when `next()` of the list iterator lost its borrowed form (retains of Collections: 8.7 M -> 38.7 M until fixed). `integration/objects/ReturnedCalls.kt` | correctness of the counts; Objects and Threads -8 % | low |
| [x] `HashMap` no longer tests the four indices of a probe: `find` holds the table and the three arrays in locals for the whole probe and reads them with `Storage.at`. They are replaced together, and a slot only names entries its own arrays have, so that holds even when comparing a key runs code that clears the map. The index it returns is still tested by the caller. No restart when `changes` moved: what the JVM does then is not defined either | Collections: no change in time (the C compiler had most of it); less code, fewer tests for an exception | low |
| [x] The class of a call's result is followed in two rounds: a call through a dispatcher that is not resolved yet says nothing of its result until nothing else moves, where it used to say "any class" at once, for good. The iterator of `map.keys` or `map.values` (the result of a call on the result of a call) is now of a known class | 558 calls made direct in the showcase, from 490 | low |
| [ ] Follow the other arguments through dispatchers too, and the element class of a collection (`List<Shape>` that only holds circles) | medium | medium |
| [ ] Stop sharing the code of a local class between the instantiations of the generic class around it (the importer reads an `X<Int>` through the type `X<Any>`): `Devirtualize` has to distrust static types wherever it sees this, and so will every later pass that reasons about classes | removes a trap more than it gains speed | medium, in the importer |
| [x] Fold `is` and `as` when the class is known: 20 tests in the showcase | low | low |
| [x] Short generated functions as `static inline` in the headers: those of at most four lines of C that need nothing but their own class and the runtime (`CEmitter.inHeader`, `KTC2_SKIP=inline`). Longer ones were tried (6, 8, 12 lines) and gained nothing | Dispatch without LTO: 0.218 s -> 0.136 s; nothing with LTO | low |
| [x] `list[i]`, `list[i] = x` and `list.size` on a list known to be an `ArrayList` are written out where they are (`ListLoops.accessor`): one test of the index, and the call to `checkIndex` only when it fails. `integration/stdlib/ListAccess.kt` | an indexed loop: 1.85 ns -> 0.25 ns for an element; Collections -9 %, Ordinary -15 % | low |
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
| [~] No bounds check in a loop whose index is known to be in range (`RangeChecks`): the loops over an array, its `indices`, `0 until size` and `Array(n) { }`. With clang at `-O2` the time does not change (it removed those tests itself); the code is smaller and such functions no longer count as throwing. Also: `while (i < a.size) { …; i++ }` (the step in the body; a copy of the counter made before the step still counts after it), `for (i in a.size - 1 downTo 0)`, and an index next to the counter (`a[i + 1]` under `i < a.size - 1`, `a[i - 1]` from `i = 1`). `list[i]` is not proven in range but written out (part 4): its test stays, the call and the test for an exception go. Left: a step other than one, strings; with clang neither changes the time | medium for array code | medium |
| [x] An array store is tested once: `ArcInsert` dropped the "already tested" mark, so every store was tested by the generated code and again by the runtime | small, everywhere | low |
| [x] The first-use test of an object or a file taken out of loops: not needed. The one of a file is at the start of the function already; the one of an object is a load and a branch that is never taken. A loop that reads two properties of an object and a top-level variable on every round takes 0.6 ns a round | none | - |

---------------------------------------------------------------------------
## 6. Strings and the library
---------------------------------------------------------------------------

| | Gain | Effort |
|---|---|---|
| [x] Remember the last index and byte position in a non-ASCII string, so `s[i]` in a loop is not a scan each time (per thread; forgotten when a non-ASCII string is destroyed) | high for loops over such text | low |
| [x] A template is built in a buffer of the frame already, and copied once into its string. What it cost was `printf` for every number: integers and Booleans are now written by the runtime itself (`format_signed`). `Double` and `Float` still go through `snprintf` and `strtod`, up to 17 times each, to find the shortest digits: an algorithm of its own (Ryu, Grisu) is what is left | a template with three numbers: 81 ns -> 25 ns; Strings -17 % | low |
| [~] Platform versions of hot library paths. Done: `indexOf`, `lastIndexOf`, `regionMatches` (so `contains`, `split`, `startsWith`, `endsWith`, `replace`) on ASCII strings work on bytes in the runtime: Strings -75 %. Also done: the same for strings that are not ASCII (they went one character at a time, each found by walking the string: 300 times slower than now), two remembered places for code that reads two strings side by side, `StringBuilder.append` of a string (one copy) and of a number (no string in between), `toCharArray`, the hash and the order of ASCII strings on their bytes, `ArrayList.sort` in its own storage. Left: `String.hashCode` kept in the string (four bytes more for every string: to be weighed), `sortedBy` on objects (15 ns for a comparison, most of it the two keys boxed), `Double` as text | medium | medium, one at a time |
| [~] `Regex`. Done: a pattern that starts with a character, exactly, is only tried where that character is (`find`, `findAll`, `containsMatchIn` 3 to 6 times faster for such a pattern), and the text is turned into characters by one loop. Left: the same for a pattern that starts with a class (`[a-z]+`: 42 ns for every place it is tried), and keeping the match state between searches (three small objects for each search) | medium for Regex-heavy code | medium |

---------------------------------------------------------------------------
## 7. Code size and the C build
---------------------------------------------------------------------------

| | Gain | Effort |
|---|---|---|
| [x] Link-time optimization by default for `--build` without `--debug`, and through CMake where the compiler has it (`KTC2_LTO=0`, `-DKTC2_LTO=OFF`). With clang on Windows it needs LLVM's linker: used when `lld-link` is on the path (`-fuse-ld=lld`), Objects 0.27 s -> 0.21 s there | 10 to 48 % faster, 30 to 45 % smaller | low |
| [x] One C file per package, not per class: not done as such. What it was for is there another way: inlining without LTO by the short functions in the headers (part 4), and a build by one compiler by `ktc2_all.c`, which includes every C file (part 1) | - | - |
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
  `next()`. The lists built on `AbstractMutableList` (`ArrayDeque`) check by `modCount`, as
  the JVM's `AbstractList` does, and `subList` is a view that fails once its list changed
  size another way.
- [ ] **The last digit of `exp`, `ln`, `pow`, `sin`.** It is the C library's. To print what
  the JVM prints on every system, the runtime would carry fdlibm's versions (what
  `StrictMath` is); a few hundred lines of C. Until then DESIGN.md lists it as a difference.
- [x] **An enum that is a number: `@SimpleEnum`** (`DESIGN.md`, section 4;
  `integration/values/SimpleEnums.kt`, `integration/errors/SimpleEnumState.kt`). A C `enum`;
  `@EnumValue` on an `Int` or `UInt` parameter of the constructor gives the numbers (a
  constant, or a `const val` such as the ones of C bindings), and `ktc2.enumOf<T>(number) {
  it.code }` goes from a number back to the entry: a loop over the entries on the JVM, the
  number compared with the entries' own in C. Left:
  - [x] `name`, `ordinal`, `toString()` (unless the class wrote its own) and `compareTo` of a
    value are answered from the number, by a lookup of their own as the constructor's
    properties have (`simpleEnumBuiltin`): no object. `compareTo` is the difference of the
    ordinals, as on the JVM.
  - [x] the SDL3 module's namespaces of constants are `@SimpleEnum` classes with SDL's own
    numbers: `SDL3.Event`, `Scancode`, `MouseButton`, `BlendMode`, `TextureAccess`,
    `PixelFormat`, `Flip`, `SystemCursor`. The functions of the module take them
    (`setBlendMode(SDL3.BlendMode.Blend)`, `isKeyDown(SDL3.Scancode.Space)`), and an event
    gives them (`event.kind`, `event.key.code`, `event.button.pressed`). The showcase and the
    demo build and run with gcc and MSVC. Not exercised by a test: an event that really comes
    from SDL (the automatic run of the showcase calls its screen directly).
  - [ ] the flags that are or-ed together (`InitFlags`, `WindowFlags`, `MessageBoxFlags`) are
    still `object { const val }`: they are not an enum. A `value class` over the number with
    the constants in its companion and an `or` would check them too, and costs nothing.
  - [ ] a `@SimpleEnum` given to a C function is converted by hand (`mode.value`): the
    bindings could accept the enum where C declares the `enum`.
  - [ ] `enumOf` gives the object kept for the entry (`T?`), which `?: Unknown` turns back
    into the number: correct, with a chain of compares on each side. A form with a default
    (`enumOf(number, Unknown) { }`) would stay a number all the way.
- [x] **A failing `valueOf` throws** `IllegalArgumentException("No enum constant ...")`, for
  every enum: it ended the program before.
- [x] **Coroutines** (2026-11-14). `suspend` functions in full, by the transformation into a
  state machine (`DESIGN.md`, "Coroutines"); `sequence { }`, `iterator { }`,
  `DeepRecursiveFunction`, `startCoroutine`, `suspendCoroutine`, the intrinsics of
  `kotlin.coroutines`. Tests: `basic/SuspendFunctions`, `stdlib/SequenceBuilders`, and the
  library's `SequenceBuilderTest`, `CoroutinesReferenceValuesTest`, `DeepRecursiveTest`. Left:
  - [ ] kotlinx.coroutines (its common sources and a platform part, as for the library);
  - [ ] a `suspend fun interface`;
  - [ ] a frame that is not a state machine still costs nothing, but every state machine is an
    object on the heap and all its locals are fields: locals that never live across a
    suspension could stay C locals, and a coroutine that never escapes could be in the frame;
  - [ ] the driver of a coroutine (a receiver that is also its completion) is held without a
    count also where suspension is not restricted to it.
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
- [x] `Clock` and `Instant`, case of characters beyond U+FFFF, the `U` flag of `Regex` and its
  four-letter script codes, `Base64` (2026-11-14).
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
  (`integration/errors/`). A use of `java.*` and a `@SimpleEnum` with state have one. So have
  inline storage that would need a copy (`InlineNeedsCopy`) and a `@Value` or `@Size` local
  that cannot stay in the frame (`ValueLeavesFrame`).

---------------------------------------------------------------------------
## What I would do first
---------------------------------------------------------------------------

1. `interop/Posix` on Linux, so that the CI job is green and its AddressSanitizer step runs
   ("Where the work stands").
2. What the files of `tests/pending/` and the `.skip` files wait for (`tests/README.md`):
   `buildList` and the others read-only once built, `KClass.cast`, `CodePoint`, then more
   of the library's tests (`libraries/stdlib/jvm/test` where it is not about the JDK).
3. Another program of ordinary code compared with the JVM, on what was not looked at yet.
4. The differences `DESIGN.md` still lists in section 12 (paths on Windows, the last digit of
   `exp` and the like).
5. `suspend` functions in full, which `sequence { }` and `iterator { }` wait for (part 8).
6. Unit tests of the passes; files through kotlinx.io or okio (part 8).
7. Speed, last: `Double` as text without `snprintf`, `sortedBy` without boxing its keys, an
   object that never leaves its function as plain locals (parts 2 and 6); a program of small
   loops over what ordinary code does, timed, as an eighth benchmark.

---------------------------------------------------------------------------
## Measuring
---------------------------------------------------------------------------

- `python tools/bench.py [name part...] [--runs N] [--copt option] [--save file] [--compare file]`
  translates `benchmarks/*.kt` with the installed tool (`./gradlew installDist` first), builds
  each at `-O2`, and prints the best time, the allocations, retains and releases, and the size
  of the program; the table goes to `build/bench/results.txt`. `--compare` shows the change
  against a table saved before; `KTC2_TOOL` names an installed tool of another state of the
  sources, to measure both on the same day. Do not run it while the suite runs.
- `KTC2_SKIP=devirtualize,copies,listloops,ranges,handover,brief,walk,owned,slots,rooms,inline` leaves optimizations out, one or several, to
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
