@file:DocumentationOnly
package ktc

/**
C interop for KotlinToC.

All C declarations are accessed through the synthetic `C` object (uppercase, to
avoid collisions with locals commonly named `c` — loop variables, chars, generic
type parameters, etc.). No import is needed.
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

`C.addr(x)` produces `&x` (address-of).
`C.NULL` is the null pointer constant.

==========
C calls
==========

Call any C function via `C.functionName(args)`:

    C.SDL_Init(C.SDL_INIT_VIDEO)
    val vHandle: Ref<C.SDL_Window> = C.SDL_CreateWindow(title.cPtr, w, h, flags)

Struct types are accessed as `C.SDL_FRect`, `C.SDL_Color`, etc.
Struct instances: `C.SDL_FRect(x, y, w, h)` — a compound literal.
Field access on a C struct value: `.field`.
Pass a struct by pointer: `C.addr(myRect)`.

==============
Initialization
==============

These forms control how variables and struct values are initialized:

    Syntax                    C output               Context
    C.zeroed()                {0}                    var decl only (type inferred from LHS)
    C.init()                  (bare declaration)     var decl only, no initializer
    C.SDL_FRect()             (SDL_FRect){0}         works everywhere
    C.SDL_FRect(x, y, w, h)  (SDL_FRect){x, y, w, h}  works everywhere
    C.init(x, y, w, h)       {x, y, w, h}           var decl only (type inferred from LHS)

`C.zeroed()` zero-initializes using the LHS type — equivalent to `= {0}` in C.
`C.init()` emits a bare declaration with NO initializer (uninitialized memory).
`C.init(x, y, z)` emits a brace-initializer list `{x, y, z}` using the LHS type.
`C.SDL_FRect(args)` is a typed compound literal and works in any expression context.
`C.SDL_FRect()` with no args is a zero compound literal `(SDL_FRect){0}`.

Examples:

    var rect: C.SDL_FRect = C.zeroed()     →  SDL_FRect rect = {0};
    var buf: C.SDL_FRect = C.init()        →  SDL_FRect buf;          // uninitialized
    var p: C.SDL_FPoint = C.init(1f, 2f)   →  SDL_FPoint p = {1, 2};
    val r = C.SDL_FRect(0f, 0f, 10f, 10f) →  SDL_FRect r = (SDL_FRect){0, 0, 10, 10};
    val z = C.SDL_FRect()                  →  SDL_FRect z = (SDL_FRect){0};

==========
Strings
==========

`String.cPtr` converts a KTC string to `const char*` (a raw C string pointer).
Use when passing Kotlin strings to C functions expecting `const char*`.

    C.SDL_SetWindowTitle(window.handle, title.cPtr)

==========
File-level annotations
==========

    @file:cInclude("SDL3/SDL.h")        →  #include <SDL3/SDL.h> in every .c of this package
    @file:cIncludeRelative("util.h")    →  #include "util.h"
*/
object C

inline fun <T> C.zeroed(vararg params: Any): T
inline fun <T> C.init(vararg params: Any): T
inline fun <T> C.addr(value: T): Ref<T>

/**
Unchecked C-level reinterpret cast. Emits `((T)(expr))` in the generated C
with no runtime check. Use this for C-interop edges where the type system
can't express the conversion — `const T*` ↔ `T*`, `FILE*` ↔ `void*`,
`Ref<T>` ↔ `Ref<U>`, primitive-to-primitive bit reinterpretation, etc.

Misuse will produce undefined behavior; the cast is exactly as safe (and
as dangerous) as the equivalent C cast.

    val fp: AnyPtr        = ...
    val file: Ref<C.FILE> = fp.cast<Ref<C.FILE>>()
    val data: RawArray<Byte> = strPtr.cast<RawArray<Byte>>()
 */
inline fun <T, R> T.cast(): R