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
- `pending/`, when there is one: tests of the same origin that do not pass yet, each with the
  reason written here. Not run. A test that does not pass has found a difference to fix; the
  file then moves to `kotlin/`. The first six files taken found five (a `Pair<Int, Nothing?>`
  read as a `Pair<Int?, Int?>`, `==` on a `T` that is a `Double`, `Pair('b', x) == Pair('b', x)`
  across two instantiations, `Char(code)`, `pow` and `ulp` at their edges) and all pass now:
  99 tests.

On Kotlin/Native 2.4.21 three of these files do not compile ("name based destructuring is only
available since language version 2.5"): they need the compiler of their own version.

Most files of the library's tests use helpers of its own (`test.TestPlatform`,
`test.collections.behaviors`): those come next, with the helpers.
