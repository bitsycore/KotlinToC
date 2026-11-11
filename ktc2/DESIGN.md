# ktc2 — Kotlin semantics on ARC, with opt-in value storage

Study and plan for a second transpiler, **ktc2**, that moves from "Kotlin syntax, C semantics"
to "Kotlin semantics, C cost model". Written 2026-11-11 against `master` @ `ac6a8a5`.

ktc2 is a **separate Gradle module** (`ktc2/`). The existing transpiler (v1, root project) is
left untouched and keeps working; ktc2 shares no code with it. Its front end is the real Kotlin
compiler (K2), so parsing, type inference and overload resolution follow Kotlin's own rules. Everything specific to the dialect lives in the
**`ktc2.` package** — nothing is added to `ktc.` or to the language itself.

---------------------------------------------------------------------------
## 1. Where the project is today
---------------------------------------------------------------------------

**First intention:** Kotlin → C11 with no runtime, no GC, nothing beyond libc.

**How it got there:** everything is a *value* by default. A class is a C struct copied on the
stack; sharing is explicit (`Ref<T>`, `.asRef()`, `.refValue`); heap is explicit
(`.allocWith(Heap)`, `.copyWith`, `freeMem`); `String` and `Array<T>` are `{ptr, len}` views
whose backing lives in the producing frame.

**What that cost:** because lifetimes are manual, the compiler has to refuse every pattern
that would dangle, and each refusal is a place where valid Kotlin stops compiling:

| Today's rule | Kotlin code it rejects or changes |
|---|---|
| E020 / E022 / E023 / E120 | returning a `String`, `Array`, lambda, or reference from a normal function |
| E070 / E071 | `val a = b` on a class instance (Kotlin: alias; KTC: refused copy) |
| E054 `capture(...)` | every lambda that reads an enclosing local |
| fat interface values are copies | mutating through a parent-typed value does not reach the original |
| allocator-threaded stdlib | `ArrayList<T>(allocator, cap)` instead of `mutableListOf<T>()` |
| longjmp exceptions | exception fields other than `message` dangle; `defer`s skipped; E132 / E133 |

The result is a good *C-with-Kotlin-syntax*, but no existing Kotlin code compiles with it and
no KTC code runs on the JVM. Each new feature adds another lifetime rule.

**What is worth keeping:** monomorphized generics (no boxing of primitives in collections),
`@Size(N)` inline arrays, C interop (`C.fn`, `c.Type`, `RawArray`,
`.cPtr`), `Arena`, alloca-backed string templates, the bounds/null checks, the module system,
and the 89 integration tests + 54 unit test files.

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

| `ktc2.` declaration | JVM definition (`ktc2/src/main/kotlin/ktc2/`) |
|---|---|
| `@Value`, `@Struct`, `@Size(N)`, `@Weak`, `@Tls`, `@Namespace`, `@SimpleEnum` | no-op annotations |
| `arena(size) { ... }` | `inline fun <R> arena(size: Int, block: () -> R): R = block()` |
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

### 4.5 What is not carried over from v1

`Ref<T>` on KTC classes, `.asRef()` / `.refValue` (kept only for C out-params), `.allocWith`,
`.copyWith(alloc)`, `freeMem`, `@RequireFree` / W018, `capture(...)` / E054, E020–E023, E070,
E120, the `inline`-to-return-a-String idiom, allocator parameters on std collections,
`StringBuffer(ptr, 0)` (replaced by `StringBuilder` / `buildString`).

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
3. A cycle collector was considered (Bacon–Rajan trial deletion over the flagged types) and
   **rejected** to keep the runtime minimal; see §11.

The existing `--mem-track` allocation tracking becomes a per-type leak report at exit and is
turned on for the whole integration suite.

### 5.4 Threads

Non-atomic counts when the program does not link `ktc.std.Thread` (known at compile time);
atomic counts when it does. Later refinement: a `SHARED` header bit set when an object crosses
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
| `@Value T` / `@Struct` | programmer | inline in field, array element, or local |
| `@Size(N)` | programmer | inline fixed arrays and strings |
| `arena(size) { }` | programmer | every allocation in the dynamic scope bumps the arena; freed at block exit |
| Monomorphization | compiler | `List<Int>`, `Map<Int, Vec2>` store elements inline, never boxed |

**Arena details.** `arena { }` pushes an allocator on a thread-local stack that `KTC_ALLOC`
consults. Arena objects carry the `ARENA` flag: retain/release are no-ops. Objects whose type
has a `destroy` are linked into a per-arena list so references they hold to *outside* heap
objects are released at exit; plain-data objects pay nothing. A reference to an arena object
stored somewhere that outlives the block is a bug the compiler cannot always see: the block's
*result* is autoboxed out, and debug builds verify at exit that no arena object is still
referenced. `--warn-heap` lists every allocation site that stayed on the heap, with the
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

**Front end: Kotlin K2 (decided 2026-11-11 after the spike in `k2spike/RESULTS.md`).**
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
  the Kotlin version is pinned in `ktc2/build.gradle.kts` and upgraded deliberately. None of
  this reaches the generated C.

---------------------------------------------------------------------------
## 10. Plan
---------------------------------------------------------------------------

All work happens in `ktc2/`. v1 (root project, `integration/`, `run_tests.py`) is not modified.
Each phase ends green on its own suite and is committed incrementally.

