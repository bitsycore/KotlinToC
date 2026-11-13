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
  `libraries/stdlib/test` of github.com/JetBrains/kotlin at tag `v2.4.21` (Apache 2.0, the
  header of each file). They are the best tests there are of what the library must do.
- `pending/`: tests of the same origin that do not pass yet, and why. Not run. Each is a
  difference to fix, after which the file moves to `kotlin/`:
  - `TuplesTest.kt`: `Pair(1, null).toList()` gives `[0, null]`: a `Pair<Int, Nothing?>` read
    as a `Pair<Int?, Int?>` (two instantiations of one class, as in "A generic class is one
    class in Kotlin" of `DESIGN.md`, for a number against a number that may be null);
  - `MathTest.kt`: 7 of its 29 tests fail in C. `assertEquals(Double.NaN, x)` fails: in code
    written for a `T` and instantiated for `Double`, `==` compares as numbers do (NaN is not
    NaN) where Kotlin calls `equals` (NaN equals NaN); `powers` and `nextAndPrev` fail too
    (not looked at);
  - `CharTest.kt`: `Char(code)`, the function that makes a character of a number, is refused;
  - `ListSpecificTest.kt`: `assertFailsWith(Exception::class) { }`, the form that takes the
    class as a value, is not in `Ktc2Test.kt` (it needs `KClass.isInstance`);
  - `GroupingTest.kt`: the `main` the suite writes takes a function that follows a `@Test`
    one for a test (`testMain` in `IntegrationTest.kt` reads the source as text).

Most files of the library's tests use helpers of its own (`test.TestPlatform`,
`test.collections.behaviors`): those come next, with the helpers.
