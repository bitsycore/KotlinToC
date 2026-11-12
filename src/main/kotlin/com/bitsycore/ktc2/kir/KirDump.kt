package com.bitsycore.ktc2.kir

import java.io.File

/**
 * Writes KIR as text, to see what the importer made of a program and what each pass did to
 * it: one file per stage, the same functions in the same order, so that two stages can be
 * compared with `diff`.
 *
 * The text is for reading, not for parsing back. It follows the statements one to one. What
 * a pass adds or decides is written where it applies: `retain(x)`, `release x`, a store that
 * gives up what was there (`<-` where a plain store is `=`), `on the stack`, an access proven
 * in range (`a[i in range]`), an `is` test that was answered, a local that is a temporary of
 * the importer (`let t~`).
 */
class KirDump(private val mModule: KModule) {

	/**
	 * Writes the functions [inWanted] accepts to [inFile], with the classes of their units.
	 *
	 * @param inStage what the module has been through, written at the top
	 */
	fun write(inFile: File, inStage: String, inWanted: (KFunction) -> Boolean) {
		val vText = StringBuilder("KIR $inStage\n")
		for (vUnit in mModule.units) {
			val vFunctions = (vUnit.functions + listOfNotNull(vUnit.initializer)).filter(inWanted)
			if (vFunctions.isEmpty()) continue
			vText.append("\n// ==================\n// unit ${vUnit.path}\n// ==================\n")
			vUnit.cls?.let { describe(it, vText) }
			vUnit.struct?.let { vStruct ->
				vText.append("\nstruct ${vStruct.cName} {\n")
				vStruct.fields.forEach { vText.append("\t${it.cName}: ${type(it.type)}\n") }
				vText.append("}\n")
			}
			vUnit.globals.forEach { vText.append("\nglobal ${it.cName}: ${type(it.type)}${it.constInit?.let { vInit -> " = ${expr(vInit)}" } ?: ""}\n") }
			vUnit.statics.forEach { vText.append("\nstatic ${it.cName}: ${it.cls.cName}\n") }
			for (vFunction in vFunctions) {
				vText.append('\n')
				function(vFunction, vFunction === vUnit.initializer, vText)
			}
		}
		inFile.parentFile?.mkdirs()
		inFile.writeText(vText.toString())
	}

	// ==================
	// MARK: Declarations
	// ==================

