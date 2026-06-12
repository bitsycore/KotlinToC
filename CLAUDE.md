# KotlinToC (KTC)

Source-to-source transpiler: **Kotlin → C11**. No runtime, no GC, no deps beyond libc.
Targets embedded/game/systems code.

## Build / Run / Test
- Build JAR: `./gradlew jar` → `build/libs/KotlinToC-1.0-SNAPSHOT.jar`
- Transpile: `java -jar <jar> file.kt -o out/ --name app`
- Unit tests: `./gradlew test` (fast; `TranspilerTestBase` transpiles snippets and asserts on emitted C)
- Full suite (builds JAR + transpile/compile/run integration tests, parallel):
  `python3 run_tests.py`  | single: `python3 run_tests.py --run TestName`  | skip unit: `--skip unit`
- Integration tests live in `integration/<Category>/<Name>/*.kt` (each compiled with gcc/cmake and executed).
- Each test has a `module.ktc.toml` for config (dependencies, executable name, main entry, etc.).

## Architecture
- Pipeline: `Lexer` → `Parser` → `CCodeGen.generate()` (`src/main/kotlin`).
- Codegen split under `codegen/`: `expression/`, `statement/`, `emit/` (class/enum/iface/object), type inference in `TypeInfer*.kt`.
- Built-in call dispatch: `expression/CallBuiltins.kt` (arrayOf/StringBuffer), `CallAlloc.kt` (allocWith/ctors), `CallMethodBuiltins.kt` (resizeWith/copyWith/get/set).
- Std library is Kotlin source in resources: `src/main/resources/ktc/` (core: Any, Arrays, Allocator, Arena…) and `src/main/resources/modules/ktc.std/` (Collections, Map, Random…). These are transpiled, not hand-written C.
- Generics via monomorphization; interfaces via fat-pointer vtables `{obj, vt}`.

## Memory model
- Values are by-value on the stack by default. `Ref<T>` = non-null reference (`T*` in C), `Ref<T?>` = nullable reference (can be NULL).
- `Ref<T>?` is accepted but warns — prefer `Ref<T?>` (nullability belongs on the inner type).
- Heap allocation is allocator-based (no GC):
  - `T(ctorArgs...).allocWith(Heap)` → `Ref<T>` (fused alloc + ctor, no stack temp)
  - `Array<T>(n).allocWith(Heap)` / `RawArray<T>(n).allocWith(Heap)`
  - `arr.resizeWith(allocator, n)`, `arr.copyWith(allocator)`, `dataClass.copyWith(...)`
  - `Array<T>.fill(value)` / `RawArray<T>.fill(count, value)` → memset when byte-sized or zero-literal, else loop
  - `Array<T>.asRaw()` → `RawArray<T>` (bare data ptr); `RawArray<T>.asArray(n)` → `Ref<Array<T>>` (VarArr) — both alias, no copy
  - `allocator.freeMem(ref)` (e.g. `Heap.freeMem(p)`); `Heap` is the default `object : Allocator` and is tagged `@RequireFree` (the marker that drives the W018 "discarded alloc" lint).
  - `Arena` is a bump allocator implementing `Allocator`. Arena is intentionally NOT `@RequireFree` — its allocations are bulk-freed by `reset()`, so discarding a per-alloc pointer is legitimate. Custom allocators that need explicit `freeMem` per allocation should be annotated `@RequireFree`.
- Note: `allocWith` returns a non-null `Ref<T>`. To null-check, declare the var `Ref<T?>` explicitly.
- `RawArray<T>` is inherently a reference type (always `T*` in C) — no `Ref<>` wrapper needed.

## Reference access
- Read through a `Ref<T>`: `p.refValue` → `*p` (property-style access).
- Write through a `Ref<T>`: `p.refValue = x` → `*p = x;`.
- `p?.refValue = x` is supported and expands to `if (p) *p = x;`.
- Take a reference: `val.asRef()` → `&val` (returns `Ref<T>`).
- Crossing the `Ref<T>` ↔ `T` boundary at var-decl/assignment requires explicit `.asRef()` or `.refValue`. Implicit conversions are rejected with a fix-it pointing at the right form. Null literals, interface receivers (already wrapped in `ktc_IfacePtr`), and array-element pointers are exempt.

## String and Array return safety
`String` is `{ const ktc_Char* ptr; ktc_Int len; }` (**OWNED + NUL-terminated**; `len` excludes the `\0`); `Array<T>` is `{ T* ptr; ktc_Int len; }` (a slice-view). **Both pass by value as a struct copy — ptr+len duplicated, backing shared** (~16-byte copy, no allocation). For an independent copy use `.copy()` (String / `@Size` / class) or `.copyOf(n)` (Array).

