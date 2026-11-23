# ktc2 - the original design and the record of what was built

**This is history, not reference.** Sections 2 to 9 are the design as it was written before
any code existed: names, flags and some decisions in them are not what was built (the macros
are not called `KTC_RETAIN`, there is no `--unity`, `Array<@Value T>` was replaced by
`ValueArray<T>`, and so on). Section 10 is the log of the work, entry after entry, oldest
first, each written when the thing was done; later entries correct earlier ones.

For how ktc2 works now, read `DESIGN.md`. For what is left to do, `PLAN.md`. Come here for
the reasoning behind a decision, or for what was tried and dropped.

---------------------------------------------------------------------------
## 1. Where this comes from
---------------------------------------------------------------------------

ktc2 replaces a first transpiler (called v1 below; it is in the history of this repository, up
to the commit that removed it). v1 made everything a *value* by default: a class was a C
struct copied on the stack, sharing and the heap were explicit (`Ref<T>`, `.allocWith(Heap)`,
`freeMem`), and `String` and `Array<T>` were views into the frame that produced them. Because
lifetimes were manual, the compiler had to refuse every pattern that could dangle - returning
a string, aliasing an object, a lambda reading a local - and each refusal was a place where
valid Kotlin stopped compiling. The result was a good *C with Kotlin syntax*, but no existing
Kotlin code compiled with it and none of its code ran on the JVM.

What ktc2 kept from it, in another form: generics by monomorphization (no boxing of numbers
in collections), inline arrays of fixed size (`@Size`), arenas, strings built in the frame,
the bounds and null checks, C interop, and a module system.

---------------------------------------------------------------------------
## 2. Target in one paragraph
---------------------------------------------------------------------------

Plain Kotlin compiles unchanged and behaves like Kotlin: classes, strings, arrays, lambdas and
collections are **references**, kept alive by **automatic reference counting**. The *default*
storage is the heap, but the compiler moves objects to the stack, inline into their owner, or
into an arena whenever it can prove (or the programmer declares) that this is unobservable.
Everything KTC-specific is an **annotation or a wrapper type in the `ktc2.` package** that has a
trivial JVM definition, so the same source also runs under `kotlinc`.

---------------------------------------------------------------------------
## 3. The governing rule: erasability
---------------------------------------------------------------------------

> **A KTC extension may change layout and cost, never observable behavior.**
> Every extension is a declaration in the `ktc2.` package with a trivial JVM definition, such
> that the program prints the same thing under `kotlinc` as under ktc2.

This single rule decides every design question below, and it is testable: the integration
suite gets a **dual-run mode** (compile with `kotlinc`, run on the JVM, diff stdout against the
C build). `kotlinc` becomes the oracle for "is this still Kotlin".

| `ktc2.` declaration | JVM definition (`src/main/kotlin/ktc2/`) |
|---|---|
| `@Value`, `@Struct`, `@Size(N)`, `@Weak`, `@Acyclic`, `@Tls`, `@Namespace`, `@SimpleEnum` | no-op annotations |
| `arena(size) { ... }` | `inline fun <R> arena(inSize: Int, inBlock: () -> R): R = inBlock()` |
| `WeakRef<T>` | wrapper over `java.lang.ref.WeakReference` |
| `Deinit` | plain interface |
| `kotlinx.cinterop.*` (Kotlin/Native syntax) | not available on the JVM (interop code is the non-portable subset; it stays portable to Kotlin/Native) |

The only documented exceptions are C interop and *timing* of destruction (ARC frees earlier
than a GC would - only visible through `WeakRef<T>` and deinit hooks).

---------------------------------------------------------------------------
## 4. Type and storage model
---------------------------------------------------------------------------

Three categories of type, and five places an object can live.

### 4.1 Categories

1. **Primitives** - `Int`, `Float`, `Boolean`, `Char`… plain C scalars. `Int?` stays the
   existing `$Opt` tagged struct. Never boxed inside generics (monomorphization).
2. **Value types** - no identity, freely copied, never ref-counted:
   - `@Struct class Vec2(val x: Float, val y: Float)` (all `val`, fields themselves value or
     reference types),
   - `value class`, `@SimpleEnum` enums, `Pair`/`Triple` of values.
   `===` on a value type is a compile error (same as Kotlin value classes).
3. **Reference types** - everything else: classes, `String`, `Array<T>`/`IntArray`,
   collections, function values, interfaces, `Any`. Identity, aliasing and mutation behave
   exactly as in Kotlin.

### 4.2 Storage classes for a reference-type object

| Storage | Chosen when | RC cost |
|---|---|---|
| **Static** | string literals, `object`, enum entries, top-level constants | none (immortal) |
| **Stack** | escape analysis proves the reference never outlives the frame | none |
| **Inline in owner** | slot declared `@Value T` / `@Size(N)`; or `@Struct` type | none |
| **Arena** | allocated inside an `arena { }` scope | none (bulk freed) |
| **Heap** | everything else - the *fallback*, not the default path | retain/release |

"Autoboxing" in this model is one precise operation: **a stack/inline object is copied to the
heap at the point where a reference to it must escape.** For immutable objects that is
invisible, so it is implicit. For mutable objects it would change aliasing, so it must be
written `.copy()` (which the JVM also executes as a copy - behavior stays identical).

### 4.3 `@Value` and `@Struct`

```kotlin
class Particle(var x: Float, var y: Float)           // ordinary reference class

class Emitter {
	val origin: @Value Vec2 = Vec2(0f, 0f)            // stored inline in Emitter
	val pool: @Size(256) Array<@Value Particle>       // 256 particles inline, no pointers
	val scratch: @Size(64) ByteArray = ByteArray(64)  // inline bytes
}
```

- `@Value T` is **use-site**: this slot stores the object inline. (It is a type annotation,
  not a `Value<T>` wrapper: Kotlin rejects `typealias Value<T> = T`, and a real wrapper class
  would force `.value` on every access and change the code's shape on the JVM.) `@Struct` is
  **declaration-site**: every slot of this type is `@Value`. One mechanism, two spellings.
- A `@Value T` slot can be **read, mutated in place, and passed as a borrowed argument** with
  no copy (`pool[i].x += 1f`, `draw(pool[i])`).
- Anything that would create a second long-lived name for a **mutable** inline object -
  `val p = pool[i]`, `list.add(pool[i])`, `pool[i] = other` - needs `.copy()`. This is today's
  **E071 "no implicit copy" rule, kept on purpose**: it is exactly the condition under which
  ignoring the annotation gives identical behavior on the JVM.
- For **immutable** types (`@Struct`, all-`val` classes) copies and boxes are implicit.
- Layout: the inline object keeps the normal object prefix so any function taking `T` can
  borrow it without specialization. `@Struct` types have no prefix at all (pure C struct).

### 4.4 Strings and arrays

- `String` → pointer to one block `{ header, len, bytes[len+1] }`, UTF-8 with Kotlin (UTF-16)
  indexing semantics (§11) (today's heap `Ref<String>`
  form becomes *the* form). Literals are static/immortal. Template and concat results that do
  not escape are built with `alloca` as today; escaping ones go to the heap in one block.
  NUL-termination is kept so `.cstr` can hand the bytes to C without a copy.
- `Array<T>` / `IntArray` → pointer to `{ header, len, data[] }`. `IntArray(n)` that does not
  escape and has a small or constant `n` is stack/alloca allocated.
- `@Size(N)` on a field or local keeps meaning "inline, fixed": same as today, but it is now a
  storage hint on a normal Kotlin array rather than a different type. Returning one from a
  function is legal - it boxes (heap copy) if the caller lets it escape, and is written
  straight into the caller's slot otherwise.
- Bare C pointers are `CPointer<T>` from `kotlinx.cinterop`: unchecked and unmanaged (§7).

---------------------------------------------------------------------------
## 5. ARC design
---------------------------------------------------------------------------

### 5.1 Runtime (the whole of it)

```c
/** Prefix of every heap object. 8 bytes. */
typedef struct {
	ktc_UInt rc;      /* count + flag bits: IMMORTAL, STACK, ARENA, HAS_WEAK */
	ktc_UInt typeId;  /* index into the generated ktc_typeInfo[] table */
} ktc_ObjHeader;

/** Generated per concrete type. */
typedef struct {
	ktc_UInt size;
	void (*destroy)(void* obj);   /* releases the object's reference fields */
	const char* name;
} ktc_TypeInfo;
```

- `KTC_RETAIN(p)` / `KTC_RELEASE(p)` are inline macros; only the reaches-zero path is a
  function call (`destroy` then free). Estimated ~150 lines of C added to `ktc_core`.
- `destroy` is generated per class (release each reference field). Types with no reference
  fields get `NULL` - release is a bare `free`.
- When the static type is a final class the release calls its `destroy` directly; the
  `typeInfo` lookup is only used through interfaces, open classes and `Any`.
- Every reference is a thin pointer, including interface and `Any` references. A virtual or
  interface call goes through a generated dispatcher that switches on the header's `typeId`
  and calls the implementation of that class directly; only classes the program instantiates
  appear in it. `is` / `as` are switches on the same id. There are no vtables.
- Primitives assigned to `Any` are boxed (a small object holding the value), as in Kotlin;
  nullable primitives (`Int?`) are not: they are a two-field value, flag + primitive.

### 5.2 Ownership rules - where the overhead is removed

Naive ARC retains on every copy. These rules, all decidable by a local or summary-based
analysis, remove most of it:

1. **Parameters are borrowed.** The caller keeps the argument alive; the callee retains only
   if it stores or returns it. A call costs zero RC operations.
2. **Returns are owned.** The callee hands over one count; the caller binds it without a retain.
3. **`val` fields borrow, `var` fields retain.** Reading a `val` field of a live object into
   a local needs no retain (it cannot be overwritten). Reading a `var` field retains unless no
   write or unknown call happens before the local's last use. *Immutability is what makes ARC
   cheap - the same thing Kotlin style already encourages.*
4. **Last use moves.** If a local's final use is a store, a return, or an escaping argument,
   ownership is transferred instead of retain + release.
5. **Static, stack, inline and arena objects are never counted** - elided at compile time
   when the storage is statically known, one flag test otherwise.
6. **Escape analysis promotes to the stack.** Per function, each parameter gets a summary bit
   "escapes" (stored, returned, captured by an escaping closure). The program is whole-program
   compiled, so summaries are a fixpoint over the call graph; an interface method's summary is
   the join over its implementors. `val p = Foo()` passed only to non-escaping parameters is a
   stack struct - which is what KTC emits today, now automatic and safe.
7. **Fresh returns fill the caller's slot.** A function that returns a newly constructed
   object gets a second entry point taking the destination pointer (the existing fused
   alloc + ctor pattern), so `val v: @Value T = make()` and stack-promoted results never touch
   the heap.

A debug build keeps a `STACK` bit in the header and asserts in `KTC_RETAIN`, so a wrong
escape-analysis answer fails loudly instead of corrupting memory. `--arc-stats` prints
retain/release/alloc counts per run, giving each optimization a measurable target.

### 5.3 Cycles

ARC does not free cycles. Two layers:

1. **Compile-time cycle lint.** Build the type graph (field edges between reference types).
   A type that can reach itself through strong fields gets a warning naming the path
   (`Node.parent → Node`). Types that cannot (the large majority) are proven leak-free.
2. **`@Weak var parent: Node?`** and **`WeakRef<T>`** break the reported cycles. A weak read
   yields `null` after the target died. Implemented with a side table used only by objects
   whose `HAS_WEAK` bit is set, so the header stays 8 bytes and unused weak support costs nothing.
   `@Acyclic` on a property tells the lint that it never closes a cycle.
3. A cycle collector was considered (Bacon–Rajan trial deletion over the flagged types) and
   **rejected** to keep the runtime minimal; see §11.

The existing `--mem-track` allocation tracking becomes a per-type leak report at exit and is
turned on for the whole integration suite.

### 5.4 Threads

Non-atomic counts when the program does not reach the thread API (`kotlin.concurrent.thread`,
`java.lang.Thread` - known at compile time); atomic counts when it does. Later refinement: a `SHARED` header bit set when an object crosses
a thread boundary, so only shared objects pay for atomics.

### 5.5 Deterministic destruction

ARC gives it for free, Kotlin has no equivalent. A class may implement `ktc2.Deinit`
(`fun deinit()`), called when the count reaches zero - meant for classes owning C resources.
On the JVM it is not called automatically. `AutoCloseable` + `use { }` remains the portable
way and replaces `defer`.

---------------------------------------------------------------------------
## 6. Avoiding the heap - the toolbox
---------------------------------------------------------------------------

| Tool | Who decides | Notes |
|---|---|---|
| Stack promotion | compiler | non-escaping objects, boxes, closures |
| `alloca` | compiler | non-escaping variable-size strings/arrays under a size cap, outside loops |
| Caller-slot return | compiler | fresh-object returns (rule 7) |
| Static/immortal | compiler | literals, `object`, enum entries, constant `@Struct` tables |
| `@Value T` / `@Struct` | programmer | inline in a field or a local; `ValueArray<T>` and collections for elements |
| `@Size(N)` | programmer | inline fixed arrays and strings |
| `arena(size) { }` | programmer | every allocation in the dynamic scope bumps the arena; freed at block exit |
| Monomorphization | compiler | `List<Int>`, `Map<Int, Vec2>` store elements inline, never boxed |

**Arena details.** `arena(size) { }` pushes an arena on a thread-local stack that the
allocator consults; objects are then carved out of blocks of `size` bytes (a larger object
gets a block of its own, a full block is followed by another). Arena objects carry the
`ARENA` flag and are still counted - an increment and a decrement are cheap, `malloc` and
`free` are what the arena removes. At a count of zero an arena object releases what it refers
to and tells its block; a block is freed once the arena has ended *and* none of its objects
is alive, which normally is the moment the block ends. So an object that outlives its arena
(the block's result, something stored in an outer list) is not a bug and needs no copy: it
keeps its block alive until it is released. The price is one word in front of each arena
object, naming its block. This replaces the earlier plan (uncounted arena objects, result
copied out, a debug check for escapes), which could not be made safe. `--warn-heap` lists every allocation site that stayed on the heap, with the
reason ("escapes via return", "stored in field `x`"), so heap use is always visible and
auditable.

---------------------------------------------------------------------------
## 7. Consequences for existing subsystems
---------------------------------------------------------------------------

- **Closures.** Implicit capture works, as in Kotlin. Non-escaping lambdas keep today's
  inlined / monomorphized functor lowering on the stack. A captured `var` that is written is
  captured by address when the closure does not escape, and moved into a ref-counted cell when
  it does (what `kotlinc` does with `Ref.ObjectRef`). Escaping closures are the existing
  `ktc_Closure` heap form, now ref-counted. `capture(...)` survives only as an optional lint.
- **Inheritance.** A subclass struct starts with its superclass struct, so a pointer to an
  object is a pointer to each of its ancestors. v1's `InheritDesugar` (copying parent members
  into children, fat values that are copies) has no equivalent: objects are shared references.
- **Exceptions.** `longjmp` skips the releases of every frame it crosses, so each throw would
  leak. ktc2 uses **checked propagation** - a may-throw call is followed by a test
  of a thread-local pending-exception slot and a `goto` to the function's cleanup label (the
  model Nim uses with ARC). Whole-program analysis knows which functions can throw, so
  non-throwing call chains pay nothing. It also removes `KTC_TRY_FN` / `optnone`, E132, E133,
  the "extra exception fields dangle" limit (the exception is a normal ref-counted object),
  and allows `try` as an expression. Cost: one branch after each may-throw call; a
  `--checks=abort` mode that makes bounds/null failures fatal keeps the may-throw set small
  for embedded builds.
- **C interop: Kotlin/Native's `kotlinx.cinterop`, same syntax.** A `.def` file names the
  headers; ktc2 generates the Kotlin binding declarations from it, and since the output is C
  the generated functions lower to direct calls with the header `#include`d - no bridge code.
  The API surface is the Kotlin/Native one: `CPointer<T>`, `CValue<T>`, `ByteVar` / `IntVar` /
  `CStructVar`, `memScoped { alloc<T>() }`, `nativeHeap`, `.ptr` / `.pointed` / `.value`,
  `cValue { }`, `.cstr` / `toKString()`, `staticCFunction`, `usePinned` / `refTo` for passing
  managed arrays, and `StableRef` when C keeps a reference to a managed object. C pointers are
  unmanaged and never counted. These declarations live in `kotlinx.cinterop` (not `ktc2.`) so
  Kotlin/Native interop code compiles unchanged; v1's `C.fn`, `c.Type`, `RawArray`, `.cPtr`
  are not carried over.
- **Stdlib.** Rewritten against the Kotlin API shape: `mutableListOf`, `ArrayList<T>()`,
  `HashMap`, `StringBuilder`, `buildString`, `listOf`, `map`/`filter` as inline functions.
  No allocator parameters; `clone/cloneWith` and `dispose` go away.

---------------------------------------------------------------------------
## 8. Output layout - one `.h` / `.c` pair per class
---------------------------------------------------------------------------

Generated C mirrors what `kotlinc` does with `.class` files:

