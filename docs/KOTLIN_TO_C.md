# KTC — Kotlin Subset Reference

This document describes the **supported Kotlin subset**, its C mapping, and known
limitations. For transpiler internals see [TRANSPILER.md](TRANSPILER.md). For how to
write tests see [AGENTS.md](../AGENTS.md).

Source-to-source transpiler: Kotlin → C11. Zero runtime, stack-first, no GC.

---

## Types

### Primitives

| Kotlin    | C                                                                     |
|-----------|-----------------------------------------------------------------------|
| `Byte`    | `ktc_Byte` (int8_t)                                                   |
| `Short`   | `ktc_Short` (int16_t)                                                 |
| `Int`     | `ktc_Int` (int32_t)                                                   |
| `Long`    | `ktc_Long` (int64_t)                                                  |
| `UByte`   | `ktc_UByte` (uint8_t)                                                 |
| `UShort`  | `ktc_UShort` (uint16_t)                                               |
| `UInt`    | `ktc_UInt` (uint32_t)                                                 |
| `ULong`   | `ktc_ULong` (uint64_t)                                                |
| `Float`   | `ktc_Float` (float)                                                   |
| `Double`  | `ktc_Double` (double)                                                 |
| `Boolean` | `ktc_Bool` (bool)                                                     |
| `Char`    | `ktc_Char` (char)                                                     |
| `String`  | `ktc_String` = `{ const char* ptr; int32_t len; }` (non-owning slice) |

All primitives have `ktc_T$Optional` and `ktc_hash_*` support.

### Nullable

| Kotlin            | C                                                      |
|-------------------|--------------------------------------------------------|
| `T?` (value type) | `ktc_T$Optional` = `{ ktc$OptionalTag tag; T value; }` |
| `@Ptr T?`         | `T*` (NULL = null)                                     |
| `Array<T>?`       | `ktc_VarArr_T` with `ptr == NULL`                       |

### Arrays

| Kotlin                       | C                                  | Notes                                    |
|------------------------------|------------------------------------|------------------------------------------|
| `IntArray`, `ByteArray`, ... | `ktc_VarArr_ktc_Int`               | Struct `{ ptr, len }`, stack or heap     |
| `Array<T>`                   | `ktc_VarArr_T`                     | Struct `{ T*, len }`, **cannot be returned** |
| `@Size(N) Array<T>`          | `T[N]` (out-pointer for return)    | Fixed-size, **can be returned** via out-ptr |
| `@Ptr Array<T>`              | `ktc_VarArr_T`                     | Same VarArr struct, passed by value      |
| `RawArray<T>` + `@Ptr`       | `T*`                               | Raw pointer, no length tracking          |
| `Array<T>(n).allocWith(Heap)` | Heap-allocated `ktc_VarArr_T`      | Safe to return from functions            |

**Array factories:**

```kotlin
ByteArray(n)          // zero-filled stack array
byteArrayOf(v1,...)   // compound literal
intArrayOf(1, 2, 3)   // typed compound literal
arrayOf("a", "b")     // generic compound literal
Array<T>(n).allocWith(Heap)  // allocator-backed heap array, safe to return
```

**All type aliases exist for every built-in type:** `ByteArray`, `ShortArray`, `IntArray`, `LongArray`, `FloatArray`, `DoubleArray`, `BooleanArray`, `CharArray`, `UByteArray`, `UShortArray`, `UIntArray`, `ULongArray`, `StringArray` and corresponding `xxxArrayOf`.

**Array field limitation:** Class/object fields cannot hold raw `Array<T>`. Use `@Size(N) Array<T>` or `@Ptr Array<T>`.

---

## Annotations

### `@Ptr`
Pointer semantics. `@Ptr T` becomes `T*` in C.
```kotlin
fun update(buf: @Ptr ByteArray) { ... }   // → ktc_VarArr_ktc_Byte buf
fun passVec(v: @Ptr Vec2) { ... }         // → Vec2* v
```

### `@Size(N)`
Fixed-size array. Can be returned from functions (out-parameter ABI).
```kotlin
@Size(32) ByteArray     // → ktc_Byte[32]
@Size(64) UIntArray     // → ktc_UInt[64]
```

**Return ABI:** `fun hash(): @Size(32) ByteArray` → `void hash(ktc_Byte* $out)`. Caller allocates `ktc_Byte tmp[32]`, passes as out-param.

