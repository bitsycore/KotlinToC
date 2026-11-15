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
}
