# ktc2 — Kotlin semantics on ARC, with opt-in value storage

ktc2 translates Kotlin to C11: "Kotlin semantics, C cost model". Its front end is the real
Kotlin compiler (K2), so parsing, type inference and overload resolution follow Kotlin's own
rules. Everything specific to it lives in the **`ktc2.` package** — nothing is added to the
language itself.

This document is the design, followed by the plan it was built by and, phase by phase, what
was done (§10): the newest entries are at the end of that section.

---------------------------------------------------------------------------
## 1. Where this comes from
---------------------------------------------------------------------------

ktc2 replaces a first transpiler (called v1 below; it is in the history of this repository, up
to the commit that removed it). v1 made everything a *value* by default: a class was a C
struct copied on the stack, sharing and the heap were explicit (`Ref<T>`, `.allocWith(Heap)`,
`freeMem`), and `String` and `Array<T>` were views into the frame that produced them. Because
lifetimes were manual, the compiler had to refuse every pattern that could dangle — returning
a string, aliasing an object, a lambda reading a local — and each refusal was a place where
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
than a GC would — only visible through `WeakRef<T>` and deinit hooks).

---------------------------------------------------------------------------
## 4. Type and storage model
---------------------------------------------------------------------------

Three categories of type, and five places an object can live.

### 4.1 Categories

1. **Primitives** — `Int`, `Float`, `Boolean`, `Char`… plain C scalars. `Int?` stays the
   existing `$Opt` tagged struct. Never boxed inside generics (monomorphization).
2. **Value types** — no identity, freely copied, never ref-counted:
   - `@Struct class Vec2(val x: Float, val y: Float)` (all `val`, fields themselves value or
     reference types),
   - `value class`, `@SimpleEnum` enums, `Pair`/`Triple` of values.
   `===` on a value type is a compile error (same as Kotlin value classes).
3. **Reference types** — everything else: classes, `String`, `Array<T>`/`IntArray`,
   collections, function values, interfaces, `Any`. Identity, aliasing and mutation behave
   exactly as in Kotlin.

### 4.2 Storage classes for a reference-type object

| Storage | Chosen when | RC cost |
|---|---|---|
| **Static** | string literals, `object`, enum entries, top-level constants | none (immortal) |
| **Stack** | escape analysis proves the reference never outlives the frame | none |
| **Inline in owner** | slot declared `@Value T` / `@Size(N)`; or `@Struct` type | none |
| **Arena** | allocated inside an `arena { }` scope | none (bulk freed) |
| **Heap** | everything else — the *fallback*, not the default path | retain/release |

"Autoboxing" in this model is one precise operation: **a stack/inline object is copied to the
heap at the point where a reference to it must escape.** For immutable objects that is
invisible, so it is implicit. For mutable objects it would change aliasing, so it must be
written `.copy()` (which the JVM also executes as a copy — behavior stays identical).

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
- Anything that would create a second long-lived name for a **mutable** inline object —
  `val p = pool[i]`, `list.add(pool[i])`, `pool[i] = other` — needs `.copy()`. This is today's
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
  function is legal — it boxes (heap copy) if the caller lets it escape, and is written
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
  fields get `NULL` — release is a bare `free`.
- When the static type is a final class the release calls its `destroy` directly; the
  `typeInfo` lookup is only used through interfaces, open classes and `Any`.
- Every reference is a thin pointer, including interface and `Any` references. A virtual or
  interface call goes through a generated dispatcher that switches on the header's `typeId`
  and calls the implementation of that class directly; only classes the program instantiates
  appear in it. `is` / `as` are switches on the same id. There are no vtables.
- Primitives assigned to `Any` are boxed (a small object holding the value), as in Kotlin;
  nullable primitives (`Int?`) are not: they are a two-field value, flag + primitive.

### 5.2 Ownership rules — where the overhead is removed

Naive ARC retains on every copy. These rules, all decidable by a local or summary-based
analysis, remove most of it:

1. **Parameters are borrowed.** The caller keeps the argument alive; the callee retains only
   if it stores or returns it. A call costs zero RC operations.
2. **Returns are owned.** The callee hands over one count; the caller binds it without a retain.
3. **`val` fields borrow, `var` fields retain.** Reading a `val` field of a live object into
   a local needs no retain (it cannot be overwritten). Reading a `var` field retains unless no
   write or unknown call happens before the local's last use. *Immutability is what makes ARC
   cheap — the same thing Kotlin style already encourages.*
4. **Last use moves.** If a local's final use is a store, a return, or an escaping argument,
   ownership is transferred instead of retain + release.
