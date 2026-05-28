@file:DocumentationOnly
package ktc

/*
StringBuilder (maps to ktc_StrBuf in C): a mutable character buffer for
building strings without intermediate allocations.

The backing storage is NOT owned by the StringBuilder — it must be
provided by the caller (stack array, arena.stringBuffer(), etc.).

C representation:
    typedef struct { ktc_Char* ptr; ktc_Int len; ktc_Int cap; } ktc_StrBuf;

ptr = NULL activates counting mode: append calls only accumulate len
without writing, so the caller can measure the required capacity before
allocating.  ktc_core_sb_to_string() converts the buffer to a
null-terminated ktc_String.

Usage with Arena (persistent strings that outlive the current scope):
    val sb = arena.stringBuffer(64)
    sb.append("score: ")
    sb.appendInt(score)
    label = sb.toString()          // points into arena memory
    arena.reset()                  // bulk-free at frame end

Usage with stack buffer (temporary strings):
    val buf = ByteArray(128)
    val sb = StringBuffer(buf.asRef(), 0, buf.size)
    sb.append("hello ")
    sb.appendInt(42)
    println(sb.toString())

Append methods:
    fun append(s: String)
    fun appendInt(v: Int)
    fun appendLong(v: Long)
    fun appendFloat(v: Float)
    fun appendDouble(v: Double)
    fun appendBool(v: Boolean)
    fun appendChar(v: Char)
    fun toString(): String
*/
class StringBuffer(
	val ptr: RawArray<Char>,
	var len: Int,
	val cap: Int
) {
	fun append(inS: String) = error("Transpiler intrinsic")
	fun appendInt(inV: Int) = error("Transpiler intrinsic")
	fun appendLong(inV: Long) = error("Transpiler intrinsic")
	fun appendFloat(inV: Float) = error("Transpiler intrinsic")
	fun appendDouble(inV: Double) = error("Transpiler intrinsic")
	fun appendBool(inV: Boolean) = error("Transpiler intrinsic")
	fun appendChar(inV: Char) = error("Transpiler intrinsic")
	fun appendChar(inV: Ref<C.char>) = error("Transpiler intrinsic")
	override fun toString(): String = error("Transpiler intrinsic")
}
