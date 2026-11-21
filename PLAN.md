# ktc2 - plan

What to do next, in order, with estimates. `DESIGN.md` says how ktc2 works now; `README.md` is the user's view;
`tests/README.md` the tests. `docs/HISTORY.md` is the archive: the original design, the log of the work, the
plan and the kotlinx plan as they were kept until 2026-11-18 (for the reasoning behind a decision, never as a
list to follow). This file replaces them as the one list to follow (rewritten on 2026-11-18).

Each item: a test that shows it (a program under `tests/integration/`, or a common test), the change, the tests
that cover it, a commit. Estimates are days of work at the pace of the last weeks; nothing below is prototyped
unless it says so.

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
  kotlinx.serialization and its JSON pass but 27 files have `.cycles` (a serializer and its descriptor, kept
  in a cache by decision) and one is pending (`JsonUpdateModeTest`, loops in C).
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

### 2.1 Bugs found and not fixed (half a day)
- [ ] `WithTimeoutTest` leaves `lambda.F_t_3 -> TimeoutCoroutine.uCont -> lambda` (a suspend lambda's frame holds the
  coroutine that holds it as its continuation).
- [ ] `Any().hashCode() != Any().hashCode()` (`HashCodeNativeTest.hashCodeOfAny`): the first object is freed before
  the second is made, which takes its address, and the identity hash is the address. The suite's test builds keep
  freed objects aside before reuse, so it passes there; a release build of `ktc2 test tests/stdlib` fails it. The header
  has no room for a hash (8 bytes: count and type). Options: a salt mixed in, counted up at each free of an
  object whose hash was taken (a header bit says so); or a short quarantine of such blocks before reuse.

### 2.2 kotlinx.coroutines' cycles (3 to 5 days)
The owner's direction (2026-11-20): kotlinx.coroutines keeps its API and behavior, even at some cost; what answers its
cycles stays in its module (edits, declarations, a small runtime of its own if needed), not in ktc2's base. Measured on
the 26 files left with `.cycles` (2026-11-20, after the cycle of a dispatched continuation and the one kept for reuse
was declared: 1,578 such cycles to 55): the lists of handlers of finished jobs (`NodeList`, `ChildHandleNode`,
`ResumeOnCompletion`, `ListClosed`, `Removed`), some 450; a coroutine's frame that holds the coroutine itself
(`F_uCont -> itself`, inside `suspendCoroutineUninterceptedOrReturn`), 60: a fix of ktc2's state machines (a frame
field holding its own coroutine needs no count); channels and broadcast, a few dozen.
- [ ] A frame field that holds its own coroutine (`lambda.F_uCont -> lambda`, `withContextUndispatched.F_uCont`).
- [ ] A completion hook of the module: what a finished job lets go of, at the points the library defines (its final
  state and its children done), as edits; the handler lists stay (another thread may still walk them).
The 16 files of the library's own structures, by kind (each file's `.cycles` names it):
- [ ] Knots whose garbage is never seen whole from where a check starts (2026-11-19; most of `AsyncTest`'s, after the
  knots of `regressions/coroutines-incomplete-async` were answered): a deferred that ended with an exception (a declared
  cycle) is named by the node `await` left in its old list of handlers (a ring). When the list goes, the deferred is
  still held by the test; when the test lets go, the check starts from the deferred, which does not reach the list.
  Proposal: a check that fails only because a member is held from outside leaves a note on that member (a bit of the
  count, which has 28), and that member's next release checks again from where the first started (kept in a small
  table, its entry dropped when the start goes).
