# Allocator Design Plan

**Goal**: Replace hardcoded `malloc`/`free` with a pluggable `Allocator` interface,
plus `allocWith(allocator)` syntax for heap/arena/pool construction.

## 1. Motivation

Currently:
```kotlin
val p: @Ptr Vec2 = HeapAlloc<Vec2>(1.0f, 2.0f)  // compiler intrinsic, always malloc
val arr = intArrayOf(1, 2, 3)
val h: @Ptr IntArray = arr.toHeap()              // always malloc + memcpy
```

Problems:
- `HeapAlloc` is a compiler intrinsic, not a real function — can't be passed as a value
- `toHeap()` name is misleading: with arena/pool allocators it's not "the heap"
- No way to use custom allocators (arena, pool, stack, bump, tracing)
- Allocator choice is global (memTrack flag), not per-allocation

## 2. Proposed API (Kotlin)

### 2.1 Allocator Interface

```kotlin
interface Allocator {
    /** Allocate `size` bytes, return pointer (or null on failure). */
    fun alloc(size: Int): @Ptr Byte?

    /** Free a pointer previously returned by alloc(). */
    fun free(ptr: @Ptr Byte)

    /** Resize a previous allocation. Returns new pointer (may differ from ptr). */
    fun realloc(ptr: @Ptr Byte, newSize: Int): @Ptr Byte?

    /** Total bytes currently allocated (for debugging/leak detection). */
    val allocatedBytes: Long
}
```

### 2.2 Built-in Allocators

```kotlin
object Heap : Allocator {
    // malloc / free / realloc
}

object Arena : Allocator {
    // bump-pointer arena, freed all-at-once on dispose
    override fun dispose() { /* free the whole arena */ }
}

object Tracing : Allocator {
    // mark-and-sweep tracing GC (future)
}
```

`Allocator.Heap` is the global singleton — the default when no allocator is specified.

### 2.3 Constructor with Allocator — `allocWith`

`ClassType.allocWith(allocator, args...)` is a static factory — allocation + construction in one call.
No chaining, no stack intermediate.

```kotlin
// Stack allocation (unchanged):
val v = Vec2(1.0f, 2.0f)

// Heap via allocWith:
val p: @Ptr Vec2 = Vec2.allocWith(Allocator.Heap, 1.0f, 2.0f)

// Arena:
val arena = Arena()
val a: @Ptr Vec2 = Vec2.allocWith(arena, 1.0f, 2.0f)

// Pool (hypothetical):
val pool = Pool<Vec2>()
val pp: @Ptr Vec2 = Vec2.allocWith(pool, 1.0f, 2.0f)
```

Compiler emits directly (no stack construction + copy):
```c
ClassName_allocWith_primaryConstructor(allocator, args...)
// → allocator->vt->alloc(sizeof(ClassName)) + placement constructor
```

### 2.4 Copying Existing Values — `copyWith(allocator)` (replaces `toHeap()`)

`allocWith` → construct a **new** object with an allocator (static call).
`copyWith` → copy an **existing** value into allocator memory (instance call).

#### 2.4.1 Polymorphic `copyWith` — via Any vtable

Works on any type through the `Any` vtable, no field args:
```kotlin
val v = Vec2(1.0f, 2.0f)
val p: @Ptr Vec2 = v.copyWith(Allocator.Heap)   // copies v to allocator memory

val arr = intArrayOf(1, 2, 3)
val h: @Ptr IntArray = arr.copyWith(arena)      // copies array to arena
```

Polymorphic dispatch:
```kotlin
fun duplicate(item: Any, alloc: Allocator): @Ptr Any {
    return item.copyWith(alloc)                 // vt->copyWith
}
```

#### 2.4.2 Data class overload — `copyWith(allocator, field = val, ...)`

Concrete data classes get an overloaded `copyWith` that accepts field arguments,
mirroring `copy(field = val)` but allocating through the given allocator:

