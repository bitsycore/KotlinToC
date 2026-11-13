#include "ktc2_core.h"

#include <inttypes.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#ifdef _WIN32
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#endif

/* ================== */
/* MARK: Failures     */
/* ================== */

static _Noreturn void fail(const char* exception, const char* message)
{
	fflush(stdout);
	fprintf(stderr, "Exception in thread \"main\" %s%s%s\n", exception, message ? ": " : "", message ? message : "");
	exit(1);
}

#ifdef KTC2_CHECKS_THROW
/* `ktc2.internal`, generated with the program: each throws the exception Kotlin specifies. */
void ktc2_internal_throwIndexOutOfBounds(int32_t index, int32_t size);
void ktc2_internal_throwStringIndexOutOfBounds(int32_t index, int32_t length);
void ktc2_internal_throwSubstringRange(int32_t begin, int32_t end, int32_t length);
void ktc2_internal_throwDivideByZero(void);
#endif

KTC2_FAILS void ktc2_fail_div_zero(void)
{
#ifdef KTC2_CHECKS_THROW
	ktc2_internal_throwDivideByZero();
#else
	fail("java.lang.ArithmeticException", "/ by zero");
#endif
}

_Noreturn void ktc2_fail_null(void)
{
	fail("java.lang.NullPointerException", NULL);
}

_Noreturn void ktc2_fail_cast(void)
{
	fail("java.lang.ClassCastException", NULL);
}

_Noreturn void ktc2_fail_dispatch(void)
{
	fail("java.lang.AbstractMethodError", NULL);
}

_Noreturn void ktc2_fail_dispatch_of(const char* function, uint32_t typeId)
{
	char text[256];
	snprintf(text, sizeof text, "%s on %s", function, typeId == 0 ? "null" : typeId < ktc2_type_count ? ktc2_types[typeId].name : "?");
	fail(typeId == 0 ? "java.lang.NullPointerException" : "java.lang.AbstractMethodError", text);
}

_Noreturn void ktc2_fail_when(void)
{
	fail("kotlin.NoWhenBranchMatchedException", NULL);
}

void ktc2_check_step(int64_t step)
{
	if (step > 0) return;
	fflush(stdout);
	fprintf(stderr, "Exception in thread \"main\" java.lang.IllegalArgumentException: Step must be positive, was: %" PRId64 ".\n", step);
	exit(1);
}

/* ================== */
/* MARK: Objects      */
/* ================== */

#ifdef KTC2_THREADS
int ktc2_threaded = 0;

#ifdef _WIN32
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#define thread_yield() Sleep(0)
#else
#include <pthread.h>
#include <sched.h>
#define thread_yield() sched_yield()
#endif

/** Guards the runtime's own tables (weak cells, leak counts): held for a few instructions only. */
static uint32_t runtime_lock;

static void runtime_enter(void)
{
	while (ktc2_atomic_exchange(&runtime_lock, 1)) thread_yield();
}

static void runtime_leave(void)
{
	ktc2_atomic_exchange(&runtime_lock, 0);
}

#define KTC2_RC_OR(object, bits) ktc2_atomic_or(&(object)->rc, (bits))
#define KTC2_RC_AND(object, bits) ktc2_atomic_and(&(object)->rc, (bits))
#else
#define runtime_enter() ((void)0)
#define runtime_leave() ((void)0)
#define KTC2_RC_OR(object, bits) ((void)((object)->rc |= (bits)))
#define KTC2_RC_AND(object, bits) ((void)((object)->rc &= (bits)))
#endif

#ifdef KTC2_LEAK_CHECK
/** Live objects per type id. */
static int64_t* live_counts;

static void count_live(uint32_t typeId, int delta)
{
	runtime_enter();
	if (!live_counts) live_counts = calloc(ktc2_type_count, sizeof *live_counts);
	if (live_counts && typeId < ktc2_type_count) live_counts[typeId] += delta;
	runtime_leave();
}
#else
#define count_live(typeId, delta) ((void)0)
#endif

#ifdef KTC2_ARC_STATS
uint64_t ktc2_stat_retains;
uint64_t ktc2_stat_releases;
static uint64_t stat_allocs;
#endif

/*
 * An arena is a list of blocks objects are carved out of. Arena objects are counted like any
 * other, so nothing about them is unsafe: at a count of zero an object gives up what it
 * refers to, and tells its block instead of calling free. A block is freed when the arena has
 * ended and no object of it is still alive - normally the moment the arena ends.
 */
typedef struct ArenaBlock {
	struct ArenaBlock* next;
	size_t used;
	size_t size;
	uint32_t holds;   /* live objects of the block, plus one for the arena while it is open */
} ArenaBlock;

typedef struct Arena {
	struct Arena* outer;
	ArenaBlock* blocks;
	size_t blockSize;
} Arena;

/** Rounded so that what follows a block header or an object's back pointer stays aligned. */
#define ARENA_ALIGN(size) (((size) + 15u) & ~(size_t)15u)
#define ARENA_PREFIX ARENA_ALIGN(sizeof(ArenaBlock*))

/** The innermost arena open on this thread, or NULL. */
static KTC2_TLS Arena* arena_top;

#ifdef KTC2_ARC_STATS
static uint64_t stat_arena_allocs;
#endif

static void arena_block_drop(ArenaBlock* block)
{
#ifdef KTC2_THREADS
	if (ktc2_atomic_sub(&block->holds, 1) == 0) free(block);
#else
	if (--block->holds == 0) free(block);
#endif
}

static ktc2_Obj* arena_alloc(Arena* arena, size_t size)
{
	size_t need = ARENA_PREFIX + ARENA_ALIGN(size);
	ArenaBlock* block = arena->blocks;
	if (!block || block->size - block->used < need) {
		size_t blockSize = arena->blockSize > need ? arena->blockSize : need;
		block = malloc(ARENA_ALIGN(sizeof *block) + blockSize);
		if (!block) fail("java.lang.OutOfMemoryError", NULL);
		block->next = arena->blocks;
		block->used = 0;
		block->size = blockSize;
		block->holds = 1;
		arena->blocks = block;
	}
	char* slot = (char*)block + ARENA_ALIGN(sizeof *block) + block->used;
	block->used += need;
#ifdef KTC2_THREADS
	ktc2_atomic_add(&block->holds, 1);
#else
	block->holds++;
#endif
	/* The object remembers its block in the word before it. */
	*(ArenaBlock**)slot = block;
	ktc2_Obj* object = (ktc2_Obj*)(slot + ARENA_PREFIX);
	memset(object, 0, size);
	return object;
}

int64_t ktc2_arenaEnter(int32_t size)
{
	Arena* arena = malloc(sizeof *arena);
	if (!arena) fail("java.lang.OutOfMemoryError", NULL);
	arena->outer = arena_top;
	arena->blocks = NULL;
	arena->blockSize = size > 256 ? (size_t)size : 256;
	arena_top = arena;
	return (int64_t)(intptr_t)arena;
}

void ktc2_arenaLeave(int64_t handle)
{
	Arena* arena = (Arena*)(intptr_t)handle;
	arena_top = arena->outer;
	for (ArenaBlock* block = arena->blocks; block;) {
		ArenaBlock* next = block->next;
		arena_block_drop(block);
		block = next;
	}
	free(arena);
}

/* ================== */
/* MARK: Small objects */
/* ================== */

/*
 * Most objects are small, of a class, and do not live long. Those of a type with one size
 * (ktc2_TypeInfo.size) of at most POOL_MAX bytes do not go through malloc and free each time:
 * a freed one is put on a list of its size, kept per thread, and the next object of that size
 * takes it from there; a list that is empty is served from a large piece of memory cut up as
 * needed. Taking and giving back are a few instructions and need no lock. The memory is not
 * returned to the system before the program ends; what a thread leaves when it ends goes to
 * lists the other threads take from.
 *
 * KTC2_NO_POOL turns this off: every object is then a block of malloc's, which is what a tool
 * that watches the heap (AddressSanitizer) wants to see.
 */
#define POOL_STEP 16
#define POOL_MAX 128
#define POOL_SIZES (POOL_MAX / POOL_STEP)
#define POOL_PIECE (64 * 1024)

typedef struct PoolBlock {
	struct PoolBlock* next;
} PoolBlock;

static KTC2_TLS PoolBlock* pool_free[POOL_SIZES];
static KTC2_TLS char* pool_room;
static KTC2_TLS size_t pool_left;

#ifdef KTC2_THREADS
/** The free blocks of the threads that have ended, by size: guarded by the runtime's lock. */
static PoolBlock* pool_orphans[POOL_SIZES];
#endif

/** The size the blocks of [typeId] have in the pool, or 0 when its objects come from malloc. */
static inline size_t pool_size(uint32_t typeId)
{
#ifdef KTC2_NO_POOL
	(void)typeId;
	return 0;
#else
	uint32_t size = ktc2_types[typeId].size;
	return size <= POOL_MAX ? size : 0;
#endif
}

/**
 * The size in the pool of the block of [object], or 0 when it is a block of malloc's. A string
 * or an array is as large as it says itself; it is in the pool when that is small enough.
 */
static inline size_t pool_size_of(const ktc2_Obj* object)
{
#ifdef KTC2_NO_POOL
	(void)object;
	return 0;
#else
	size_t size;
	if (object->typeId == KTC2_TYPE_STRING) {
		size = sizeof(ktc2_String) + (size_t)((const ktc2_String*)object)->size + 1;
	} else if (object->typeId <= KTC2_TYPE_ARRAY_VALUE) {
		const ktc2_Array* array = (const ktc2_Array*)object;
		size = sizeof(ktc2_Array) + (size_t)(array->length > 0 ? array->length : 1) * (size_t)array->elementSize;
	} else {
		size = ktc2_types[object->typeId].size;
	}
	return size <= POOL_MAX ? size : 0;
#endif
}

/** A block for an object of [size] bytes (1..POOL_MAX), not zeroed. */
static void* pool_take(size_t size)
{
	size_t index = (size - 1) / POOL_STEP;
	PoolBlock* block = pool_free[index];
#ifdef KTC2_THREADS
	if (!block && pool_orphans[index]) {
		runtime_enter();
		block = pool_orphans[index];
		pool_orphans[index] = NULL;
		runtime_leave();
	}
#endif
	if (block) {
		pool_free[index] = block->next;
		return block;
	}
	size_t rounded = (index + 1) * POOL_STEP;
	if (pool_left < rounded) {
		/* What is left of the last piece is too small for this size: it is given up. */
		pool_room = malloc(POOL_PIECE);
		if (!pool_room) fail("java.lang.OutOfMemoryError", NULL);
		pool_left = POOL_PIECE;
	}
	void* taken = pool_room;
	pool_room += rounded;
	pool_left -= rounded;
	return taken;
}

static void pool_give(void* memory, size_t size)
{
	size_t index = (size - 1) / POOL_STEP;
	PoolBlock* block = memory;
	block->next = pool_free[index];
	pool_free[index] = block;
}

#ifdef KTC2_THREADS
/** Hands the free blocks of the thread that is ending to the others. */
static void pool_leave(void)
{
	runtime_enter();
	for (size_t index = 0; index < POOL_SIZES; index++) {
		PoolBlock* block = pool_free[index];
		if (!block) continue;
		PoolBlock* last = block;
		while (last->next) last = last->next;
		last->next = pool_orphans[index];
		pool_orphans[index] = block;
		pool_free[index] = NULL;
	}
	runtime_leave();
}
#endif

