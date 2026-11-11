package com.bitsycore.ktc2.backend

import com.bitsycore.ktc2.kir.*

/**
 * Adds reference counting to KIR (the naive, always-correct scheme; optimization comes later).
 *
 * Ownership rules:
 * - Parameters are borrowed: the caller keeps the object alive for the call.
 * - A call, an allocation or a string concatenation hands its result to the caller (+1).
 * - Every local of reference type owns what it holds. It is declared null at the start of its
 *   scope and released when the scope is left, whichever way (fall-through, break, continue,
 *   return) — so a jump can never skip an initialization or a release.
 * - Storing into a local, field, global or array element takes a reference (a retain unless
 *   the value is a fresh +1 result) and releases the previous content.
 * - An argument that is not a local is first held by a temporary, so the callee cannot free
 *   it by overwriting the field it was read from.
 *
 * Three optimizations remove counting that cannot be observed:
 * - a `val` field of an object that is itself safely held needs no temporary as an argument;
 * - a local read exactly once, by a store, hands over its reference instead of sharing it;
 * - a returned local is handed to the caller instead of being retained and then released.
 */
class ArcInsert(private val mModule: KModule) {

	fun run() {
		for (vUnit in mModule.units) {
			for (vFunction in vUnit.functions + listOfNotNull(vUnit.initializer)) {
				if (vFunction.isExternal || vFunction.dispatch != null) continue
				val vBody = FunctionPass(readCounts(vFunction.body)).block(vFunction.body, Scope(null))
				vFunction.body.clear()
				vFunction.body += vBody
			}
		}
	}

	/** How many times each local is read in [inBody]. */
	private fun readCounts(inBody: List<KStmt>): Map<KLocal, Int> {
		val vCounts = HashMap<KLocal, Int>()
		fun expr(inExpr: KExpr?) {
			when (inExpr) {
				null, is KConst, KUnitValue, is KNull, is KStringLit, is KGlobalRead, is KStaticRead, is KAlloc, is KZero, is KStackSlot -> {}
				is KRead -> vCounts.merge(inExpr.local, 1, Int::plus)
				is KBinary -> { expr(inExpr.left); expr(inExpr.right) }
				is KRefEquals -> { expr(inExpr.left); expr(inExpr.right) }
				is KUnary -> expr(inExpr.operand)
				is KConvert -> expr(inExpr.operand)
				is KOptSome -> expr(inExpr.operand)
				is KOptHas -> expr(inExpr.operand)
				is KOptValue -> expr(inExpr.operand)
				is KSelect -> { expr(inExpr.condition); expr(inExpr.whenTrue); expr(inExpr.whenFalse) }
				is KFieldRead -> expr(inExpr.target)
				is KStructNew -> inExpr.values.forEach { expr(it) }
				is KRefCast -> expr(inExpr.operand)
				is KInstanceOf -> expr(inExpr.operand)
				is KIndexRead -> { expr(inExpr.array); expr(inExpr.index) }
				is KArrayNew -> expr(inExpr.length)
				is KIntrinsic -> inExpr.arguments.forEach { expr(it) }
				is KCall -> inExpr.arguments.forEach { expr(it) }
				is KRetain -> expr(inExpr.operand)
			}
		}
		fun parts(inParts: List<KPrintPart>) = inParts.forEach { if (it is KPrintValue) expr(it.value) }
		fun block(inStatements: List<KStmt>) {
			for (vStatement in inStatements) {
				when (vStatement) {
					is KLet -> expr(vStatement.init)
					is KSet -> expr(vStatement.value)
					is KGlobalSet -> expr(vStatement.value)
					is KFieldSet -> { expr(vStatement.target); expr(vStatement.value) }
					is KIndexSet -> { expr(vStatement.array); expr(vStatement.index); expr(vStatement.value) }
					is KEval -> expr(vStatement.expr)
					is KIf -> { expr(vStatement.condition); block(vStatement.thenBlock); block(vStatement.elseBlock) }
					is KLoop -> { block(vStatement.body); block(vStatement.latch) }
					is KReturn -> expr(vStatement.value)
					is KPrint -> parts(vStatement.parts)
					is KConcat -> parts(vStatement.parts)
					is KRelease -> expr(vStatement.operand)
					is KBreak, is KContinue -> {}
					is KThrow, is KTryRegion -> error("exceptions are lowered before this pass")
				}
			}
		}
		block(inBody)
		return vCounts
	}

	/** One lexical scope: a function body, a branch, or a loop (body and latch together). */
	private class Scope(val loopId: Int?) {
		val owned = ArrayList<KLocal>()

		/** Locals of this scope that handed their reference over: nothing left to release from here on. */
		val spent = HashSet<KLocal>()
	}

	private class FunctionPass(private val mReadCounts: Map<KLocal, Int>) {
		/** Enclosing scopes, innermost last. */
		private val mScopes = ArrayList<Scope>()
		private var mNextTemp = 0