```kotlin
data class Vec2(val x: Float, val y: Float)

val v = Vec2(1.0f, 2.0f)

// Copy all fields to heap:
val p: @Ptr Vec2 = v.copyWith(Allocator.Heap)

// Copy with overridden fields:
val q: @Ptr Vec2 = v.copyWith(Allocator.Heap, x = 5.0f)
val r: @Ptr Vec2 = v.copyWith(arena, y = 10.0f)
```

Generated C for the overload:
```c
Vec2* Vec2_copyWith_primaryConstructor(ktc_std_Allocator* alloc, Vec2* $self, ktc_Float x, ktc_Float y) {
    Vec2* dst = (Vec2*)alloc->vt->alloc(alloc, sizeof(Vec2));
    if (dst) *dst = (Vec2){{.typeId = Vec2_TYPE_ID}, x, y};
    return dst;
}
```

The compiler recognizes the pattern `instance.copyWith(allocator, namedArgs...)` on a
data class and emits the overload instead of the vtable path.

#### 2.4.3 Function selection

| Receiver | Args | Dispatches to |
|----------|------|---------------|
| `Any` | `(alloc)` | `vt->copyWith` |
| Concrete data class | `(alloc)` | `vt->copyWith` (or concrete overload, same result) |
| Concrete data class | `(alloc, field=val, ...)` | `ClassName_copyWith_primaryConstructor` overload |

### 2.5 Deferred Free via `defer`

```kotlin
val p: @Ptr Vec2 = Vec2.allocWith(Allocator.Heap, 1.0f, 2.0f)
defer Allocator.Heap.free(p)   // or: defer p.dispose()
```

## 3. C-Level Design

### 3.1 Allocator Vtable

```c
typedef struct ktc_std_Allocator_vt {
    void*    (*alloc)(void* $self, ktc_Int size);
    void     (*free)(void* $self, void* ptr);
    void*    (*realloc)(void* $self, void* ptr, ktc_Int newSize);
    ktc_Long (*allocatedBytes)(void* $self);
} ktc_std_Allocator_vt;

typedef struct {
    ktc_core_AnySupertype __base;       // typeId
    const ktc_std_Allocator_vt* vt;
} ktc_std_Allocator;
```

### 3.2 Allocator.Heap Singleton

```c
// Static vtable instance
const ktc_std_Allocator_vt Allocator_Heap_vt = {
    .alloc = ktc_std_Heap_alloc,
    .free = ktc_std_Heap_free,
    .realloc = ktc_std_Heap_realloc,
    .allocatedBytes = ktc_std_Heap_allocatedBytes,
};

ktc_std_Allocator Allocator_Heap = {
    { .typeId = Allocator_Heap_TYPE_ID },
    .vt = &Allocator_Heap_vt
};
```

Wrapper functions (malloc/free thin wrappers matching vtable signatures):
```c
static void* ktc_std_Heap_alloc(void* $self, ktc_Int size) {
    (void)$self;
    return malloc((size_t)size);
}
static void ktc_std_Heap_free(void* $self, void* ptr) {
    (void)$self;
    free(ptr);
}
// etc.
```

### 3.3 `copyWith` on Any Vtable

Add a slot to `ktc_core_AnyVt`:
```c
typedef struct ktc_core_AnyVt {
    void      (*toString)(void* $self, void* sb);
    ktc_Int   (*hashCode)(void* $self);
    ktc_Bool  (*equals)(void* $self, void* other);
    void      (*dispose)(void* $self);
    void*     (*copyWith)(void* $self, ktc_std_Allocator* alloc);  // NEW
} ktc_core_AnyVt;
```

The `copyWith` wrapper for each class:
```c
static void* ClassName_copyWith_any(void* $self, ktc_std_Allocator* alloc) {
    ClassName* src = (ClassName*)$self;
    ClassName* dst = (ClassName*)alloc->vt->alloc(alloc, sizeof(ClassName));
    if (dst) *dst = *src;
    return dst;
}
```