void* ktc2_alloc(size_t size, uint32_t typeId)
{
#ifdef KTC2_ARC_STATS
	KTC2_STAT_INC(stat_allocs);
#endif
	ktc2_Obj* object;
	if (arena_top) {
#ifdef KTC2_ARC_STATS
		KTC2_STAT_INC(stat_arena_allocs);
#endif
		object = arena_alloc(arena_top, size);
		object->rc = KTC2_ARENA | 1;
	} else {
		size_t pooled = pool_size(typeId);
#ifndef KTC2_NO_POOL
		/* A small string or array: its size is found again in the object when it is freed (pool_size_of). */
		if (typeId <= KTC2_TYPE_ARRAY_VALUE && size <= POOL_MAX) pooled = size;
#endif
		if (pooled) {
			/* Where the object goes back to is decided by its type alone: it must be of the size of its type. */
			if (size > pooled) fail("java.lang.InternalError", "an object larger than its type");
			object = pool_take(pooled);
			memset(object, 0, pooled);
		} else {
			object = calloc(1, size);
			if (!object) fail("java.lang.OutOfMemoryError", NULL);
		}
		object->rc = 1;
	}
	object->typeId = typeId;
	count_live(typeId, 1);
	return object;
}

static void weak_drop(ktc2_Obj* target);

/* The boxes of the small values: static, immortal, written here once. */
#define BOX_1(kind, id, value) { { KTC2_IMMORTAL, id }, { .kind##_ = (value) } }
#define BOX_4(kind, id, value) BOX_1(kind, id, value), BOX_1(kind, id, (value) + 1), BOX_1(kind, id, (value) + 2), BOX_1(kind, id, (value) + 3)
#define BOX_16(kind, id, value) BOX_4(kind, id, value), BOX_4(kind, id, (value) + 4), BOX_4(kind, id, (value) + 8), BOX_4(kind, id, (value) + 12)
#define BOX_64(kind, id, value) BOX_16(kind, id, value), BOX_16(kind, id, (value) + 16), BOX_16(kind, id, (value) + 32), BOX_16(kind, id, (value) + 48)
#define BOX_128(kind, id, value) BOX_64(kind, id, value), BOX_64(kind, id, (value) + 64)
#define BOX_256(kind, id, value) BOX_128(kind, id, value), BOX_128(kind, id, (value) + 128)

ktc2_Box ktc2_box_cache_boolean[2] = { BOX_1(boolean, KTC2_TYPE_BOX_BOOLEAN, false), BOX_1(boolean, KTC2_TYPE_BOX_BOOLEAN, true) };
ktc2_Box ktc2_box_cache_int[KTC2_BOX_CACHE_SIZE] = { BOX_256(int, KTC2_TYPE_BOX_INT, KTC2_BOX_CACHE_LOW) };
ktc2_Box ktc2_box_cache_long[KTC2_BOX_CACHE_SIZE] = { BOX_256(long, KTC2_TYPE_BOX_LONG, KTC2_BOX_CACHE_LOW) };
ktc2_Box ktc2_box_cache_char[KTC2_BOX_CACHE_SIZE / 2] = { BOX_128(char, KTC2_TYPE_BOX_CHAR, 0) };

ktc2_Obj* ktc2_box_value(const void* data, size_t size, uint32_t typeId, size_t offset)
{
	ktc2_Obj* object = ktc2_alloc(offset + size, typeId);
	memcpy((char*)object + offset, data, size);
	return object;
}

/*
 * With KTC2_LEAK_CHECK a freed object is first filled with 0xDD, so that code which still
 * uses it (a reference that was not counted) reads nonsense and fails instead of appearing to work.
 */
#ifdef KTC2_LEAK_CHECK
#if defined(_WIN32)
#include <malloc.h>
#define block_size(object) _msize(object)
#elif defined(__APPLE__)
#include <malloc/malloc.h>
#define block_size(object) malloc_size(object)
#elif defined(__GLIBC__)
#include <malloc.h>
#define block_size(object) malloc_usable_size(object)
#else
#define block_size(object) sizeof(ktc2_Obj)
#endif

/*
 * The memory is not given back at once either: the allocator would write into it or hand it
 * out again, and the fill would be gone. The last freed objects wait here, still filled, so
 * that a retain or a release of one of them is recognized (ktc2_fail_freed).
 */
#define RETIRED_COUNT 4096
#define RETIRED_MAX_SIZE 65536

/** A freed object that waits: [pooled] is its size in the pool, 0 when it is a block of malloc's. */
typedef struct {
	void* memory;
	size_t pooled;
} Retired;

static Retired retired[RETIRED_COUNT];
static uint32_t retired_at;

static void retire(ktc2_Obj* object)
{
	size_t pooled = pool_size_of(object);
	size_t size = pooled ? pooled : block_size(object);
	memset(object, 0xDD, size);
	if (size > RETIRED_MAX_SIZE) {
		free(object);
		return;
	}
	runtime_enter();
	Retired oldest = retired[retired_at];
	retired[retired_at] = (Retired){ object, pooled };
	retired_at = (retired_at + 1) % RETIRED_COUNT;
	runtime_leave();
	if (oldest.pooled) pool_give(oldest.memory, oldest.pooled); else free(oldest.memory);
}

void ktc2_fail_freed(void)
{
	fflush(stdout);
	fprintf(stderr, "ktc2: use of a freed object: a reference was not counted\n");
	abort();
}
#else
static inline void retire(ktc2_Obj* object)
{
	size_t pooled = pool_size_of(object);
	if (pooled) pool_give(object, pooled); else free(object);
}
#endif

/*
 * Counts the strings with characters beyond ASCII that were destroyed. A position remembered
 * in such a string (see `place_in`) is only good while this has not moved: the memory of a
 * string that is gone may hold another one by now.
 */
static uint32_t strings_gone;

static void threads_wait(void);

/** Set when the program is over: see ktc2_ending. */
static int ending;

void ktc2_ending(void)
{
	threads_wait();
	ending = 1;
}

/**
 * Calls the `deinit` of [object], whose count has reached zero. The object is whole while it
 * runs and counts as held once, so the code may take references to it and give them back. One
 * that is still there afterwards would refer to an object that is gone: that ends the program,
 * and so does an exception, which has nowhere to go. An exception already in flight (the
 * object is given up on the way out of a function that threw) is set aside for the call.
 */
static void deinit_run(ktc2_Obj* object, void (*deinit)(ktc2_Obj*))
{
	uint32_t count = KTC2_RC_LOAD(object);
	KTC2_RC_INC(object, count);
	ktc2_Obj* pending = ktc2_pending;
	ktc2_pending = NULL;
	deinit(object);
	if (ktc2_pending) ktc2_uncaught();
	ktc2_pending = pending;
	count = KTC2_RC_LOAD(object);
	if ((KTC2_RC_DEC(object, count) & KTC2_COUNT_MASK) != 0) {
		fflush(stdout);
		fprintf(stderr, "ktc2: an object of type %s is still referred to after its deinit\n", ktc2_types[object->typeId].name);
		abort();
	}
}

/*
 * Destroying an object releases what it refers to, which destroys that in turn: a list of a
 * million cells would need a million frames of the C stack. Once the stack has grown by
 * DESTROY_STACK bytes since the shallowest destruction seen on this thread, an object is put
 * aside instead, and destroyed by the first destruction that gets back below that. An object
 * in a frame is never put aside: its memory does not wait.
 */
#define DESTROY_STACK (96 * 1024)

/** The address of a local of the shallowest ktc2_destroy so far (the stack grows downwards). */
static KTC2_TLS char* destroy_base;
static KTC2_TLS ktc2_Obj** destroy_later;
static KTC2_TLS size_t destroy_later_count;
static KTC2_TLS size_t destroy_later_room;

KTC2_ALWAYS_INLINE void destroy_now(ktc2_Obj* object);

/** Puts [object] aside: the stack is deep. */
static void destroy_put_aside(ktc2_Obj* object)
{
	if (destroy_later_count == destroy_later_room) {
		destroy_later_room = destroy_later_room ? destroy_later_room * 2 : 64;
		destroy_later = realloc(destroy_later, destroy_later_room * sizeof *destroy_later);
		if (!destroy_later) abort();
	}
	destroy_later[destroy_later_count++] = object;
}

/** Destroys what was put aside, and what that puts aside in turn. */
static void destroy_rest(void);

void ktc2_destroy(ktc2_Obj* object)
{
	char* here = ktc2_frame_address();
	char* base = destroy_base;
	if (ktc2_seldom(here > base)) {
		destroy_base = here;
	} else if (ktc2_seldom((size_t)(base - here) > DESTROY_STACK) && !(object->rc & KTC2_STACK)) {
		destroy_put_aside(object);
		return;
	}
	destroy_now(object);
	if (ktc2_seldom(destroy_later_count != 0)) destroy_rest();
}

KTC2_ALWAYS_INLINE void destroy_now(ktc2_Obj* object)
{
	/* A weak reference reads null from the moment the count is zero, also inside `deinit`. */
	if (object->rc & KTC2_WEAK) weak_drop(object);
	void (*deinit)(ktc2_Obj*) = ktc2_types[object->typeId].deinit;
	if (deinit && !ending) {
		KTC2_RC_AND(object, ~KTC2_WEAK);
		deinit_run(object, deinit);
		/* A weak reference made by `deinit` itself. */
		if (object->rc & KTC2_WEAK) weak_drop(object);
	}
	if (object->typeId == KTC2_TYPE_STRING && ((ktc2_String*)object)->size != ((ktc2_String*)object)->length) {
		(void)ktc2_atomic_add(&strings_gone, 1);
	}
	void (*destroy)(ktc2_Obj*) = ktc2_types[object->typeId].destroy;
	if (destroy) destroy(object);
	/* A stack object only gives up what it refers to; its memory belongs to a frame. */
	if (object->rc & KTC2_STACK) return;
	count_live(object->typeId, -1);
	if (object->rc & KTC2_ARENA) {
		arena_block_drop(*(ArenaBlock**)((char*)object - ARENA_PREFIX));
		return;
	}
	retire(object);
}

static void destroy_rest(void)
{
	while (destroy_later_count != 0) destroy_now(destroy_later[--destroy_later_count]);
}

int ktc2_exit(void)
{
	/* As on the JVM, the program ends when its last thread does. */
	threads_wait();
	fflush(stdout);
#ifdef KTC2_ARC_STATS
	fprintf(stderr, "ktc2: arc: allocs=%" PRIu64 " retains=%" PRIu64 " releases=%" PRIu64 " arena=%" PRIu64 "\n", stat_allocs, ktc2_stat_retains, ktc2_stat_releases, stat_arena_allocs);
#endif
#ifdef KTC2_LEAK_CHECK
	for (uint32_t id = 0; live_counts && id < ktc2_type_count; id++) {
		if (live_counts[id] != 0) {
			fprintf(stderr, "ktc2: leak: %" PRId64 " object(s) of type %s\n", live_counts[id], ktc2_types[id].name);
		}
	}
	free(live_counts);
#endif
	return 0;
}

/* ================== */
/* MARK: Arrays       */
/* ================== */

/** A range that does not fit its array, in a runtime function: always fatal. */
static _Noreturn void fail_range(int32_t index, int32_t length)
{
	fflush(stdout);
	fprintf(stderr, "Exception in thread \"main\" java.lang.ArrayIndexOutOfBoundsException: Index %" PRId32 " out of bounds for length %" PRId32 "\n", index, length);
	exit(1);
}

