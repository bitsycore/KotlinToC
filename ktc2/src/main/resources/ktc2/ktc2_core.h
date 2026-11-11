/**
 * ktc2 core runtime — primitive semantics that differ between Kotlin and C.
 *
 * Kotlin integer arithmetic wraps, shifts mask their count, and float-to-int
 * conversion saturates; plain C operators are undefined or different in those
 * cases, so generated code goes through these helpers.
 */
#ifndef KTC2_CORE_H
#define KTC2_CORE_H

#include <math.h>
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

/* Generated for each program: defines KTC2_THREADS when the program starts threads. */
#include "ktc2_config.h"

/* ================== */
/* MARK: Compilers    */
/* ================== */

/*
 * What is not the same in every C compiler is named here once. gcc and clang (on every
 * system, clang-cl included) have the `__atomic` and `__builtin` operations; the compiler of
 * Visual Studio has its own, or none, in which case plain C does the work.
 */
#include <string.h>

#if defined(_MSC_VER) && !defined(__clang__)
#include <intrin.h>
#include <malloc.h>

/* Atomic operations on a 32-bit count. `add` and `sub` give the value after the operation. */
/* A plain read: the count of a constant object is in memory that cannot be written, which an interlocked operation would do. */
#define ktc2_atomic_load(address) (*(volatile uint32_t*)(address))
#define ktc2_atomic_add(address, amount) ((uint32_t)_InterlockedExchangeAdd((volatile long*)(address), (long)(amount)) + (uint32_t)(amount))
#define ktc2_atomic_sub(address, amount) ((uint32_t)_InterlockedExchangeAdd((volatile long*)(address), -(long)(amount)) - (uint32_t)(amount))
#define ktc2_atomic_or(address, bits) ((void)_InterlockedOr((volatile long*)(address), (long)(bits)))
#define ktc2_atomic_and(address, bits) ((void)_InterlockedAnd((volatile long*)(address), (long)(bits)))
#define ktc2_atomic_exchange(address, value) ((uint32_t)_InterlockedExchange((volatile long*)(address), (long)(value)))
#define ktc2_atomic_add64(address, amount) ((void)_InterlockedExchangeAdd64((volatile __int64*)(address), (__int64)(amount)))

/** Stores [desired] where [expected] is found; otherwise tells in [expected] what is there. */
static __inline int ktc2_atomic_replace(uint32_t* address, uint32_t* expected, uint32_t desired)
{
	uint32_t found = (uint32_t)_InterlockedCompareExchange((volatile long*)address, (long)desired, (long)*expected);
	if (found == *expected) return 1;
	*expected = found;
	return 0;
}

#define ktc2_alloca(size) _alloca(size)

#else

#define ktc2_atomic_load(address) __atomic_load_n((address), __ATOMIC_RELAXED)
#define ktc2_atomic_add(address, amount) __atomic_add_fetch((address), (amount), __ATOMIC_ACQ_REL)
#define ktc2_atomic_sub(address, amount) __atomic_sub_fetch((address), (amount), __ATOMIC_ACQ_REL)
#define ktc2_atomic_or(address, bits) ((void)__atomic_fetch_or((address), (bits), __ATOMIC_ACQ_REL))
#define ktc2_atomic_and(address, bits) ((void)__atomic_fetch_and((address), (bits), __ATOMIC_ACQ_REL))
#define ktc2_atomic_exchange(address, value) __atomic_exchange_n((address), (value), __ATOMIC_ACQ_REL)
#define ktc2_atomic_add64(address, amount) ((void)__atomic_fetch_add((address), (amount), __ATOMIC_RELAXED))
#define ktc2_atomic_replace(address, expected, desired) __atomic_compare_exchange_n((address), (expected), (desired), 1, __ATOMIC_ACQ_REL, __ATOMIC_RELAXED)

#define ktc2_alloca(size) __builtin_alloca(size)

#endif

/** [size] zeroed bytes in the frame of the function that asks. */
#define ktc2_stack_zeroed(size) memset(ktc2_alloca(size), 0, (size))

/* Counting bits. The arguments of the zero counts are not zero: Kotlin's functions test for it first. */
#if defined(__GNUC__) || defined(__clang__)
#define ktc2_popcount32(value) __builtin_popcount(value)
#define ktc2_popcount64(value) __builtin_popcountll(value)
#define ktc2_clz32(value) __builtin_clz(value)
#define ktc2_clz64(value) __builtin_clzll(value)
#define ktc2_ctz32(value) __builtin_ctz(value)
#define ktc2_ctz64(value) __builtin_ctzll(value)
#else
static __inline int ktc2_popcount64(uint64_t value)
{
	int count = 0;
	for (; value; value &= value - 1) count++;
	return count;
}
static __inline int ktc2_clz64(uint64_t value)
{
	int count = 0;
	for (uint64_t bit = UINT64_C(1) << 63; bit && !(value & bit); bit >>= 1) count++;
	return count;
}
static __inline int ktc2_ctz64(uint64_t value)
{
	int count = 0;
	for (; !(value & 1); value >>= 1) count++;
	return count;
}
#define ktc2_popcount32(value) ktc2_popcount64((uint32_t)(value))
#define ktc2_clz32(value) (ktc2_clz64((uint32_t)(value)) - 32)
#define ktc2_ctz32(value) ktc2_ctz64((uint32_t)(value))
#endif