For non-class types (primitives, arrays) wrapped in `ktc_Any`, the `copyWith`
path is handled by the existing `toHeap` logic but routed through the vtable.

### 3.4 Constructor with Allocator

Compiler transformation — when the AST has:
```
DotExpr(NameExpr(ClassName), "allocWith", [allocExpr, arg1, arg2...])
```

It emits:
```c
ClassName* ClassName_allocWith_primaryConstructor(ktc_std_Allocator* alloc, params...) {
    ClassName* $self = (ClassName*)alloc->vt->alloc(alloc, sizeof(ClassName));
    if ($self) *$self = ClassName_primaryConstructor(params...);
    return $self;
}
```

Call site: `ClassName_allocWith_primaryConstructor(allocExpr, arg1, arg2...)`.

For generic classes, monomorphized versions follow the same pattern.

## 4. Implementation Steps

### Phase 1: Allocator Interface (stdlib)

1. Add `Allocator` interface to `ktc_std.kt` with `alloc`, `free`, `realloc`, `allocatedBytes`
2. Add `Allocator.Heap` object implementing `Allocator`
3. Emit interface vtable + tagged union for `Allocator` (like any interface)
4. Emit `Allocator_Heap_vt` + singleton in `ktc_std.c`

### Phase 2: Any Vtable Extension

1. Add `copyWith` slot to `ktc_core_AnyVt` in `ktc_core.h`
2. Update `emitAnyVtable()` in `CCodeGenEmit.kt` to include `copyWith` wrapper
3. For primitives/arrays, the `copyWith` wrapper copies value into allocator memory

### Phase 3: `allocWith` / `copyWith` Compiler Support

1. Recognize `ClassName.allocWith(allocator, args...)` pattern in the AST (DotExpr on NameExpr)
2. Emit `ClassName_allocWith_primaryConstructor(allocator, args...)` directly — no stack intermediate
3. For existing values: recognize `expr.copyWith(allocator)` pattern
4. Route `copyWith` through `vtable->copyWith` for classes
5. For arrays: emit `allocator->alloc + memcpy` pattern
6. For primitives wrapped in Any: use typeId to determine size, `alloc + copy`

### Phase 4: Deprecate Old API

1. Mark `HeapAlloc<T>(args)` as deprecated, redirect to `T.allocWith(Allocator.Heap, args)`
2. Mark `toHeap()` as deprecated, redirect to `.copyWith(Allocator.Heap)`
3. Keep old intrinsics working for compatibility

### Phase 5: Arena Allocator

1. Add `Arena` class implementing `Allocator` in stdlib
2. Bump-pointer allocation with a linked list of blocks
3. `dispose()` frees all blocks at once
4. Useful for per-frame or per-request allocation patterns

### Phase 6: Integration

1. Update stdlib collections (`ArrayList`, `HashMap`) to accept optional allocator
2. Example: `mutableListOf(1, 2, 3, allocator = arena)`
3. Update `defer` pattern to work with allocator-based dispose

## 5. Naming Decision

| Old | New | Rationale |
|-----|-----|-----------|
| `HeapAlloc<T>(args)` | `T.allocWith(alloc, args...)` | Static factory: allocate + place |
| `toHeap()` | `instance.copyWith(alloc)` | Copy existing value into allocator |
| `Heap.freeMem(ptr)` | `allocator.free(ptr)` | Direct method on allocator instance |
| `HeapArrayZero` | `allocator.allocZeroed(...)` | Via allocator, not global |

## 6. Open Questions

1. **Default allocator**: Should `allocWith()` without explicit allocator default to `Allocator.Heap`? Yes — makes the common case concise.

2. **Nullability**: `alloc()` can return null. Return type is `@Ptr T?`:
   - `Vec2.allocWith(alloc, x, y)` returns `@Ptr Vec2?`

3. **Allocator as parameter**: Functions accept `Allocator` by value (interface tagged union with vtable).
   Global `Allocator.Heap` is a singleton object, passed as `const ktc_std_Allocator*`.
