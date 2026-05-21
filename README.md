# Kotlin.toC()

Write Kotlin, get C11. No runtime, no GC, no dependencies beyond the C standard library.

KTC is a source-to-source transpiler targeting **embedded, game, and systems** use cases
where you want Kotlin's ergonomics but C's performance and portability.

---

## Quick Start

```bash
./gradlew jar
java -jar build/libs/KotlinToC-1.0-SNAPSHOT.jar myfile.kt
cc -std=c11 -o myfile ktc_intrinsic.c myfile.c
./myfile
```

Or with multiple files and an output directory:

```bash
java -jar KotlinToC.jar GameEngine.kt Physics.kt -o out/
cc -std=c11 -o game out/ktc_intrinsic.c out/GameEngine.c out/Physics.c
```

---

## What it looks like

```kotlin
package game

data class Vec2(val x: Float, val y: Float) {
    fun dot(other: Vec2): Float = x * other.x + y * other.y
}

fun main() {
    val a = Vec2(1.0f, 0.0f)
    val b = Vec2(0.0f, 1.0f)
    println("dot = ${a.dot(b)}")   // → dot = 0.0
}
```

Generated C (simplified):

```c
typedef struct {
    ktc_Int __type_id;
    ktc_Float x;
    ktc_Float y;
} game_Vec2;

ktc_Float game_Vec2_dot(game_Vec2* $self, game_Vec2 other) {
    return ($self->x * other.x) + ($self->y * other.y);
}

int main(void) {
    game_Vec2 a = game_Vec2_primaryConstructor(1.0f, 0.0f);
    game_Vec2 b = game_Vec2_primaryConstructor(0.0f, 1.0f);
    ktc_Float $t0 = game_Vec2_dot(&a, b);
    printf("dot = %g\n", $t0);
    return 0;
}
```

---

## Features

**Types and memory**
- All types are **by value** on the stack by default — no hidden allocations
- `@Ptr T` for pointer semantics; `@Ptr T?` for nullable pointers (NULL)
- `T?` value-nullable via `ktc_T$Optional { tag, value }` struct
- Heap allocation via `HeapAlloc`, `HeapArrayZero`, `heapArrayOf` (explicit, no GC)

**Classes and interfaces**
- `class`, `data class`, `object`, `companion object`, `enum class`
- Interfaces with vtable dispatch (fat pointer: `{ void* obj, const vtable* vt }`)
- Generics via **monomorphization** — each `Box<Int>` / `Box<String>` is a distinct C type
- `private` fields get `PRIV_` prefix enforced by the C compiler

**Functions**
- Extension functions, operator overloading, `vararg`
- Default parameter values (filled at call site)
- `inline fun` with lambda arguments — expanded at call site, zero overhead
- Function references `::fun` → raw C function pointer

**Control flow**
- `if`/`else`, `when`, `for`/`while`/`do-while`, `break`/`continue`
- `defer { }` — RAII-style LIFO cleanup blocks
- Smart casts on `val` after null check or `is` check

**Arrays**
- `IntArray`, `Array<T>`, `@Size(N) Array<T>`, `@Ptr Array<T>` — see [KOTLIN_TO_C.md](docs/KOTLIN_TO_C.md)
- `Pair<A,B>`, `Triple<A,B,C>` as intrinsic stack structs

**Stdlib** (`package ktc.std`)
- `ArrayList<T>`, `HashMap<K,V>`, `List<T>`, `MutableList<T>`, `Map<K,V>`, `MutableMap<K,V>`
- `Sha256`, `Random`, `error()`, `TODO()`

**C interop**
- `c.printf(...)`, `c.memcpy(...)`, `c.malloc(...)` — direct C function calls
- String literals passed as raw C strings in interop calls

---

## CLI Flags

```
java -jar KotlinToC.jar <file.kt...> [-o <dir>] [--mem-track] [--ast] [--dump-semantics]
```

| Flag | Effect |
|---|---|
| `-o <dir>` | Output directory for `.c`/`.h` files (default: `.`) |
| `--mem-track` | Track allocations and report leaks at exit |
| `--ast` | Print parsed AST then exit |
| `--dump-semantics` | Print symbol tables and resolved types then exit |

---

## Documentation

| Document | Contents |
|---|---|
| [KOTLIN_TO_C.md](docs/KOTLIN_TO_C.md) | Full language reference: every supported feature with Kotlin → C examples and known limitations |
| [TRANSPILER.md](docs/TRANSPILER.md) | Transpiler internals: architecture, type system, how to add features, evolution rules |
| [AGENTS.md](AGENTS.md) | How to write unit and integration tests, contributor conventions |

---

## Building

```bash
./gradlew jar        # fat JAR in build/libs/
./gradlew test       # 577 unit tests
./run_tests.sh       # unit + integration tests (Unix)
.\run_tests.ps1      # unit + integration tests (Windows)
```

Requires JDK 21+ and a C11 compiler (GCC, Clang, or MSVC) on PATH.