/* ================== */
/* MARK: Threads      */
/* ================== */

/*
 * A program that never starts a thread counts references with plain increments. One that
 * does is built with KTC2_THREADS: counts become atomic, and the state that belongs to one
 * thread (the exception in flight) becomes thread-local.
 *
 * Even then a count is atomic only where two threads can meet on it: not before the program
 * starts its first thread, and never for an object in a frame, which no other thread reaches
 * (an object handed to a thread is one that leaves its frame, so it is not built there).
 */
#ifdef KTC2_THREADS
/** Set by the first thread the program starts, before that thread exists. */
extern int ktc2_threaded;
#define KTC2_TLS _Thread_local
#define KTC2_RC_LOAD(object) ktc2_atomic_load(&(object)->rc)
#define KTC2_RC_ALONE(count) (!ktc2_threaded || ((count) & KTC2_STACK))
#define KTC2_RC_INC(object, count) (KTC2_RC_ALONE(count) ? (void)(object)->rc++ : (void)ktc2_atomic_add(&(object)->rc, 1))
#define KTC2_RC_DEC(object, count) (KTC2_RC_ALONE(count) ? --(object)->rc : ktc2_atomic_sub(&(object)->rc, 1))
#define KTC2_STAT_INC(counter) ((void)ktc2_atomic_add64(&(counter), 1))
#else
#define KTC2_TLS
#define KTC2_RC_LOAD(object) ((object)->rc)
#define KTC2_RC_INC(object, count) ((void)(object)->rc++)
#define KTC2_RC_DEC(object, count) (--(object)->rc)
#define KTC2_STAT_INC(counter) ((void)(counter)++)
#endif

/* ================== */
/* MARK: Objects      */
/* ================== */

/** Prefix of every reference object. */
typedef struct ktc2_Obj {
	uint32_t rc;      /* reference count, or KTC2_IMMORTAL for static objects */
	uint32_t typeId;  /* index into ktc2_types[] */
} ktc2_Obj;

#define KTC2_IMMORTAL UINT32_MAX

/** Flag in `rc` of an object that lives on the stack: at a count of zero it is cleaned up but not freed. */
#define KTC2_STACK UINT32_C(0x80000000)

/** Flag in `rc` of an object that has a weak cell: destroying it clears the cell. */
#define KTC2_WEAK UINT32_C(0x40000000)

/** Flag in `rc` of an object allocated in an `arena { }` block: its memory belongs to the arena. */
#define KTC2_ARENA UINT32_C(0x20000000)

/** The bits of `rc` that count references. */
#define KTC2_COUNT_MASK UINT32_C(0x1FFFFFFF)

/** Per-type data. `destroy` releases the references an instance holds; NULL when it holds none. */
typedef struct ktc2_TypeInfo {
	const char* name;
	void (*destroy)(ktc2_Obj* object);
} ktc2_TypeInfo;

enum {
	KTC2_TYPE_NONE = 0,
	KTC2_TYPE_STRING = 1,
	KTC2_TYPE_ARRAY_BOOLEAN,
	KTC2_TYPE_ARRAY_BYTE,
	KTC2_TYPE_ARRAY_SHORT,
	KTC2_TYPE_ARRAY_INT,
	KTC2_TYPE_ARRAY_LONG,
	KTC2_TYPE_ARRAY_CHAR,
	KTC2_TYPE_ARRAY_FLOAT,
	KTC2_TYPE_ARRAY_DOUBLE,
	KTC2_TYPE_ARRAY_UBYTE,
	KTC2_TYPE_ARRAY_USHORT,
	KTC2_TYPE_ARRAY_UINT,
	KTC2_TYPE_ARRAY_ULONG,
	KTC2_TYPE_ARRAY_REF,    /* elements are references, released with the array */
	KTC2_TYPE_ARRAY_VALUE,  /* elements are plain values (nullable primitives, structs) */
	KTC2_TYPE_BOX_BOOLEAN,  /* a primitive held as an object (an Int seen as Any) */
	KTC2_TYPE_BOX_BYTE,
	KTC2_TYPE_BOX_SHORT,
	KTC2_TYPE_BOX_INT,
	KTC2_TYPE_BOX_LONG,
	KTC2_TYPE_BOX_CHAR,
	KTC2_TYPE_BOX_FLOAT,
	KTC2_TYPE_BOX_DOUBLE,
	KTC2_TYPE_BOX_UBYTE,
	KTC2_TYPE_BOX_USHORT,
	KTC2_TYPE_BOX_UINT,
	KTC2_TYPE_BOX_ULONG,
	KTC2_TYPE_WEAK_CELL,    /* what a weak reference holds: see ktc2_weakCell */
	KTC2_TYPE_FIRST_USER
};

struct ktc2_Obj;
void ktc2_array_destroy(struct ktc2_Obj* object);
void ktc2_weak_cell_destroy(struct ktc2_Obj* object);