- [ ] A coroutine of a channel (`produce`, and flows built on it) cancelled: its exception is kept both as its
  state and as the channel's cause of closing, and names the coroutine. Two cycles through one object; neither
  declared cycle frees it, and a ring of the four classes made it worse (tried, taken out). Options: a check
  that follows several declared cycles through a shared object (a generalization of `KPairCycle` to "every
  reference to each object of the set comes from the set", which is what rings do for lists); or an edit that
  closes the channel with a copy of the exception that does not name the job. Recommended: the first, it is
  the general answer and the next item needs it too.
- [ ] A failed `runBlocking` (and `coroutineScope`, `flowScope`) whose exception names a scope coroutine that
  keeps its caller's continuation (`ScopeCoroutine.uCont`). An edit that lets go of `uCont` once the scope has
  resumed its caller touches the five subclasses; or the general check above.
- [ ] A lazy coroutine cancelled while it is being made (its parent already cancelled): cancelled in the
  superclass's constructor, before the subclass keeps its block. The edit of `Builders.common.kt` can test
  `isCancelled` where it keeps the block.
- [ ] The segments of a channel or a semaphore (`_next`, `_prev`) of a channel left unclosed, and a
  `select`'s clauses (inner objects in its list): rings to declare (`rings`), as the job lists are.
- [ ] The lists of handlers of jobs that end on several threads at once (`ConcurrentExceptionsStressTest`): the
  rings of jobs with several handlers in threaded programs, known since 2026-11-18.
- [ ] The 11 files whose tests keep a continuation in a variable their own coroutine captures, or keep the
  deprecated `BroadcastChannel`'s subscriptions: nothing in the library to answer; only a collector frees them.
  This is the decision of section 7.1.

### 2.3 kotlinx.coroutines: what is not taken yet (3 to 5 days)
- [ ] `native/test` (workers, `MultithreadedDispatchersTest`, `DelayExceptionTest`): Native's own tests.
- [ ] `kotlinx-coroutines-test` as a second module (`runTest`, virtual time, `TestDispatcher`): many programs
  and libraries test with it; its own tests then.
- [ ] The 20 pending box tests of coroutines (`tests/box/pending/coroutines`: suspend conversion, callable
  references to suspend functions, value classes spilled in a state machine, tail calls), now that
  `String::plus` as a `suspend` type works: some may pass already.
- [ ] A `suspend fun interface`.

### 2.4 kotlinx.serialization (2 to 4 days)
- [ ] The 27 `.cycles`: a serializer and its descriptor refer to each other through a field typed by an
  interface (`PluginGeneratedSerialDescriptor.generatedSerializer`). Decided (2026-11-15): kept in a cache by
  class and arguments; the tests that make them still leak. The pair freeing through a field typed by an
  interface (the partner's class checked at run time) would free most.
- [ ] `JsonUpdateModeTest` loops in C (`.pending`).
- [ ] The three skipped tests (`ModuleBuildersTest`, two of `JsonDecodingErrorMessagesTest`): the first is the
  JVM side's message without kotlin-reflect, the others a stream reader's message; to check against Native.

### 2.5 The standard library (2 to 3 days)
- [ ] The 7 `.cycles` of `tests/stdlib/` (read each reason; most are Regex and coroutine helpers).
- [ ] `AnyTest.testToString`: the default `toString()` of a local class names it with dots; Native and the JVM
  use `$`. A second name per class in the runtime's table.
- [ ] `MapTest.nullKeyAndValue`: a `null` put through an unchecked cast into a map that keeps its values as
  numbers. Accepted as a difference unless generics of numbers are kept boxed where a cast can reach them.
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
- [ ] **Dispatchers of a function type sized by what can reach them** (2 to 3 days; first in this list for a large
  program). Every test as one program (`KTC2_TEST_CATEGORIES=all`, 15,800 C files) had all but 7 of its files compiled
  at `-O2` within 10 minutes on 32 threads; the 7 left are dispatchers of `Function2.invoke` (`kotlin/Function2__dispatch/invoke__Any_Int_IntOpt__v.c`), about
  6 minutes each: a dispatcher of one signature (`(Any, Int) -> Int?`) has a case for each of the 3,120 lambdas of
  `Function2` in the program, most of another signature, each with the boxing and the class checks that adapt it.
  Leaving the cases of another signature out would break programs: generics are erased, ktc2 takes `Any` for an
  argument it does not know, and an unchecked cast or a `Function2<*, *, *>` brings a `(String, Int) -> Int` to that
  dispatcher legitimately (its case checks for a `String` and throws `ClassCastException`, as the JVM does).
  - Recommended: each lambda class gets one erased entry (`invoke` with every parameter and the result boxed), its
    address in the class's type table (`ktc2_types[]`); a dispatcher keeps direct cases for the classes whose own
    signature is its own (or differs only by boxing it can do inline), and sends every other class through the
    erased entry, one indirect call, its result unboxed. The same answers, a few cases instead of thousands, and
    one adapter per lambda instead of one per lambda and signature. Kotlin/Native's tables work this way.
  - Or: prune by flow: a class keeps its case in a dispatcher only if the program converts one of its instances
    (a cast, an argument, a generic instantiation) to a type whose dispatcher it is. Smaller still, but every
    conversion has to be found, through monomorphization; a miss is a crash (`ktc2_fail_dispatch_of`) where the
    program worked.
  - Or: keep the cases and move each adapter out of line, shared by every dispatcher (a function per lambda and
    signature): smaller dispatchers, the same number of functions. The least change, the least gain.
  Measure with the all-in-one program: its C, the time of the slowest files at `-O2`, the size of the program, and
  `tools/bench.py` for the cost of the indirect call (a lambda called through another signature is rare).
- [ ] **Interfaces with a source** keep their dispatchers in the interface's own unit: `kotlin/coroutines/Continuation.c`
  is 10 MB in the all-in-one program (`resumeWith` over every continuation). The same split as the library's classes
  without a source (a directory `Continuation__dispatch/`, a file per dispatcher) when the unit grows past a size.
- [ ] **A release with symbols** from `ktc2 --build` (a new setting, `-g` with `-O2`; there is none yet), with the
  progress of each file as `--build` and CMake show it: the release and the debug program of every test were built by a script
  of the moment (`-O2 -g -flto=thin`, `-O0 -g -DKTC2_LEAK_CHECK`). Its debug build ran all 6,488 tests and passed.

---------------------------------------------------------------------------
## 7. Other proposals
---------------------------------------------------------------------------

### 7.1 A cycle collector as a backup (the owner's decision; 1 to 2 weeks)
Everything left in sections 2.2 to 2.5 is memory Kotlin/Native's collector frees and ktc2's counting does not
without a declaration in a module. Options:
1. None (now): declare each library's cycles (`weak`, `acyclic`, `cycles`, `rings`, edits), and accept the
   tests whose own design makes cycles (11 files). Exact and cheap, but every new library brings its own work,
   and a user's program can leak where Native's would not.
2. A collector that runs in test and debug builds only, to name every leaked cycle precisely (no freeing):
   the tooling for option 1.
3. **A backup collector in the runtime, by trial deletion** (as CPython's and PHP's: the objects whose count
   went down without reaching zero are candidates; from time to time, or when memory grows, their subgraph is
   scanned and what only it holds is freed). Exact, no pauses for programs without cycles, a cost per release
   of a candidate. The declarations stay as optimizations. Recommended, as an option of the build (on by
   default, `--no-cycle-collector` for programs that know they have none): it makes "every test passes" reachable
   and Kotlin code behave as on Native without a module file.

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
- The last digit of `exp`, `pow`... is the C library's, as on Native (2026-11-14).

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