KTC2_FAILS void ktc2_fail_index(int32_t index, int32_t length)
{
#ifdef KTC2_CHECKS_THROW
	ktc2_internal_throwIndexOutOfBounds(index, length);
#else
	fail_range(index, length);
#endif
}

int32_t ktc2_arrayKind(ktc2_Obj* object)
{
	if (!object) return 0;
	if (object->typeId == KTC2_TYPE_ARRAY_REF) return 1;
	if (object->typeId >= KTC2_TYPE_ARRAY_BOOLEAN && object->typeId <= KTC2_TYPE_ARRAY_DOUBLE) return 2 + (int32_t)(object->typeId - KTC2_TYPE_ARRAY_BOOLEAN);
	return 0;
}

ktc2_Array* ktc2_array_new(int32_t length, size_t elementSize, uint32_t typeId)
{
	if (length < 0) {
		char text[16];
		snprintf(text, sizeof text, "%" PRId32, length);
		fail("java.lang.NegativeArraySizeException", text);
	}
	/* Room for at least one element, so that a read with a bad index has something to yield. */
	ktc2_Array* array = ktc2_alloc(sizeof(ktc2_Array) + (size_t)(length > 0 ? length : 1) * elementSize, typeId);
	array->length = length;
	array->elementSize = (int32_t)elementSize;
	return array;
}

void ktc2_array_assign(ktc2_Array* slot, int32_t length, uint32_t typeId, size_t elementSize, const ktc2_Array* source)
{
	if (slot == source) return;
	if (source->length != length) {
		char text[96];
		snprintf(text, sizeof text, "an array of %" PRId32 " elements assigned to inline storage of %" PRId32, source->length, length);
		fail("java.lang.IllegalArgumentException", text);
	}
	void** incoming = (void**)(source + 1);
	void** current = (void**)(slot + 1);
	if (typeId == KTC2_TYPE_ARRAY_REF) {
		for (int32_t i = 0; i < length; i++) ktc2_retain(incoming[i]);
		for (int32_t i = 0; i < slot->length; i++) ktc2_release(current[i]);
	}
	memcpy(current, incoming, (size_t)length * elementSize);
	slot->header.rc = KTC2_IMMORTAL;
	slot->header.typeId = typeId;
	slot->length = length;
	slot->elementSize = (int32_t)elementSize;
}

void ktc2_array_destroy(ktc2_Obj* object)
{
	ktc2_Array* array = (ktc2_Array*)object;
	void** elements = (void**)(array + 1);
	for (int32_t i = 0; i < array->length; i++) ktc2_release(elements[i]);
}

/* ================== */
/* MARK: Strings      */
/* ================== */

/** Decodes one code point at [*at] (valid UTF-8 assumed) and advances. */
static uint32_t next_code_point(const char** at)
{
	const unsigned char* p = (const unsigned char*)*at;
	uint32_t c = p[0];
	if (c < 0x80) {
		*at += 1;
	} else if (c < 0xE0) {
		c = ((c & 0x1F) << 6) | (p[1] & 0x3F);
		*at += 2;
	} else if (c < 0xF0) {
		c = ((c & 0x0F) << 12) | ((p[1] & 0x3F) << 6) | (p[2] & 0x3F);
		*at += 3;
	} else {
		c = ((c & 0x07) << 18) | ((p[1] & 0x3F) << 12) | ((p[2] & 0x3F) << 6) | (p[3] & 0x3F);
		*at += 4;
	}
	return c;
}

/** Walks a string as UTF-16 code units. */
typedef struct {
	const char* at;
	const char* end;
	uint16_t pending; /* low surrogate still to deliver, or 0 */
} UnitCursor;

static UnitCursor cursor_of(const ktc2_String* s)
{
	return (UnitCursor){ s->bytes, s->bytes + s->size, 0 };
}

static bool cursor_has(const UnitCursor* cursor)
{
	return cursor->pending || cursor->at < cursor->end;
}

static uint16_t cursor_next(UnitCursor* cursor)
{
	if (cursor->pending) {
		uint16_t low = cursor->pending;
		cursor->pending = 0;
		return low;
	}
	uint32_t c = next_code_point(&cursor->at);
	if (c < 0x10000) return (uint16_t)c;
	c -= 0x10000;
	cursor->pending = (uint16_t)(0xDC00 | (c & 0x3FF));
	return (uint16_t)(0xD800 | (c >> 10));
}

/*
 * In a string that is not all ASCII, the place of a character is found by walking the bytes.
 * A loop over the characters asks for 0, 1, 2...: where the last walk ended is remembered, per
 * thread, and the next one starts there when it is for the same string and not further back.
 */
typedef struct {
	const ktc2_String* string;
	uint32_t gone;   /* strings_gone when this was noted */
	int32_t unit;    /* index of a UTF-16 unit that starts a code point */
	int32_t offset;  /* the byte where that code point starts */
} Place;

/* Two of them: code that compares two strings character by character asks for one, then the other. */
static KTC2_TLS Place last_places[2];
static KTC2_TLS int last_place_used;

/** Where to start walking [s] to reach unit [index]: the remembered place when it serves, else the beginning. */
static const char* place_in(const ktc2_String* s, int32_t index, int32_t* unit)
{
	for (int which = 0; which < 2; which++) {
		const Place* place = &last_places[which];
		if (place->string == s && place->unit <= index && place->gone == ktc2_atomic_load(&strings_gone)) {
			last_place_used = which;
			*unit = place->unit;
			return s->bytes + place->offset;
		}
	}
	*unit = 0;
	return s->bytes;
}

static void place_note(const ktc2_String* s, int32_t unit, const char* at)
{
	int which = last_places[0].string == s ? 0 : last_places[1].string == s ? 1 : !last_place_used;
	last_places[which] = (Place){ s, ktc2_atomic_load(&strings_gone), unit, (int32_t)(at - s->bytes) };
	last_place_used = which;
}

uint16_t ktc2_str_get(const ktc2_String* s, int32_t index)
{
	if (index < 0 || index >= s->length) {
#ifdef KTC2_CHECKS_THROW
		ktc2_internal_throwStringIndexOutOfBounds(index, s->length);
		return 0;
#endif
		fflush(stdout);
		fprintf(stderr, "Exception in thread \"main\" java.lang.StringIndexOutOfBoundsException: Index %" PRId32 " out of bounds for length %" PRId32 "\n", index, s->length);
		exit(1);
	}
	if (s->size == s->length) return (unsigned char)s->bytes[index];
	int32_t unit;
	const char* at = place_in(s, index, &unit);
	for (;;) {
		const char* start = at;
		uint32_t point = next_code_point(&at);
		int32_t width = point >= 0x10000 ? 2 : 1;
		if (index < unit + width) {
			place_note(s, unit, start);
			if (width == 1) return (uint16_t)point;
			point -= 0x10000;
			return index == unit ? (uint16_t)(0xD800 | (point >> 10)) : (uint16_t)(0xDC00 | (point & 0x3FF));
		}
		unit += width;
	}
}

bool ktc2_str_equals(const ktc2_String* a, const ktc2_String* b)
{
	if (a == b) return true;
	if (!a || !b) return false;
	return a->size == b->size && memcmp(a->bytes, b->bytes, (size_t)a->size) == 0;
}

int32_t ktc2_str_compare(const ktc2_String* a, const ktc2_String* b)
{
	if (a->size == a->length && b->size == b->length) {
		/* ASCII: a character is a byte. */
		int32_t shared = a->length < b->length ? a->length : b->length;
		for (int32_t index = 0; index < shared; index++) {
			if (a->bytes[index] != b->bytes[index]) return (unsigned char)a->bytes[index] - (unsigned char)b->bytes[index];
		}
		return a->length - b->length;
	}
	UnitCursor ca = cursor_of(a);
	UnitCursor cb = cursor_of(b);
	while (cursor_has(&ca) && cursor_has(&cb)) {
		int32_t ua = cursor_next(&ca);
		int32_t ub = cursor_next(&cb);
		if (ua != ub) return ua - ub;
	}
	return a->length - b->length;
}

int32_t ktc2_str_hashCode(const ktc2_String* s)
{
	uint32_t hash = 0;
	if (s->size == s->length) {
		for (int32_t index = 0; index < s->size; index++) hash = 31u * hash + (unsigned char)s->bytes[index];
		return (int32_t)hash;
	}
	UnitCursor cursor = cursor_of(s);
	while (cursor_has(&cursor)) hash = 31u * hash + cursor_next(&cursor);
	return (int32_t)hash;
}

_Noreturn void ktc2_fail_enum_value(const ktc2_String* name)
{
	fflush(stdout);
	fprintf(stderr, "Exception in thread \"main\" java.lang.IllegalArgumentException: No enum constant %s\n", name->bytes);
	exit(1);
}

int32_t ktc2_hash_float(float v)
{
	if (v != v) return 0x7FC00000;
	uint32_t bits;
	memcpy(&bits, &v, sizeof bits);
	return (int32_t)bits;
}

int32_t ktc2_hash_double(double v)
{
	uint64_t bits = UINT64_C(0x7FF8000000000000);
	if (v == v) memcpy(&bits, &v, sizeof bits);
	return (int32_t)(bits ^ (bits >> 32));
}

static ktc2_String* new_string(const char* bytes, int32_t size, int32_t length)
{
	ktc2_String* s = ktc2_alloc(sizeof(ktc2_String) + (size_t)size + 1, KTC2_TYPE_STRING);
	s->size = size;
	s->length = length;
	memcpy(s->bytes, bytes, (size_t)size);
	return s;
}

/* ================== */
/* MARK: Formatting   */
/* ================== */

/*
 * Half of a surrogate pair on its own is held as the three bytes UTF-8 would give that number
 * (0xED, then 0xA0 to 0xBF: "WTF-8"), so that a string holds any sequence of UTF-16 units. Two
 * halves that meet are written as the one character they are (see ktc2_sb_text): a text has
 * one form, and equal strings equal bytes. What leaves the program (stdout, the bytes of
 * `encodeToByteArray`) has `?` in its place, as on the JVM.
 */

/** The first half of a pair on its own in [from, end), or NULL. */
static const char* lone_surrogate(const char* from, const char* end)
{
	while (from < end) {
		const char* at = memchr(from, 0xED, (size_t)(end - from));
		if (!at || at + 1 >= end) return NULL;
		if ((unsigned char)at[1] >= 0xA0) return at;
		from = at + 1;
	}
	return NULL;
}

/*
 * The first half of a pair that ended what was last written: it waits for what is written
 * next, as in the JVM's stream. The second half makes the character; anything else, a `?`.
 * What still waits when the program ends is not written, as there.
 */
static KTC2_TLS unsigned char waiting_half[3];

