# KTC plan

Working backlog of proposed improvements across three axes:

- **Kotlin features** — Kotlin source-language compatibility gaps.
- **Codegen** — quality, size, and speed of the emitted C.
- **CLI / tooling** — transpiler flags and the `run_tests.py` harness.

Each item has a size estimate: **S** ≈ one commit, **M** ≈ a few commits, **L** ≈ multi-session refactor.

## Kotlin features

### Full Kotlin-style enums (L)

Today every enum is the simple form: a C `enum` of integer tags, plus `names[]` / `values[]` / `valueOf()`. No constructor params, no body, no instance methods. Goal: keep that path opt-in as `@SimpleEnum`, default to a Kotlin-faithful form that supports:

1. **Per-enum constructor params** — `enum class Op(val sym: String) { PLUS("+"), MINUS("-") }`
2. **Body methods/properties** — methods on the enum class itself, callable on entries
3. **Standard properties** — `name: String`, `ordinal: Int` on every entry
4. **Any methods** — `toString()` returning the entry name (overridable), `hashCode()`, `equals()`
5. **Per-entry method overrides** — `PLUS { override fun apply(a, b) = a + b }` (stretch)

**Why this is L, not M:** Every site that touches an enum value would change. Today `op == Op.PLUS` lowers to integer equality (`op == 0`). With ctor params, `Op` must become a struct (or a pointer to a singleton), and equality switches to ordinal comparison or pointer equality. The places that need updating:

- `parser/Parser.parseEnumDecl` — currently skips body content with `while (!at(RBRACE)) advance()`. Must parse: optional `(ctorParams)` after name, `ENTRY(args)` per entry, optional `; <members>` body block.
- `ast/Ast.EnumDecl` — extend to `name + ctorParams + List<EnumEntry(name, args, body?)> + members`.
- `codegen/emit/Enum.kt` — entirely rewrite. Two paths:
  - **Simple path** (`@SimpleEnum`, or implicit when no ctor/body): emit the current C enum.
  - **Full path**: emit `typedef struct { <fields>, ktc_Int ordinal; ktc_String name; } Op;` plus `extern const Op Op_PLUS;` per entry plus initialized `.c` definitions. Method dispatch becomes a regular function on `Op self`.
- **Type inference** — `Op.PLUS` is currently a value of type `Op` (an int). After: still type `Op`, but `Op` is now a struct, not a primitive. Anywhere that assumes enum-is-int (printf format, switch statement, hash) needs updating.
- **`values()` / `valueOf()`** — already emitted; need to switch from `Op[]` of ints to `Op[]` of structs.
- **`when (op)`** — currently lowers via int equality. Must lower via ordinal compare (or pointer compare if we make entries `Ref<Op>` singletons).
- **Generic instantiation, hashCode/equals, toString integration with `${op}` and `print(op)`** — all need updating.

**Recommended phasing** (each phase ships independently):

- **Phase 0** (S): Add `@SimpleEnum` as a no-op annotation that asserts the enum has no ctor/body; document the boundary. Existing enums stay simple.
- **Phase 1** (M): Parse ctor params + per-entry args. Without body. Emit struct form for non-`@SimpleEnum` enums with ctor params. Update all enum-equality and `when` sites to dispatch on the struct (probably via `.ordinal`). `name` and `ordinal` properties land here.
- **Phase 2** (M): Parse and emit body methods/properties. Methods dispatch via plain function call on the struct. `toString` integrates with string templates.
- **Phase 3** (M): Per-entry overrides via per-entry vtable pointer (essentially making each entry a tiny instance of a class).

Risk: Phase 1's "every enum-equality site updates" is the hard part — must avoid silent miscompiles where some int paths slip through.

### Other

(none queued)

## Codegen

(none queued — auto-inline was dropped: speculative, and `-O2`/`-flto` already inline trivial fns aggressively. Re-add with measurements if needed.)

## CLI / tooling

(empty — all queued items shipped.)