Because the backing lives in the producing frame, **returning a freshly-built one from a non-inline function dangles**:
- **`String`**: bare returns refused unless the function is `inline`, named `toString`, or its body returns only `StrLit`s. Otherwise return `Ref<String>` (the heap form from `.copyWith(alloc)` / `.allocWith(alloc)` — a `ktc_String*` to one block holding header+bytes, released whole by `freeMem`) or `@Size(N) String`. `String?` returns allowed.
- **`Array<T>` / `IntArray` / etc.**: bare returns refused unless `inline`. Otherwise `Ref<Array<T>>` or `@Size(N) Array<T>`.

`.asRef()` yields a `Ref<>` aliasing the value (frame-bound — returning it is E120-refused; use `.copyWith`/`.allocWith` to escape). Note: `Ref<String>` is a real `ktc_String*` (header+bytes block), unlike `Ref<Array<T>>` which is the bare VarArr value — because `RawArray<String>` and `Ref<String>` would otherwise collide on `Ptr(String)`.

## Strings
- `String` is an OWNED, NUL-terminated value struct `{ const ktc_Char* ptr; ktc_Int len; }` (`len` excludes the `\0`). Literals are interned into a named `.rodata` pool. It behaves like a **read-only `Array<T>`**: `.copy()` duplicates (explicit pass-by-value), `.asRef()` → `Ref<String>`, `.copyWith(alloc)` / `.allocWith(alloc)` → heap `Ref<String>` (freed with `freeMem`).
- `s.length`, `s[i]`, `s.startsWith/endsWith/contains`, `s == t` lower to `memcmp` / `ktc_core_string_*` (`==` uses `ktc_core_string_eq`). **`s.cPtr`** is the raw `const char*` for C interop (`RawArray<Char>`); **`.ptr` is no longer available on String/Array — use `.cPtr` (E055)**.
- `s.substring(a, b)` now **COPIES** (NUL-terminated owned String), as do the inline slice/trim/prefix extensions (`take`/`drop`/`trim`/`removePrefix`/`substringBefore`…) — frame-bound, so returning one needs `inline` or `Ref<String>`. `s + t` concat ≡ the template `"$s$t"` (alloca, frame-scoped).
- `value.toStringMaxLen()` → static upper bound on its `toString()` length (compile-time constant; refused when not statically bounded); `value.toStringComputeLen()` → the runtime length via a counting-only StrBuf pass.
- `templateOf("…")` → a frame-local, compile-time-only Template handle: `.maxLen` / `.computeLen()` / `.toString()` / `.toString(sb)` size or build it without repeating the template text. `sb."…"` renders a template into a StringBuffer and returns the String.
- `s.toInt()` / `toLong()` / `toFloat()` / `toDouble()` parse on demand and **throw NumberFormatException** on invalid input (Kotlin semantics); `*OrNull()` variants return `Int?` etc.
- `StringBuffer` (mutable: `{ ktc_Char* ptr; ktc_Int len; ktc_Int cap; }`) requires a caller-provided backing buffer: `StringBuffer(charArray.cPtr, 0)`. With `ptr=NULL` it runs in counting-only mode (no writes, just `len` accumulates) so you can size a real buffer in a second pass.

## Arrays
- `Array<T>` is a `VarArr` struct `{ T* ptr; ktc_Int len; }` — variable-length, header carries the count. `arr.size` reads `.len`, `arr[i]` reads `.ptr[i]`.
- `RawArray<T>` is bare `T*` — a reference type with no length, no `.size`. Use when you already track the length elsewhere (FFI, fixed-size protocols). **Not bounds-checked** — there's no length to check against.
- `@Size(N) Array<T>` / `@Size(N) IntArray` is a fixed-size struct `{ T arr[N]; }` — lives entirely on the caller's stack, no heap. Pass by value. Assigning a larger source warns and inserts an implicit `.copyOf(N)` truncation (data loss).
- `Array<T>.asRaw()` → `RawArray<T>` (alias, no copy); `RawArray<T>.asArray(n)` → `Ref<Array<T>>` (alias plus length tag).
- Functions can return `@Size(N) T` arrays by value (struct return), but cannot return bare `Array<T>` or `RawArray<T>` — the underlying buffer would dangle. Use `Ref<Array<T>>` (heap-backed) or `@Size(N)` (stack struct).

