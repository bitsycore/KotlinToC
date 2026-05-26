@file:DocumentationOnly
package ktc

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

`Ref<T>` maps to `T*` in C.  Use on parameter types, return types, and val/var types.
Nullable `Ref<T?>` maps to `T*` and compares against NULL instead of using Optional.

    fun read(buf: Ref<ByteArray>, len: Int): Int     →  int32_t read(int8_t* buf, int32_t len)
    fun next(): Ref<Node?>                           →  sdl3_Node* next(void)
    val fData: Ref<Array<Float>>                     →  float* fData;

`c.addr(x)` produces `&x` (address-of).
`c.NULL` is the null pointer constant.

==========
C calls
==========

Call any C function via `c.functionName(args)`:

    c.SDL_Init(c.SDL_INIT_VIDEO)
    val vHandle: Ref<c.SDL_Window> = c.SDL_CreateWindow(title.ptr, w, h, flags)

Struct types are accessed as `c.SDL_FRect`, `c.SDL_Color`, etc.
Struct instances: `c.SDL_FRect(x, y, w, h)` — a compound literal.
Field access on a C struct value: `.field`.
Pass a struct by pointer: `c.addr(myRect)`.

==============
Initialization
==============

These forms control how variables and struct values are initialized:

    Syntax                    C output               Context
    c.zeroed()                {0}                    var decl only (type inferred from LHS)
    c.init()                  (bare declaration)     var decl only, no initializer
    c.SDL_FRect()             (SDL_FRect){0}         works everywhere
    c.SDL_FRect(x, y, w, h)  (SDL_FRect){x, y, w, h}  works everywhere
    c.init(x, y, w, h)       {x, y, w, h}           var decl only (type inferred from LHS)

`c.zeroed()` zero-initializes using the LHS type — equivalent to `= {0}` in C.
`c.init()` emits a bare declaration with NO initializer (uninitialized memory).
`c.init(x, y, z)` emits a brace-initializer list `{x, y, z}` using the LHS type.
`c.SDL_FRect(args)` is a typed compound literal and works in any expression context.
`c.SDL_FRect()` with no args is a zero compound literal `(SDL_FRect){0}`.

Examples:

    var rect: c.SDL_FRect = c.zeroed()     →  SDL_FRect rect = {0};
    var buf: c.SDL_FRect = c.init()        →  SDL_FRect buf;          // uninitialized
    var p: c.SDL_FPoint = c.init(1f, 2f)   →  SDL_FPoint p = {1, 2};
    val r = c.SDL_FRect(0f, 0f, 10f, 10f) →  SDL_FRect r = (SDL_FRect){0, 0, 10, 10};
    val z = c.SDL_FRect()                  →  SDL_FRect z = (SDL_FRect){0};

==========
Strings
==========

`String.ptr` converts a KTC string to `const char*` (a raw C string pointer).
Use when passing Kotlin strings to C functions expecting `const char*`.

    c.SDL_SetWindowTitle(window.handle, title.ptr)

==========
File-level annotations
==========

    @file:cInclude("SDL3/SDL.h")        →  #include <SDL3/SDL.h> in every .c of this package
    @file:cIncludeRelative("util.h")    →  #include "util.h"
*/
object c

inline fun <T> c.zeroed(vararg params: Any): T
inline fun <T> c.init(vararg params: Any): T
inline fun <T> c.addr(value: T): Ref<T>