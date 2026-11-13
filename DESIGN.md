# ktc2 - how it works

ktc2 translates Kotlin to C11. This document describes what is built, as it is. What is left
to do is in `PLAN.md`; why things are as they are, and what was tried on the way, is in
`HISTORY.md`.

---------------------------------------------------------------------------
## 1. The two rules
---------------------------------------------------------------------------

1. **Plain Kotlin behaves as on the JVM.** The front end is the Kotlin compiler itself (K2),
   so resolution and typing are Kotlin's. Every test program is run as C and on the JVM and
   must print the same. A difference is a bug in ktc2.
2. **A ktc2 feature changes storage and cost, never behavior.** Everything ktc2 adds is in
   the `ktc2.` package and has a plain definition for the JVM (`src/main/kotlin/ktc2/`), so a
   program that uses it still runs on the JVM and prints the same.

What follows from them: no dialect, no annotations that change meaning, and nothing of the
JDK (a program has no `java.*`, as in Kotlin/Native; `JavaUseCheck` reports uses).

The exceptions to rule 1 that exist today are listed in section 12.

---------------------------------------------------------------------------
## 2. Pipeline
---------------------------------------------------------------------------

```
sources + library sources + generated bindings
  -> Kotlin K2 compiler, in process, with the Ktc2Extension IR plugin   frontend/K2Frontend.kt
  -> JavaUseCheck            no java.* in the program                    frontend/JavaUseCheck.kt
  -> KirImporter/BodyBuilder IR -> KIR, from main, monomorphizing        frontend/KirImport.kt, BodyLowering.kt
  -> CycleLint               warns about classes that can form cycles    backend/CycleLint.kt
  -> Devirtualize            dispatched calls -> direct calls            backend/Devirtualize.kt
  -> ListLoops               `for` over an ArrayList or a HashMap as a loop over its storage; `list[i]` and `list.size` written out   backend/ListLoops.kt
  -> RangeChecks             array accesses proven in range              backend/RangeChecks.kt
  -> ExceptionLowering       throw/try -> pending value + tests          backend/ExceptionLowering.kt
  -> EscapeAnalysis          objects to the frame, results in place      backend/EscapeAnalysis.kt
  -> ArcInsert               retain/release, minus what is unobservable  backend/ArcInsert.kt (+ Effects, BorrowedReturns)
  -> CEmitter                one .h/.c per class (short functions in the .h), ktc2_all.c, CMakeLists.txt   backend/CEmit.kt
```

- The plugin receives the module's IR after the compiler's front end and before any JVM
  lowering; when it is done it empties the module, so no JVM code is generated.
- **KIR** (`kir/Kir.kt`) is a small typed form: statements (`KLet`, `KSet`, `KFieldSet`,
  `KIf`, `KLoop`, `KReturn`...) over expressions. Calls and allocations are only ever the
  whole right-hand side of a statement, every temporary has a name, control flow is explicit.
- The importer starts at `main` and translates only what is reached. Generic code is
  instantiated per set of type arguments, where every reference type counts as `Any?`: one
  `ArrayList<Any>` serves every list of objects, `ArrayList<Int>` holds ints unboxed. A
  `@Struct` without references gets its own instantiation and is stored in place; one that
  holds references is kept in a box inside generic code and shares the `Any?` instantiation.
- Compile time is the front end reading the library sources: about 13 s for a small program
  in a process of its own (`KTC2_TIMING=1` prints the split). `ktc2` therefore keeps a process
  running between commands (`Daemon.kt`), in which the same compilation takes about half a
  second, because that process also keeps the front end's work on the library
  (`PhasedCompiler.kt`: the library is resolved once; each program is resolved as one more
  part of the compilation on top of it, and the whole is converted to IR again, which is
  fast). The
  command hands its arguments and its directory over a port of the machine itself and prints
  what comes back. The process is replaced when ktc2's own files, the Java machine or an
  environment variable ktc2 reads (`KTC2_*`, `PATH`) differ from what it was started with,
  and ends after 30 minutes without a command. `KTC2_DAEMON=0` runs each command in its own
  process; `ktc2 --stop-daemon` ends the one that stays. A program started by `--run` is
  started by the command, not by that process: it needs the command's terminal.

---------------------------------------------------------------------------
## 3. Types and where values live
---------------------------------------------------------------------------

| Kotlin | C |
|---|---|
| `Int`, `Double`, `Boolean`, `Char` (16-bit)... | the C scalar |
| `Int?` and other nullable numbers | a two-field struct: flag and value (`ktc2_Opt_...`) |
| a class, `Any`, an interface | a pointer to an object that starts with the 8-byte header |
| `String` | a pointer to `{ header, size, length, bytes[] }`: UTF-8, NUL-terminated |
| `Array<T>`, `IntArray`... | a pointer to `{ header, length, elementSize, elements[] }` |
| a `@Struct` class, a final non-generic `value class` | a C struct, by value |
| a lambda, a function reference | an object with an `invoke`; captured variables that are written live in a cell |
| `CPointer<T>` and the other interop pointers | `void*`, never counted |

**The object header** is two 32-bit words: a count with flag bits (`KTC2_STACK`, `KTC2_WEAK`,
`KTC2_ARENA`; `KTC2_IMMORTAL` for static objects) and a type id, the index into the generated
table of type names and `destroy` functions.

**Strings** keep Kotlin's meaning of `length` and `s[i]` (UTF-16 code units): constant time
when the string is all ASCII, a scan otherwise. They are UTF-8, with one addition so that a
string holds any sequence of units: half of a surrogate pair on its own is the three bytes
UTF-8 would give its number, and two halves that meet (a concatenation, a `StringBuilder`)
are written as the character they are, so equal strings have equal bytes. Such a half
leaves the program as `?` (stdout, `encodeToByteArray`), as on the JVM; a first half that
ends what is printed waits for what is printed next, as in the JVM's stream.