| Kotlin | Generated |
|---|---|
| `package game; class Player` | `game/Player.h` + `game/Player.c` |
| nested `Player.Stats` | `game/Player.Stats.h` / `.c` (`.` instead of the JVM's `$`, which CMake and make mishandle) |
| top-level funs / vals of `Utils.kt` | `game/UtilsKt.h` / `.c` |
| `interface Shape` | `game/Shape.h` + `.c`: its default methods and the dispatchers for its members |
| generic instantiation `ArrayList<Int>` | `ktc2/std/ArrayList_Int.h` / `.c`, emitted once per distinct instantiation |
| program-wide tables | `ktc2_program.c`: `ktc_typeInfo[]`, string-literal pool, `main` |

Why this fits the reference model:

- A reference class is only ever used through a pointer, so other headers need just a forward
  declaration (`typedef struct game_Player game_Player;`). A header includes another header
  only for a supertype, a `@Value T` / `@Struct` / `@Size` field (inline layout), or a
  generic instantiation it stores inline. Inline layouts cannot be cyclic (v1's E010 rule),
  so includes are acyclic by construction.
- The `.h` holds the struct, the method prototypes and the hot `static inline` parts (field
  getters, retain/release of that type); the `.c` holds method bodies, `destroy`, the dispatchers.
- Files are rewritten only when their content changes, so the C build is incremental even
  though ktc2 itself analyses the whole program (escape summaries, monomorphization).
- `--unity` additionally emits a single `all.c` that includes every `.c`, for release builds
  that want cross-file inlining without LTO.

---------------------------------------------------------------------------
## 9. Compiler pipeline
---------------------------------------------------------------------------

v1's `CCodeGen` goes AST → C text in one step and infers types on demand. Ownership and escape
analysis need the type of every sub-expression, every temporary named, explicit control flow
and last-use information, so ktc2 is built around a typed IR.

**Front end: Kotlin K2 (decided 2026-11-11 after a spike, since removed, that showed the IR
could be had this way).**
`K2Frontend` runs `K2JVMCompiler` in-process with an `IrGenerationExtension` and receives the
module IR after FIR→IR, before any JVM lowering. That IR is fully typed and resolved, smart
casts / `?.` / `?:` / `for` are already desugared, and `ktc2.` type annotations are present on
the types. The same run writes the JVM classes used as the reference build.

```
K2 (parse, resolve, typecheck, FIR→IR)
  → KIR import (typed, temporaries explicit; range loops recognized)
  → Inheritance lowering → Monomorphize → ClosureConvert
  → EscapeAnalysis → ArcInsert → ArcOptimize → C emit (one .h/.c per class)
```

What follows from using K2:

- **Std library: the real one, as source.** K2 gives library functions as signatures without
  bodies, so ktc2 compiles the Kotlin stdlib *sources* (Apache-2) together with the program;
  module sources in `kotlin.*` stand in for the jar (`-Xallow-kotlin-package`), so user code
  needs no special imports. The rule is to reuse stdlib files verbatim wherever they are pure
  Kotlin (scope functions, collection and string extensions, ranges, sequences…) and to write
  only the platform layer the JVM normally provides - the storage of `String`, arrays,
  `ArrayList`, `HashMap`, `StringBuilder` - following Kotlin/Native's pure-Kotlin
  implementations over a handful of runtime intrinsics. Anything that exists only in ktc2 lives
  in the `ktc2.` package. The importer starts from `main` and translates only what is
  reachable, so the size of the stdlib does not affect a program that uses little of it.
- **Intrinsics.** Primitive operators, `Array` / `String` members and `kotlin.internal.ir.*`
  comparisons map directly to C in the emitter.
- **C interop needs declarations.** K2 rejects unresolved names, so C functions and types are
  real Kotlin declarations, generated from `.def` files as Kotlin/Native does (§7).
- **Diagnostics.** ktc2-specific errors (cycle lint, the `.copy()` rule) are reported from the
  back end; K2 checker extensions are an option for editor-time feedback.
- **Costs.** A 60 MB build-time dependency, about 3 s per run, and an experimental IR API:
  the Kotlin version is pinned in `build.gradle.kts` and upgraded deliberately. None of
  this reaches the generated C.

---------------------------------------------------------------------------
## 10. Plan
---------------------------------------------------------------------------

Each phase ended green on its own suite and was committed incrementally.

```
DESIGN.md
build.gradle.kts                    depends on kotlin-compiler-embeddable (pinned)
src/main/kotlin/com/bitsycore/ktc2/ the transpiler (frontend/K2Frontend.kt is the K2 hook)
src/main/kotlin/ktc2/               the ktc2. package, JVM definitions (the oracle's classpath)
src/main/resources/ktc2/            runtime C (ktc2_core.h/.c), the platform layer of the library, the bundled modules
src/test/kotlin/                    the test that runs every program of integration/ as C and on the JVM
integration/                        plain-Kotlin programs, C output compared with the JVM's
examples/                           programs made of modules: the SDL3 demo, the showcase
tools/                              generators of the generated sources, and helpers for trying things
```

| # | Phase | Deliverable | Exit test |
|---|---|---|---|
| 0 | Skeleton + oracle | module, `ktc2.` package, K2 driver, and the dual-run test (`IntegrationTest`: every program in `integration/` runs as JVM classes and as C, outputs must match) - **done** | `./gradlew :ktc2:test` |
| 1 | KIR + first C | KIR import from K2 IR; per-class C emitter for primitives, functions, control flow, `@Struct`; `println` and operator intrinsics - **done** (see status below) | arithmetic / control-flow programs match the JVM |
| 2 | Naive ARC | `ktc2_core` header + type table + retain/release at every copy and scope exit; classes, `String`, arrays, interfaces, inheritance, objects, enums, data classes, boxing as references; leak report at exit - **done** | class / string / array programs match the JVM, zero leaks |
| 3 | Generics, closures, std library | generics by monomorphization, inline functions, closures, object expressions; `kotlin.collections`, `kotlin.text`, ranges and sequences compiled from the Kotlin stdlib's own sources - **done** | collection, string and lambda programs match the JVM |
| 4 | Ownership optimization | rules 1–5 of §5.2 in `ArcInsert`; reference-count statistics (`-DKTC2_ARC_STATS`, collected per test in `build/integration/arc-stats.txt`) - **done** (see status) | RC operations per test drop sharply, output unchanged |
| 5 | Escape analysis | whole-program escape summaries; stack promotion of objects, closures, cells, strings and small arrays; `@Value` / `@Size` inline fields with autobox; `--warn-heap` - **done** (see status; caller-slot returns and inline array elements are left) | heap allocations drop where objects are temporary, output unchanged |
| 6 | Exceptions + cycles + threads | checked propagation; cycle lint, `@Weak` / `WeakRef<T>` / `@Acyclic`; threads with atomic counts - **done** (see status; index and division failures are still fatal) | exception programs leak-free; thread programs under atomic RC |
| 7 | Arena + C interop | `arena { }`; `kotlinx.cinterop` API + `.def` binding generator - **done** (see status); SDL3 binds and runs, v1's wrapper module is not ported | v1's interop tests ported and passing |

Phases 1 to 3 are the bulk of the effort; Phase 2 carries the most risk. Phases 4 and 5 are pure optimizations
over a model that is already correct, so each lands rule by rule with numbers attached.

**Status (2026-11-11).** Phases 0–3 are complete: the sixteen programs under `integration/`
match the JVM, and every C run ends with all objects freed (`-DKTC2_LEAK_CHECK`). Phase 4 is
done as well (below).

Phase 1 (`integration/basic/`): all signed, unsigned and floating primitives with Kotlin's
wrapping / saturating / shift semantics and JVM-identical `Double` / `Float` printing;
top-level and extension functions, default and named arguments, recursion, left-to-right
evaluation; top-level properties; `if` / `when` / loops / ranges with `step` / labeled jumps;
`print` / `println`; immutable `@Struct` classes.

Phase 2 (`integration/objects/`): classes with constructors, `init` blocks, properties with
default or custom accessors; `String` (UTF-8 storage, Kotlin indexing, templates, equality,
comparison, hash); nullable references and nullable primitives, `?.`, `?:`, `!!`; abstract and
open classes, interfaces with default methods, `super` calls, `is` / `as` / `as?` and smart
casts; `equals` / `hashCode` / `toString`; `object` and companion objects; primitive and
reference arrays, `vararg`, `for` over arrays; enums (with constructor parameters and entry
bodies, `values()`, `valueOf()`); data classes; primitives boxed when used as `Any`.

How Phase 2 works:
- **Import on demand.** The importer starts at `main` and declares a function or class the
  first time it is reached; touching a file brings in its top-level properties. Virtual calls
  are resolved by a fixpoint: every instantiated class contributes its implementation to the
  dispatchers already requested.
- **Naive ARC (`ArcInsert`).** Parameters are borrowed. Every local of reference type owns its
  value: it is declared null at the top of its scope and released when the scope is left by
  any route, so jumps can neither skip an initialization nor a release. Stores retain the new
  value and release the old one. An argument that is not a local is first held in a
  temporary. Statics (`object`s, enum entries, string literals) are immortal.
- **Lambdas** passed to array constructors are expanded in place; the same mechanism will
  serve `inline` functions in Phase 3.

Phase 3 (`integration/generics/`, `integration/stdlib/`):
- **Generics.** A generic function or class is instantiated per distinct tuple of type
  arguments, where every reference type counts as `Any?` (they are all pointers). `Box<Int>`
  is its own class holding an `int32_t`; `Box<String>` and `Box<User>` share one. Results of
  shared instantiations are cast back to the caller's static type. Dispatchers are per
  instantiation. An unbound type parameter falls back to `Any?`, with boxing at the boundary.
- **Inline functions** are expanded at the call site: arguments are evaluated once into
  locals, lambda arguments are bound unevaluated and expanded at each call, `return` inside a
  lambda returns from the enclosing function as in Kotlin.
- **Function values.** A lambda that is not expanded becomes a closure object: one class per
  lambda with a field per captured value and an `invoke`. Calling a function value is an
  interface call on `FunctionN.invoke` for that signature, so `(Int) -> Int` passes ints
  unboxed. A captured `var` lives in a ref-counted cell shared by the closure and its
  creator; captured `val`s are copied. Local functions and `::references` are closures too.
- **Object expressions and local classes.** A class declared inside a function gets one
  field per outer value it uses; each lowering of the declaration makes its own class (an
  inline function expanded twice yields two). A lambda bound to an `inline` parameter and
  used inside such a class or another lambda (`crossinline`) is expanded there, and what it
  captures travels with the object.
- **Std library from its own sources.** 62 files of the Kotlin stdlib sources jar (unpacked
  by Gradle, listed in `resources/ktc2/stdlib/files.txt`) are compiled, unmodified, with
  every program: scope functions, `kotlin.collections` (lists, maps, sets and their
  extension functions, `ArrayDeque`), `kotlin.text` (`Strings.kt`, `_Strings.kt`, `Char.kt`,
  number conversions, indentation), ranges and progressions, sequences, `Pair` / `Triple`,
  preconditions. Only what a program reaches is translated to C.

How the stdlib is brought in:
- **Multiplatform mode.** The stdlib files are the *common* source set; ktc2's platform
  layer (`resources/ktc2/stdlib/Ktc2*.kt`, about 1,900 lines) is the platform one and
  provides an `actual` for each of their 140 `expect` declarations. K2 checks every one
  against the real API. The layer holds `ArrayList`, the four hash collections (one
  insertion-ordered open-addressing implementation), the `AbstractMutable*` bases,
  `StringBuilder`, `Appendable`, sorting, the builder functions, and the String / Char
  functions (`substring`, `replace`, case conversion, number parsing and formatting).
- **Unboxed storage.** `kotlin.Array<T>` is a reference array as in Kotlin (`Array<Int>`
  holds boxes; the stdlib casts between `Array<T>` and `Array<Comparable<T>>`). The platform
  layer stores elements in `ktc2.internal.Storage<T>`, which ktc2 lowers to a typed block,
  so `ArrayList<Int>` and `HashMap<String, Int>` hold plain ints.
- **A filtered stdlib jar.** K2 still needs the JVM stdlib jar for the built-in types. So that
  it cannot offer a second copy of what the sources declare (or its typealiases to
  `java.util`), ktc2 compiles against a cached copy of the jar without those classes: the
  parts named after each source file, plus `resources/ktc2/stdlib/hidden.txt`.
- **Source wins.** When a call still resolves to a function without a body (an `expect`, or a
  jar function) and the module has one with the same name and signature, the importer uses
  that one.
- **`external` and redirects.** An `external fun` in `ktc2.internal` is implemented by the C
  runtime (`ktc2_<name>`). A library function with no Kotlin body can be provided by a
  function of `ktc2.internal` named after it (`kotlin_collections_asList`); that is how the
  few array functions used so far are covered without the 1 MB `_Arrays.kt`.
- **Erased dispatch.** A call through a star projection (`Collection<*>.size`) builds a
  dispatcher for `Any?`, which accepts every instantiation and boxes or unboxes at that
  boundary.
- **Cost.** The stdlib sources add about four seconds to every compile (K2 analyses them
  each time). Caching that work is the obvious next optimization.

Not ported yet: the array extension functions (`_Arrays.kt`, 1 MB; a handful are provided
by hand), `collections/Arrays.kt`, comparators built from lambdas (`compareBy`, `sortedBy` -
SAM conversion), `Random`, `kotlin.math`. In `kotlin.text`, case conversion and the Char
classification functions cover ASCII and Latin-1 and treat the rest of Unicode
approximately, and `Regex` is declared but throws. `throw` reports the exception and exits
(Phase 6 makes it catchable).

Phase 4 - ownership optimization. Three rules were added to the naive scheme, each removing
counting that cannot be observed:
- an argument that is a `val` field of a safely held object needs no holding temporary;
- a local read exactly once, by a store, hands its reference over instead of sharing it
  (the temporaries the importer introduces all qualify); in its own straight-line scope the
  release of such a local is dropped altogether;
- a returned local is handed to the caller instead of being retained and then released.

Measured on the test programs (operations executed, before → after):

| Program | Allocations | Retains | Releases |
|---|---|---|---|
| objects/Classes | 2025 | 2094 → 1073 | 4135 → 3119 |
| objects/Strings | 65 | 65 → 14 | 148 → 131 |
| stdlib/Collections | 832 | 2514 → 1263 | 4150 → 3079 |
| stdlib/Text | 291 | 1487 → 1113 | 2101 → 1776 |
| generics/Lambdas | 47 | 98 → 65 | 186 → 153 |

Releases still exceed allocations + retains because a release of a local that is null on
that path (hoisted declarations, branches not taken) is counted; those cost a null test.
What remains for later: borrowing a `val` field read into a local, releasing a temporary at
its last use rather than at scope end, and not counting at all for objects that never
escape (Phase 5).

Phase 5 - escape analysis and inline storage (`EscapeAnalysis`, before `ArcInsert`;
`integration/values/`). 17 programs.
- **Escape summaries.** A reference escapes when it is returned, stored into a field, global
  or array element, or passed to a parameter that escapes; the per-parameter summary is a
  fixpoint over the whole program (a dispatcher's is the union of its targets', a runtime
  function is assumed to keep its arguments).
- **Stack promotion.** An allocation whose object, and every copy of its reference, stays
  inside the block of the allocation lives in that block: a class instance, closure or cell
  as a C compound literal, an array of a small constant size likewise, a concatenated string
  in a 128-byte buffer of the block (falling back to the heap when it does not fit). Such an
  object carries a flag in its count: reaching zero releases what it refers to and frees
  nothing.
- **`@Value` and `@Size(N)` fields.** The object or array is embedded in its owner's struct
  (`values_Vec2 position;`, `struct { ktc2_Array header; int32_t data[4]; } history;`).
  Reading the field yields a pointer into the owner, which is kept alive for as long as that
  pointer is in scope; assigning the field copies the value in. The same analysis decides
  what happens to such a pointer: if it stays in its block it is used as is; if it has to be
  kept (stored, returned, passed to a function that keeps it) an immutable object is copied to
  the heap - the autobox - and a mutable object or an array is refused with a message asking
  for an explicit `copy()` / `copyOf()`, because a silent copy would behave differently from
  the JVM.
- **`--warn-heap`** lists every allocation left on the heap with the reason, for example
  `Classes.kt:52: a Node is allocated on the heap: returned`.

Effect on heap allocations (before any promotion → now): `objects/Classes` 2025 → 1020,
`objects/Strings` 65 → 45, `objects/Arrays` 42 → 28, `generics/Lambdas` 47 → 30,
`stdlib/ScopeFunctions` 14 → 7. Collection-heavy programs change little: their allocations
are the collections' own storage, which has to live on the heap.

Not done in Phase 5 (the first two were done later, see the end of this section): returning
a new object directly into the caller's slot, `@Value` on locals; `@Value` on array elements
(dropped, see the end of this section), arrays of variable
size on the stack, and the `.copy()` rule for assigning a *mutable* object into an inline
field from a non-fresh source (the content is copied; on the JVM the two would alias).

Phase 6 - exceptions, cycles, threads (`integration/objects/Exceptions.kt`, `Weak.kt`,
`Threads.kt`):
- **Exceptions by checked propagation** (`ExceptionLowering`, the first back-end pass).
  `throw` parks the exception in the runtime and leaves the function - to the end of the
  enclosing `try`, or by returning a zero value. After each call to a function that may
  throw, the caller tests for a parked exception and leaves the same way. "May throw" is
  computed for the whole program, so call chains that cannot throw carry no test. Leaving is
  expressed with `break` and `return`, which `ArcInsert` already handles: every local of every
  scope that is left gets released, and exception programs are leak-free. `try` is an
  expression; `finally` runs on normal exit, on a throw, and on `return` / `break` / `continue`
  out of it. `kotlin.Throwable` is a built-in base class (message, cause, `toString`); the
  exception classes of the standard library are ordinary Kotlin classes of the platform
  layer (`Ktc2Exceptions.kt`). `!!` and a failed cast throw; an uncaught exception prints the
  JVM-style line and exits with status 1.
- **`@Weak` and `WeakRef<T>`.** A weak reference holds a *weak cell*: a small counted object
  that points at the target without owning it, shared by all weak references to that
  target. The target only carries a bit in its count; when it is destroyed the runtime looks
  its cell up in a side table and clears it, so later reads give `null`. Objects never
  referred to weakly pay nothing, and the header stays 8 bytes. A `@Weak` property has to be
  a nullable reference. Because a weak target dies at its last strong reference rather than
  at some later collection, a program stays erasable only if it does not depend on *when* a
  weak reference turns null.
- **Cycle lint** (`CycleLint`, on by default, `--no-warn-cycles`). Reports each class that
  can reach itself through owning fields, with the path:
  `reference cycle possible: Shape.group -> Group.first -> Shape`. A field of class `D` is
  taken to hold `D` or any subclass; a cycle needs at least one `var` on the way; an
  inherited field is reported once. The answer is `@Weak`, `WeakRef`, or `@Acyclic` on a
  property that by construction never closes a cycle (the `next` of a plain linked list).
  Fields typed as an interface, `Any` or a type parameter - which is how collections hold
  their elements - are not followed, so the lint is silent about cycles through a collection
  or a captured lambda.
- **Threads.** `kotlin.concurrent.thread { }` and `java.lang.Thread` (`start`, `join`,
  overriding `run`) run on operating-system threads, and `synchronized` works. A program that
  reaches the thread API gets `#define KTC2_THREADS` in its generated `ktc2_config.h`: counts
  become atomic, the exception in flight thread-local, and the runtime's own tables locked.
  Every other program keeps plain increments. As on the JVM the process ends when its last
  thread does. As in Swift, a property written by one thread while another reads it needs
  `synchronized` - unsynchronized access is a data race that can corrupt a count, where the
  JVM would only show a stale value.

Not done in Phase 6: atomic counting applies to every object
of a threaded program (the `SHARED`-bit refinement of §5.4 is open).

Phase 7 - arena and C interop (`integration/values/Arena.kt`, `integration/interop/`):
- **`arena(size) { }`** as described in §6. In `values/Arena.kt` 1614 of 1626 allocations
  come from arena blocks, with the same output as on the JVM and nothing leaked.
- **`kotlinx.cinterop`**, written in Kotlin in the platform layer (`Ktc2Cinterop.kt`).
  Every type that stands for a C address - `CPointer<T>`, `NativePtr`, `CValuesRef<T>`,
  `StableRef<T>`, and the variables and structs pointed at (`IntVar`, a struct class) - is
  one KIR type, a bare `void*`: never an object, never counted, and one shared instantiation
  as a type argument. Available: `memScoped`, `nativeHeap`, `alloc` / `allocArray` /
  `allocPointerTo`, `.ptr` / `.pointed` / `.value`, indexing and `+` on pointers, `sizeOf` /
  `alignOf`, `reinterpret`, `toLong` / `toCPointer`, `String.cstr`, `toKString`, `refTo` and
  `usePinned` / `addressOf` on primitive arrays, `StableRef`, and `staticCFunction(::topLevel)`.
- **What interop is built from.** Three functions of `kotlinx.cinterop.internal` are
  replaced by C text: `cExpr<R>("C with $0 $1", a, b)`, `cSizeOf<T>()` / `cAlignOf<T>()`
  (from the `@CType("struct point")` of `T`'s class), and the address of a generated function.
  The library and the generated bindings are ordinary Kotlin over these, so `p.pointed.x = 3`
  ends up as `((struct point*)p)->x = 3` and a bound function as the C call itself. A file
  of bindings names its headers with `@file:CHeaders`; only the C files that contain its
  text include them, so a library's macros do not reach the rest of the program.
- **Bindings from `.def` files** (`interop/CHeaderParser.kt`, `interop/Bindings.kt`).
  `ktc2 main.kt lib.def` preprocesses the headers with the C compiler (`-E -dD`), reads their
  declarations with a C declaration parser of its own, and writes `<out>/interop/<package>.kt`,
  compiled with the program. Keys understood: `headers`, `headerFilter`, `package`,
  `compilerOpts`, `linkerOpts` (also with `.mingw` / `.linux` / `.osx`), and the C section
  after `---`. Functions take `String?` for `const char*` and `CValuesRef<T>?` for other
  pointers, and return `CPointer<T>?`; variadic functions take `vararg` and each call passes
  its extra arguments as they are; structs and unions are classes with a property per field
  (nested structs and arrays in place); a type known only by name is opaque; enums are an
  integer `typealias` plus `const val`s; object-like macros with a number or string value
  are `const val`s. The compiler and linker options go to `ktc2_build.txt` (`cflag=`, `ldflag=`).
- **Structs by value.** `CValue<T>` is a counted block of bytes holding the struct (the KIR
  type of a `ByteArray`), so it is freed like any object and needs no scope. A bound function
  reads a by-value parameter out of it (`*(struct point*)data`) and copies a returned struct
  into a new one. `cValue<T> { }`, `useContents`, `readValue`, `copy { }`, `place` and `write`
  work on it, and a `CValue` passed where C wants a pointer gives the address of its struct.
  One difference from Kotlin/Native: `useContents` runs on the value itself, not on a
  temporary copy, so what the block writes stays.
- **SDL3.** A three-line `.def` over the real SDL3 headers yields 1242 functions (7 skipped, all taking `va_list`);
  a program that initializes SDL, opens a window, polls events and fills an `SDL_FRect`
  compiles with `-Wall -Werror`, links and runs. This is the raw C API; the Kotlin wrapper
  module of v1 (`ktc.sdl3`: Window, Renderer…) is not ported.

Not done in Phase 7: a Kotlin callback (`staticCFunction`) that takes or returns a struct by
value; `staticCFunction` of a lambda that captures; C enums as Kotlin `enum class` (always
the `typealias` form); bindings are regenerated on every compilation. Programs using interop
have no JVM to compare with: their tests compare with a `.expected` file.

After Phase 7 - what the phases above had left open:
- **The language's own failures throw.** An array index out of bounds, a string index out of
  range and an integer division by zero raise `ArrayIndexOutOfBoundsException`,
  `StringIndexOutOfBoundsException` and `ArithmeticException` (`objects/Checks.kt`).
  `ExceptionLowering` puts the test in front of the statement - `if (index is bad) { throw;
  leave }` - and the operation behind it is emitted unchecked, so the cost is the bounds
  check that was there already. A division by a constant other than zero needs no test. An
  operation on one side of a condition (`i < n && a[i] == x`) cannot be tested ahead: it
  keeps its check in the runtime, which parks the exception and yields a harmless value, and
  the statement is followed by the usual test. `--checks=abort` restores the old behavior
  (the program ends), with no test and no "may throw" on the functions involved.
- **Array functions from the standard library's own `_Arrays.kt`** (`stdlib/ArrayFunctions.kt`):
  `map`, `filter`, `sum`, `joinToString`, `sorted`, `toList`… on `Array<T>` and the primitive
  arrays. The 150 functions it leaves to its platform (`copyOf`, `fill`, `sort`, `plus`,
  `contentEquals`, `asList`…) are in `Ktc2Arrays.kt`, written once for `Array<T>` and
  generated per primitive type by `tools/gen_arrays.py`. Sorting is a heap sort for
  primitives and a stable merge sort for objects; `asList` is a view of the array.
- **Inner classes** (`objects/Inner.kt`). An `inner` class holds its enclosing object in a
  field, set when an instance is created and read at the start of each member - the
  mechanism local classes capture with. Its type parameters include those of the classes
  around it. The iterators of the standard library's abstract lists are inner classes.
- **SAM-converted lambdas**: `Comparator { a, b -> … }` (and so `compareBy`, `sortedBy`,
  `sortedWith`). The lambda's object implements the interface itself.
- **A reference implementation behind a primitive dispatcher.** A `Comparator<Comparable<Any>>`
  used, after a cast, to compare Ints is reached by the `Comparator<Int>` dispatcher, which
  boxes the arguments for it and releases the boxes after the call.
- **`Result`, `runCatching` and value classes** (`objects/Results.kt`): `kotlin/util/Result.kt`
  is compiled from the standard library's source; a `value class` is an ordinary class.
- **Interop**: calling through a C function pointer (`pointer(args)`), `staticCFunction` of a
  lambda that uses nothing around it, `cValuesOf(…)` / `toCValues()`, variables declared
  `extern` as properties, and object-like macros valued through the preprocessor (so
  `SDL_WINDOW_RESIZABLE`, defined as `SDL_UINT64_C(0x20)`, is a `const val`).
  **`platform.posix`** is generated from a `.def` of ktc2's own when a program imports it
  (`interop/Posix.kt`: `printf`, `fopen`, `qsort`, `strtol`, `errno`…). `-copt` and `-lopt`
  pass options to the C compiler and the linker; a directory on the command line stands for
  the `.kt` and `.def` files in it.
- **The SDL3 module** (`resources/ktc2/modules/sdl3`, package `ktc.sdl3`): the port of v1's wrapper -
  `SDL3.Window`, `SDL3.Renderer`, textures, cursors, shapes, rounded rectangles, triangles,
  events, keyboard and mouse state, `gameLoop`, the `FPoint` / `FRect` math and 266 constants -
  over the generated `sdl3` bindings. Values such as `FRect` are Kotlin data classes; a
  function that hands one to SDL fills a C struct in its own stack frame, so drawing
  allocates nothing. `example/Demo.kt` opens a window, draws for a second and exits cleanly.
  It is not part of the test suite (it needs SDL3 and a display).

Values on the stack, and Kotlin 2.5 - the latest round:
- **Kotlin 2.5.0-RC.** The front end is the 2.5.0-RC compiler and the library sources are
  those of the 2.5.0-RC standard library. Programs may use what that release has, including
  what it still calls experimental (`integration/basic/Kotlin25.kt`): `companion { }` blocks
  and companion extensions (`companion val Vector.UnitX`), collection literals
  (`val xs: List<Int> = [1, 2, 3]`), full value classes and value objects, name-based
  destructuring (`(val y, val x) = point`), context parameters, guards in `when`. A member of
  a companion block is a function or a variable of the program - no object is involved. The
  reference build on the JVM is compiled with the same options.
- **`@Struct` values everywhere** (`values/StructValues.kt`). A struct is a plain C value:
  in a local, a parameter, a field, or an element of a collection (`ArrayList<Point>` stores
  the points themselves). What was missing is now there: `==` and `!=` (through the `equals`
  the class declares - a data class has one; a struct class without `equals` cannot be
  compared, since on the JVM that would be identity), `hashCode`, `toString`, string
  templates, `copy`, destructuring, secondary constructors and `init` blocks, use as a map
  key or set element, and type arguments in general. Where Kotlin needs an object - the value
  seen as `Any` or as an interface it implements, or a nullable `Point?` - the value is put in
  its *box*, a small class generated per struct that answers `toString`, `equals`, interface
  calls with the struct's own functions. A box that does not outlive its block is itself a
  stack object (comparing two structs allocates nothing). A member called on a struct value is
  called directly, never through its box. `Array<Point>` holds boxes, as `Array<Int>` holds
  boxed ints; collections hold the values.
- **References inside structs** (`values/StructReferences.kt`). A struct may hold a String,
  an object or an array. Such a value owns what it refers to: copying it into a local, a field
  or a box retains each reference (one generated `Entry__retain` per struct), dropping it
  releases them, exactly where a plain reference would be retained and released - `ArcInsert`
  treats "a struct that holds references" as it treats a reference. The escape analysis
  follows references into and out of struct values, so an object referred to by a struct that
  escapes is not put on the stack. Inside generic code (the element blocks of collections)
  such a struct is kept in its box, since those blocks only know how to release objects.
- **Value classes are structs.** A final `value class` whose properties are numbers or other
  such values (`value class Complex(val re: Double, val im: Double)`, `@JvmInline value class
  Meters(val v: Double)`, `value class Email(val address: String)`) is treated exactly like a
  `@Struct` class. Kotlin gives such a class no identity, so nothing observable changes; it
  simply never touches the heap. A generic one (`Result<T>`) stays an ordinary class.
- **`@Size(N)` and `@Value` on locals** (`values/StackLocals.kt`). `@Size(2000) val buffer =
  BooleanArray(2000)` and `val p: @Value Particle = Particle(0f, 0f)` require the array or the
  object to live in the stack frame, whatever its size. If the program then lets it escape
  (returns it, stores it, passes it to something that keeps it), compilation fails with the
  place and the reason, instead of quietly using the heap.
- **Library**: `kotlin.math` (over `<math.h>`, each call expanded in place), the bit
  operations of `Int` and `Long`, `kotlin.random` (the standard library's own XorWow, so a
  seeded generator gives the same numbers as on the JVM), `lazy`, `Delegates`, `Enum.entries`
  (`stdlib/MathAndMore.kt`).
- **Language**: property delegation - `by lazy`, `by Delegates.observable`, custom
  `getValue` / `setValue`, on class, top-level and local properties (`objects/Delegation.kt`);
  `lateinit`; function references in the form the 2.5 compiler produces; `x is IntArray` on
  an `Any`, by the kind of array.
- **SDL3 module**: `FRect`, `Rect`, `FPoint`, `Color` and `FColor` are structs. The demo
  allocates five objects in its whole run.

- **Threads**: `synchronized(object)` is a recursive monitor per object, kept in a table of
  the runtime while the object is locked - two threads holding different objects no longer
  wait for each other. `lazy` computes its value once whichever thread asks first.
- **Delegates** receive a property object that knows its name (`property.name`); anything
  else asked of it fails, there being no reflection.
- **Results built in the caller's frame** (`values/InPlace.kt`) - rule 7 of §5.2. A function
  whose result is always the one object it creates, and which lets that object go nowhere
  else, gets a second form, `make__into(slot, …)`, that builds the object in room the caller
  supplies. A call whose result does not leave its block passes a slot of its own stack frame
  and uses that form; every other call keeps the ordinary one. "Creates" includes receiving
  the object from another function that has such a form, so the room is handed down a chain
  (`midpoint` → `scaled`). A function that returns one of its arguments, or one of several
  objects, has no such form. If the function throws, the slot is simply left unused.
- **Unsigned arrays** (`stdlib/UnsignedArrays.kt`): `UByteArray`, `UShortArray`, `UIntArray`
  and `ULongArray` are the standard library's own classes - value classes over the signed
  array - compiled from its sources together with their functions (`_UArrays.kt`). Being
  value classes over one reference they are structs here: a `UIntArray` is the pointer to its
  `IntArray` and nothing more.
- **`ktc2.ValueArray<T>`** (`values/ValueArrays.kt`): a fixed-size array that stores its
  elements themselves, so structs, value classes and numbers lie side by side in one block.
  It is a type of its own, which is what makes that safe.
- **A waiting monitor sleeps.** A thread that finds its monitor taken sleeps on a condition
  variable until a monitor is given up, instead of spinning.
- **`Regex`** (`stdlib/Regexes.kt`, `Ktc2Regex.kt`): a backtracking matcher written in Kotlin
  with the syntax and behavior of `java.util.regex` for what it covers - classes, anchors,
  groups (numbered, named, non-capturing), alternation, greedy and lazy quantifiers, back
  references, look-ahead and look-behind, `IGNORE_CASE` and `MULTILINE`, the flags `(?i)`
  `(?m)` `(?s)` at the start of a pattern; `find`, `findAll`, `matchEntire`, `matchAt`,
  `replace` (with `$1` / `${name}` or a function), `split`, `escape`. Possessive quantifiers,
  atomic groups, class intersection and flags in the middle of a pattern are refused when the
  `Regex` is created. The nodes of a compiled pattern name their loops by number rather than
  pointing back at them, so a `Regex` is freed like any other object.

- **Objects and enums are created on first use** (`objects/Initialization.kt`), as Kotlin
  specifies. Each access is preceded by a call to the class's `ensure` function, which runs
  the initializer the first time. Once the whole program is imported, the initializers that
  only compute and store - nearly all of them - are identified, their classes go back to
  being initialized at startup, and the `ensure` calls for them are removed: no cost where
  the difference cannot be seen. An initializer that prints, throws, calls out or reads a
  top-level property keeps the exact Kotlin order (and an exception in it reaches the code
  that touched the object). Top-level properties themselves are still initialized at startup,
  file by file.
- **A function value converted to a `fun interface`** (`Comparator(compare)`, `Shaper(f)`)
  becomes an object whose one method calls the function.
- **Variables changed by a `crossinline` lambda** live in a shared cell when that lambda ends
  up inside a closure of the inline function; before, the closure changed a copy.
- **An uncaught exception in a thread** is reported on stderr and ends that thread only.
- **Modules and building.** A directory with a `module.ktc2.toml` (name, sources,
  dependencies, compilerOpts, linkerOpts) is a module; a dependency is a path to another
  module or the name of one shipped with ktc2 (`sdl3`). `ktc2 <module> --build` compiles the
  generated C with the options the bindings ask for, `--run` runs the program (arguments after
  `--`), `--debug` builds without optimization and with the leak report. The installed ktc2
  carries the standard library sources it needs, so it runs without the Gradle project.
- **v1's plain-Kotlin tests** (`integration/ported/`, 36 programs) run under the JVM oracle.
  What they found and what was added:
  `tailrec` (the function becomes a loop: parameters are variables, `return f(args)` assigns
  them and goes round again; a tail call inside a `try` or an expanded inline function stays
  a call), vararg spread (`f(1, *more, 2)`) and a `vararg` given nothing, `x as? Int`,
  `fun main(args: Array<String>)`, `enumValues<T>()` / `enumValueOf<T>()`, calling a function
  reference held in a variable (`val f = ::add; f(1, 2)`), `minOf` / `maxOf`. The eight v1
  tests written in v1's own dialect (`Heap`, `dispose`, `@SimpleUnion`, rune functions) stay
  with v1, as do its `stdlib` tests, which are written against v1's library.
- **Arrays of run-time size in the frame** (`values/StackArrays.kt`). `IntArray(n)` that does
  not leave its block no longer goes to the heap because `n` is not a constant: the frame
  keeps `KTC2_STACK_ARRAY_BYTES` (256) bytes of elements for it and `ktc2_array_local` uses
  them when the array fits, the heap otherwise. The storage belongs to the block, so a loop
  reuses it each round. `@Size` / `@Value` on such an array is still refused: those promise
  the stack, and a size known only at run time cannot.
- **Atomic counts only where two threads can meet.** In a program that starts threads,
  counts were atomic from the first instruction and for every object. They are now plain until
  the first thread is started (`ktc2_threaded`), and always plain for an object in a frame: an
  object a thread can reach is one that left its frame, and escape analysis keeps those on
  the heap.
- **First use from several threads** (`objects/ThreadInitialization.kt`). One thread runs the
  initializer of an object or enum class; a thread that arrives meanwhile waits until it has
  finished, instead of seeing the object half built. The initializing thread itself may come
  back to the object, as before. One recursive lock serves all initializers
  (`ktc2_initEnter` / `ktc2_initExit`); it is taken only until the object is marked done, and
  not at all in a program without threads.
- **Counting that cannot be observed is left out** (`objects/Borrowing.kt`). A local that is
  only another name for something held at least as long borrows it: no retain when it is
  declared, no release when its block ends. That is the case for a copy of a parameter or of
  a local that is never reassigned, and for a `val` field of either. It is also the case for
  what is read from a `var` field, an array element or a top-level property when nothing in
  the rest of the block can overwrite that place; and an argument read from such a place is
  passed without a temporary when the function called cannot overwrite it. "Cannot" comes
  from `Effects`, a whole-program summary of what each function may write, the functions it
  calls included: each field of each class, the elements of arrays of references as one,
  top-level properties one by one. C written by the program and calls through C pointers may
  write anything. So may starting or joining a thread and entering or leaving a monitor:
  another thread's writes come through there, so nothing is borrowed across them
  (`objects/ThreadBorrowing.kt`). A field of a class that is stored inline somewhere is never
  borrowed from: assigning to that storage overwrites the whole object. Over the test programs this removes half
  of the retains (32,900 to 16,400, the thread stress test left aside: its 400,000 are a list
  handing out each element with a count; see the next entry). What still counts: storing into
  a field or an array, and a returned reference the caller cannot borrow. `tools/retain_profile.sh` lists
  the lines of a program that retain most; `tools/arc_compare.py` compares two runs of the
  suite. Test builds (`KTC2_LEAK_CHECK`) now overwrite an object before freeing it, so a
  reference that should have been counted shows up as a failure.
- **Results that are borrowed** (`backend/BorrowedReturns.kt`). A function that only hands out
  a reference something else keeps - `list[i]`, a getter, `iterator.next()` over such a list,
  `append` returning its receiver - gets a second form, `name__b`, whose result carries no
  count. A function qualifies when everything it returns is read from a place reached from
  its parameters, or is the result of a qualifying function called with such arguments, and
  it does not itself write those places; a virtual dispatcher qualifies when every
  implementation behind it in the program does. When only some do - one list computes its
  elements, one iterator returns boxed numbers - the dispatcher's form is `name__m`: it takes
  the address of a variable of the caller, borrows where the implementation allows it, and
  otherwise puts the owned result in that variable as well, which the caller releases with
  the block (a release of nothing on the borrowed path). A function whose result comes from
  such a call passes the address on. The caller uses the borrowed form where the
  result goes into a local that is never reassigned and nothing in the rest of that local's
  block can overwrite the places the result may have come from; otherwise it calls the
  ordinary form as before. Forms are generated only when some caller asks for one. A loop
  that reads the elements of a list (50 elements, 2000 rounds, four threads) went from
  400,000 retains to none. Across the test programs the gain is small (16,400 to 15,500).
  Measured over five of the larger ones, of 729 calls whose result goes into a local, 172
  borrow; 450 return an object of their own (`toString`, `iterator()`, `substring`); 62 pass
  an argument that is itself a temporary; 45 are followed by a write to an array of
  references (a loop that adds to a list), which is where telling arrays apart would help. `KTC2_DEBUG_BORROW=1` prints why a function does not qualify or a caller does not
  borrow.
- **No JDK.** As in Kotlin/Native, a program has nothing of `java.*` or `javax.*`. The Kotlin
  compiler ktc2 runs is the JVM one and cannot be told so (it needs the JDK for the built-in
  types), so `JavaUseCheck` looks for such uses in the files of the program and reports each
  with its line. `java.lang` in the platform layer now holds only the exceptions the runtime
  throws under their JVM names. Files are not part of ktc2's library: they are to come from
  kotlinx.io or okio through a ktc2 compatibility layer, as other kotlinx libraries later.
  Until then a program reads files through `platform.posix`.
- **`ktc2.concurrent`** (`objects/Threads.kt` and the other thread tests): `Thread`,
  `thread { }`, `synchronized(lock) { }` and `sleep(milliseconds)`. Kotlin has these on the
  JVM only, under the names of the JDK, so they are ktc2's, with a JVM side
  (`src/main/kotlin/ktc2/concurrent`) for running a program on the JVM. `kotlin.concurrent.thread`
  does not exist for a program; `kotlin.synchronized` is kept for the library's own code, and a
  program that uses it is told to use the other one.
- **`kotlin.time`** (`stdlib/Time.kt`), from the library sources: `Duration`, `DurationUnit`,
  `TimeSource.Monotonic`, `measureTime`, `measureTimedValue`; and `kotlin.system`'s
  `measureTimeMillis` / `measureNanoTime`. The clock behind them is the clock of the day, not
  a monotonic counter. `Clock` and `Instant` are not included yet. The accessors of top-level
  properties in the jar are now matched to their source, as functions were
  (`Long.sign` was not found before). The JVM the tests compare with runs with an English
  locale: it wrote `150,25m` on a machine set to French.
- **Top-level properties get their values on first use of their file**
  (`objects/TopLevelInitialization.kt`), in the order Kotlin gives: touching a property or
  calling a top-level function of the file runs the file's initializer first, and that
  creates the objects it refers to on the way. As for objects, an initializer that only
  computes and stores runs at startup instead and costs nothing later. The rule for that is
  now stricter, for objects too: such an initializer may not use anything that has an
  initializer of its own, so the ones run at startup do not depend on the order they are run
  in. A use that keeps its `ensure` call makes it only while the initializer has not
  finished (one flag tested in place).
- **A failed assignment leaves its target alone** (`objects/StoreOnFailure.kt`). `x = f()`,
  `obj.p = f()`, `a[i] = f()` and the same with a check of the language inside a condition
  compute the value first; if that throws, the variable, field, element or top-level
  property keeps what it had. Before, it received a zero on the way out.
- **String templates as on the JVM** (`objects/Templates.kt`): every part of a template, or
  of a chain of `+`, is evaluated before the first `toString` runs. `Any()` makes an object.
- **Standard input** (`stdlib/Input.kt`): `readln`, `readlnOrNull`, `readLine`. A test may
  have a `<Name>.in` next to it, given to both the C program and the JVM.
- **C enums as enum classes** (`interop/Enums.kt`). As in Kotlin/Native, an enum whose
  constants are simply listed is bound as a Kotlin enum class: `color_t.COLOR_RED`, `.value`,
  `color_t.byValue(n)`, usable in `when`. Functions, struct fields and variables of that type
  take and give the entry; the conversion is in the generated binding. An enum whose constants
  are given values, which is often a set of flags, stays an integer `typealias` with
  constants. `strictEnums` and `nonStrictEnums` in the .def decide otherwise, by name (`*` for
  all, which the sdl3 module uses to keep its integers). A second name for a value is a
  property of the companion. A function called from C, and a variadic function, see the enum
  as the integer it is.
- **Callbacks with structs by value** (`interop/StructCallbacks.kt`).
  `staticCFunction { v: CValue<vec> -> … }` and a function returning a `CValue` can be handed
  to C. C calls a small function of its own shape, written into the file that takes the
  address, which copies each struct argument into a `CValue` for the call and the returned
  `CValue` out into a struct (zeroes if the Kotlin function left with an exception).
- **Unicode for `Char` and `String`** (`stdlib/Unicode.kt`). Classification (`isLetter`,
  `isDigit`, `isUpperCase`, `category`…), the case of characters and of strings, and digits of
  every script follow Unicode for all a `Char` can hold, with a short way for ASCII.
  `String.uppercase` knows the forms of several characters (`ß` gives `SS`) and
  `lowercase` the final sigma. The tables (`Ktc2Unicode.kt`, 68 KB of text decoded on first
  use) are written by `tools/GenUnicode.java` from the JDK the tests compare with, so both
  answer alike: the test agrees with the JVM on the category and the case of all 65,536
  characters. Characters beyond U+FFFF, held as two `Char`s, are left as they are by the
  case functions.
- **Regex, the rest of the syntax** (`stdlib/RegexExtras.kt`): atomic groups and possessive
  quantifiers, class intersection (`&&`), the flags `i m s x u d` from a place on or for a
  group, and `\p{…}` for the general categories, the POSIX and `java…` classes, the binary
  properties, scripts and blocks. As in Java, `(?i)` alone knows the case of ASCII letters
  only, while `RegexOption.IGNORE_CASE` knows every letter's; `\b` and `\w` are about ASCII.
  Still refused: the flag `U`, and the four-letter codes of scripts.
- **Regex and delegates allocate less.** A search no longer copies the text for each match
  (`findAll`, `replace` and `split` were quadratic in the length of the text), and the
  matcher's inner loops go by index: a `for` over a list made an iterator for every
  character tried. Finding 4,000 matches and replacing 2,000 in one text went from 123,700
  allocations to 14,100. The object a delegate receives as its `property` is one static
  object per property name, in place of a new one at each access.
- **Module file, git and CMake.** A module file now also has `executable` (the program built),
  `main` (the function the program starts with, `pkg.function`), `fetch` (C sources to have at
  hand: `NAME=url@tag`, usable as `${NAME}` in the options) and `cmake` (a CMake file of the
  module). A dependency can be a module in a git repository: `url[#directory][@branch or tag]`.
  Repositories are cloned once into `~/.ktc2/cache` (`KTC2_CACHE` names another place). Every
  compilation writes a `CMakeLists.txt` next to the C: the program, the options the bindings
  ask for, threads, and the CMake file of each module, which sees the program as
  `${KTC2_TARGET}` and a fetched repository as `${KTC2_FETCH_<NAME>}`. `--cmake` builds through
  it, and so does `--build` when a module has a CMake file; `KTC2_CMAKE_ARGS` adds arguments
  (a generator, a toolchain file), `KTC2_CC` names the compiler. Without a CMake file in play
  `--build` still calls the C compiler directly, which needs nothing installed but that.
- **The sdl3 module no longer needs SDL3 installed.** It fetches SDL3 from GitHub
  (`release-3.4.8`); the bindings are made from those headers, and its `module.cmake` builds the
  library from them, statically, unless CMake finds an SDL3 on the system. First build of the
  demo, SDL3 included: one minute here.
- **The showcase** (`examples/showcase`): one program of three modules - a small library
  (`shapes/`), 92 checks in seven groups over the language, the library, errors, values,
  threads and the other module (`core/`, plain Kotlin, so the suite also runs it against the
  JVM as `projects/examples/showcase/core`), and a window with a button per group
  (`src/`, SDL3). `--headless` prints, `--auto` clicks every button by itself and
  `--shot file.bmp` saves what the window shows, which is how it is checked without a screen.
  A click adds everything the group did to a log - each check and what the program gave for
  it - which the wheel, the arrows, Page Up and Down, Home and End and a bar move through;
  `--headless --log` prints the same.
  Writing it found three gaps, now closed: `Number` as a type, a cast of a nullable number to
  its type in generic library code (`Map.getValue`), and library functions that have one path
  through coroutines (`chunked`, `windowed`): those now compile, and fail only if that path is
  taken. `sequence { }` and `iterator { }` themselves are still not supported.
- **Compilers.** The generated C and the runtime are C11 with nothing of one compiler left
  outside one block of `ktc2_core.h`. Built and run here, on Windows:
  gcc 15 (the whole suite), clang 23 with the MSVC ABI (the showcase, no warning), and
  MSVC 19.51 through CMake's Visual Studio generator (the showcase, threads and leak check
  included). For MSVC three things had to change: the `__atomic` operations (now macros, the
  `Interlocked` functions there), the bit-counting builtins, and a division by the constant
  zero, which MSVC refuses even for floating-point numbers, where it is how NaN is written.
  What remains per compiler and system:
  - *gcc, clang on Linux and macOS*: nothing known, but never run. The runtime's POSIX side
    (threads, monitors, sleeping, the clock) has only been read, not compiled. macOS before
    10.15 has no `timespec_get`.
  - *MSVC*: warning C4116 on each array kept in the frame (a struct type written inside a
    cast); the bindings generator runs gcc or clang to read headers, so one of them is still
    needed when a program uses a `.def`; `-pthread`, `-lm` and other gcc-style options in a
    module's `compilerOpts` / `linkerOpts` are not translated, which is what CMake files are for.
  - *Android NDK* (clang and bionic, not installed here): the C should compile as it does
    with clang elsewhere. What is missing is around it: `println` goes to a console Android
    does not have (it should go to the log), a program is a shared library entered from Java
    rather than a `main`, `timespec_get` needs API 29, and the build has to be driven by the
    NDK's CMake toolchain file, which `KTC2_CMAKE_ARGS` can pass.
  - *Everywhere*: paths and arguments are taken as UTF-8, which on Windows is right only for
    ASCII; 32-bit targets have not been considered (pointers kept in `Long`s are fine, the
    object header is the same).
- **Compile time.** The Kotlin compiler was going on to produce JVM classes of the program
  and of the library sources after the translation to C; it is now left with nothing to
  produce. With the JVM settings of the launcher (quick JIT tier, parallel collector) a small
  program compiles in about 11 s instead of 20 s. Nearly all of it is the Kotlin front end
  reading the library sources (`KTC2_TIMING=1` prints the split); generating the bindings of
  a `.def` file takes a fraction of a second, so nothing is cached there. The step that would
  change the order of magnitude is a compiler kept running between compilations.

Known gaps: `isDaemon`, thread names and priorities are ignored.

`@Value` on array elements (`Array<@Value Particle>`) is **not** going to be done as planned.
An annotation does not change the Kotlin type, so such an array could be passed wherever an
`Array<Particle>` is expected - to any function of the standard library - and code compiled
for an array of references would then read object bytes as pointers. Guarding against it
would cost a test on every access to every array of references. What the plan wanted from it
is available another way: collections store structs and value classes in place
(`ArrayList<Point>`), and `ktc2.ValueArray<T>` is a fixed-size array that does. 

**Relation to v1.** The tests of v1 that exercised plain behavior were ported
(`integration/ported/`). v1 itself was removed once ktc2 covered what was wanted of it: what
it had and ktc2 does not is its manual memory model, by design, and a file API, which is to
come from kotlinx.io or okio.

---------------------------------------------------------------------------
## 11. Decisions
---------------------------------------------------------------------------

Decided 2026-11-11:

| Topic | Decision |
|---|---|
| Front end | Kotlin K2 (§9). |
| Mutable inline objects | Allowed. `@Value` slots may hold classes with `var` fields, mutated in place; a second long-lived name needs an explicit `.copy()` (§4.3). `@Struct` stays all-`val`. |
| Exceptions | Checked propagation: a pending-exception test and jump to the cleanup label after each call that can throw (§7). No `setjmp` / `longjmp` in ktc2. |
| Arrays | One pointer to `{ header, len, data[] }`. Inline `@Size(N)` fields carry the same prefix so they pass as ordinary arrays; exact C layouts go through C struct bindings (§4.4). |
| C interop | Kotlin/Native `kotlinx.cinterop` syntax and `.def` files, bindings generated (§7). |
| Strings | Stored as UTF-8, NUL-terminated (`.cstr` is free). `length` and `s[i]` keep Kotlin's UTF-16 meaning: constant time when the string is all ASCII (a header flag), a scan otherwise. `Char` is 16-bit. |
| Std library | Real Kotlin stdlib sources compiled with the program and tree-shaken; own code only for the platform layer; ktc2-only APIs in `ktc2.` (§9). |
| Dispatch | Thin pointers; virtual and interface calls go through a generated `switch` on the object's type id (whole-program), not vtables or fat pointers. |
| Cycles | Compile-time lint + `@Weak` / `WeakRef<T>` only. No cycle collector (§5.3, layer 3 is not planned). |

No decision is open at the moment.

---------------------------------------------------------------------------
## 12. Done since the plan of 2026-11-18
---------------------------------------------------------------------------

A line for each item of `PLAN.md` that is done; how it works is in `DESIGN.md`.

- 2026-11-19, `PLAN.md` section 4 (kotlin.test as a module, a test mode of the compiler): the bundled module
  `kotlin-test` (Kotlin's common sources and Kotlin/Native's `actual`s and test runner, the latter fetched from
  Kotlin's repository by a sparse git upstream); `ktc2 test`, whose program's `main` is Native's runner with the
  suites the compiler registers (`TestRegistry.kt`); the suite's `kotlin` and `libraries` categories by group,
  compared with the JVM test by test: some 25 minutes of the whole suite's 28 on a Mac became about 6. On the way:
  an inline function inside itself lost the target of its outer `return`, a property a module makes weak gave its
  cell through its default getter, a dispatcher of a function returning `Nothing` returned a value
  (`tests/integration/regressions/`, a new place for them).
- 2026-11-19, found by the tests by group: kotlinx.coroutines' `CombineStressTest.testCancellation` used a freed
  object in most runs (13 of 15). ktc2's edit of `DispatchedContinuation.release()`, which set the continuation kept
  for reuse to null to cut a cycle, broke the library's protocol of claiming it (a claim made meanwhile found null:
  "Inconsistent state null", and the coroutine ran on two threads). The edit is gone, and no cycle came back in the
  library's tests. Also: the C of a suspension point wrote and read the coroutine's fields after the call returned
  `COROUTINE_SUSPENDED`, when another thread may run it already; the result is now tested in a C local and that path
  returns at once (`CEmitter.suspendingResult`). `regressions/coroutines-combine-cancel`.
- 2026-11-19, `PLAN.md` 2.1: `ArrayList(...).removeFirst()` stopped Kotlin's compiler when the library was kept apart
  from the program ("No override for ... removeFirst"): the call went to the member Java 21 gives every List. The
  front end now sees the JDK's API as of Java 17 (`-Xjdk-release=17`), as a program on Kotlin/Native sees none:
  `removeFirst`, `getFirst`, `reversed` are the library's extensions there. `regressions/ArrayListRemoveFirst.kt`.
- 2026-11-19, `PLAN.md` 2.1, the owner's choice (a): knots. A job whose result is one of its own handlers was held
  by its declared cycle and by the node `await` left in its list (a ring): neither check freed it. The bounded check
  of rings now follows the classes of declared cycles too, from a check that found its object held by its cycle and
  by more, or from a node of a declared cycle the ring check found held only by its ring (12 objects at most); a
  busy node is held by its job, and walks no further. A first version that let every ring check follow them made
  `async` 30% slower; this one is at the cost it had. `regressions/coroutines-incomplete-async`, without
  `program.cycles` now. Still leaking (PLAN.md 2.2): the other tests of `AsyncTest` and 26 files, whose garbage is
  never seen whole from where a check starts (the deferred does not reach the old list that names it).
- 2026-11-19, `PLAN.md` 3.3 (names), the owner's layout: a folder under `tests/` for each category (`integration`,
  `ktc2`, `stdlib`, `box`, `modules`). The programs of `tests/integration` that only check what they print became
  `@Test` files of `tests/ktc2/<topic>/`, a package each (146 programs; `ported/` dissolved; small files of one subject
  merged, 132 files); what only a whole program shows stayed (`interop`, `errors`, `native`, `process`). The log names
  each line by its category and path, lists each group's files, and ends with the run's time.
  Then the box tests by folder (each file copied in a package of its own with a `@Test` that checks `box()`; the
  files themselves stay as the compiler has them), and the programs of several modules under `integration/projects/`.
- 2026-11-20, `PLAN.md` 2.2: the cycle of a dispatched continuation and the cancellable continuation it keeps for reuse
  declared in the module (`cycles`), where an edit had cut it and broken the library's protocol: 1,578 such cycles left
  in the library's tests to 55, `AsyncTest` without `.cycles`. The check of a pair read a field held in an atomic cell
  as a plain one; it reads it through the cell now. What the base has because of coroutines, looked at again: the
  declared cycles, rings and knots are opt-in (nothing for a program none of whose modules declares one), and the knots
  are still what frees `regressions/coroutines-incomplete-async`.
- 2026-11-20, the CI jobs on Windows: clang links with `lld-link` when it is on the path (Microsoft's `link.exe` refuses
  the 4,700 objects of the stdlib's tests in one line of its command file); `tools/msvc_all.sh` compiles with
  `/bigobj` and a program of more than 1,000 C files in parts (100 files each; one file took hours, parts of 500 more
  than the job's 90 minutes), with a line for each program and its times.
- 2026-11-20, the MSVC job still ran out of its 90 minutes: programs under 1,000 C files went to cl as one file, and
  those of 300 to 400 files with Native's Regex took 13 minutes each. Every program is now compiled in parts of 100
  (16 to 19 s for those).
- 2026-11-20, the escape analysis gave an in-place form (`__into`) to a function whose new object was held by another
  one that escapes (`Worker.requestTermination()`: its future in a job of the worker's queue). The future was then in
  the caller's frame, counted without atomics there and by the worker's thread: a leak in some runs of
  `native/Workers.kt` on Linux, found with ThreadSanitizer. The form now asks the closure of what is kept, as the
  summary did. `tests/ktc2/regressions/ResultHeldByKept.kt` shows it without threads (the list read a dead frame).
  The runtime writes `ktc2_threaded` once only.
- 2026-11-21, the MSVC job's time: `__collect` and `__pair` of a class whose field is typed with a base class (a node of
  Regex: some 60 subclasses) tested each partner one after another, with a load of its count each; cl took 15 minutes
  over each program with Regex. Each field is read once and a switch on the class tells the partners apart: 40 s.
- 2026-11-21, the AddressSanitizer job: the stdlib's tests took 1,960 s, more than the 2,400 s limit on a slower runner;
  `-g1` (lines only) and 3,600 s: 806 s.
- 2026-11-21, the MSVC job's last failure, a leak of `ktc2-values` with cl 14.52 (VS 18.12, the CI's) and not 14.51:
  at `-O1`, cl lost a release after `x = (T){ 0 };` that followed a copy of `x` (the loop of `map` in
  `StructReferences`). Found with the CI's toolset installed here, files then functions compiled at `-Od`, and a probe
  of a count after each line printed. A struct slot is emptied by `KTC2_ZERO_VALUE` (a memset) since.
- 2026-11-21, every test as one program (`KTC2_TEST_CATEGORIES=all`: ktc2's, the stdlib's and the libraries' but
  kotlinx-serialization-json-io's, which are kotlinx-serialization's at another version): its debug build ran 6,488
  tests from 716 cases and passed, without a leak. Its `KtDispatch.c` was 167 MB, which clang's `-O2` did not get
  through in hours: each dispatcher of a library class with no source compiled has a file of its own since, in a
  directory of the class's (`kotlin/Function2__dispatch/invoke__Any_Int_Any__v.c`).
- 2026-11-21, `PLAN.md` 6: 7 of those dispatchers still took clang's `-O2` 6 minutes each: a dispatcher of one signature
  of `Function2` had a case, with its adapters, for each of the 3,120 lambdas of the program that could reach it by
  erasure. A closure of another signature is called through its erased bridge, from a table by class: that file went
  from 1.6 MB to 824 bytes, the C of the program from 485 MB to 386 MB, and the whole suite from 13 to 9.5 minutes.
- 2026-11-21, `PLAN.md` 2.1: `Any().hashCode() != Any().hashCode()` failed in release builds (the identity hash is the
  address, and the second object got the first one's memory). An object whose hash was taken is marked (`KTC2_HASHED`,
  a bit taken from the count, 27 bits left) and its memory waits in a ring of 256 before reuse. A salt mixed into the
  hash, the other option, would have changed the hash of objects still alive.
- 2026-11-21, `PLAN.md` 2.2: a cycle a module declares held only for the first instantiation of a generic class (the
  first class of that name): `CancellableContinuationImpl<Int>` and the `DispatchedContinuation` that kept it for reuse
  were never checked, in 10 of kotlinx.coroutines' test files. Found with the report of what a leak starts from and
  `KTC2_PAIR_TRACE`, after a wrong lead (a cycle closed by a store). `OnCompletionTest`, `BroadcastTest` and `FlowOnTest`
  lose their `.cycles`.
- 2026-11-21, `PLAN.md` 2.2, "a frame field that holds its own coroutine" (`lambda.F_uCont -> lambda`, `F_t_3`, `F_c`): no
  such leak. The report of cycles followed every field of a coroutine's frame, also those that hold no count where it
  waits (`uCont` is the coroutine itself, set without one). `__refs` follows a frame's fields as `__destroy` gives them
  up, by the label it waits at; what those files leave is their tests' own design (a continuation kept in a variable
  the coroutine captures), as their `.cycles` said.
- 2026-11-21, `PLAN.md` 2.2: two neighbouring segments of a channel or a semaphore (`_next`, `_prev`) declared as a cycle of
  the module, on their base class `ConcurrentLinkedListNode`: `SelectBufferedChannelTest`, `SelectMutexStressTest`,
  `CombineParametersTest` and `SelectChannelStressTest` lose their `.cycles`.
- 2026-11-21, `PLAN.md` 2.2: a longer declared cycle that is there but with an object on the way held by more (two cycles
  through one object) answered "not a cycle", and the check of a group (knot) never started from it. It answers "held
  by its cycle and more" now: `WithTimeoutTest` and `WithTimeoutDurationTest` (a job whose result is one of its own
  handlers) lose their `.cycles`. The variables of those checks are named apart from those of the cycles of two
  (`chain`, `seen`), which a class with both could have had twice.
