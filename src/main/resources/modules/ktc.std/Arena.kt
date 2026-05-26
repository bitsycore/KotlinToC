package ktc.std

/**
Arena allocator: bump-allocates from a caller-provided backing buffer in O(1).
All allocations are freed at once via reset() — individual slots cannot be
released. freeMem() is a deliberate no-op.
reallocMem() bump-allocates new space and copies; the old slot leaks within
the arena until the next reset().

The arena does NOT own its backing buffer. The caller is responsible for the
buffer's lifetime — typically a stack array, or a heap allocation freed by
the caller.

Implements Allocator so the arena can be passed wherever an Allocator is
expected — e.g. allocWith, resizeWith, or custom generic containers.

Stack-backed (zero heap overhead):
val vBuf = ByteArray(4096)
val vArena = Arena(vBuf.asRaw(), vBuf.size)
defer vArena.reset()

Heap-backed (caller owns and frees the buffer):
val vBuf: RawArray<Byte> = RawArray<Byte>(4096).allocWith(Heap)!!
defer Heap.freeMem(vBuf)
val vArena = Arena(vBuf, 4096)

String buffer backed by the arena:
val vSb = vArena.stringBuffer(256)
// use vSb with println, toString, etc.
 */
class Arena(
    val buf: RawArray<Byte>,
    val cap: Int
) : Allocator {

    private var bytesUsed: Int = 0

    /**
    Bump-allocate size bytes, aligned to 8 bytes.
    Panics on overflow — check remaining() first if the call site needs to be safe.
     */
    override fun allocMem(size: Int, file: String, line: Int): AnyPtr {
        val aligned = (size + 7) / 8 * 8   // round up to 8-byte alignment
        if (bytesUsed + aligned > cap) error("Arena overflow: requested $size bytes but only ${cap - bytesUsed} remaining")
        val ptr: AnyPtr = c.ktc_ptr_at(buf, bytesUsed)
        bytesUsed += aligned
        return ptr
    }

    /** No-op: arena allocations cannot be individually freed. */
    override fun freeMem(ptr: AnyPtr) {}

    /**
    Bump-allocates new space and copies ptr content (newSize bytes).
    The old slot leaks within the arena until reset() is called.
     */
    override fun reallocMem(ptr: AnyPtr, newSize: Int, file: String, line: Int): AnyPtr {
        val newPtr = allocMem(newSize, file, line)
        c.memcpy(newPtr, ptr, newSize)
        return newPtr
    }

    /** Free all allocations at once by resetting the bump pointer to zero. */
    fun reset() {
        bytesUsed = 0
    }

    /**
    Returns a StringBuffer backed by capacity bytes from this arena.
    Writes past capacity chars are silently dropped.
    Panics on overflow — check remaining() first if needed.
     */
    fun stringBuffer(capacity: Int): StringBuffer {
        val aligned = (capacity + 7) / 8 * 8   // round up to 8-byte alignment
        if (bytesUsed + aligned > cap) error("Arena overflow for StringBuffer: requested $capacity bytes but only ${cap - bytesUsed} remaining")
        val ptr: Ref<Char?> = c.ktc_ptr_at_char(buf, bytesUsed)
        bytesUsed += aligned
        return StringBuffer(ptr, 0, capacity)
    }

    /** Bytes bump-allocated so far. */
    fun used(): Int = bytesUsed

    /** Bytes remaining before the next allocMem would overflow. */
    fun remaining(): Int = cap - bytesUsed
}