**Where an object is stored**, from cheapest:

| Place | When |
|---|---|
| static | string literals, `object`s, enum entries, the boxes of small numbers (-128..127 as `Int` or `Long`, characters below 128, the two Booleans, as the JVM keeps them) |
| the frame | escape analysis shows it does not leave its block: objects, arrays of constant size up to 64 elements, arrays of run-time size up to 256 bytes, strings built from templates up to 128 bytes |
| inside its owner | a `@Value` or `@Size(n)` field; a `@Struct` anywhere |
| the caller's frame | a function that returns a new object has a second form, `name__into(slot, ...)`, that builds it in a slot of the caller. A variable that is given such a result again and again while it still holds the one before (`place = place + step`) has two rooms in the frame of its function, and each call builds in the one that holds no object in use (`ktc2_slot_reuse`: the count in the header says so); with both in use the function is handed no room and makes a heap object. A dispatcher has one too when an implementation behind it has: the caller's slot is then room for the largest of the classes they build (a `union`), and the dispatcher marks it for the implementation it calls (`KFunction.slotClasses`); the implementations without such a form return a heap object as before. This is what keeps the iterator of `for (x in list)` in the frame when `list` is a parameter that receives several classes |
| an arena | allocated inside `arena(size) { }` |
| the heap | everything else |

`--warn-heap` lists every allocation left on the heap, with the reason.

An object "leaves its block" when it is returned, stored into a top-level property, an array
or a field, or passed to something that keeps it. One store into a field does not count: into
an object the same function made, which stays in the frame itself; nor does handing it to a
function that only stores it into the object of another argument (a constructor, a setter:
`Keeper(Item())` held by a local allocates neither), which is recorded per parameter
(`mParamHeld`). Inside that function the object it stores into was passed in and outlives the
call, so what it creates and stores there is not put in its own frame. The same goes for a
function that stores an argument into the new object it returns (`iterator()` keeps the list
it goes over): the argument is held by the local that receives the result (`mResultHeld`), so
a list that is filled and gone over with `for` never leaves the frame. That is what a closure
does with the variables its lambda uses, so a lambda that is only called, not kept, costs no
allocation for itself, for the objects it uses or for the cells of the `var`s it writes. The
condition is that no read of that field anywhere in the program lets the value out (returns
it, stores it, hands it to something that keeps it): `EscapeAnalysis` keeps the set of such
"leaky" fields, by name within a family of generic instantiations. Through a dispatcher an
argument is held as the implementations hold it, and leaves if it does in any of them: a
local list handed to a function that takes a `List` and goes over it stays in the frame.

Destroying an object releases what it holds, which may destroy that in turn. So that a long
list does not need a frame of the C stack per cell, `ktc2_destroy` looks at how far the stack
has grown since the shallowest destruction on the thread: past 96 KB the object is put aside
and destroyed when the stack is back (`DESTROY_STACK` in `ktc2_core.c`).

On the heap, an object of at most 128 bytes does not go through `malloc` and `free`: the
runtime keeps freed blocks in lists by size, per thread, and the next object of that size
takes one. That goes for small strings and arrays too, whose size is read from the object
when it is freed. Larger objects are `malloc`'s. `-DKTC2_NO_POOL`
makes every object a block of `malloc`'s again.

---------------------------------------------------------------------------
## 4. The `ktc2.` package
---------------------------------------------------------------------------

| | |
|---|---|
| `@Struct` on a class | held by value everywhere. Boxed when used as `Any`, an interface or nullable. May hold references. |
| `@SimpleEnum` on an enum class | its values are numbers: a C `enum` (`typedef enum { Key_A = 4, ... } Key;`). The number of an entry is its ordinal, or what it gives for the `Int` or `UInt` parameter of the constructor marked `@EnumValue` (a constant or a `const val`; an unsigned number above 2^31 is written as the negative number with the same bits). `ktc2.enumOf<Key>(number) { it.code }` gives the entry with that number or null: on the JVM it tries the entries, in C it compares the number with theirs. `==` and `when` compare numbers; the `val`s of the constructor are looked up from the number (the arguments have to be constants); `values()`, `valueOf()` and member functions work on numbers. Where Kotlin needs an object (`Key?`, `List<Key>`, `Any`, `Enum<*>`, `entries`) the value is replaced by an object kept for its entry, an ordinary `Enum` with its name and ordinal: one per entry for the whole run, so identity holds and nothing is allocated. `name`, `ordinal`, `toString()` and `compareTo` go through that object. Refused at compile time: an entry with a body, a property with a value of its own, an `init` block, an argument that is not a constant, two entries with the same number. On the JVM it is the enum it is declared as. In KIR it is a `KStruct` with one field and `enumEntries` set, which the emitter writes as the `enum`. |
| `@Value` on a type | a field with it holds the object in place. A local with it must stay in the frame: it is an error if the object could outlive the block. |
| `@Size(n)` on an array | a field with it holds the `n` elements in place; a local with it must stay in the frame. |
| `ValueArray<T>` | an array whose elements are in place. Exists because `Array<@Value T>` cannot be made safe: the annotation does not change the Kotlin type. |
| `arena(size) { }` | allocations in the block come from blocks of `size` bytes. Objects are still counted; one that outlives the arena keeps its block alive. |
| `@Weak` on a property, `WeakRef<T>` | references that do not keep their target alive; they read as null once it is gone. |
| `@Acyclic` on a property | tells the cycle warning this reference never closes a cycle. |
| `ktc2.concurrent` | `Thread`, `thread { }`, `synchronized(lock) { }`, `sleep(ms)`. Kotlin has these on the JVM only. |
| `createCleaner(resource) { }`, `Cleaner` | the way to release something that runs everywhere: Kotlin/Native's `kotlin.native.ref.createCleaner`, with `java.lang.ref.Cleaner` behind it on the JVM. In C it is a `deinit`, and in a private property it costs no object. See "Cleaners" in section 5. |