5. **Static, stack, inline and arena objects are never counted** — elided at compile time
   when the storage is statically known, one flag test otherwise.
6. **Escape analysis promotes to the stack.** Per function, each parameter gets a summary bit
   "escapes" (stored, returned, captured by an escaping closure). The program is whole-program
   compiled, so summaries are a fixpoint over the call graph; an interface method's summary is
   the join over its implementors. `val p = Foo()` passed only to non-escaping parameters is a
   stack struct — which is what KTC emits today, now automatic and safe.
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
`java.lang.Thread` — known at compile time); atomic counts when it does. Later refinement: a `SHARED` header bit set when an object crosses
a thread boundary, so only shared objects pay for atomics.

### 5.5 Deterministic destruction

ARC gives it for free, Kotlin has no equivalent. A class may implement `ktc2.Deinit`
(`fun deinit()`), called when the count reaches zero — meant for classes owning C resources.
On the JVM it is not called automatically. `AutoCloseable` + `use { }` remains the portable
way and replaces `defer`.

---------------------------------------------------------------------------
## 6. Avoiding the heap — the toolbox
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
`ARENA` flag and are still counted — an increment and a decrement are cheap, `malloc` and
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
  leak. ktc2 uses **checked propagation** — a may-throw call is followed by a test
  of a thread-local pending-exception slot and a `goto` to the function's cleanup label (the
  model Nim uses with ARC). Whole-program analysis knows which functions can throw, so
  non-throwing call chains pay nothing. It also removes `KTC_TRY_FN` / `optnone`, E132, E133,
  the "extra exception fields dangle" limit (the exception is a normal ref-counted object),
  and allows `try` as an expression. Cost: one branch after each may-throw call; a
  `--checks=abort` mode that makes bounds/null failures fatal keeps the may-throw set small
  for embedded builds.
- **C interop: Kotlin/Native's `kotlinx.cinterop`, same syntax.** A `.def` file names the
  headers; ktc2 generates the Kotlin binding declarations from it, and since the output is C
  the generated functions lower to direct calls with the header `#include`d — no bridge code.
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
## 8. Output layout — one `.h` / `.c` pair per class
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
  only the platform layer the JVM normally provides — the storage of `String`, arrays,
  `ArrayList`, `HashMap`, `StringBuilder` — following Kotlin/Native's pure-Kotlin
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
| 0 | Skeleton + oracle | module, `ktc2.` package, K2 driver, and the dual-run test (`IntegrationTest`: every program in `integration/` runs as JVM classes and as C, outputs must match) — **done** | `./gradlew :ktc2:test` |
| 1 | KIR + first C | KIR import from K2 IR; per-class C emitter for primitives, functions, control flow, `@Struct`; `println` and operator intrinsics — **done** (see status below) | arithmetic / control-flow programs match the JVM |
| 2 | Naive ARC | `ktc2_core` header + type table + retain/release at every copy and scope exit; classes, `String`, arrays, interfaces, inheritance, objects, enums, data classes, boxing as references; leak report at exit — **done** | class / string / array programs match the JVM, zero leaks |
| 3 | Generics, closures, std library | generics by monomorphization, inline functions, closures, object expressions; `kotlin.collections`, `kotlin.text`, ranges and sequences compiled from the Kotlin stdlib's own sources — **done** | collection, string and lambda programs match the JVM |
| 4 | Ownership optimization | rules 1–5 of §5.2 in `ArcInsert`; reference-count statistics (`-DKTC2_ARC_STATS`, collected per test in `build/integration/arc-stats.txt`) — **done** (see status) | RC operations per test drop sharply, output unchanged |
| 5 | Escape analysis | whole-program escape summaries; stack promotion of objects, closures, cells, strings and small arrays; `@Value` / `@Size` inline fields with autobox; `--warn-heap` — **done** (see status; caller-slot returns and inline array elements are left) | heap allocations drop where objects are temporary, output unchanged |
| 6 | Exceptions + cycles + threads | checked propagation; cycle lint, `@Weak` / `WeakRef<T>` / `@Acyclic`; threads with atomic counts — **done** (see status; index and division failures are still fatal) | exception programs leak-free; thread programs under atomic RC |
| 7 | Arena + C interop | `arena { }`; `kotlinx.cinterop` API + `.def` binding generator — **done** (see status); SDL3 binds and runs, v1's wrapper module is not ported | v1's interop tests ported and passing |

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
by hand), `collections/Arrays.kt`, comparators built from lambdas (`compareBy`, `sortedBy` —
SAM conversion), `Random`, `kotlin.math`. In `kotlin.text`, case conversion and the Char
classification functions cover ASCII and Latin-1 and treat the rest of Unicode
approximately, and `Regex` is declared but throws. `throw` reports the exception and exits
(Phase 6 makes it catchable).

