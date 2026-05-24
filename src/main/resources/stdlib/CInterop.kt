@file:DocumentationOnly
package ktc.std

/**
C interop for KotlinToC.

All C declarations are accessed through the synthetic `c` object. No import is needed.
Use `@file:cInclude("header.h")` to add an `#include <header.h>` to every generated file
in the same package.

==========
Primitives
==========

Kotlin primitive types map directly to C types:

    Kotlin    C (ktc alias)
    Boolean   ktc_Bool  (int, 0/false 1/true)
    Byte      ktc_Byte  (int8_t)
    UByte     ktc_UByte (uint8_t)
    Short     ktc_Short (int16_t)
    UShort    ktc_UShort(uint16_t)
    Int       ktc_Int   (int32_t)
    UInt      ktc_UInt  (uint32_t)
    Long      ktc_Long  (int64_t)
    ULong     ktc_ULong (uint64_t)
    Float     ktc_Float (float)
    Double    ktc_Double(double)
    Char      ktc_Char  (char)

==========
Pointers
==========

`@Ptr T` maps to `T*` in C.  Use on parameter types, return types, and val/var types.
Nullable `@Ptr T?` maps to `T*` and compares against NULL instead of using Optional.

    fun read(buf: @Ptr ByteArray, len: Int): Int     →  int32_t read(int8_t* buf, int32_t len)
    fun next(): @Ptr Node?                           →  sdl3_Node* next(void)
    val fData: @Ptr Array<Float>                     →  float* fData;

`c.addr(x)` produces `&x` (address-of).
`c.NULL` is the null pointer constant.

==========
C calls
==========

Call any C function via `c.functionName(args)`:

    c.SDL_Init(c.SDL_INIT_VIDEO)
    val vHandle: @Ptr c.SDL_Window = c.SDL_CreateWindow(title.ptr, w, h, flags)

Struct types are accessed as `c.SDL_FRect`, `c.SDL_Color`, etc.
Struct instances: `c.SDL_FRect(x, y, w, h)` — a compound literal.
Field access on a C struct value: `.field`.
Pass a struct by pointer: `c.addr(myRect)`.

==========
Strings
==========

`String.ptr` converts a KTC string to `const char*` (a raw C string pointer).
Use when passing Kotlin strings to C functions expecting `const char*`.

    c.SDL_SetWindowTitle(window.handle, title.ptr)

==========
Sized arrays
==========

`@Size(N) Array<T>` maps to `T[N]` as a fixed-size stack array (no length companion).

    data class Header(val fDigest: @Size(32) ByteArray)

==========
File-level annotations
==========

    @file:cInclude("SDL3/SDL.h")        →  #include <SDL3/SDL.h> in every .c of this package
    @file:cIncludeRelative("util.h")    →  #include "util.h"
    @file:DocumentationOnly             →  this file is type-info only; no .c output is emitted
*/
object CInterop
