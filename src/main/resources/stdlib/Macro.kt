@file:DocumentationOnly
package ktc

object Macro

/**
 * Get the current file name as a String.
 * This is a transpiler intrinsic replaced with the actual Kotlin source filename at the callsite.
 * On default arguments, it always captures the callsite file, not the callee file.
 * Usage: fun foo(file: String = Macro.FILE) { ... }
 */
val Macro.FILE: String
    get() = error("Transpiler intrinsic")

/**
 * Get the current line number as an Int.
 * This is a transpiler intrinsic replaced with the actual Kotlin source line at the callsite.
 * On default arguments, it always captures the callsite line, not the callee line.
 * Usage: fun foo(line: Int = Macro.LINE) { ... }
 */
val Macro.LINE: Int
    get() = error("Transpiler intrinsic")

/**
 * Get the current file in C code.
 * Emited as a ktc_core_str(__FILE__)
 */
val Macro.C_FILE: String
    get() = error("Transpiler intrinsic")

/**
 * Get the current line number in C code.
 * Emited as a ktc_Int
 */
val Macro.C_LINE: Int
    get() = error("Transpiler intrinsic")