#define KTC2_BUILTIN_TYPES \
	[KTC2_TYPE_STRING] = { "String", NULL }, \
	[KTC2_TYPE_ARRAY_BOOLEAN] = { "BooleanArray", NULL }, \
	[KTC2_TYPE_ARRAY_BYTE] = { "ByteArray", NULL }, \
	[KTC2_TYPE_ARRAY_SHORT] = { "ShortArray", NULL }, \
	[KTC2_TYPE_ARRAY_INT] = { "IntArray", NULL }, \
	[KTC2_TYPE_ARRAY_LONG] = { "LongArray", NULL }, \
	[KTC2_TYPE_ARRAY_CHAR] = { "CharArray", NULL }, \
	[KTC2_TYPE_ARRAY_FLOAT] = { "FloatArray", NULL }, \
	[KTC2_TYPE_ARRAY_DOUBLE] = { "DoubleArray", NULL }, \
	[KTC2_TYPE_ARRAY_UBYTE] = { "UByteArray", NULL }, \
	[KTC2_TYPE_ARRAY_USHORT] = { "UShortArray", NULL }, \
	[KTC2_TYPE_ARRAY_UINT] = { "UIntArray", NULL }, \
	[KTC2_TYPE_ARRAY_ULONG] = { "ULongArray", NULL }, \
	[KTC2_TYPE_ARRAY_REF] = { "Array", ktc2_array_destroy }, \
	[KTC2_TYPE_ARRAY_VALUE] = { "Array", NULL }, \
	[KTC2_TYPE_BOX_BOOLEAN] = { "Boolean", NULL }, \
	[KTC2_TYPE_BOX_BYTE] = { "Byte", NULL }, \
	[KTC2_TYPE_BOX_SHORT] = { "Short", NULL }, \
	[KTC2_TYPE_BOX_INT] = { "Int", NULL }, \
	[KTC2_TYPE_BOX_LONG] = { "Long", NULL }, \
	[KTC2_TYPE_BOX_CHAR] = { "Char", NULL }, \
	[KTC2_TYPE_BOX_FLOAT] = { "Float", NULL }, \
	[KTC2_TYPE_BOX_DOUBLE] = { "Double", NULL }, \
	[KTC2_TYPE_BOX_UBYTE] = { "UByte", NULL }, \
	[KTC2_TYPE_BOX_USHORT] = { "UShort", NULL }, \
	[KTC2_TYPE_BOX_UINT] = { "UInt", NULL }, \
	[KTC2_TYPE_BOX_ULONG] = { "ULong", NULL }, \
	[KTC2_TYPE_WEAK_CELL] = { "ktc2.WeakRef cell", ktc2_weak_cell_destroy }

/**
 * A new object of [typeId] holding a copy of the [size] bytes at [data], [offset] bytes into
 * the object: a `@Struct` value put in its box.
 */
ktc2_Obj* ktc2_box_value(const void* data, size_t size, uint32_t typeId, size_t offset);

/**
 * The object a function builds in the room its caller gave it: the room itself, taken as the
 * one reference the function then returns.
 */
#define ktc2_slot_take(slot) ((void*)(slot))

/** Generated in ktc2_main.c. */
extern const ktc2_TypeInfo ktc2_types[];
extern const uint32_t ktc2_type_count;

/** A zeroed object of [size] bytes with a count of 1. */
void* ktc2_alloc(size_t size, uint32_t typeId);

/** Runs the type's destroy and frees the memory. */
void ktc2_destroy(ktc2_Obj* object);

/* With KTC2_ARC_STATS the runtime counts reference-count operations and reports them at exit. */
#ifdef KTC2_ARC_STATS
extern uint64_t ktc2_stat_retains;
extern uint64_t ktc2_stat_releases;
#define KTC2_COUNT(counter) KTC2_STAT_INC(counter)
#else
#define KTC2_COUNT(counter) ((void)0)
#endif

/** Null-safe; returns its argument so it can wrap an expression. */
static inline void* ktc2_retain(void* pointer)
{
	ktc2_Obj* object = pointer;
	KTC2_COUNT(ktc2_stat_retains);
	if (object) {
		uint32_t count = KTC2_RC_LOAD(object);
		if (count != KTC2_IMMORTAL) KTC2_RC_INC(object, count);
	}
	return pointer;
}

/** Null-safe; destroys the object when the last reference goes. */
static inline void ktc2_release(void* pointer)
{
	ktc2_Obj* object = pointer;
	KTC2_COUNT(ktc2_stat_releases);
	if (object) {
		uint32_t count = KTC2_RC_LOAD(object);
		if (count != KTC2_IMMORTAL && (KTC2_RC_DEC(object, count) & KTC2_COUNT_MASK) == 0) ktc2_destroy(object);
	}
}

/**
 * Stores an owned reference into a slot and releases what the slot held. The new value is
 * evaluated first: computing it may itself change the slot.
 */
#define KTC2_ASSIGN(slot, value) 	do { 		void* ktc2_new = (value); 		void* ktc2_old = (slot); 		(slot) = ktc2_new; 		ktc2_release(ktc2_old); 	} while (0)

/** The same for a slot holding a struct with references in it; [release] gives up those of the old value. */
#define KTC2_ASSIGN_VALUE(type, slot, value, release) 	do { 		type ktc2_new = (value); 		type ktc2_old = (slot); 		(slot) = ktc2_new; 		release(ktc2_old); 	} while (0)

/** Called at the end of main: reports objects still alive when built with KTC2_LEAK_CHECK. Returns the exit code. */
int ktc2_exit(void);

/* ================== */
/* MARK: C interop    */
/* ================== */

struct ktc2_String;
struct ktc2_Array;