/** Writes text to stdout with `?` for each half of a pair on its own. */
static void write_text(const char* text, int32_t size)
{
	if (size <= 0) return;
	if (waiting_half[0]) {
		const unsigned char* next = (const unsigned char*)text;
		if (size >= 3 && next[0] == 0xED && (next[1] & 0xF0) == 0xB0) {
			uint32_t high = 0xD000u | ((uint32_t)(waiting_half[1] & 0x3F) << 6) | (waiting_half[2] & 0x3F);
			uint32_t low = 0xD000u | ((uint32_t)(next[1] & 0x3F) << 6) | (next[2] & 0x3F);
			uint32_t point = 0x10000 + ((high - 0xD800) << 10) + (low - 0xDC00);
			fputc((int)(0xF0 | (point >> 18)), stdout);
			fputc((int)(0x80 | ((point >> 12) & 0x3F)), stdout);
			fputc((int)(0x80 | ((point >> 6) & 0x3F)), stdout);
			fputc((int)(0x80 | (point & 0x3F)), stdout);
			text += 3;
			size -= 3;
		} else {
			fputc('?', stdout);
		}
		waiting_half[0] = 0;
	}
	if (size >= 3 && (unsigned char)text[size - 3] == 0xED && ((unsigned char)text[size - 2] & 0xF0) == 0xA0) {
		memcpy(waiting_half, text + size - 3, 3);
		size -= 3;
	}
	const char* end = text + size;
	for (;;) {
		const char* at = lone_surrogate(text, end);
		if (!at) break;
		fwrite(text, 1, (size_t)(at - text), stdout);
		fputc('?', stdout);
		text = at + 3;
	}
	fwrite(text, 1, (size_t)(end - text), stdout);
}

/** Encodes one UTF-16 code unit as UTF-8, half of a pair as its own three bytes. Returns the byte count. */
static int format_char(char* out, uint16_t v)
{
	if (v < 0x80) {
		out[0] = (char)v;
		return 1;
	}
	if (v < 0x800) {
		out[0] = (char)(0xC0 | (v >> 6));
		out[1] = (char)(0x80 | (v & 0x3F));
		return 2;
	}
	out[0] = (char)(0xE0 | (v >> 12));
	out[1] = (char)(0x80 | ((v >> 6) & 0x3F));
	out[2] = (char)(0x80 | (v & 0x3F));
	return 3;
}

/** Writes [v] in decimal, without a final zero byte. Returns the length. `printf` takes several times as long. */
static int format_unsigned(char* out, uint64_t v)
{
	char digits[20];
	int count = 0;
	do {
		digits[count++] = (char)('0' + v % 10);
		v /= 10;
	} while (v);
	for (int index = 0; index < count; index++) out[index] = digits[count - 1 - index];
	return count;
}

static int format_signed(char* out, int64_t v)
{
	if (v >= 0) return format_unsigned(out, (uint64_t)v);
	out[0] = '-';
	return 1 + format_unsigned(out + 1, 0 - (uint64_t)v);
}

static int format_bool(char* out, bool v)
{
	memcpy(out, v ? "true" : "false", 5);
	return v ? 4 : 5;
}

/**
 * Formats like Double.toString / Float.toString: the shortest digits that
 * read back to the same value, plain notation for 1e-3 <= |v| < 1e7 and
 * "d.dddE±n" otherwise. Returns the length.
 */
static int format_real(char* out, double v, bool is_float)
{
	if (v != v) return sprintf(out, "NaN");
	if (isinf(v)) return sprintf(out, v > 0 ? "Infinity" : "-Infinity");
	if (v == 0) return sprintf(out, signbit(v) ? "-0.0" : "0.0");

	char buf[48];
	int max_digits = is_float ? 9 : 17;
	for (int prec = 1; prec <= max_digits; prec++) {
		snprintf(buf, sizeof buf, "%.*e", prec - 1, v);
		if (is_float ? strtof(buf, NULL) == (float)v : strtod(buf, NULL) == v) break;
	}

	char digits[24];
	int n = 0;
	const char* p = buf;
	bool negative = *p == '-';
	if (negative) p++;
	for (; *p != 'e'; p++) {
		if (*p != '.') digits[n++] = *p;
	}
	int exp = atoi(p + 1);
	while (n > 1 && digits[n - 1] == '0') n--;

	char* o = out;
	if (negative) *o++ = '-';
	if (exp >= -3 && exp < 7) {
		if (exp < 0) {
			*o++ = '0';
			*o++ = '.';
			for (int i = 0; i < -exp - 1; i++) *o++ = '0';
			for (int i = 0; i < n; i++) *o++ = digits[i];
		} else {
			for (int i = 0; i <= exp; i++) *o++ = i < n ? digits[i] : '0';
			*o++ = '.';
			if (n > exp + 1) {
				for (int i = exp + 1; i < n; i++) *o++ = digits[i];
			} else {
				*o++ = '0';
			}
		}
		*o = 0;
	} else {
		*o++ = digits[0];
		*o++ = '.';
		if (n > 1) {
			for (int i = 1; i < n; i++) *o++ = digits[i];
		} else {
			*o++ = '0';
		}
		o += sprintf(o, "E%d", exp);
	}
	return (int)(o - out);
}

/* ================== */
/* MARK: String builder */
/* ================== */

void ktc2_sb_init(ktc2_Sb* sb)
{
	sb->data = sb->local;
	sb->size = 0;
	sb->capacity = (int32_t)sizeof sb->local;
	sb->length = 0;
}

void ktc2_sb_text(ktc2_Sb* sb, const char* text, int32_t size, int32_t length)
{
	/* The first half of a pair at the end, the second at the start of what comes: the character they are. */
	if (size >= 3 && sb->size >= 3 && (unsigned char)text[0] == 0xED && ((unsigned char)text[1] & 0xF0) == 0xB0) {
		const unsigned char* last = (const unsigned char*)sb->data + sb->size - 3;
		if (last[0] == 0xED && (last[1] & 0xF0) == 0xA0) {
			uint32_t high = 0xD000u | ((uint32_t)(last[1] & 0x3F) << 6) | (last[2] & 0x3F);
			uint32_t low = 0xD000u | ((uint32_t)((unsigned char)text[1] & 0x3F) << 6) | ((unsigned char)text[2] & 0x3F);
			uint32_t point = 0x10000 + ((high - 0xD800) << 10) + (low - 0xDC00);
			char pair[4] = {
				(char)(0xF0 | (point >> 18)),
				(char)(0x80 | ((point >> 12) & 0x3F)),
				(char)(0x80 | ((point >> 6) & 0x3F)),
				(char)(0x80 | (point & 0x3F)),
			};
			sb->size -= 3;
			sb->length -= 1;
			ktc2_sb_text(sb, pair, 4, 2);
			ktc2_sb_text(sb, text + 3, size - 3, length - 1);
			return;
		}
	}
	if (sb->size + size > sb->capacity) {
		int32_t capacity = sb->capacity * 2;
		while (capacity < sb->size + size) capacity *= 2;
		char* data = malloc((size_t)capacity);
		if (!data) fail("java.lang.OutOfMemoryError", NULL);
		memcpy(data, sb->data, (size_t)sb->size);
		if (sb->data != sb->local) free(sb->data);
		sb->data = data;
		sb->capacity = capacity;
	}
	memcpy(sb->data + sb->size, text, (size_t)size);
	sb->size += size;
	sb->length += length;
}

void ktc2_sb_string(ktc2_Sb* sb, const ktc2_String* s)
{
	if (s) ktc2_sb_text(sb, s->bytes, s->size, s->length); else ktc2_sb_text(sb, "null", 4, 4);
}

ktc2_String* ktc2_sb_finish(ktc2_Sb* sb)
{
	ktc2_String* s = new_string(sb->data, sb->size, sb->length);
	if (sb->data != sb->local) free(sb->data);
	return s;
}

ktc2_String* ktc2_sb_finish_into(ktc2_Sb* sb, void* storage, size_t capacity)
{
	if (sizeof(ktc2_String) + (size_t)sb->size + 1 > capacity) return ktc2_sb_finish(sb);
	ktc2_String* s = storage;
	s->header.rc = KTC2_STACK | 1;
	s->header.typeId = KTC2_TYPE_STRING;
	s->size = sb->size;
	s->length = sb->length;
	memcpy(s->bytes, sb->data, (size_t)sb->size);
	s->bytes[sb->size] = 0;
	if (sb->data != sb->local) free(sb->data);
	return s;
}

/* Each value kind is formatted once, into a small buffer, for both the builder and stdout. */
#define KTC2_FORMATTED(name, type, format_call) \
	void ktc2_sb_##name(ktc2_Sb* sb, type v) \
	{ \
		char text[48]; \
		int size = format_call; \
		ktc2_sb_text(sb, text, size, size); \
	} \
	void ktc2_print_##name(type v) \
	{ \
		char text[48]; \
		int size = format_call; \
		write_text(text, size); \
	}

KTC2_FORMATTED(bool, bool, format_bool(text, v))
KTC2_FORMATTED(int, int32_t, format_signed(text, v))
KTC2_FORMATTED(long, int64_t, format_signed(text, v))
KTC2_FORMATTED(uint, uint32_t, format_unsigned(text, v))
KTC2_FORMATTED(ulong, uint64_t, format_unsigned(text, v))
KTC2_FORMATTED(float, float, format_real(text, v, true))
KTC2_FORMATTED(double, double, format_real(text, v, false))

void ktc2_sb_char(ktc2_Sb* sb, uint16_t v)
{
	char text[4];
	ktc2_sb_text(sb, text, format_char(text, v), 1);
}

void ktc2_print_char(uint16_t v)
{
	char text[4];
	write_text(text, format_char(text, v));
}

void ktc2_print_text(const char* text, int32_t size, int32_t length)
{
	(void)length;
	write_text(text, size);
}

void ktc2_print_string(const ktc2_String* s)
{
	if (s) write_text(s->bytes, s->size); else write_text("null", 4);
}

/* ================== */
/* MARK: Any defaults */
/* ================== */

int32_t ktc2_any_hashCode(ktc2_Obj* object)
{
	const ktc2_Box* box = (const ktc2_Box*)object;
	switch (object->typeId) {
	case KTC2_TYPE_STRING: return ktc2_str_hashCode((ktc2_String*)object);
	case KTC2_TYPE_BOX_BOOLEAN: return ktc2_hash_boolean(box->value.boolean_);
	case KTC2_TYPE_BOX_BYTE: return box->value.byte_;
	case KTC2_TYPE_BOX_SHORT: return box->value.short_;
	case KTC2_TYPE_BOX_INT: return box->value.int_;
	case KTC2_TYPE_BOX_LONG: return ktc2_hash_long(box->value.long_);
	case KTC2_TYPE_BOX_CHAR: return box->value.char_;
	case KTC2_TYPE_BOX_FLOAT: return ktc2_hash_float(box->value.float_);
	case KTC2_TYPE_BOX_DOUBLE: return ktc2_hash_double(box->value.double_);
	case KTC2_TYPE_BOX_UBYTE: return box->value.ubyte_;
	case KTC2_TYPE_BOX_USHORT: return box->value.ushort_;
	case KTC2_TYPE_BOX_UINT: return (int32_t)box->value.uint_;
	case KTC2_TYPE_BOX_ULONG: return ktc2_hash_ulong(box->value.ulong_);
	default: {
		uintptr_t address = (uintptr_t)object;
		return (int32_t)((address >> 3) ^ (address >> 35));
	}
	}
}