## Runtime safety checks
Two safety nets default-ON, each with an opt-out flag. They emit calls to `ktc_core_*_check` helpers. On violation they **throw** — `IndexOutOfBoundsException` for bounds, `NullPointerException` for null-deref — so `try/catch` handles them like Kotlin (the generated `main()` registers the typeIds via `ktc_core_exc_register`; without a generated main, e.g. library builds, they fall back to printing a Kotlin-style stack trace and exiting).

**Bounds check** — every `arr[i]` / `s[i]` for `Array<T>`, `String`, or `@Size(N)` routes through `ktc_core_bounds_check(file, line, idx, len)`. Static-warning sibling fires at transpile time when the index is a literal AND length is statically known (`@Size(N)`, string literals). Opt out with `--no-check-bounds`. `RawArray<T>` (bare `T*`) carries no length and is never bounds-checked.

**Null-deref check** — every `p.refValue` / `p.refValue = x` is preceded by `ktc_core_null_check(p, file, line)` which exits with `NullPointerException` if `p` is NULL. The static analysis already refuses bare `.refValue` on a statically-nullable `Ref<T?>` (forces `?.refValue`), so this catches the harder cases — allocator failure returning NULL, dangling pointer becomes NULL, etc. Opt out with `--no-check-null`.

Both flags are accepted as `--check-bounds` / `--check-null` too (no-op since default is already ON) for explicit-intent clarity.

## Class inheritance (open/abstract/sealed)
Class hierarchies compile down to the interface machinery via a whole-program AST pass (`codegen/InheritDesugar.kt`, run before codegen — also see `Main.kt` and `TranspilerTestBase`). No new dispatch mechanism: data + code inheritance by copying, polymorphism by the existing fat tagged-union values.

- `open class P` / `abstract class P` / `sealed class P` → interface `P` (method signatures + stored props as properties). `open` classes additionally get a hidden concrete **`P$Impl`**, and `P(args)` ctor calls rewrite to it; abstract/sealed instantiation is refused. Type references to `P` (vars, params, returns, `is`, `as`, `catch`) lower through the interface machinery untouched. **Generic parents work**: `class IntBox(v: Int) : Box<Int>(v)` substitutes T through every copied field/method (and `class MyBox<T> : Box<T>(v)` keeps the child generic); the parent view monomorphizes like any generic interface.
- `class C(x: Int) : P(args)` implements interface `P` and is **augmented**: P's stored ctor props become C fields initialized from the super-call args (positional then named, with P's param defaults filling the rest), P's body props + concrete method bodies (override-marked) + init blocks are copied in. Chains flatten transitively. Forwarding (non-val) parent ctor params are substituted by the child's super-arg expressions inside copied initializers/init blocks (an arg referenced N times evaluates N times — keep super-args simple).
- Method copying is monomorphization-style — virtual dispatch is correct even for parent bodies calling open methods (copied bodies resolve against the child). `override fun` is itself open for further overrides (Kotlin semantics); overriding a non-open method is refused.
- `class C : P` (no parens) still means "implement the interface directly" — provide `override val` storage yourself. Both styles coexist.
- Sealed classes get `when` exhaustiveness like sealed interfaces. `super.method()` calls lower to private level-qualified copies of the parent body (`work$super$Animal`) so multi-level chains stay correct; `super.prop` collapses to `this.prop`. Extending a final class and redeclaring an inherited stored prop are refused with clear errors.
- Inherited `var` props are writable through parent-typed (fat) values too — `var` props get vtable setter slots (`name_set`) alongside the getters (this also applies to `var` properties declared directly in interfaces). `val` props stay read-only through the parent type; compound assignment (`+=`) through the parent type needs the explicit `x.p = x.p + v` form. Remember fat values are COPIES — mutating one doesn't change the original it was built from.

## Exceptions (try/catch/finally/throw)
Lightweight setjmp/longjmp exceptions — no unwinder, no heap per throw. Runtime in `ktc/core/ktc_core_exception.{h,c}` (KTC_TRY macro family); codegen in `codegen/statement/Try.kt`; stdlib hierarchy in `resources/ktc/Throwable.kt`.

