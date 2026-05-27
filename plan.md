# KTC plan

Working backlog of proposed improvements across three axes:

- **Kotlin features** — Kotlin source-language compatibility gaps.
- **Codegen** — quality, size, and speed of the emitted C.
- **CLI / tooling** — transpiler flags and the `run_tests.py` harness.

Each item has a size estimate: **S** ≈ one commit, **M** ≈ a few commits, **L** ≈ multi-session refactor.

## Kotlin features

### Full Kotlin-style enums — Phase 3 (per-entry overrides) remaining (M)

Phases 0–2 of the full-enum roadmap shipped: enums with primary ctor params, per-entry args, body methods, and the `.name` / `.ordinal` / `==` / `when` lowering are all working. Default is now the struct form; `@SimpleEnum` keeps the zero-overhead C-int form (asserted at parse time to have no ctor/body).

Remaining: **Phase 3** — per-entry method overrides (`PLUS { override fun apply(a, b) = a + b }`). Approach: extend each entry's struct singleton with a vtable pointer, populate per entry, dispatch `op.method(args)` through the vtable when an override exists.

### Other

(none queued)

## Codegen

(none queued)

## CLI / tooling

(none queued)
