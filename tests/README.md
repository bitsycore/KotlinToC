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
  there are of what the library must do. 42 files, 405 tests.
- `kotlin/<Name>.cycles`: says, with the reason, that the tests of `<Name>.kt` build objects
  that refer to each other in a circle. Those are never freed, there being no collector of
  cycles, and for that file what is left allocated is not a failure. Three files have one.
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

## Pending, and why

| File | Why |
|---|---|
| `AtomicCommonTest` | `kotlin.concurrent.atomics` is not there |
| `DeepRecursiveTest` | `DeepRecursiveFunction` is built on coroutines |
| `KTypeProjectionTest` | reflection of types (`KType`) is not there |
| `TimeSourceClockTest` | `kotlin.time.Clock` is not there |
| `MeasureTimeTest` | prints the time it measured, which the suite compares with the JVM's |
| `UuidTest` | `kotlin.uuid` needs a source of random bytes of the system and opt-ins of the library's own build |
| `PropertyReferenceTest`, `RandomTest` | use `@JsName`-like annotations that the compiler only takes in common sources: the tests would have to be compiled as the common part of a multiplatform module |

Not taken at all, because they test what only the library's own module can reach
(`internal`): `AbstractListTest`, `InstantTest`, `InstantIsoStringsTest`, `DurationParserTest`,
`UByteValueSetTest`, `BytesHexFormatTest`, `Base64Test`.

Not looked at yet: the 30 or so files that use helpers of the library's tests
(`test.TestPlatform`, `test.collections.behaviors`, `testUtils.kt`), among them the largest
(`StringTest`, `CollectionTest`, `ArraysTest`, `MapTest`, `SequenceTest`). The helpers have
to come with them; that is the next step.

On Kotlin/Native 2.4.21 some of these files do not compile ("name based destructuring is only
available since language version 2.5"): they need the compiler of their own version.