---------------------------------------------------------------------------
## 5. Reference counting
---------------------------------------------------------------------------

The rules `ArcInsert` applies:

- Parameters are borrowed: the caller keeps the object alive for the call.
- A call, an allocation or a string concatenation hands its result to the caller, counted.
- A local of reference type owns what it holds: declared null at the start of its block,
  released on every way out of it.
- A temporary (the importer's own local: the object built for an argument, `KLocal.isTemporary`)
  that is only passed to functions is released after the statement that uses it last, and a
  result nobody takes at once: what no variable of the program holds ends with its statement.
- Storing into a local, field, top-level property or array element takes a count and
  releases what was there.
- An argument that is not a local is first held in a temporary.

What is left out because nobody can observe it:

- A local read exactly once, by a store or a return, hands its count over. So does a local
  read several times at the last of its reads (`t = T(); t.init(); x.f = t`), when no loop
  lies between its declaration and that read and nothing has borrowed from it.
- A local that is another name for something held at least as long (a parameter, a local
  never reassigned, a `val` field of either) borrows: no retain, no release.
- The same for what is read from a `var` field, an array element or a top-level property,
  when nothing in the rest of the block can overwrite that place. "Can overwrite" comes from
  `Effects`: for each function, what it and everything it calls may write (each field of each
  class, elements of reference arrays as one, each top-level property). C written by the
  program may write anything; so may starting or joining a thread and entering or leaving a
  monitor, since another thread's writes come through there.
- A function that stores a parameter as it is, in a statement of its body that is not inside
  a branch or a loop (`this.next = next`, with no read of the parameter after it), has a second
  form `name__o` in which that parameter is owned like a local: the store takes its reference
  over, and a way out before the store gives it up. A caller at its last use of what it passes
  calls that form and keeps nothing; for the other owned parameters of the same call it takes
  a count to give. Only for calls that name the function: a dispatcher has no such form.
- "Last use" includes a local that is given another value before anything reads it again
  (`head = Node(i, head)`): what it holds is handed over and the local is cleared, so the
  assignment finds nothing to release. This holds inside a loop too.
- A `var` that goes down a structure (`node = node.next`) does not count the objects it
  passes: every value it is given is read, through fields and elements only, from itself or
  from something that holds its object for the rest of the block, and nothing in the rest of
  the block writes a field it follows (`canWalk`). Each object then hangs on the one before.
  Not when it starts from a local at its last use: that one hands its reference over, and the
  walk gives up each object as it leaves it, which is cheaper than giving up the structure
  whole at the end. Not from a temporary either: `var x = T(); x = null` gives up at once.
- A way out of a block (the `return` after a call that failed) does not release the locals
  that no statement before it assigned: they still hold the null they were declared with.
- When something later in the block does overwrite the place, the local may still borrow up
  to its last use, if nothing before that overwrites it: in `x.f = g(x.f)` the old value is
  only needed until `g` returns, and the store comes after. Nothing may in turn borrow from
  such a local.
- A function that only hands out what something else keeps (`list[i]`, a getter,
  `iterator.next()`) has a second form whose result is not counted: `name__b`, or `name__m`
  for a dispatcher where only some implementations qualify. The `__m` form takes one more
  argument, the address of a variable of the caller: `x = List_get__m(list, i, &keep)`. When
  the implementation behind the call borrows, `keep` stays null; when it returns an object of
  its own, that object is also put in `keep`, which the caller releases with the block. See
  `BorrowedReturns`.

Counts are plain until the program starts its first thread, atomic after, and always plain
for objects in a frame.

**Cleaners.** What runs when an object ends is a cleaner: `ktc2.createCleaner(resource,
action)` returns a `Cleaner` whose end runs `action(resource)`. It is the signature and the
rules of Kotlin/Native's `kotlin.native.ref.createCleaner` (the action uses nothing from
around it; a resource that refers back to the holder of the cleaner leaks), with
`java.lang.ref.Cleaner` behind it on the JVM. The three targets differ only in when: C at the
last release, Kotlin/Native and the JVM some time after, on another thread, perhaps never.
There is no other way: ktc2 had a `Deinit` interface of its own, as Swift's `deinit`, which
was removed for this one that every target has.

In C:

- the action runs once, on the thread that gave up the last reference, before the fields of
  the object are released, wherever the object lives (heap, frame, arena). A variable holds
  its object to the end of its block unless it handed it on; a temporary to the end of its
  statement;
- an exception that leaves the action is reported as an uncaught one. An exception already in
  flight is set aside for the call, so the action runs normally while a function that threw
  is being left;
- a weak reference to the object reads null from the moment the count is zero;
- what is still alive when the program ends (held by a top-level property or an `object`) is
  not cleaned: `main` calls `ktc2_ending()`, which waits for the other threads and switches
  the calls off, before it releases those;
- a `@Struct`, a value class or a class held in place by `@Value` cannot have a cleaner: those
  are copied, and a copy has no last reference (an error from the importer).

The general case is a small object the runtime calls when it ends (`ResourceCleaner` in
`Ktc2Lifetime.kt`, which implements `ktc2.internal.Deinit`: the runtime's own interface, not
one for programs). The usual case has none:

```
class Wrapper {
    private val resource = Resource()
    private val cleaner = createCleaner(resource) { it.dispose() }
}
```

