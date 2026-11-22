# The tests

The suite (`./gradlew test --rerun`) has five categories, one for each folder under `tests/`, which
`KTC2_TEST_CATEGORIES` chooses from (all by default). The log names the category on each line and the path there,
lists the files of each group under it (✔ or ✘, and under a file that failed its tests that did), and counts the
categories apart; at its end, the steps added over all programs and the run's own time:

```
[ 87/584  14%] modules     ✔ kotlinx-serialization-json-io/json (126/126 files)  65.4 s  5.2 · 16.2 · 29.1 · 5.2
        ✔ commonTest/kotlinx/serialization/json/JsonParserTest.kt
[481/584  82%] integration ✔ interop/Geometry.kt   1.1 s  0.3 · 0.1 · 0.5 · 0.1
```

(the times of the steps: Kotlin → IR, IR → C, C → exe, run.)

- `integration` (`tests/integration/`): whole programs, each with its own `main`, compared with the JVM or with an
  `.expected` file, for what only a program shows: `interop/` (C libraries, `.def`), `errors/` (programs ktc2 must
  refuse), `native/` (output that is Kotlin/Native's where the JVM's differs), `process/` (standard input,
  `exitProcess`, a warning of the compiler, threads left running at the end); and the programs of several modules
  (`projects/`, each a directory with a `module.ktc2.toml`, whose line lists its own files; and `regressions/`);
- `ktc2` (`tests/ktc2/<topic>/`): ktc2's own tests, written as Kotlin's common tests are, a group for each topic:
  `language`, `numbers`, `text`, `collections`, `objects`, `generics`, `memory`, `threads`, `coroutines`, `values`
  (the `ktc2.` package), `regressions`;
- `stdlib` (`tests/stdlib/`): the Kotlin standard library's and Kotlin/Native's runtime's own tests, a group;
- `box` (`tests/box/<folder>/`): the box tests of the Kotlin compiler, a group for each folder of its tree;
- `modules` (`tests/modules/<module>/<library>/`): the own tests of the bundled libraries, a group each.

The CI job runs `integration`, `ktc2`, and `stdlib` in C only (`KTC2_TEST_NO_JVM=stdlib`: a group of tests checks
itself, the JVM is only a second opinion). The box tests and the libraries' tests run on the machines where the work
is done. `examples/` are not tests: they are built and run by hand.

# ktc2's own tests

A file of `tests/ktc2/<topic>/` is in a package of its own (`ktc2test.<topic>.<file>`): the files of a topic are
compiled together, and many declare the same names. A test about what a program prints has its `@Test` function
print it: the suite compares what each test prints with the JVM, test by test. They were programs with a `main` until
2026-11-19 (`tests/integration/basic`, `objects`, `stdlib`, `values`, `generics`, and `ported`, the plain-Kotlin
tests of the first transpiler), made tests and sorted by topic then (small files of one subject merged:
`language/Loops.kt`, `objects/Enums.kt`...); `language/TestClasses.kt` checks what `ktc2 test` registers. What only a
whole program can check goes to `tests/integration/`.

# Common tests

Tests written as Kotlin Multiplatform's common tests are: classes with `@Test` functions that
assert with `kotlin.test`, and no `main`. A test says what must hold on every platform, and
nothing of what a platform is free in (the text of a message, the name of an exception's
package). The same file runs:

- in C and on the JVM, by the suite (`./gradlew test --rerun`, `KTC2_TEST_FILTER=tests/stdlib/,tests/ktc2/`), by
  groups (below), and a test that fails on either fails the suite. The log has a line per group (its tests passed
  out of how many, its time and the time of each step), and lists the tests of a group, by class, only when one of
  them failed;
- with `ktc2 test <directory or files>` (`README.md`), which is what the suite does in C;
- on Kotlin/Native, by `tools/native.sh tests/` after a run of the suite, with Kotlin/Native's
  own `kotlin.test`.

`kotlin.test` is the bundled module `kotlin-test` (`modules/kotlin-test`): Kotlin's own library, its common
sources and Kotlin/Native's `actual`s of them, with Native's test runner. On the JVM the suite compiles the
tests with the JVM jar of kotlin-test (the same assertions and messages, `DefaultAsserter`) and the four
annotations of `tests/support/KotlinTestAnnotations.kt`.

