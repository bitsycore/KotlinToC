# ktc2 - plan

The one list to follow, in order. It holds no log: an item done leaves it for a line in `docs/HISTORY.md` (the
archive: the original design, the log of the work, older plans), and its description goes to `DESIGN.md` (how ktc2
works now). `README.md` is the user's view, `tests/README.md` the tests, `CLAUDE.md` how to work here. Rewritten
2026-11-22.

How to take an item: a test that shows it (a file under `tests/ktc2/<topic>/`, a program under `tests/integration/`,
or a regression under `tests/ktc2/regressions/` for a bug found on the way), the change, the smallest set of tests that
covers it while working (`KTC2_TEST_FILTER=...`), the whole suite before the commit (`build/suite-all.ps1` on Windows,
`./gradlew test --rerun --no-configuration-cache`), a commit (author `Bitsy <bitsydoge@proton.me>`, no mention of an
assistant). Push only when the owner asks. When a decision is the owner's, propose two or three options with a
recommendation; when in doubt, do as Kotlin/Native.

---------------------------------------------------------------------------
## 0. The owner's principles (read before anything)
---------------------------------------------------------------------------

**Plain Kotlin behaves as on Kotlin/Native** (2026-11-13, 2026-11-15). ktc2 is a native target: what Native gives
native code ktc2 gives too, so that libraries' `nativeMain` compiles as it is. Where the JVM and Native agree the tests
check against the JVM; where they differ ktc2 does as Native and the test says so (an `.expected` file). A difference
from both is a bug. Memory is the one difference allowed, never results. What ktc2 adds is in the `ktc2.` package,
changes storage and cost only, and is proposed to the owner first.

**The runtime a program needs, and no more** (2026-11-21). What a library needs costs the programs that use it only:
its parts of the runtime are options of the program's `ktc2_config.h`, written by the emitter from what the program
has (`KTC2_THREADS` when it starts a thread, `KTC2_KNOTS` when a module declares cycles or rings). A small addition to
the base is fine when plain Kotlin needs it.

**Who answers a cycle** (2026-11-21). No general cycle collector, in the runtime or as an option:
1. The language stays plain reference counting, as Swift: a program's cycles are its own, warned by the compiler
   where it can see them (`CycleLint`, `UserCycleLint`), saying what to write (`@Weak`, `WeakRef`, `@Acyclic`,
   clearing a variable). What no compiler sees (a list put into itself) is the program's.
2. A kotlinx library keeps its API and behavior.
3. Its internal cycles are answered inside its module (`weak`, `acyclic`, `cycles`, `rings`, edits of its sources,
   Kotlin of its own, the reaper), compiled only into the programs that use it.
4. What the user can fix with the library's API plus a `ktc2.` annotation or way of working is a compiler warning,
   never machinery; a library's rules never cut a cycle through the user's own objects.
No known leak (2026-11-22): a library's cycle is never left as an accepted leak.

---------------------------------------------------------------------------
## 1. Where it stands (2026-11-22, Windows)
---------------------------------------------------------------------------

- **Last green commit**: `a56495f6` (pushed; CI green on Linux, macOS, Windows, MSVC and AddressSanitizer on Linux).
- **The commit after it is not green**: the first box tests beyond coroutines (section 2.1) with some twenty fixes.
  The whole suite has 3 groups failing, listed in 2.1: fix them first.