A `private val` that nothing in the class reads, initialized by `createCleaner` with a lambda
without captures, is folded into the class (`foldedCleaner` in `KirImport.kt`): the lambda
becomes a function `Class_property__clean(resource)`, and `KClass.cleaners` lists it with the
field the resource is found in when the object ends. When the resource is a `val` property of
the class, as above, that is the property's own field and the cleaner takes no room: a `val`
still holds what it held when the cleaner was declared. For any other resource (a constructor
parameter, a `var`, an expression) the field of the cleaner holds it instead. `settleDeinit`
then writes `Class__ends(self)`, which calls each action with its field, the most derived
class first, and makes it `KClass.deinit`: what the runtime calls (`ktc2_destroy`, through
`ktc2_TypeInfo.deinit`) when the count of an instance reaches zero. Nothing after the
importer knows of cleaners: the runtime, `Effects` and the emitter see a class with a
`deinit`.

For counting, the consequence is that giving up a reference can run the program's code.
`Effects` accounts for it: in a program that has a cleaner, every statement that may give up
a reference (a store over an old value, a local that ends with its block, a result nobody
takes, a runtime function handed references) writes what all those functions write, unless
the type rules it out (a string, an array of numbers, a class whose objects hold only such
things, with its subclasses and the other instantiations of the same generic class). A
program without one is counted exactly as before. The early release of a temporary is the
one release `Effects` does not place: `ArcInsert` leaves it to the end of the block when the
object may have a cleaner and a local that borrows from a writable place is still in use
(`mPlaceBorrowers`).

**Cycles** are not collected. `CycleLint` warns at compile time about classes that can reach
themselves through strong references; `@Weak`, `WeakRef` or `@Acyclic` answer the warning.

Checking it: test builds define `KTC2_LEAK_CHECK` (a report at exit of objects still alive,
and freed objects are overwritten so a wrong borrow fails) and `KTC2_ARC_STATS` (counts of
allocations, retains and releases, written to `build/integration/arc-stats.txt`).
`KTC2_DEBUG_BORROW=1` explains why something was or was not borrowed.

---------------------------------------------------------------------------
## 6. Calls, classes, exceptions
---------------------------------------------------------------------------

- **Inheritance**: a subclass struct starts with its superclass struct.
- **Virtual and interface calls** go through a generated dispatcher: a `switch` on the type id
  that calls the implementation of that class directly. Only classes the program creates
  appear in it. `is` and `as` are tests of the same id. There are no vtables.
- **A dispatched call becomes a direct one** (`Devirtualize`) where the program shows its
  target: one class implements the method, or the class of the receiver is known. The class
  of a value is followed through the whole program, from the allocation through locals,
  parameters, fields, top-level properties and results: a property declared `List<T>` that
  only ever receives an `ArrayList` is known to hold one. The passes after it then see an
  ordinary call: it may not throw, its result may be built in the caller's frame, and it can
  be inlined. This is what keeps the iterator of a `for` over a list in the frame.
  A parameter that receives several classes says nothing by itself, so a small function is
  copied for the calls that pass an object of a known class (`name__c0`, at most four): in
  the copy the parameter is of that class and the calls on it are direct. `total(values:
  List<Int>)` called with an `ArrayList` and with other lists has one copy for the
  `ArrayList` and stays as it is for the rest.
- **Exceptions** are checked propagation: `throw` parks the exception in a thread-local slot
  and the function leaves; after a call that can throw, the caller tests the slot and leaves
  in turn, releasing what it holds. A `try` is a `KTryRegion` in KIR, which `ExceptionLowering`
  turns into a loop that runs once: leaving it is a `break`. There is no `setjmp`.
- **The checks of the language** (array and string index, integer division by zero, null
  assertion, casts) throw the Kotlin exceptions. `--checks=abort` makes index and division
  failures end the program instead. An array access in the loop of `for (x in array)`,
  `for (i in array.indices)`, `for (i in 0 until array.size)`, `Array(n) { }`,
  `while (i < array.size) { …; i++ }` or `for (i in array.size - 1 downTo 0)` is known to be
  in range and carries no check, and so is an index a constant away from the counter when the
  bounds of the loop leave room for it (`a[i + 1]` under `i < a.size - 1`, `a[i - 1]` from
  `i = 1`) (`RangeChecks`).
- **An assignment whose right-hand side fails** leaves its target as it was.
- **Objects, enum classes and the top-level properties of a file** are initialized on first
  use, as Kotlin says. When an initializer only computes and stores, it runs at startup
  instead and costs nothing later. First use from several threads is synchronized.

---------------------------------------------------------------------------
## 7. The standard library
---------------------------------------------------------------------------

The library is the real one, compiled from source with every program:

- `src/main/resources/ktc2/stdlib/files.txt` lists the files of the Kotlin stdlib sources
  that are used unmodified (Gradle fetches the sources jar; the installed tool carries the
  files it needs).
- The `Ktc2*.kt` files next to that list are the **platform layer**: what the JVM normally
  provides (`String`, arrays, `ArrayList`, `HashMap`, `StringBuilder`, `Regex`, `Char`
  classification, math, time, threads), written in Kotlin over a few runtime functions
  (`ktc2.internal`, implemented in `ktc2_core.c`).
- The stdlib jar is still on the compiler's class path, for what is not compiled from source.
  Two mechanisms keep it from getting in the way. Where a file of `files.txt` is compiled from
  source, the jar's class holding the same top-level declarations is hidden automatically, and
  `hidden.txt` lists further classes to hide (JVM-only `actual`s, typealiases to `java.util`):
  otherwise a call would see two candidates and fail to compile as ambiguous. Where a call
  still resolves to a function of the jar that also has a source in the module, the importer
  takes the source (`sourceOf`). A call to a jar function with no source at all is reported
  as "not supported yet: call to ...".

The platform layer's `ArrayList` reads and writes its storage without testing the index
(`Storage.at`, `Storage.put`): it has compared the index with its length, and its storage
only ever grows. Its `add` therefore cannot throw, and a caller does not test for it.

