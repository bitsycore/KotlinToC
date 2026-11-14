# Common tests

Tests written as Kotlin Multiplatform's common tests are: classes with `@Test` functions that
assert with `kotlin.test`, and no `main`. A test says what must hold on every platform, and
nothing of what a platform is free in (the text of a message, the name of an exception's
package). The same file runs:

- in C and on the JVM, by the suite (`./gradlew test --rerun`, `KTC2_TEST_FILTER=tests/`): it
  writes the `main` that calls each test (`build/testmain/`), and a test that fails on either
  fails the suite;
- on Kotlin/Native, by `tools/native.sh tests/` after a run of the suite, with Kotlin/Native's
  own `kotlin.test`.

`kotlin.test` here is `src/main/resources/ktc2/stdlib/Ktc2Test.kt`: the assertions, `@Test`,
`@Ignore`, `@BeforeTest`, `@AfterTest`.

- `*.kt`: tests written for ktc2.
- `kotlin/`: tests of the Kotlin standard library itself, unchanged, from
  `libraries/stdlib/test` of github.com/JetBrains/kotlin at tag `v2.5.0-RC`, the version of
  the library ktc2 compiles (Apache 2.0, the header of each file). They are the best tests
  there are of what the library must do. 152 files, some 2200 tests: every file of `libraries/stdlib/test` but those of JavaScript and the
  six in `pending/` (coroutines, reflection of types). They are compiled as the
  library's own tests are: with its opt-ins (`kotlin.ExperimentalUnsignedTypes` and the others
  of its `commonTestOptIns`; `ktc2.optIn` in `build.gradle.kts` for C), and on the JVM seeing
  what the library keeps `internal`.
- `support/`: the helpers of the library's tests, compiled with every file of `kotlin/`.
  `support/common/` is theirs as they are (`testUtils.kt` without its `expect` line,
  `CollectionBehaviors.kt`, `ComparisonDSL.kt`, `jsCollectionFactories.kt`,
  `DurationTestUtils.kt`); `support/jvm/` and `support/ktc2/` answer what they leave to the
  platform, for each side. ktc2 answers as the JVM does (`TestPlatform.current` is `Jvm`),
  since where the JVM and Kotlin/Native differ ktc2 does as the JVM; only `assertTypeEquals`
  differs, being `javaClass` there. A line `expect ...` of a test file is left out when it is
  compiled (`support/*/Text.kt` answers those of `StringEncodingTest`).
- `kotlin/<Name>.with`: other files of tests whose helpers `<Name>.kt` uses (`Sortable` of
  `IterableTests.kt`, `STRING_CASE_INSENSITIVE_ORDER` of `OrderingTest.kt`), one a line:
  compiled with it, their tests not run.
- `kotlin/<Name>.jvm`: options the compilation for the JVM needs for that file, one a line.
- `kotlin/<Name>.vary`: the beginnings of the lines it prints that differ on every run (a random seed, a time), one a line: left out of the comparison.
- `kotlin/<Name>.skip`: tests of `<Name>.kt` that are not run, one a line, `name: why` (or
  `Class.name: why`). Neither side runs them.
- `kotlin/<Name>.cycles`: says, with the reason, that the tests of `<Name>.kt` build objects
  that refer to each other in a circle. Those are never freed, there being no collector of
  cycles, and for that file what is left allocated is not a failure.
- A class of tests that extends another (`IterableTests.kt`: `ListTest : OrderedIterableTests`)
  runs the tests it inherits; an abstract one runs none itself.
- `pending/`: tests of the same origin that do not pass yet, each with the reason below. Not
  run. A test that does not pass has found a difference to fix; the file then moves to
  `kotlin/`.

To take in more: get the tests (`git clone --depth 1 --filter=blob:none --sparse -b v2.5.0-RC
https://github.com/JetBrains/kotlin`, then `git sparse-checkout set libraries/stdlib/test`),
copy files to `kotlin/`, run `KTC2_TEST_FILTER=tests/kotlin/ ./gradlew test --rerun`, and
read `python tools/failures.py`. A file from another tag than the library's fails on the JVM
too (two did, taken from `v2.4.21`).

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

| File | Why |
|---|---|
| `DeepRecursiveTest` | `DeepRecursiveFunction` is built on coroutines |
| `SequenceBuilderTest`, `CoroutinesReferenceValuesTest` | `sequence { }`, `suspend` functions: coroutines |
| `KTypeProjectionTest` | reflection of types (`KType`) is not there |

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