		/** Locals whose reference was handed over by the statement being built: they are cleared after it. */
		private val mHandedOver = ArrayList<KLocal>()

		private fun localOf(inValue: KExpr): KLocal? = when (inValue) {
			is KRead -> inValue.local
			is KRefCast -> localOf(inValue.operand)
			else -> null
		}

		/**
		 * True when [inLocal] is an owning local read only here, with no loop between its scope
		 * and this point (inside a loop the one read would run many times).
		 */
		private fun canHandOver(inLocal: KLocal): Boolean {
			if (mReadCounts[inLocal] != 1) return false
			val vIndex = mScopes.indexOfLast { inLocal in it.owned }
			if (vIndex < 0) return false
			return (vIndex + 1 until mScopes.size).all { mScopes[it].loopId == null }
		}

		// ==================
		// MARK: Blocks
		// ==================

		fun block(inStatements: List<KStmt>, inScope: Scope): List<KStmt> {
			mScopes += inScope
			val vOut = ArrayList<KStmt>()
			inStatements.forEach { statement(it, vOut) }
			mScopes.removeLast()
			return declarations(inScope) + vOut + if (endsWithJump(vOut)) emptyList() else releases(inScope)
		}

		private fun loop(inLoop: KLoop): KLoop {
			val vScope = Scope(inLoop.id)
			mScopes += vScope
			val vBody = ArrayList<KStmt>()
			val vLatch = ArrayList<KStmt>()
			inLoop.body.forEach { statement(it, vBody) }
			inLoop.latch.forEach { statement(it, vLatch) }
			mScopes.removeLast()
			// The latch runs at the end of every iteration, also after `continue`: that is where
			// the iteration's locals are released.
			return KLoop(inLoop.id, declarations(vScope) + vBody, vLatch + releases(vScope))
		}

		private fun declarations(inScope: Scope): List<KStmt> = inScope.owned.map { KLet(it, KNull(it.type)) }

		private fun releases(inScope: Scope): List<KStmt> =
			inScope.owned.asReversed().filter { it !in inScope.spent }.map { KRelease(KRead(it)) }

		private fun endsWithJump(inStatements: List<KStmt>): Boolean = when (inStatements.lastOrNull()) {
			is KReturn, is KBreak, is KContinue -> true
			else -> false
		}

		private fun own(inType: KType): KLocal = KLocal("ktc2_t${mNextTemp++}", inType).also { mScopes.last().owned += it }

		// ==================
		// MARK: Values
		// ==================

		private fun isRef(inType: KType): Boolean = inType.isCounted()

		/** True when [inValue] can be passed without holding it: a local, or something that never dies. */
		private fun isSafeBorrow(inValue: KExpr): Boolean = when (inValue) {
			is KRead, is KNull, is KStringLit, is KStaticRead, is KStackSlot -> true
			is KRefCast -> isSafeBorrow(inValue.operand)
			// A `val` field cannot be overwritten: it lives as long as its object does.
			is KFieldRead -> inValue.field.isImmutable && isSafeBorrow(inValue.target)
			else -> false
		}

		/** [inValue] as a +1 reference: itself when it is a fresh result, retained otherwise. */
		private fun owned(inValue: KExpr): KExpr = when {
			inValue.isImpure() -> inValue
			inValue is KNull || inValue is KStringLit || inValue is KStaticRead -> inValue
			inValue is KRefCast && (inValue.operand is KNull || inValue.operand is KStringLit || inValue.operand is KStaticRead) -> inValue
			localOf(inValue)?.let { canHandOver(it) } == true -> {
				mHandedOver += localOf(inValue)!!
				inValue
			}
			else -> KRetain(inValue)
		}

		/** Holds the reference arguments of a call that are not plain locals in temporaries. */
		private fun protectArguments(inValue: KExpr, outStatements: MutableList<KStmt>): KExpr = when (inValue) {
			is KRefCast -> KRefCast(protectArguments(inValue.operand, outStatements), inValue.type)
			is KCall -> KCall(inValue.function, inValue.arguments.map { vArgument ->
				if (!isRef(vArgument.type) || isSafeBorrow(vArgument)) {
					vArgument
				} else {
					val vHolder = own(vArgument.type)
					outStatements += KSet(vHolder, KRetain(vArgument))
					KRead(vHolder)
				}
			})
			else -> inValue
		}

		/** Prepares the right-hand side of a store into a slot of type [inSlotType]. */
		private fun stored(inValue: KExpr, inSlotType: KType, outStatements: MutableList<KStmt>): KExpr {
			val vValue = protectArguments(inValue, outStatements)
			return if (isRef(inSlotType)) owned(vValue) else vValue
		}

		// ==================
		// MARK: Statements
		// ==================