Where the JVM would use an intrinsic, the platform layer asks the runtime: searching and
comparing strings works on their bytes (`indexOf`, `contains`, `split`, `startsWith`,
`replace`; in text that is not ASCII the bytes of whole code points are found where their
UTF-16 units are), and indexing a string that is not ASCII starts from where the last index
of that string was found, so a loop over its characters is linear. Two such places are
remembered, for code that reads two strings side by side. A `StringBuilder` takes a string
at once and writes the digits of a number itself; integers are formatted without `printf`.

An `ArrayList` is sorted in its own storage (a stable merge sort that does nothing to runs
already in order); `subList` is a view of its list (`ListPart`), as on the JVM. A `Regex`
that starts with a character, exactly, only tries to match where that character is.

What is there: collections and sequences (not `sequence { }`; a library function that uses
it on one of its paths, like `chunked` on something that is not a list, compiles and fails
when that path is taken), strings, `Char` with Unicode
for the 16-bit range, `Regex` with Java's syntax (not the `U` flag), ranges, `kotlin.math`,
`kotlin.time` (`Duration`, `TimeSource`, `measureTime`; not `Clock` and `Instant`),
`kotlin.random`, `Result`, the property delegates, `readln`.

Several files are generated; change the generator, not the file:

| File | Generator |
|---|---|
| `Ktc2Arrays.kt` | `tools/gen_arrays.py` |
| `Ktc2Math.kt` | `tools/gen_math.py` |
| `Ktc2Comparisons.kt` | `tools/gen_comparisons.py` |
| `Ktc2Unicode.kt` | `tools/GenUnicode.java`, run with the JDK 21 of the build so the tables match what the tests compare with |

---------------------------------------------------------------------------
## 8. C interop
---------------------------------------------------------------------------

Kotlin/Native's model and syntax. A `.def` file names headers; `interop/Bindings.kt` runs
the C preprocessor (gcc or clang) over them, `CHeaderParser` reads the declarations, and a
Kotlin file of bindings is generated and compiled with the program:

- functions, including variadic ones; structs (fields as properties, by value as `CValue<T>`);
  typedefs; constants and simple macros; global variables;
- enums: a Kotlin enum class when the constants are simply listed, integer constants when
  they are given values (`strictEnums` / `nonStrictEnums` in the `.def` decide otherwise);
- callbacks: `staticCFunction`, also with structs by value (a small C function is generated
  in front of the Kotlin one); `StableRef` to pass an object through C.

A generated binding is an inline function whose body is the C call itself (`cExpr`), so there
is no bridge code. `import platform.posix.*` generates the bindings of the C library on demand.

---------------------------------------------------------------------------
## 9. Modules and building
---------------------------------------------------------------------------

A directory with a `module.ktc2.toml` is a module (`Project.kt`):

```toml
name         = "game"
executable   = "game"                     # default: the name
main         = "game.start"               # default: the one `main`
sources      = ["src"]                    # default: the directory
dependencies = ["sdl3", "../engine", "https://github.com/user/repo.git#dir@tag"]
fetch        = ["SDL3=https://github.com/libsdl-org/SDL.git@release-3.4.8"]
compilerOpts = ["-I${SDL3}/include"]
linkerOpts   = ["-L/opt/lib"]
cmake        = "module.cmake"
files        = ["Api.kt", "lib.def"]      # only in a module shipped with ktc2: its files, since resources cannot be listed
```

- A dependency is a module shipped with ktc2 (`src/main/resources/ktc2/modules/`, today
  `sdl3`), a directory, or a place in a git repository. Repositories are cloned once into
  `~/.ktc2/cache` (`KTC2_CACHE`).
- Output goes to `<module>/build` (or `./out`, or `-o`): `c/` with one `.h`/`.c` per unit
  under its package (a unit is a class, the top-level declarations of one file as `<File>Kt`,
  a lambda, or the box of a `@Struct`), `ktc2_core.h/.c`, `ktc2_config.h` (what this program
  needs of the runtime: threads, checks), `ktc2_main.c`, `ktc2_types.h`, `KtDispatch.c`; a
  `ktc2_build.txt` listing the C files and options; a `CMakeLists.txt`.
- The C compiler is the one `KTC2_CC` names, else the first of gcc, clang and cc on the path,
  for `--build` and for reading headers when bindings are generated. The test suite uses gcc
  unless `KTC2_CC` says otherwise, and its options are gcc's.
- `--build` calls the C compiler directly. `--cmake`, or any
  module with a CMake file, builds through CMake (`KTC2_CMAKE_ARGS` adds arguments). A
  module's CMake file sees the program as `${KTC2_TARGET}` and a fetched repository as
  `${KTC2_FETCH_<NAME>}`.
- A release build is optimized as a whole program (`-flto`, or CMake's interprocedural
  optimization where the compiler has it): a class is one C file, and its small functions are
  only inlined into the others this way. `KTC2_LTO=0`, or `-DKTC2_LTO=OFF` for CMake, turns it
  off; with clang on Windows it needs LLVM's linker, and is used when `lld-link` is on the path.
- Every build passes `-ffp-contract=off` and the runtime header asks the same with a pragma:
  Kotlin rounds after each floating-point operation, and a C compiler would otherwise fuse a
  product and a sum into one where the processor can (ARM64).

---------------------------------------------------------------------------
## 10. The C that comes out
---------------------------------------------------------------------------

C11, nothing beyond libc and the system's threads. Everything specific to one compiler is in
one block at the top of `ktc2_core.h` (atomics, bit counts, `alloca`).