A library's `nativeTest/` (and `nativeCommonTest/`) files are compiled into the C program only, as Kotlin/Native's
own tests: their tests have no JVM side and are decided by their own assertions.

## Groups

The files of tests are compiled by directory, each group one program, as `ktc2 test` compiles them: `tests/stdlib`
(with `support/`), each topic of `tests/ktc2`, and for each library of `tests/modules/<module>/<library>/` its `commonTest` and
`concurrentTest` (`IntegrationTest.kt`, "Groups of tests"). ktc2's compiler registers every class of tests (and the
top-level `@Test` functions of a file) as a suite of Kotlin/Native's runner, whose `main` the program has
(`TestRegistry.kt`); it prints what Native's prints (`[ RUN      ] Class.test`, `[       OK ]`...), and takes its
options (`--ktest_filter=Class.*:Other.test`, `--ktest_list_tests`). The JVM side compiles the same files and runs
`tests/support/Ktc2JvmTestRunner.kt` on the list the C program gives, in its order; the two are compared test by
test: each test passes in C, passes on the JVM, and prints the same lines on both.

A group compiles once where each file was a program compiled with all the others (kotlinx.coroutines: 170
compilations of 180 files, some 25 minutes; now one, 3 minutes). What a file says next to it is said by file:
`.skip` (its tests not registered), `.pending` (its tests not run; the file is still compiled, for what others use
of it), `.vary` (lines left out of the comparison), `.native` (its tests may fail on the JVM), `.jvm` (options of
the JVM compilation, of the whole group), `.cycles` (it may leave objects in a cycle). A run that leaks or crashes
is run again file by file (`--ktest_filter` of each file's suites, without compiling again), to say which file
did it: only a file without `.cycles` that leaks fails. `build/integration/<group>/` has the program
(`program.exe`), what it printed (`program.out`), the JVM's (`jvm.out`), the list (`tests-list.txt`) and the file
of each suite (`tests.txt`).

## Regressions

`tests/ktc2/regressions/` holds a test for each bug found in ktc2 itself on the way (a miscompilation, a crash, a
wrong C), named after it, written before the fix; one that needs a module's settings or a library to show is a program
of its own module under `tests/integration/regressions/`. `KTC2_TEST_FILTER=regressions/` checks them all quickly.

- `tests/stdlib/`: tests of the Kotlin standard library itself, unchanged, from
  `libraries/stdlib/test` of github.com/JetBrains/kotlin at tag `v2.5.0-RC`, the version of
  the library ktc2 compiles (Apache 2.0, the header of each file). They are the best tests
  there are of what the library must do. 156 files, some 2230 tests: every file of `libraries/stdlib/test` but those of JavaScript. They are compiled as the
  library's own tests are: with its opt-ins (`kotlin.ExperimentalUnsignedTypes` and the others
  of its `commonTestOptIns`; `ktc2.optIn` in `compiler/build.gradle.kts` for C), and on the JVM seeing
  what the library keeps `internal`.
- `tests/stdlib/nativeTest/`: tests of Kotlin/Native's own runtime (`kotlin-native/runtime/test`) that the JVM cannot
  compile (`kotlin.native` APIs): compiled in C only with the group, decided by their own assertions. Their `actual`s
  answer the common tests too where Native's answer is ktc2's (`StringEncodingTestNative.kt`).
- `tests/stdlib/support/`: the helpers of the library's tests, compiled with the group `tests/stdlib/`.
  `support/common/` is theirs as they are (`testUtils.kt` without its `expect` line,
  `CollectionBehaviors.kt`, `ComparisonDSL.kt`, `jsCollectionFactories.kt`,
  `DurationTestUtils.kt`); `support/jvm/` and `support/ktc2/` answer what they leave to the
  platform, for each side. ktc2 answers as Kotlin/Native does (`TestPlatform.current` is
  `Native`, and its back-reference handling, as `kotlin-native/runtime/test/utils.kt`), since
  2026-11-16: where the JVM and Native differ ktc2 sides with Native (`DESIGN.md`, section 1).
  The JVM's side answers as the JVM's tests do; each side's run then checks its own platform. A line `expect ...` of a test file is left out when it is
  compiled (`support/*/Text.kt` answers those of `StringEncodingTest`).
