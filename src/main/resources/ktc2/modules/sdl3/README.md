# ktc.sdl3

SDL3 for ktc2: the raw C API as package `sdl3` (generated from `sdl3.def`), and on top of it
the small Kotlin API of package `ktc.sdl3` - `SDL3.Window`, `SDL3.Renderer`, shapes, textures,
events, input and a game loop.

A program uses it by naming it in its `module.ktc2.toml`:

```toml
name         = "game"
dependencies = ["sdl3"]
```

```
ktc2 path/to/game --run
```

Nothing has to be installed. The module fetches the sources of SDL3 from GitHub the first
time (into `~/.ktc2/cache`): the bindings are made from their headers, and `module.cmake`
builds the library from them, statically. When CMake finds an SDL3 on the system it uses that
one instead; `-DKTC2_SDL3_FROM_SOURCE=ON` in `KTC2_CMAKE_ARGS` insists on the sources.

A program that depends on the module is therefore built with CMake, which needs a generator
it can use: Visual Studio, or Ninja or make with gcc or clang.

`examples/sdl3-demo` opens a window and draws for a second; `examples/showcase` is a window
with buttons.

The constant tables in `SDL3.kt` (between `// <constants>` and `// </constants>`) name SDL's
constants in groups (`SDL3.Event.Quit`, `SDL3.Scancode.Escape`). A constant SDL adds later is
there in package `sdl3` under its C name, and can be given a name here by adding a line.
