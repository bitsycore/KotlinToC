package com.bitsycore.ktc2.backend

import com.bitsycore.ktc2.kir.*
import java.io.File

private val kRuntimeFiles = listOf("ktc2_core.h", "ktc2_core.c")

/**
 * Prints a [KModule] as C11: one `.h` / `.c` pair per [KUnit], the runtime, and `ktc2_main.c`.
 * KIR is already in statement form, so this is a direct print with no analysis.
 */
class CEmitter(private val mModule: KModule) {

	/**
	 * Writes all files under [inRoot].
	 *
	 * @return the `.c` files to compile, relative to [inRoot]
	 */
	fun emit(inRoot: File): List<String> {
		val vSources = ArrayList<String>()
		for (vUnit in mModule.units) {
			write(File(inRoot, vUnit.path + ".h"), header(vUnit))
			write(File(inRoot, vUnit.path + ".c"), source(vUnit))
			vSources += vUnit.path + ".c"
		}
		for (vName in kRuntimeFiles) {
			val vText = javaClass.getResourceAsStream("/ktc2/$vName")!!.use { it.readBytes().decodeToString() }
			write(File(inRoot, vName), vText)
			if (vName.endsWith(".c")) vSources += vName
		}
		mModule.entry?.let { vEntry ->
			val vStartup = mModule.units.mapNotNull { it.initializer }
			val vText = buildString {
				(vStartup.map { it.unit } + vEntry.unit).distinct().forEach { append("#include \"${it.path}.h\"\n") }
				append("\nint main(void)\n{\n")
				vStartup.forEach { append("\t${it.cName}();\n") }
				append("\t${vEntry.cName}();\n\treturn 0;\n}\n")
			}
			write(File(inRoot, "ktc2_main.c"), vText)
			vSources += "ktc2_main.c"
		}
		return vSources
	}

	/** Leaves an unchanged file untouched so the C build stays incremental. */
	private fun write(inFile: File, inText: String) {
		if (inFile.isFile && inFile.readText() == inText) return
		inFile.parentFile.mkdirs()
		inFile.writeText(inText)
	}

	// ==================
	// MARK: Units
	// ==================

	private fun header(inUnit: KUnit): String = buildString {
		val vGuard = "KTC2_" + inUnit.path.uppercase().map { if (it.isLetterOrDigit()) it else '_' }.joinToString("") + "_H"
		append("#ifndef $vGuard\n#define $vGuard\n\n#include \"ktc2_core.h\"\n")
		val vNeeded = LinkedHashSet<KUnit>()
		inUnit.struct?.fields?.forEach { unitOf(it.type)?.let(vNeeded::add) }
		inUnit.globals.forEach { unitOf(it.type)?.let(vNeeded::add) }
		for (vFunction in inUnit.functions) {
			unitOf(vFunction.returnType)?.let(vNeeded::add)
			vFunction.params.forEach { vParam -> unitOf(vParam.type)?.let(vNeeded::add) }
		}
		vNeeded.filter { it !== inUnit }.forEach { append("#include \"${it.path}.h\"\n") }
		append("\n")
		inUnit.struct?.let { vStruct ->
			append("typedef struct ${vStruct.cName} {\n")
			if (vStruct.fields.isEmpty()) append("\tchar _empty;\n")
			vStruct.fields.forEach { append("\t${cType(it.type)} ${it.cName};\n") }
			append("} ${vStruct.cName};\n\n")
		}
		inUnit.globals.forEach { append("extern ${cType(it.type)} ${it.cName};\n") }
		(inUnit.functions + listOfNotNull(inUnit.initializer)).forEach { append(signature(it)).append(";\n") }
		append("\n#endif\n")
	}

	private fun source(inUnit: KUnit): String {
		val vBodies = StringBuilder()
		val vIncludes = LinkedHashSet<KUnit>()
		val vPrinter = FunctionPrinter(vBodies, vIncludes)
		if (inUnit.globals.isNotEmpty()) vBodies.append("\n")
		for (vGlobal in inUnit.globals) {
			val vInit = vGlobal.constInit?.let { " = ${vPrinter.constant(it)}" } ?: ""
			vBodies.append("${cType(vGlobal.type)} ${vGlobal.cName}$vInit;\n")
		}
		for (vFunction in inUnit.functions + listOfNotNull(inUnit.initializer)) {
			vBodies.append("\n").append(signature(vFunction)).append("\n{\n")
			FunctionPrinter(vBodies, vIncludes).block(vFunction.body, 1)
			vBodies.append("}\n")
		}
		return buildString {
			append("#include \"${inUnit.path}.h\"\n")
			vIncludes.filter { it !== inUnit }.forEach { append("#include \"${it.path}.h\"\n") }
			append(vBodies)
		}
	}

