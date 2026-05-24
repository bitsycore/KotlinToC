@file:DocumentationOnly
package ktc

/**
Allocate a single heap instance of T and call its primary constructor.
Usage:
    val p = HeapAlloc<MyClass>(args...)
    val arr = HeapAlloc<Array<T>>(n)
    val raw = HeapAlloc<RawArray<T>>(n)
*/
fun <T> HeapAlloc(vararg inArgs: Any): @Ptr T = error("Transpiler intrinsic")

/**
Alloc a zero-initialised heap array of n elements.
Works for both Array<T> and RawArray<T>.
Usage:
    HeapArrayZero<Array<T>>(n)
    HeapArrayZero<RawArray<T>>(n)
*/
fun <T> HeapArrayZero(inN: Int): @Ptr T = error("Transpiler intrinsic")

/**
Resize a heap array to n elements using realloc.
Works for both Array<T> and RawArray<T>.
Returns a new @Ptr to the possibly-moved allocation.
Usage: val newPtr = HeapArrayResize<T>(ptr, n)
*/
fun <T> HeapArrayResize(inPtr: @Ptr Array<T>, inN: Int): @Ptr Array<T> = error("Transpiler intrinsic")

/**
Free a heap allocation obtained from HeapAlloc.
Usage: Heap.freeMem(ptr)
*/
fun Heap.freeMem(inPtr: @Ptr Any) = error("Transpiler intrinsic")