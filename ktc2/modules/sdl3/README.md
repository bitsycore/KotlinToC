# ktc.sdl3

SDL3 for ktc2: the raw C API as package `sdl3` (generated from `sdl3.def`), and on top of it
the small Kotlin API of package `ktc.sdl3` — `SDL3.Window`, `SDL3.Renderer`, shapes, textures,
events, input and a game loop. It is the ktc2 port of the v1 module of the same name.

```
ktc2 game.kt ktc2/modules/sdl3 -o out -copt -I<sdl>/include -lopt -L<sdl>/lib
```

A directory on the command line stands for the `.kt` and `.def` files in it. `out/ktc2_build.txt`
then lists the C files (`c=`), the compiler options (`cflag=`) and the linker options
(`ldflag=`) to build the program with.

`example/Demo.kt` opens a window and draws for a second.

The constant tables in `SDL3.kt` (between `// <constants>` and `// </constants>`) are written
by `ktc2/tools/gen_sdl3_constants.py` from the generated bindings, so they only name what the
installed SDL3 has.
