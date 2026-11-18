@file:OptIn(ExperimentalNativeApi::class)

package kotlin.native

import kotlin.experimental.ExperimentalNativeApi
import kotlinx.cinterop.internal.cExpr

/*
ktc2 platform layer: `kotlin.native.Platform`, Kotlin/Native's account of the system a program runs
on, which libraries written for Kotlin/Native use (kotlinx-io asks it whether it is on Windows).
The answers are the C compiler's, about the system it compiles for (`KTC2_OS_FAMILY` and the
others in ktc2_core.h).
*/

/** The family of operating systems a program runs on. */
@ExperimentalNativeApi
public enum class OsFamily {
	UNKNOWN,
	MACOSX,
	IOS,
	LINUX,
	WINDOWS,
	ANDROID,
	WASM,
	TVOS,
	WATCHOS,
}

/** The processor a program runs on, with the size of its words. */
@ExperimentalNativeApi
public enum class CpuArchitecture(public val bitness: Int) {
	UNKNOWN(-1),
	ARM32(32),
	ARM64(64),
	X86(32),
	X64(64),
	MIPS32(32),
	MIPSEL32(32),
	WASM32(32),
}

/** What the program knows of the system it runs on. */
@ExperimentalNativeApi
public object Platform {
	public val osFamily: OsFamily get() = OsFamily.entries[cExpr<Int>("KTC2_OS_FAMILY")]

	public val cpuArchitecture: CpuArchitecture get() = CpuArchitecture.entries[cExpr<Int>("KTC2_CPU_ARCHITECTURE")]

	public val isLittleEndian: Boolean get() = cExpr("KTC2_LITTLE_ENDIAN")

	/** False: a ktc2 program reads a number only where it is aligned. */
	public val canAccessUnaligned: Boolean get() = false

	/** How many processors the system gives the program. */
	public fun getAvailableProcessors(): Int = ktc2.internal.availableProcessors()
}

/** The hash of this object by its identity, whatever its class says (0 for null). */
@ExperimentalNativeApi
public fun Any?.identityHashCode(): Int = if (this == null) 0 else ktc2.internal.identityHashCode(this)

// ==================
// MARK: Exceptions nothing caught
// ==================

/** What [processUnhandledException] gives an exception to. */
@ExperimentalNativeApi
public typealias ReportUnhandledExceptionHook = Function1<Throwable, Unit>

private val unhandledExceptionHook = kotlin.concurrent.AtomicReference<ReportUnhandledExceptionHook?>(null)

/** Sets what [processUnhandledException] gives an exception to; returns the one there was. */
@ExperimentalNativeApi
@IgnorableReturnValue
public fun setUnhandledExceptionHook(hook: ReportUnhandledExceptionHook?): ReportUnhandledExceptionHook? = unhandledExceptionHook.getAndSet(hook)

@ExperimentalNativeApi
public fun getUnhandledExceptionHook(): ReportUnhandledExceptionHook? = unhandledExceptionHook.value

/**
 * What is done with an exception nothing caught (in a job of a worker, at the end of a coroutine): it goes to
 * the hook when one is set ([setUnhandledExceptionHook]); otherwise the program ends, as at the end of `main`.
 */
@ExperimentalNativeApi
public fun processUnhandledException(throwable: Throwable) {
	val vHook = unhandledExceptionHook.value ?: terminateWithUnhandledException(throwable)
	try {
		vHook(throwable)
	} catch (vError: Throwable) {
		terminateWithUnhandledException(vError)
	}
}

/** Ends the program with [throwable], as an exception that leaves `main` does. */
@ExperimentalNativeApi
public fun terminateWithUnhandledException(throwable: Throwable): Nothing {
	ktc2.internal.terminateUncaught(throwable)
	throw throwable
}

// ==================
// MARK: Reading numbers out of byte arrays
// ==================

/** The Int whose bytes, in the order of the machine (little-endian on every system ktc2 compiles for), start at [index]. */
public fun ByteArray.getIntAt(index: Int): Int {
	if (index < 0 || index + 4 > size) throw IndexOutOfBoundsException("Index $index out of bounds for an Int in an array of size $size")
	return (this[index].toInt() and 0xFF) or ((this[index + 1].toInt() and 0xFF) shl 8) or
		((this[index + 2].toInt() and 0xFF) shl 16) or (this[index + 3].toInt() shl 24)
}

/** The Short whose bytes, in the order of the machine, start at [index]. */
public fun ByteArray.getShortAt(index: Int): Short {
	if (index < 0 || index + 2 > size) throw IndexOutOfBoundsException("Index $index out of bounds for a Short in an array of size $size")
	return ((this[index].toInt() and 0xFF) or (this[index + 1].toInt() shl 8)).toShort()
}

/** The Long whose bytes, in the order of the machine, start at [index]. */
public fun ByteArray.getLongAt(index: Int): Long {
	if (index < 0 || index + 8 > size) throw IndexOutOfBoundsException("Index $index out of bounds for a Long in an array of size $size")
	return (getIntAt(index).toLong() and 0xFFFFFFFFL) or (getIntAt(index + 4).toLong() shl 32)
}