### `@Tls`
Thread-local storage. Applies to objects and top-level properties.
```kotlin
@Tls object Cache { var x = 0 }   // → ktc_tls Cache_t Cache = {0};
@Tls var globalCounter: Int = 0   // → ktc_tls ktc_Int globalCounter = 0;
```
Only object instance and init flag are TLS. For objects, `$init` is also `ktc_tls` so each thread initializes independently.

---

## Classes

```kotlin
class Vec2(val x: Float, val y: Float)        // data class
class Counter { var n = 0 }                   // regular class
class Inner : Parent { ... }                  // interface implementation
```

### Auto-generated methods (all classes)

| Method       | Signature                                                   | Notes                                                                    |
|--------------|-------------------------------------------------------------|--------------------------------------------------------------------------|
| `hashCode()` | `ktc_Int ClassName_hashCode(ClassName* $self)`              | Data: field hash; regular: identity hash with type_id mix                |
| `equals()`   | `bool ClassName_equals(ClassName a, ClassName b)`           | Field-by-field comparison; handles String, nullable, nested data classes |
| `toString()` | `void ClassName_toString(ClassName* $self, ktc_StrBuf* sb)` | Data classes only                                                        |
| `dispose()`  | `void ClassName_dispose(void* $self)`                       | No-op by default; can be overridden                                      |

All four can be overridden with `override`. `dispose()` and `hashCode()` require `override` keyword even when implicitly overriding.

### Private visibility
- Fields: `PRIV_` prefix on struct member names (C compiler enforces)
- Methods: `PRIV_` prefix on function name; forward-declared only in `.c`, not `.h`

### Constructor overloading (secondary constructors)
```kotlin
class Vec2(val x: Float, val y: Float) {
    constructor(s: Float) : this(s, s)                // → constructorWithFloat
    constructor(x: Int, y: Int) : this(x.toFloat(), y.toFloat())  // → constructorWithInt_Int
    constructor() : this(0f, 0f) { ... }              // → emptyConstructor
}
```

### Value semantics
All types are **by value** by default. Assignment copies the struct. `@Ptr T` introduces pointer semantics.

### `@Ptr` class pointer methods
| `.value()` | `(*ptr)` | Dereference to stack copy |
| `.ptr()` | `&value` | Take address → `T*` |
| `.copy()` | data class copy | Struct copy for data classes |
| `.copyTo(allocator)` | Allocation to selected allocator like "Heap" |

---

## Objects (Singletons)

```kotlin
object Config {
    var debug = true
    fun get(): Int = ...
}
```

- Generated as global `static Config_t Config = {0}`
- Lazy init: `$ensure_init()` called at first access
- `dispose()` called automatically at program exit (before `mem_report`)
- Object properties can be `extern` (accessible from other files)
- Private fields use `PRIV_` prefix

### Companion objects
```kotlin
class Foo {
    companion object { fun bar() = 42 }
}
// → Foo$Companion_t Foo$Companion
// → called via Foo.bar() → Foo$Companion_bar()
```

---

## Nested Classes

```kotlin
class Outer {
    class Inner(val x: Int)       // → Outer$Inner
}
object Sha256 {
    class Context() { ... }       // → Sha256$Context
}
```

- Separator: `$` (e.g. `Outer$Inner`, `Sha256$Context`)
- Parent object private functions/fields accessible from nested class
- Nested classes emitted inline within parent, with proper forward-declarations

---

## Functions

### Overloading (methods only)
```kotlin
fun digest(buff: @Ptr ByteArray) → digest
fun digest(buff: @Ptr ByteArray, offset: Int, length: Int) → digestWithByteArray_Int_Int
fun greet() → greetNoArg
fun greet(name: String) → greetWithString
```
No-param overload: `NoArg`. Others: `WithType1_Type2`. Top-level overloads use same naming.

### Default parameters
Filled at call site. Not part of C function signature (all params always present).

### Inline functions
Expanded at call site. No C function emitted. Only valid use of lambdas (as inline args).

### Function references `::funRef`
Produces raw C function pointer.

### `defer`
RAII extension. LIFO execution at function end/return.
```kotlin
fun example() {
    defer { println("cleanup") }
    // ...body...
} // cleanup runs here
```

---

## Control Flow

### `if` / `when` / `for` / `while`
Standard Kotlin semantics. `if` as expression generates C ternary (only when both branches present). `when` supports enum and value matching.

### `for` loops
```kotlin
for (i in 0..5) { ... }
for (i in 0 until 10 step 2) { ... }
for (i in 10 downTo 0) { ... }
for (item in collection) { ... }  // requires operator iterator()
```

