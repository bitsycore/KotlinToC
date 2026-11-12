package com.bitsycore.ktc2.kir

/**
 * A copy of this function under the name [inName], in the same unit (it is not added to it):
 * its own parameters and locals, and its own statements and expressions, so that a pass can
 * change one without the other. What the body refers to outside itself (functions, classes,
 * fields, top-level properties) is the same.
 */
fun KFunction.copyAs(inName: String): KFunction {
	val vCopier = Copier()
	val vCopy = KFunction(inName, unit, params.map { vCopier.local(it) }, returnType)
	vCopy.isExternal = isExternal
	vCopy.body += vCopier.block(body)
	return vCopy
}

/** Copies statements and expressions, giving each local it meets a counterpart. */
private class Copier {
	private val mLocals = HashMap<KLocal, KLocal>()

	fun local(inLocal: KLocal): KLocal = mLocals.getOrPut(inLocal) { KLocal(inLocal.cName, inLocal.type).also { it.isTemporary = inLocal.isTemporary } }

	fun block(inBlock: List<KStmt>): List<KStmt> = inBlock.map { statement(it) }

	private fun parts(inParts: List<KPrintPart>): List<KPrintPart> = inParts.map { vPart ->
		when (vPart) {
			is KPrintText -> vPart
			is KPrintValue -> KPrintValue(expr(vPart.value))
		}
	}

	private fun statement(inStatement: KStmt): KStmt = when (inStatement) {
		is KLet -> KLet(local(inStatement.local), inStatement.init?.let { expr(it) })
		is KSet -> KSet(local(inStatement.local), expr(inStatement.value), inStatement.releasesOld)
		is KGlobalSet -> KGlobalSet(inStatement.global, expr(inStatement.value), inStatement.releasesOld)
		is KFieldSet -> KFieldSet(expr(inStatement.target), inStatement.field, expr(inStatement.value), inStatement.releasesOld)
		is KIndexSet -> KIndexSet(expr(inStatement.array), expr(inStatement.index), expr(inStatement.value), inStatement.releasesOld).also { it.isChecked = inStatement.isChecked }
		is KEval -> KEval(expr(inStatement.expr))
		is KIf -> KIf(expr(inStatement.condition), block(inStatement.thenBlock), block(inStatement.elseBlock))
		is KLoop -> KLoop(inStatement.id, block(inStatement.body), block(inStatement.latch))
		is KBreak -> KBreak(inStatement.loopId)
		is KContinue -> KContinue(inStatement.loopId)
		is KReturn -> KReturn(inStatement.value?.let { expr(it) })
		is KPrint -> KPrint(parts(inStatement.parts), inStatement.newline)
		is KConcat -> KConcat(local(inStatement.target), parts(inStatement.parts)).also {
			it.onStack = inStatement.onStack
			it.origin = inStatement.origin
		}
		is KThrow -> KThrow(expr(inStatement.value))
		is KTryRegion -> KTryRegion(inStatement.id, block(inStatement.body))
		is KRelease -> KRelease(expr(inStatement.operand))
	}

	private fun expr(inExpr: KExpr): KExpr = when (inExpr) {
		is KConst, KUnitValue, is KNull, is KStringLit, is KGlobalRead, is KStaticRead, is KZero -> inExpr
		is KRead -> KRead(local(inExpr.local))
		is KBinary -> KBinary(inExpr.op, expr(inExpr.left), expr(inExpr.right), inExpr.operandType, inExpr.type)
		is KRefEquals -> KRefEquals(expr(inExpr.left), expr(inExpr.right), inExpr.negated)
		is KUnary -> KUnary(inExpr.op, expr(inExpr.operand), inExpr.type)
		is KConvert -> KConvert(expr(inExpr.operand), inExpr.from, inExpr.to)
		is KOptSome -> KOptSome(expr(inExpr.operand), inExpr.type)
		is KOptHas -> KOptHas(expr(inExpr.operand))
		is KOptValue -> KOptValue(expr(inExpr.operand))
		is KSelect -> KSelect(expr(inExpr.condition), expr(inExpr.whenTrue), expr(inExpr.whenFalse), inExpr.type)
		is KFieldRead -> KFieldRead(expr(inExpr.target), inExpr.field).also { it.needsCopy = inExpr.needsCopy }
		is KStructNew -> KStructNew(inExpr.struct, inExpr.values.map { expr(it) })
		is KRefCast -> KRefCast(expr(inExpr.operand), inExpr.type)
		is KInstanceOf -> KInstanceOf(expr(inExpr.operand), inExpr.target).also { it.answer = inExpr.answer }
		is KIndexRead -> KIndexRead(expr(inExpr.array), expr(inExpr.index), inExpr.type).also { it.isChecked = inExpr.isChecked }
		is KIntrinsic -> KIntrinsic(inExpr.name, inExpr.arguments.map { expr(it) }, inExpr.type, inExpr.isPure).also {
			it.template = inExpr.template
			it.headers = inExpr.headers
			it.functions = inExpr.functions
			it.trampoline = inExpr.trampoline
		}
		is KArrayNew -> KArrayNew(expr(inExpr.length), inExpr.type).also {
			it.onStack = inExpr.onStack
			it.forcedBy = inExpr.forcedBy
			it.origin = inExpr.origin
		}
		is KAlloc -> KAlloc(inExpr.cls).also {
			it.onStack = inExpr.onStack
			it.forcedBy = inExpr.forcedBy
			it.origin = inExpr.origin
		}
		is KCall -> KCall(inExpr.function, inExpr.arguments.map { expr(it) })
		is KStackSlot -> KStackSlot(inExpr.cls, inExpr.alternatives, inExpr.rooms?.let { local(it.first) to local(it.second) })
		is KRetain -> KRetain(expr(inExpr.operand))
	}
}
