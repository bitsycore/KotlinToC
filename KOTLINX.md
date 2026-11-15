# The kotlinx libraries in ktc2

How kotlinx.collections.immutable, kotlinx-datetime, kotlinx.serialization and kotlinx.coroutines
can become modules of ktc2, after kotlinx-io, which is one (2026-11-15). Written before any of
them was started: the numbers are measured on the libraries' published sources; the estimates
are estimates. `PLAN.md` (part 8) points here.

**Done since** (2026-11-15): kotlinx.collections.immutable and atomicfu are modules, with all of
their own tests passing (`tests/README.md`); `@ThreadLocal` is local to a thread (point 2 of
section 3); the versions are the latest, pinned in `gradle/libs.versions.toml`. Next: datetime.

---------------------------------------------------------------------------
## 1. How kotlinx-io was done, and what it left in place
---------------------------------------------------------------------------

A library is a bundled module made of its own sources, unchanged, and nothing of ktc2's own API:

- **Sources.** Gradle fetches the library's sources jars (the common one, and the ones of the
  Kotlin/Native targets, `-mingwx64`, `-linuxx64`, `-macosarm64`, which hold the source sets of
  each system); `files.txt` in the module lists the files compiled. They are put next to the
  module file in ktc2's resources.
- **Source sets.** `commonMain` is common code; `nativeMain`, a level of its own when it has
  `expect`s, sees what Kotlin/Native's platform libraries are to it (ktc2's platform layer,
  `kotlinx.cinterop`, `platform.posix`); the rest refines it. A file of a Native source set gets
  `import kotlin.native.*`, as Kotlin/Native imports it by default.
- **What each system gives apart** (`mingwMain`, `linuxMain`, `appleMain`...) is a file of the
  module's own, once for every system, with the little C that differs in a `.def` of the module.
- **Cycles** a collector would free: `weak` and `acyclic` in the module file.
- **Tests**: the library's own common tests, unchanged, under `tests/<library>/`, run in C
  against the module and on the JVM against the library's JVM jars (which they see the
  internals of); and a program of ktc2's under `integration/modules/<library>`.

What it took of ktc2, now there for the next ones: common sources in a module, three levels of
source sets, `kotlin.native.Platform`, `kotlin.concurrent.Volatile` as a class, `convert()`,
POSIX's headers for files in `platform.posix`, and two units for two files of one name.

---------------------------------------------------------------------------
## 2. The four libraries, measured
---------------------------------------------------------------------------

Latest versions on Maven Central on 2026-11-15. Lines and `expect`s of the source sets a
Kotlin/Native target compiles (from the sources jars), and what their native code imports.

