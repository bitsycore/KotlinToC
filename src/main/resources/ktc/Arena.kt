package ktc.std

/**
Arena allocator: bump-allocates from a fixed backing buffer in O(1).
All allocations are freed at once via reset() or free() — individual
slots cannot be released. freeMem() is a deliberate no-op.
reallocMem() bump-allocates new space and copies; the old slot leaks
within the arena until the next reset().

Implements Allocator so the arena can be passed wherever an Allocator
is expected — e.g. allocWith, resizeWith, or custom generic containers.

Stack-backed (zero heap overhead):
val vBuf = ByteArray(4096)
val vArena = Arena(vBuf.ptr(), vBuf.size)
defer vArena.reset()

Heap-backed:
val vArena = Arena(4096)
defer vArena.free()

String buffer backed by the arena:
val vSb = vArena.stringBuffer(256)
// use vSb with println, toString, etc.
 */
class Arena(
    var fBuf: @Ptr RawArray<Byte>,
    var fCap: Int,
    var fOwns: Boolean = false
) : Allocator {

    var fUsed: Int = 0

    /** Heap-backed: the arena allocates inCap bytes internally; call free() when done. */
    constructor(inCap: Int) : this(RawArray<Byte>(inCap).allocWith(Heap), inCap, true)

    /**
    Bump-allocate inSize bytes, aligned to 8 bytes.
    Panics on overflow — check remaining() first if the call site needs to be safe.
     */
    override fun allocMem(inSize: Int, inFile: String, inLine: Int): AnyPtr {
        val vAligned = (inSize + 7) / 8 * 8   // round up to 8-byte alignment
        if (fUsed + vAligned > fCap) error("Arena overflow: requested $inSize bytes but only ${fCap - fUsed} remaining")
        val vPtr: AnyPtr = c.ktc_ptr_at(fBuf, fUsed)
        fUsed += vAligned
        return vPtr
    }

    /** No-op: arena allocations cannot be individually freed. */
    override fun freeMem(inPtr: AnyPtr) {}

    /**
    Bump-allocates new space and copies inPtr content (inNewSize bytes).
    The old slot leaks within the arena until reset() is called.
     */
    override fun reallocMem(inPtr: AnyPtr, inNewSize: Int, inFile: String, inLine: Int): AnyPtr {
        val vNew = allocMem(inNewSize, inFile, inLine)
        c.memcpy(vNew, inPtr, inNewSize)
        return vNew
    }

    /** Free all allocations at once by resetting the bump pointer to zero. */
    fun reset() {
        fUsed = 0
    }

    /** Release the heap backing buffer. Only meaningful for heap-backed arenas. */
    fun free() {
        if (fOwns) {
            Heap.freeMem(fBuf)
        }
        fUsed = 0
        fCap = 0
    }

    /**
    Returns a StringBuffer backed by inCap bytes from this arena.
    Writes past inCap chars are silently dropped.
    Panics on overflow — check remaining() first if needed.
     */
    fun stringBuffer(inCap: Int): StringBuffer {
        val vAligned = (inCap + 7) / 8 * 8   // round up to 8-byte alignment
        if (fUsed + vAligned > fCap) error("Arena overflow for StringBuffer: requested $inCap bytes but only ${fCap - fUsed} remaining")
        val vPtr: @Ptr Char? = c.ktc_ptr_at_char(fBuf, fUsed)
        fUsed += vAligned
        return StringBuffer(vPtr, 0, inCap)
    }

    /** Bytes bump-allocated so far. */
    fun used(): Int = fUsed

    /** Bytes remaining before the next allocMem would overflow. */
    fun remaining(): Int = fCap - fUsed
}
