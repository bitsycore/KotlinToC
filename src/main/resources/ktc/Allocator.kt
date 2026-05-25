package ktc.std

interface Allocator {
    fun allocMem(size: Int, file: String = Macro.FILE, line: Int = Macro.LINE): AnyPtr
    fun freeMem(ptr: AnyPtr)
    fun reallocMem(ptr: AnyPtr, newSize: Int, file: String = Macro.FILE, line: Int = Macro.LINE): AnyPtr
}

object Heap : Allocator {
    override fun allocMem(size: Int, file: String, line: Int): AnyPtr {
        return c.ktc_core_malloc(size, file.ptr, line)
    }

    override fun freeMem(ptr: AnyPtr) {
        c.free(ptr)
    }

    override fun reallocMem(ptr: AnyPtr, newSize: Int, file: String, line: Int): AnyPtr {
        return c.ktc_core_realloc(ptr, newSize, file.ptr, line)
    }
}