- **Hierarchy is a real class hierarchy** (via class inheritance, mirroring Kotlin): `open class Throwable(val message: String = "")` ← `Exception` ← `RuntimeException` ← `IllegalState/IllegalArgument/IndexOutOfBounds/NoSuchElement/UnsupportedOperation`; `NumberFormatException : IllegalArgumentException`; `Throwable` ← `Error` ← `NotImplementedError` (so `catch (e: Exception)` does not swallow Errors, only `catch (e: Throwable)` does). User exceptions extend any open class: `class ParseError(message: String, val pos: Int) : Exception(message)` — or implement the (desugared) interface directly with `override val message`. A catch on a supertype catches the whole subtree (`catch (e: RuntimeException)` catches IllegalStateException). `throw Exception("x")` instantiates directly. **`message` must be stored** (E130 if computed) — the runtime patches it by `offsetof` when relocating.
- **Result<T> + runCatching:** `Result.Failure<T>(exception: Throwable)` carries the Throwable (no more Int codes); `runCatching { ... }` captures Success/Failure; extensions: `getOrNull`, `exceptionOrNull`, `getOrDefault`, `getOrThrow` (rethrows the captured exception), `isSuccess`/`isFailure`. `Result` is `@MustUseReturnValue` — discarding one in statement position warns (W036, `-Wno-unused-result`); annotate your own funs/types with `@MustUseReturnValue`, opt single funs out with `@IgnorableReturnValue`. `Result<Unit>` works: in value positions (generic slots, fields, params) Unit lowers to the one-byte `ktc_Unit` value (`KTC_UNIT`); plain Unit-returning functions stay C `void`, and Unit-typed arguments are evaluated for side effects with the unit constant passed instead. A `Nothing`-typed lambda binds T as Unit. Binding a Unit result (`val u = save()`) is accepted Kotlin-style — `u` becomes a real unit value — but warns (W034, `-Wno-unit-binding`).
- **Stdlib throws Kotlin-style:** `error()` → IllegalStateException, `check`/`checkNotNull` → IllegalStateException, `require`/`requireNotNull` → IllegalArgumentException, `TODO()` → NotImplementedError, `s.toInt()/toLong()/toFloat()/toDouble()` → NumberFormatException (the `*OrNull` variants stay null-returning intrinsics; the throwing forms are inline extensions composing them), `Arena` overflow → IllegalStateException, `Random.nextInt` empty range → IllegalArgumentException, `List.first/last` on empty + `Iterator.next` past the end + `Map.getValue` missing key → NoSuchElementException, `List` get/set/removeAt are size-guarded → IndexOutOfBoundsException (`Map.get` stays the nullable accessor). **`fatalError(msg)`** keeps the old behavior — Kotlin-style stack trace with the caller's file:line + `exit(1)`, NOT catchable — and is what integration tests use for assert-style failures.
- **Mechanics:** each `try` pushes a stack `ktc_ExcFrame` (jmp_buf) onto a TLS frame stack. `throw` deep-copies the exception object + its message bytes into a **growable TLS arena** (one realloc'd block per thread, reused per throw, freed at thread exit), then longjmps to the innermost frame. A **used** catch binding is copied back off the arena onto the catching frame (object as a local, message alloca'd) so the catch body can rethrow / throw a new exception immediately; unused bindings skip the copy.
- **Matching is whole-program static:** `catch (e: Class)` compares the TLS typeId against that class; `catch (e: Iface)` ORs over every known implementor (transitive). Clauses match top-to-bottom; fully-shadowed clauses warn.
- **`throw e` rethrow** works on concrete and interface-typed bindings (interface throws switch on the concrete typeId for sizeof/offsetof). Throw from a catch propagates outward after the finally runs; throw from a finally replaces the in-flight exception and propagates immediately.
- **Uncaught** → Kotlin-style stack trace (`Uncaught exception <Type>: <message>` + throw-site file:line) and `exit(1)`.
- **Control-flow rules:** `return` inside try/catch pops the frame(s) and re-emits the finally bodies before returning (finallys innermost-out, then defers). `break`/`continue` crossing a try boundary → E132; `return` inside a finally → E133; `throw` of a non-Throwable → E130; invalid catch type → E131. Tailrec self-calls inside a try fall back to genuine recursion.
- **setjmp caveat handled:** functions lexically containing a `try` (including via inlined `inline fun` bodies) are emitted with `KTC_TRY_FN` (`optnone` / `optimize("-O0")`) so locals modified in the try and read in a catch aren't register-rolled-back by longjmp at -O2. MSVC has no per-function equivalent (documented in the header).
- **`?: throw` works** (`val v = find(k) ?: throw NotFound(k)`) — the left side is evaluated once, the throw lowers to an if-null statement prefix. **Limitations:** `try` as an expression and `throw` in other expression positions are not supported; exception fields other than `message` are NOT deep-copied — keep them value types (an extra String/Array/Ref field may dangle after the longjmp); `defer`s in frames unwound by a longjmp do not run.