- `tests/stdlib/<Name>.jvm`: options the compilation for the JVM needs for that file, one a line.
- `tests/stdlib/<Name>.vary`: the beginnings of the lines it prints that differ on every run (a random seed, a time), one a line: left out of the comparison. A line `/regex/` names whole lines (a duration, which has no fixed beginning).
- `tests/stdlib/<Name>.skip`: tests of `<Name>.kt` that are not run, one a line, `name: why` (or
  `Class.name: why`). Neither side runs them.
- `tests/stdlib/<Name>.cycles`: says, with the reason, that the tests of `<Name>.kt` build objects
  that refer to each other in a circle. Those are never freed, there being no collector of
  cycles, and for that file what is left allocated is not a failure (see Groups).
- A class of tests that extends another (`IterableTests.kt`: `ListTest : OrderedIterableTests`)
  runs the tests it inherits; an abstract one runs none itself.
- `pending/` (none at the moment): tests of the same origin that do not pass yet, each with the
  reason below. Not run. A test that does not pass has found a difference to fix; the file then
  moves to `tests/stdlib/`.

To take in more: get the tests (`git clone --depth 1 --filter=blob:none --sparse -b v2.5.0-RC
https://github.com/JetBrains/kotlin`, then `git sparse-checkout set libraries/stdlib/test`),
copy files to `tests/stdlib/`, run `KTC2_TEST_FILTER=tests/stdlib/ ./gradlew test --rerun`, and
read `python tools/failures.py`. A file from another tag than the library's fails on the JVM
too (two did, taken from `v2.4.21`).

Besides `libraries/stdlib/test`, `tests/stdlib/` has the files of two other directories of the same
tag that are not about one platform's own API, unchanged:
- of `libraries/stdlib/jvm/test`, the 3 that use nothing of the JDK nor of the JVM's own part of
  the library (`DurationJVMTest`, `NumbersJVMTest`, `SequenceJVMTest`). The others use
  `java.*` (`Character`, `Thread.sleep`, serialization) or what only the JVM's library has
  (`IEEErem` is in now, but `MathJVMTest` checks it against `java.lang.Math`; `binarySearch` of
  unsigned arrays, the internal `MapBuilder`, `assert`);
- of `kotlin-native/runtime/test`, the 20 that use nothing of Kotlin/Native's own API
  (`HashMapTest`, `ArrayListNativeTest`, `StringNativeTest`, `HarmonyMathTests`, `MathNativeTest`
  (its `MathTest.kt` renamed, the name being taken)...). Where Kotlin/Native and the JVM differ,
  ktc2 does as Kotlin/Native (since 2026-11-16), and the file has a `<Name>.native`: its run on
  the JVM may fail those tests, and the JVM is the reference only for the others
  (`StringNativeTest.native`, `UnsignedArraysNativeTest.native`). Not taken yet: `CharNativeTest`
  (`Char.toChars` and the like, Kotlin/Native's only: there since 2026-11-16, `Ktc2CharNative.kt`,
  so it can be taken now), `BitSetTest`, `ByteArrayTest`, the
  atomics, workers, `cinterop` and the other tests of Kotlin/Native's own API.

## Box tests

`box/` holds tests of the Kotlin compiler itself (`compiler/testData/codegen/box` at the same tag),
which every back end of Kotlin runs, Kotlin/Native's included: each file has a `fun box(): String`
that returns "OK". The suite compiles them by folder, a group each: each file copied in a package of its own
(`build/testmain/box/`), with a `@Test` that prints what `box()` returns and fails otherwise, run in C and on the JVM
(`KTC2_TEST_FILTER=tests/box/`). The files under `tests/box/` stay as the compiler has them. `box/helpers/` is
what they import (`import helpers.*`: `EmptyContinuation`, `handleResultContinuation`,
`StateMachineChecker`), from `compiler/testData/diagnostics/helpers/coroutines`.