| | State |
|---|---|
| gcc on Windows (mingw) | the whole suite, on every change |
| clang on Windows (MSVC ABI) | the showcase's tests |
| MSVC through CMake | the showcase's tests, threads included; one harmless warning (C4116) per array kept in the frame |
| Apple clang on macOS (ARM64) | the whole suite; the showcase with SDL3 through CMake |
| gcc, clang on Linux | written for, never compiled |
| Android NDK | not there: no console, no `main`; see `PLAN.md` |

---------------------------------------------------------------------------
## 11. Tests
---------------------------------------------------------------------------

`./gradlew test --rerun` (`src/test/kotlin/.../IntegrationTest.kt`): every `integration/**/*.kt` is
translated, built with gcc (`-O1 -Wall -Werror`, leak check, counts) and run; the same file
is compiled and run on the JVM; the outputs must be equal and nothing may leak.

- `<Name>.expected` replaces the JVM for programs that use C (`integration/interop/`);
  `<Name>.def` is the C library of the test; `<Name>.in` its standard input.
- `examples/showcase/core` is run the same way as a program of several modules.
- Test builds (`KTC2_LEAK_CHECK`) fill a freed object with `0xDD` and keep the last few
  thousand of them aside: a retain or a release of one ends the program with "use of a freed
  object", on every system. AddressSanitizer (`-fsanitize=address -DKTC2_NO_POOL` on the C of
  `build/integration/<Name>/c`) finds the reads the fill does not.
- `integration/ported/` are the plain-Kotlin tests of the first transpiler.
- There are no unit tests of the passes: everything is tested through whole programs.

`examples/showcase` is the program that uses a bit of everything, with an SDL3 window. It is
not in the suite (it fetches and builds SDL3). `--headless` runs its tests with no window;
`--auto` opens the window and clicks by itself, which with `SDL_VIDEODRIVER=dummy` needs no
screen, and `--shot file.bmp` saves what it drew.

`benchmarks/` holds six programs (objects, collections, strings, numbers, dispatch,
threads) that `python tools/bench.py` builds at `-O2` and times, with their counts of
allocations, retains and releases; `--compare` shows the change against a saved table.

There is no CI: the only gate is running the suite, on Windows and on macOS.

---------------------------------------------------------------------------
## 12. Known differences and gaps
---------------------------------------------------------------------------

Not Kotlin, by design:
- no coroutines (`sequence { }`, `iterator { }`, `suspend`), no reflection. A call into them
  from the library compiles and throws `UnsupportedOperationException` when it is reached;
- no JDK;
- destruction is immediate, not deferred: visible only through weak references.

Kotlin, but not done:
- `Clock`, `Instant`; the `U` flag and four-letter script codes of `Regex`;
- case conversion of characters beyond U+FFFF;
- `isDaemon`, names and priorities of threads;
- files (to come from kotlinx.io or okio, through a ktc2 compatibility layer).

Known to differ from the JVM:
- the last digit of `exp`, `ln`, `pow`, `sin` and the like is the C library's, which is not
  everywhere the JVM's (`exp(1.0) == E` is false on the JVM and true with Apple's library);
