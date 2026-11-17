@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

import kotlinx.cinterop.*
import widechars.*

// The shapes of <windows.h> that kotlinx-datetime's Windows part uses, on every system: a typedef as
// a type alias (WCHAR, DWORD, HANDLE and their Var), a `const WCHAR*` parameter given a String, a
// macro that is a cast to a number or to a pointer, a macro that names a function.

fun main() {
	println(wide_length("héllo wörld"))
	println(wide_length(""))
	memScoped {
		val vBuffer = allocArray<WCHARVar>(16)
		val vWritten: DWORD = wide_hello(vBuffer, 16u)
		println("$vWritten ${vBuffer.toKStringFromUtf16()}")
		val vSize = alloc<DWORDVar>()
		vSize.value = 7u
		println(vSize.value)
	}
	println(WIDE_INVALID == 0xFFFFFFFFu)
	val vRoot: HANDLE? = WIDE_ROOT
	println(wide_is_root(vRoot!!))
	memScoped { println(WideLength!!("abc".wcstr.ptr)) }
}