- 2026-11-21, the owner's decision (`PLAN.md`, at its top): what a library needs costs its users only. The checks of
  rings and knots are compiled into a program only when a class of it is of a declared cycle or a ring (`KTC2_KNOTS`),
  the quarantine of identity hashes only when it can take one (`KTC2_IDENTITY_HASH`: 2 % of `BinaryTrees` otherwise).
  `tools/bench.py` against the tool of the day before: the same counts, times within 2 %, programs up to 2 % larger. A
  closure whose case only casts or turns a number into its nullable form, or returns `Unit`, keeps its case in a
  dispatcher of another signature: through the erased bridge it boxed (31 % more allocations in `Ordinary`).
  The note of knots (`PLAN.md` 2.2) was built and taken out: it freed nothing in the files left.
- 2026-11-21, `PLAN.md` 2.3: 25 of the 41 pending box tests of coroutines pass and leave `pending/`. A closure is also a
  target of the dispatchers of `invoke` of `KFunctionN`, `KSuspendFunctionN` and `SuspendFunctionN` (a suspend function
  reference called as a `KSuspendFunction0`); `name` of a `KSuspendFunction` written where it is read; the box tests'
  `OPTIONAL_JVM_INLINE_ANNOTATION` is `@kotlin.jvm.JvmInline`, as the compiler's tests make it for the JVM.