bool ktc2_any_equals(ktc2_Obj* a, ktc2_Obj* b)
{
	if (a == b) return true;
	if (!a || !b || a->typeId != b->typeId) return false;
	const ktc2_Box* x = (const ktc2_Box*)a;
	const ktc2_Box* y = (const ktc2_Box*)b;
	switch (a->typeId) {
	case KTC2_TYPE_STRING: return ktc2_str_equals((ktc2_String*)a, (ktc2_String*)b);
	case KTC2_TYPE_BOX_BOOLEAN: return x->value.boolean_ == y->value.boolean_;
	case KTC2_TYPE_BOX_BYTE: return x->value.byte_ == y->value.byte_;
	case KTC2_TYPE_BOX_SHORT: return x->value.short_ == y->value.short_;
	case KTC2_TYPE_BOX_INT: return x->value.int_ == y->value.int_;
	case KTC2_TYPE_BOX_LONG: return x->value.long_ == y->value.long_;
	case KTC2_TYPE_BOX_CHAR: return x->value.char_ == y->value.char_;
	/* Boxed floating-point values compare by bits, as on the JVM: NaN equals NaN, 0.0 differs from -0.0. */
	case KTC2_TYPE_BOX_FLOAT: return ktc2_hash_float(x->value.float_) == ktc2_hash_float(y->value.float_);
	case KTC2_TYPE_BOX_DOUBLE: return memcmp(&x->value.double_, &y->value.double_, sizeof(double)) == 0 || (x->value.double_ != x->value.double_ && y->value.double_ != y->value.double_);
	case KTC2_TYPE_BOX_UBYTE: return x->value.ubyte_ == y->value.ubyte_;
	case KTC2_TYPE_BOX_USHORT: return x->value.ushort_ == y->value.ushort_;
	case KTC2_TYPE_BOX_UINT: return x->value.uint_ == y->value.uint_;
	case KTC2_TYPE_BOX_ULONG: return x->value.ulong_ == y->value.ulong_;
	default: return false;
	}
}

ktc2_Obj ktc2_unit_object = { KTC2_IMMORTAL, KTC2_TYPE_UNIT };

ktc2_String* ktc2_className(ktc2_Obj* object)
{
	const char* name = ktc2_types[object->typeId].name;
	/* The built-in types are listed by their short names, and `Unit` by its whole one. */
	bool isShort = object->typeId < KTC2_TYPE_FIRST_USER && object->typeId != KTC2_TYPE_UNIT;
	ktc2_Sb sb;
	ktc2_sb_init(&sb);
	if (isShort) ktc2_sb_text(&sb, "kotlin.", 7, 7);
	int32_t size = (int32_t)strlen(name);
	ktc2_sb_text(&sb, name, size, size);
	return ktc2_sb_finish(&sb);
}

ktc2_String* ktc2_any_toString(ktc2_Obj* object)
{
	if (object->typeId == KTC2_TYPE_STRING) return ktc2_retain(object);
	if (object->typeId == KTC2_TYPE_UNIT) return new_string("kotlin.Unit", 11, 11);
	const ktc2_Box* box = (const ktc2_Box*)object;
	ktc2_Sb sb;
	ktc2_sb_init(&sb);
	switch (object->typeId) {
	case KTC2_TYPE_BOX_BOOLEAN: ktc2_sb_bool(&sb, box->value.boolean_); break;
	case KTC2_TYPE_BOX_BYTE: ktc2_sb_int(&sb, box->value.byte_); break;
	case KTC2_TYPE_BOX_SHORT: ktc2_sb_int(&sb, box->value.short_); break;
	case KTC2_TYPE_BOX_INT: ktc2_sb_int(&sb, box->value.int_); break;
	case KTC2_TYPE_BOX_LONG: ktc2_sb_long(&sb, box->value.long_); break;
	case KTC2_TYPE_BOX_CHAR: ktc2_sb_char(&sb, box->value.char_); break;
	case KTC2_TYPE_BOX_FLOAT: ktc2_sb_float(&sb, box->value.float_); break;
	case KTC2_TYPE_BOX_DOUBLE: ktc2_sb_double(&sb, box->value.double_); break;
	case KTC2_TYPE_BOX_UBYTE: ktc2_sb_uint(&sb, box->value.ubyte_); break;
	case KTC2_TYPE_BOX_USHORT: ktc2_sb_uint(&sb, box->value.ushort_); break;
	case KTC2_TYPE_BOX_UINT: ktc2_sb_uint(&sb, box->value.uint_); break;
	case KTC2_TYPE_BOX_ULONG: ktc2_sb_ulong(&sb, box->value.ulong_); break;
	default: {
		char text[160];
		int size = snprintf(text, sizeof text, "%s@%" PRIx32, ktc2_types[object->typeId].name, (uint32_t)ktc2_any_hashCode(object));
		ktc2_sb_text(&sb, text, size, size);
	}
	}
	return ktc2_sb_finish(&sb);
}

int32_t ktc2_any_compareTo(ktc2_Obj* a, ktc2_Obj* b)
{
	if (a->typeId != b->typeId) ktc2_fail_cast();
	const ktc2_Box* x = (const ktc2_Box*)a;
	const ktc2_Box* y = (const ktc2_Box*)b;
	switch (a->typeId) {
	case KTC2_TYPE_STRING: return ktc2_str_compare((ktc2_String*)a, (ktc2_String*)b);
	case KTC2_TYPE_BOX_BOOLEAN: return KTC2_CMP(x->value.boolean_, y->value.boolean_);
	/* As java.lang.Byte, Short and Character, which answer the difference. */
	case KTC2_TYPE_BOX_BYTE: return x->value.byte_ - y->value.byte_;
	case KTC2_TYPE_BOX_SHORT: return x->value.short_ - y->value.short_;
	case KTC2_TYPE_BOX_INT: return KTC2_CMP(x->value.int_, y->value.int_);
	case KTC2_TYPE_BOX_LONG: return KTC2_CMP(x->value.long_, y->value.long_);
	case KTC2_TYPE_BOX_CHAR: return x->value.char_ - y->value.char_;
	case KTC2_TYPE_BOX_FLOAT: return ktc2_cmp_double(x->value.float_, y->value.float_);
	case KTC2_TYPE_BOX_DOUBLE: return ktc2_cmp_double(x->value.double_, y->value.double_);
	case KTC2_TYPE_BOX_UBYTE: return KTC2_CMP(x->value.ubyte_, y->value.ubyte_);
	case KTC2_TYPE_BOX_USHORT: return KTC2_CMP(x->value.ushort_, y->value.ushort_);
	case KTC2_TYPE_BOX_UINT: return KTC2_CMP(x->value.uint_, y->value.uint_);
	case KTC2_TYPE_BOX_ULONG: return KTC2_CMP(x->value.ulong_, y->value.ulong_);
	default: ktc2_fail_dispatch();
	}
}

bool ktc2_is_number(const void* object)
{
	if (!object) return false;
	switch (((const ktc2_Obj*)object)->typeId) {
	case KTC2_TYPE_BOX_BYTE:
	case KTC2_TYPE_BOX_SHORT:
	case KTC2_TYPE_BOX_INT:
	case KTC2_TYPE_BOX_LONG:
	case KTC2_TYPE_BOX_FLOAT:
	case KTC2_TYPE_BOX_DOUBLE:
		return true;
	default:
		return false;
	}
}

/** The number in a box: true and [*real] when it is a Float or a Double, false and [*whole] otherwise. */
static bool number_in(ktc2_Obj* number, int64_t* whole, double* real)
{
	const ktc2_Box* box = (const ktc2_Box*)number;
	switch (number->typeId) {
	case KTC2_TYPE_BOX_BYTE: *whole = box->value.byte_; return false;
	case KTC2_TYPE_BOX_SHORT: *whole = box->value.short_; return false;
	case KTC2_TYPE_BOX_INT: *whole = box->value.int_; return false;
	case KTC2_TYPE_BOX_LONG: *whole = box->value.long_; return false;
	case KTC2_TYPE_BOX_FLOAT: *real = box->value.float_; return true;
	case KTC2_TYPE_BOX_DOUBLE: *real = box->value.double_; return true;
	default: ktc2_fail_dispatch();
	}
}

int32_t ktc2_number_toInt(ktc2_Obj* number)
{
	int64_t whole = 0;
	double real = 0;
	return number_in(number, &whole, &real) ? ktc2_d2i(real) : (int32_t)(uint32_t)(uint64_t)whole;
}

int64_t ktc2_number_toLong(ktc2_Obj* number)
{
	int64_t whole = 0;
	double real = 0;
	return number_in(number, &whole, &real) ? ktc2_d2l(real) : whole;
}

int16_t ktc2_number_toShort(ktc2_Obj* number)
{
	return (int16_t)(uint16_t)(uint32_t)ktc2_number_toInt(number);
}

int8_t ktc2_number_toByte(ktc2_Obj* number)
{
	return (int8_t)(uint8_t)(uint32_t)ktc2_number_toInt(number);
}

double ktc2_number_toDouble(ktc2_Obj* number)
{
	int64_t whole = 0;
	double real = 0;
	return number_in(number, &whole, &real) ? real : (double)whole;
}

float ktc2_number_toFloat(ktc2_Obj* number)
{
	int64_t whole = 0;
	double real = 0;
	return number_in(number, &whole, &real) ? (float)real : (float)whole;
}

/* `compareTo` of a boxed primitive against a value of its own kind: the box seen as a `Comparable<Int>`. */
#define KTC2_BOX_COMPARE(name, type, id, field, result) \
	int32_t ktc2_box_compareTo_##name(ktc2_Obj* a, type y) \
	{ \
		if (a->typeId != id) ktc2_fail_cast(); \
		type x = ((const ktc2_Box*)a)->value.field; \
		return result; \
	}

KTC2_BOX_COMPARE(boolean, bool, KTC2_TYPE_BOX_BOOLEAN, boolean_, KTC2_CMP(x, y))
KTC2_BOX_COMPARE(byte, int8_t, KTC2_TYPE_BOX_BYTE, byte_, x - y)
KTC2_BOX_COMPARE(short, int16_t, KTC2_TYPE_BOX_SHORT, short_, x - y)
KTC2_BOX_COMPARE(int, int32_t, KTC2_TYPE_BOX_INT, int_, KTC2_CMP(x, y))
KTC2_BOX_COMPARE(long, int64_t, KTC2_TYPE_BOX_LONG, long_, KTC2_CMP(x, y))
KTC2_BOX_COMPARE(char, uint16_t, KTC2_TYPE_BOX_CHAR, char_, x - y)
KTC2_BOX_COMPARE(float, float, KTC2_TYPE_BOX_FLOAT, float_, ktc2_cmp_double(x, y))
KTC2_BOX_COMPARE(double, double, KTC2_TYPE_BOX_DOUBLE, double_, ktc2_cmp_double(x, y))
KTC2_BOX_COMPARE(ubyte, uint8_t, KTC2_TYPE_BOX_UBYTE, ubyte_, KTC2_CMP(x, y))
KTC2_BOX_COMPARE(ushort, uint16_t, KTC2_TYPE_BOX_USHORT, ushort_, KTC2_CMP(x, y))
KTC2_BOX_COMPARE(uint, uint32_t, KTC2_TYPE_BOX_UINT, uint_, KTC2_CMP(x, y))
KTC2_BOX_COMPARE(ulong, uint64_t, KTC2_TYPE_BOX_ULONG, ulong_, KTC2_CMP(x, y))

/* ================== */
/* MARK: CharSequence */
/* ================== */

int32_t ktc2_cs_length(ktc2_Obj* object)
{
	if (object->typeId != KTC2_TYPE_STRING) ktc2_fail_dispatch();
	return ((ktc2_String*)object)->length;
}

uint16_t ktc2_cs_get(ktc2_Obj* object, int32_t index)
{
	if (object->typeId != KTC2_TYPE_STRING) ktc2_fail_dispatch();
	return ktc2_str_get((ktc2_String*)object, index);
}

_Noreturn void ktc2_fail_throw(const ktc2_String* exception, const ktc2_String* message)
{
	fflush(stdout);
	fprintf(stderr, "Exception in thread \"main\" %s%s%s\n", exception->bytes, message ? ": " : "", message ? message->bytes : "");
	exit(1);
}

