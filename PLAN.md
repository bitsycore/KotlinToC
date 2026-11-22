# ktc2 - plan

What to do next, in order, with estimates. `DESIGN.md` says how ktc2 works now; `README.md` is the user's view;
`tests/README.md` the tests. `docs/HISTORY.md` is the archive: the original design, the log of the work, the
plan and the kotlinx plan as they were kept until 2026-11-18 (for the reasoning behind a decision, never as a
list to follow). This file replaces them as the one list to follow (rewritten on 2026-11-18).

Each item: a test that shows it (a program under `tests/integration/`, or a common test), the change, the tests
that cover it, a commit. Estimates are days of work at the pace of the last weeks; nothing below is prototyped
unless it says so.

**The runtime a program needs, and no more** (the owner, 2026-11-21; read before any work on the runtime or on a
library's needs):
- What a library needs costs the programs that use it, not every program. kotlinx.coroutines' cycles (declared
  cycles, rings, knots and their notes) are its own: their runtime is compiled in only when a module of the program
  declares cycles or rings (`KTC2_KNOTS` in the program's `ktc2_config.h`, as `KTC2_THREADS` is when it starts a
  thread). A program without them has none of that code, none of its branches, none of its bits of the count.
- The runtime is made for each program by such options: a module that needs a part of the runtime turns it on. A new
  part that only some programs need is a new option of `ktc2_config.h`, written by the emitter from what the program
  has, not a cost for all.
- A small addition to ktc2's base is fine when Kotlin itself needs it (the identity hash of `Any`, a cycle of an object
  and its inner object): what plain Kotlin needs is the base.
- kotlinx.coroutines first, as it is (its API, so that ordinary Kotlin works), at its own cost. Then `ktc2.coroutines`,
  ktc2's own implementation for counting, without cycles by design, compatible with the JVM, Kotlin/Native and ktc2
  (section 7.5). Keep it in mind: nothing for kotlinx.coroutines goes into the base where it could stand in its way.

**Who answers a cycle** (the owner, 2026-11-21; read before any work on memory, a library's cycles or the lint). There is
no general cycle collector, in the runtime or as an option (section 7.1):
1. The language stays plain reference counting, as Swift is: a cycle a program makes is the program's to break, and
   the compiler warns where it can see one coming (`CycleLint`), saying what to write (`@Weak`, `WeakRef`, `@Acyclic`,
   letting go of a reference once done). What no compiler can see (an array or a list put into itself) is the
   program's, as in Swift.
2. A kotlinx library keeps its API and its behavior: a program written for the JVM or Kotlin/Native compiles and
   computes the same.
3. To keep that behavior, a library's module may have rules and runtime of its own (`weak`, `acyclic`, `cycles`,
   `rings`, edits of its sources, Kotlin code of its own, a runtime option), compiled only into the programs that use it
   (the runtime principle above). Everything internal to the library, its own objects referring to one another, is
   answered there: the user never has to know.
4. What the user can fix with the library's own API as it is, adding only a `ktc2.` annotation or way of working
   (`@Weak` on a property, a `WeakRef` captured instead of the object, a variable cleared once done), is the user's:
   a warning of the compiler, never machinery in the library or the runtime. A cycle that passes through the user's
   own objects (a lambda, a class, a frame of theirs) is never cut by a library's rules.

---------------------------------------------------------------------------
## 1. Where it stands (2026-11-18, night, Windows)
---------------------------------------------------------------------------

