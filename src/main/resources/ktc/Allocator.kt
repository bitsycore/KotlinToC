package ktc

interface Allocator {
    fun allocMem(size: Int, file: String = Macro.FILE, line: Int = Macro.LINE): AnyPtr
    fun freeMem(ptr: AnyPtr)
    fun reallocMem(ptr: AnyPtr, newSize: Int, file: String = Macro.FILE, line: Int = Macro.LINE): AnyPtr
}

@RequireFree
object Heap : Allocator {
    override fun allocMem(size: Int, file: String, line: Int): AnyPtr {
        return C.ktc_core_malloc(size, file.cPtr, line)
    }

    override fun freeMem(ptr: AnyPtr) {
        C.free(ptr)
    }

    override fun reallocMem(ptr: AnyPtr, newSize: Int, file: String, line: Int): AnyPtr {
        return C.ktc_core_realloc(ptr, newSize, file.cPtr, line)
    }
}