ktc2_String* ktc2_stringOfChars(ktc2_Array* chars, int32_t start, int32_t length)
{
	const uint16_t* units = (const uint16_t*)(chars + 1);
	if (start < 0 || length < 0 || start + length > chars->length) fail_range(start + length, chars->length);
	/* All ASCII, as most text is: a byte per character, written where it stays. */
	uint16_t all = 0;
	for (int32_t i = start; i < start + length; i++) all |= units[i];
	if (all < 0x80) {
		ktc2_String* s = ktc2_alloc(sizeof(ktc2_String) + (size_t)length + 1, KTC2_TYPE_STRING);
		s->size = length;
		s->length = length;
		for (int32_t i = 0; i < length; i++) s->bytes[i] = (char)units[start + i];
		return s;
	}
	ktc2_Sb sb;
	ktc2_sb_init(&sb);
	for (int32_t i = start; i < start + length; i++) {
		uint16_t unit = units[i];
		/* A surrogate pair is one code point: four UTF-8 bytes, two units of Kotlin length. */
		if (unit >= 0xD800 && unit <= 0xDBFF && i + 1 < start + length && units[i + 1] >= 0xDC00 && units[i + 1] <= 0xDFFF) {
			uint32_t point = 0x10000 + (((uint32_t)unit - 0xD800) << 10) + (units[i + 1] - 0xDC00);
			char text[4] = {
				(char)(0xF0 | (point >> 18)),
				(char)(0x80 | ((point >> 12) & 0x3F)),
				(char)(0x80 | ((point >> 6) & 0x3F)),
				(char)(0x80 | (point & 0x3F)),
			};
			ktc2_sb_text(&sb, text, 4, 2);
			i++;
		} else {
			ktc2_sb_char(&sb, unit);
		}
	}
	return ktc2_sb_finish(&sb);
}

void ktc2_stringToChars(ktc2_String* s, ktc2_Array* chars, int32_t at)
{
	uint16_t* units = (uint16_t*)(chars + 1) + at;
	if (at < 0 || at + s->length > chars->length) fail_range(at + s->length, chars->length);
	if (s->size == s->length) {
		for (int32_t i = 0; i < s->length; i++) units[i] = (unsigned char)s->bytes[i];
		return;
	}
	UnitCursor cursor = cursor_of(s);
	while (cursor_has(&cursor)) *units++ = cursor_next(&cursor);
}

/**
 * Where the code point that holds UTF-16 unit [index] of a non-ASCII string starts, and in
 * [*unit] the index of its first unit: [index] itself unless it is the second half of a pair.
 * The end of the string for its length.
 */
static const char* code_point_at(const ktc2_String* s, int32_t index, int32_t* unit)
{
	const char* end = s->bytes + s->size;
	const char* at = place_in(s, index, unit);
	while (at < end) {
		int32_t width = (unsigned char)*at >= 0xF0 ? 2 : 1;
		if (index < *unit + width) break;
		next_code_point(&at);
		*unit += width;
	}
	place_note(s, *unit, at);
	return at;
}

/** How many UTF-16 units the code points that start in [from, to) are. */
static int32_t units_in(const char* from, const char* to)
{
	int32_t units = 0;
	for (; from < to; from++) {
		unsigned char byte = (unsigned char)*from;
		if ((byte & 0xC0) != 0x80) units += byte >= 0xF0 ? 2 : 1;
	}
	return units;
}

/*
 * Searches a non-ASCII string for [size] bytes of UTF-8 that are [count] UTF-16 units, whole
 * code points and not empty. A place where the bytes are found is the start of a code point
 * (the first of them is no continuation byte), so it is where the units are found too. Not
 * for text that starts with the second half of a pair or ends with the first, which is also
 * found inside a pair: the caller leaves that to the Kotlin code.
 */
static int32_t find_wide(ktc2_String* s, const char* wanted, int32_t size, int32_t count, int32_t from, bool backwards)
{
	int32_t length = s->length;
	const char* limit = s->bytes + s->size - size;
	int32_t unit;
	if (backwards) {
		int32_t last = length - count;
		if (from < last) last = from;
		if (last < 0 || s->size < size) return -1;
		const char* at = code_point_at(s, last, &unit);
		if (at > limit) at = limit;
		for (; at >= s->bytes; at--) {
			if (*at == wanted[0] && memcmp(at, wanted, (size_t)size) == 0) return units_in(s->bytes, at);
		}
		return -1;
	}
	int32_t index = from < 0 ? 0 : from;
	if (index > length - count) return -1;
	const char* at = code_point_at(s, index, &unit);
	if (unit < index) {
		/* Inside a pair: nothing is found there. */
		at += 4;
		unit += 2;
	}
	const char* start = at;
	for (; at <= limit; at++) {
		if (*at != wanted[0] || memcmp(at, wanted, (size_t)size) != 0) continue;
		int32_t found = unit + units_in(start, at);
		place_note(s, found, at);
		return found;
	}
	return -1;
}

int32_t ktc2_stringFind(ktc2_String* s, ktc2_String* other, int32_t from, bool backwards)
{
	if (s->size != s->length) {
		int32_t count = other->length;
		if (count == 0) {
			if (backwards) return from < 0 ? -1 : from < s->length ? from : s->length;
			int32_t index = from < 0 ? 0 : from;
			return index <= s->length ? index : s->length;
		}
		const unsigned char* wanted = (const unsigned char*)other->bytes;
		if (other->size >= 3 && wanted[0] == 0xED && (wanted[1] & 0xF0) == 0xB0) return -2;
		if (other->size >= 3 && wanted[other->size - 3] == 0xED && (wanted[other->size - 2] & 0xF0) == 0xA0) return -2;
		return find_wide(s, other->bytes, other->size, count, from, backwards);
	}
	int32_t length = s->length;
	int32_t count = other->length;
	/* Text that is all ASCII holds no string that is not; the empty string is ASCII. */
	if (other->size != other->length) return -1;
	int32_t last = length - count;
	if (backwards) {
		for (int32_t index = last < from ? last : from; index >= 0; index--) {
			if (memcmp(s->bytes + index, other->bytes, (size_t)count) == 0) return index;
		}
		return -1;
	}
	int32_t index = from < 0 ? 0 : from;
	/* The empty string is found wherever the search starts, and at the end when it starts beyond it. */
	if (count == 0) return index <= length ? index : length;
	/* Compared in place: where the first character is frequent, a library call per candidate costs more than it saves. */
	const char* text = s->bytes;
	const char* wanted = other->bytes;
	for (; index <= last; index++) {
		if (text[index] != wanted[0]) continue;
		int32_t same = 1;
		while (same < count && text[index + same] == wanted[same]) same++;
		if (same == count) return index;
	}
	return -1;
}

int32_t ktc2_stringFindChar(ktc2_String* s, uint16_t c, int32_t from, bool backwards)
{
	if (s->size != s->length) {
		if (c >= 0xD800 && c <= 0xDFFF) return -2;
		char text[3];
		int32_t size;
		if (c < 0x80) {
			text[0] = (char)c;
			size = 1;
		} else if (c < 0x800) {
			text[0] = (char)(0xC0 | (c >> 6));
			text[1] = (char)(0x80 | (c & 0x3F));
			size = 2;
		} else {
			text[0] = (char)(0xE0 | (c >> 12));
			text[1] = (char)(0x80 | ((c >> 6) & 0x3F));
			text[2] = (char)(0x80 | (c & 0x3F));
			size = 3;
		}
		return find_wide(s, text, size, 1, from, backwards);
	}
	if (c >= 0x80) return -1;
	int32_t length = s->length;
	if (backwards) {
		for (int32_t index = from >= length ? length - 1 : from; index >= 0; index--) {
			if ((unsigned char)s->bytes[index] == c) return index;
		}
		return -1;
	}
	int32_t index = from < 0 ? 0 : from;
	if (index >= length) return -1;
	const char* found = memchr(s->bytes + index, (int)c, (size_t)(length - index));
	return found ? (int32_t)(found - s->bytes) : -1;
}

/**
 * The bytes of the UTF-16 units [offset, offset + length) of a string, which must be inside it:
 * where they start in [*start], and how many they are; -1 when a pair is cut at either end.
 */
static int32_t region_of(const ktc2_String* s, int32_t offset, int32_t length, const char** start)
{
	if (s->size == s->length) {
		*start = s->bytes + offset;
		return length;
	}
	int32_t unit;
	const char* from = code_point_at(s, offset, &unit);
	if (unit != offset) return -1;
	const char* to = from;
	while (unit < offset + length) {
		unit += (unsigned char)*to >= 0xF0 ? 2 : 1;
		next_code_point(&to);
	}
	if (unit != offset + length) return -1;
	*start = from;
	return (int32_t)(to - from);
}

int32_t ktc2_stringRegionEquals(ktc2_String* s, int32_t offset, ktc2_String* other, int32_t otherOffset, int32_t length)
{
	if (s->size == s->length && other->size == other->length) return memcmp(s->bytes + offset, other->bytes + otherOffset, (size_t)length) == 0;
	const char* mine;
	const char* theirs;
	int32_t mineSize = region_of(s, offset, length, &mine);
	int32_t theirsSize = region_of(other, otherOffset, length, &theirs);
	if (mineSize < 0 || theirsSize < 0) return -2;
	return mineSize == theirsSize && memcmp(mine, theirs, (size_t)mineSize) == 0;
}

ktc2_String* ktc2_stringSubstring(ktc2_String* s, int32_t start, int32_t end)
{
	if (start < 0 || end > s->length || start > end) {
#ifdef KTC2_CHECKS_THROW
		ktc2_internal_throwSubstringRange(start, end, s->length);
		return NULL;
#endif
		fflush(stdout);
		fprintf(stderr, "Exception in thread \"main\" java.lang.StringIndexOutOfBoundsException: begin %" PRId32 ", end %" PRId32 ", length %" PRId32 "\n", start, end, s->length);
		exit(1);
	}
	if (start == 0 && end == s->length) return ktc2_retain(s);
	if (s->size == s->length) return new_string(s->bytes + start, end - start, end - start);
	int32_t firstUnit;
	int32_t lastUnit;
	const char* first = code_point_at(s, start, &firstUnit);
	const char* last = code_point_at(s, end, &lastUnit);
	if (firstUnit == start && lastUnit == end) return new_string(first, (int32_t)(last - first), end - start);
	/* An end inside a pair: half of it is in the result, on its own. */
	ktc2_Sb sb;
	ktc2_sb_init(&sb);
	UnitCursor cursor = { first, s->bytes + s->size, 0 };
	for (int32_t unit = firstUnit; unit < end; unit++) {
		uint16_t value = cursor_next(&cursor);
		if (unit >= start) ktc2_sb_char(&sb, value);
	}
	return ktc2_sb_finish(&sb);
}

bool ktc2_stringToDouble(ktc2_String* s, ktc2_Array* result)
{
	const char* at = s->bytes;
	const char* last = s->bytes + s->size;
	while (at < last && (unsigned char)*at <= ' ') at++;
	while (last > at && (unsigned char)last[-1] <= ' ') last--;
	if (at == last) return false;
	/* Java accepts a trailing type suffix. */
	if (last - at > 1 && (last[-1] == 'd' || last[-1] == 'D' || last[-1] == 'f' || last[-1] == 'F')) last--;
	char text[400];
	size_t size = (size_t)(last - at);
	if (size >= sizeof text) return false;
	memcpy(text, at, size);
	text[size] = 0;
	/* strtod also takes "inf" and "nan"; Java only "Infinity" and "NaN". */
	const char* digits = text + (text[0] == '-' || text[0] == '+');
	if ((digits[0] < '0' || digits[0] > '9') && digits[0] != '.' && strcmp(digits, "Infinity") != 0 && strcmp(digits, "NaN") != 0) return false;
	char* stop = NULL;
	double value = strtod(text, &stop);
	if (stop == text || *stop != 0) return false;
	((double*)(result + 1))[0] = value;
	return true;
}

