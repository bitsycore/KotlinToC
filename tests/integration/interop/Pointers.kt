@file:OptIn(ExperimentalForeignApi::class)
@file:CHeaders("<stdlib.h>", "<string.h>")

package interop

import kotlinx.cinterop.*
import kotlinx.cinterop.internal.CHeaders
import kotlinx.cinterop.internal.cExpr

// Bindings written by hand, the way the generator writes them.

fun strlen(text: String): Long = cExpr("(int64_t)strlen($0)", text)

fun strcpy(target: CValuesRef<ByteVar>?, source: CValuesRef<ByteVar>?) {
	cExpr<Unit>("strcpy($0, $1)", target, source)
}

fun qsort(base: CValuesRef<*>?, count: Long, size: Long, compare: CPointer<CFunction<(COpaquePointer?, COpaquePointer?) -> Int>>?) {
	cExpr<Unit>("qsort($0, (size_t)$1, (size_t)$2, (int (*)(const void*, const void*))$3)", base, count, size, compare)
}

fun compareInts(a: COpaquePointer?, b: COpaquePointer?): Int {
	val vLeft = a!!.reinterpret<IntVar>().pointed.value
	val vRight = b!!.reinterpret<IntVar>().pointed.value
	return vLeft.compareTo(vRight)
}

class Session(val name: String) {
	var hits = 0
}

/** What a C library would call back with the user data it was given. */
fun touch(inUserData: COpaquePointer?) {
	val vSession = inUserData!!.asStableRef<Session>().get()
	vSession.hits++
}

fun main() {
	memScoped {
		val vCount = alloc<IntVar>()
		println(vCount.value)
		vCount.value = 41
		vCount.value += 1
		println(vCount.value)
		val vPointer: CPointer<IntVar> = vCount.ptr
		vPointer.pointed.value = 7
		println(vCount.value)

		val vNumbers = allocArray<IntVar>(5)
		for (vIndex in 0 until 5) vNumbers[vIndex] = (5 - vIndex) * 10
		qsort(vNumbers, 5, sizeOf<IntVar>(), staticCFunction(::compareInts))
		var vLine = ""
		for (vIndex in 0 until 5) vLine += "${vNumbers[vIndex]} "
		println(vLine.trim())
		println((vNumbers + 2)!!.pointed.value)

		val vDoubles = allocArray<DoubleVar>(3)
		vDoubles[0] = 1.5
		vDoubles[1] = 2.5
		println(vDoubles[0] + vDoubles[1] + vDoubles[2])

		val vSlot = alloc<CPointerVar<IntVar>>()
		println(vSlot.value == null)
		vSlot.value = vNumbers
		println(vSlot.pointed?.value)

		val vText = "from Kotlin"
		val vCopy = allocArray<ByteVar>(32)
		strcpy(vCopy, vText.cstr)
		vCopy[0] = 'F'.code.toByte()
		println(vCopy.toKString())
		println(vText.cstr.ptr.toKString() == vText)
	}
	println(strlen("héllo"))

	val vBytes = byteArrayOf(72, 105, 0)
	println(vBytes.usePinned { it.addressOf(0).toKString() })
	val vInts = intArrayOf(3, 1, 2)
	qsort(vInts.refTo(0), 3, 4, staticCFunction(::compareInts))
	println("${vInts[0]} ${vInts[1]} ${vInts[2]}")

	val vHeap = nativeHeap.alloc<LongVar>()
	vHeap.value = 1L shl 40
	println(vHeap.value)
	println(vHeap.ptr.toLong() != 0L)
	nativeHeap.free(vHeap)

	val vSession = Session("main")
	val vRef = StableRef.create(vSession)
	val vUserData: COpaquePointer = vRef.asCPointer()
	touch(vUserData)
	touch(vUserData)
	vRef.dispose()
	println("${vSession.name} ${vSession.hits}")

	val vNothing: CPointer<IntVar>? = null
	println(vNothing == null)
	println(vNothing?.pointed?.value ?: -1)

	// Pointers kept in a collection.
	val vCells = ArrayList<CPointer<IntVar>>()
	for (vIndex in 0 until 3) {
		val vCell = nativeHeap.alloc<IntVar>()
		vCell.value = vIndex * vIndex
		vCells.add(vCell.ptr)
	}
	var vSum = 0
	for (vCell in vCells) {
		vSum += vCell.pointed.value
		nativeHeap.free(vCell)
	}
	println(vSum)
	println("done")
}
