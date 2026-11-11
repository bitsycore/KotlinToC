# ktc2 — Kotlin to C

ktc2 translates Kotlin to C11. The Kotlin is real Kotlin: the front end is the Kotlin compiler
itself (K2), and a program behaves as it does on the JVM. The C has no garbage collector:
objects are reference counted, and the compiler keeps them out of the heap where it can — in
the frame, inside their owner, in an arena.

```kotlin
data class Point(val x: Int, val y: Int)

fun main() {
	val points = (1..5).map { Point(it, it * it) }
	println(points.filter { it.y > 4 }.sumOf { it.x })
}
```

```bash
./gradlew installDist
```

```bash
build/install/ktc2/bin/ktc2 hello.kt --run
```

## What it takes

- **The language**: classes, inheritance, interfaces, generics, lambdas and closures, data and
  enum classes, sealed types, objects, inner classes, extensions, operators, properties and
  delegation, exceptions, string templates, inline functions, `tailrec`, the features of
  Kotlin 2.5. Not coroutines, and no reflection.
- **The standard library**, compiled from the sources of the real one: collections, sequences,
  strings and `Char` with Unicode, `Regex`, ranges, `kotlin.math`, `kotlin.time`, `Random`,
  `Result`, `lazy` and the other delegates.
- **No JDK**: as in Kotlin/Native, nothing of `java.*`.

## What it adds, in the `ktc2.` package

Every addition is an annotation or a type that changes where things are stored and what they
cost, never what the program does: each has a plain definition for the JVM, so the same source
also runs there.

| | |
|---|---|
| `@Struct` | a class held by value, like a number |
| `@Value`, `@Size(n)` | an object or an array stored inside its owner, or kept in the frame |
| `ValueArray<T>` | an array that holds its elements in place |
| `arena(size) { }` | what a block allocates is given back in one go |
| `@Weak`, `WeakRef<T>` | references that do not keep their target alive, to break cycles |
| `ktc2.concurrent` | `thread { }`, `synchronized(lock) { }`, `sleep(ms)` |

## C libraries

C is called the way Kotlin/Native calls it: a `.def` file names the headers, ktc2 generates
the bindings (functions, structs, enums, constants, callbacks), and the program uses them
through `kotlinx.cinterop`. `import platform.posix.*` gives the C library.

## Programs made of modules

A directory with a `module.ktc2.toml` is a module:

```toml
name         = "game"
executable   = "game"
main         = "game.start"
sources      = ["src"]
dependencies = ["sdl3", "../engine", "https://github.com/user/repo.git#modules/ui@v1.2"]
```

A dependency is a module shipped with ktc2 (`sdl3`), a directory, or a place in a git
repository. Every compilation writes a `CMakeLists.txt` next to the generated C, and a module
can bring a CMake file of its own: the `sdl3` module fetches SDL3 from GitHub and builds it,
so nothing has to be installed.

```bash
build/install/ktc2/bin/ktc2 examples/showcase --run
```

That opens the showcase: a window with a button for each group of its tests. `-- --headless`
prints them instead.

```
ktc2 <module directory | file.kt | library.def>... [options] [-- program arguments]
  -o <dir>        where the output goes (default: <module>/build, or ./out)
  --build         also compile the C into a program (KTC2_CC names the compiler)
  --cmake         build with CMake (always so when a module has a CMake file)
  --run           build, then run the program
  --debug         no optimization, debug information, and a report of what was left allocated
  --warn-heap     list every allocation left on the heap, with the reason
```

## Compilers and systems

The generated C is C11. It is built and run on Windows with gcc, clang and MSVC. Linux and
macOS are written for and not yet tried; Android is not there yet.

## Working on ktc2

```bash
./gradlew test
```

runs every program under `integration/` twice — as C, compiled with `-Wall -Werror` and
checked for leaks, and on the JVM — and compares what they print. `DESIGN.md` is the design
and the record of what was built; `CLAUDE.md` is the short map of the code.