### Smart casts
`is` / `!is` / `as` / `as?` on `Any` and nullable types.

---

## Built-in Operations

### Bitwise (compiler intrinsic)
| Kotlin                   | C                  |
|--------------------------|--------------------|
| `x.and(y)` / `x and y`   | `x & y`            |
| `x.or(y)` / `x or y`     | `x \| y`           |
| `x.xor(y)` / `x xor y`   | `x ^ y`            |
| `x.shl(y)` / `x shl y`   | `x << y`           |
| `x.shr(y)` / `x shr y`   | `x >> y`           |
| `x.ushr(y)` / `x ushr y` | `(ktc_UInt)x >> y` |
| `x.inv()`                | `~(x)`             |

### String
- `.length`, `.len`, `.runeLen`, `.runeAt(byteIndex)`
- `.substring()`, `.startsWith()`, `.endsWith()`, `.contains()`, `.isEmpty()`
- `.toInt()`, `.toLong()`, `.toFloat()`, `.toDouble()`
- `.toIntOrNull()`, `.toLongOrNull()`, ...
- `ktc_string_eq()`
- String templates: `"value: $x"` → printf format

### Type conversions
- `.toByte()`, `.toShort()`, `.toInt()`, `.toLong()`, `.toFloat()`, `.toDouble()`
- `.toUByte()`, `.toUShort()`, `.toUInt()`, `.toULong()`, `.toChar()`

### `Pair` / `Triple` / `Tuple`
Built-in compound types. `Pair<A,B>`, `Triple<A,B,C>`, `Tuple<T...>`. Emitted as dedicated C structs.

### `StringBuffer`
Mutable string builder. Methods: `toString(sb)`, `.buffer` (pointer), `.len`, reuse via `sb.len = 0`.

---

## C Interop (`c.` prefix)

```kotlin
c.printf("value: %d\n", x)
c.memcpy(dst, src, n)
c.putchar(ch.toInt())
c.time(c.NULL)
c.EXIT_SUCCESS
c.NULL
```
String literals passed as raw C strings.

---

## Standard Library (`package ktc.std`)

### `Random` — `@Tls` global PRNG
```kotlin
Random.nextInt(until = 0)
Random.nextIntBetween(from, until)
Random.nextLong(until = 0L)
Random.nextLongBetween(from, until)
Random.nextFloat() / nextDouble()
Random.nextDoubleBetween(from, until)
Random.nextBoolean()
```

### `Sha256`
```kotlin
val hash = Sha256.digest(data)                    // one-shot
val hash = Sha256.digest(data, offset, length)    // with bounds
val ctx = Sha256.new()                             // streaming
ctx.update(data)
ctx.update(data, offset, length)
val hash = ctx.finalizeHash()                      // → @Size(32) ByteArray
```

### Collections (`Map`, `HashMap`, `ArrayList`, `List`, `MutableList`, `ListIterator`, `MapIterator`)

### `error(msg)` / `TODO(reason)`
Terminates with message.

---

## Known Limitations

These Kotlin features are **not supported** and have no planned equivalent:

| Feature                                          | Status                                                     |
|--------------------------------------------------|------------------------------------------------------------|
| Coroutines / `suspend`                           | Not supported                                              |
| Reflection                                       | Not supported                                              |
| Closures (capturing lambdas stored in variables) | Not supported — lambdas are inline-only                    |
| `try`/`catch`/`throw` exceptions                 | Not supported — use `error()`                              |
| Inheritance between classes (`open class`)       | Not supported — use interfaces                             |
| `sealed class`                                   | Not supported — use `when` + interfaces                    |
| String `split`, `replace`, complex operations    | Limited — use C interop for complex string work            |
| Checked arithmetic, overflow detection           | No — wraps like C                                          |
| Dynamic dispatch on value types                  | Not supported — use `@Ptr` for polymorphism                |
| `Array<T>` as a class field                      | Not supported — use `@Size(N) Array<T>` or `@Ptr Array<T>` |
| Raw `Array<T>` returned from functions           | Not supported — use `@Size(N)` or heap arrays              |

---

## Target C Output

- Standard C11 (`gcc -std=c11`, clang, MSVC)
- Section comments: `// ══ class Vec2 (file.kt) ══`
- All generated symbols use `package_` prefix (e.g. `game_Vec2`)
- Single `.h` and `.c` per package