- 2026-11-21, `PLAN.md` 2.3: a `suspend fun interface` made of a function value (`Print(::plain)`, `FlowCollector` of a
  function) called it as a plain function, without its continuation (`tests/ktc2/coroutines/SuspendFunInterfaces.kt`,
  the box test `kt49168`).
- 2026-11-21, `PLAN.md` 2.3: kotlinx.coroutines' native tests (`WorkerTest`, `MultithreadedDispatchersTest`,
  `DelayExceptionTest`, in the module's `nativeTest/`) pass. A test of a file of the C side only (`nativeTest`,
  `nativeCommonTest`) is decided by its own assertions, without the JVM. An edit of `MultithreadedDispatchers.kt`: a
  delayed block is taken out of its holder as it runs (it runs once), which held the continuation it resumes, whose
  state names the block's handle (a cycle of seven objects).
- 2026-11-21, `PLAN.md` 2.3: `kotlinx-coroutines-test` is a bundled module (`runTest`, virtual time, the test dispatchers,
  flows and channels under virtual time: `projects/kotlinx-coroutines-test`). Its scope's exception handlers held the
  scope from the scope's own context: an edit (`TestScope.kt.edit`) has them reach it through a holder the module
  declares weak, which cannot change a result (they are called for a child of the scope, which holds it); the
  scheduler and its time source are a declared cycle.
- 2026-11-21, `PLAN.md` 2.3: kotlinx-coroutines-test's own tests (`tests/modules/kotlinx-coroutines-test/`, with the
  repository's shared helpers) pass, in C and on the JVM. A local of type Unit (`val result = block()` of
  `measureTimedValue` where T is Unit) has no storage: in a coroutine it was a field of type void
  (`tests/ktc2/regressions/UnitLocalInCoroutine.kt`).
- 2026-11-21, `PLAN.md` 2.4: 23 of kotlinx-serialization's 27 `.cycles` go. Half were already free of cycles (the
  fixes of declared cycles and frames of 2.2); the others were the serializer of a generic class and its descriptor,
  now a cycle the module declares for every such serializer (`*.$serializer.descriptor -> ...generatedSerializer`: a
  `*` in a declared step's class). `JsonUpdateModeTest` no longer loops. The okio mode of the JSON tests reads through a
  kotlinx-io stream, as okio's own module does on the JVM: `JsonDecodingErrorMessagesTest`'s two skips go. Left: four
  types that contain themselves, a ring held for the program's life (a known leak in `PLAN.md`).
- 2026-11-21, `PLAN.md` 2.5: an object that holds its delegate twice (`val bDelegate = Delegates.observable(...)`,
  `var b by bDelegate`), whose handler uses the object, was never freed: the check of the pair wanted the delegate held
  once (`tests/ktc2/regressions/DelegateHeldTwice.kt`). `DelegationTest` and `AbstractCollectionsTest` lose their
  `.cycles`. The default `toString()` of a local class names it as Kotlin/Native does, through the compiler's own
  naming lowering (`AnyTest.testToString` passes).
- 2026-11-21, `PLAN.md` 2.3: 8 more of the pending box tests of coroutines pass. A class that implements a `suspend`
  function type (`class C : suspend () -> Int`, an object expression, an interface that extends one) is the `FunctionN`
  that takes the continuation last: `is` and casts hold, and the dispatcher of its `invoke` calls its `suspend` `invoke`.
  A call whose type is Unit gives the Unit object, whatever the function behind it returned (`(::f as () -> Unit)()`;
  `tests/ktc2/regressions/UnitTypedCall.kt`). A `tailrec` function whose calls to itself are in the branches of a
  returned `if` or `when`, or at the end of a block, is a loop there too (100,000 rounds overflowed the stack;
  `TailrecThroughWhen.kt`). Two references to one function holding equal values are equal and hash alike, and a function
  reference knows its name (`FunctionReferenceEquality.kt`). `suspendFunctionAsSupertypeIsCheckWithArity` fails on the
  JVM by design (`.native`).
- 2026-11-21, `PLAN.md` 2.3: the last 7 pending box tests of coroutines pass; none is left in `tests/box/pending/`. A
  waiting coroutine keeps only what it uses after: a frame local dead after its suspension point gives its count to a C
  local before the label is set, released by the run that makes the call (`releaseDeadAtSuspensions`). A coroutine never
  resumed was kept alive by the `SafeContinuation` in its own frame (`infiniteLoopInNextMeaningful`). Six of them keep
  their own continuation in a variable or a list their coroutine holds, by design (`.cycles`). Two mistakes of the first
  form, found by the suite: a local given up after a call that had resumed the coroutine already (a resume inside a call
  before the one tested: the window starts where the label is set), and one read again in the next round of a loop.
- 2026-11-21, the owner's rules of who answers a cycle (`PLAN.md`, at its top): no general collector; the user's cycles
  are warnings. `UserCycleLint` warns, in the program's own files, about the cycles made through coroutines and lambdas
  that the API as it is lets a program avoid; in the suite, exactly the 12 files with such `.cycles` (after leaving out
  builders that call their lambda, jobs that let go of their block, drained lists, the `this` of a named `suspend`
  function). The reaper of kotlinx.coroutines was built and taken out for now (`docs/wip/reaper/`, `PLAN.md` 2.2).
- 2026-11-22, `PLAN.md` 2.2: the reaper of kotlinx.coroutines, in the runtime (`ktc2_reapLater`, `ktc2_reap`, a trial
  deletion over the objects of declared cycles and rings), given the jobs that end with an exception and reaping in
  `runBlocking`'s loops and at the end of `main`; edits for a scope's caller and a lazy coroutine cancelled while made, a
  select's clauses' outer reference weak. Six files of kotlinx.coroutines' tests lose their `.cycles`. Its crash was the
  runtime's: a weak cell given up on one thread while its target ended on another (`weak_drop` reads the mark again under
  the lock); its first failures were a shared channel segment, held from outside, which the all-or-nothing check of a
  release took for a member that keeps its group, and the last test's group, freed only by the reap at the end.
- 2026-11-22, `PLAN.md` 2.2: the last of kotlinx.coroutines' own cycles. A broadcast channel's subscriptions reach it
  weakly (nested classes with a holder, `BroadcastChannel.kt.edit`); two removed list nodes left holding each other by
  concurrent removals are given to the reaper where `correctPrev` gives up (`ConcurrentExceptionsStressTest`). The reaps
  between events are amortized: one through the whole list each time as many were asked for as it holds (a reap at every
  event made a test of 500,000 dispatches time out).
  The whole suite then crashed once in kotlinx.coroutines under load; AddressSanitizer found the walk of a group
  reading a frame field that held no count (`__ring_refs` visited every field of a frame, `__refs` only those its label
  holds): it follows the label now.
- 2026-11-22, the owner: no known leak. kotlinx.serialization's types that contain themselves are the reaper's: each
  descriptor given to it as it is made, the classes of the rings declared, and the reaper's walk going through arrays of
  references (with room for 1,024 objects on the heap, a static counted as outside); the last reap moved after the
  program gives up its globals and objects. The 4 `.cycles` of kotlinx-serialization-json-io go: no library leaks.
- 2026-11-22, `UserCycleLint` follows the lambdas of a `vararg` and what a collection's `add`, `set`, `put` is given
  (`listOf({ ... })` of `ChannelUndeliveredElementFailureTest`, warned now). The documents gone over whole: `PLAN.md`
  (section 1 rewritten, 2.1 to 2.4 done and out of it), `CLAUDE.md`, `README.md`, `tests/README.md`, `DESIGN.md`.
- 2026-11-22, `PLAN.md` 2.5: seven of Kotlin/Native's runtime tests (`tests/stdlib/nativeTest/`, C only) and
  `MathJVMTest`. They found: half a pair encoded as `?` (the JVM's) where Native writes the replacement character, and
  decoded as one replacement for its three bytes where Native gives one for each (ktc2 follows Native now, as its
  `StringEncodingTest` answers say); `lazy(PUBLICATION)` that kept the last value of a recursive initializer where Native
  keeps the first published; `ByteArray.toKString` that did not check its bounds; a `WeakReference` to a boxed number
  that read null at once (a box is kept as long as the reference now: it holds nothing). The publication lazy, made
  of plain fields under a lock so that the reaper can walk it, joins kotlinx.serialization's ring of descriptors
  (its `childSerializers`).
- 2026-11-22, `PLAN.md` 2.5: the rest of Kotlin/Native's runtime tests of its public API (`BitSet`, the typed accessors
  of `ByteArray`, `ImmutableBlob`, the atomic arrays and `AtomicNativePtr` of `kotlin.concurrent`, `MutableData`,
  workers, `Random`), with what they test written after Native's sources. Differences found: `Random.Default` was one
  XorWow shared by every thread (threads that drew at once raced on its state), now Native's generator of each thread;
  a worker's termination left the jobs after it waiting for ever, now cancelled; `Worker.name` of a terminated worker
  answered where Native throws; `kotlin.concurrent.atomics`' classes lacked the members Native keeps as errors
  (`value`, `getAndSet`...), which code suppressing the error still calls.
- 2026-11-22, `PLAN.md` 2.6: the box tests of `basics`, `when`, `controlStructures`, `classes`, `closures`, `strings`,
  `dataClasses` and `enum` (657). They found: a package of the harness named after a keyword (`when`), and files of
  one folder sharing `package test`; a `break` or `return` inside a string template or a loop condition; `String()`;
  `x + "!"` on a type parameter bounded by `String?`; `is Unit`; a `when` whose arm in the middle always applies (a
  crash of the importer); values reached through nesting (a local function called from a local class or from a lambda
  of an `init` block, an outer object read from a chain of objects, an inner class's default argument that uses the
  outer object, a local class created inside a local function), and a member of an object expression built before
  the object's declaration, without its captures; a `finally` that throws after a `return` ran every `finally` again;
  an unchecked cast to a type parameter that checked nothing, let a null through `T : Any`, and checked the type made
  for rather than the bound when its value is dropped; a lambda that captures nothing made anew each time (Kotlin
  has one object); an enum's companion object made apart from its entries; an enum entry with named arguments in
  another order not made at all. The unchecked casts that remain are listed in `PLAN.md` 2.6.
- 2026-11-23, `PLAN.md` 2.1, what that commit left: the companion object of a `@SimpleEnum` started the enum's entries
  as objects, through a constructor a C `enum` does not have (the importer crashed on `tests/ktc2/values`; its entries
  are constants: `regressions/SimpleEnumCompanion.kt`); `box/basics/unchecked_cast8` (an object through an unchecked
  cast to an override that needs more of it: the JVM's bridge throws, ktc2 ends the program at the interface call)
  is a `.skip` of the family of `PLAN.md` 2.2; `box/classes/initializerBlockDImpl` makes a cycle of its own (an inner
  object that adds itself to a list of its outer one), a `.cycles` file: the compiler does not warn of it yet.
  `tools/box_one.sh` finds the repository from its own place and runs without `timeout` where there is none (a Mac).
- 2026-11-23, `PLAN.md` 2.3: the box tests of `ranges` (790; 791 taken, one pending). They found: the step of a loop
  that is not positive ended the program instead of throwing Kotlin's `IllegalArgumentException`, which a program
  catches (a call to `ktc2.internal.checkStep` now); a bound that jumps (`for (j in continue downTo x)`) left a value of
  type void in the loop; `a..b` on a type parameter bounded by an integer was not supported; a name of a C macro
  (`val NAN = Double.NaN`, a field `EOF`) was a C name the preprocessor replaced: such names get a `_`, as C's keywords
  do. `forInIndices/kt43159_ArrayUpperBound` is pending: the JVM's front end refuses an array as a type parameter's
  bound (`FirUpperBoundsChecker`, an error that cannot be suppressed), which Native accepts.
  `tests/ktc2/regressions/RangeEdgesFromBoxTests.kt`.


---------------------------------------------------------------------------
# Archive: the plan as it was kept until 2026-11-18
---------------------------------------------------------------------------

`PLAN.md` until the night of 2026-11-18, kept whole: the log of each day ("Where the work stands"), the
benchmarks of that time, and the tables of optimizations with what was done and measured. The plan to follow
is the new `PLAN.md`; this is only for the reasoning behind a decision and what was tried.

## ktc2 - plan

What is left to do, in the order I would do it within each part. `DESIGN.md` describes what
exists; `HISTORY.md` is the record of how it got there.

Gain and effort are estimates from the code and from the counts measured so far. Nothing here
has been prototyped unless it says so.

How to work on an item: write a program under `tests/integration/<group>/` that shows it (it must
print the same on the JVM), make the change, run `./gradlew test --rerun`, commit when green.
For anything about speed, measure before and after; see "Measuring" at the end.

`[x]` is done, `[~]` is done in part: what is left is said next to it.

---------------------------------------------------------------------------
### Where the work stands (2026-11-18)
---------------------------------------------------------------------------

**State on 2026-11-18 (Windows), night: kotlinx.coroutines' own tests.** They are in the suite
(`tests/modules/kotlinx-coroutines/core`, `tests/README.md`): 170 files of `common/test` and `concurrent/test` with
the common `test-utils`; every one runs and passes its tests in C and on the JVM. 16 files leave cycles of the
library that are not answered yet (`.cycles`, each naming the structure), and 11 cycles by their tests' own design or
of the deprecated `BroadcastChannel`. Found and fixed on the way, in ktc2: a `val` given its value in a
`callsInPlace` lambda read as 0; `String::plus` given as a `suspend` function type made no function value; a null
cast without a check to a type parameter that is a number failed (now its zero, `DESIGN.md`, section 12); a field of
a class below the class being made, through a generic class (`AbstractSharedFlow`, so `SharedFlow`/`StateFlow`
compile now: the members of classes are declared once the outermost `classFor` is done); `removeFirst` on an
`ArrayList` itself; `shuffle()` had a fixed seed (Native's uses `Random`); a state machine that ends in its first
run kept its intercepted continuation (`coroutineRanThrough`); and a double release in a state machine resumed
inside the call that suspends it (an immediate dispatcher): the release of the call's argument ran before the test
for a suspension once a declaration stood in front of the suspension point (`CEmit.withTestsAfterCalls`). In the
library, ten more edits of the module (lazy coroutines let go of their block, `awaitAll`'s nodes and disposer, a
`Mutex`'s resumed waiters, a shared flow's emitters, Native's `SafeCollector` releases its last context, the
channel's handler of undelivered elements, `shareIn`'s sentinel as `Any?`, `Deprecated.kt` compiled) and four
declared cycles (the dispatchers' pool and its lambda: `lambda.self` may now name a step; a handler boxed as a
job's state; a closed channel's cause; an aborted flow). An edit may now name the second of repeated lines
(`replace 2:`). Left, the 16 `.cycles` of the library, by kind: a channel's coroutine cancelled (its exception kept
both as its state and as the channel's cause: two cycles through one object, which no declared cycle frees, and a
ring of those four classes made it worse); a failed `runBlocking` whose exception names a scope coroutine that
keeps its caller's continuation (`ScopeCoroutine.uCont`); a job whose result is its own handler (declared, and
still not freed: to look at with `KTC2_CYCLES=1`); a lazy coroutine cancelled while it is made; the segments of a
channel or a semaphore (`_next`/`_prev`) left unclosed; a select's clauses; the handler lists in threads (below).
Not run here: the CI job, Linux, macOS; the whole suite on Windows before the commit (`build/suite-all.log`).

**Read first (2026-11-18, end of the day, Mac).** Pushed together: eleven commits of the day (from `bcf1aef`,
kotlinx.coroutines as a module, to the rings), each with the whole suite green on the Mac (1,043, clang) and
AddressSanitizer clean on the threaded programs; never run on Windows nor Linux: the CI job of this push is
the first look, read it first. What changed under every program, not only coroutines: atomicfu's atomics are
fields of their owner (`DESIGN.md`, section 6); the monitor table is 64 buckets; paired objects take the global
step only at their end; pair cycles may be declared longer (`cycles`) and rings (`rings`); `Throwable(cause)`
has a message; a `suspend` call returned inside a `try` is no tail call. The paragraphs below, newest first.

