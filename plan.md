# KTC plan

Working backlog of proposed improvements across three axes:

- **Kotlin features** — Kotlin source-language compatibility gaps.
- **Codegen** — quality, size, and speed of the emitted C.
- **CLI / tooling** — transpiler flags and the `run_tests.py` harness.

Each item has a size estimate: **S** ≈ one commit, **M** ≈ a few commits, **L** ≈ multi-session refactor.

## Kotlin features

- [ ] Can you make that enum can be @SimpleEnum and so be just simple enum like now and that other enum can be more complexe and be class (like in kotlin) with real instance that have full Any and other stuff ?

## Codegen

- [ ] **Auto-`inline` for trivial functions** (S–M) — Tiny single-expression functions (1–2 stmts, no recursion, no overloads) could be auto-inlined to skip the call. Worth measuring on real code before committing to it — `-flto` may already cover this.

## CLI / tooling

(empty — all queued items shipped.)