## Operator overloading
- `operator fun` arities are enforced at transpile time: `plus`/`minus`/`times`/`div`/`rem` take 1 arg, unaries (`unaryPlus`/`unaryMinus`/`not`/`inc`/`dec`) take 0, `compareTo`/`contains`/`rangeTo` take 1, iterator protocol (`iterator`/`hasNext`/`next`) takes 0, `equals` takes 1.
- `get`/`set`/`invoke` have free arity (Kotlin allows multi-index access like `m[i, j]`).

## Lambdas and functional types
Two distinct lowerings, picked by context. Rule of thumb: **`inline fun` + lambda param → inlined (no capture); `fun` + lambda param → closure (capture); `inline fun` + `noinline` lambda param → closure (capture)** — `noinline` opts a single parameter out of inlining so the lambda becomes a real value the body can move around.
- **Inline lambdas (no closure):** a lambda passed to an `inline fun` (a non-`noinline` param) is expanded in place — `let`, `apply`, `with`, `run`, `also`, `takeIf`, `repeat` all work through this mechanism. No struct, no capture, no allocation.
- **Capture closures (functor model):** a lambda in *value position* — assigned to a function-typed `val`, passed to a **non-inline** function's function-typed parameter, or passed to a `noinline` parameter of an `inline fun` — lowers to its OWN per-lambda struct (the captured fields) plus a generated `R Closure_N_invoke(Closure_N* self, params…)`. The closure value IS that struct (real data, passed by value or `&`), NOT a fat `{fn,ctx}` pointer and NOT type-erased. Calling `f(x)` → `Closure_N_invoke(&f, x)`.
  - **Explicit capture, always:** KTC has no implicit capture. A closure body must `capture(...)` every enclosing local it reads, or it's a hard error (**E054**). `capture(x)` captures by value (a snapshot taken when the closure is built; an existing `Ref<T>` passes its pointer). `capture(x.asRef())` captures `&x` by reference — inside the closure `x` is a `Ref<T>`, read/written via `x.refValue`, reaching the original storage.
  - **Higher-order = monomorphization:** a non-inline function that receives a closure is specialized per closure type (the C++-template model) — each function-typed param is retyped to the functor struct (`F__Closure_N`), so `param(x)` in the body dispatches through `_invoke`. Handles multiple closure params, closure-typed-var args (`foo(g)`), and overloaded callees. Bare C function references (`::fn`) stay as plain function pointers (C interop / thread ABI).
  - **Type inference:** an un-annotated `val f = { x: Int -> … }` infers its functor type from the lambda's own typed params + body result; the `it` shorthand needs an expected type (a `val f: (Int)->Int = { it… }` annotation or a higher-order param).
  - **`noinline`:** within an `inline fun`, a `noinline` parameter is the one exception to inline expansion — its lambda argument is lowered to a closure functor (built at the call site, frame-bound) that the inlined body can call, assign to a local, and pass on; the other (inlined) params expand in place as usual.