`box/coroutines/` has the tests of `codegen/box/coroutines` that `tools/select_box.py` takes:
not those marked for one back end or ignored on Kotlin/Native, not those of several files or
modules (`// FILE:`), not those that need options of the language (`// LANGUAGE:`), and not those
that use the JDK or the JVM's `TailCallOptimizationChecker`. 425 were taken, 384 pass.

They found, all fixed:
- a coroutine resumed inside the call that suspended it (`resume` in
  `suspendCoroutineUninterceptedOrReturn` before it returns `COROUTINE_SUSPENDED`), which most of
  them do: the state machine noted where it waits only after the call (it ran from the start
  again, until the stack was gone), noted it again after a run inside the call had ended the
  coroutine, handed a local over to the call (the inner run released it too), and released the
  arguments of the call after it (the inner run had released them already). The test for
  `COROUTINE_SUSPENDED` now comes right after the call, before anything else;
- a coroutine started with an exception (`createCoroutine(...).resumeWithException(e)`) did
  not throw it;
- `tailrec suspend fun` was not a loop;
- a loop inside a `finally` that a jump leaves was emitted twice with one label (not about
  coroutines);
- a parameter of type `Unit` (`(T) -> Unit` with `T = Unit`, `fun f(u: Unit)`), and `==`
  between a `String` and an `Any?` (not about coroutines either).

`box/pending/` held the 41 that did not pass at first; every one passes since 2026-11-21 (`docs/HISTORY.md`), and
the folder is gone until a new box test needs it. Six of them have `.cycles`: they keep their own continuation in a
variable or a list their coroutine holds (the three `*WithHandleResult`, `boxReturnValueOfSuspendLambda` twice,
`intrinsicSemantics/resultExceptionOrNullInLambda`). `featureIntersection/suspendFunctionAsSupertypeIsCheckWithArity`
fails on the JVM by design (`.native`).

## kotlinx-io's tests

`tests/modules/kotlinx-io/` holds the tests of kotlinx-io itself, as its repository has them at the version the
bundled module compiles (0.9.3, Apache 2.0): `core/commonTest` and `bytestring/commonTest` (from
`core/common/test` and `bytestring/common/test`), and the helpers of each platform for the common
`expect`s, `core/nativeTest/util.kt` and `core/jvmTest/utilJVM.kt`. The files of a `commonTest` are a group
(above): in C with the module's sources and `nativeTest`, on the JVM with kotlinx-io's jars, whose internals it
sees as a test of the library does, and `jvmTest` (`moduleTestList` in `IntegrationTest.kt`). All 32 pass.
`.skip` next to a file says what it says for the library's tests: `bytestring/commonTest/samples/samples.skip` (a
sample prints random bytes). No file has `.cycles` any more.

## The tests of kotlinx.collections.immutable, atomicfu and kotlinx-datetime

The libraries' own tests (`tests/modules/<module>/`, the category `modules`) run locally; the
CI job leaves them out (`KTC2_TEST_CATEGORIES=integration,ktc2,stdlib`). The programs on each module (`tests/integration/projects/`) run everywhere.

The same, for the other libraries that are bundled modules. `kotlinx-collections-immutable/core/`
holds `commonTest` of kotlinx.collections.immutable 0.5.2 (from `core/commonTest/src`) and its
helpers `nativeTest/testUtilsNative.kt` and `jvmTest/testUtilsJvm.kt`; the stress tests print their
sizes and times, which `.vary` files leave out. All 21 pass. `atomicfu/atomicfu/` holds
`commonTest` and `concurrentTest` of atomicfu 0.33.0 (the latter common to the JVM and Native, run
the same way) and `jvmTest/TestThread.jvm.kt`; `nativeTest/TestThread.native.kt` is ktc2's own, a
thread of `ktc2.concurrent` where atomicfu's starts a `kotlin.native.concurrent.Worker`, which ktc2
does not have yet (`SynchronizedTest` of its `nativeTest`, written with workers, is not taken).
All 40 pass. `kotlinx-datetime/core/` holds `commonTest` of kotlinx-datetime 0.8.0 (from
`core/common/test`): all 51 pass on Windows, where the time zones come from the registry; two of
them print the time they run at (`.vary`). A module's tests are compiled with the bundled modules
it depends on (kotlinx-datetime with kotlinx-serialization), and on the JVM with their jars. The
tests of `commonKotlin/test`, which read datetime's internals that only Native and the web have,
are not taken.

