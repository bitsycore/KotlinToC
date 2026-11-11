# ktc.sdl3

SDL3 for ktc2: the raw C API as package `sdl3` (generated from `sdl3.def`), and on top of it
the small Kotlin API of package `ktc.sdl3` — `SDL3.Window`, `SDL3.Renderer`, shapes, textures,
events, input and a game loop. It is the ktc2 port of the v1 module of the same name.

A program uses it by naming it in its `module.ktc2.toml`:

```toml
name         = "game"
dependencies = ["sdl3"]
```

```
ktc2 path/to/game --run -copt -I<sdl>/include -lopt -L<sdl>/lib
```

The two options are only needed when SDL3 is not where the C compiler looks by itself; they
can also be written in the module file as `compilerOpts` / `linkerOpts`.
`ktc2/examples/sdl3-demo` opens a window and draws for a second.

The constant tables in `SDL3.kt` (between `// <constants>` and `// </constants>`) are written
by `ktc2/tools/gen_sdl3_constants.py` from the generated bindings, so they only name what the
installed SDL3 has.