- **The suite**: 1,218 tests in four categories (integration 185 programs, kotlin ~180 files of the standard
  library's and Kotlin/Native's tests, box 384 of the compiler's coroutine box tests, libraries ~470 files of
  the bundled libraries' own tests). The last whole run on Windows with clang: 1,207 passed and none failed;
  the last 11 (kotlinx-serialization-json-io) were not reached when it was stopped. The CI job of the last
  push from the Mac failed on Linux (`strdup`, gcc 14's dangling pointer); fixed in `1e64df1`, not pushed.
  The fix and the work of the night were pushed together on 2026-11-19 (`ab63593` and the eleven before it):
  read that CI run first.
- **kotlinx.coroutines**: the library compiles as it is (with 14 edits of the module) and its 170 test files
  run and pass their tests in C and on the JVM; 27 of them leave cycles (`.cycles`): 16 of the library's
  structures not answered yet, 11 by their tests' design or the deprecated `BroadcastChannel` (section 2).
- **The other libraries**: kotlinx-io, kotlinx.collections.immutable, atomicfu, kotlinx-datetime pass whole;
  kotlinx.serialization and its JSON pass whole (2026-11-21), 4 files with `.cycles` for a known leak (types that
  contain themselves, section 2.4).
- **Speed** (2026-11-17, a Mac, `tools/compare.py`): fastest of the three on 9 of 10 benchmarks, 1.2 to 4.2
  times Kotlin/Native, 1.3 to 8.5 times less memory than Native; the JVM is 9 % faster on making small
  objects in a loop. Programs of about 0.1 MB.
- **Compile time**: 9 to 13 s for a first program (the library analysed), 0.4 to 1.5 s after a change through
  the compiler process, plus the C (20 s for the showcase with clang).

---------------------------------------------------------------------------
## 2. The first working version: every test passes
---------------------------------------------------------------------------

The objective (the owner, 2026-11-18): every test of the Kotlin standard library, of each bundled kotlinx
library and of ktc2's own passes in C, as on Kotlin/Native. "Passes" means: no `.pending`, no `.skip` but the
few that state a difference the owner's rule accepts (section 8), and no `.cycles` (memory freed as
Kotlin/Native's collector would). What stands between, in the order to do it:

### 2.1 Bugs found and not fixed
Nothing left: `WithTimeoutTest`'s cycle is one of the lists of handlers of 2.2 today.

### 2.2 Cycles: the reaper of kotlinx.coroutines and the lint (the owner, 2026-11-21; 1 to 2 weeks)
The rules are "Who answers a cycle", at the top. What the 32 files with `.cycles` leave, by who answers it:

**The user's (rule 4): warnings of the compiler.** A test that does what a program should not, and that the library's
API as it is lets it avoid: its `.cycles` stays, saying "the user's cycle, warned at compile time", once the warning is
there.
- Done (2026-11-21, `UserCycleLint`, `DESIGN.md` section on cycles; `tests/integration/process/UserCycles`): a coroutine's
  continuation kept where its own coroutine reaches it (a captured `var`, a property of an object the coroutine holds, a
  list of actions that is never drained), a registration whose handler uses its own handle, a property whose
  `DeepRecursiveFunction` or `lazy` value keeps a lambda that uses the object. Warned in 11 of the files with `.cycles`
  (6 of kotlinx.coroutines' tests, 3 box tests, `DeepRecursiveTest`, `LazyValuesTest`), whose `.cycles` now say so; and
  nowhere else in the suite. The three `*WithHandleResult` box tests drain their list of actions and leave one only when
  it throws: not warned (what no compiler sees, rule 1).
- An array, a list or exceptions put into themselves (`ArraysTest`, `CollectionTest`, `ExceptionTest`): what no
  compiler can see, the program's (rule 1). Accepted.

**The library's (rule 3): the reaper of kotlinx.coroutines.** Done (2026-11-22; `DESIGN.md`, "The reaper"): a job that
ends with an exception is given to the runtime's reaper (`ktc2.internal.reapLater`, through its weak cell), which
checks its group by trial deletion between the events of `runBlocking`'s loops and when `main` returns; with edits that
let go of what the library is done with (a scope's caller once resumed, a lazy coroutine's block when it was cancelled
while made) and a select's clauses' outer reference weak. Freed: `ZipTest`, `ChannelBuildersFlowTest`, `IdFlowTest`,
`CombineTest`, `CancelledParentAttachTest`, `SelectRendezvousChannelTest`. Found on the way: a weak cell given up on one
thread while its target ended on another crashed the runtime (`regressions/WeakCellAndTargetEndTogether`).
Left of the library's own:
- [ ] The lists of handlers of jobs that end on several threads at once (`ConcurrentExceptionsStressTest`): rings of
  `ChildHandleNode`s and `Removed`s that no job given to the reaper reaches (the parent's list of children).
- [ ] The deprecated `BroadcastChannel`'s subscriptions (`BasicOperationsTest`, `BufferedBroadcastChannelTest`,
  `ConflatedBroadcastChannelTest`): each subscription, an inner object the user holds and that names its channel, is in
  the channel's list; a weak outer reference would let a channel go while a subscription is used. Its own answer: the
  reaper given the channel when it closes, or an edit that takes a subscription out of the list once cancelled.

**Known, kept:** kotlinx.serialization's types that contain themselves (section 2.4): one ring for each such type,
held for the life of the program by its serializer object.

### 2.3 kotlinx.coroutines: what is not taken yet (3 to 5 days)
- Every box test of coroutines taken passes (41 of 41, 2026-11-21; none pending). Six have `.cycles`: they keep a
  continuation in a variable or a list their own coroutine holds (the three `*WithHandleResult`,
  `boxReturnValueOfSuspendLambda` twice, `resultExceptionOrNullInLambda`), the test's design, which a collector frees.

### 2.4 kotlinx.serialization (done but for a known leak)
- Known leak, kept: the 4 `.cycles` left (`JsonPathTest`, `JsonDecodingErrorMessagesTest`,
  `JsonTransformingSerializerTest`, `JsonTreeDecoderPolymorphicTest`) are types that contain themselves (directly, or a
  sealed class through a subclass): the descriptor reaches its own serializer again through the lazy `childSerializers`,
  a ring whose size depends on the type. One for each such type, held by its serializer object for the life of the
  program and left at its end: nothing a program makes again and again.
- The two skips left are the JVM side's (`ModuleBuildersTest`: its message without kotlin-reflect;
  `KeyValueSerializersTest`: a JVM-only body that needs JUnit's asserter), not differences of ktc2.

### 2.5 The standard library (2 to 3 days)
- The 5 `.cycles` left of `tests/stdlib/` are the tests' own design: a collection, an array or exceptions put into
  themselves (`ArraysTest`, `CollectionTest`, `ExceptionTest`: the program's, rule 1), an object whose
  `DeepRecursiveFunction`s or whose lazy value hold lambdas that use it (`DeepRecursiveTest`, `LazyValuesTest`: a
  warning to come, section 2.2).
- Known difference, accepted (the owner's to decide otherwise): `MapTest.nullKeyAndValue`, a `null` put through an
  unchecked cast into a map that keeps its values as numbers. Only generics of numbers kept boxed where a cast can
  reach them would answer it.
- [ ] Not taken yet: the tests of `libraries/stdlib/jvm/test` that are not about the JDK, and what is left of
  `kotlin-native/runtime/test`.

### 2.6 More tests to take (ongoing)
- [ ] Box tests of the Kotlin compiler beyond coroutines (`tools/select_box.py`): classes, inline classes,
  generics, lambdas, when, strings. Thousands; take them directory by directory, the ones without JVM-only
  features.
- [ ] ktc2's own tests (`tests/ktc2/`, 132 files since 2026-11-19): more of what must hold everywhere as assertions,
  where a test now only prints and is compared with the JVM.

### 2.7 Every system (1 to 2 days, mostly waiting)
- [ ] Push and read the CI job (Linux, macOS, Windows, MSVC, AddressSanitizer on Linux), then fix.
- [ ] The whole suite once on the Mac and on Linux (WSL), AddressSanitizer on the coroutines' tests
  (`tools/asan_all.sh kotlinx-coroutines`): threads and counts under contention.
- [ ] Paths and arguments on Windows as wide characters (now bytes, right for ASCII only).

---------------------------------------------------------------------------
## 3. The project: cleanup and reorganization
---------------------------------------------------------------------------

The repository grew one need at a time. A proposal, to be decided item by item (options with a
recommendation first):

### 3.1 Documents (done 2026-11-18)
Five documents to follow: `README.md` (the user's), `DESIGN.md` (how it works), `PLAN.md` (this),
`tests/README.md` (the tests), `CLAUDE.md` (how to work here). `docs/HISTORY.md` is the archive.
`KOTLINX.md` and the long log of `PLAN.md` went there. Rule from now on: `PLAN.md` holds no log; what was done
leaves it for a line in `docs/HISTORY.md`'s log, and its numbers for `DESIGN.md` when they describe the design.

### 3.2 Gradle (1 to 2 days)
Today: a root build, `compiler/` (the tool, its tests, the suite's task), `compat/` (the `ktc2.` package for
the JVM), `ide/` (source sets for an IDE), and Gradle's configuration cache that kept a stale filter once.
- Recommended: one Gradle project for the tool (`compiler/`, with `compat` as a source set of it, since the
  tool and the JVM side of the suite are its only users), and `ide/` generated by a task rather than kept by
  hand. The suite's options (`KTC2_TEST_*`) read at execution, not configuration, so the configuration cache
  can stay on without the filter going stale.
- Or: keep the three, document why each exists. Less work, the same confusion for a newcomer.

### 3.3 Tests (2 to 3 days; section 4 is done)
Today (2026-11-19): a folder under `tests/` for each category: `integration/` (whole programs), `ktc2/` (ktc2's own
tests by topic), `stdlib/` (Kotlin's own tests), `modules/<module>/` (libraries' tests copied in), `box/`, and eleven
kinds of files next to a test (`.skip .vary .cycles .pending .jvm .native .expected .in .def .errors
.warnings`), read by a harness of 1,000 lines (`IntegrationTest.kt`) that also builds and runs.
- [ ] One file of settings per test or per directory (`<Name>.test.toml`, or `tests.toml` with a section per
  file) in place of the eleven kinds: skips, varying lines, cycles, pending, JVM options, expected output.
- [ ] The libraries' tests fetched at the module's version, like their sources (a `tests` key in
  `module.ktc2.toml`: the repository and the directories), with only the settings files in the repository. The
  repository loses some 600 copied files and a new version of a library is one line. Or keep them copied:
  greppable and offline. Recommended: fetched, with the cache as the place to read them.
- [ ] The harness reduced to running `ktc2 test` and comparing with the JVM: the groups compile as `ktc2 test` does
  (section 4), but through the harness's own build and run; the build, the run and the leak check by file move
  into the tool, where a user gets them too.

### 3.4 Tools (1 day)
Fifteen scripts in bash, Python and PowerShell (`tools/`). Recommended: the ones a user needs become `ktc2`
commands (`ktc2 test`, `ktc2 bench`, `ktc2 asan`), the ones the suite needs one Python entry
(`tools/dev.py failures | jvmdiff | native | msvc | arc-compare`), and `try.sh`, `try_cmake.sh` go (`ktc2 test`
and `--cmake` do it).

---------------------------------------------------------------------------
## 4. kotlin.test as a module, and a test mode of the compiler
---------------------------------------------------------------------------

Done on 2026-11-19 (`docs/HISTORY.md`, section 12; `DESIGN.md`, `tests/README.md` "Groups"): the module
`kotlin-test`, `ktc2 test`, and the suite on it. What is left of the proposal (a day):
- [ ] `--isolate`: each test in a process of its own (the program runs itself with `--ktest_filter=Class.test`), so a
  crash fails one test and not the run; the suite does it by file today, after a crash.
- [ ] `--report junit.xml` for CI (Native's runner has a TeamCity logger, `--ktest_logger=TEAMCITY`, already).
- [ ] Leaks named by test rather than by file: the runtime's count of live objects around each test (a
  `TestListener` of ktc2's).
- [ ] `tools/native.sh` on the groups: the same directories with Kotlin/Native's `-generate-test-runner`, one
  program each, instead of one a file.

---------------------------------------------------------------------------
## 5. Speed of programs: toward Go and Swift
---------------------------------------------------------------------------

Where it stands: faster than Kotlin/Native nearly everywhere, at the JVM's level on allocation-heavy code.
Go (escape analysis to the stack, a fast GC, value types) and Swift (ARC with ownership, value types,
specialization) are the references. What to investigate, by expected gain:

### 5.1 Measure against them first (2 days)
- [ ] The same benchmarks in Go and Swift (`benchmarks/`, written idiomatically), and the classic ones:
  binary-trees, n-body, spectral-norm, fannkuch-redux, mandelbrot, fasta, k-nucleotide, regex-redux, a JSON
  round trip, a channel ping-pong, a `HashMap` of strings. `tools/compare.py` with targets `go` and `swift`.
  Without numbers the list below is guesses.

### 5.2 Allocation and escape analysis (the largest)
- [ ] **An object that never leaves its function becomes plain locals** (scalar replacement, as Swift's
  allocation promotion and Go's stack objects): no header, no count, fields in registers.
- [ ] **Escape analysis through dispatchers and across the program**: a summary per function ("parameter 1
  escapes, parameter 2 is only read"), computed bottom-up, also for dispatchers (the union of their targets).
  Today a call through a dispatcher makes its arguments escape unless the slots apply.
- [ ] **Inlining in KIR** before the analyses, for small functions and lambdas passed to non-inline ones: the
  C compiler inlines too late for escape analysis and counting to see through.
- [ ] **The array inside a local `ArrayList`/`StringBuilder`/`HashMap`** in the frame (measured: 5 of 22 ns
  of a small local list).
- [ ] **Regions inferred**: a loop body whose objects all die by its end allocates from a region reset each
  turn (`arena { }` exists, by hand).
- [ ] **Coroutine frames**: locals that never live across a suspension as C locals; a coroutine that never
  escapes (a `suspend` call awaited at once) in its caller's frame; frames from the pool by size.

### 5.3 Counting
- [ ] **Non-atomic counts for objects no other thread can reach**: an escape-to-thread analysis (an object
  never stored where a thread or a shared object reaches it) and a "shared" bit set when it is published.
  Threaded programs pay an atomic operation for every count today.
- [ ] **Retain and release moved and paired across blocks** (Swift's ARC optimizer: code motion, the identity
  of the counted object through casts and copies); today the pairing is within a block and at last uses.
- [ ] **Owned and guaranteed parameters through dispatchers** (`__o` forms are direct calls only).
- [ ] **Immutable objects counted less**: a `val`-only class read many times; its fields borrowed.

### 5.4 Values and generics
- [ ] **Small immutable classes as values without an annotation**: a final class with only `val`s whose objects
  are never compared by identity, synchronized on, given a cleaner or a weak reference (the whole program is
  seen) becomes a struct, as `@Value` does by hand. Data classes of two or three numbers are the common case.
- [ ] **Generics instantiated for hot reference types too** (only numbers are now): no casts, direct calls;
  more code, so by profile or for classes of the program only.
- [ ] **One implementation for instantiations that compile to the same code** (the other side of the above:
  size).

### 5.5 Calls, checks, exceptions
- [ ] **Exceptions in the return value** (a flag returned with the result, as Swift's error register) instead of
  the thread-local pending slot tested after each call: fewer loads in threaded code.
- [ ] **Devirtualization by the element class of collections** (`List<Shape>` that only ever holds circles) and
  by profile (a guarded direct call where one class dominates).
- [ ] **Bounds checks** in loops with a step other than one, and on strings.
- [ ] **Profile-guided builds**: clang's PGO (`-fprofile-generate`/`-fprofile-use`) behind a `--pgo` option; and
  ktc2's own profile (hot classes at call sites) feeding the devirtualization and the specialization above.

### 5.6 Strings and the library
- [ ] `Double` and `Float` as text by an algorithm of its own (Ryu) without `snprintf`/`strtod` loops (Native's
  `NumberConverter` is used now: correct, slow at the edges).
- [ ] `String.hashCode` kept in the string; small strings inside the header.
- [ ] `sortedBy` without boxing its keys; `Regex` that starts with a class of characters only tried where one is.
- [ ] A dispatcher of ktc2's own for `Dispatchers.Default` (work stealing, as Go's scheduler), as the platform
  part Native has in `nativeOtherMain`: the library's own pool goes through a channel for each task.

### 5.7 Annotations of the `ktc2.` package (proposals only: each is the owner's to accept)
They change storage and cost, never results (the rule of section 8):
- `@ThreadConfined` on a class: its counts are never atomic, checked in debug builds.
- `@NoEscape` on a parameter (a lambda or an object only used during the call): the caller keeps it in its
  frame without proof; checked in debug builds.
- `@Unboxed` on a generic parameter of a class of the program: instantiate it for every type, references too.
- `@Region` on a function: everything it allocates that does not escape comes from a region freed at return.

---------------------------------------------------------------------------
## 6. Compile time
---------------------------------------------------------------------------

Where it stands: a first program 9 to 13 s (the library analysed: 12 s of it), 0.45 s of Kotlin after that in
the compiler process, the IR to C under a second, the C 0.2 to 20 s. By gain:
- [ ] **The test mode of section 4** for the suite: one compilation per directory of tests (the libraries'
  category goes from about 50 minutes of compilation to a few).
- [ ] **The library's analysis kept across processes**: serialize the resolved library (FIR or IR, `-Xserialize-ir`
  and lazily read bodies) into the cache, keyed by the Kotlin version and the platform layer's hash. 12 s to
  perhaps 2 s for a first compilation, every CI job and every new terminal. High effort, unknown.
- [ ] **The library's IR kept between programs** in the process, not only its analysis (0.4 s per program now).
- [ ] **Only the library files a program can reach** handed to the front end (tried once, fragile; with a
  reachability over the library's declarations computed once and cached it may hold).
- [ ] **Incremental translation**: the KIR and the C of each function cached by the hash of its IR and of what
  it depends on, so a change of one function translates one function. One C file per Kotlin file (or per
  class, as now) with stable names makes the C side follow.
- [ ] **Passes in parallel**: the passes after the importer work function by function but for the
  whole-program ones (`Devirtualize`, `EscapeAnalysis`'s summaries); run them on all cores.
- [ ] **C**: a precompiled header for `ktc2_core.h` and the types header; `ccache`/`sccache` when found; the
  unity build (`ktc2_all.c`) measured on Windows; LTO only for release builds (it is the default for `--build`).
- [ ] **Smaller C**: fewer C files for small classes (one file per package for classes under a size), the
  instantiations that compile to the same code shared (5.4).
- [ ] **Interfaces with a source** keep their dispatchers in the interface's own unit: `kotlin/coroutines/Continuation.c`
  is 10 MB in the all-in-one program (`resumeWith` over every continuation), though clang's `-O2` takes 2 s on it:
  only if one grows to cost time. The same split as the library's classes
  without a source (a directory `Continuation__dispatch/`, a file per dispatcher) when the unit grows past a size.
- [ ] **A release with symbols** from `ktc2 --build` (a new setting, `-g` with `-O2`; there is none yet), with the
  progress of each file as `--build` and CMake show it: the release and the debug program of every test were built by a script
  of the moment (`-O2 -g -flto=thin`, `-O0 -g -DKTC2_LEAK_CHECK`). Its debug build ran all 6,488 tests and passed.

---------------------------------------------------------------------------
## 7. Other proposals
---------------------------------------------------------------------------

### 7.1 A cycle collector (decided 2026-11-21: none)
No general collector, in the runtime or as an option: the language stays reference counting with warnings, as Swift;
a library's internal cycles are its module's, by hand; the user's are warnings ("Who answers a cycle", at the top; the
work is section 2.2). A collector that only names leaked cycles in test builds (no freeing) stays possible as tooling:
`KTC2_CYCLES=1` does most of it already.

### 7.5 ktc2.coroutines (the owner, 2026-11-21: after kotlinx.coroutines works)
An implementation of coroutines of ktc2's own, with an API of its own, that runs on the JVM and Kotlin/Native too (as
the `ktc2.` package does, `compat/`), and costs on ktc2 what counting makes cheap. To be proposed in full before
anything is built; the directions:
- No cycles by design: a scope owns its tasks, a task refers to its scope without a count (it cannot outlive it);
  no lists of handlers that name their job; cancellation is a flag the scope sets, not an exception that names the job.
- Channels own their waiting tasks one way only; a finished task's frame comes from its scope's arena (`ktc2.arena`).
- No pair checks, rings or knots: none of kotlinx.coroutines' runtime parts (`KTC2_KNOTS` off).
- Measured against kotlinx.coroutines on ktc2, Kotlin/Native and the JVM (`tools/compare.py`).

### 7.2 Ways to use ktc2
- [ ] **A Gradle plugin**: a ktc2 target in a Kotlin Multiplatform project (`kotlin { ktc2() }`), its
  `commonMain` and `ktc2Main` compiled by ktc2, `ktc2Test` run by the test mode. This is how most Kotlin code
  would reach it.
- [ ] **Prebuilt modules**: a module compiled once to KIR (or IR) in the cache, as a klib is, instead of its
  sources analysed with every program (section 6, second item, is the same work).
- [ ] **A library as output**: a C library with a header (`ktc2 --lib`), for C, Swift and Python to call; the
  first step to Android (a shared library entered from Java) and to iOS.
- [ ] **Debugging**: `#line` directives to the Kotlin sources (gdb and lldb step through Kotlin), stack traces
  with Kotlin names for `Throwable.stackTrace` in debug builds.

### 7.3 Finding differences faster
- [ ] **Differential testing in bulk**: every box test of the Kotlin compiler run on the JVM and in C, the
  differences listed (a nightly job), as `tools/native.sh` does with Native.
- [ ] **Unit tests of the passes** on KIR (`EscapeAnalysis`, `ArcInsert`, `Effects`, `BorrowedReturns`), where a
  small wrong answer is a use of freed memory.
- [ ] **AddressSanitizer and ThreadSanitizer as categories of the suite** on Linux (ASan runs in CI on the
  programs only).

### 7.4 Platforms
- [ ] Android (the NDK's clang, the log for `println`, a shared library entered from Java).
- [ ] WebAssembly through C (wasi-sdk's clang): ktc2's C is portable C11 with a small platform layer.
- [ ] MSVC without gcc or clang for the bindings (`cl /E`).

---------------------------------------------------------------------------
## 8. The owner's rules and decisions
---------------------------------------------------------------------------

**The rule** (2026-11-13, 2026-11-15): plain Kotlin behaves as on Kotlin/Native; ktc2 is a native target and
what Native gives native code ktc2 gives too, so that libraries' `nativeMain` compiles as it is. Where the JVM
and Native agree the tests check against the JVM; where they differ, ktc2 does as Native and the test says so.
A difference from both is a bug, fixed in general. Memory is the one difference allowed, never results: a cycle
is answered by a module's `weak`/`acyclic`/`cycles`/`rings` or an edit where the library's design makes that
harmless, a bounded cache, or a known leak written here; never a weak reference that changes what a program
computes. What ktc2 adds is in the `ktc2.` package and changes storage and cost only; any addition to it is
proposed first.

**Decided** (the date when it was):
- clang is the C compiler ktc2 prefers on every system; gcc and MSVC keep working (2026-11-17).
- The standard exceptions have their Kotlin names; nothing of `java.*` for programs; files and the like
  through kotlinx-io (2026-11-13).
- Coroutines are state machines, as on Native; the library's own `sequence { }` functions stay plain
  iterators of the platform layer (2026-11-14).
- A class as a value and `typeOf<T>()` are in; the rest of reflection is not (2026-11-14).
- What only the JDK has is not added (`String.format`, `toSortedMap`...); `removeFirst`/`removeLast` stay
  (Kotlin's own extensions) (2026-11-13).
- A serializer of a generic class and its descriptor are kept in a cache by class and arguments (2026-11-15).
- `assert()` is off unless `-ea`, as on Native (2026-11-16).
- What runs when an object ends is a cleaner (`createCleaner`); no `Deinit` (2026-11-14).
- kotlinx.coroutines as Native: `Dispatchers.Default` a pool of as many threads as processors started on first
  use, `Dispatchers.IO` the same pool up to 64; its cycles answered in the library by edits, after atomicfu's
  atomics became fields of their owner (2026-11-18).
- Each bundled module pins the latest version of its library (2026-11-17).
- kotlinx.coroutines first, as it is (its API, the base Kotlin experience), and what it needs costs the programs
  that use it only: nothing of it in ktc2's base where it can be kept out, its runtime parts compiled into a program
  only when its modules declare cycles or rings (`KTC2_KNOTS`). A `ktc2.coroutines` comes after it: an implementation
  of ktc2's own, optimized for counting (no cycles by design), compatible with the JVM, Kotlin/Native and ktc2
  (2026-11-21; section 7.5).
- The last digit of `exp`, `pow`... is the C library's, as on Native (2026-11-14).
- No general cycle collector (2026-11-21): the four rules of "Who answers a cycle", at the top. A library's internal
  cycles are answered inside its module, by hand: for kotlinx.coroutines a reaper written in the module (section 2.2)
  that sweeps finished jobs at the points the library already runs code between continuations, with one small
  intrinsic of the base (the count of an object) compiled only where it is called. The user's cycles are warnings.

**Not differences** (either platform is right): two entries a `HashMap` hands out for one key are equal and not
the same object; the last digit of the C library's mathematics.

---------------------------------------------------------------------------
## 9. Measuring
---------------------------------------------------------------------------

- `python tools/bench.py [name] [--save f] [--compare f]`: time, allocations, retains, releases, size of
  `benchmarks/*.kt` with the installed tool (`build/bench/results.txt`). Compare the counts too after a change
  to the library or to `ExceptionLowering`: a pass that quietly stops applying shows there first.
- `python tools/compare.py [name] [--targets ktc2,native,jvm]`: against Kotlin/Native and the JVM
  (`./gradlew compareClasspath` once).
- `KTC2_SKIP=<passes>` leaves optimizations out; `KTC2_DEBUG_PASSES=1`, `KTC2_DEBUG_BORROW=1`, `--warn-heap`,
  `KTC2_TIMING=1` explain what a pass did and where compile time goes.
- `build/integration/arc-stats.txt` after a run of the suite, `tools/arc_compare.py` to compare two.