	private fun describe(inClass: KClass, outText: StringBuilder) {
		outText.append("\nclass ${inClass.cName}")
		inClass.superClass?.let { outText.append(" : ${it.cName}") }
		if (inClass.interfaces.isNotEmpty()) outText.append(" implements ${inClass.interfaces.joinToString(", ") { it.name }}")
		val vNotes = listOfNotNull(
			"created".takeIf { inClass.isInstantiated },
			"held in place somewhere".takeIf { inClass.isUsedInline },
			inClass.deinit?.let { "ends with ${it.cName}" },
		)
		outText.append(" {")
		if (vNotes.isNotEmpty()) outText.append("  // ${vNotes.joinToString(", ")}")
		outText.append('\n')
		for (vField in inClass.fields) {
			val vMarks = listOfNotNull(
				"val".takeIf { vField.isImmutable },
				"in place[${vField.inlineSize}]".takeIf { vField.isInline },
				vField.weakType?.let { "weak ${type(it)}" },
				"acyclic".takeIf { vField.isAcyclic },
			)
			outText.append("\t${vField.cName}: ${type(vField.type)}${if (vMarks.isEmpty()) "" else "  // ${vMarks.joinToString(", ")}"}\n")
		}
		outText.append("}\n")
	}

	private fun function(inFunction: KFunction, inIsInitializer: Boolean, outText: StringBuilder) {
		val vParams = inFunction.params.joinToString(", ") { "${it.cName}: ${type(it.type)}" }
		val vKind = when {
			inFunction.isExternal -> "external fun"
			inFunction.dispatch != null -> "dispatcher"
			inIsInitializer -> "initializer"
			else -> "fun"
		}
		outText.append("$vKind ${inFunction.cName}($vParams): ${type(inFunction.returnType)}")
		val vDispatch = inFunction.dispatch
		if (inFunction.isExternal) {
			outText.append('\n')
		} else if (vDispatch != null) {
			outText.append(" {\n")
			for ((vClass, vTarget) in vDispatch) outText.append("\t${vClass.cName} -> ${vTarget.cName}\n")
			inFunction.dispatchDefault?.let { outText.append("\telse -> ${it.cName}${if (inFunction.keepsDefault) " (its result is kept)" else ""}\n") }
			outText.append("}\n")
		} else {
			outText.append(" {\n")
			block(inFunction.body, 1, outText)
			outText.append("}\n")
		}
	}

	// ==================
	// MARK: Statements
	// ==================

	private fun block(inBlock: List<KStmt>, inDepth: Int, outText: StringBuilder) {
		val vIndent = "\t".repeat(inDepth)
		for (vStatement in inBlock) {
			when (vStatement) {
				is KIf -> {
					outText.append("${vIndent}if ${expr(vStatement.condition)} {\n")
					block(vStatement.thenBlock, inDepth + 1, outText)
					if (vStatement.elseBlock.isNotEmpty()) {
						outText.append("$vIndent} else {\n")
						block(vStatement.elseBlock, inDepth + 1, outText)
					}
					outText.append("$vIndent}\n")
				}
				is KLoop -> {
					outText.append("${vIndent}loop ${vStatement.id} {\n")
					block(vStatement.body, inDepth + 1, outText)
					if (vStatement.latch.isNotEmpty()) {
						outText.append("$vIndent} latch {\n")
						block(vStatement.latch, inDepth + 1, outText)
					}
					outText.append("$vIndent}\n")
				}
				is KTryRegion -> {
					outText.append("${vIndent}try ${vStatement.id} {\n")
					block(vStatement.body, inDepth + 1, outText)
					outText.append("$vIndent}\n")
				}
				else -> outText.append(vIndent).append(statement(vStatement)).append('\n')
			}
		}
	}

	private fun statement(inStatement: KStmt): String = when (inStatement) {
		is KLet -> "let ${local(inStatement.local)}: ${type(inStatement.local.type)}" + (inStatement.init?.let { " = ${expr(it)}" } ?: "")
		is KSet -> "${inStatement.local.cName} ${store(inStatement.releasesOld)} ${expr(inStatement.value)}"
		is KGlobalSet -> "${inStatement.global.cName} ${store(inStatement.releasesOld)} ${expr(inStatement.value)}"
		is KFieldSet -> "${operand(inStatement.target)}.${inStatement.field.cName} ${store(inStatement.releasesOld)} ${expr(inStatement.value)}"
		is KIndexSet -> "${operand(inStatement.array)}[${expr(inStatement.index)}${if (inStatement.isChecked) " in range" else ""}] ${store(inStatement.releasesOld)} ${expr(inStatement.value)}"
		is KEval -> expr(inStatement.expr)
		is KReturn -> "return" + (inStatement.value?.let { " ${expr(it)}" } ?: "")
		is KBreak -> "break ${inStatement.loopId}"
		is KContinue -> "continue ${inStatement.loopId}"
		is KThrow -> "throw ${expr(inStatement.value)}"
		is KRelease -> "release ${expr(inStatement.operand)}"
		is KPrint -> (if (inStatement.newline) "println " else "print ") + parts(inStatement.parts)
		is KConcat -> "${inStatement.target.cName} = concat ${parts(inStatement.parts)}${if (inStatement.onStack) " on the stack" else ""}"
		is KIf, is KLoop, is KTryRegion -> error("written by block")
	}

	/** A store that gives up what the place held is written `<-`. */
	private fun store(inReleasesOld: Boolean): String = if (inReleasesOld) "<-" else "="

	private fun local(inLocal: KLocal): String = inLocal.cName + if (inLocal.isTemporary) "~" else ""

	private fun parts(inParts: List<KPrintPart>): String = inParts.joinToString(" + ") { vPart ->
		when (vPart) {
			is KPrintText -> quoted(vPart.text)
			is KPrintValue -> expr(vPart.value)
		}
	}

	// ==================
	// MARK: Expressions
	// ==================

	private fun quoted(inText: String): String =
		"\"" + inText.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\t", "\\t").replace("\r", "\\r") + "\""

	/** [inValue] as the left side of `.field` or `[index]`: in parentheses unless it is a name. */
	private fun operand(inValue: KExpr): String = when (inValue) {
		is KRead, is KGlobalRead, is KStaticRead, is KFieldRead, is KIndexRead -> expr(inValue)
		else -> "(${expr(inValue)})"
	}

	private fun expr(inExpr: KExpr): String = when (inExpr) {
		is KConst -> when (val vValue = inExpr.value) {
			is Char -> "'${quoted(vValue.toString()).trim('"')}'"
			else -> "$vValue"
		}
		KUnitValue -> "unit"
		is KNull -> "null"
		is KZero -> "zero(${type(inExpr.type)})"
		is KStringLit -> quoted(inExpr.text)
		is KRead -> inExpr.local.cName
		is KGlobalRead -> inExpr.global.cName
		is KStaticRead -> inExpr.static.cName
		is KBinary -> "(${expr(inExpr.left)} ${inExpr.op.name.lowercase()} ${expr(inExpr.right)})"
		is KRefEquals -> "(${expr(inExpr.left)} ${if (inExpr.negated) "!==" else "==="} ${expr(inExpr.right)})"
		is KUnary -> "${inExpr.op.name.lowercase()} ${operand(inExpr.operand)}"
		is KConvert -> "${operand(inExpr.operand)} to ${inExpr.to.cName}"
		is KOptSome -> "some(${expr(inExpr.operand)})"
		is KOptHas -> "has(${expr(inExpr.operand)})"
		is KOptValue -> "value(${expr(inExpr.operand)})"
		is KSelect -> "(${expr(inExpr.condition)} ? ${expr(inExpr.whenTrue)} : ${expr(inExpr.whenFalse)})"
		is KFieldRead -> "${operand(inExpr.target)}.${inExpr.field.cName}${if (inExpr.needsCopy) " (copied out)" else ""}"
		is KStructNew -> "${inExpr.struct.cName}{${inExpr.values.joinToString(", ") { expr(it) }}}"
		is KRefCast -> "(${expr(inExpr.operand)} as ${type(inExpr.type)})"
		is KInstanceOf -> {
			val vTarget = when (val vOf = inExpr.target) {
				is KClassTarget -> vOf.cls.cName
				is KInterfaceTarget -> vOf.iface.name
				KStringTarget -> "String"
				is KBoxTarget -> "box ${vOf.prim.cName}"
			}
			val vAnswer = when (inExpr.answer) {
				true -> " (yes unless null)"
				false -> " (no)"
				null -> ""
			}
			"(${expr(inExpr.operand)} is $vTarget$vAnswer)"
		}
		is KIndexRead -> "${operand(inExpr.array)}[${expr(inExpr.index)}${if (inExpr.isChecked) " in range" else ""}]"
		is KArrayNew -> "new ${type(inExpr.type)}(${expr(inExpr.length)})${if (inExpr.onStack) " on the stack" else ""}"
		is KAlloc -> "new ${inExpr.cls.cName}${if (inExpr.onStack) " on the stack" else ""}"
		is KStackSlot -> inExpr.rooms?.let { "free one of ${it.first.cName}, ${it.second.cName} as ${inExpr.cls.cName}" }
			?: ("slot " + inExpr.alternatives.ifEmpty { listOf(inExpr.cls) }.joinToString(" | ") { it.cName })
		is KRetain -> "retain(${expr(inExpr.operand)})"
		is KCall -> "${inExpr.function.cName}(${inExpr.arguments.joinToString(", ") { expr(it) }})"
		is KIntrinsic -> {
			val vName = inExpr.template?.let { "c${quoted(it)}" } ?: inExpr.name
			"$vName${if (inExpr.isPure) "" else "!"}(${inExpr.arguments.joinToString(", ") { expr(it) }})"
		}
	}

	private fun type(inType: KType): String = when (inType) {
		is KPrimType -> inType.prim.cName
		KUnitType -> "unit"
		KNothingType -> "nothing"
		is KOptType -> "${inType.prim.cName}?"
		KPtrType -> "pointer"
		is KStructType -> "struct ${inType.struct.cName}"
		KStringType -> "String"
		is KClassType -> inType.cls.cName
		KObjType -> "Any"
		is KArrayType -> "[${type(inType.element)}]"
	}
}