ktc2_Array* ktc2_stringUtf8(ktc2_String* s)
{
	const char* end = s->bytes + s->size;
	int32_t lone = 0;
	for (const char* at = lone_surrogate(s->bytes, end); at; at = lone_surrogate(at + 3, end)) lone++;
	ktc2_Array* bytes = ktc2_array_new(s->size - 2 * lone, 1, KTC2_TYPE_ARRAY_BYTE);
	char* out = (char*)(bytes + 1);
	const char* text = s->bytes;
	/* As the JVM: `?` for half of a pair on its own. */
	for (const char* at = lone_surrogate(text, end); at; at = lone_surrogate(text, end)) {
		memcpy(out, text, (size_t)(at - text));
		out += at - text;
		*out++ = '?';
		text = at + 3;
	}
	memcpy(out, text, (size_t)(end - text));
	return bytes;
}

ktc2_String* ktc2_stringOfUtf8(ktc2_Array* bytes, int32_t start, int32_t end)
{
	if (start < 0 || end > bytes->length || start > end) fail_range(end, bytes->length);
	const char* data = (const char*)(bytes + 1) + start;
	int32_t size = end - start;
	int32_t length = 0;
	for (int32_t i = 0; i < size; i++) {
		unsigned char byte = (unsigned char)data[i];
		/* Count code units: one per lead byte, two for a four-byte sequence. */
		if ((byte & 0xC0) != 0x80) length += byte >= 0xF0 ? 2 : 1;
	}
	return new_string(data, size, length);
}

/* ================== */
/* MARK: Input        */
/* ================== */

/** A ByteArray holding the [size] bytes at [data]. */
static ktc2_Array* bytes_of(const char* data, size_t size)
{
	ktc2_Array* bytes = ktc2_array_new((int32_t)size, 1, KTC2_TYPE_ARRAY_BYTE);
	memcpy(bytes + 1, data, size);
	return bytes;
}

/** Appends [byte] to a buffer that grows; false when memory ran out. */
static bool buffer_add(char** buffer, size_t* size, size_t* capacity, const char* data, size_t count)
{
	if (*size + count > *capacity) {
		size_t wanted = *capacity ? *capacity : 256;
		while (wanted < *size + count) wanted *= 2;
		char* grown = realloc(*buffer, wanted);
		if (!grown) return false;
		*buffer = grown;
		*capacity = wanted;
	}
	memcpy(*buffer + *size, data, count);
	*size += count;
	return true;
}

ktc2_Array* ktc2_readLineBytes(void)
{
	char* line = NULL;
	size_t size = 0;
	size_t capacity = 0;
	int next;
	fflush(stdout);
	while ((next = getchar()) != EOF && next != '\n') {
		char byte = (char)next;
		if (!buffer_add(&line, &size, &capacity, &byte, 1)) fail("java.lang.OutOfMemoryError", NULL);
	}
	if (next == EOF && size == 0) {
		free(line);
		return NULL;
	}
	if (size > 0 && line[size - 1] == '\r') size--;
	ktc2_Array* bytes = bytes_of(line ? line : "", size);
	free(line);
	return bytes;
}

ktc2_Obj* ktc2_cs_subSequence(ktc2_Obj* object, int32_t start, int32_t end)
{
	if (object->typeId != KTC2_TYPE_STRING) ktc2_fail_dispatch();
	return (ktc2_Obj*)ktc2_stringSubstring((ktc2_String*)object, start, end);
}

/* ================== */
/* MARK: C interop    */
/* ================== */

void* ktc2_str_cptr(ktc2_String* string)
{
	return string ? string->bytes : NULL;
}

ktc2_String* ktc2_string_of_cstr(const char* text)
{
	if (!text) ktc2_fail_null();
	size_t size = strlen(text);
	int32_t length = 0;
	for (size_t i = 0; i < size; i++) {
		unsigned char byte = (unsigned char)text[i];
		if ((byte & 0xC0) != 0x80) length += byte >= 0xF0 ? 2 : 1;
	}
	return new_string(text, (int32_t)size, length);
}

void* ktc2_cvalue_ptr(ktc2_Array* value)
{
	return value ? value + 1 : NULL;
}

ktc2_Array* ktc2_cvalue_copy(const void* data, int64_t size)
{
	ktc2_Array* value = ktc2_array_new((int32_t)size, 1, KTC2_TYPE_ARRAY_BYTE);
	memcpy(value + 1, data, (size_t)size);
	return value;
}

ktc2_Array* ktc2_args(int argc, char** argv)
{
	int32_t count = argc > 1 ? argc - 1 : 0;
	ktc2_Array* arguments = ktc2_array_new(count, sizeof(ktc2_String*), KTC2_TYPE_ARRAY_REF);
	for (int32_t i = 0; i < count; i++) ((ktc2_String**)(arguments + 1))[i] = ktc2_string_of_cstr(argv[i + 1]);
	return arguments;
}

ktc2_Obj* ktc2_box_ptr(void* address)
{
	return address ? ktc2_box_long((int64_t)(intptr_t)address) : NULL;
}

void* ktc2_unbox_ptr(ktc2_Obj* object)
{
	return object ? (void*)(intptr_t)ktc2_unbox_long(object) : NULL;
}

void* ktc2_scope_alloc(void* head, int64_t size)
{
	void** block = calloc(1, 16 + (size_t)size);
	if (!block) fail("java.lang.OutOfMemoryError", NULL);
	block[0] = head;
	return block;
}

void ktc2_scope_free(void* head)
{
	while (head) {
		void* next = *(void**)head;
		free(head);
		head = next;
	}
}

/* ================== */
/* MARK: Weak         */
/* ================== */

/**
 * Weak references go through a cell. The cell is an ordinary counted object, owned by the
 * weak references; the target does not know it, apart from the KTC2_WEAK bit in its count.
 * A table from target to cell lets the dying target find its cell and clear it, so objects
 * that are never weakly referenced pay nothing.
 */
typedef struct WeakCell {
	ktc2_Obj header;
	ktc2_Obj* target;        /* NULL once the target is destroyed */
	struct WeakCell* next;   /* next cell of the same bucket */
} WeakCell;

static WeakCell** weak_buckets;
static size_t weak_bucket_count;
static size_t weak_cell_count;

static size_t weak_hash(const ktc2_Obj* target, size_t bucketCount)
{
	return (size_t)((((uint64_t)(uintptr_t)target >> 3) * UINT64_C(0x9E3779B97F4A7C15)) >> 32) & (bucketCount - 1);
}

/** The link that holds the cell of [target]; the cell is known to be in the table. */
static WeakCell** weak_link(const ktc2_Obj* target)
{
	WeakCell** link = &weak_buckets[weak_hash(target, weak_bucket_count)];
	while ((*link)->target != target) link = &(*link)->next;
	return link;
}

static void weak_insert(WeakCell* cell)
{
	if (weak_cell_count >= weak_bucket_count) {
		size_t count = weak_bucket_count ? weak_bucket_count * 2 : 64;
		WeakCell** buckets = calloc(count, sizeof *buckets);
		if (!buckets) fail("java.lang.OutOfMemoryError", NULL);
		for (size_t i = 0; i < weak_bucket_count; i++) {
			for (WeakCell* moved = weak_buckets[i]; moved;) {
				WeakCell* next = moved->next;
				size_t slot = weak_hash(moved->target, count);
				moved->next = buckets[slot];
				buckets[slot] = moved;
				moved = next;
			}
		}
		free(weak_buckets);
		weak_buckets = buckets;
		weak_bucket_count = count;
	}
	size_t slot = weak_hash(cell->target, weak_bucket_count);
	cell->next = weak_buckets[slot];
	weak_buckets[slot] = cell;
	weak_cell_count++;
}

static void weak_remove(const ktc2_Obj* target)
{
	WeakCell** link = weak_link(target);
	*link = (*link)->next;
	weak_cell_count--;
}

/** The target is being destroyed: its cell reads NULL from now on. */
static void weak_drop(ktc2_Obj* target)
{
	runtime_enter();
	WeakCell* cell = *weak_link(target);
	weak_remove(target);
	cell->target = NULL;
	runtime_leave();
}

/**
 * Takes a reference to [object] unless its count already reached zero - another thread is
 * then about to destroy it, and is waiting for the runtime lock the caller holds.
 */
static ktc2_Obj* retain_if_alive(ktc2_Obj* object)
{
	if (!object) return NULL;
	uint32_t rc = KTC2_RC_LOAD(object);
	if (rc == KTC2_IMMORTAL) return object;
#ifdef KTC2_THREADS
	while ((rc & KTC2_COUNT_MASK) != 0) {
		if (ktc2_atomic_replace(&object->rc, &rc, rc + 1)) return object;
	}
	return NULL;
#else
	object->rc++;
	return object;
#endif
}

ktc2_Obj* ktc2_weakCell(ktc2_Obj* target)
{
	if (!target) return NULL;
	if (target->rc == KTC2_IMMORTAL) {
		/* Never destroyed: the cell needs no tracking. */
		WeakCell* cell = ktc2_alloc(sizeof *cell, KTC2_TYPE_WEAK_CELL);
		cell->target = target;
		return &cell->header;
	}
	WeakCell* fresh = NULL;
	runtime_enter();
	WeakCell* cell = NULL;
	if (KTC2_RC_LOAD(target) & KTC2_WEAK) {
		WeakCell* existing = *weak_link(target);
		cell = (WeakCell*)retain_if_alive(&existing->header);
		if (!cell) {
			/* The last weak reference is going away right now: its cell is replaced. */
			weak_remove(target);
			existing->target = NULL;
		}
	}
	runtime_leave();
	if (cell) return &cell->header;
	fresh = ktc2_alloc(sizeof *fresh, KTC2_TYPE_WEAK_CELL);
	fresh->target = target;
	runtime_enter();
	if (KTC2_RC_LOAD(target) & KTC2_WEAK) {
		/* Another thread made the cell in the meantime. */
		cell = (WeakCell*)retain_if_alive(&(*weak_link(target))->header);
	}
	if (!cell) {
		if (KTC2_RC_LOAD(target) & KTC2_WEAK) {
			WeakCell* dying = *weak_link(target);
			weak_remove(target);
			dying->target = NULL;
		}
		weak_insert(fresh);
		KTC2_RC_OR(target, KTC2_WEAK);
		cell = fresh;
		fresh = NULL;
	}
	runtime_leave();
	if (fresh) {
		fresh->target = NULL;
		ktc2_release(fresh);
	}
	return &cell->header;
}

ktc2_Obj* ktc2_weakGet(ktc2_Obj* object)
{
	WeakCell* cell = (WeakCell*)object;
	if (!cell) return NULL;
	runtime_enter();
	ktc2_Obj* target = retain_if_alive(cell->target);
	runtime_leave();
	return target;
}

void ktc2_weak_cell_destroy(ktc2_Obj* object)
{
	WeakCell* cell = (WeakCell*)object;
	runtime_enter();
	if (cell->target && KTC2_RC_LOAD(cell->target) != KTC2_IMMORTAL) {
		weak_remove(cell->target);
		KTC2_RC_AND(cell->target, ~KTC2_WEAK);
	}
	runtime_leave();
}