/** The bytes of a string as C sees them (UTF-8, NUL-terminated). Valid while the string lives. */
#define ktc2_str_bytes(string) ((void*)((ktc2_String*)(string))->bytes)

/** The same for a string that may be null: NULL for NULL. */
void* ktc2_str_cptr(struct ktc2_String* string);

/** A new string from NUL-terminated UTF-8 text. */
struct ktc2_String* ktc2_string_of_cstr(const char* text);

/**
 * A C struct held by value (`CValue<T>`) is a byte array with the struct as its content.
 * This is the address of that struct.
 */
#define ktc2_cvalue_data(value) ((void*)((ktc2_Array*)(value) + 1))

/** The same for a value that may be null: NULL for NULL. */
void* ktc2_cvalue_ptr(struct ktc2_Array* value);

/** A new struct value holding a copy of the [size] bytes at [data]. */
struct ktc2_Array* ktc2_cvalue_copy(const void* data, int64_t size);

/** A C address held as an object: a boxed Long, NULL staying NULL. */
ktc2_Obj* ktc2_box_ptr(void* address);
void* ktc2_unbox_ptr(ktc2_Obj* object);

/**
 * Memory of a `memScoped` block: a zeroed block of [size] bytes chained in front of [head],
 * the previous block of the scope. The usable bytes start 16 bytes into the block.
 */
void* ktc2_scope_alloc(void* head, int64_t size);

/** Frees the chain of blocks starting at [head]. */
void ktc2_scope_free(void* head);

/* ================== */
/* MARK: Arena        */
/* ================== */

/**
 * Starts an arena on this thread: until the matching [ktc2_arenaLeave], objects are carved
 * out of blocks of [size] bytes instead of being allocated one by one. Arenas nest.
 * Returns the handle to leave with.
 */
int64_t ktc2_arenaEnter(int32_t size);

/**
 * Ends the arena. Its blocks are freed at once, except a block that still holds an object
 * referred to from outside: that block lives until the last such object is released.
 */
void ktc2_arenaLeave(int64_t arena);

/* ================== */
/* MARK: Concurrency  */
/* ================== */

/**
 * Runs `java.lang.Thread.run` of [thread] on a new thread, which holds a reference to it
 * until it ends. Returns a handle for [ktc2_threadJoin]. Only with KTC2_THREADS.
 */
int64_t ktc2_threadStart(ktc2_Obj* thread);

/** Waits for the thread behind [handle] to end and gives the handle up. */
void ktc2_threadJoin(int64_t handle);

/** The time of day in nanoseconds since 1970 (`System.currentTimeMillis`, `System.nanoTime`). */
int64_t ktc2_timeNanos(void);

/** `a / b` for a `b` that is written as zero in the program: infinity or NaN, as Kotlin has it. */
double ktc2_divide_by_zero(double a, double b);

/** Suspends the calling thread for [milliseconds]. */
void ktc2_threadSleep(int64_t milliseconds);

/**
 * `synchronized`: a recursive monitor per object, kept in a table of the runtime for as long
 * as the object is locked. It needs no room in the object.
 */
void ktc2_monitorEnter(ktc2_Obj* lock);
void ktc2_monitorExit(ktc2_Obj* lock);

/**
 * Around the first use of an object or an enum class: one thread initializes, a thread that
 * arrives meanwhile waits until it has finished. The lock is recursive, an initializer may
 * use other objects. Nothing to do in a program without threads.
 */
#ifdef KTC2_THREADS
void ktc2_initEnter(void);
void ktc2_initExit(void);
#else
#define ktc2_initEnter() ((void)0)
#define ktc2_initExit() ((void)0)
#endif

/* ================== */
/* MARK: Weak         */
/* ================== */

/**
 * The weak cell of [target]: an object that refers to it without keeping it alive, shared by
 * every weak reference to the same target. Returns an owned reference, NULL for NULL.
 */
ktc2_Obj* ktc2_weakCell(ktc2_Obj* target);

/** The target of a weak cell as an owned reference, or NULL once it has been destroyed. */
ktc2_Obj* ktc2_weakGet(ktc2_Obj* cell);

/* ================== */
/* MARK: Exceptions   */
/* ================== */

/**
 * The exception in flight, or NULL. A function that throws parks it here and returns; each
 * caller that can receive one tests for it and leaves in turn, until a `try` takes it.
 */
extern KTC2_TLS ktc2_Obj* ktc2_pending;

static inline bool ktc2_has_pending(void) { return ktc2_pending != NULL; }

/** Parks [exception] (taking a reference of its own), replacing one already in flight. */
void ktc2_throw(ktc2_Obj* exception);

/** Takes the exception in flight out of the runtime; the caller owns it. NULL when there is none. */
ktc2_Obj* ktc2_take_pending(void);

/** Reports the exception in flight as the JVM reports an uncaught one, and exits. */
_Noreturn void ktc2_uncaught(void);

/** Layout of kotlin.Throwable, the base of every exception object. */
typedef struct ktc2_Throwable {
	ktc2_Obj header;
	struct ktc2_String* message;
	struct ktc2_Throwable* cause;
} ktc2_Throwable;

/** `Throwable.toString()`: the class name, then ": message" when there is one. Owned by the caller. */
struct ktc2_String* ktc2_throwable_toString(ktc2_Obj* exception, struct ktc2_String* message);