Phase 4 — ownership optimization. Three rules were added to the naive scheme, each removing
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

Phase 5 — escape analysis and inline storage (`EscapeAnalysis`, before `ArcInsert`;
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
  the heap — the autobox — and a mutable object or an array is refused with a message asking
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

Phase 6 — exceptions, cycles, threads (`integration/objects/Exceptions.kt`, `Weak.kt`,
`Threads.kt`):
- **Exceptions by checked propagation** (`ExceptionLowering`, the first back-end pass).
  `throw` parks the exception in the runtime and leaves the function — to the end of the
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
  Fields typed as an interface, `Any` or a type parameter — which is how collections hold
  their elements — are not followed, so the lint is silent about cycles through a collection
  or a captured lambda.
- **Threads.** `kotlin.concurrent.thread { }` and `java.lang.Thread` (`start`, `join`,
  overriding `run`) run on operating-system threads, and `synchronized` works. A program that
  reaches the thread API gets `#define KTC2_THREADS` in its generated `ktc2_config.h`: counts
  become atomic, the exception in flight thread-local, and the runtime's own tables locked.
  Every other program keeps plain increments. As on the JVM the process ends when its last
  thread does. As in Swift, a property written by one thread while another reads it needs
  `synchronized` — unsynchronized access is a data race that can corrupt a count, where the
  JVM would only show a stale value.

Not done in Phase 6: atomic counting applies to every object
of a threaded program (the `SHARED`-bit refinement of §5.4 is open).

Phase 7 — arena and C interop (`integration/values/Arena.kt`, `integration/interop/`):
- **`arena(size) { }`** as described in §6. In `values/Arena.kt` 1614 of 1626 allocations
  come from arena blocks, with the same output as on the JVM and nothing leaked.
- **`kotlinx.cinterop`**, written in Kotlin in the platform layer (`Ktc2Cinterop.kt`).
  Every type that stands for a C address — `CPointer<T>`, `NativePtr`, `CValuesRef<T>`,
  `StableRef<T>`, and the variables and structs pointed at (`IntVar`, a struct class) — is
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

After Phase 7 — what the phases above had left open:
- **The language's own failures throw.** An array index out of bounds, a string index out of
  range and an integer division by zero raise `ArrayIndexOutOfBoundsException`,
  `StringIndexOutOfBoundsException` and `ArithmeticException` (`objects/Checks.kt`).
  `ExceptionLowering` puts the test in front of the statement — `if (index is bad) { throw;
  leave }` — and the operation behind it is emitted unchecked, so the cost is the bounds
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
  field, set when an instance is created and read at the start of each member — the
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
- **The SDL3 module** (`resources/ktc2/modules/sdl3`, package `ktc.sdl3`): the port of v1's wrapper —
  `SDL3.Window`, `SDL3.Renderer`, textures, cursors, shapes, rounded rectangles, triangles,
  events, keyboard and mouse state, `gameLoop`, the `FPoint` / `FRect` math and 266 constants —
  over the generated `sdl3` bindings. Values such as `FRect` are Kotlin data classes; a
  function that hands one to SDL fills a C struct in its own stack frame, so drawing
  allocates nothing. `example/Demo.kt` opens a window, draws for a second and exits cleanly.
  It is not part of the test suite (it needs SDL3 and a display).

Values on the stack, and Kotlin 2.5 — the latest round:
- **Kotlin 2.5.0-RC.** The front end is the 2.5.0-RC compiler and the library sources are
  those of the 2.5.0-RC standard library. Programs may use what that release has, including
  what it still calls experimental (`integration/basic/Kotlin25.kt`): `companion { }` blocks
  and companion extensions (`companion val Vector.UnitX`), collection literals
  (`val xs: List<Int> = [1, 2, 3]`), full value classes and value objects, name-based
  destructuring (`(val y, val x) = point`), context parameters, guards in `when`. A member of
  a companion block is a function or a variable of the program — no object is involved. The
  reference build on the JVM is compiled with the same options.
- **`@Struct` values everywhere** (`values/StructValues.kt`). A struct is a plain C value:
  in a local, a parameter, a field, or an element of a collection (`ArrayList<Point>` stores
  the points themselves). What was missing is now there: `==` and `!=` (through the `equals`
  the class declares — a data class has one; a struct class without `equals` cannot be
  compared, since on the JVM that would be identity), `hashCode`, `toString`, string
  templates, `copy`, destructuring, secondary constructors and `init` blocks, use as a map
  key or set element, and type arguments in general. Where Kotlin needs an object — the value
  seen as `Any` or as an interface it implements, or a nullable `Point?` — the value is put in
  its *box*, a small class generated per struct that answers `toString`, `equals`, interface
  calls with the struct's own functions. A box that does not outlive its block is itself a
  stack object (comparing two structs allocates nothing). A member called on a struct value is
  called directly, never through its box. `Array<Point>` holds boxes, as `Array<Int>` holds
  boxed ints; collections hold the values.
- **References inside structs** (`values/StructReferences.kt`). A struct may hold a String,
  an object or an array. Such a value owns what it refers to: copying it into a local, a field
  or a box retains each reference (one generated `Entry__retain` per struct), dropping it
  releases them, exactly where a plain reference would be retained and released — `ArcInsert`
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
- **Language**: property delegation — `by lazy`, `by Delegates.observable`, custom
  `getValue` / `setValue`, on class, top-level and local properties (`objects/Delegation.kt`);
  `lateinit`; function references in the form the 2.5 compiler produces; `x is IntArray` on
  an `Any`, by the kind of array.
- **SDL3 module**: `FRect`, `Rect`, `FPoint`, `Color` and `FColor` are structs. The demo
  allocates five objects in its whole run.

- **Threads**: `synchronized(object)` is a recursive monitor per object, kept in a table of
  the runtime while the object is locked — two threads holding different objects no longer
  wait for each other. `lazy` computes its value once whichever thread asks first.
- **Delegates** receive a property object that knows its name (`property.name`); anything
  else asked of it fails, there being no reflection.
- **Results built in the caller's frame** (`values/InPlace.kt`) — rule 7 of §5.2. A function
  whose result is always the one object it creates, and which lets that object go nowhere
  else, gets a second form, `make__into(slot, …)`, that builds the object in room the caller
  supplies. A call whose result does not leave its block passes a slot of its own stack frame
  and uses that form; every other call keeps the ordinary one. "Creates" includes receiving
  the object from another function that has such a form, so the room is handed down a chain
  (`midpoint` → `scaled`). A function that returns one of its arguments, or one of several
  objects, has no such form. If the function throws, the slot is simply left unused.
- **Unsigned arrays** (`stdlib/UnsignedArrays.kt`): `UByteArray`, `UShortArray`, `UIntArray`
  and `ULongArray` are the standard library's own classes — value classes over the signed
  array — compiled from its sources together with their functions (`_UArrays.kt`). Being
  value classes over one reference they are structs here: a `UIntArray` is the pointer to its
  `IntArray` and nothing more.
- **`ktc2.ValueArray<T>`** (`values/ValueArrays.kt`): a fixed-size array that stores its
  elements themselves, so structs, value classes and numbers lie side by side in one block.
  It is a type of its own, which is what makes that safe.
- **A waiting monitor sleeps.** A thread that finds its monitor taken sleeps on a condition
  variable until a monitor is given up, instead of spinning.
- **`Regex`** (`stdlib/Regexes.kt`, `Ktc2Regex.kt`): a backtracking matcher written in Kotlin
  with the syntax and behavior of `java.util.regex` for what it covers — classes, anchors,
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
  only compute and store — nearly all of them — are identified, their classes go back to
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
  a reference something else keeps — `list[i]`, a getter, `iterator.next()` over such a list,
  `append` returning its receiver — gets a second form, `name__b`, whose result carries no
  count. A function qualifies when everything it returns is read from a place reached from
  its parameters, or is the result of a qualifying function called with such arguments, and
  it does not itself write those places; a virtual dispatcher qualifies when every
  implementation behind it in the program does. When only some do — one list computes its
  elements, one iterator returns boxed numbers — the dispatcher's form is `name__m`: it takes
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
- **The showcase** (`examples/showcase`): one program of three modules — a small library
  (`shapes/`), 92 checks in seven groups over the language, the library, errors, values,
  threads and the other module (`core/`, plain Kotlin, so the suite also runs it against the
  JVM as `projects/examples/showcase/core`), and a window with a button per group
  (`src/`, SDL3). `--headless` prints, `--auto` clicks every button by itself and
  `--shot file.bmp` saves what the window shows, which is how it is checked without a screen.
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
`Array<Particle>` is expected — to any function of the standard library — and code compiled
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
