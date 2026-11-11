# K2 frontend spike — results

2026-11-11, Kotlin 2.3.20 (`kotlin-compiler-embeddable`), JDK 21, Windows 11.

**Question:** can ktc2 take its input from the real Kotlin compiler (K2) instead of the v1
hand-written Lexer / Parser, and is what K2 hands over usable for a C backend?

**Method:** `Spike.kt` runs `K2JVMCompiler` in-process with an `IrGenerationExtension`
(`SpikePlugin.kt`) that receives the module IR right after FIR→IR, before any JVM lowering.
Input: `samples/Sample.kt` — classes, interface, open class + override, generics, `when` with
smart casts, a lambda capturing and writing a local, `for`, safe call + elvis, stdlib
collections, and the `ktc2.` annotations.

```bash
./gradlew :ktc2:k2spike:run --args="samples/Sample.kt samples/StdOverride.kt"
```

Outputs land in `build/spike/`: `ir.txt` (full IR), `ir.kt.txt` (Kotlin-like IR), `report.txt`.

## Findings

| # | Question | Result |
|---|---|---|
| 1 | Does it run embedded, without a Kotlin install? | Yes. ~130 lines of glue, worked on the first run. |
| 2 | Is the IR fully typed and resolved? | Yes. Every call names its resolved target, every local has its inferred type, generic type arguments are explicit (`firstOr<String>(...)`). |
| 3 | How much is already desugared? | Smart casts are explicit `IMPLICIT_CAST` nodes; `?.` and `?:` are `when` + temporaries; `for` is an `iterator()/hasNext()/next()` loop; `+=` on a property is get + set; `if` / `when` are one node. See `main` in `ir.kt.txt`. |
| 4 | Do `ktc2.` type annotations survive? | Yes, with arguments, despite `SOURCE` retention: `@[Value] sample.Vec2`, `@[Size(inN = 64)] kotlin.ByteArray`, and inside type arguments (`Array<@Value Particle>`). |
| 5 | Lambdas and capture? | Lambdas are local functions (`IrFunctionExpression`); a captured variable is an ordinary reference to the outer `IrVariable`, so the free-variable set is a simple walk. No `capture(...)` needed. |
| 6 | Are stdlib bodies available? | **No.** Every library callee (`map`, `println`, `mutableListOf`, `sum`, `until`, `List.get`…) arrives as a signature with no body, including `inline` ones. |
| 7 | Can ktc2 supply those bodies as Kotlin source? | **Yes.** With `-Xallow-kotlin-package`, a source file declaring `kotlin.collections.map` shadows the jar's version: the call resolves to the source function and its body is in the IR (`samples/StdOverride.kt`). User code stays plain Kotlin, no special imports. |
| 8 | JVM oracle? | Free. The same compiler run also emits `.class` files; running them printed the expected output. One invocation yields both the IR for C and the reference program. |
| 9 | Cost | Compiler jar is 59.7 MB (v1's whole JAR is far smaller). ~3.0 s per in-process compile of this 80-line file, cold JVM, including the JVM codegen the spike does not need. |

## What this means for a C backend

- The work v1 does in `TypeInfer*.kt`, overload resolution, smart-cast tracking, inline-lambda
  typing and most of the `E0xx` type errors is done by K2, with Kotlin's exact rules.
- The `for`-over-range desugaring is the unoptimized form; ktc2 must recognize
  `until(...).iterator()` loops and emit a counted `for` (the JVM backend does this in a
  lowering that runs after the extension point).
- The stdlib is still ktc2's job, but it can be written — or copied from the Apache-2 Kotlin
  stdlib sources — in the real `kotlin.*` packages, and only for what programs use.
- Primitive operators (`Int.plus`, `kotlin.internal.ir.less`, `EQEQ`) and `Array` / `String`
  members are intrinsics the emitter maps directly to C.

## Not tested

- C interop surface: under K2, `C.fn()` / `c.Type` must be real Kotlin declarations
  (generated or hand-written `external` stubs); v1's "any name under `C.` is accepted" has no
  equivalent. This is extra work that option A does not have.
- ktc2-specific diagnostics (cycle lint, `.copy()` rule): K2 has FIR checker extensions for
  this, not exercised here.
- Compile time on a large program, and whether skipping JVM codegen (frontend-only pipeline)
  is practical through public entry points.
- API stability: `IrGenerationExtension` and the IR tree are experimental; the spike needed an
  opt-in (`UnsafeDuringIrConstructionAPI`). Expect breakage on Kotlin upgrades, so the Kotlin
  version must be pinned.

Note: `report.txt` lists a few builtins (`kotlin.internal.ir.less`, `IntIterator.hasNext`)
under "declared in the module"; that is the spike's crude origin test, not a K2 behavior.

## Verdict

K2 is viable and removes the largest risk in the plan (Phase 1's hand-written
resolve/typecheck pass and the long tail of Kotlin compatibility). The price is a 60 MB
build-time dependency, seconds instead of milliseconds per run, a pinned Kotlin version, and
real declarations for C interop. None of that reaches the generated C or its runtime.