_Noreturn void ktc2_fail_null(void);
_Noreturn void ktc2_fail_cast(void);
_Noreturn void ktc2_fail_dispatch(void);

/** A virtual call reached an object whose class has no implementation of [function]. */
_Noreturn void ktc2_fail_dispatch_of(const char* function, uint32_t typeId);

/* Nullable primitives: a presence flag and the value. */
#define KTC2_OPT(type) typedef struct { bool has; type v; } ktc2_Opt_##type
KTC2_OPT(bool);
KTC2_OPT(int8_t);
KTC2_OPT(int16_t);
KTC2_OPT(int32_t);
KTC2_OPT(int64_t);
KTC2_OPT(uint8_t);
KTC2_OPT(uint16_t);
KTC2_OPT(uint32_t);
KTC2_OPT(uint64_t);
KTC2_OPT(float);
KTC2_OPT(double);

_Noreturn void ktc2_fail_when(void);

/* ================== */
/* MARK: Boxes        */
/* ================== */

/** A primitive held as an object, for when it is used as `Any`. */
typedef struct ktc2_Box {
	ktc2_Obj header;
	union {
		bool boolean_;
		int8_t byte_;
		int16_t short_;
		int32_t int_;
		int64_t long_;
		uint16_t char_;
		float float_;
		double double_;
		uint8_t ubyte_;
		uint16_t ushort_;
		uint32_t uint_;
		uint64_t ulong_;
	} value;
} ktc2_Box;

/* ktc2_box_<kind>(v) returns a new box; ktc2_unbox_<kind>(o) fails unless o is a box of that kind.
   The _opt_ variants convert between a nullable primitive and a box-or-NULL. */
