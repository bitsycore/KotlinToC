@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package atomicnativeptr

import kotlinx.cinterop.*

// Kotlin/Native's AtomicNativePtr, of `kotlin.concurrent` and of `kotlin.concurrent.atomics`: the JVM has neither.
// `.expected` is what Kotlin/Native prints.

fun main() {
	memScoped {
		val vFirst = alloc<IntVar>().ptr.rawValue
		val vSecond = alloc<IntVar>().ptr.rawValue

		val vOld = kotlin.concurrent.AtomicNativePtr(nativeNullPtr)
		println(vOld.value == nativeNullPtr)
		vOld.value = vFirst
		println(vOld.compareAndSet(vFirst, vSecond))
		println(vOld.compareAndSet(vFirst, vSecond))
		println(vOld.compareAndExchange(vSecond, vFirst) == vSecond)
		println(vOld.getAndSet(nativeNullPtr) == vFirst)
		println(vOld)

		val vNew = kotlin.concurrent.atomics.AtomicNativePtr(nativeNullPtr)
		println(vNew.load() == nativeNullPtr)
		vNew.store(vFirst)
		println(vNew.compareAndSet(vFirst, vSecond))
		println(vNew.compareAndSet(vFirst, vSecond))
		println(vNew.compareAndExchange(vFirst, nativeNullPtr) == vSecond)
		println(vNew.exchange(nativeNullPtr) == vSecond)
		println(vNew)
	}
}