	private fun unitOf(inType: KType): KUnit? = (inType as? KStructType)?.struct?.unit

	private fun cType(inType: KType): String = when (inType) {
		is KPrimType -> inType.prim.cName
		is KStructType -> inType.struct.cName
		KUnitType, KNothingType -> "void"
	}

	private fun signature(inFunction: KFunction): String {
		val vParams = if (inFunction.params.isEmpty()) "void" else inFunction.params.joinToString(", ") { "${cType(it.type)} ${it.cName}" }
		return "${cType(inFunction.returnType)} ${inFunction.cName}($vParams)"
	}

	// ==================
	// MARK: Function bodies
	// ==================

	private inner class FunctionPrinter(
		private val mOut: StringBuilder,
		private val mIncludes: MutableSet<KUnit>,
	) {
		/** Enclosing loops, innermost last. */
		private val mLoops = ArrayList<KLoop>()
		private val mBreakLabels = HashSet<Int>()
		private val mContinueLabels = HashSet<Int>()

		fun block(inStatements: List<KStmt>, inDepth: Int) {
			inStatements.forEach { statement(it, inDepth) }
		}

		private fun line(inDepth: Int, inText: String) {
			repeat(inDepth) { mOut.append('\t') }
			mOut.append(inText).append('\n')
		}

		private fun statement(inStatement: KStmt, inDepth: Int) {
			when (inStatement) {
				is KLet -> {
					unitOf(inStatement.local.type)?.let(mIncludes::add)
					val vInit = inStatement.init?.let { " = ${expr(it)}" } ?: ""
					line(inDepth, "${cType(inStatement.local.type)} ${inStatement.local.cName}$vInit;")
				}
				is KSet -> line(inDepth, "${inStatement.local.cName} = ${bare(expr(inStatement.value))};")
				is KGlobalSet -> {
					mIncludes += inStatement.global.unit
					line(inDepth, "${inStatement.global.cName} = ${bare(expr(inStatement.value))};")
				}
				is KEval -> line(inDepth, "${expr(inStatement.expr)};")
				is KReturn -> line(inDepth, inStatement.value?.let { "return ${expr(it)};" } ?: "return;")
				is KIf -> ifChain(inStatement, inDepth)
				is KLoop -> loop(inStatement, inDepth)
				is KBreak -> {
					if (mLoops.last().id == inStatement.loopId) {
						line(inDepth, "break;")
					} else {
						mBreakLabels += inStatement.loopId
						line(inDepth, "goto break_${inStatement.loopId};")
					}
				}
				is KContinue -> {
					val vInner = mLoops.last()
					if (vInner.id == inStatement.loopId && vInner.latch.isEmpty()) {
						line(inDepth, "continue;")
					} else {
						mContinueLabels += inStatement.loopId
						line(inDepth, "goto continue_${inStatement.loopId};")
					}
				}
				is KPrint -> print(inStatement, inDepth)
			}
		}

		private fun ifChain(inIf: KIf, inDepth: Int) {
			var vCurrent = inIf
			var vKeyword = "if"
			while (true) {
				line(inDepth, "$vKeyword (${bare(expr(vCurrent.condition))}) {")
				block(vCurrent.thenBlock, inDepth + 1)
				val vElse = vCurrent.elseBlock
				if (vElse.isEmpty()) break
				val vOnly = vElse.singleOrNull() as? KIf
				if (vOnly == null) {
					line(inDepth, "} else {")
					block(vElse, inDepth + 1)
					break
				}
				vCurrent = vOnly
				vKeyword = "} else if"
			}
			line(inDepth, "}")
		}

		private fun loop(inLoop: KLoop, inDepth: Int) {
			line(inDepth, "for (;;) {")
			mLoops += inLoop
			block(inLoop.body, inDepth + 1)
			if (inLoop.id in mContinueLabels) line(inDepth, "continue_${inLoop.id}: ;")
			// The latch belongs to the loop but is not a place `continue` may natively target.
			block(inLoop.latch, inDepth + 1)
			mLoops.removeLast()
			line(inDepth, "}")
			if (inLoop.id in mBreakLabels) line(inDepth, "break_${inLoop.id}: ;")
		}

		private fun print(inPrint: KPrint, inDepth: Int) {
			val vText = StringBuilder()
			fun flush() {
				if (vText.isNotEmpty()) line(inDepth, "ktc2_print_str(${cString(vText.toString())});")
				vText.clear()
			}
			for (vPart in inPrint.parts) {
				when (vPart) {
					is KPrintText -> vText.append(vPart.text)
					is KPrintValue -> {
						flush()
						val vSuffix = when ((vPart.value.type as KPrimType).prim) {
							KPrim.BOOLEAN -> "bool"
							KPrim.BYTE, KPrim.SHORT, KPrim.INT -> "int"
							KPrim.LONG -> "long"
							KPrim.CHAR -> "char"
							KPrim.FLOAT -> "float"
							KPrim.DOUBLE -> "double"
							KPrim.UBYTE, KPrim.USHORT, KPrim.UINT -> "uint"
							KPrim.ULONG -> "ulong"
						}
						line(inDepth, "ktc2_print_$vSuffix(${bare(expr(vPart.value))});")
					}
				}
			}
			if (inPrint.newline) vText.append('\n')
			flush()
		}

		// ============
		//  Expressions

		/** Drops one pair of redundant outer parentheses. */
		private fun bare(inText: String): String {
			if (!inText.startsWith("(") || !inText.endsWith(")")) return inText
			var vDepth = 0
			for (vIndex in inText.indices) {
				if (inText[vIndex] == '(') vDepth++ else if (inText[vIndex] == ')') vDepth--
				if (vDepth == 0 && vIndex < inText.length - 1) return inText
			}
			return inText.substring(1, inText.length - 1)
		}

		private fun expr(inExpr: KExpr): String = when (inExpr) {
			is KConst -> constant(inExpr)
			KUnitValue -> "((void)0)"
			is KRead -> inExpr.local.cName
			is KGlobalRead -> {
				mIncludes += inExpr.global.unit
				inExpr.global.cName
			}
			is KIntrinsic -> "${inExpr.name}(${inExpr.arguments.joinToString(", ") { bare(expr(it)) }})"
			is KUnary -> unary(inExpr)
			is KBinary -> binary(inExpr)
			is KConvert -> convert(inExpr)
			is KSelect -> when {
				(inExpr.whenFalse as? KConst)?.value == false -> "(${expr(inExpr.condition)} && ${expr(inExpr.whenTrue)})"
				(inExpr.whenTrue as? KConst)?.value == true -> "(${expr(inExpr.condition)} || ${expr(inExpr.whenFalse)})"
				else -> "(${expr(inExpr.condition)} ? ${expr(inExpr.whenTrue)} : ${expr(inExpr.whenFalse)})"
			}
			is KFieldRead -> "${expr(inExpr.target)}.${inExpr.field.cName}"
			is KStructNew -> {
				mIncludes += inExpr.struct.unit
				val vFields = inExpr.struct.fields.zip(inExpr.values).joinToString(", ") { (vField, vValue) -> ".${vField.cName} = ${bare(expr(vValue))}" }
				"(${inExpr.struct.cName}){ ${vFields.ifEmpty { "0" }} }"
			}
			is KCall -> {
				mIncludes += inExpr.function.unit
				"${inExpr.function.cName}(${inExpr.arguments.joinToString(", ") { bare(expr(it)) }})"
			}
		}

		fun constant(inConst: KConst): String = when (val vValue = inConst.value) {
			is Number if inConst.type.prim.isUnsigned -> when (inConst.type.prim) {
				KPrim.UBYTE -> "((uint8_t)${vValue.toInt() and 0xFF})"
				KPrim.USHORT -> "((uint16_t)${vValue.toInt() and 0xFFFF})"
				KPrim.UINT -> "UINT32_C(${vValue.toInt().toUInt()})"
				else -> "UINT64_C(${vValue.toLong().toULong()})"
			}
			is Boolean -> vValue.toString()
			is Char -> "((uint16_t)${vValue.code})"
			is Byte -> "((int8_t)${vValue.toInt()})"
			is Short -> "((int16_t)${vValue.toInt()})"
			is Int -> if (vValue == Int.MIN_VALUE) "INT32_MIN" else if (vValue < 0) "($vValue)" else "$vValue"
			is Long -> if (vValue == Long.MIN_VALUE) "INT64_MIN" else "INT64_C($vValue)"
			is Float -> when {
				vValue.isNaN() -> "NAN"
				vValue.isInfinite() -> if (vValue > 0) "INFINITY" else "(-INFINITY)"
				else -> if (vValue < 0 || 1 / vValue < 0) "(${vValue}f)" else "${vValue}f"
			}
			is Double -> when {
				vValue.isNaN() -> "((double)NAN)"
				vValue.isInfinite() -> if (vValue > 0) "((double)INFINITY)" else "(-(double)INFINITY)"
				else -> if (vValue < 0 || 1 / vValue < 0) "($vValue)" else "$vValue"
			}
			else -> error("unexpected constant ${vValue::class}")
		}

		private fun intPrefix(inPrim: KPrim): String? = when (inPrim) {
			KPrim.INT -> "ktc2_i"
			KPrim.LONG -> "ktc2_l"
			KPrim.UINT -> "ktc2_u"
			KPrim.ULONG -> "ktc2_ul"
			else -> null
		}

		private fun unary(inExpr: KUnary): String {
			val vOperand = expr(inExpr.operand)
			return when (inExpr.op) {
				KUnaryOp.NOT -> "(!$vOperand)"
				KUnaryOp.INV -> "(~$vOperand)"
				KUnaryOp.NEG -> intPrefix(inExpr.type.prim)?.let { "${it}neg(${bare(vOperand)})" } ?: "(-$vOperand)"
			}
		}

		private fun binary(inExpr: KBinary): String {
			val vLeft = expr(inExpr.left)
			val vRight = expr(inExpr.right)
			val vInt = intPrefix(inExpr.operandType)
			fun helper(inName: String) = "$vInt$inName(${bare(vLeft)}, ${bare(vRight)})"
			fun infix(inOp: String) = "($vLeft $inOp $vRight)"
			return when (inExpr.op) {
				KBinaryOp.ADD -> if (vInt != null) helper("add") else infix("+")
				KBinaryOp.SUB -> if (vInt != null) helper("sub") else infix("-")
				KBinaryOp.MUL -> if (vInt != null) helper("mul") else infix("*")
				KBinaryOp.DIV -> if (vInt != null) helper("div") else infix("/")
				KBinaryOp.REM -> when {
					vInt != null -> helper("rem")
					inExpr.operandType == KPrim.FLOAT -> "fmodf(${bare(vLeft)}, ${bare(vRight)})"
					else -> "fmod(${bare(vLeft)}, ${bare(vRight)})"
				}
				KBinaryOp.SHL -> helper("shl")
				KBinaryOp.SHR -> helper("shr")
				KBinaryOp.USHR -> helper("ushr")
				KBinaryOp.AND -> infix("&")
				KBinaryOp.OR -> infix("|")
				KBinaryOp.XOR -> infix("^")
				KBinaryOp.LT -> infix("<")
				KBinaryOp.LE -> infix("<=")
				KBinaryOp.GT -> infix(">")
				KBinaryOp.GE -> infix(">=")
				KBinaryOp.EQ -> infix("==")
				KBinaryOp.NE -> infix("!=")
				KBinaryOp.CMP -> "KTC2_CMP($vLeft, $vRight)"
			}
		}

		private fun convert(inExpr: KConvert): String {
			val vOperand = expr(inExpr.operand)
			if (inExpr.from.isFloating && !inExpr.to.isFloating) {
				val vHelper = when (inExpr.to) {
					KPrim.LONG -> "ktc2_d2l"
					KPrim.ULONG -> "ktc2_d2ul"
					KPrim.UBYTE, KPrim.USHORT, KPrim.UINT -> "ktc2_d2u"
					else -> "ktc2_d2i"
				}
				val vSaturated = "$vHelper(${bare(vOperand)})"
				val vExact = inExpr.to == KPrim.INT || inExpr.to == KPrim.LONG || inExpr.to == KPrim.UINT || inExpr.to == KPrim.ULONG
				return if (vExact) vSaturated else "((${inExpr.to.cName})$vSaturated)"
			}
			return "((${inExpr.to.cName})$vOperand)"
		}

		/** A C string literal holding the UTF-8 bytes of [inText]. */
		private fun cString(inText: String): String = buildString {
			append('"')
			for (vByte in inText.encodeToByteArray()) {
				val vCode = vByte.toInt() and 0xFF
				when {
					vCode == '"'.code -> append("\\\"")
					vCode == '\\'.code -> append("\\\\")
					vCode == '\n'.code -> append("\\n")
					vCode == '\t'.code -> append("\\t")
					vCode in 32..126 && vCode != '?'.code -> append(vCode.toChar())
					else -> append('\\').append(vCode.toString(8).padStart(3, '0'))
				}
			}
			append('"')
		}
	}
}