#define KTC2_BOX(kind, type, id) \
	static inline ktc2_Obj* ktc2_box_##kind(type v) \
	{ \
		ktc2_Box* box = ktc2_alloc(sizeof(ktc2_Box), id); \
		box->value.kind##_ = v; \
		return &box->header; \
	} \
	static inline type ktc2_unbox_##kind(ktc2_Obj* object) \
	{ \
		if (!object) ktc2_fail_null(); \
		if (object->typeId != id) ktc2_fail_cast(); \
		return ((ktc2_Box*)object)->value.kind##_; \
	} \
	static inline ktc2_Obj* ktc2_box_opt_##kind(ktc2_Opt_##type v) \
	{ \
		return v.has ? ktc2_box_##kind(v.v) : NULL; \
	} \
	static inline ktc2_Opt_##type ktc2_unbox_opt_##kind(ktc2_Obj* object) \
	{ \
		return object ? (ktc2_Opt_##type){ true, ktc2_unbox_##kind(object) } : (ktc2_Opt_##type){ false }; \
	}

KTC2_BOX(boolean, bool, KTC2_TYPE_BOX_BOOLEAN)
KTC2_BOX(byte, int8_t, KTC2_TYPE_BOX_BYTE)
KTC2_BOX(short, int16_t, KTC2_TYPE_BOX_SHORT)
KTC2_BOX(int, int32_t, KTC2_TYPE_BOX_INT)
KTC2_BOX(long, int64_t, KTC2_TYPE_BOX_LONG)
KTC2_BOX(char, uint16_t, KTC2_TYPE_BOX_CHAR)
KTC2_BOX(float, float, KTC2_TYPE_BOX_FLOAT)
KTC2_BOX(double, double, KTC2_TYPE_BOX_DOUBLE)
KTC2_BOX(ubyte, uint8_t, KTC2_TYPE_BOX_UBYTE)
KTC2_BOX(ushort, uint16_t, KTC2_TYPE_BOX_USHORT)
KTC2_BOX(uint, uint32_t, KTC2_TYPE_BOX_UINT)
KTC2_BOX(ulong, uint64_t, KTC2_TYPE_BOX_ULONG)

/* hashCode() of the primitives whose hash is not the value itself (the JVM's formulas). */
static inline int32_t ktc2_hash_boolean(bool v) { return v ? 1231 : 1237; }
static inline int32_t ktc2_hash_long(int64_t v) { return (int32_t)((uint64_t)v ^ ((uint64_t)v >> 32)); }
static inline int32_t ktc2_hash_ulong(uint64_t v) { return (int32_t)(v ^ (v >> 32)); }
int32_t ktc2_hash_float(float v);
int32_t ktc2_hash_double(double v);

/* ================== */
/* MARK: Arrays       */
/* ================== */

/** Header of every array; the elements follow it. */
typedef struct ktc2_Array {
	ktc2_Obj header;
	int32_t length;
	int32_t elementSize;
} ktc2_Array;

/** The arguments of the program as an `Array<String>`: the command line without the program's name. */
struct ktc2_Array* ktc2_args(int argc, char** argv);

/** The kind of array [object] is, as `ktc2.internal.arrayKind` documents; 0 for anything else. */
int32_t ktc2_arrayKind(ktc2_Obj* object);

/** True when [object] is an array of the kind [kind] names (`x is IntArray`). */
#define ktc2_array_is(object, kind) ((object) != NULL && ((ktc2_Obj*)(object))->typeId == (kind))

/** A zeroed array; fails on a negative length. */
ktc2_Array* ktc2_array_new(int32_t length, size_t elementSize, uint32_t typeId);

/** Bytes of elements a frame keeps for an array whose size is known only at run time. */
#ifndef KTC2_STACK_ARRAY_BYTES
#define KTC2_STACK_ARRAY_BYTES 256
#endif

/**
 * An array that does not leave the block it is created in: [storage] (zeroed, in the caller's
 * frame, with room for KTC2_STACK_ARRAY_BYTES of elements) when [length] elements fit there,
 * otherwise a new array.
 */
static inline ktc2_Array* ktc2_array_local(ktc2_Array* storage, int32_t length, size_t elementSize, uint32_t typeId)
{
	if (length < 0 || (size_t)length * elementSize > KTC2_STACK_ARRAY_BYTES) return ktc2_array_new(length, elementSize, typeId);
	storage->header.rc = KTC2_STACK | 1;
	storage->header.typeId = typeId;
	storage->length = length;
	storage->elementSize = (int32_t)elementSize;
	return storage;
}

/*
 * A failed check of the language. With KTC2_CHECKS_THROW these park the Kotlin exception and
 * return, and the caller carries on with a harmless value until the generated code tests for
 * the exception; otherwise they end the program.
 */
#ifdef KTC2_CHECKS_THROW
#define KTC2_FAILS
#else
#define KTC2_FAILS _Noreturn
#endif

KTC2_FAILS void ktc2_fail_index(int32_t index, int32_t length);

/** True when [index] is outside [array]. */
#define ktc2_index_bad(array, index) ((uint32_t)(index) >= (uint32_t)((ktc2_Array*)(array))->length)

#define ktc2_is_zero(value) ((value) == 0)

static inline int32_t ktc2_array_length(const ktc2_Array* array) { return array->length; }

/** Returns [index] after checking it against the array's bounds. */
static inline int32_t ktc2_array_index(const ktc2_Array* array, int32_t index)
{
	if ((uint32_t)index >= (uint32_t)array->length) {
		ktc2_fail_index(index, array->length);
		/* Every array has room for one element: a failed read yields that one. */
		return 0;
	}
	return index;
}

/**
 * Fills the inline array [slot] (room for [length] elements) with a copy of [source], which
 * must have exactly that many elements.
 */
void ktc2_array_assign(ktc2_Array* slot, int32_t length, uint32_t typeId, size_t elementSize, const ktc2_Array* source);

/** The bounds-checked element, as an lvalue. */
#define KTC2_ARRAY_AT(array, type, index) (((type*)((array) + 1))[ktc2_array_index((array), (index))])

/** The same for an index the generated code has already tested. */
#define KTC2_ARRAY_AT_UNCHECKED(array, type, index) (((type*)((array) + 1))[(index)])

/* ================== */
/* MARK: Strings      */
/* ================== */

/**
 * An immutable string: UTF-8 bytes, NUL-terminated. `length` is the Kotlin length (UTF-16
 * code units); when it equals `size` the string is ASCII and indexing is direct.
 */
typedef struct ktc2_String {
	ktc2_Obj header;
	int32_t size;
	int32_t length;
	char bytes[];
} ktc2_String;

/** Defines a static, immortal string object. */
#define KTC2_STRING_LIT(name, size, length, text) 	static const struct { ktc2_Obj header; int32_t size_; int32_t length_; char bytes[sizeof(text)]; } name = 		{ { KTC2_IMMORTAL, KTC2_TYPE_STRING }, size, length, text }

#define KTC2_STR(name) ((ktc2_String*)&name)

static inline int32_t ktc2_str_length(const ktc2_String* s) { return s->length; }

/** `s[index]`: the UTF-16 code unit at [index]. */
uint16_t ktc2_str_get(const ktc2_String* s, int32_t index);
/** Null-safe content equality. */
bool ktc2_str_equals(const ktc2_String* a, const ktc2_String* b);
int32_t ktc2_str_compare(const ktc2_String* a, const ktc2_String* b);
int32_t ktc2_str_hashCode(const ktc2_String* s);

/** A new string from [length] UTF-16 code units of a CharArray, starting at [start]. */
ktc2_String* ktc2_stringOfChars(ktc2_Array* chars, int32_t start, int32_t length);

/** The UTF-16 code units [start, end) of a string, as a new string. */
ktc2_String* ktc2_stringSubstring(ktc2_String* s, int32_t start, int32_t end);
/** Parses as Double.parseDouble into the first element of a DoubleArray; false when not a number. */
bool ktc2_stringToDouble(ktc2_String* s, ktc2_Array* result);
/** The next line of stdin as a ByteArray, without its line end; NULL at the end of the input. */
ktc2_Array* ktc2_readLineBytes(void);

/** The UTF-8 bytes of a string, as a ByteArray. */
ktc2_Array* ktc2_stringUtf8(ktc2_String* s);
/** A string from the UTF-8 bytes [start, end) of a ByteArray. */
ktc2_String* ktc2_stringOfUtf8(ktc2_Array* bytes, int32_t start, int32_t end);

/** `Enum.valueOf` found no entry called [name]. */
_Noreturn void ktc2_fail_enum_value(const ktc2_String* name);

/** Defaults of kotlin.Any, also correct for strings. `toString` returns an owned reference. */
ktc2_String* ktc2_any_toString(ktc2_Obj* object);
bool ktc2_any_equals(ktc2_Obj* a, ktc2_Obj* b);
int32_t ktc2_any_hashCode(ktc2_Obj* object);
/** `CharSequence` members for the built-in implementation, String. */
int32_t ktc2_cs_length(ktc2_Obj* object);
uint16_t ktc2_cs_get(ktc2_Obj* object, int32_t index);
ktc2_Obj* ktc2_cs_subSequence(ktc2_Obj* object, int32_t start, int32_t end);
/** Reports a thrown exception and exits (until exceptions can be caught). [message] may be NULL. */
_Noreturn void ktc2_fail_throw(const ktc2_String* exception, const ktc2_String* message);
/** `Comparable.compareTo` for the built-in comparable types: strings and boxed primitives. */
int32_t ktc2_any_compareTo(ktc2_Obj* a, ktc2_Obj* b);

/** Builds a string piece by piece; starts on the stack and moves to the heap when it grows. */
typedef struct ktc2_Sb {
	char* data;
	int32_t size;
	int32_t capacity;
	int32_t length;
	char local[104];
} ktc2_Sb;

void ktc2_sb_init(ktc2_Sb* sb);
/** Appends UTF-8 bytes whose Kotlin length is [length]. */
void ktc2_sb_text(ktc2_Sb* sb, const char* text, int32_t size, int32_t length);
/** Appends a string, or "null". */
void ktc2_sb_string(ktc2_Sb* sb, const ktc2_String* s);
void ktc2_sb_bool(ktc2_Sb* sb, bool v);
void ktc2_sb_int(ktc2_Sb* sb, int32_t v);
void ktc2_sb_long(ktc2_Sb* sb, int64_t v);
void ktc2_sb_uint(ktc2_Sb* sb, uint32_t v);
void ktc2_sb_ulong(ktc2_Sb* sb, uint64_t v);
void ktc2_sb_char(ktc2_Sb* sb, uint16_t v);
void ktc2_sb_float(ktc2_Sb* sb, float v);
void ktc2_sb_double(ktc2_Sb* sb, double v);
/** Returns the built string (owned by the caller) and releases the builder. */
ktc2_String* ktc2_sb_finish(ktc2_Sb* sb);

/** Size of the block-local buffer a string that stays in its block is built in. */
#define KTC2_STACK_STRING_SIZE 128

/**
 * Like ktc2_sb_finish, but puts the string in [storage] (of [capacity] bytes, in the caller's
 * frame) when it fits; such a string must not outlive that frame.
 */
ktc2_String* ktc2_sb_finish_into(ktc2_Sb* sb, void* storage, size_t capacity);

/* ================== */
/* MARK: Primitives   */
/* ================== */

/** Three-way comparison, the result of Kotlin's `compareTo`. */
#define KTC2_CMP(a, b) (((a) > (b)) - ((a) < (b)))

/** Reports `ArithmeticException: / by zero` and exits. */
KTC2_FAILS void ktc2_fail_div_zero(void);

static inline int32_t ktc2_iadd(int32_t a, int32_t b) { return (int32_t)((uint32_t)a + (uint32_t)b); }
static inline int32_t ktc2_isub(int32_t a, int32_t b) { return (int32_t)((uint32_t)a - (uint32_t)b); }
static inline int32_t ktc2_imul(int32_t a, int32_t b) { return (int32_t)((uint32_t)a * (uint32_t)b); }
static inline int32_t ktc2_ineg(int32_t a) { return (int32_t)(0u - (uint32_t)a); }
static inline int32_t ktc2_ishl(int32_t a, int32_t n) { return (int32_t)((uint32_t)a << (n & 31)); }
static inline int32_t ktc2_ishr(int32_t a, int32_t n) { return a >> (n & 31); }
static inline int32_t ktc2_iushr(int32_t a, int32_t n) { return (int32_t)((uint32_t)a >> (n & 31)); }

static inline int32_t ktc2_idiv(int32_t a, int32_t b)
{
	if (b == 0) {
		ktc2_fail_div_zero();
		return 0;
	}
	return b == -1 ? ktc2_ineg(a) : a / b;
}

static inline int32_t ktc2_irem(int32_t a, int32_t b)
{
	if (b == 0) {
		ktc2_fail_div_zero();
		return 0;
	}
	return b == -1 ? 0 : a % b;
}

static inline int64_t ktc2_ladd(int64_t a, int64_t b) { return (int64_t)((uint64_t)a + (uint64_t)b); }
static inline int64_t ktc2_lsub(int64_t a, int64_t b) { return (int64_t)((uint64_t)a - (uint64_t)b); }
static inline int64_t ktc2_lmul(int64_t a, int64_t b) { return (int64_t)((uint64_t)a * (uint64_t)b); }
static inline int64_t ktc2_lneg(int64_t a) { return (int64_t)(0u - (uint64_t)a); }
static inline int64_t ktc2_lshl(int64_t a, int32_t n) { return (int64_t)((uint64_t)a << (n & 63)); }
static inline int64_t ktc2_lshr(int64_t a, int32_t n) { return a >> (n & 63); }
static inline int64_t ktc2_lushr(int64_t a, int32_t n) { return (int64_t)((uint64_t)a >> (n & 63)); }

static inline int64_t ktc2_ldiv(int64_t a, int64_t b)
{
	if (b == 0) {
		ktc2_fail_div_zero();
		return 0;
	}
	return b == -1 ? ktc2_lneg(a) : a / b;
}

static inline int64_t ktc2_lrem(int64_t a, int64_t b)
{
	if (b == 0) {
		ktc2_fail_div_zero();
		return 0;
	}
	return b == -1 ? 0 : a % b;
}

static inline uint32_t ktc2_uadd(uint32_t a, uint32_t b) { return a + b; }
static inline uint32_t ktc2_usub(uint32_t a, uint32_t b) { return a - b; }
static inline uint32_t ktc2_umul(uint32_t a, uint32_t b) { return a * b; }
static inline uint32_t ktc2_ushl(uint32_t a, int32_t n) { return a << (n & 31); }
static inline uint32_t ktc2_ushr(uint32_t a, int32_t n) { return a >> (n & 31); }

static inline uint32_t ktc2_udiv(uint32_t a, uint32_t b)
{
	if (b == 0) {
		ktc2_fail_div_zero();
		return 0;
	}
	return a / b;
}

static inline uint32_t ktc2_urem(uint32_t a, uint32_t b)
{
	if (b == 0) {
		ktc2_fail_div_zero();
		return 0;
	}
	return a % b;
}

static inline uint64_t ktc2_uladd(uint64_t a, uint64_t b) { return a + b; }
static inline uint64_t ktc2_ulsub(uint64_t a, uint64_t b) { return a - b; }
static inline uint64_t ktc2_ulmul(uint64_t a, uint64_t b) { return a * b; }
static inline uint64_t ktc2_ulshl(uint64_t a, int32_t n) { return a << (n & 63); }
static inline uint64_t ktc2_ulshr(uint64_t a, int32_t n) { return a >> (n & 63); }

static inline uint64_t ktc2_uldiv(uint64_t a, uint64_t b)
{
	if (b == 0) {
		ktc2_fail_div_zero();
		return 0;
	}
	return a / b;
}

static inline uint64_t ktc2_ulrem(uint64_t a, uint64_t b)
{
	if (b == 0) {
		ktc2_fail_div_zero();
		return 0;
	}
	return a % b;
}

/* Last element of a stepped progression (kotlin.internal.getProgressionLastElement). */

/** Reports `IllegalArgumentException: Step must be positive` and exits when step <= 0. */
void ktc2_check_step(int64_t step);

static inline int32_t ktc2_imod(int32_t a, int32_t b)
{
	int32_t m = a % b;
	return m >= 0 ? m : m + b;
}

/** For start <= end and step > 0: the last value reached from start that is <= end. */
static inline int32_t ktc2_irange_last_up(int32_t start, int32_t end, int32_t step)
{
	return end - ktc2_imod(ktc2_imod(end, step) - ktc2_imod(start, step), step);
}

/** For start >= end and step > 0: the last value reached downward from start that is >= end. */
static inline int32_t ktc2_irange_last_down(int32_t start, int32_t end, int32_t step)
{
	return end + ktc2_imod(ktc2_imod(start, step) - ktc2_imod(end, step), step);
}

static inline int64_t ktc2_lmod(int64_t a, int64_t b)
{
	int64_t m = a % b;
	return m >= 0 ? m : m + b;
}

static inline int64_t ktc2_lrange_last_up(int64_t start, int64_t end, int64_t step)
{
	return end - ktc2_lmod(ktc2_lmod(end, step) - ktc2_lmod(start, step), step);
}

static inline int64_t ktc2_lrange_last_down(int64_t start, int64_t end, int64_t step)
{
	return end + ktc2_lmod(ktc2_lmod(start, step) - ktc2_lmod(end, step), step);
}

/** `Double.toInt()`: NaN is 0, out-of-range values saturate. */
static inline int32_t ktc2_d2i(double v)
{
	if (v != v) return 0;
	if (v >= 2147483647.0) return INT32_MAX;
	if (v <= -2147483648.0) return INT32_MIN;
	return (int32_t)v;
}

/** `Double.toLong()`: NaN is 0, out-of-range values saturate. */
static inline int64_t ktc2_d2l(double v)
{
	if (v != v) return 0;
	if (v >= 9223372036854775808.0) return INT64_MAX;
	if (v <= -9223372036854775808.0) return INT64_MIN;
	return (int64_t)v;
}

/** `Double.toUInt()`: NaN and negatives are 0, large values saturate. */
static inline uint32_t ktc2_d2u(double v)
{
	if (!(v > 0)) return 0;
	if (v >= 4294967295.0) return UINT32_MAX;
	return (uint32_t)v;
}

/** `Double.toULong()`: NaN and negatives are 0, large values saturate. */
static inline uint64_t ktc2_d2ul(double v)
{
	if (!(v > 0)) return 0;
	if (v >= 18446744073709551616.0) return UINT64_MAX;
	return (uint64_t)v;
}

/* Output, formatted exactly as Kotlin's print / println. */
void ktc2_print_text(const char* text, int32_t size, int32_t length);
/** Prints a string, or "null". */
void ktc2_print_string(const ktc2_String* s);
void ktc2_print_bool(bool v);
void ktc2_print_int(int32_t v);
void ktc2_print_long(int64_t v);
void ktc2_print_uint(uint32_t v);
void ktc2_print_ulong(uint64_t v);
void ktc2_print_char(uint16_t v);
void ktc2_print_float(float v);
void ktc2_print_double(double v);

#endif
