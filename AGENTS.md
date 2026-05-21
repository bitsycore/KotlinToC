# AGENTS.md — KTC Contributor Guide

KTC is a source-to-source Kotlin → C11 transpiler. Zero runtime, stack-first, no GC.

```
Kotlin source → Lexer → Parser → AST → CCodeGen → .h + .c files
```

- **Language features, syntax, and C mapping** → [KOTLIN_TO_C.md](docs/KOTLIN_TO_C.md)
- **Transpiler internals, architecture, how to add features** → [TRANSPILER.md](docs/TRANSPILER.md)

---

## Running Tests

```bash
./gradlew test                       # 577 unit tests (fast, no C compiler needed)
./run_tests.sh                       # integration tests (transpile + compile + run, Unix)
.\run_tests.ps1                      # integration tests (Windows)
./run_tests.sh --run GenericsTest    # single integration test
```

All 577 unit tests must pass before merging.

---

## Writing Unit Tests

Unit tests live in `src/test/kotlin/com/bitsycore/`. They feed Kotlin source through
the transpiler and assert on the generated C text — no C compiler involved.

```kotlin
class MyFeatureUnitTest : TranspilerTestBase() {

    @Test fun basicCase() {
        val r = transpile("""
            package test.Main
            fun add(a: Int, b: Int): Int = a + b
            fun main() { }
        """)
        r.sourceContains("ktc_Int test_Main_add(ktc_Int a, ktc_Int b)")
    }

    @Test fun withMainBody() {
        // transpileMain wraps the body in a package + main()
        val r = transpileMain("val x: Int? = null")
        r.sourceContains("ktc_Int\$Opt x = KTC_NONE(ktc_Int)")
    }
}
```

Key helpers on `TranspileResult`:
- `sourceContains(text)` — asserts the `.c` output contains the string
- `headerContains(text)` — asserts the `.h` output contains the string
- `sourceNotContains(text)` — negative assertion

---

## Writing Integration Tests

Integration tests live in `tests/<TestName>/`. All `.kt` files in the directory are
transpiled together, compiled with a real C11 compiler, and executed. The test passes
when the process exits with code 0.

### Rules

**Use `error("…")` for runtime assertions.** It prints to stderr and exits with
failure code, which fails the test:
```kotlin
val x = add(2, 3)
if (x != 5) error("expected 5, got $x")
```

**Use `println` for progress output** so failures are diagnosable:
```kotlin
println("step 1 passed")
println("result = $result")
```

**Avoid `c.*` interop** unless the test is specifically about C interop. It makes
tests less readable and non-portable. Use stdlib functions instead:
```kotlin
// Good
if (count != 3) error("wrong count: $count")
println("ok")

// Only when testing interop explicitly
c.printf("raw: %d\n", value)
```

**Cover combinations** — the most valuable tests hit multiple features interacting:
nullable + generic, interface + inline, array + defer, smart cast + when, etc.

```kotlin
package MyNewTest

fun main() {
    // nullable + elvis + smart cast
    val v: Int? = compute()
    val safe = v ?: error("expected non-null")
    if (safe < 0) error("expected positive, got $safe")
    println("value ok: $safe")

    // generic class + interface
    val list: MutableList<String> = mutableListOf("a", "b", "c")
    defer list.dispose()
    if (list.size != 3) error("wrong size ${list.size}")
    println("list ok")

    // array + for loop + sum
    val arr = intArrayOf(1, 2, 3)
    var sum = 0
    for (x in arr) sum += x
    if (sum != 6) error("wrong sum $sum")
    println("array ok")

    println("all passed")
}
```

### Structure

```
tests/
  MyNewTest/
    MyNewTest.kt   ← package MyNewTest; fun main() { … }
    Helper.kt      ← optional: same package, additional types/funs
```