- **The suite**: five categories, about 125 programs and groups: `integration` (50 files), `ktc2` (143 files of
  ktc2's own tests), `stdlib` (180 files of the standard library's tests and 17 of Kotlin/Native's runtime tests),
  `box` (1,082 of the Kotlin compiler's box tests: the coroutines' 425 and 657 more) and `modules` (557 files of the
  bundled libraries' own tests). No `.pending`; the `.skip` lines each state a difference the owner accepted or has to
  decide (2.2); the `.cycles` files are tests that make cycles of their own.
- **The libraries**: kotlinx.coroutines (with -test), kotlinx.serialization (with JSON and json-io), kotlinx-io,
  kotlinx-datetime, kotlinx.collections.immutable and atomicfu are bundled as they are and pass their own tests in C
  and on the JVM, with no leak.
- **Speed** (2026-11-17, a Mac): fastest of ktc2, Kotlin/Native and the JVM on 9 of 10 benchmarks, 1.2 to 4.2 times
  Native, 1.3 to 8.5 times less memory. **Compile time**: 9 to 13 s for a first program, 0.4 to 1.5 s after a change
  through the compiler process, plus the C.

---------------------------------------------------------------------------
## 2. Next, in order: the first working version (every test passes)
---------------------------------------------------------------------------

The objective (2026-11-18): every test of the Kotlin standard library, of each bundled kotlinx library, of the Kotlin
compiler's box tests that Native runs, and of ktc2's own passes in C as on Kotlin/Native.

### 2.1 Make the current work green (hours) - FIRST
The commit after `a56495f6` took the box folders `basics`, `when`, `controlStructures`, `classes`, `closures`,
`strings`, `dataClasses`, `enum` (`docs/HISTORY.md` lists what they found and what was fixed). Its last runs:
- [ ] `ktc2/values` (`tests/ktc2/values/`): the importer crashes (`IndexOutOfBoundsException` in `callFunction`, from
  `BodyBuilder.buildEnumInitializer`, `BodyLowering.kt`). Cause: the new handling of an enum entry whose initializer is
  a block (named arguments in another order, `box/enum/constructorWithReordering`). Find the entry of `tests/ktc2/values`
  that crashes (likely a `@SimpleEnum`, or an entry with a body) and narrow the block case to the reordered arguments.
- [ ] `box/basics/unchecked_cast8`: `AbstractMethodError` (a getter of an interface not found on a class) after an
  unchecked cast. Newly reached, not a regression: fix, or `.skip` with the reason if it is the family of 2.2.
- [ ] `box/classes/initializerBlockDImpl`: leaks one `World`, its `ArrayList` and an array. Newly reached: find the
  cycle (`KTC2_CYCLES=1`), fix, or a `.cycles` file if it is the test's own (principle 1).
- [ ] Then the whole suite, a commit, the push when the owner asks, and the CI run read (`gh run list --repo
  bitsycore/KotlinToC`).
Tools: `bash tools/box_one.sh "<regex of paths>" [lines]` builds and runs box tests one by one with the installed tool
(`./gradlew installDist` first); `python tools/failures.py` after a run; the generated C under `build/boxp/<name>/out/c`.

### 2.2 Unchecked casts: a decision of the owner (then 1 day)
ktc2 keeps numbers and the element type of an array or collection where Kotlin erases them, so a value put in through
an unchecked cast is caught where it enters (`box/basics/kt7972`: a String added to an `ArrayList<Int>` throws at
once, Native throws when it is read; `MapTest.nullKeyAndValue` of the stdlib), at an unboxing that ends the program
(`box/basics/unchecked_cast7` and `11`: `ktc2_fail_cast`, not catchable), or not at all (`box/basics/unchecked_cast5`,
`6`, `6x`: an element of a String array cast to `Array<Data>` read as a `Data`, which is unsafe memory). All are
`.skip` lines naming this. Options:
- (a) leave them skipped as accepted differences;
- (b) check each element when an array is cast to an array of a narrower element type, and make a failed unboxing a
  catchable exception (a test after each unboxing of an object of unknown kind: some cost everywhere);
- (c) (b) for arrays only. Recommended: (c), it removes the one unsafe case at the cost of the casts that need it.

### 2.3 The rest of the box tests (1 to 3 weeks, folder by folder)
5,058 box tests outside coroutines are taken by `tools/select_box.py` (those every back end runs, without several
files, language options or the JVM). 657 are in. To take a folder: the clone (`git clone --depth 1 --filter=blob:none
--sparse -b v2.5.0-RC https://github.com/JetBrains/kotlin build/kotlin-src`, then `git sparse-checkout set
compiler/testData/codegen/box` in it), `python -I tools/select_box.py build/kotlin-src/compiler/testData/codegen/box/<dir>
tests/box/<dir>`, `KTC2_TEST_CATEGORIES=box KTC2_TEST_FILTER=tests/box/<dir>`, fix (a regression test for each bug),
the whole suite, a commit per folder or per few. A file that does not compile stops its whole group: look at the
`error:` and `not supported yet` lines of the log first. Folders by size, the order to take them:
- [ ] `ranges` (791), `inlineClasses` (704), `callableReference` (244), `inference` (188), `bridges` (135),
  `delegatedProperty` (127), `functions` (96), `primitiveTypes` (94), `objects` (91), `properties` (90), `arrays` (76),
  `defaultArguments` (71), `regressions` (61), `diagnostics` (57), `innerNested` (50), `extensionFunctions` (49),
  `casts` (48), `versionOverloads` (40), `unsignedTypes` (40), `localClasses` (39), `funInterface` (39), `super` (38),
  `annotations` (38), `function` (36), `smartCasts` (35), `ieee754` (35), `delegation` (35), `traits` (31),
  `operatorConventions` (31), `extensionProperties` (29), `secondaryConstructors` (28), `boxingOptimization` (27),
  and the some 60 smaller folders (`python -I tools/select_box.py` on the whole `box` directory lists them).

### 2.4 ktc2's own tests as assertions (2 days)
- [ ] `tests/ktc2/` (143 files): more of what must hold everywhere as assertions, where a test now only prints and is
  compared with the JVM.

### 2.5 Every system (1 to 2 days, mostly waiting)
- [ ] The whole suite once on the Mac and on Linux (WSL), AddressSanitizer on the coroutines' tests
  (`tools/asan_all.sh kotlinx-coroutines`).
- [ ] Paths and arguments on Windows as wide characters (now bytes, right for ASCII only).

---------------------------------------------------------------------------
## 3. The project: cleanup (proposals, each the owner's to decide)
---------------------------------------------------------------------------

- [ ] **Gradle** (1 to 2 days). Recommended: one project for the tool (`compiler/`, with `compat` as a source set),
  `ide/` generated by a task, the suite's `KTC2_TEST_*` read at execution so the configuration cache never keeps a
  stale filter. Or keep the three projects and document why each exists.
- [ ] **Test settings** (2 days): one settings file per test or directory (`<Name>.test.toml` or `tests.toml`) in
  place of the eleven kinds of files next to a test (`.skip .vary .cycles .pending .jvm .native .expected .in .def
  .errors .warnings`).
- [ ] **The libraries' tests fetched** at the module's version like their sources (a `tests` key in
  `module.ktc2.toml`), only their settings kept in the repository. Recommended; or keep them copied (offline,
  greppable).
- [ ] **The harness reduced** to running `ktc2 test` and comparing with the JVM; the build, the run and the leak
  check by file move into the tool.
- [ ] **Tools** (1 day): what a user needs becomes `ktc2` commands (`ktc2 bench`, `ktc2 asan`), the suite's helpers
  one Python entry (`tools/dev.py failures | jvmdiff | native | msvc | arc-compare`); `try.sh` and `try_cmake.sh` go.
  `tools/box_one.sh` (2.1) becomes one of them.
- [ ] **The test mode**: `--isolate` (each test in a process of its own), `--report junit.xml`, leaks named by test,
  `tools/native.sh` on the groups (one program each with Native's `-generate-test-runner`).

---------------------------------------------------------------------------
## 4. Speed of programs (after the first working version)
---------------------------------------------------------------------------

Go and Swift are the references. By expected gain, each measured first (`tools/bench.py`, `tools/compare.py`):
- [ ] **Measure against Go and Swift** (2 days): the benchmarks in both, and the classic ones (binary-trees, n-body,
  spectral-norm, fannkuch-redux, mandelbrot, fasta, k-nucleotide, regex-redux, a JSON round trip, a channel ping-pong,
  a `HashMap` of strings).
- [ ] **Allocation**: an object that never leaves its function becomes plain locals; escape analysis through
  dispatchers and across the program (a summary per function); inlining in KIR before the analyses; the array inside
  a local `ArrayList`/`StringBuilder`/`HashMap` in the frame; regions inferred for loop bodies; coroutine frames in the
  caller's frame when awaited at once.
- [ ] **Counting**: non-atomic counts for objects no other thread can reach; retains and releases moved and paired
  across blocks; owned parameters through dispatchers; immutable objects counted less.
- [ ] **Values and generics**: small immutable classes as values without an annotation; generics instantiated for hot
  reference types; one implementation for instantiations that compile the same.
- [ ] **Calls and checks**: exceptions in the return value instead of the pending slot; devirtualization by the
  element class of collections and by profile; bounds checks with steps and on strings; profile-guided builds.
- [ ] **Library**: `Double` and `Float` as text by Ryu; `String.hashCode` kept; `sortedBy` without boxing; a
  dispatcher of ktc2's own for `Dispatchers.Default`.
- [ ] **Annotations to propose** (the owner's to accept, storage and cost only): `@ThreadConfined`, `@NoEscape`,
  `@Unboxed`, `@Region`.

---------------------------------------------------------------------------
## 5. Compile time
---------------------------------------------------------------------------

- [ ] The library's analysis kept across processes (serialized into the cache): 12 s to perhaps 2 s for a first
  compilation. High effort.
- [ ] The library's IR kept between programs in the process (0.4 s per program now).
- [ ] Only the library files a program can reach handed to the front end (a reachability computed once and cached).
- [ ] Incremental translation: KIR and C of each function cached by the hash of its IR and what it depends on.
- [ ] Passes in parallel, function by function.
- [ ] C: a precompiled header for `ktc2_core.h`, `ccache` when found, LTO for release builds only; fewer C files for
  small classes; big interface dispatchers split when they grow.
- [ ] A release with symbols from `ktc2 --build` (`-g` with `-O2`).

---------------------------------------------------------------------------
## 6. Other proposals
---------------------------------------------------------------------------

- [ ] **ktc2.coroutines** (2026-11-21: after kotlinx.coroutines works, which it does): coroutines of ktc2's own, with
  an API of their own that runs on the JVM and Native too (as the `ktc2.` package does), without cycles by design (a
  scope owns its tasks, a task refers to its scope without a count, cancellation a flag), no runtime parts
  (`KTC2_KNOTS` off), measured against kotlinx.coroutines. To be proposed in full to the owner before anything is built.
- [ ] **A Gradle plugin**: a ktc2 target in a Kotlin Multiplatform project (`kotlin { ktc2() }`).
- [ ] **Prebuilt modules**: a module compiled once to KIR in the cache, as a klib.
- [ ] **A library as output** (`ktc2 --lib`): a C library with a header, the first step to Android and iOS.
- [ ] **Debugging**: `#line` directives to the Kotlin sources, stack traces with Kotlin names in debug builds.
- [ ] **Finding differences**: every box test run in bulk on the JVM and in C nightly; unit tests of the passes on
  KIR; AddressSanitizer and ThreadSanitizer as categories of the suite on Linux.
- [ ] **Platforms**: Android (the NDK's clang), WebAssembly through wasi-sdk, MSVC without gcc or clang for the
  bindings.

---------------------------------------------------------------------------
## 7. The owner's decisions (with their dates)
---------------------------------------------------------------------------

- clang is the C compiler ktc2 prefers on every system; gcc and MSVC keep working (2026-11-17).
- The standard exceptions have their Kotlin names; nothing of `java.*` for programs; files and the like through
  kotlinx-io (2026-11-13).
- Coroutines are state machines, as on Native (2026-11-14).
- A class as a value and `typeOf<T>()` are in; the rest of reflection is not (2026-11-14).
- What only the JDK has is not added (`String.format`, `toSortedMap`...) (2026-11-13).
- What runs when an object ends is a cleaner (`createCleaner`); no `Deinit` (2026-11-14).
- `assert()` is off unless `-ea`, as on Native (2026-11-16).
- The last digit of `exp`, `pow`... is the C library's, as on Native (2026-11-14).
- Each bundled module pins the latest version of its library (2026-11-17).
- kotlinx.coroutines as Native: `Dispatchers.Default` a pool of as many threads as processors, `Dispatchers.IO` the
  same pool up to 64 (2026-11-18).
- kotlinx.coroutines first, as it is, at its own cost; `ktc2.coroutines` after it (2026-11-21).
- No general cycle collector; the four rules of "Who answers a cycle" (2026-11-21); no known leak (2026-11-22).
- Accepted difference: `MapTest.nullKeyAndValue` (a null put through an unchecked cast into a map of numbers); the
  family of unchecked casts is open (2.2).
- Not differences (either platform is right): two entries a `HashMap` hands out for one key are equal and not the
  same object; the last digit of the C library's mathematics.

---------------------------------------------------------------------------
## 8. Measuring
---------------------------------------------------------------------------

- `python tools/bench.py [name] [--save f] [--compare f]`: time, allocations, retains, releases, size of
  `benchmarks/*.kt`. Compare the counts too after a change to the library or to `ExceptionLowering`.
- `python tools/compare.py [name] [--targets ktc2,native,jvm]`: against Kotlin/Native and the JVM
  (`./gradlew compareClasspath` once).
- `KTC2_SKIP=<passes>`, `KTC2_DEBUG_PASSES=1`, `KTC2_DEBUG_BORROW=1`, `--warn-heap`, `KTC2_TIMING=1`.
- `build/integration/arc-stats.txt` after a run of the suite, `tools/arc_compare.py` to compare two.