/* ================== */
/* MARK: Concurrency  */
/* ================== */

#ifdef KTC2_THREADS

/** `ktc2.internal.threadEntry`, generated with the program: calls `run` of the Thread object. */
void ktc2_internal_threadEntry(ktc2_Obj* thread);

/** Threads started and not yet ended. */
static uint32_t threads_running;

static void thread_body(ktc2_Obj* thread)
{
	ktc2_internal_threadEntry(thread);
	if (ktc2_has_pending()) {
		/* As on the JVM, an exception nobody caught ends this thread only: it is reported and the program goes on. */
		ktc2_Throwable* exception = (ktc2_Throwable*)ktc2_take_pending();
		static uint32_t reported;
		uint32_t number = ktc2_atomic_add(&reported, 1) - 1;
		fflush(stdout);
		fprintf(stderr, "Exception in thread \"Thread-%" PRIu32 "\" %s%s%s\n", number, ktc2_types[exception->header.typeId].name,
			exception->message ? ": " : "", exception->message ? exception->message->bytes : "");
		ktc2_release(exception);
	}
	ktc2_release(thread);
	pool_leave();
	free(destroy_later);
	destroy_later = NULL;
	destroy_later_room = 0;
	ktc2_atomic_sub(&threads_running, 1);
}

static void threads_wait(void)
{
	while (ktc2_atomic_load(&threads_running) != 0) thread_yield();
}

#ifdef _WIN32

static DWORD WINAPI thread_main(LPVOID argument)
{
	thread_body(argument);
	return 0;
}

int64_t ktc2_threadStart(ktc2_Obj* thread)
{
	ktc2_threaded = 1;
	ktc2_retain(thread);
	ktc2_atomic_add(&threads_running, 1);
	HANDLE handle = CreateThread(NULL, 0, thread_main, thread, 0, NULL);
	if (!handle) fail("java.lang.OutOfMemoryError", "unable to create native thread");
	return (int64_t)(intptr_t)handle;
}

void ktc2_threadJoin(int64_t handle)
{
	WaitForSingleObject((HANDLE)(intptr_t)handle, INFINITE);
	CloseHandle((HANDLE)(intptr_t)handle);
}

#else

static void* thread_main(void* argument)
{
	thread_body(argument);
	return NULL;
}

int64_t ktc2_threadStart(ktc2_Obj* thread)
{
	pthread_t* handle = malloc(sizeof *handle);
	ktc2_threaded = 1;
	ktc2_retain(thread);
	ktc2_atomic_add(&threads_running, 1);
	if (!handle || pthread_create(handle, NULL, thread_main, thread) != 0) fail("java.lang.OutOfMemoryError", "unable to create native thread");
	return (int64_t)(intptr_t)handle;
}

void ktc2_threadJoin(int64_t handle)
{
	pthread_t* thread = (pthread_t*)(intptr_t)handle;
	pthread_join(*thread, NULL);
	free(thread);
}

#endif

#endif

double ktc2_divide_by_zero(double a, double b)
{
	/* Read through a volatile, so that no compiler works the division out and objects to it. */
	volatile double divisor = b;
	return a / divisor;
}

/* The clock, in nanoseconds since 1970. */
int64_t ktc2_timeNanos(void)
{
#if defined(_WIN32)
	/* Windows counts 100 ns steps from 1601. */
	FILETIME now;
	GetSystemTimeAsFileTime(&now);
	int64_t steps = (int64_t)(((uint64_t)now.dwHighDateTime << 32) | now.dwLowDateTime);
	return (steps - INT64_C(116444736000000000)) * 100;
#elif defined(TIME_UTC)
	struct timespec now;
	timespec_get(&now, TIME_UTC);
	return (int64_t)now.tv_sec * INT64_C(1000000000) + now.tv_nsec;
#else
	return (int64_t)time(NULL) * INT64_C(1000000000);
#endif
}

/* Strict C11 hides the POSIX clock on Linux: it is there all the same, and CLOCK_MONOTONIC is 1. */
#if !defined(_WIN32) && !defined(CLOCK_MONOTONIC) && defined(__linux__)
int clock_gettime(int clock, struct timespec* now);
#define KTC2_CLOCK_MONOTONIC 1
#elif defined(CLOCK_MONOTONIC)
#define KTC2_CLOCK_MONOTONIC CLOCK_MONOTONIC
#endif

/* A counter that only goes forward, in nanoseconds from some moment: what durations are measured with. */
int64_t ktc2_ticksNanos(void)
{
#if defined(_WIN32)
	LARGE_INTEGER count;
	LARGE_INTEGER rate;
	QueryPerformanceCounter(&count);
	QueryPerformanceFrequency(&rate);
	/* Whole seconds and the rest apart: the product of the two would not fit. */
	return (int64_t)(count.QuadPart / rate.QuadPart) * INT64_C(1000000000) + (int64_t)(count.QuadPart % rate.QuadPart) * INT64_C(1000000000) / rate.QuadPart;
#elif defined(KTC2_CLOCK_MONOTONIC)
	struct timespec now;
	clock_gettime(KTC2_CLOCK_MONOTONIC, &now);
	return (int64_t)now.tv_sec * INT64_C(1000000000) + now.tv_nsec;
#else
	return ktc2_timeNanos();
#endif
}

/* `Thread.sleep`, which a program without threads may call too. */
#ifdef _WIN32

void ktc2_threadSleep(int64_t milliseconds)
{
	if (milliseconds > 0) Sleep((unsigned long)milliseconds);
}

#else

/* Declared here: <time.h> shows it only when asked for POSIX, which -std=c11 does not do. */
int nanosleep(const struct timespec* wanted, struct timespec* remaining);

void ktc2_threadSleep(int64_t milliseconds)
{
	if (milliseconds <= 0) return;
	struct timespec wanted = { (time_t)(milliseconds / 1000), (long)(milliseconds % 1000) * 1000000L };
	struct timespec remaining;
	/* An interrupted sleep goes on with what is left. */
	while (nanosleep(&wanted, &remaining) != 0) wanted = remaining;
}

#endif

#ifdef KTC2_THREADS

/*
 * `synchronized(object)`: a monitor per object, kept in a table while the object is locked or
 * waited for. An object that is never locked has no entry and pays nothing; the lock is
 * recursive, as Kotlin's is.
 */
typedef struct Monitor {
	const ktc2_Obj* object;
	uintptr_t owner;       /* the thread inside (the address of one of its thread-locals), or 0 */
	uint32_t depth;        /* how many times the owner entered */
	uint32_t waiting;      /* threads that want in */
	struct Monitor* next;
} Monitor;

#define MONITOR_BUCKETS 64

static Monitor* monitor_buckets[MONITOR_BUCKETS];
static KTC2_TLS char monitor_self;

static Monitor** monitor_link(const ktc2_Obj* object)
{
	Monitor** link = &monitor_buckets[(((uintptr_t)object >> 4) * 2654435761u >> 8) % MONITOR_BUCKETS];
	while (*link && (*link)->object != object) link = &(*link)->next;
	return link;
}

/*
 * The table has a lock of its own, and a thread that finds its monitor taken sleeps on a
 * condition until some monitor is given up.
 */
#ifdef _WIN32
static SRWLOCK monitor_mutex = SRWLOCK_INIT;
static CONDITION_VARIABLE monitor_freed = CONDITION_VARIABLE_INIT;
#define monitor_lock() AcquireSRWLockExclusive(&monitor_mutex)
#define monitor_unlock() ReleaseSRWLockExclusive(&monitor_mutex)
#define monitor_wait() SleepConditionVariableSRW(&monitor_freed, &monitor_mutex, INFINITE, 0)
#define monitor_wake() WakeAllConditionVariable(&monitor_freed)
#else
static pthread_mutex_t monitor_mutex = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t monitor_freed = PTHREAD_COND_INITIALIZER;
#define monitor_lock() pthread_mutex_lock(&monitor_mutex)
#define monitor_unlock() pthread_mutex_unlock(&monitor_mutex)
#define monitor_wait() pthread_cond_wait(&monitor_freed, &monitor_mutex)
#define monitor_wake() pthread_cond_broadcast(&monitor_freed)
#endif

void ktc2_monitorEnter(ktc2_Obj* lock)
{
	if (!lock) ktc2_fail_null();
	uintptr_t self = (uintptr_t)&monitor_self;
	monitor_lock();
	for (;;) {
		Monitor** link = monitor_link(lock);
		Monitor* monitor = *link;
		if (!monitor) {
			monitor = calloc(1, sizeof *monitor);
			if (!monitor) fail("java.lang.OutOfMemoryError", NULL);
			monitor->object = lock;
			*link = monitor;
		}
		if (monitor->owner == 0 || monitor->owner == self) {
			monitor->owner = self;
			monitor->depth++;
			break;
		}
		/* Held by another thread: registered as waiting, the entry stays while this thread sleeps. */
		monitor->waiting++;
		monitor_wait();
		(*monitor_link(lock))->waiting--;
	}
	monitor_unlock();
}

void ktc2_monitorExit(ktc2_Obj* lock)
{
	monitor_lock();
	Monitor** link = monitor_link(lock);
	Monitor* monitor = *link;
	if (monitor && --monitor->depth == 0) {
		monitor->owner = 0;
		if (monitor->waiting == 0) {
			*link = monitor->next;
			free(monitor);
		} else {
			monitor_wake();
		}
	}
	monitor_unlock();
}

/* The monitor of an object nobody else can name is the lock of first-use initialization. */
static ktc2_Obj init_lock = { KTC2_IMMORTAL, 0 };

void ktc2_initEnter(void)
{
	ktc2_monitorEnter(&init_lock);
}

void ktc2_initExit(void)
{
	ktc2_monitorExit(&init_lock);
}

#else

static void threads_wait(void) {}

/* Without threads nothing can contend for a monitor. */
void ktc2_monitorEnter(ktc2_Obj* lock)
{
	if (!lock) ktc2_fail_null();
}

void ktc2_monitorExit(ktc2_Obj* lock)
{
	(void)lock;
}

#endif

/* ================== */
/* MARK: Exceptions   */
/* ================== */

KTC2_TLS ktc2_Obj* ktc2_pending;

void ktc2_throw(ktc2_Obj* exception)
{
	if (!exception) ktc2_fail_null();
	ktc2_retain(exception);
	ktc2_release(ktc2_pending);
	ktc2_pending = exception;
}

ktc2_Obj* ktc2_take_pending(void)
{
	ktc2_Obj* exception = ktc2_pending;
	ktc2_pending = NULL;
	return exception;
}

ktc2_String* ktc2_throwable_toString(ktc2_Obj* exception, ktc2_String* message)
{
	ktc2_Sb sb;
	ktc2_sb_init(&sb);
	const char* name = ktc2_types[exception->typeId].name;
	int32_t size = (int32_t)strlen(name);
	ktc2_sb_text(&sb, name, size, size);
	if (message) {
		ktc2_sb_text(&sb, ": ", 2, 2);
		ktc2_sb_string(&sb, message);
	}
	return ktc2_sb_finish(&sb);
}

_Noreturn void ktc2_uncaught(void)
{
	const ktc2_Throwable* exception = (const ktc2_Throwable*)ktc2_pending;
	fail(ktc2_types[exception->header.typeId].name, exception->message ? exception->message->bytes : NULL);
}