| library | version | common | native and per system | imports of the native part |
|---|---|---|---|---|
| kotlinx-collections-immutable | 0.5.2 | 8 156 lines, 1 `expect` | `nativeMain`: 11 lines | none |
| kotlinx-datetime | 0.8.0 | 13 645 lines, 34 `expect`s; `commonKotlinMain` 2 311 lines (date and time arithmetic in Kotlin, shared by Native and Wasm) | `tzdbOnFilesystemMain` 111, `tzfileMain` 52, `linuxMain` 61, `darwinMain` 285, `windowsMain` 975 | `platform.posix` (reading `/usr/share/zoneinfo`), `platform.windows` (the registry), Foundation on Apple |
| kotlinx-serialization-core | 1.13.0 | 8 495 lines, 12 `expect`s | `nativeMain`: 125 lines | `findAssociatedObject` (Kotlin/Native's associated objects) |
| kotlinx-serialization-json | 1.13.0 | 6 492 lines, 5 `expect`s | `nativeMain`: 142 lines | `kotlin.native.ref` |
| kotlinx-coroutines-core | 1.11.0 | 27 143 lines, 56 `expect`s; `concurrentMain` 739 | `nativeMain` 740 lines (22 files), `nativeOtherMain` 30 | atomicfu, `kotlin.native.concurrent.Worker`, `@ThreadLocal` |
| atomicfu (what coroutines is written with) | 0.33.0 | 947 lines, 22 `expect`s; `concurrentMain` 498 | `nativeMain` 439, `mingwMain` 121, `linuxMain` 67 | `platform.posix` (locks), `kotlinx.cinterop` |

---------------------------------------------------------------------------
## 3. What they need of ktc2 besides what kotlinx-io left
---------------------------------------------------------------------------

Some of it is shared; done once, it serves several.

1. **atomicfu** (coroutines; done: the module `atomicfu`, its own sources, with
   `Ktc2Atomicfu.kt` for its systems' part over a lock and a condition of Win32 or pthreads). The library is written with `atomic(0)`, `AtomicRef`,
   `atomicArrayOfNulls`, `SynchronizedObject`, `reentrantLock()` and `synchronized`; on Kotlin/Native
   atomicfu's runtime implements them over `kotlin.concurrent.AtomicInt`/`AtomicReference` and
   pthread locks. ktc2 has `kotlin.concurrent.atomics` (atomics of the C compiler) and the
   monitors of `ktc2.concurrent`. Either atomicfu's own sources as a module (its `nativeMain` uses
   `platform.posix`'s pthread mutexes; the `mingwMain`/`linuxMain` parts are small), or a
   compatibility module of ktc2's with the same API over what ktc2 has. Its compiler plugin
   (which rewrites the atomic fields into plain ones on the JVM) is not needed: on Native the
   library runs without it.
2. **`@ThreadLocal` that is local to a thread** (done, `DESIGN.md` section 6; coroutines: the event loop of `runBlocking` and
   of each thread; serialization and datetime declare caches with it). In ktc2 it changes nothing
   today, as on the JVM, where it does not exist. A global or an `object` marked so would become a
   C `_Thread_local` (`KTC2_TLS`), with its initialization on each thread's first use. This changes
   what plain Kotlin does only where Kotlin/Native also differs from the JVM.
3. **`kotlin.native.concurrent.Worker`** (coroutines' dispatchers): a thread with a queue of
   jobs, `Worker.start()`, `Worker.current`, `execute`, `executeAfter(delay)`, `requestTermination`.
   Over `ktc2.concurrent.Thread` and a monitor, in the platform layer, as Kotlin/Native's API.
4. **`Platform.getAvailableProcessors()`** (coroutines' `Dispatchers.Default`): C's
   `sysconf(_SC_NPROCESSORS_ONLN)`, `GetSystemInfo` on Windows.
5. **Associated objects** (serialization): `@AssociatedObjectKey` marks an annotation
   (`@SerializableWith`) whose argument, an `object`, is found back from the class with
   `KClass.findAssociatedObject<T>()`. That is how `serializer<T>()` finds what the plugin
   generated, on Kotlin/Native. The importer can see every class annotated so and write a table
   from the class to the object; `findAssociatedObject` reads it.
6. **A compiler plugin** (serialization). `@Serializable` classes get their serializers from the
   serialization plugin, which the Kotlin compiler runs before its IR is handed on.
   `kotlin-serialization-compiler-plugin-embeddable` is published for 2.5.0-RC, the version ktc2
   runs; added to the compilation (`-Xplugin`), its IR comes to `Ktc2Extension` like the
   program's, if ktc2's extension is registered after it. The JVM side of the suite compiles with
   the same plugin.
7. **Time zones on Windows** (datetime). Its `windowsMain` reads the zones from the registry with
   `platform.windows`, which ktc2 does not generate (`windows.h` is too large for the header
   reader as it is). Either the part of the registry it needs, declared in a `.def` of the module
   as kotlinx-io's Win32 calls are, or kotlinx-datetime's packaged database
   (`kotlinx-datetime-zoneinfo`, made for JS and Wasm, versions `2026f-spi.0.8.0`), which would
   give every system the same zones. To try first: the second.
8. **Cycles.** Each library's own structures are to be looked at under the leak check of the
   suite: coroutines first (a parent `Job` and its children are linked both ways; handlers,
   channels and `select` hold continuations), and the other three for what they cache.

---------------------------------------------------------------------------
## 4. Each library
---------------------------------------------------------------------------

### kotlinx.collections.immutable 0.5.2 (done)

Done in a day. What it took: one cycle (the iterator of a builder's entries and its iterators of
each level, `weak` in the module file), and five fixes of the compiler that its tests found
(`tests/README.md`).

Persistent lists, sets and maps (`persistentListOf`, `toImmutableList`, builders): tries and
HAMTs in common code. Nothing of the platform.

- **Take**: `commonMain` and `nativeMain` as they are.
- **Watch**: the nodes of the tries are shared between versions of a collection; no cycle is
  expected (a node refers to its children only), the leak check will say. Arrays of nodes are
  `Array<Any?>`: the speed of their element access matters.
- **Tests**: the library's common tests, from its repository at the tag of the version, against its JVM jar.
- **Estimate**: one to two days. The first to do: it tests the method on a library with no
  platform part at all, and what it finds is in the compiler, not in a platform layer.

### kotlinx-datetime 0.8.0

`Instant`, `LocalDateTime`, `TimeZone`, formats and parsing. The arithmetic is common Kotlin
(`commonKotlinMain`); the platform gives the time zones.

- **Take**: `commonMain`, `commonKotlinMain` (a source set of its own, between common and
  native), `tzdbOnFilesystemMain` and `tzfileMain` (reading the zone files) for Linux and
  macOS, `linuxMain`. Not `darwinMain` (Foundation).
- **Write**: the Windows part (point 7 above), and what `darwinMain` gives on macOS
  (`currentSystemDefaultZone` from `/etc/localtime`, as on Linux).
- **Watch**: `kotlin.time.Instant` is now the standard library's (ktc2 has it); datetime 0.8
  uses it. Four levels of source sets (common, commonKotlin, native, platform): the fragments
  ktc2 passes are three today, to be made general (each source set refines the ones it names).
- **Tests**: `core/common/test` (many tests over time zones: compared with the JVM's, which
  reads its own database; the versions of the database have to be the same, or the tests about
  recent changes of zones skipped).
- **Estimate**: three to five days.

### kotlinx.serialization 1.13.0 (core and JSON)

- **Take**: `commonMain` and `nativeMain` of core and json.
- **Write**: associated objects (point 5), the plugin in the compilation (point 6),
  `@ThreadLocal` (point 2) for the caches.
- **Watch**: serialization leans on generics and on `KType`/`typeOf<T>()` (`serializer<T>()`),
  which ktc2 has as Kotlin/Native has them; polymorphism by `KClass` (`SerializersModule`),
  which needs `KClass` as keys and `isInstance` for interfaces (there since 2026-11-14).
  Generated code is large: the cost of the C of each `@Serializable` class is to be measured.
- **Tests**: the libraries' common tests, which use `@Serializable` everywhere (so the plugin on
  both sides); and a program of ktc2's with a model of ordinary classes to and from JSON.
- **Estimate**: one to two weeks, most of it the plugin and the associated objects.

### kotlinx.coroutines 1.11.0

- **Take**: `commonMain`, `concurrentMain`, `nativeMain`, `nativeOtherMain` (not `nativeDarwinMain`,
  which is the main thread's dispatcher of Apple's).
- **Write**: atomicfu (point 1), `@ThreadLocal` (2), `Worker` (3), the number of processors (4).
- **Watch**: cycles (point 8: the part most likely to need `weak` entries, and the choice in
  `PLAN.md`, "To decide", 3); the suspend machinery at scale (ktc2's state machines with
  `suspendCancellableCoroutine`, `select`, inline suspend lambdas: the box tests of coroutines
  that are pending today, `tests/box/pending/`, are the first to make pass); threads and
  reference counts under contention (the suite's thread tests, and AddressSanitizer on Linux).
- **Tests**: `kotlinx-coroutines-core/common/test` and `concurrent/test`, with their `TestBase`;
  `runTest` comes from kotlinx-coroutines-test, a second module.
- **Estimate**: two to three weeks.

---------------------------------------------------------------------------
## 5. Order, and what is the owner's to decide
---------------------------------------------------------------------------

Proposed order: collections.immutable, then datetime, then serialization, then coroutines.
The first two need little of the platform and test the method; serialization brings the one
new kind of thing (a compiler plugin); coroutines needs everything else and is the largest.

Decided by the owner (2026-11-15): `@ThreadLocal` made real, as on Kotlin/Native; atomicfu from
its own sources; the latest version of each, pinned in `gradle/libs.versions.toml`.

To decide:
1. Time zones on Windows from the registry or from the packaged database (point 7).
2. The choices for coroutines already listed in `PLAN.md` (dispatchers, `Dispatchers.IO`, cycles).