```
ktc2/
  DESIGN.md
  build.gradle.kts                    depends on kotlin-compiler-embeddable (pinned)
  src/main/kotlin/com/bitsycore/ktc2/ the transpiler (frontend/K2Frontend.kt is the K2 hook)
  src/main/kotlin/ktc2/               the ktc2. package, JVM definitions (the oracle's classpath)
  src/main/resources/ktc2/            runtime C (ktc2_core.h/.c) + std library sources in kotlin.*
  src/test/kotlin/                    unit tests on KIR / emitted C
  integration/                        plain-Kotlin programs, C output diffed against the JVM classes
  k2spike/                            the frontend spike and its results (kept as evidence)
```

| # | Phase | Deliverable | Exit test |
|---|---|---|---|
| 0 | Skeleton + oracle | module, `ktc2.` package, K2 driver, and the dual-run test (`IntegrationTest`: every program in `integration/` runs as JVM classes and as C, outputs must match) — **done** | `./gradlew :ktc2:test` |
| 1 | KIR + first C | KIR import from K2 IR; per-class C emitter for primitives, functions, control flow, `@Struct`; `println` and operator intrinsics — **done** (see status below) | arithmetic / control-flow programs match the JVM |
| 2 | Naive ARC | `ktc2_core` header + type table + retain/release at every copy and scope exit; classes, `String`, arrays, interfaces, inheritance, objects, enums, data classes, boxing as references; leak report at exit — **done** | class / string / array programs match the JVM, zero leaks |
| 3 | Generics, closures, std library | generics by monomorphization, inline functions, closures, and `kotlin.collections` + `StringBuilder` compiled from the Kotlin stdlib's own sources — **done**; `kotlin.text` string functions, array extension functions, sequences, comparators — next | collection and lambda programs match the JVM |
| 4 | Ownership optimization | rules 1–5 of §5.2; `--arc-stats` | RC operations per test drop sharply, output unchanged |
| 5 | Escape analysis | stack promotion, alloca, caller-slot returns, `@Value T`, `@Size` inline, autobox, `--warn-heap` | heap allocations on v1's test programs back to v1's level |
| 6 | Exceptions + cycles + threads | checked propagation; cycle lint, `@Weak` / `WeakRef<T>`; atomic mode | exception programs leak-free; thread programs under atomic RC |
| 7 | Arena + C interop | `arena { }`; `kotlinx.cinterop` API + `.def` binding generator; port of the SDL3 module | v1's interop tests ported and passing |

Phases 1 to 3 are the bulk of the effort; Phase 2 carries the most risk. Phases 4 and 5 are pure optimizations
over a model that is already correct, so each lands rule by rule with numbers attached.

**Status (2026-11-11).** Phases 0–2 are complete: the eleven programs under `integration/`
match the JVM, and every C run ends with all objects freed (`-DKTC2_LEAK_CHECK`).

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

Phase 3 so far (`integration/generics/`, `integration/stdlib/`), 14 programs in total:
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
- **Std library from its own sources.** 44 files of the Kotlin stdlib sources jar (unpacked
  by Gradle, listed in `resources/ktc2/stdlib/files.txt`) are compiled, unmodified, with
  every program: scope functions, the whole `kotlin.collections` package (lists, maps, sets,
  their extension functions, `ArrayDeque`, sequences' basics), `Pair` / `Triple`,
  preconditions. Only what a program reaches is translated to C.

How the stdlib is brought in:
- **Multiplatform mode.** The stdlib files are the *common* source set; ktc2's platform
  layer (`resources/ktc2/stdlib/Ktc2*.kt`, about 1,300 lines) is the platform one and
  provides an `actual` for each of their 59 `expect` declarations. K2 checks every one
  against the real API. The layer holds `ArrayList`, the four hash collections (one
  insertion-ordered open-addressing implementation), the `AbstractMutable*` bases,
  `StringBuilder`, `Appendable`, sorting, and the builder functions.
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
- **Cost.** The stdlib sources add about three seconds to every compile (K2 analyses them
  each time). Caching that work is the obvious next optimization.

Not ported yet: `kotlin.text` string functions (`_Strings.kt`, `Strings.kt`), the array
extension functions (`_Arrays.kt`), `collections/Arrays.kt`, comparators built from lambdas
(`compareBy`, `sortedBy` — SAM conversion), ranges as objects, `Regex`, `Random`.
`throw` reports the exception and exits (Phase 6 makes it catchable).

Known gaps, deliberately left for later phases: failures that Kotlin reports as exceptions
(null `!!`, bad cast, index out of bounds, `/ by zero`, `throw`) print the JVM-style message
and exit until Phase 6; `object` and enum initializers run at startup rather than on first
use; reference fields in `@Struct` classes, `Enum.entries`, inner classes, a function value
whose static type differs in variance from the lambda's own, and an inline lambda used
inside another lambda (`crossinline`) are not supported yet. Nothing in the ARC pass is
optimized (Phase 4).

**Relation to v1.** v1 integration tests that exercise plain behavior are ported as plain
Kotlin into `ktc2/integration/` and gain the JVM oracle. Tests of the manual model (`ArenaTest`,
`DisposeTest`, allocWith / copyWith cases) stay with v1. What to do with v1 once ktc2 reaches
parity — freeze it, or keep it as the manual-memory dialect — does not need deciding now.

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