They found, with datetime: a lambda's parameter of type Unit (`check(x).let { }`); `name` of a
reference; an enum entry not seen as Comparable; a function or a property with the C name of a
class or a function; a local `inline fun`, refused by the JVM's front end only; the shapes of
`<windows.h>` the bindings did not know (typedefs, wide strings, cast macros); `@Test` functions
of a nested class, which the suite's runner gave to the outer one.

They found: a dispatcher that tested an argument against an interface that a lambda does not
carry (`suspend` function types); a value of an instantiation of a generic class reached through a
star projection, which `Devirtualize` took for the one instantiation it knew; `HashMap.remove(key,
value)`; cycles that the cycle lint did not see (an `inner` object kept by its outer one, a lambda
that captures `this`), and a thread that kept its block after it ended (`Fut` of atomicfu's tests,
whose thread runs a lambda that holds it); a `return` of an endless `loop { }`.

They found (kotlinx-io): a file's unit named after it, with another file of the same name in the package
(`util.kt` of `commonTest` and of `nativeTest`), wrote over the other's C; `main` was not looked
for in a package whose name starts with `kotlin` (`kotlinx.io`); a value class passed between a
dispatcher and an implementation, one as it is and the other as an object, had no box class.

`kotlinx-coroutines/core/` holds the tests of kotlinx.coroutines 1.11.0 (from the repository's tag `1.11.0`):
`commonTest` is `kotlinx-coroutines-core/common/test` with the common part of its `test-utils`
(`commonTest/testutils`: `TestBase.common.kt`, `LaunchFlow.kt`, `MainDispatcherTestBase.kt`), `concurrentTest` is
`kotlinx-coroutines-core/concurrent/test`; `nativeTest` has Native's `TestBase` and `ConcurrentTestUtilities`, and
`jvmTest` the same `TestBase` (Native's, with a line that says so: the JVM's needs JUnit) and the JVM's
`ConcurrentTestUtilities`. Of Native's own tests (`native/test`), `WorkerTest`, `MultithreadedDispatchersTest` and
`DelayExceptionTest` are taken (`nativeTest/`, decided by their own assertions, without the JVM). Some 180 files are one
program, about 3 minutes; `KTC2_TEST_FILTER=tests/modules/kotlinx-coroutines/` runs them. The 7 files whose tests keep a
continuation (or a handler, a list of lambdas) where their own coroutine reaches it leave cycles by their design: each
`.cycles` says so, and the compiler warns of each (`UserCycleLint`). The library itself leaves none (its module's
declarations and edits, and the runtime's reaper: `DESIGN.md`, "The reaper"). Three stress tests print counts that
depend on the threads (`.vary`).

They found (2026-11-18): a `val` given its value in a lambda a contract runs in place (`coroutineScope { x = 1 }`)
read as 0 (`basic/ContractInitialization`); a reference to an ordinary function given as a `suspend` function type
(`combine(other, String::plus)`) made no function value (`basic/SuspendReferences`); a null cast without a check to a
type parameter that is a number here failed (`generics/UncheckedNullCast`); a class whose field is of a class below
it, through a generic class being made (`AbstractSharedFlow` and `SubscriptionCountStateFlow`:
`generics/FieldOfSubclassType`); `removeFirst` called on an `ArrayList` itself; `shuffle()` with a fixed seed
(Native's uses `Random`: `selectUnbiased` was biased); a state machine whose first run ends it never let go of its
intercepted continuation; and in a state machine, the release of a call's argument ran before the test for a
suspension when a declaration stood in front of the suspension point, so a coroutine resumed inside the call (a
dispatcher that runs at once) released it twice (`modules/kotlinx-coroutines`, the combined flows).

Beyond coroutines, the folders `basics`, `when`, `controlStructures`, `classes`, `closures`, `strings`, `dataClasses`
and `enum` are taken (657 files; `PLAN.md` 2.6 lists the next). A file of a folder with a package of its own goes under
its `boxtest.` package too (several of a folder have `package test`), and its imports of what it declares with it; a
part of a path that is a keyword of Kotlin (`when`, `try`) gets a `_`. `basics/*.skip` names the unchecked casts that
differ (`PLAN.md` 2.6).

To take in more: `git clone --depth 1 --filter=blob:none --sparse -b v2.5.0-RC https://github.com/JetBrains/kotlin
build/kotlin-src`, `git sparse-checkout set compiler/testData/codegen/box` in it, then
`python -I tools/select_box.py build/kotlin-src/compiler/testData/codegen/box/<dir> tests/box/<dir>`.

## What they found

The first six files found five differences (a `Pair<Int, Nothing?>` read as a
`Pair<Int?, Int?>`, `==` on a `T` that is a `Double`, `Pair('b', x) == Pair('b', x)` across two
instantiations, `Char(code)`, `pow` and `ulp` at their edges). The next 36, taken all at once
on 2026-11-13, found these, all fixed:

- `a.equals(b)` written out on a number (`0.0.equals(-0.0)`, `UInt`);
- `a..b` and `a..<b` between `Byte`, `Short`, `UByte`, `UShort`, and between an `Int` and a
  `Long`, which was taken for a range of `Int`;
- `1 as Int?`; `<` between two numbers that may be null and are known not to be;
- `floorDiv` and `mod` of unsigned numbers; `Random.nextUInt` and the like; `min` and `max`
  of unsigned numbers; `toHexString` and `hexToUInt` of unsigned numbers;
- `a compareTo b` written as an infix call; `KotlinVersion`;
- `AssertionError(cause)` has its cause; `addSuppressed`, `suppressedExceptions`, and a
  `stackTraceToString` that shows them and the causes as the JVM does (without the stack);
- `use { }` runs its block exactly once as far as the compiler knows (a `val` assigned in
  it), and adds what `close` throws to the exception of the block;
- a function that returns something and whose body is `TODO()`; `2 xor 3` between constants
  (both were C that did not compile with `-Werror`);
- `nextUp`, `nextDown`, `nextTowards` among the smallest numbers on Windows.

The 24 files that use the helpers, taken on 2026-11-14, found these, all fixed:

- a local class or an object in generic code (the iterator of `withIndex()` on a sequence of
  numbers) was always of the instantiation for objects: it now takes the one of the code around
  it (`classParameters`, `KirImporter.contextSubst`);
- `containsAll` of a list of numbers asked through a view of it as a list of objects;
  `hashCode` of a number reached through `Comparable`; `toString`, `hashCode` and `equals` of an
  array; an array in a template; a `vararg` of unsigned numbers given nothing;
- an object that delegates an interface (`object : List<String> by list`): its field was not
  declared;
- `enumEntries<T>()` of an enum class known where it is written; the companion of a built-in
  class (`Int`) used as a value; the iterator of an array of numbers (`IntArray.iterator()`);
- a branch of `when` that a type test rules out in one instantiation was still compiled;
- a lambda parameter of type `Nothing` (`emptyList<Nothing>().sumOf { 1 }`);
- `NegativeArraySizeException` for `IntArray(-1)` and `copyOf(-1)`, catchable;
- `CharacterCodingException`, and UTF-8 that is checked and replaced as the JVM does;
- `StringBuilder`: the checks of its ranges, `setRange` and `deleteRange` past the end,
  `indexOf("", past the end)`, `OutOfMemoryError` past what an Int holds;
- `ArrayList(-1)`, `HashMap(-1)`, a load factor that is not above zero; an empty `addAll`
  counts as a change; a part of a list fails once the list changed, also for `iterator()`;
- `AbstractMutableMap`: its keys and values are views, and it has `equals`, `hashCode`,
  `toString`; `AbstractMutableList.addAll` checks its index;
- `hashCode` of `UByte` and `UShort` (as the signed number they are kept as);
- a collection that holds itself names itself in its text; `contentDeepToString` of an array
  that holds itself; `contentDeepEquals` of arrays of unsigned numbers; `toArray(array)` puts
  null after the last element, as the JVM does;
- a number in base 16 needs its exponent (`0x1p3`); a duration of more nanoseconds than a
  Long holds, as text;
- `lowercase` and `uppercase` of letters outside the first 65536 characters; a regex compares
  letters without case as Java does, takes a pair of surrogates as one character, refuses `\0`
  without digits, and a reference to a group the pattern does not have matches nothing;
- a member that takes only objects of its class (or of the class a type parameter is bounded by,
  `contains(element: E)` of `EnumEntriesList`), asked through a view that takes any object, was
  given anything: the dispatcher now tests the class as the JVM's bridge does, and a call is made
  direct only when the object is known to be of it (AddressSanitizer on Linux found this);
- `windowed` and `chunked` of what is not a list, which the library writes as a coroutine
  (`kotlin_collections_windowedIterator` in the platform layer).

## Pending, and why

Nothing is pending. The last three (`SequenceBuilderTest`, `CoroutinesReferenceValuesTest`,
`DeepRecursiveTest`) came in with coroutines (2026-11-14): `suspend` functions as state machines,
`sequence { }`, `iterator { }`, `DeepRecursiveFunction`. `DeepRecursiveTest.cycles` says why that
file leaves objects allocated: two of its classes hold functions whose lambdas capture the object.

Tests set aside inside files that run (`.skip`): one of `MapTest` that puts null in a
`HashMap<String, Int>` through an unchecked cast (that map keeps its values as numbers);
`StringBuilderTest.deprecatedAppend` (a method of the JDK's StringBuilder).

Taken in on 2026-11-14 after what they waited for was done: the atomics
(`kotlin.concurrent.atomics`: numbers changed by the C compiler's atomic operations, references
under a monitor), `PropertyReferenceTest` and `RandomTest` (the test files are compiled as the
common part of a module on the JVM, and `@ThreadLocal` is there, without effect, as on the JVM),
`CodePoint*Test` (with Unicode beyond U+FFFF in `Ktc2Unicode.kt`), `ContainerBuilderTest`
(`buildList` and the others give a collection that refuses changes, and one shared empty one),
`KClassTest` (`cast`, `safeCast`), the 10 tests of `SequenceTest` that went through `sequence { }`
(`scan`, `runningReduce`, `zipWithNext`, `flatMapIndexed`, `shuffled` and the others are iterators
of the platform layer: `kWithoutCoroutine` in `BodyLowering.kt`), `UuidTest` (random bytes from the system), `TimeSourceClockTest`
(`Clock`, `Instant`), `MeasureTimeTest` and `CollectionTest.copyToArray` (with a `.vary` file).
They found two faults of the compiler besides: `Devirtualize` did not see a text built by a
template into a local, and took a generic function's parameter for the one class it had seen
elsewhere (`objects/TemplateArguments`); and a reference to a `var` could not be written through.

Taken in later the same day: `Base64Test` (with `kotlin.io.encoding.Base64`), the files thought
to need the library's `internal` declarations (`AbstractListTest`, `InstantTest`,
`InstantIsoStringsTest`, `DurationParserTest`, `UByteValueSetTest`, `BytesHexFormatTest`: they pass,
compiled as part of the library's module), the 67 generated files of `generated/` (`allDistinct`,
`allEqual`, `isSorted`, `min`/`max` for every kind of array, iterable and sequence), and the tests of
the coroutine context that need no `suspend` (`CoroutineContextTest`,
`AbstractCoroutineContextElementTest`, `ContinuationInterceptorKeyTest`,
`CancellationExceptionTest`, `ResultTest`, `StackTraceRecoverableTest`). They found: `==` between two
values of a type parameter that stands for Double or Float is `equals` (NaN equals NaN, -0.0 is not
0.0: `basic/GenericFloatEquality`), a local of type `Nothing`, booleans compared in C, a default
argument that names an earlier parameter of an inherited member (`objects/InheritedDefaults`), and
a private property read on another instance of a class below its own (`generics/PrivateOfAnother`).

On Kotlin/Native 2.4.21 some of these files do not compile ("name based destructuring is only
available since language version 2.5"): they need the compiler of their own version.

A file of a library's own tests (`tests/modules/<module>/`) that does not pass yet is kept out of the
suite by a `<Name>.pending` next to it, which says why; moved files would no longer be where
the library has them. None is pending since 2026-11-21 (the first, `JsonUpdateModeTest`, looped in C from 2026-11-16
and passes since).