- an entry of a `HashMap` is a new object each time the map hands it out (as on
  Kotlin/Native; on the JVM it is the map's own node, the same every time): two entries for
  one key are equal, not identical, and what one is given by `setValue` after its key was
  removed the other does not see;
- a `HashMap` goes over its entries in the order they were put in, as Kotlin/Native's does;
  the JVM's goes by hash. Kotlin promises neither;
- of reflection there is a class as a value, and no more: `x::class` and `Name::class` can be
  compared, used as keys, asked for `simpleName` and `qualifiedName`, and asked `isInstance`
  for a class (not for an interface: false) (`ClassReference` in `Ktc2Internal.kt`, by the
  names the runtime's table of types has); everything else a `KClass` offers throws;
- what only the JDK has is not there, by the owner's decision: `toSortedMap`, `toSortedSet`,
  `String.format`, `getOrDefault` and `putIfAbsent` of a map, `setCharAt` and `deleteCharAt` of
  a `StringBuilder`. Kotlin/Native has none of them either; a compatibility layer in the
  `ktc2.` package may come later;
- an argument of the wrong kind that reaches a function of a concrete class through an
  unchecked cast (a `Comparator<Int>` handed a `String` as a `Comparator<Any>`) is answered
  with -1, false or null where the JVM throws `ClassCastException`;
- paths and program arguments are UTF-8, which on Windows is right for ASCII only.

A difference from the JVM in plain Kotlin is a bug to fix, not a line for this list: what may
be limited is only what a program asks for with an annotation of the `ktc2.` package.

---------------------------------------------------------------------------
## 13. Inside the code
---------------------------------------------------------------------------

Most fixes of a difference from Kotlin land in the importer, which is also the largest part
(`BodyLowering.kt` and `KirImport.kt` are four tenths of the code). Its sources have KDoc on
nearly every function; this is the map.

**The driver**, around the passes:

- `Main.kt` reads the command; `Project.kt` reads module files, fetches what they name, and
  builds the C (directly or through the `CMakeLists.txt` it writes).
- `Daemon.kt` is the process `ktc2` keeps running: the command sends its arguments to it and
  prints what comes back. `Main.runCommand` is what either of them runs.
- `frontend/K2Frontend.kt` prepares a compilation (the library sources, the bindings, the
  options) and starts the Kotlin compiler: as one call (`K2JVMCompiler.exec`) in a process
  that compiles once, or through `frontend/PhasedCompiler.kt` in one that compiles again and
  again, which runs the compiler's phases itself so that the library is resolved only once.
  Either way the compiler calls `Ktc2Extension.generate` with the IR of the library and the
  program together, and everything below starts there.
- `kir/Kir.kt` is KIR; `KirDump.kt` writes it as text, `KirCopy.kt` copies a function.

**`KirImporter`** (`frontend/KirImport.kt`) owns everything that exists once per program:

- `classFor`, `structFor`, `functionFor`, `globalFor` turn an IR declaration, with its type
  arguments, into its KIR counterpart, creating it on first request. That is the
  monomorphization: the key is the declaration and the `canonical` form of the arguments.
  A function's body is not built at once but queued, so recursion and cycles are no problem.
- `kTypeOf` maps a Kotlin type to a KIR type (`KPrimType`, `KOptType`, `KClassType`,
  `KObjType`, `KArrayType`, `KStringType`, `KStructType`, `KPtrType`, `KUnitType`, `KNothingType`).
- Names in C: package and class joined by `_`, then the function; overloads get `__` and the
  mangled parameter types, instantiations get `__` and the mangled type arguments
  (`kotlin_collections_ArrayList__Any_get`); `uniqueName` settles what is left.
- `dispatcherFor` makes the function that switches on the type id for a virtual or interface
  call; closures and `fun interface` objects are registered so that `invoke` dispatches to them.
- `sourceOf` swaps a function of the stdlib jar for its source; `internalFunction` finds a
  function of the platform layer's `ktc2.internal` package by name; `runtime` declares a
  function implemented in `ktc2_core.c`.
- Initialization on first use (`ensure` functions) is decided at the end, in
  `settleInitialization`.
- `unsupported` records an error with its file and line. Errors do not stop the import: all
  of them are printed, and the count goes back to the driver through a system property.
  `warnings` are printed and stop nothing.
- A `@SimpleEnum` class is a `KStruct` with one field and `enumEntries` (`declareSimpleEnum`);
  the object that stands for a value where one is needed is made by `simpleEnumBox`, the
  lookup of a constructor property by `simpleEnumProperty`.

**`BodyBuilder`** (`frontend/BodyLowering.kt`) builds the KIR of one function body:

- `lowerStatement` and `lowerRhs` walk the IR. An expression is lowered to a KIR expression;
  whatever it needs done first (a call, an allocation) is appended to the current block.
- The invariant it keeps: a call or an allocation is the whole right-hand side of a
  statement. `spill` names a value in a temporary; `lowerPure` lowers and spills what is not
  pure; `lowerOperands` and `protect` name earlier operands when a later one needed
  statements, so evaluation order is Kotlin's.
- `coerce` adapts a value to the type a place wants: casts between reference types, boxing,
  wrapping and unwrapping nullable numbers, struct to box and back. The IR leaves these implicit.
- `lowerCall` is the dispatch for calls: interop intrinsics, `inline` functions
  (`expandInline`: the body is lowered in place, lambdas passed to it are bound and expanded
  by `expandLambda`, never created), members (`memberTarget`), and for what has no source,
  `lowerLibraryCall`: the operations the emitter knows directly (arithmetic, comparisons,
  array and string members, `println`).
- Lambdas that are values become classes with an `invoke` (`makeClosure`). `Captures.kt`
  answers which variables a lambda uses from around it and which of them must live in a
  shared cell because the lambda writes them.

**What each pass may assume of KIR**

| Node | Exists |
|---|---|
| `KThrow`, `KTryRegion` | from the importer until `ExceptionLowering`, which removes them |
| `KAlloc.onStack`, `KArrayNew.onStack`, `KConcat.onStack`, `KStackSlot`, calls to `name__into` (a dispatcher's has `KFunction.slotClasses`), the locals `ktc2_roomN_0/1` | set or added by `EscapeAnalysis` |
| `KRetain`, `KRelease`, `releasesOld` on stores, calls to `name__b` / `name__m` | added by `ArcInsert`; before it no counting exists |
| `KIndexRead.isChecked`, `KIndexSet.isChecked` | set by `RangeChecks` when the index is proven in range, and by `ExceptionLowering` when it put the test in front of the statement |
| calls to a dispatcher | fewer after `Devirtualize`, which replaces the ones it can resolve by `KCall`s to the implementation (under a `KRefCast` when the types of the two differ) |
| functions `name__cN` | copies made by `Devirtualize` for the classes a function is called with; ordinary functions for every pass after it |
| a returned call | always through a local after `ExceptionLowering` (`let x = f(); return x`), whether or not it can throw: `BorrowedReturns` and `EscapeAnalysis` look for the result of a call there |

What a pass produced can be read in the generated C, which follows KIR closely, and in the
dumps: `--dump-ir` writes the Kotlin IR the importer received (`ir.txt`, `ir.kt.txt`) and KIR as text
after the importer and after each pass (`kir/0-import.txt`, `1-devirtualize.txt`,
`2-exceptionlowering.txt`, `3-escapeanalysis.txt`, `4-arcinsert.txt`): the functions of the
program itself, or with `KTC2_DUMP_KIR=<text>` those whose C name contains the text (`all`
for the library's too). `diff` of two stages shows what the pass between them did: a retain
added, a store that now gives up what was there (`<-`), an object put on the stack.

**Where a small mistake costs most**

- `ArcInsert`, `Effects`, `BorrowedReturns`, `EscapeAnalysis`: a wrong "this can be borrowed"
  or "this stays in the frame" is a use of freed memory. Test builds overwrite freed objects
  to make that fail; the cases known to be delicate are in `integration/objects/Borrowing.kt`,
  `LastUse.kt`, `ThreadBorrowing.kt`, `DispatchedResults.kt`, `Reassigned.kt` and
  `ReturnedCalls.kt`. After any change there, compare the counts of the benchmarks and of
  the suite as well as the output: a pass that silently stops applying shows only there.
- `Storage.at` and `Storage.put` in the platform layer do not test the index: they are for
  code that has made sure of it and whose storage cannot shrink meanwhile (`ArrayList`).
  Used anywhere else they are memory errors that only AddressSanitizer reports.
- `PhasedCompiler.kt` calls the Kotlin compiler's internals. A program compiled on the kept
  library that differs from the same program compiled alone points there first
  (`KTC2_KEEP_LIBRARY=0` to compare).
- `Devirtualize`: a wrong "this receiver is of class C" calls the method of another class on
  the object. The static type of a reference is not always its class: the importer shares the
  code of a local class between the instantiations of the generic class around it, so that
  code reads an `X<Int>` through the type `X<Any>` (`KClass.family` names what the
  instantiations have in common; the pass looks for the casts that give this away and then
  trusts only what it followed from the allocation). The same knowledge answers `is` and the
  test of `as` where the class is known (`KInstanceOf.answer`). The classes are followed to a
  standstill twice: at first a call through a dispatcher that cannot be resolved yet says
  nothing of its result (the class of its receiver may still be on its way), and only once
  nothing moves does it say "anything". Said in the first round, it would stay said. `KTC2_SKIP=devirtualize` (also
  `copies`, `listloops`, `ranges`, `handover`, `brief`, `walk`, `owned`, `slots`, `rooms`, `inline`, separated by commas) leaves an optimization out, to find which one a
  wrong program comes from; `KTC2_DEBUG_PASSES=1` prints how much the first two did.
- A generic class is one class in Kotlin and several here: `Box<Int>` stores an int, `Box<String>`
  a reference, and they are `Box__Int` and `Box__Any`. A type that names no number for an
  argument (`Box<*>`, `Box<T>` in code written for objects, `Box<String>`) may be given an
  object of either, so a member of such a receiver is reached through a dispatcher, as a member
  of an interface is (`isErasedReceiver` in `BodyLowering.kt`, `mayBeOtherInstantiation` in
  `KirImport.kt`): the dispatcher switches on the class and turns numbers into boxes and back.
  `this` is exact, and so is a type with a number in it. `x is Box<*>` tests the family of
  classes (`KClassTarget.isAnyInstantiation`). `Devirtualize` makes the call direct again where
  it knows the class, which is nearly everywhere: the benchmarks did not move. Where the
  A value that comes from a cast to a generic class (`other as Pair<A, B>`, which Kotlin only
  tests by the class) is of an uncertain instantiation whatever its type says (`isUncertain`):
  its members are looked up under the type with `Any?` for every argument, a field through
  its getter, and a field compared with `==` is compared as the object the getter gives, so
  that a `Pair<Char, X>` equals the same pair made by code written for objects. Where the
  dispatcher is handed a value the implementation cannot take (a `List<Int>` asked
  `contains("x")`, or seen as a `List<Int?>` and asked `contains(null)`) it answers what a
  lookup that finds nothing answers (`CEmitter.dispatcher`).
- `kotlin.Unit` is nothing at all (`KUnitValue`) until it is needed as an object (`List<Unit>`,
  `"$unit"`, a lambda's result kept as a `T`): then it is `ktc2_unit_object`. As a type
  argument it counts as a reference.
- `ListLoops` finds the platform `ArrayList` by its name and by the names of what it needs
  of it (the fields `length`, `changes`, `array`, the function `checkChanges`): rename one in
  `Ktc2Collections.kt` and the loops silently go back to the iterator. The same for `HashMap`: the
  iterator classes `HashMapEntryIterator`, `HashMapKeyIterator`, `HashMapValueIterator` and
  their field `owner`, the fields `keyArray`, `valueArray`, `changes`, the functions
  `liveFrom` and `checkChanges` (`Ktc2HashMap.kt`), and the accessors of an entry by the
  names `component1`, `component2`, `key`, `value`. The loop it writes
  reads the storage without a test, which holds because the storage never shrinks and every
  change of size adds to `changes`: a new way to change the size has to add to it too.
  It also writes out the calls to `get`, `set` and `size` of an `ArrayList` (found by those
  names, with `checkIndex`): what it writes is what those functions do, so a change to one of
  them in `Ktc2Collections.kt` has to be made in `ListLoops.accessor` too.
- `CEmitter` writes a function in the header of its unit, as `static inline`, when its body
  is at most four lines of C and needs nothing but what that header declares (`inHeader`):
  it prints the body once to find out. A body that would need another unit's header, a
  string or a type id stays in the C file. The names a C file keeps to itself (`lit_3_0`,
  `is_3_0`) carry the number of the unit, so that `ktc2_all.c`, which includes every C file,
  compiles.
- `kotlin.Number` is an abstract class without a class here: the boxed numbers extend it and
  have none. The importer treats it as an interface (`isNumberClass`): a class of the program
  that extends it implements it; a call on a `Number` goes through a dispatcher whose default,
  for what is no class of the program, is the runtime (`ktc2_number_toInt` and the like);
  `is Number` is `ktc2_is_number` or one of those classes. `CharSequence` and `Comparable`
  work the same way for `String` and the boxes.
- A function of the C runtime that can leave with an exception has to be named in
  `kRaisingRuntime` (`Kir.kt`): otherwise the call is not followed by the test, and the
  exception shows up later, somewhere else. There are three today.
- `hidden.txt` and `files.txt`: a class hidden too much or too little shows as an unresolved
  or an ambiguous call inside library sources, far from the cause.
- The copy of the stdlib jar without the hidden classes is cached in the system's temporary
  directory (`ktc2/kotlin-stdlib-<key>.jar`), keyed by the jar and the lists; if a compile
  error in library code makes no sense, delete it.
- Options travel from the driver to the compiler plugin as Java system properties
  (`ktc2.outDir`, `ktc2.main`, `ktc2.checks`...): the compiler is not re-entrant, and a
  property left set leaks into the next compilation in the same process, as in the tests.
- The compiler plugin API of Kotlin is experimental and the version is pinned to a release
  candidate (`2.5.0-RC`): an upgrade is a task of its own.