		private fun statement(inStatement: KStmt, outStatements: MutableList<KStmt>) {
			translate(inStatement, outStatements)
			// A local that handed its reference over no longer holds it. In its own, straight-line
			// scope every later exit simply skips it; elsewhere (a branch, or a loop whose `continue`
			// may have jumped past this point) it is cleared so the release there finds nothing.
			val vScope = mScopes.last()
			for (vLocal in mHandedOver) {
				if (vScope.loopId == null && vLocal in vScope.owned) vScope.spent += vLocal else outStatements += KSet(vLocal, KNull(vLocal.type))
			}
			mHandedOver.clear()
		}

		private fun translate(inStatement: KStmt, outStatements: MutableList<KStmt>) {
			when (inStatement) {
				is KLet -> {
					val vLocal = inStatement.local
					val vInit = inStatement.init?.let { stored(it, vLocal.type, outStatements) }
					if (isRef(vLocal.type)) {
						mScopes.last().owned += vLocal
						if (vInit != null) outStatements += KSet(vLocal, vInit)
					} else {
						outStatements += KLet(vLocal, vInit)
					}
				}
				is KSet -> {
					val vIsRef = isRef(inStatement.local.type)
					outStatements += KSet(inStatement.local, stored(inStatement.value, inStatement.local.type, outStatements), vIsRef)
				}
				is KGlobalSet -> {
					val vIsRef = isRef(inStatement.global.type)
					outStatements += KGlobalSet(inStatement.global, stored(inStatement.value, inStatement.global.type, outStatements), vIsRef)
				}
				is KFieldSet -> {
					// An inline field is assigned by copying content, not by taking a reference.
					val vIsRef = isRef(inStatement.field.type) && !inStatement.field.isInline
					outStatements += KFieldSet(inStatement.target, inStatement.field, stored(inStatement.value, if (vIsRef) inStatement.field.type else KUnitType, outStatements), vIsRef)
				}
				is KIndexSet -> {
					val vIsRef = isRef(inStatement.value.type)
					outStatements += KIndexSet(inStatement.array, inStatement.index, stored(inStatement.value, inStatement.value.type, outStatements), vIsRef)
				}
				is KEval -> {
					val vValue = protectArguments(inStatement.expr, outStatements)
					if (isRef(vValue.type) && vValue.isImpure()) {
						// A discarded +1 result still has to be released.
						outStatements += KSet(own(vValue.type), vValue)
					} else {
						outStatements += KEval(vValue)
					}
				}
				is KConcat -> {
					mScopes.last().owned += inStatement.target
					outStatements += inStatement
				}
				is KIf -> outStatements += KIf(inStatement.condition, block(inStatement.thenBlock, Scope(null)), block(inStatement.elseBlock, Scope(null)))
				is KLoop -> outStatements += loop(inStatement)
				is KBreak -> {
					leave(inStatement.loopId, true, outStatements)
					outStatements += inStatement
				}
				is KContinue -> {
					leave(inStatement.loopId, false, outStatements)
					outStatements += inStatement
				}
				is KReturn -> returnValue(inStatement, outStatements)
				is KPrint, is KRelease -> outStatements += inStatement
				is KThrow, is KTryRegion -> error("exceptions are lowered before this pass")
			}
		}

		/** Releases the scopes a jump to loop [inLoopId] leaves; a `break` also leaves the loop's own scope. */
		private fun leave(inLoopId: Int, inIncludingLoop: Boolean, outStatements: MutableList<KStmt>) {
			for (vScope in mScopes.asReversed()) {
				if (vScope.loopId == inLoopId) {
					if (inIncludingLoop) outStatements += releases(vScope)
					return
				}
				outStatements += releases(vScope)
			}
		}

		private fun returnValue(inReturn: KReturn, outStatements: MutableList<KStmt>) {
			// Returning an owning local hands its reference to the caller: no retain, no release.
			val vReturned = inReturn.value?.let { localOf(it) }?.takeIf { vLocal -> isRef(vLocal.type) && mScopes.any { vLocal in it.owned } }
			if (vReturned != null) {
				for (vScope in mScopes.asReversed()) {
					outStatements += vScope.owned.asReversed().filter { it !== vReturned && it !in vScope.spent }.map { KRelease(KRead(it)) }
				}
				outStatements += inReturn
				return
			}
			val vValue = inReturn.value?.let { stored(it, it.type, outStatements) }
			mHandedOver.clear()
			if (mScopes.all { it.owned.isEmpty() }) {
				outStatements += KReturn(vValue)
				return
			}
			// The result is computed before the locals it may read from are released.
			val vResult = vValue?.let { KLocal("ktc2_r${mNextTemp++}", it.type) }
			if (vResult != null) outStatements += KLet(vResult, vValue)
			for (vScope in mScopes.asReversed()) outStatements += releases(vScope)
			outStatements += KReturn(vResult?.let { KRead(it) })
		}
	}
}