- **Lifetime — frame-bound by default:** the functor struct is stack-local in the defining frame, so a closure must not outlive that frame. Returning a function type from a non-inline function is rejected (**E023**). For thread closures the spawning frame must `join()` before it returns (the context is `alloca`'d, no heap/free).
  - **Heap promotion — `Ref<(P) -> R>`:** `closure.copyWith(allocator)` heap-promotes a frame-bound functor to a **`Ref<(Int) -> Int>`** — the function type *is* the closure type (as in Kotlin); `Ref<>` marks the heap form, exactly like `Ref<Interface>` is itself a fat pointer (`ktc_IfacePtr`). One heap block holds the type-erased fat pointer (`ktc_Closure`) with its captures folded in, so `freeMem(g)` frees it in one call. It's nameable and **escapes**: return it (`fun make(): Ref<(Int)->Int> = …`), store it, hold it in a `List<Ref<(Int)->Int>>`. Callable like any function — `g(x)` casts the erased invoke to the signature. Refused for a closure that captured a stack local by reference (`capture(x.asRef())`) — its address would dangle. A **bare** `(Int) -> Int` returned from a non-inline function is a frame-bound value that would dangle and is refused (E023) — return `Ref<(Int) -> Int>`. (Heterogeneous reassignment / `obj.field(x)` direct-call on a stored closure are the remaining Phase-2 polish.)
- Not yet supported (higher-order): cross-package callees, receiver (extension/member) closure params, generic higher-order functions, and a closure passed by named/defaulted argument.
- A standalone lambda used as a bare expression (not assigned, not an argument) is still unsupported.

## Limitations vs Kotlin (intentional)
- No coroutines (`suspend`, `async`), no reflection (no `KClass`, `::class`), no `java.lang.*`.
- Class inheritance is fully supported (see "Class inheritance"): generic parents, `super.method()`, named super-args, writable inherited `var` props through parent-typed values.
- No `inner class` with implicit outer-instance capture. Nested classes are fine; they're emitted as `Outer$Inner`.
- No general property delegation (`by`). `by lazy { ... }` is supported for local and top-level vals (thread-safe via `ktc_thread_call_once` for top-level).
- No `inline value class`.
- No variance modifiers (`in`/`out`) enforced; generic substitution is purely positional.
- Exhaustiveness for `when` is warned on enums (each entry covered) and on sealed classes / sealed interfaces (each direct subclass covered via `is` branches). Adding an `else` branch silences the warning.
- Enums default to the full Kotlin-style form (struct with ctor-param fields + `.ordinal` + `.name`) and support primary ctor params, per-entry args, body methods, per-entry method overrides (`PLUS { override fun apply(...) = ... }` — dispatched through a per-entry vtable pointer), and `.values()` / `.valueOf()`. `@SimpleEnum` opts into the zero-overhead C-int form (asserted at parse time to have no ctor/body/overrides).
- `RawArray<T>` is never bounds-checked (no length carried). `Array<T>` / `String` / `@Size(N)` indexing IS checked by default — see "Bounds checking" above.
- `private` fields and methods are enforced across class boundaries (codegen error on access from outside). `internal` on classes and top-level functions is enforced per-package (cross-package access is rejected with E044).
- Direct self-recursive class layouts (`class Node(val next: Node)`) are rejected — must indirect through `Ref<Node>`, `Array<Node>`, or `RawArray<Node>`.

## Module system
- Project config is `module.ktc.toml` (replaces the old `deps.ktc.toml` + `config.ktc.toml`):
  ```toml
  name         = "My App"
  version      = "1.0"
  description  = "What this module does"
  authors      = ["name"]
  license      = "MIT"
  url          = "https://project-home"
  repository   = "https://github.com/user/repo.git#path/to/module"
  executable   = "myapp"          # output binary name
  main         = "pkg.funcName"   # explicit entry point (like --main)
  autoFindMain = true             # auto-detect single main function
  interactive  = true             # requires user interaction; --skip-interaction passed in test mode
  dependencies = ["ktc.std", "./../LocalMod", "https://github.com/user/repo.git#module"]
  ```
- Dependency sources: named (bundled JAR), relative (`./`), or URL (git clone to `~/.ktc/cache/`).
- Bundled modules: `src/main/resources/modules/<name>/` with `module.ktc.toml` + `.kt` files + optional `module.cmake`.

## Filesystem (ktc.std)
- `Path("a/b/c.kt")` — immutable string-wrapper with `name`, `nameWithoutExtension`, `extension`, `parent`, `isAbsolute`. Forward slashes everywhere; drive-letter `C:/...` recognized as absolute.
- `path.child("sub")` — extension method, joins a relative segment (Okio semantics: an absolute child replaces the receiver).
- `FileSystem` is `@Namespace` — `FileSystem.exists(p)`, `.delete(p)`, `.rename(a, b)`, `.createDirectory(p)`, `.metadata(p)` (returns sentinel `FileMetadata` with `size=-1` if missing), `.source(p)` / `.sink(p, append)` (returns `FileSource` / `FileSink`; check `.isOpen`), `.writeBytes(p, ptr, n)`, `.writeUtf8(p, s)`.
- `listDir(path) { name -> ... }` — top-level inline iterator (Win32 FindFirstFile / POSIX dirent under the hood). `.` and `..` are filtered out.
- C side lives in `src/main/resources/ktc/core/ktc_core_fs.{h,c}` — all paths are passed as `(bytes, len)` pairs and NUL-terminated in a stack buffer (KTC_FS_PATH_MAX = 4096).

## Conventions
- For Kotlin and KTC use standard kotlin conventions.
- Generated C: K&R style. Doc comments with `/** */`.
- Kotlin codegen source: section markers `// ====` + `// MARK: Name` between major groups; concise comments only when non-obvious.
- Commits: no Co-Authored-By / AI attribution.