**State on 2026-11-18 (Mac), evening: the speed of coroutines, and a correction.** A benchmark of coroutines on
`Dispatchers.Default` (100,000 `async`, a channel, 20,000 cancellations) hung: the cut of a finished job's ring
(below) is not safe while another thread still walks that list; taken out, the rings of jobs with several
handlers leak again (`KOTLINX.md`). It also measured three global locks: atomic references through the monitor
table (an entry made and freed at each operation), the monitor table under one lock with one condition, and
every release of a paired object as one global step. Now each atomic is its own lock (a bit of what it holds),
the table is 64 buckets each with its lock and conditions, and only a release that enters the pair zone takes
the global step (`DESIGN.md`): `async` 4.6 s -> 0.33 s, the channel 0.45 s -> 0.11 s, cancellations 1.6 s ->
0.10 s; the `cycles` entry now costs about nothing. Then the rings, safely: a module may declare a structure
that is a ring of any size (`rings`, a head and its nodes), freed when only its own objects hold it, checked
when its head goes (`DESIGN.md`, Cycles); with it and one more edit (`ChildCompletion`), nothing
kotlinx.coroutines leaves grows with use. Cost: cancellations on `Dispatchers.Default` 0.1 s -> 0.2 s for
20,000 (a check when each job's list goes), the rest unchanged. Next: the library's own tests
(`kotlinx-coroutines-core/common/test`), then the pending box tests of coroutines.

**State on 2026-11-18 (Mac), kotlinx.coroutines' cycles, done.** Nothing that kotlinx.coroutines leaves grows
with use any more (`KOTLINX.md`, kotlinx.coroutines, "Cycles"): what is left is the dispatchers' own, made once.
Last: the rings of a job's list cut once it has ended, a timeout's exception weak on its coroutine, and the
cycle of a coroutine and the exception it ended with declared by the module (`cycles`, a new key: a cycle of
any length freed as a pair is, the same `KPairCycle` with steps between; the owner asked for one reusable
mechanism rather than a case of kotlinx's in the emitter). Next: the library's own tests
(`kotlinx-coroutines-core/common/test`), then the pending box tests of coroutines.

**State on 2026-11-18 (Mac), kotlinx.coroutines' cycles.** The owner chose to answer the library's
cycles with edits of it, after atomicfu's atomics are made fields of their owner, which they are now
(`DESIGN.md`, section 6): the objects left for 1,000 coroutines went from 109,521 to 21,947, and every
cycle left is of two to four objects (`KOTLINX.md`, kotlinx.coroutines, "Cycles": the list, and what is
next). `KTC2_CYCLES=1` on a debug or test build names each cycle and its fields. Then five edits and a
`weak` entry of the module answer the cycles of plain use (`launch`, `async`/`await`, `join`, channels): what
is left no longer grows with the number of coroutines (2,359 objects, the dispatchers'). Next, from flows,
timeouts, cancellation and failures: the circular lists of handlers, a cancelled job and its exception, and
a finished state machine that keeps its locals (ktc2's, `KOTLINX.md`).

**State on 2026-11-18 (Mac), kotlinx.coroutines.** The owner chose coroutines before more optimizations, and
the three choices of the library as Kotlin/Native (below, "Decided by the owner (2026-11-18)"). The first
stages are done (`KOTLINX.md`, kotlinx.coroutines, "State"): the library compiles as it is and a program of
it runs as on the JVM. Found on the way, in ktc2 itself: a `suspend` call returned from inside a `try` was
made a tail call (`Coroutines.kt`, `TailCheck`); `Throwable(cause)` had no message (it is `cause.toString()`);
a dispatcher's own C variable `result` met a parameter of that name. Run: integration, Kotlin and box tests
(744, clang), and the libraries' own tests. Next: the library's cycles (what its program leaves), then its
own tests (`kotlinx-coroutines-core/common/test`), then the pending box tests of coroutines.

**State on 2026-11-18 (Mac), later.** A program of modules is translated again in 1.5 s after a change of one
line (the release's demo; it was 9 s), the showcase in 3.7 s: the compiler process keeps the modules' files
resolved with the library and resolves only the program's own on top (`DESIGN.md`, section 2); it keeps two
such libraries. The suite compiles its programs of modules the same way.

**State on 2026-11-18 (Mac).** Start here, then the evening of 2026-11-17 below. The ways out of a
function share their releases (`ReleaseLadders`, after `ArcInsert`; `DESIGN.md`, section 5): a way out
that returns a constant (after a failed call, mostly) jumps into a ladder of releases written once, each
step at the end of the block that declares its object. The showcase: 8,345 ways out, releases in its C
49,298 to 21,981, its C 331,000 lines to 317,000 (-4 %), the program 6.57 to 6.37 MB; compile time the
same. `tools/bench.py`: counts unchanged, times within the noise. The whole suite on the Mac, 1041 of
1041 (27 min, clang), and AddressSanitizer on every integration program and project (173). Not run on
Windows (MSVC) nor Linux: the CI job of the push that carries it. Left of the "Next" below: a `return`
of a computed value keeps its releases (the result in a variable, then the same ladder, would share
those too); what is left of the size (Native's Regex 2.2 MB, the Unicode tables); fewer C files.

**State on 2026-11-17, evening (Windows).**
- The repository was cleaned of everything a build makes (`tools/clean.ps1`: the build directories,
  `.gradle`, `~/.ktc2/cache`) and built from nothing: the tool, the CI's part of the suite (358 of
  358: integration, and the Kotlin tests in C only), and the showcase (SDL3 and the kotlinx sources
  fetched by ktc2, self-tests 92 of 92).
- clang is ktc2's C compiler first, on every system (the owner, "Decided by the owner"): `cCompiler()`
  takes clang, then gcc, then cc. On Windows clang targets MSVC's C library: a `.def` file's `.msvc`
  lines apply then besides its `.mingw` ones (`DefFile`, `targetsMsvc` in `Bindings.kt`), and
  `posix.def` reads MSVC's `corecrt*.h`, asks for the POSIX names (`_CRT_DECLARE_NONSTDC_NAMES`), and
  its `dirent` (now open to clang, `!defined(__MINGW32__)`) adds `F_OK`. Before, the bindings made by
  clang there had not even `free`. Checked: the kotlinx-io program with files (clang, direct build:
  227 C files in 2.5 s, ThinLTO link 1.0 s) and the showcase through CMake and Ninja (its C in 27 s,
  9 s after a change; gcc's LTO link took 215 s on one thread, mingw's gcc running its parts in
  parallel only through `make`, which is not on the path). The suite keeps gcc and hands it to ktc2
  for the bindings (`compiler/build.gradle.kts`).
- A command lists its modules one a line; `sdl3` has a version (`3.4.8`, the SDL release it fetches:
  `${version}` is expanded in `fetch` too).
- The console of Windows decodes UTF-8 only when told (`[Console]::OutputEncoding`): the test log's
  marks showed as `Ô£ö` in PowerShell, not on the Mac.
- Then MSVC and the suite with clang. MSVC (CMake's default, Visual Studio 18) builds the showcase,
  self-tests 92 of 92 (its C in 127 s). The CI's part of the suite with `KTC2_CC=clang` on Windows:
  358, of which `tests/stdlib/MathTest` fails as with MSVC (`DoubleMathTest.powers`: the last digit
  of `pow` in MSVC's C library, `tools/msvc_known.txt`); it found that MSVC's C library warns of
  `strcpy` and of the POSIX names under `-Werror` (`ktc2_core.h` now asks it not to, before any
  header) and declares `memset`, `strchr` in `vcruntime_string.h` (`posix.def`, `.msvc`).
- The suite now uses clang (the owner: "for test use clang as it was the fastest"): the CI part, 358
  of 358 in 4 min 36 s on Windows (gcc: 5 min 46 s; the C compiler's share 14 minutes in all
  instead of 23). A `.skip` line beginning with `msvc ` leaves a test out on MSVC's C library only
  (`MathTest`'s `pow`). The CI job runs clang too: its first run is the check on Linux and macOS.
- Fewer C files (the owner asked): measured on the showcase. The linker throws away almost nothing
  of what ktc2 writes (0.03 MB of 10.6 MB, with `-ffunction-sections -Wl,--gc-sections`): the
  dispatch and type tables reference what they list, so that measure cannot see past them. What
  it showed is where the size is: one file, `WindowsZoneNamesKt.c` (a `mutableMapOf` of 585
  pairs), was 3.8 MB, 36 %: each element of a `vararg` was stored with a test of its index, whose
  way out released every element made before it, 110,142 releases. `RangeChecks` now takes a
  constant index into an array created with a constant length above it as in range: the file is
  2,393 lines instead of 114,869, the showcase's C 313,000 lines instead of 515,000 (-39 %), its C
  built in 20 s with clang instead of 27.
- **Next:** what a release ladder could still do: a way out of a function releases each live
  object in its own copy (quadratic in the number of live temporaries wherever many tests remain):
  one shared ladder of releases, entered at the right step, would make it linear; then what is
  left of the size (Native's Regex 2.2 MB, the Unicode and decomposition tables) and the number of
  files (one C file per Kotlin file: fewer compiler runs); `tools/msvc_all.sh` on the suite's
  programs; `make` for gcc on Windows, or a warning; then the Mac's "Next" below.

**State on 2026-11-17 (Mac).** What follows from "State at the end of 2026-11-16" is
the day before. That day's items 1 to 4, 6 (in part) and 7 are done, each in its place below:
the MSVC nesting limit (`flatChain`), `dirent` for MSVC (not yet seen by MSVC: the CI's MSVC job is
the check), the showcase on a real screen (pictures in the README), `listOf`'s cost (none left),
ktc2 against Kotlin/Native 2.5.0-RC and the JVM on ten programs ("Where the benchmarks stand"), pair
cycles with threads. Then the repository was put in parts (the owner's layout): `compiler/`
(a Gradle project; its build directory is still `build/`), `modules/stdlib/` (with `runtime/` and
`interop/`), `compat/` (the `ktc2.` package on the JVM, a Gradle project, and on Kotlin/Native),
`modules/<name>/`, and the tests under `tests/` (`integration`, `stdlib` (was `tests/kotlin`),
`common`, `modules/<name>` (was `tests/libraries`, then `tests/libs`), `box`). CLAUDE.md, "Repository layout". Checked
on the new layout: every integration program and project, and one test of each other category; the
CI job of that push is the first run of the rest. Left: the JSON benchmark, the input of the showcase
by hand, MSVC itself, item 5 (the speed of Native's regex engine). Then (owner's design) a module says where
its library's sources come from: `version`, `upstream` (Maven source jars or `git:url@rev`) and `jvm` in
its module file; ktc2 fetches them into `<cache>/modules/<name>@<version>` the first time
(`Project.kt`, `upstreamOf`, `filesOf`); `"name@version"` in `dependencies` asks for another version (with
a warning: kotlinx-io 0.9.2 does not compile with the list written for 0.9.3); a module is usable by its
git path (checked with a `file://` clone of this repository and an empty cache). The tool no longer
carries the libraries' sources, and `gradle/libs.versions.toml` holds Kotlin alone. Then the standard library is a module too
(`modules/stdlib`, which was `stdlib/`): its sources fetched by ktc2 at the compiler's Kotlin version
(`version = "${kotlin}"`), the Regex patch an `edits` file, fetching under a lock (the suite's processes
fetched the same module at once and applied the patch three times). The tool's jar went from 2.2 to 1.0 MB.
A first build on a machine needs the network (Maven Central, or `KTC2_MAVEN`).
Then the tool as a user meets it (`git log 9b5ff57^..e6f08b4`): a command shows its steps on one line each
(`Progress.kt`) and does again only what changed (`ktc2_inputs.txt`, an object for each C file in `obj/`,
the link and CMake's configuration skipped when nothing changed: the demo of the release builds in 20 s,
0.2 s unchanged, 9 s after a change of one line); a build's directory is the program, `c/`, `obj/`,
`modules/` and `gen/` (the bindings and the `nativeMain` copies), nothing of the Kotlin compiler's own; and
settings (`Settings.kt`): default, then `config/ktc2.toml` of the installation (or `KTC2_CONFIG`), then the
environment, then the command (`--set`, `--settings`), with the Maven repositories as `[[maven]]` tables
with credentials, a module's own `[[maven]]` tried first (pushed: `7a089a2`). Then, on macOS, universal
programs (`--universal`, the setting `build.macosArch`): `-arch arm64 -arch x86_64`, and
`CMAKE_OSX_ARCHITECTURES` through CMake (the `sdl3` module then builds its own SDL3 for both). **What was
run on these:** the integration programs (178); a repository behind a login (a local server); universal
builds of the release's demo, the four C interop programs and the showcase (its `--headless`, 92 of 92),
each half run (the x86_64 one through Rosetta) and the same as expected. Not the whole suite, nor Windows
or Linux: the CI job of the push that carries them is the first look. Left for universal programs: the
suite itself built universal (`KTC2_MACOS_ARCH=arm64,x86_64` with the integration programs, each run
under `arch -x86_64` too), and an Intel Mac. **Next, proposed:** the time of a
program made of modules after a change of one line (9 s, most of it the Kotlin front end on the
modules' common sources, which the process that stays could keep as it keeps the library's).

**State at the end of 2026-11-16 (Windows, late evening).** Start here. `git log bd6b7d2..` is
the evening's work, all on `master` and pushed:
- `93b1f7f` the suite in four categories (`KTC2_TEST_CATEGORIES`: `integration`, `kotlin`, `box`,
  `libraries`; `KTC2_TEST_NO_JVM`); library tests under `tests/modules/`, ktc2's own common test
  under `tests/common/`, the stdlib tests' helpers under `tests/stdlib/support/`; the showcase left
  the suite. The CI job runs `integration`, and `kotlin` in C only (macOS had run out of its 90
  minutes); `tools/msvc_all.sh` checks such a file of tests by how it ends. `interop/Geometry`:
  `selectany` on Windows (two copies of a variable before);
- `31a4165` `Regex` is Kotlin/Native's engine (owner's choice), compiled from the Wasm sources jar;
  a cleaner Gradle adds to Native's `Regex.kt` cuts its pattern's cycles (`modules/stdlib/Ktc2RegexCycles.kt`;
  owner's choice over a bounded cycle collector). Naming `ArrayIndexOutOfBoundsException`,
  `StringIndexOutOfBoundsException`, `NegativeArraySizeException` is refused as on Native;
- `0d3005b` cycles of two objects through interface-typed fields are freed (a serializer and its
  descriptor, `left.other = right`), every pair of classes, the library's classes too; a cycle
  warning names the property to mark `@Weak`. `tools/bench.py` counts unchanged;
- `487dda1` the platform layer is `modules/stdlib/` (was `src/main/resources/ktc2/stdlib`), carried as
  resources by `processResources`;
- `3d78f05` the showcase is one SDL3 program of 8 pages (`examples/showcase`, see its module file);
  `--auto` runs every page as a user would, checked here with `SDL_VIDEODRIVER=dummy` and `--shot`:
  all pages, no leak, self-tests green. Built with gcc through Ninja (kotlinx-io's files are POSIX);
- `70605f0` `listOf(a, b...)` is a read-only view of its array as on Native (`ArrayAsList`, which
  `ListLoops` now writes out); Ordinary of `tools/bench.py` is 9 % slower for it (item 4 below);
- `612f37c` `ide/`: source sets an IDE resolves (`modules/stdlib/` read only, `tests/stdlib` read only,
  `tests/box`, `tests/integration/` in parts where each name is once); `./gradlew :ide:compileIntegrationObjectsKotlin`;
- `6f48035` `tools/compare.py`: ktc2 against Kotlin/Native and the JVM (compile, size, peak memory,
  speed). First figures, Numeric, Windows: ktc2 0.252 s / 23 MB / 0.1 MB stripped; Native 2.4.21
  0.276 s / 37 MB / 0.4 MB; JVM 0.290 s / 84 MB / 1.8 MB of jars.

**What was run.** The whole suite (1039: integration 176, kotlin 180, box 384, libraries 299) was
green on Windows on everything up to `0d3005b`. After it, only filtered runs: the `modules/stdlib/` move
(Kotlin tests, regex, pair cycles), `listOf` (collections, list loops, `CollectionTest`,
generics, `ListOfViews`). Not run on WSL/Linux nor the Mac; the CI job of the push is the first
look at them. `bench.py` before/after: in `build/bench/` of the Windows machine only.

**What to do next, in order** (each: a test, the change, the tests that cover it, a commit):
1. (done 2026-11-17, Mac, `eb1e5ab`, not pushed yet) The CI run of `c4c894a` was green on Linux,
   macOS, Windows and AddressSanitizer; MSVC failed on 10 programs with C1061 ("blocks nested too
   deeply"): Native's regex has a `when` over some 180 classes, and each branch, which reads the
   subject again before its test, opened one more block (184 levels). `CEmitter.flatChain` now
   writes a chain of 8 such steps or more at one level (`if (c) { ...; goto chain_end_N; }`); the
   deepest C of the integration programs is 12 levels. Checked with clang, not with MSVC: the
   first push shows it. On a machine that pulled the `Regex.kt` change, delete `build/stdlib-src`
   if ktc2 says "ktc2RegexCleaner ... has no corresponding expect".
2. (done in part 2026-11-17, Mac, not pushed yet) MSVC for kotlinx-io's files. What MSVC lacks is
   only `dirent.h`: the bindings are made on Windows from mingw's headers, whose `open`, `read`,
   `write`, `close`, `access`, `rmdir`, `stat` MSVC's `io.h`, `direct.h`, `sys/stat.h` declare under
   the same names and types, and the constants (`O_*`, `F_OK`, `S_IF*`) are Kotlin constants, not C.
   `posix.def`'s C section (after `---`, only for MSVC) has `DIR`, `struct dirent { d_name }`,
   `opendir`, `readdir`, `closedir` over `_findfirst`/`_findnext`/`_findclose` (no `<windows.h>`);
   `opendir` gives ENOTDIR for a file, `readdir` leaves errno as it was at the end. Its C was
   checked with clang against stand-ins of MSVC's headers (`-Wall -Wextra`); gcc and clang builds
   unchanged (interop programs, `modules/kotlinx-io`, `SmokeFileTest`s). `project-kotlinx-io` and
   the `SmokeFileTest`s are out of `tools/msvc_known.txt`. Left, on Windows: `tools/msvc_all.sh`
   (or the CI's MSVC job), `tools/try_cmake.sh modules/kotlinx-io`, and the showcase with CMake's
   default (MSVC).
3. (done in part 2026-11-17, Mac) The showcase on a real screen and GPU: built with
   `ktc2 examples/showcase --cmake` (Release, link-time optimization; SDL3 fetched and built, 76 s in
   all), `--headless` 92 of 92, `--auto --shot` on the Mac's display: every page shown, no leak,
   menu 81 fps, Bunnies 32 (a million: moved in 0.3 ms, drawn in 18.6 ms in 64 calls), Particles 99,
   Physics 59 (4000 balls), Fractal 24 (29 ms a picture on 8 threads), Life 100, Data 98, Self-tests
   100. Four pictures are in the README (`docs/showcase/`). The number beside the frame rate is the
   longest frame of the last second: under `--auto` it shows the frame that saved the picture
   (100 ms). Left for a person at the keyboard: the input of each page (the keys of its bottom
   line, the mouse on Physics and Fractal, the buttons of Data), F2 (vsync on and off), and the
   same on Windows and Linux. Ideas from it: the drawing of the bunnies is most of their frame
   (SDL's renderer, 64 calls); the fractal's 29 ms are the place for speed in ktc2's code.
4. (done 2026-11-17, Mac) `listOf` cost. It was not the iterator (that was in the frame, through the
   dispatcher's `__into`; the functions taking a `List` are copied for each class anyway). Two causes:
   `ListLoops` gave up on a view of numbers (`listOf(1, 2, 3)` is an `Array<Int>`, boxed, whose `next`
   and `get` unbox): it now unboxes where it reads; and the view's iterator held the array, which made
   the field `mArray` leaky for `EscapeAnalysis` (it follows fields by name), so every array handed to
   `asList()` was on the heap (`split(" ")`'s vararg array: 300 000 allocations in Ordinary). The
   iterator now holds the list and asks it (`tools/gen_arrays.py`). Ordinary against the tool before
   `70605f0`: same allocations, retains and releases, time within the noise (0.237 to 0.245 s both);
   the other benchmarks the same. A lesson for the platform layer: an object that copies a field of
   another into one of its own makes that field leaky for the whole program.
5. The speed of Native's regex engine against ktc2's former one: a benchmark of regex for
   `tools/bench.py` and `tools/compare.py`; then speed it up (it is Native's code: change only the
   platform layer or the passes).
6. (done in part 2026-11-17, Mac) `tools/compare.py` on all of `benchmarks/` with Kotlin/Native
   2.5.0-RC, with three new programs (`Empty`, `BinaryTrees`, `ManyObjects`): the numbers are in
   "Where the benchmarks stand". Fixed on the way: the Native side of `createCleaner`
   (`compat/native/Lifetime.kt`: Native's takes only a lambda that captures nothing) and
   the version the report names. Left: the JSON program (a library for a program, on the three
   targets), `--self-timed`, the same on Windows and Linux.
7. (done 2026-11-17, Mac) Pair cycles in programs with threads: a release of a paired object and its
   check are one step under a lock (`ktc2_release_paired`), and a reference counted to such an object
   meanwhile waits (`ktc2_retain_paired`); `__pair` now only decides and returns what to free, which is
   released after the lock. The first try (a lock around the check alone) was wrong and
   `tests/ktc2/threads/PairCyclesThreads` found it at once: a thread waiting to check an object it had
   just released met it freed by another thread's check. That test, 20 times under AddressSanitizer and
   50 times plain, passes; the benchmarks are unchanged (none has a paired object in a threaded part).
   The cost: a global lock for each release of a paired object once threads run. Not tried with
   ThreadSanitizer.
8. What "What I would do first" lists below from item 5 on.

For whoever picks this up next, on any machine. `CLAUDE.md` says how to build and test,
`DESIGN.md` how it works, `tests/README.md` how the common tests work; this is what those do
not say. Start with `git pull`.

**(Earlier, the Mac, 2026-11-16 afternoon; done since.)** The push of `ec18f49` went out without
the whole suite having run on its last changes: read its CI run first (`gh run list --repo
bitsycore/KotlinToC`, then `gh run view <id> --log-failed`). The whole suite (1037 tests) was
green on the Mac with everything up to Native's `NumberConverter`; what came after it (Kotlin/
Native's Unicode code and tables, the fragment levels of `K2Frontend.kt` that compile them,
distinct C names for private declarations of one name in two files, the time of each step in
the test log) was checked on the 170 integration programs, the 5 projects and the common tests
of characters, strings, numbers, code points and regex (all green), not on the libraries' own
tests (`tests/modules/kotlinx-*`, `tests/modules/atomicfu`) nor the box tests. If the CI job is red, the first
suspects are those: a library that uses `Char` classification or case (kotlinx-datetime's
parsing, kotlinx-serialization's JSON names) or code that depends on a `private` name in C.

**What 2026-11-16 did** (on the Mac, `git log 9aa6bbc~1..ec18f49`):
- the CI of 2026-11-15 was red on all jobs; fixed (`399cab7`'s dispatcher pairing, `SEEK_SET` on
  macOS, the runtime out of `ktc2_all.c` for MSVC) and green again (`d567946`);
- the whole suite on the Mac found and fixed: a safe call whose block gives `Unit`, `printStackTrace`
  as a member, `@Serializable` local classes, two local classes of one name, a time limit that
  never applied (`JsonUpdateModeTest` is `.pending`, the new marker);
- the test log was rewritten as an IDE shows tests (`build.gradle.kts`): a line per program with
  its progress, kind, ✔/✘, time and the time of each step (Kotlin → IR, IR → C, C → exe, run),
  the test functions only under a file where one failed, a summary per kind and the total of
  each step;
- `ktc2 -ea` (Kotlin/Native's `-enable-assertions`) turns `assert()` on; `--debug` does not;
- cycles of two objects (an object and its `inner` object or self-calling lambda, two objects
  through `var`s) are freed by the runtime when nothing else holds them (`KClass.pairCycles`,
  `ktc2_pair_check`; not with threads); `@Weak inner class` for the rest;
- plain Kotlin aligned on Kotlin/Native where the JVM differs, checked against Native's 2.5.0-RC
  sources (below, "Decided by the owner"), and the standard library now takes Kotlin/Native's own
  code for `Float`/`Double` as text and for `Char` classification and case, from the sources jar
  of `kotlin-stdlib-wasm-js` (`DESIGN.md`, "The standard library");
- `tools/native.sh` (Kotlin/Native 2.4.21 under `~/.konan` on the Mac) was run on every plain
  program: what is left of its differences is in "What I would do first", item 2.

**State.** Everything is on `master`. The suite is 1038 tests: 170 programs under
`tests/integration/` (compared with the JVM, with an `.expected` file, which for 13 of them is
Kotlin/Native's own output, or that must be refused), 5 projects (the showcase and four
programs of several files under `tests/integration/modules/`), 180 files of common tests under
`tests/` (one of ktc2's own, 156 of the Kotlin standard library's, 3 of its JVM part and 20 of
Kotlin/Native's), 299 files of the libraries' own tests (`tests/modules/kotlinx-io`,
`kotlinx-collections-immutable`, `atomicfu`, `kotlinx-datetime`, `kotlinx-serialization`,
`kotlinx-serialization-json-io`) and 384 box tests of the Kotlin compiler (`tests/box/`). About
28 minutes on the Mac (M-series, 8 processes), most of it the libraries' tests; the integration
programs alone take about 2 minutes. AddressSanitizer is clean on every program on Linux (the
CI job's last step).

**The CI job** (`.github/workflows/test.yml`, on every push; see it with
`gh run list --repo bitsycore/KotlinToC`) runs the suite on Linux, macOS and Windows:
- the suite passes on all three since `c556bea` (2026-11-13, late). What Linux needed: the
  GNU C library declares `FILE` under a private name first (`__FILE`), and the bindings kept
  the first name of a type; and `stdin`, `stdout`, `stderr` are variables there, which met
  the ones written by hand for the systems where they are macros (`interop/Geometry` now has
  both shapes, so every system tests them);
- the AddressSanitizer step on Linux ran for the first time on that commit and found two
  things, fixed in the commit after it: a thread nobody joins left the block of its handle
  (Linux's sanitizer looks for leaks, macOS's does not; the handle is now the `pthread_t`
  itself), and `tools/asan_all.sh` took a note of gcc's for a failure. Look at the run of the
  latest commit to see that step green;
- Linux can also be run on the Windows machine: it has WSL2 with Debian, gcc, git and CMake
  (`wsl -e bash -lc "cd /mnt/c/Dev/KotlinToC && ./gradlew test --rerun"`; about 4 minutes
  from the Windows checkout, whose `build/` it shares, so never at the same time as a run on
  Windows). `.gitattributes` keeps the shell scripts with Unix line ends for that;
- MSVC is in the job since 2026-11-13: on Windows, after the suite, `tools/msvc_all.sh`
  compiles the one C file of every program (`ktc2_all.c`, with the runtime `ktc2_core.c`
  beside it: since 2026-11-16 the runtime is not in that file, because a C library's own small
  header may declare a part of `<windows.h>` that the runtime includes) with MSVC and compares what it
  prints with gcc's build. Run by hand that day: 166 of 168 programs the same, the two others
  left out with their reason in `tools/msvc_known.txt` (the last digit of `pow` in MSVC's C
  library, and a test whose own C uses a gcc attribute).

**What that day did**, in the order of `git log 3a04274..`: speed (text that is not ASCII
searched as bytes, `ArrayList` sorted in its storage, integers as text without `printf`,
`get`, `set` and `size` of an `ArrayList` written out, short functions in the headers), then
correctness, which is where the work is now. The benchmarks did not move after the speed
work (table below).

**The owner's rule** (2026-11-13): ktc2 does what Kotlin does on the JVM and on
Kotlin/Native, everywhere, and is made fast on top of that. The only place something may be
limited or different is behind an annotation of the `ktc2.` package (`@Struct`, `@Value`...),
which is the program saying it accepts that in exchange for storage closer to the machine. A
difference found in plain Kotlin is fixed in general, not written down as a gap with the
common case covered. Where the JVM and Kotlin/Native themselves differ because of their
platform, either is accepted, and Kotlin/Native is the one to look at first.

**Decided by the owner** (all on 2026-11-13 unless said):
- (2026-11-17) clang is the C compiler ktc2 prefers, on every system; gcc and MSVC keep working
  (CMake's default on Windows is MSVC, and the suite runs with gcc);
- (2026-11-15) ktc2 is Kotlin/Native as closely as it can be: where the JVM and Kotlin/Native
  differ, ktc2 does as Kotlin/Native (it was the JVM until then); the JVM stays the oracle of
  the suite where the two agree. Memory is the one difference allowed, never results: cycles
  are answered by `weak`/`acyclic` where a library's design allows it, a bounded cache, or a
  known leak written here, not by a weak reference that changes results. What ktc2 adds is in
  `ktc2.` and changes storage and cost only. `DESIGN.md`, section 1. Aligned on 2026-11-16:
  the support of the library's tests answers as Kotlin/Native's (`TestPlatform.Native`, its
  back-reference handling), and what that showed ktc2 doing as the JVM now does as Native: a
  negative array size is `IllegalArgumentException`, a capital sigma at the end of a word is ς
  by Native's rule over code points (now Native's own `StringCasing.kt`), `toArray(dest)` leaves
  `dest` as it was after the last element, a map's entry fails with
  `ConcurrentModificationException` once a key was added or removed, a back reference to a group
  not opened yet is an error, an object expression's `simpleName` is null. A test of Kotlin/Native's
  own runtime says so with `<Name>.native`: the JVM is not its reference where the two differ;
  aligned on 2026-11-16 from `tools/native.sh` (checked against the sources of Kotlin/Native
  2.5.0-RC): an index outside an array or a string, `substring` out of range, a division by zero
  and `toInt()` of no number throw without a message (a string's index error is
  `ArrayIndexOutOfBoundsException`); a list says `index: 3, size: 3` and a range
  `fromIndex: .., toIndex: .., size: ..`; `toDouble()` of no number has the text as message;
  `ArrayList` and `HashMap` notice changes while they are gone over as Native's do (`hasNext` is
  `index < size`, `clear()` of an empty list and a sort are no change, a key added after the last
  entry is noticed, a `clear` ends the loop); a template turns each object into text as it reaches
  it; `String.compareTo` (also ignoring case) and a boxed `Char`, `Short`, `Byte` compared answer
  -1, 0 or 1. Programs that print those have Native's output as `.expected` (ListAccess,
  ListIndexMessages, StringRanges, ConcurrentModification, ListLoops, MapLoops, SortInPlace,
  OrdinaryCollections, OrdinaryStrings, objects/Templates, and for the next point Arithmetic,
  NumberType, Unicode); the others print what both agree on. Then `Float` and `Double` as text are
  Native's `NumberConverter` (Harmony's algorithm, not always the shortest: `1.5E10f` is
  `1.5000001E10`) and `Char` classification and case are Native's code with its Unicode tables,
  both compiled from Native's own sources (`DESIGN.md`, "The standard library");
- (2026-11-15) the serializers of generic classes (which refer to their descriptors and back)
  are kept in a cache by class and arguments, in ktc2's part of the kotlinx-serialization
  module, rather than freed through a weak reference that would change what a descriptor
  answers;
- `ktc2.Deinit` is removed: what runs when an object ends is a cleaner (`createCleaner`),
  which the JVM and Kotlin/Native have too, and which costs nothing in a private property.
  The runtime's hook is still there under it (`ktc2.internal.Deinit`, for the cleaner that is
  kept as an object);
- coroutines are `suspend` functions done in full, by the transformation into a state
  machine, not a stack of their own for each coroutine (done 2026-11-14: `DESIGN.md`,
  "Coroutines"). The library's own functions written with `sequence { }` (`zipWithNext`,
  `chunked`, `windowed`, `scan`, `shuffled` of a sequence...) stay plain iterators of the
  platform layer: measured, they are a quarter faster than the coroutine;
- a class as a value is in: `x::class`, `Name::class`, compared, as keys, `simpleName`,
  `qualifiedName`, `isInstance`, `cast` for a class, and `typeOf<T>()` (2026-11-14), which is
  what Kotlin/Native has. The rest of reflection is not;
- what only the JDK has is not added (`toSortedMap`, `String.format`, `getOrDefault`,
  `setCharAt`...): a program has no JDK, as on Kotlin/Native. A compatibility layer in the
  `ktc2.` package may come later. `removeFirst` and `removeLast` of a list stay: they are
  Kotlin's own extensions, which a program compiled for the JVM reaches as the members Java 21
  gave lists;
- the standard exceptions have their Kotlin names (`kotlin.IllegalStateException`), as on
  Kotlin/Native; the suite takes that or the JVM's `java.lang.` name when it compares;
- tests are also written as Kotlin Multiplatform's common tests are (`tests/`), and the
  tests of the Kotlin standard library itself are taken as they are (`tests/stdlib/`);
- the enum that is a number is `@SimpleEnum`, with `@EnumValue` and `ktc2.enumOf`; the
  package of the SDL3 module is `ktc2.sdl3`; nothing of `java.*` for programs; files and the
  like through kotlinx.io or okio (part 8);
- any addition to the `ktc2.` package is proposed first, not built unasked. The model is
  `ktc2.concurrent` and `createCleaner`: one API that is plain Kotlin on the JVM and the
  native thing in C. The flags of the SDL3 module as value classes wait for the same reason.

**How differences are found**, which is the work to go on with:
1. *Tests of the Kotlin standard library* (`tests/stdlib/`, from `libraries/stdlib/test` of
   github.com/JetBrains/kotlin at the tag of the library's version, unchanged). 153 files are
   in and pass (some 2200 tests), with the helpers of the library's tests under
   `tests/stdlib/support/`; `tests/README.md` lists what they found (some fifty differences, all
   fixed). What is left: 2 tests in `<Name>.skip` files, each with the reason; no file of them is
   pending since coroutines came. Every file of `libraries/stdlib/test` but JavaScript's is in.
   Next: the tests of `libraries/stdlib/jvm/test` that are not about the JDK, and those of
   `kotlin-native/runtime/test` (its coroutine tests among them).
2. *Programs of ordinary code compared with the JVM* (`generics/ErasedViews`,
   `objects/OrdinaryClasses`, `stdlib/OrdinaryCollections`, `stdlib/OrdinaryStrings`,
   `basic/OrdinaryControl`, `objects/ClassReferences`): write what ordinary code does with one
   part of the language, run it with `KTC2_TEST_FILTER`, read `tools/jvmdiff.sh <Name>`. They
   found some twenty differences. Not looked at yet: inheritance with generics in depth,
   inner and local classes, interfaces with properties, labels and non-local returns in depth,
   threads, `kotlin.time`, `Regex` in depth, `Random`, arrays of arrays, value classes,
   delegation of properties (`by map`), `vararg` with spread in depth, `Double` as text at its
   limits, `Comparable` and `Comparator` in depth.
3. *Kotlin/Native as a third opinion* (`tools/native.sh`, after a run of the suite; it needs a
   Kotlin/Native compiler under `~/.konan` or named by `KTC2_KONAN`, which a Mac with a
   multiplatform project has and another machine may not; the Mac has 2.4.21, Kotlin 2.5's
   features fail there). Run on 2026-11-16: 18 programs differed, all where the JVM and
   Native differ; all were aligned on Native that day but those listed in "What I would do
   first", item 2. Native 2.4.21 and the 2.5.0-RC sources ktc2 follows differ in places
   (`String.compareTo` answers the difference in 2.4.21, -1/0/1 in 2.5): the sources win; they
   are at github.com/JetBrains/kotlin, tag `v2.5.0-RC` (`kotlin-native/runtime/src/main`,
   `libraries/stdlib/native-wasm`), and a sparse clone of them takes a minute. To make a
   program's `.expected` from Native: `kotlinc-native <file> -o out && ./out.kexe > <Name>.expected`,
   after checking each differing line against those sources. Not part of the suite or of the CI
   job.

**What the fixes of that day changed in the design**, to know before touching the importer
(each is in `DESIGN.md`, "Where a small mistake costs most"):
- a generic class is one class in Kotlin and several here. A member of an object whose
  instantiation the type does not settle (`Box<*>`, a `Pair<Int?, Int?>`, what a cast to a
  generic class gives) is reached through a dispatcher; `Devirtualize` makes it direct again
  where the class is known;
- `Unit` is an object of the runtime where one is asked for; `Number` is treated as an
  interface the boxed numbers implement; a string holds half of a surrogate pair;
- `==` on a `T` that stands for `Double` is `equals`; `compareTo` of `Double` is a total order.

**Known and not done**, besides what the parts below list:
- the serializer of a generic class and its descriptor refer to each other
  (`PluginGeneratedSerialDescriptor.generatedSerializer`): decided on 2026-11-15, they are kept in
  a cache by class and arguments (`Ktc2Serialization.kt` of the module), so a program has one for
  each type it serializes; the tests that make them have a `.cycles`. The field is typed by an
  interface, so the pair freeing of 2026-11-16 does not see it: extending it to a field whose
  class is checked at run time would free most of those (35 programs still have a `.cycles`);
- the default `toString()` of a local or anonymous class names it with dots
  (`AnyTest.testToString.Local1`), where the JVM and Kotlin/Native use `$`
  (`AnyTest$testToString$Local1`): the runtime's table of types would need a second name per
  class, the one of `qualifiedName` staying as it is (`tests/stdlib/AnyTest.skip`);
- `assert()` is there (`Ktc2Assertions.kt`), off unless asked for, as on Kotlin/Native: `ktc2 -ea`
  (Native's `-enable-assertions`) turns it on, in any build; `--debug` does not (decided by the
  owner on 2026-11-16: follow Native).
- (done 2026-11-16, the owner's choice of two proposals) a cycle of two objects that hold each
  other through their own fields is freed by the runtime when nothing else holds either: an
  object and its `inner` object (`private val key = Key()`), its lambda or object expression that
  calls its members (`val handler = { update() }`), two objects that point at each other through
  `var`s; a star of them around one object too. No annotation and nothing changes in what the
  program computes: the objects go only when no reference from outside is left (`KClass.pairCycles`,
  `CycleLint`, `ktc2_pair_check`). Not in a program with threads yet (the cycle is still reported
  there). A cycle ktc2 cannot see this way (three objects or more) is answered by `@Weak` on a
  property, or now on an `inner` class (`@Weak inner class Entry`: its outer object is not owned,
  and an inner object that outlives it throws `NullPointerException` where it uses it).
  `tests/ktc2/memory/PairCycles`, `SelfCycles`, `tests/integration/native/WeakInner`.

Fixed on 2026-11-14 from that list: a value of the wrong class that reaches a function through an
unchecked cast throws `ClassCastException`, as on the JVM and Kotlin/Native, except for the
members of the collections that answer it (`contains` false, `indexOf` -1, `get` null, as Kotlin's
bridges do) (`objects/WrongClassArguments`); `KClass.isInstance` and `cast` know interfaces, and a
lambda is a `FunctionN` for `is` and `as` (`objects/ClassInstanceChecks`).

Not differences after all, so not to be fixed (the owner's rule: where the JVM and Kotlin/Native
differ, either is right):
- two entries a `HashMap` hands out for one key are equal and not the same object. Kotlin/Native's
  `HashMap` makes an `EntryRef` on every `next()` too;
- the last digit of `exp`, `ln`, `pow`, `sin`, `cos`, `tan`, `log10` is the C library's. Measured
  on Windows (mingw's C library, HotSpot 21 on x86-64, 100 000 values): the C library differs from
  the JVM's `Math` in 0.03 to 0.23 % of them (`tan`: 4.7 %), and fdlibm (`StrictMath`, which the
  plan was to carry) in 3.4 to 10 %: HotSpot computes `Math` with intrinsics of its own, which
  also differ from one processor to another. Carrying fdlibm would make ktc2 differ from the JVM
  far more often. Kotlin/Native uses the C library, as ktc2 does. A test still must not print
  the last digit.

**How the owner works** (as said on 2026-11-16). They follow a run in a terminal tab (`./gradlew
test --rerun 2>&1 | tee build/suite.log`, a line per program with its progress): run the suite
where they can see it. While developing, run the smallest set that covers the change: the
integration programs are the workspace for quick checks (`KTC2_TEST_FILTER=integration/`, about
2 minutes), with the few common tests that matter (`tests/stdlib/String`...), never the
libraries' own tests (a filter, or `KTC2_TEST_CATEGORIES=integration,kotlin`; the whole suite is 28 minutes). The whole suite
before a commit that touches a pass or the platform layer broadly. Never two runs at once: they
share `build/integration`. No push and no waiting on the CI job during the work ("CI is too
long"): commit locally, push when the owner asks. They push to `master` directly, from several
machines: pull first. Commits are by `Bitsy <bitsydoge@proton.me>` with no mention of an
assistant (`CLAUDE.md`, "Conventions"): on a new machine set `user.name` and `user.email` in the
checkout before the first commit. They decide by short answers to proposals: give two or three
options with a recommendation, and follow Kotlin/Native when in doubt.

**How the work went, and should go on.** One item at a time: a test that shows it (a common
test under `tests/` when it is about what must hold everywhere, a program under
`tests/integration/` otherwise), the change, the whole suite, `tools/asan.sh` on the programs it
touches, `tools/bench.py --compare` when a pass or the library changed, the documents, a
commit. Every optimization of counting or of the frame got a test that also holds the cases
where it must *not* apply (the value that gets out, the place that is overwritten): those are
what caught the mistakes. Three were made and found that way, and are worth knowing:
- an object created inside a constructor and stored into `this` was put in the constructor's
  own frame: held by an object that was passed in means it outlives the call;
- letting a `var` borrow from a temporary kept `var x = T(); x = null` alive to the end of the
  block;
- borrowing in `node = node.next` when the walk starts from a variable at its last use made
  the benchmark slower: the list was then given up whole at the end. A count removed is not
  always time saved: measure.

If the suite misbehaves on a system, `KTC2_TEST_FORKS=1 KTC2_TEST_JOBS=1 KTC2_KEEP_LIBRARY=0`
is the suite as it ran before it was made parallel: start from that, then turn the three back
on one by one. `ktc2` itself: `KTC2_DAEMON=0` is the command as it was. The process that
stays keeps the environment it was started with: only the `KTC2_` variables and `PATH` make a
command start another one, so a changed `SDKROOT` or `CPATH` is not seen until it is stopped
(`ktc2 --stop-daemon`). `KTC2_SKIP=inline` puts the short functions back in the C files if a
C compiler objects to them in the headers.

---------------------------------------------------------------------------
### Benchmarks against Kotlin/Native and the JVM: the plan (2026-11-15)
---------------------------------------------------------------------------

`tools/bench.py` measures ktc2 against itself: one build of the C, timed, with the counts of
allocations and retains, to see what a change to the translation does. The plan: the same
programs built three ways, ktc2, Kotlin/Native and the JVM, and four measures of each.

**What is measured, on each target**

| | ktc2 | Kotlin/Native | JVM |
|---|---|---|---|
| Compile time | `ktc2` to C (the compiler process cold, its first command, and warm, the next), then the C compiler and the linker (`-O2`, with and without LTO): each part apart, `KTC2_TIMING` gives the split | `kotlinc-native -opt`, cold (a new process; its caches of the library made once beforehand, not counted) | `kotlinc` to a jar, cold and warm (the compiler kept in a process, as the suite's `PhasedCompiler` and Gradle's daemon do) |
| Size | the executable, as built and stripped (`strip`), `-O2` and LTO; the lines of C | the executable, as built and stripped | the jar; and what it needs to run: a JDK, or a runtime cut to the modules it uses (`jlink`), for a size comparable with the others |
| Memory | peak resident memory of the process; with the allocations of the counted build beside it | peak resident memory, Native's collector as it comes | peak resident memory, the collector as it comes; and the same with `-XX:+UseSerialGC -Xmx` at a few times the program's need, closer to what a native program asks for |
| Speed | the whole process, best and median of N runs | the same | the same, and the time the work takes once the JIT is warm, which the program measures itself (below) |

**How**

1. *The programs.* `benchmarks/*.kt` are the same file on the three targets, and may use what
   the `ktc2.` package offers to make ktc2's code faster or smaller (`@Value`, `@Struct`,
   `ValueArray`, `@Size`, `@Weak`, `arena`, `@SimpleEnum`, `ktc2.concurrent`; the owner,
   2026-11-15): those change storage and cost, never results, so the program computes the same
   on the three. On the JVM `ktc2.` is there already (`compat/src/main/kotlin/ktc2/`, plain
   definitions); for Kotlin/Native it is to be written the same way, plain Kotlin over what
   Native has (the annotations doing nothing, `ValueArray` an array, `WeakRef` over
   `kotlin.native.ref.WeakReference`, `createCleaner` Native's own, `ktc2.concurrent` over a
   `Worker` or `pthread`, `arena` a plain block), compiled with each program. Where a program
   uses them, it is measured twice on ktc2, with and without (the annotations stripped by the
   tool), to show what they bring. Each program prints what it computes (the three must print
   the same, which also checks Native and the JVM against ktc2) and, with `--self-timed`, runs
   its work several times and prints the time of each run, measured with
   `kotlin.time.TimeSource.Monotonic`, which all three have: the JVM's warm time, and the first
   run against the others. To add, as the existing ones are about ktc2's own costs: a program
   that does nothing (the floor of size, memory and start), the usual allocation tests (binary
   trees, a map of many objects), and kotlinx.serialization's JSON on a model (the three have
   the library; Native and the JVM with its plugin).
2. *The compilers.* All three on the Kotlin of `gradle/libs.versions.toml`: ktc2 as installed;
   Kotlin/Native's compiler of that version, fetched by Gradle into the build directory (not
   whatever is in `~/.konan`), its target the machine's (`mingwX64`, `linuxX64`,
   `macosArm64`); the JVM's compiler from the same embeddable jar ktc2 runs, and the JDK of the
   toolchain (21).
3. *The tool.* `tools/bench.py --targets ktc2,native,jvm --measure compile,size,memory,speed`,
   the present behaviour being `--targets ktc2` with the counts. Peak memory: `/usr/bin/time`
   on Linux (`-v`) and macOS (`-l`), and on Windows the process's `PeakWorkingSetSize`
   (`GetProcessMemoryInfo`, through Python's `ctypes`, nothing to install). Each measure is
   the best and the median of N runs (seven by default), on a quiet machine: not while the
   suite runs, and not in the CI job, whose machines are shared and too noisy for times.
4. *The report.* A table for each measure, the three targets side by side and ktc2's ratio to
   each, printed and written to `build/bench/compare.txt` (`--save` and `--compare` as today, to
   follow it over time); the numbers of a day go into "Where the benchmarks stand" below, with
   the machine, the versions and what was not run.

**Order and estimates**: `ktc2.` for Kotlin/Native (half a day); the programs made self-timing,
with ktc2's features where they help, and the new ones written (half a day);
the Kotlin/Native and JVM builds in the tool, with the compiler fetched by Gradle (a day); the
peak memory on the three systems (half a day); the compile times cold and warm, and the
report (half a day). About three days, of which nothing changes ktc2 itself.

**To decide**: whether the JVM's speed is reported as the whole process (start and JIT
included, as a user runs it) or warm, or both, as proposed; whether the comparison of size
counts the JVM's runtime (proposed: both, the jar and the `jlink` runtime).

### Where the benchmarks stand
---------------------------------------------------------------------------

**ktc2 against Kotlin/Native and the JVM** (2026-11-17, `python tools/compare.py`, a MacBook with
Apple silicon, macOS; ktc2 with Apple clang `-O2` and LTO; Kotlin/Native 2.5.0-RC `-opt`, its own
collector; the JVM: OpenJDK 21, the whole process timed, start and JIT included; best of 7 runs,
peak resident memory; sizes stripped, the JVM's being its jar and the Kotlin library's, without
the 40.8 MB of a `jlink` runtime it needs to run). The `ktc2` columns of `compile` are cold (a
process of its own), then warm (the compiler process `ktc2` keeps) and the C compiler.

| | ktc2 | Kotlin/Native | JVM | ktc2 memory | Native memory | JVM memory |
|---|---|---|---|---|---|---|
| Empty | 0.002 s | 0.004 s | 0.025 s | 1.8 MB | 6.4 MB | 39.6 MB |
| BinaryTrees | 0.340 s | 0.699 s | 0.375 s | 33.9 MB | 190.7 MB | 1109 MB |
| Collections | 0.142 s | 0.439 s | 0.288 s | 11.8 MB | 77.8 MB | 231 MB |
| Dispatch | 0.110 s | 0.463 s | 0.398 s | 1.9 MB | 16.0 MB | 385 MB |
| ManyObjects | 0.162 s | 0.257 s | 0.320 s | 116.9 MB | 254.9 MB | 308 MB |
| Numeric | 0.278 s | 0.341 s | 0.315 s | 29.5 MB | 38.7 MB | 81.5 MB |
| Objects | 0.157 s | 0.186 s | 0.143 s | 2.6 MB | 16.0 MB | 380 MB |
| Ordinary | 0.219 s | 0.543 s | 0.277 s | 1.9 MB | 16.4 MB | 362 MB |
| Strings | 0.202 s | 0.351 s | 0.231 s | 3.8 MB | 16.3 MB | 158 MB |
| Threads | 0.048 s | 0.179 s | 0.173 s | 13.6 MB | 28.1 MB | 154 MB |

- Speed: ktc2 is the fastest on 9 of 10, 1.2 to 4.2 times Kotlin/Native and up to 3.6 times the
  JVM; the JVM is 9 % faster on Objects (small objects made and dropped in a loop: its young
  generation), and within 10 % on BinaryTrees, Numeric, Strings. Against Native, the most is on
  Dispatch (4.2x), Threads (3.7x) and Collections (3.1x).
- Memory: 1.3 to 8.5 times less than Kotlin/Native, 2.6 to 200 times less than the JVM; ManyObjects
  (a million live objects) is the closest, 2.2x and 2.6x.
- Size: about 0.1 MB stripped, Native 0.4 MB, the JVM 1.8 MB of jars (and its runtime).
- Compile: `kotlinc-native` takes about 7 s a program; ktc2 9.3 to 10.2 s cold (the library is
  analysed first), 0.4 to 0.8 s warm plus 0.2 to 1.7 s of C; `kotlinc` to a jar about 1.6 s.
- Not done: the JSON program on kotlinx.serialization (the tool has no way yet to give a program a
  library: the module on ktc2, the klibs and plugin on Native, the jars and plugin on the JVM); the
  programs timing themselves (`--self-timed`, the JVM's warm time); Windows and Linux;
  the same with `ktc2.` features stripped.

`python tools/bench.py`, Apple clang on an ARM64 Mac, best of seven runs. "Before" is commit
`2a16110`, built `-O2`; "now" is the same build (on 2026-11-13, at the end of the day); "now, LTO" is what
`--build` does by default.

| | Before | Now | Now, LTO | Allocations | Retains |
|---|---|---|---|---|---|
| Collections | 0.527 s | 0.128 s | 0.149 s | 4.50 M, unchanged | 11.9 M -> 8.5 M |
| Dispatch | 0.508 s | 0.136 s | 0.115 s | 6.52 M -> 1,013 | 5.0 M -> 1,275 |
| Numeric | 0.279 s | 0.269 s | 0.284 s | 44 | 0 |
| Objects | 0.473 s | 0.169 s | 0.151 s | 26.0 M, unchanged | 58.0 M -> 16.0 M |
| Strings | 1.207 s | 0.241 s | 0.201 s | 1.20 M, unchanged | 3.9 M -> 2.6 M |
| Threads | 0.352 s | 0.049 s | 0.045 s | 5.54 M, unchanged | 17.0 M -> 6.2 M |
| Ordinary (added later) | | 0.223 s | 0.203 s | 8.56 M | 2.2 M |

The benchmarks did not show the largest things that day; a program of small loops over what
ordinary code does (`build/scratch`, not kept) did. Each line is the same work before and after:

| | Before | After |
|---|---|---|
| 200,000 `indexOf` and `contains` in a text of 650 characters with accents | 37 s | 0.11 s |
| a template with three numbers, 2 M times | 162 ms | 50 ms |
| three `StringBuilder.append` (a string, a number, a character), 5 M times | 234 ms | 39 ms |
| `sort()` of an `ArrayList` of a million `Int` | 169 ms | 35 ms |
| `for (i in 0 until list.size) sum += list[i]`, 20 M elements | 37 ms | 5 ms |
| a `HashMap` lookup with a key of 35 characters, 5 M times | 109 ms | 75 ms |
| `Regex("item[0-9]+").find` in a text of 350 characters, 20,000 times | 46 ms | 7 ms |

Programs are also 30 to 45 % smaller with LTO. What these numbers say about what is left:
the number of allocations did not move in the benchmarks (the iterators that left the heap
are few next to the objects the programs make); each one got cheaper instead, since small
objects no longer go through `malloc`. Not making them at all is what part 2 still offers.
Over the test programs allocations went from 320,970 to 303,664. Numeric has nothing left
for these passes: it is the C compiler's.

On Windows (gcc 15, x64, `-O2`, no LTO), before and after results through a dispatcher were
built in the caller's frame. `Ordinary` is the benchmark of ordinary code, added that day.

| | Before | After | Allocations | Retains |
|---|---|---|---|---|
| Collections | 0.460 s | 0.446 s | 4.50 M, unchanged | 8.7 M |
| Dispatch | 0.482 s | 0.467 s | 6.50 M -> 1,014 | 5.0 M |
| Numeric | 0.246 s | 0.241 s | 44 | 0 |
| Objects | 0.235 s | 0.234 s | 26.0 M, unchanged | 16.0 M |
| Ordinary | 0.514 s | 0.509 s | 13.78 M -> 12.56 M | 5.5 M |
| Strings | 0.262 s | 0.260 s | 1.20 M, unchanged | 2.7 M |
| Threads | 0.716 s | 0.707 s | 5.54 M, unchanged | 6.2 M |

At the end of that day on the same machine, after the rooms, the copies, the unchecked
storage of `ArrayList`, the borrowed returned calls and `listOf`: Collections 0.384 s,
Dispatch 0.424 s, Numeric 0.241 s, Objects 0.226 s, Ordinary 0.425 s (8.56 M allocations),
Strings 0.256 s, Threads 0.661 s. Retains are what the table says.

The times hardly move: an allocation from the pool costs about as much as clearing a slot
of the frame. What is left in `Ordinary`, by what makes it (counted from the program):
- 4.0 M: `place = place + step`, a new object assigned to a variable of an enclosing block
  on every turn of a loop (part 2, "a variable that is given a new object in a loop");
- 4.0 M: the `Pair`s of `zip`, kept in a list: nothing to gain;
- 2.0 M: the array inside a local `ArrayList` that is itself in the frame (part 2);
- about 2.5 M: the strings and lists of `split` and of a template.

---------------------------------------------------------------------------
### 0. Before the optimizations
---------------------------------------------------------------------------

- [x] **Benchmarks.** `benchmarks/*.kt` and `tools/bench.py` (see "Measuring"); `Ordinary.kt`
  is the one whose objects mostly stay in their function. Not there: a
  comparison with the JVM and with Kotlin/Native on the same programs, and with the first
  transpiler (in the git history before commit `0d1da61`).
- [~] **Linux and macOS.** macOS is done: the whole suite and the SDL3 showcase pass with Apple
  clang on ARM64. What it took: the bindings of `platform.posix` (Apple declares the C library
  in private headers), `-ffp-contract=off` (ARM64 fuses a product and a sum), two test lines
  that printed the last digit of `exp` and `log`, the tool's JVM running out of code cache,
  and a double release that Windows had been hiding (below). Linux runs in the CI job since
  2026-11-13 and passes but for `interop/Posix` (the same bindings, with the GNU C library:
  see "Where the work stands"). The CI job runs the three systems on every push.
- [x] **A use of freed memory is reported on every system.** A local that handed its
  reference over and was assigned again later was released twice; the fill of freed objects
  made that pass on Windows. Test builds now keep freed objects aside, filled, and end the
  program on a retain or release of one. AddressSanitizer on the generated C finds the rest
  and is worth a second suite target (`-fsanitize=address`), which is not there yet.
- [x] **What runs when an object ends** is a cleaner (`DESIGN.md`, section 5):
  `createCleaner`, Kotlin/Native's API, the JVM's cleaner behind it on the JVM; in a private
  property it is folded into the class and costs no object, and no field either when its
  resource is a `val` of the class. With it: `Effects` treats giving up a reference as
  something that can run the program's code, temporaries end with their statement, and
  `ArrayList` and `HashMap.remove` let go of what is removed at once. It began as a `Deinit`
  interface as Swift's `deinit`, removed since: `tests/integration/native/CleanerLifetimes.kt` is
  the test of when objects end.
  Left:
  - [x] a `HashMap` entry removed through an iterator is let go at once: an entry that is
    handed out has its own key and value (`HashMapEntry`), which a `for ((k, v) in map)` no
    longer makes (`ListLoops`). `tests/ktc2/collections/MapEntries.kt`.
  - [ ] a variable could give up its object after its last use, as Swift does in optimized
    builds, instead of at the end of its block: frees earlier, but a guard object
    (`val lock = Guard()`) would then end at once. Not planned unless asked for.

---------------------------------------------------------------------------
### 1. Compile time
---------------------------------------------------------------------------

A small program takes about 13 s in a process of its own, nearly all of it the Kotlin front
end reading the library sources (`KTC2_TIMING=1`). Through the process that stays it took
3.6 s, and takes about 1 s since that process keeps the front end's work on the library
(0.45 s inside the process, the rest is starting the command).

The suite, on a 16-core Windows machine: about 10 minutes with one program after another,
2 min 40 s now. Of a program's time 64 % was translating it (4.1 s in a warm process) and
32 % compiling its C (2.1 s); running it is nothing. What was done: the programs are shared
between processes (Gradle forks, one test class each, dealt out in the order of the class names: 8 gave 3 min 17 s), the C files of a
program are compiled by four compilers at once (2 min 52 s), and 12 processes instead of 8
(2 min 41 s). The dumps are no longer written in a run of the whole suite (they turned out
not to cost time, only 2.7 GB of disk). What limits it now: eight Kotlin compilers at once
each take twice as long as one alone, with the machine half idle (memory, not processors),
and each process pays for a first compilation. The next gain was in the translation itself,
the first line below: with the library kept, the whole suite takes 71 s with 8 processes
(74 s with 6, 90 s with 4, 81 s with 12). What is left of a program's time is then, in
about equal parts, the one analysis of the library per process (12 s, more when several
start together) and the C compiler.

| | Gain | Effort |
|---|---|---|
| [x] A compiler process kept running between compilations (`Daemon.kt`): `ktc2` starts it on first use and hands it each command; it ends after 30 minutes without one, and is replaced when ktc2 or an environment variable it reads changed. `KTC2_DAEMON=0` does without, `ktc2 --stop-daemon` ends it | 12.9 s -> 3.6 s on Windows | medium |
| [x] The front end's work on the library is kept in the process (`frontend/PhasedCompiler.kt`): the compiler is run phase by phase; the library is resolved once, and a program is one more part on top of it (a source session that depends on the library's, as one source set of a multiplatform module on another), the only one resolved each time. The conversion to IR is done for the whole each time (0.4 s), told not to empty what it converted. In the process `ktc2` keeps running and in the suite; `KTC2_KEEP_LIBRARY=0` goes back to one whole compilation per program | 3.1 s -> 0.45 s per program in a warm process; the suite 2 min 41 s -> 71 s | high |
| [ ] The same across processes: the 12 s of a first compilation are the library resolved from nothing. The compiler has no form of its resolved tree that can be written and read back; the nearest is the library compiled once to a jar with its IR serialized (`-Xserialize-ir`) and function bodies read from there on demand, which would also need the importer to accept IR that is loaded lazily | 12 s -> perhaps 2 s for a first compilation | high, unknown |
| [ ] Keep the library's IR too, not only its analysis: the 0.4 s left per program are mostly the conversion of the library to IR again. The importer does not change the IR it reads, but the conversion registers every declaration in tables that would have to be shared between programs | 0.45 s -> perhaps 0.15 s | medium to high |
| [~] The C of a test program: 1 to 2 s, as much as the translation now. One C file that includes all the others is written (`ktc2_all.c`; the `static` names are numbered by unit), and `KTC2_TEST_UNITY=1` has the suite compile it. Measured on a Mac: one compiler on that file takes what four take on the files (1.4 s against 4.4 s for one after another), and the suite the same 46 s. Not tried on Windows, where starting gcc costs more; and not the default, because a C file that lacks an include is only found when each is compiled by itself | none on a Mac; the suite on Windows: perhaps 71 s -> 55 s | done, to be measured |
| [x] The suite in several processes, and the C of a program by several compilers (`IntegrationTest.kt`, `build.gradle.kts`: `KTC2_TEST_FORKS`, `KTC2_TEST_JOBS`) | 10 min -> 2 min 40 s | low |
| [ ] Hand the compiler only the library files a program can reach | 1-2 s; tried once for the unsigned arrays and dropped as fragile | medium |
| [x] Do not rewrite C files whose content did not change, so the C build is incremental (`CEmitter.write` already did) | faster rebuilds through CMake | low |

---------------------------------------------------------------------------
### 2. Allocation
---------------------------------------------------------------------------

| | Gain | Effort |
|---|---|---|
| [x] `for` over an `ArrayList`, an array or a range as an index loop when the type is known. Arrays and ranges were index loops already. Over a list whose class is known to be `ArrayList` (`Devirtualize`), `ListLoops` writes the loop over the storage itself: no iterator, the size and the count of changes compared once per round, the element read without a test. It is the platform iterator written out, so it ends and fails as that one does (`tests/integration/native/ListLoops.kt`, `KTC2_SKIP=listloops`) | Collections 0.26 s -> 0.15 s, Dispatch 0.31 -> 0.22, Ordinary 0.35 -> 0.28, Threads 0.17 -> 0.05 | medium |
| [x] The same for a `HashMap`: `for ((k, v) in map)`, `map.keys`, `map.values`, and what is built on it (`LinkedHashMap`, the sets). The loop is known by the class of its iterator, which is still made once; the map is read from it, and the loop goes over the entries itself. For the entries, the entry object is not made when the loop only asks it for its key and its value (`tests/integration/native/MapLoops.kt`) | 3.7 ns per entry -> 0.5 ns (LTO, 30 M entries) | medium |
| [x] Iterators in the frame when the loop cannot be an index loop. Done where the class of the collection is known (a local, a parameter every caller passes the same class to, a property that only ever receives one class). A parameter that receives several classes (`List<T>` given an `ArrayList` here and a `listOf()` there) is covered by the next line, and a small function is copied for each class (`Devirtualize`) | high where it applies | medium |
| [x] The result of a call through a dispatcher is built in the caller's frame when it stays there: the dispatcher has an `__into` form that hands the caller's room (a `union` of the classes the implementations build) to those that can build in place. With it an argument is no longer "kept" just because the call is dispatched: it is held as the implementations hold it, so a local list handed to a function that takes a `List` stays in the frame (`tests/ktc2/objects/DispatchedResults.kt`, `KTC2_SKIP=slots`) | Dispatch: 6.5 M allocations -> 1,014; every `for` over a `List`, `Collection` or `Map` parameter | medium |
| [x] A variable that is given a new object on every turn of a loop (`place = place + step`): the function has two rooms for it, and the call builds in the one that holds no object in use (`KStackSlot.rooms`, `ktc2_slot_reuse`); when something else still holds an older object, that one comes from the heap. `tests/ktc2/memory/Reassigned.kt`, `KTC2_SKIP=rooms` | Ordinary: 12.56 M allocations -> 8.56 M; the time does not move | medium |
| [ ] The array inside an `ArrayList` (or `StringBuilder`, `HashMap`) that is itself in the frame: the constructor stores it into `this`, which was passed in. Needs room in the caller for a first small array, as `ktc2_array_local` has for arrays. Measured before building it: a local list that is given six numbers and summed takes 22 ns, of which making and freeing its array is 5 (the same loop over a local `IntArray` takes 1.4 ns: the rest is the list itself, its `add` and its counters). So about a quarter of such a function at best, for a change to the most delicate pass; an object that never leaves its function as plain locals (below) is where the other 15 ns are | 5 ns per local list; perhaps 5 % of `Ordinary` | medium to high |
| [ ] An object that never leaves its function becomes plain locals | medium | medium |
| [x] Closures in the frame when the lambda does not outlive the call it is passed to. The closure was there already; what it captured was not, because storing into a field counted as leaving. An object or the cell of a `var` stored into an object that stays in the frame now stays too, unless some read of that field lets it out | every capturing lambda that is only called: no allocation left | medium |
| [x] Destroying a long structure does not use a frame of the C stack per object: past 96 KB of stack the object is put aside and destroyed later. A list of a million cells crashed before | correctness | low |
| [ ] Small strings inside the header, no block of their own | medium for string-heavy code | medium |
| [x] A size-class allocator in the runtime for small objects: those of a class or a box, up to 128 bytes, come from per-thread lists of freed blocks of their size (`KTC2_NO_POOL` turns it off) | Objects 0.39 s -> 0.19 s; 10 to 15 % elsewhere | low to medium |
| [x] The same for small strings and arrays, whose size is not that of their type: the size is found in the object when it is freed (`pool_size_of`) | Strings -4 % | low |
| [x] A list that is only filled and gone over with `for` stays in the frame: `iterator()` stores the list in the iterator it returns, which is "held by the result" and no longer an escape. The caller follows the local that receives the result | common in ordinary code | low |
| [x] What a constructor or a setter keeps stays in the frame with the object that keeps it (`Keeper(Item())` as a local: neither is allocated). A parameter stored into a field of the object of another parameter is "held" by it, not escaped; the caller decides by where its own object goes | every object built around others | medium |
| [~] Reuse the storage of an object freed just before one of the same class is made. The pool does this already: its lists are last in, first out, so the block just freed is the next one handed out. Left: skipping the pool altogether when the compiler can see the pair, which saves a few instructions | low now | medium |
| [x] A cache of boxes for small integers: -128..127 as `Int` and `Long`, characters below 128, the Booleans, static and immortal as on the JVM | low to medium | low |
| [ ] Escape analysis through dispatchers, not only direct calls | medium: more objects stay in the frame | high |

---------------------------------------------------------------------------
### 3. Reference counting
---------------------------------------------------------------------------

Where it stands: 1.90 million retains for 305,000 allocations over the test programs (the
Unicode test alone is 1.36 million of them); the benchmarks are in the table at the top. Of
the calls whose result goes into a local, 172 of 729 borrowed when last counted; 450 return
an object of their own; 62 pass an argument that is itself a temporary; 43 are followed by a
write to an array of references.

| | Gain | Effort |
|---|---|---|
| [ ] Tell arrays apart in `Effects`, so a loop that adds to one list can borrow from another | unblocks those 43 loops | high |
| [x] Owned parameters for constructors and setters: a function that stores a parameter as it is (`this.next = next`) has a form `name__o` that takes the caller's reference over, called where the caller is at its last use of what it passes. A local that is assigned again before anything reads it (`head = Node(i, head)`) counts as at its last use, in a loop too. Direct calls only: not through a dispatcher | Objects: retains -50 % | medium |
| [~] No release for locals known to be null or immortal (literals, statics). Done: a way out of a block skips the locals nothing assigned yet (outside loops). A local that only ever holds literals or statics borrowed already unless it is reassigned; that case is left | smaller code; the C compiler removed most of these itself | low |
| [ ] A "shared" bit per object, so counts are atomic only for objects another thread can reach | high for threaded programs | high; a mistake is a data race. Needs a transitive mark when an object is shared and a barrier on stores into shared objects |
| [x] Merge a retain with a later release of the same object: a local hands its reference over at its last read (`t = T(); init(t); x.f = t`), not only when it is read once | Objects: retains -45 % | medium |
| [x] Borrow up to the last use when the place is overwritten after it (`x.f = g(x.f)`) | part of the same -45 % | medium |
| [x] The same last-use rule for the result of a borrowed call (`BorrowedReturns`): when the place is written later in the block but not before the local's last use, the result is borrowed that long. Not for the `__m` form, whose result may be the caller's own | low to medium | low |
| [x] A `var` local that walks a structure (`node = node.next`) borrows when nothing in the rest of the block writes the fields it follows. Not when it starts from a local at its last use (the walk then consumes the structure, which measured faster than giving it up whole: Objects 0.20 s against 0.25 s) | one retain and release less per step | medium |

---------------------------------------------------------------------------
### 4. Calls and dispatch
---------------------------------------------------------------------------

| | Gain | Effort |
|---|---|---|
| [x] Call the implementation directly when only one class implements the method, or the type is known at the call (`Devirtualize`: the class is followed from the allocation through locals, parameters, fields and results) | Dispatch -17 %, -48 % with LTO; 375 calls in the showcase | medium |
| [x] A function called with objects of several classes for one parameter is copied for the calls that pass a known class (`Devirtualize.copyFor`, `KirCopy.kt`): `total(values: List<Int>)` given an `ArrayList` goes to `total__c0`, where the iterator and its `hasNext` and `next` are direct calls. At most 4 copies of a function of at most 80 statements, three rounds. `tests/ktc2/memory/Copies.kt`, `KTC2_SKIP=copies` | the `for` over a list parameter in `Ordinary`: 87 ms -> 60 ms with LTO (half its calls pass an `ArrayList`); Ordinary -7 % with LTO, -3 % without; programs under 1 % larger | medium |
| [x] `listOf(a, b, c)` is of a known class: with at least one element and no spread the call is lowered as `elements.asList()`, which is what `listOf` does then (`lowerCall` in `BodyLowering.kt`) | with the two lines below: Collections -18 %, Ordinary -17 %, Dispatch -12 % | low |
| [x] `ArrayList` does not test the index of its own storage: `Storage.at` and `Storage.put` (`Ktc2Internal.kt`) are `get` and `set` without the test, for the platform layer where it has made sure of the index. The storage of an `ArrayList` never shrinks and every index was below its length. So `add` cannot throw, and no caller tests for an exception after it | part of the above | low |
| [x] A function that returns the result of a call directly (`fun first() = at(0)`) can have its result borrowed: `ExceptionLowering` gives every returned call a local, the shape it already gave the ones that can throw and the only one `BorrowedReturns` looked at. Found when `next()` of the list iterator lost its borrowed form (retains of Collections: 8.7 M -> 38.7 M until fixed). `tests/ktc2/objects/ReturnedCalls.kt` | correctness of the counts; Objects and Threads -8 % | low |
| [x] `HashMap` no longer tests the four indices of a probe: `find` holds the table and the three arrays in locals for the whole probe and reads them with `Storage.at`. They are replaced together, and a slot only names entries its own arrays have, so that holds even when comparing a key runs code that clears the map. The index it returns is still tested by the caller. No restart when `changes` moved: what the JVM does then is not defined either | Collections: no change in time (the C compiler had most of it); less code, fewer tests for an exception | low |
| [x] The class of a call's result is followed in two rounds: a call through a dispatcher that is not resolved yet says nothing of its result until nothing else moves, where it used to say "any class" at once, for good. The iterator of `map.keys` or `map.values` (the result of a call on the result of a call) is now of a known class | 558 calls made direct in the showcase, from 490 | low |
| [ ] Follow the other arguments through dispatchers too, and the element class of a collection (`List<Shape>` that only holds circles) | medium | medium |
| [ ] Stop sharing the code of a local class between the instantiations of the generic class around it (the importer reads an `X<Int>` through the type `X<Any>`): `Devirtualize` has to distrust static types wherever it sees this, and so will every later pass that reasons about classes | removes a trap more than it gains speed | medium, in the importer |
| [x] Fold `is` and `as` when the class is known: 20 tests in the showcase | low | low |
| [x] Short generated functions as `static inline` in the headers: those of at most four lines of C that need nothing but their own class and the runtime (`CEmitter.inHeader`, `KTC2_SKIP=inline`). Longer ones were tried (6, 8, 12 lines) and gained nothing | Dispatch without LTO: 0.218 s -> 0.136 s; nothing with LTO | low |
| [x] `list[i]`, `list[i] = x` and `list.size` on a list known to be an `ArrayList` are written out where they are (`ListLoops.accessor`): one test of the index, and the call to `checkIndex` only when it fails. `tests/integration/native/ListAccess.kt` | an indexed loop: 1.85 ns -> 0.25 ns for an element; Collections -9 %, Ordinary -15 % | low |
| [ ] Instantiate generic classes for hot reference types too, not only for numbers | medium: no casts, more direct calls | high, more code |
| [x] Dispatcher cases ordered by how often each class is created: not needed. A dispatcher is a `switch` on the type id, which the C compiler turns into a table or a search of its own; the order of the cases changes nothing | none | - |

---------------------------------------------------------------------------
### 5. Exceptions and checks
---------------------------------------------------------------------------

| | Gain | Effort |
|---|---|---|
| [x] A whole-program "cannot throw" answer per function, so calls to such functions are not followed by a test (`ExceptionLowering.mMayThrow` was there; direct calls and proven array accesses now make more functions qualify) | medium to high | medium |
| [x] The same answer for the runtime's functions and for dispatchers' defaults. Checked one by one: three could leave with an exception and were trusted not to (`ktc2_cs_get`, `ktc2_cs_subSequence`, `ktc2_stringSubstring`), so a bad index on a `String` seen as a `CharSequence` was not caught, and `substring` with a bad range ended the program. They are listed in `kRaisingRuntime` (`Kir.kt`); the others end the program on purpose (`AbstractMethodError`, a cast that cannot happen) | correctness | low |
| [ ] The failure in the return value, not in a thread-local slot | medium in threaded programs | high: changes how every function is called |
| [~] No bounds check in a loop whose index is known to be in range (`RangeChecks`): the loops over an array, its `indices`, `0 until size` and `Array(n) { }`. With clang at `-O2` the time does not change (it removed those tests itself); the code is smaller and such functions no longer count as throwing. Also: `while (i < a.size) { …; i++ }` (the step in the body; a copy of the counter made before the step still counts after it), `for (i in a.size - 1 downTo 0)`, and an index next to the counter (`a[i + 1]` under `i < a.size - 1`, `a[i - 1]` from `i = 1`). `list[i]` is not proven in range but written out (part 4): its test stays, the call and the test for an exception go. Left: a step other than one, strings; with clang neither changes the time | medium for array code | medium |
| [x] An array store is tested once: `ArcInsert` dropped the "already tested" mark, so every store was tested by the generated code and again by the runtime | small, everywhere | low |
| [x] The first-use test of an object or a file taken out of loops: not needed. The one of a file is at the start of the function already; the one of an object is a load and a branch that is never taken. A loop that reads two properties of an object and a top-level variable on every round takes 0.6 ns a round | none | - |

---------------------------------------------------------------------------
### 6. Strings and the library
---------------------------------------------------------------------------

| | Gain | Effort |
|---|---|---|
| [x] Remember the last index and byte position in a non-ASCII string, so `s[i]` in a loop is not a scan each time (per thread; forgotten when a non-ASCII string is destroyed) | high for loops over such text | low |
| [x] A template is built in a buffer of the frame already, and copied once into its string. What it cost was `printf` for every number: integers and Booleans are now written by the runtime itself (`format_signed`). `Double` and `Float` still go through `snprintf` and `strtod`, up to 17 times each, to find the shortest digits: an algorithm of its own (Ryu, Grisu) is what is left | a template with three numbers: 81 ns -> 25 ns; Strings -17 % | low |
| [~] Platform versions of hot library paths. Done: `indexOf`, `lastIndexOf`, `regionMatches` (so `contains`, `split`, `startsWith`, `endsWith`, `replace`) on ASCII strings work on bytes in the runtime: Strings -75 %. Also done: the same for strings that are not ASCII (they went one character at a time, each found by walking the string: 300 times slower than now), two remembered places for code that reads two strings side by side, `StringBuilder.append` of a string (one copy) and of a number (no string in between), `toCharArray`, the hash and the order of ASCII strings on their bytes, `ArrayList.sort` in its own storage. Left: `String.hashCode` kept in the string (four bytes more for every string: to be weighed), `sortedBy` on objects (15 ns for a comparison, most of it the two keys boxed), `Double` as text | medium | medium, one at a time |
| [~] `Regex`. Done: a pattern that starts with a character, exactly, is only tried where that character is (`find`, `findAll`, `containsMatchIn` 3 to 6 times faster for such a pattern), and the text is turned into characters by one loop. Left: the same for a pattern that starts with a class (`[a-z]+`: 42 ns for every place it is tried), and keeping the match state between searches (three small objects for each search) | medium for Regex-heavy code | medium |

---------------------------------------------------------------------------
### 7. Code size and the C build
---------------------------------------------------------------------------

| | Gain | Effort |
|---|---|---|
| [x] Link-time optimization by default for `--build` without `--debug`, and through CMake where the compiler has it (`KTC2_LTO=0`, `-DKTC2_LTO=OFF`). With clang on Windows it needs LLVM's linker: used when `lld-link` is on the path (`-fuse-ld=lld`), Objects 0.27 s -> 0.21 s there | 10 to 48 % faster, 30 to 45 % smaller | low |
| [x] One C file per package, not per class: not done as such. What it was for is there another way: inlining without LTO by the short functions in the headers (part 4), and a build by one compiler by `ktc2_all.c`, which includes every C file (part 1) | - | - |
| [ ] One implementation for instantiations that compile to the same code | smaller programs | medium |

---------------------------------------------------------------------------
### 8. Features
---------------------------------------------------------------------------

Decided, not started:

- [ ] **The kotlinx libraries.** `KOTLINX.md` is the plan for kotlinx.collections.immutable,
  kotlinx-datetime, kotlinx.serialization and kotlinx.coroutines (measured, with what each needs
  of ktc2 and what is the owner's to decide). Below, kotlinx-io (done) and the first notes on
  coroutines.
- [ ] **Files, through kotlinx.io, and kotlinx.coroutines.** Not an API of ktc2's own: compile
  the libraries' sources as they are and write the platform part for ktc2, as was done for the
  standard library. Later the same for kotlinx.serialization and other kotlinx libraries.
  Nothing of `java.*`. The plan (owner's decision of 2026-11-14: a plan first, nothing built
  before the choices below are made):
  - **How they come in.** Gradle fetches the sources jars (`kotlinx-io-core`,
    `kotlinx-io-bytestring`, `kotlinx-coroutines-core`, of the versions built for Kotlin 2.5) as
    it fetches the standard library's. Each is a bundled module (`resources/ktc2/modules/`, as
    `sdl3`), so a program asks for it in `module.ktc2.toml`, and its sources are compiled with
    the library, kept between programs by the compiler process. A list of files per module, as
    `modules/stdlib/files.txt`, says which source sets are taken: `commonMain`, and the native ones
    where they suit (`nativeMain`, `concurrentMain`), with `Ktc2*.kt` files for what is left.
  - **kotlinx.io first** (smaller: about 5 000 lines). Started on 2026-11-15: the bundled module
    `kotlinx-io` compiles kotlinx-io 0.9.3's `commonMain` and `nativeMain` (core and bytestring)
    as they are, and `tests/integration/modules/kotlinx-io` passes, compared with the same program on
    kotlinx-io's JVM jars. What it took of ktc2: common sources in a module, Kotlin/Native's
    default import `kotlin.native.*`, `kotlin.native.Platform`, `kotlin.concurrent.Volatile` as
    a class, and `weak`/`acyclic` in the module file (`Segment.prev`, which made every buffer of
    two segments a cycle). Files work too (`kotlinx.io.files`, kotlinx-io's own native code over
    POSIX, with what differs between Windows and the others in the module's C), except with MSVC
    (no `unistd.h` nor `dirent.h`). kotlinx-io's own tests (32 files, `tests/modules/kotlinx-io/`) pass in
    C and on the JVM. Done, but for what is listed under "Known and not done".
    `Buffer`, `Segment`, `Source`, `Sink`, `ByteString`, UTF-8: common code. Files (`kotlinx.io.files`: `Path`, `SystemFileSystem`,
    `FileMetadata`): its `nativeMain` is written over `platform.posix` (`fopen`, `fread`,
    `stat`, `opendir`), which ktc2 generates; to try as it is first, with a Windows part where
    its `mingwMain` needs one. Tests: its common tests, taken in as the library's are
    (`tests/modules/kotlinx-io/`). Estimate: 2 to 4 days.
  - **kotlinx.coroutines** (about 30 000 lines of common and concurrent code). What the
    platform part has to give: `Dispatchers.Default` and `Dispatchers.IO` (threads of
    `ktc2.concurrent`), `Dispatchers.Unconfined`, no `Dispatchers.Main` (it fails, as on
    Kotlin/Native for Linux and Windows), `runBlocking` and its event loop, the thread that
    runs `delay` and timeouts (`DefaultExecutor`), what is local to a thread (`ThreadLocal`:
    a C `_Thread_local` behind a platform function), the handler of uncaught exceptions (it
    prints them), and atomicfu: the library is written with `atomic(...)`, `AtomicRef`,
    `SynchronizedObject` and `reentrantLock`, which a compatibility layer gives over
    `kotlin.concurrent.atomics` and the monitors of `ktc2.concurrent`. Tests: its `commonTest`
    (with its `TestBase`), as the library's are. Estimate: 2 to 3 weeks.
  - **Decided by the owner (2026-11-18)**, as Kotlin/Native: `Dispatchers.Default` a pool of as
    many threads as processors, started on first use; `Dispatchers.IO` the same pool, allowed up
    to 64 threads; cycles: the library compiled as it is, what its own tests leave allocated
    measured, then `weak`/`acyclic` entries in its module file where its design makes that
    harmless. The choices as they were put:
    1. `Dispatchers.Default`: a pool of as many threads as processors (as on the JVM and
       Kotlin/Native), started on first use; or one thread, simpler to reason about.
    2. `Dispatchers.IO`: the same pool with more threads (64, as on the JVM), or an alias of
       `Default`.
    3. Cycles. The library links a parent `Job` and its children both ways, and handlers,
       channels and `select` hold continuations that hold them back. On the JVM and Kotlin/Native
       the collector frees what is left when a coroutine is dropped without being completed or
       cancelled. Here a completed coroutine detaches itself, but an abandoned one would stay
       allocated. Either the sources are compiled as they are and the leaks measured on the
       library's own tests first (the leak check of the suite says where), or ktc2 gets a way
       to say, outside the sources, that some fields are `@Weak` (a list per module, as
       `hidden.txt`).
    4. Where the module's version is pinned, and how a program asks for another.
- [ ] **Unit tests of the passes.** The ones of the first transpiler were thrown away on
  purpose. To be written fresh, on KIR: `EscapeAnalysis`, `ArcInsert`, `Effects`,
  `BorrowedReturns` are where a small wrong answer costs most.

Open:

- [x] **`ConcurrentModificationException`.** `ArrayList` and `HashMap` (and what is built on
  it: `LinkedHashMap`, the sets) count their changes, and their iterators fail as on the JVM,
  the cases the JVM does not notice included, and `sort()` counts as a change. One compare per
  `next()`. The lists built on `AbstractMutableList` (`ArrayDeque`) check by `modCount`, as
  the JVM's `AbstractList` does, and `subList` is a view that fails once its list changed
  size another way.
- [x] **The last digit of `exp`, `ln`, `pow`, `sin`.** Measured on 2026-11-14: the C library is
  much closer to what the JVM prints than fdlibm would be ("Where the work stands", not
  differences after all). It stays the C library's, as on Kotlin/Native.
- [x] **An enum that is a number: `@SimpleEnum`** (`DESIGN.md`, section 4;
  `tests/ktc2/values/SimpleEnums.kt`, `tests/integration/errors/SimpleEnumState.kt`). A C `enum`;
  `@EnumValue` on an `Int` or `UInt` parameter of the constructor gives the numbers (a
  constant, or a `const val` such as the ones of C bindings), and `ktc2.enumOf<T>(number) {
  it.code }` goes from a number back to the entry: a loop over the entries on the JVM, the
  number compared with the entries' own in C. Left:
  - [x] `name`, `ordinal`, `toString()` (unless the class wrote its own) and `compareTo` of a
    value are answered from the number, by a lookup of their own as the constructor's
    properties have (`simpleEnumBuiltin`): no object. `compareTo` is the difference of the
    ordinals, as on the JVM.
  - [x] the SDL3 module's namespaces of constants are `@SimpleEnum` classes with SDL's own
    numbers: `SDL3.Event`, `Scancode`, `MouseButton`, `BlendMode`, `TextureAccess`,
    `PixelFormat`, `Flip`, `SystemCursor`. The functions of the module take them
    (`setBlendMode(SDL3.BlendMode.Blend)`, `isKeyDown(SDL3.Scancode.Space)`), and an event
    gives them (`event.kind`, `event.key.code`, `event.button.pressed`). The showcase and the
    demo build and run with gcc and MSVC. Not exercised by a test: an event that really comes
    from SDL (the automatic run of the showcase calls its screen directly).
  - [ ] the flags that are or-ed together (`InitFlags`, `WindowFlags`, `MessageBoxFlags`) are
    still `object { const val }`: they are not an enum. A `value class` over the number with
    the constants in its companion and an `or` would check them too, and costs nothing.
  - [ ] a `@SimpleEnum` given to a C function is converted by hand (`mode.value`): the
    bindings could accept the enum where C declares the `enum`.
  - [ ] `enumOf` gives the object kept for the entry (`T?`), which `?: Unknown` turns back
    into the number: correct, with a chain of compares on each side. A form with a default
    (`enumOf(number, Unknown) { }`) would stay a number all the way.
- [x] **A failing `valueOf` throws** `IllegalArgumentException("No enum constant ...")`, for
  every enum: it ended the program before.
- [x] **Coroutines** (2026-11-14). `suspend` functions in full, by the transformation into a
  state machine (`DESIGN.md`, "Coroutines"); `sequence { }`, `iterator { }`,
  `DeepRecursiveFunction`, `startCoroutine`, `suspendCoroutine`, the intrinsics of
  `kotlin.coroutines`. Tests: `basic/SuspendFunctions`, `stdlib/SequenceBuilders`, and the
  library's `SequenceBuilderTest`, `CoroutinesReferenceValuesTest`, `DeepRecursiveTest`. Left:
  - [ ] kotlinx.coroutines (its common sources and a platform part, as for the library);
  - [ ] a `suspend fun interface`;
  - [ ] a frame that is not a state machine still costs nothing, but every state machine is an
    object on the heap and all its locals are fields: locals that never live across a
    suspension could stay C locals, and a coroutine that never escapes could be in the frame;
  - [ ] the driver of a coroutine (a receiver that is also its completion) is held without a
    count also where suspension is not restricted to it.
- [ ] **Android.** The C should compile with the NDK's clang. Missing: `println` to the log
  and not to a console; a program as a shared library entered from Java and not a `main`; a
  clock that works below API 29; the build driven by the NDK's CMake toolchain file.
- [ ] **MSVC without gcc.** The bindings generator runs gcc or clang to read headers. With
  `cl /E` it would need neither.
- [ ] **Paths and arguments on Windows** are taken as bytes: right for ASCII only. Use the
  wide-character functions.
- [x] **A monotonic clock** behind `kotlin.time` and `measureTimeMillis`
  (`ktc2_ticksNanos`: `QueryPerformanceCounter`, `clock_gettime(CLOCK_MONOTONIC)`). The
  Windows and Linux branches are written and not yet run.
- [x] `Clock` and `Instant`, case of characters beyond U+FFFF, the `U` flag of `Regex` and its
  four-letter script codes, `Base64` (2026-11-14).
- [ ] **A `.klib`-like way to ship a module.** Today a module is its sources (a directory or a
  git repository). The Kotlin compiler ktc2 runs is the JVM one and cannot read klibs, and
  published klibs are built against Kotlin/Native's own library, so this means either a
  format of ktc2's own or moving the front end; not small.
- [x] **The package of the SDL3 module's Kotlin API is `ktc2.sdl3`** (it was `ktc.sdl3`, a
  name left from the first transpiler).
- [x] **A test of a warning.** A program with a `<Name>.warnings` file has to be accepted
  with a message that contains each line of that file.
- [x] **A dump of KIR**, after the importer and after each pass: `--dump-ir` writes
  `kir/0-import.txt` to `kir/4-arcinsert.txt` (`KirDump`), the same functions in the same
  order, so `diff` of two of them shows what a pass did.
- [x] **A test that must fail to compile.** A program with a `<Name>.errors` file next to it
  has to be refused, with a message that contains each line of that file
  (`tests/integration/errors/`). A use of `java.*` and a `@SimpleEnum` with state have one. So have
  inline storage that would need a copy (`InlineNeedsCopy`) and a `@Value` or `@Size` local
  that cannot stay in the frame (`ValueLeavesFrame`).

---------------------------------------------------------------------------
### What I would do first
---------------------------------------------------------------------------

The order to follow is "What to do next, in order" at the top of this file; this list is the
older one it continues (its items 1 to 4 are done or carried over there).

1. Read the CI run of the last push (see "Where the work stands"); fix what it shows, with a
   targeted run, before anything else.
2. What is left of Kotlin/Native's behavior (`tools/native.sh`, 2026-11-16):
   - (done 2026-11-16) `Regex` is Native's engine; naming the JVM's exceptions is refused;
     `listOf(a, b)` checked against Native (`stdlib/ListOfViews`);
   - the speed of Native's regex engine against ktc2's former one: to measure
     (`tools/bench.py`, a benchmark of regex to add);
   - not to follow: `readLine()` after `readln()` finds nothing on Native (one reads stdin
     directly, the other through `stdio`'s buffer), and a stack trace's frames.
3. Memory, proposed on 2026-11-16: the pair freeing through a field typed by an interface or
   an open class (the partner's class checked at run time), which would free the serializers'
   cycle of kotlinx-serialization; and pair cycles in a program with threads (part 3: the check
   reads two counts another thread could change; atomic loads and a second look would do).
4. The benchmarks against Kotlin/Native and the JVM ("Benchmarks against Kotlin/Native and the
   JVM: the plan"), about three days, nothing of ktc2 itself changed. Number formatting is now
   Native's code: ordinary values as fast as Native, extreme exponents (the big-number path)
   three times slower (array accesses with checks, functions not inlined across files).
5. More tests taken as they are: what is left of `kotlin-native/runtime/test`, the box tests
   of the Kotlin compiler beyond the coroutines'.
6. Unit tests of the passes (part 8).
7. Speed: `sortedBy` without boxing its keys, an object that never leaves its function as
   plain locals (parts 2 and 6).

---------------------------------------------------------------------------
### Measuring
---------------------------------------------------------------------------

- `python tools/bench.py [name part...] [--runs N] [--copt option] [--save file] [--compare file]`
  translates `benchmarks/*.kt` with the installed tool (`./gradlew installDist` first), builds
  each at `-O2`, and prints the best time, the allocations, retains and releases, and the size
  of the program; the table goes to `build/bench/results.txt`. `--compare` shows the change
  against a table saved before; `KTC2_TOOL` names an installed tool of another state of the
  sources, to measure both on the same day. Do not run it while the suite runs.
- `KTC2_SKIP=devirtualize,copies,listloops,ranges,handover,brief,walk,owned,slots,rooms,inline` leaves optimizations out, one or several, to
  measure what each gives or to find which one a wrong program comes from;
  `KTC2_DEBUG_PASSES=1` prints how many calls were made direct and how many array accesses
  were proven in range.
- `build/integration/arc-stats.txt` holds allocations, retains and releases of every test
  program after a run of the suite; `python tools/arc_compare.py before.txt after.txt`
  compares two runs. Copy the file before a change.
- `tools/retain_profile.sh <group/Name>` lists the lines of one program that retain most.
- `KTC2_DEBUG_BORROW=1` prints why a function's result cannot be borrowed and why a caller did
  not borrow.
- `--warn-heap` lists what stayed on the heap and why.
- `KTC2_TIMING=1` prints where compile time goes.



---------------------------------------------------------------------------
# Archive: the plan for the kotlinx libraries (KOTLINX.md), until 2026-11-18
---------------------------------------------------------------------------

What was `KOTLINX.md`: how kotlinx-io was taken, the libraries measured, what they needed of ktc2, and each
library's state (kotlinx.coroutines' cycles in detail). What is left of it is in `PLAN.md`.

## The kotlinx libraries in ktc2

How kotlinx.collections.immutable, kotlinx-datetime, kotlinx.serialization and kotlinx.coroutines
can become modules of ktc2, after kotlinx-io, which is one (2026-11-15). Written before any of
them was started: the numbers are measured on the libraries' published sources; the estimates
are estimates. `PLAN.md` (part 8) points here.

**Done since** (2026-11-15): kotlinx.collections.immutable, atomicfu and kotlinx-datetime are
modules, with all of their own tests passing (`tests/README.md`); kotlinx.serialization's core is
one too (`kotlinx-serialization`), without the compiler plugin yet, because datetime needs it;
`@ThreadLocal` is local to a thread (point 2 of section 3); the versions are the latest, pinned in
`gradle/libs.versions.toml`. Next: the serialization plugin and kotlinx-serialization-json.

---------------------------------------------------------------------------
### 1. How kotlinx-io was done, and what it left in place
---------------------------------------------------------------------------

A library is a bundled module made of its own sources, unchanged, and nothing of ktc2's own API:

- **Sources.** The module file names the library's `version` and its `upstream`: the sources jars
  of Maven artifacts (the common one, and those of Kotlin/Native targets, `-mingwx64`, `-linuxx64`,
  `-macosarm64`, which hold the source sets of each system), or a git repository. ktc2 fetches them
  the first time a program needs them, into `<cache>/modules/<name>@<version>` (`~/.ktc2/cache`;
  the repositories are the module's own `[[maven]]`, then the setting `maven`, Maven Central by
  default), and `files.txt` in the module lists the files compiled, by paths or patterns
  (`core/commonMain/**`). ktc2's resources carry only the module's own files.
- **Source sets.** `commonMain` is common code; `nativeMain`, a level of its own when it has
  `expect`s, sees what Kotlin/Native's platform libraries are to it (ktc2's platform layer,
  `kotlinx.cinterop`, `platform.posix`); the rest refines it. A file of a Native source set gets
  `import kotlin.native.*`, as Kotlin/Native imports it by default.
- **What each system gives apart** (`mingwMain`, `linuxMain`, `appleMain`...) is a file of the
  module's own, once for every system, with the little C that differs in a `.def` of the module.
- **Cycles** a collector would free: `weak` and `acyclic` in the module file.
- **Tests**: the library's own common tests, unchanged, under `tests/<library>/`, run in C
  against the module and on the JVM against the library's JVM jars (which they see the
  internals of); and a program of ktc2's under `tests/integration/modules/<library>`.

What it took of ktc2, now there for the next ones: common sources in a module, three levels of
source sets, `kotlin.native.Platform`, `kotlin.concurrent.Volatile` as a class, `convert()`,
POSIX's headers for files in `platform.posix`, and two units for two files of one name.

---------------------------------------------------------------------------
### 2. The four libraries, measured
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
### 3. What they need of ktc2 besides what kotlinx-io left
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
### 4. Each library
---------------------------------------------------------------------------

#### kotlinx.collections.immutable 0.5.2 (done)

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

#### kotlinx-datetime 0.8.0 (done)

Done in a day, not as planned below: its classes are `@Serializable(with = ...)`, so it needs
kotlinx-serialization's core (a module of its own, which it depends on); its source sets are
three levels as ktc2 has them (`commonKotlinMain` in the middle, as `nativeMain` is); each system
takes its own source sets, unchanged (a file of the module's list may be for some systems only):
`linuxMain` with `tzfileMain` and `tzdbOnFilesystemMain` on Linux and macOS, `windowsMain` on
Windows, which reads the registry through `platform.windows`, declared in a `.def` of the module.
That took typedefs, wide strings and macros of `<windows.h>` in the bindings. The packaged
database (`kotlinx-datetime-zoneinfo`) has no sources: it is compiled for the web only.

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

#### kotlinx.serialization 1.13.0 (core and JSON)

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

#### kotlinx.coroutines 1.11.0

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
- **State (2026-11-18)**: the module `kotlinx-coroutines` compiles the library as it is, and a program with
  `runBlocking`, `async`, `delay`, `launch`, channels, flows, `Dispatchers.Default` with a `Mutex`,
  cancellation, `withTimeoutOrNull` and exceptions runs as on the JVM
  (`tests/integration/modules/kotlinx-coroutines`). What it took: Kotlin/Native's `Worker` and `Future`
  in the platform layer (`Ktc2Workers.kt`, over a monitor that a thread can wait on and be woken, new in the
  runtime: `ktc2_monitorWait`, `ktc2_monitorNotifyAll`), `Platform.getAvailableProcessors()`,
  `identityHashCode()`, `processUnhandledException`; workers that end with the program, as Native's do
  (one idle at the end of `main` is ended and gives back what it holds; one in a job, as the event loops of
  `Dispatchers.Default`, is left to end with the process, and then the leak check is not made);
  `concurrentMain` as a level of its own between `commonMain` and `nativeMain`; Native's own
  `CancellationException`; a `suspend` lambda that implements a `fun interface` (`FlowCollector`); and a
  `suspend` call returned from inside a `try`, which ktc2 took for a tail call (its `catch` never saw what the
  resumption brought back: `withTimeoutOrNull`). Left: the library's cycles (its program leaves objects:
  `program.cycles` says so until `weak`/`acyclic` entries answer them), its own tests (in the suite since the night of
  2026-11-18: `PLAN.md`), the pending box
  tests of coroutines, `kotlinx-coroutines-test`.
- **Cycles (2026-11-18)**: the owner chose to answer them in the library (edits of the module), after
  atomicfu's atomics are made fields of their owner (`DESIGN.md`, section 6), which they now are. Measured
  with `KTC2_CYCLES=1` on a program that launches N coroutines, awaits N and sends N through a channel:
  before, 8,541 / 17,721 / 109,521 objects left for N = 10 / 100 / 1,000 (each atomic three or four
  objects, every cycle through one of four or more); now 2,642 / 4,397 / 21,947, of which 2,386 are a
  fixed set of atomics, and every cycle left is of two to four objects: a removed node and its cached
  `Removed`, a coroutine and its `context` (`parentContext + this`), a resumed continuation, its
  `CompletedContinuation`'s cancel handler and the job node that resumed it, a `DispatchedContinuation`
  and its reusable `CancellableContinuationImpl`, a channel and its segments (`ChannelSegment._channel`),
  and a closed empty list of handlers. Then answered (`modules/kotlinx-coroutines`): the cached `Removed` is
  not cached (`LockFreeLinkedList.kt.edit`); a job node that resumes a continuation lets go of it once it has
  (`JobSupport.kt.edit`: a node is invoked once); a `DispatchedContinuation` lets go of the continuation it
  keeps for reuse when it is released (`DispatchedContinuation.kt.edit`, taken out on 2026-11-19: it broke the
  library's protocol of reuse, see section 12); a segment refers to its channel
  weakly (`weak`: whoever sends or receives through it holds the channel); a coroutine's `context` is made
  when it is read, `parentContext.minusKey(Job) + this`, equal to what it was (`AbstractCoroutine.kt.edit`:
  only two reads are no longer the same object, which `withContext` and a flow's collector use as a
  shortcut, never for a result). The same program now leaves 2,359 objects for any N: the dispatchers'
  own, which live as long as the program. Left, from a program that also uses flows, a `Mutex`, timeouts,
  cancellation and failures: the circular lists of handlers, closed when a job ends (a ring of the list's
  head and its nodes); a cancelled job and its `JobCancellationException`, which refers back to it; and
  ktc2's own, a finished state machine that still holds its locals in its fields (`withTimeoutOrNull`'s
  continuation is itself). That last one was not: the state machine holds itself there without a count; it
  was kept by the ring of its timeout's job. Then answered: the ring of a job's list is cut once the job has
  ended and notified it (`LockFreeLinkedList.kt.edit`, `ktc2Unlink`: each node then refers to the head
  only, the head to itself; called from `JobSupport.completeStateFinalization`), and a timeout's exception
  refers to its coroutine weakly (`weak`: only `withTimeoutOrNull` reads it, to know its own, holding that
  coroutine meanwhile). The cut of the rings was taken out the same day: another thread may still walk the list
  of a job that is ending (cancelling it, adding a handler), and on a cut ring it walked for ever (a benchmark
  of 20,000 cancellations on `Dispatchers.Default` hung in `correctPrev`). The rings of jobs with several
  handlers are left again, until an answer safe under concurrency. That answer: the module declares the
  lists as rings (`rings`, `DESIGN.md`, Cycles), which the runtime frees when only their own objects hold them,
  checked when a list's head goes; and a `ChildCompletion` (the node a parent puts in its child's list) lets go
  of the parent's state, the child and the result once invoked (`JobSupport.kt.edit`), which otherwise held two
  jobs and their lists together. The broad program leaves 2,356 objects again for any N. Left: a cancelled coroutine and its `JobCancellationException`, which refers back to
  it from the coroutine's final state (four objects for each cancelled coroutine), and the dispatchers' own
  structures, which live as long as the program. The first is now declared by the module (`cycles`, a cycle
  of three the runtime frees as it frees a pair: `DESIGN.md`, Cycles). Without the cut of the rings, the broad
  program (flows, a `Mutex`, timeouts, cancellation, failures) leaves again a few objects for each job that had
  several handlers; launch, async/await, join, channels and cancelled coroutines leave none that grows.

---------------------------------------------------------------------------
### 5. Order, and what is the owner's to decide
---------------------------------------------------------------------------

Proposed order: collections.immutable, then datetime, then serialization, then coroutines.
The first two need little of the platform and test the method; serialization brings the one
new kind of thing (a compiler plugin); coroutines needs everything else and is the largest.

Decided by the owner (2026-11-15): `@ThreadLocal` made real, as on Kotlin/Native; atomicfu from
its own sources; the latest version of each, pinned in its module file since 2026-11-17
(`modules/<name>/module.ktc2.toml`: `version`, `upstream`, `jvm`; `gradle/libs.versions.toml` before).

To decide:
1. The choices for coroutines already listed in `PLAN.md` (dispatchers, `Dispatchers.IO`, cycles).
