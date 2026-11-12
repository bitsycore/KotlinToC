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
 *   return) - so a jump can never skip an initialization or a release.
 * - Storing into a local, field, global or array element takes a reference (a retain unless
 *   the value is a fresh +1 result) and releases the previous content.
 * - An argument that is not a local is first held by a temporary, so the callee cannot free
 *   it by overwriting the field it was read from.
 *
 * Four optimizations remove counting that cannot be observed:
 * - a `val` field of an object that is itself safely held needs no temporary as an argument;
 * - a local read exactly once, by a store, hands over its reference instead of sharing it;
 * - a returned local is handed to the caller instead of being retained and then released;
 * - a local that is only another name for something held at least as long - a parameter, a
 *   local that is never reassigned, a `val` field of either - borrows instead of owning: no
 *   retain when it is declared, no release when its scope is left;
 * - the same for what is read from a `var` field, an array element or a top-level property,
 *   when nothing in the rest of the local's block can overwrite that place ([Effects]); and an
 *   argument read from such a place needs no temporary when the function called cannot
 *   overwrite it;
 * - a function that only hands out a reference something else keeps has a second form whose
 *   result is borrowed ([BorrowedReturns]), called where the result is held by a local and
 *   nothing in the rest of that local's block can overwrite the place it came from.
 */
class ArcInsert(
	private val mModule: KModule,
	/** A local read several times hands its reference over at the last read; false leaves that to locals read once. */
	private val mHandsOverAtLastUse: Boolean = true,
	/** A local may borrow up to its last use when the place it reads from is overwritten after that. */
	private val mBorrowsBriefly: Boolean = true,
) {

	fun run() {
		val vEffects = Effects(mModule)
		val vReturns = BorrowedReturns(mModule, vEffects)
		// The borrowed form of a function is built from its body as it is before this pass.
		val vOriginals = HashMap<KFunction, List<KStmt>>()
		for (vUnit in mModule.units) {
			for (vFunction in vUnit.functions.toList() + listOfNotNull(vUnit.initializer)) {
				if (vFunction.isExternal || vFunction.dispatch != null) continue
				val vOriginal = vFunction.body.toList()
				if (vReturns.of(vFunction) != null) vOriginals[vFunction] = vOriginal
				val vBody = FunctionPass(readCounts(vOriginal), reassigned(vOriginal), vFunction.params.toSet(), vEffects, vReturns, null, null).block(vOriginal, Scope(null))
				vFunction.body.clear()
				vFunction.body += vBody
			}
		}
		// The borrowed forms that were asked for; building one may ask for others.
		while (vReturns.pending.isNotEmpty()) {
			val vPending = vReturns.pending.removeLast()
			val vFunction = vPending.function
			val vInfo = vReturns.of(vFunction)!!
			if (vFunction.dispatch != null) {
				// The implementations whose result is borrowed, each in its own borrowed form (one that only throws has none).
				vPending.form.dispatch = vInfo.borrowedTargets.mapTo(ArrayList()) { (vClass, vTarget) ->
					vClass to if (vReturns.of(vTarget) != null) vReturns.form(vTarget) else vTarget
				}
				if (vInfo.isMixed) {
					// The others go through the ordinary dispatcher, and their result into the caller's variable.
					vPending.form.dispatchDefault = vFunction
					vPending.form.keepsDefault = true
				}
			} else {
				val vOriginal = vOriginals.getValue(vFunction)
				vPending.form.body += FunctionPass(readCounts(vOriginal), reassigned(vOriginal), vFunction.params.toSet(), vEffects, vReturns, vInfo, vPending.keep)
					.block(vOriginal, Scope(null))
			}
		}
	}

	/** The locals of [inBody] that are given a value somewhere else than in their declaration. */
	private fun reassigned(inBody: List<KStmt>): Set<KLocal> {
		val vResult = HashSet<KLocal>()
		fun block(inStatements: List<KStmt>) {
			for (vStatement in inStatements) {
				when (vStatement) {
					is KSet -> vResult += vStatement.local
					is KConcat -> vResult += vStatement.target
					is KIf -> { block(vStatement.thenBlock); block(vStatement.elseBlock) }
					is KLoop -> { block(vStatement.body); block(vStatement.latch) }
					else -> {}
				}
			}
		}
		block(inBody)
		return vResult
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

	private inner class FunctionPass(
		private val mReadCounts: Map<KLocal, Int>,
		private val mReassigned: Set<KLocal>,
		private val mParams: Set<KLocal>,
		private val mEffects: Effects,
		private val mReturns: BorrowedReturns,
		/** Set when the borrowed form of a function is being built: what it returns is not counted. */
		private val mBorrowedForm: BorrowedReturns.Info?,
		/** In a `__m` form: the parameter holding the address of the caller's variable for an owned result. */
		private val mKeep: KLocal?,
	) {
		/** The statements that run after the one being translated, in its block (for a loop: to the end of the iteration). */
		private var mRest: List<KStmt> = emptyList()

		/** Enclosing scopes, innermost last. */
		private val mScopes = ArrayList<Scope>()

		/** For each enclosing scope, the statements that run in it after the one being translated. */
		private val mRests = ArrayList<List<KStmt>>()

		/** Locals that borrow what they hold: they neither retain nor release it. */
		private val mBorrowed = HashSet<KLocal>()

		/**
		 * Borrowing locals whose borrow is only good up to their last use: something after it
		 * overwrites the place they read from. Nothing may depend on them for longer than that.
		 */
		private val mBriefly = HashSet<KLocal>()

		/** Owning locals something borrows from: they keep their reference to the end of their scope. */
		private val mLentOut = HashSet<KLocal>()

		/** True when a statement of [inBlock], or of a block inside it, reads [inLocal]. */
		private fun reads(inBlock: List<KStmt>, inLocal: KLocal): Boolean = inBlock.anyStatement { vStatement ->
			vStatement.ownExpressions().any { vRoot -> vRoot.anyNode { it is KRead && it.local === inLocal } }
		}

		/**
		 * True when [inLocal] holds the same object for as long as any scope opened after its
		 * declaration lasts: it is never reassigned, and it is a parameter (the caller holds the
		 * object), a borrowing local, or an owning local that keeps its reference to the end.
		 */
		private fun isSteady(inLocal: KLocal): Boolean {
			if (inLocal in mReassigned || inLocal in mBriefly) return false
			if (inLocal in mParams || inLocal in mBorrowed) return true
			val vOwner = mScopes.indexOfLast { inLocal in it.owned }
			if (vOwner < 0) return false
			// Read only here, as what is borrowed: the local hands its reference over instead.
			if (mReadCounts[inLocal] == 1 && (vOwner + 1 until mScopes.size).all { mScopes[it].loopId == null }) return false
			// From here on something counts on this local to keep its reference: it may no longer hand it over.
			mLentOut += inLocal
			return true
		}

		/**
		 * True when what [inValue] reads stays where it was read from, and so alive, while the code
		 * summarized by [inWritten] runs: a `val` field lives as long as its object, any other place
		 * as long as nothing overwrites it. [inIsHeld] decides for the local at the bottom.
		 */
		private fun stays(inValue: KExpr, inWritten: Lazy<Effects.Summary>, inIsHeld: (KExpr) -> Boolean): Boolean = when (inValue) {
			is KRefCast -> stays(inValue.operand, inWritten, inIsHeld)
			is KFieldRead -> BorrowedReturns.isPlainField(inValue) &&
				(inValue.field.isImmutable || !inWritten.value.writesField(inValue.field)) && stays(inValue.target, inWritten, inIsHeld)
			is KIndexRead -> !inWritten.value.writesElements() && stays(inValue.array, inWritten, inIsHeld)
			is KGlobalRead -> !inWritten.value.writesGlobal(inValue.global)
			else -> inIsHeld(inValue)
		}

		/** True when a local declared with [inValue] can borrow it for the rest of its block: see [isSteady] and [stays]. */
		private fun canBorrow(inValue: KExpr): Boolean {
			val vRest = mRest
			return stays(inValue, lazy { mEffects.of(vRest) }) { vBase ->
				(vBase is KRead && isSteady(vBase.local)) || vBase is KStaticRead || vBase is KStringLit
			}
		}

		/**
		 * What the rest of the block may write up to the last use of [inLocal]: the statements
		 * before the last one that reads it, and of that one what happens before its own store
		 * (`x.f = g(local)` computes `g(local)` first). After that the local is dead, and what is
		 * written no longer matters to it.
		 */
		private fun writtenWhileUsed(inLocal: KLocal): Effects.Summary {
			val vRest = mRest
			val vLast = vRest.indexOfLast { reads(listOf(it), inLocal) }
			if (vLast < 0) return Effects.Summary()
			return mEffects.of(vRest.subList(0, vLast)).also { it.add(mEffects.beforeStore(vRest[vLast])) }
		}

		/**
		 * True when [inLocal], declared with [inValue], can borrow it at least until its last use,
		 * though not for the whole block (`t = x.f; x.f = g(t)`). The local is then recorded in [mBriefly].
		 */
		private fun canBorrowBriefly(inLocal: KLocal, inValue: KExpr): Boolean {
			if (!mBorrowsBriefly) return false
			val vWritten = lazy { writtenWhileUsed(inLocal) }
			val vStays = stays(inValue, vWritten) { vBase ->
				(vBase is KRead && isSteady(vBase.local)) || vBase is KStaticRead || vBase is KStringLit
			}
			if (vStays) mBriefly += inLocal
			return vStays
		}
		private var mNextTemp = 0

		/** Locals whose reference was handed over by the statement being built: they are cleared after it. */
		private val mHandedOver = ArrayList<KLocal>()

		private fun localOf(inValue: KExpr): KLocal? = when (inValue) {
			is KRead -> inValue.local
			is KRefCast -> localOf(inValue.operand)
			else -> null
		}

		/**
		 * True when [inLocal] is an owning local read here for the last time, with no loop between
		 * its scope and this point (inside a loop the read would run many times).
		 */
		private fun canHandOver(inLocal: KLocal): Boolean {
			val vIndex = mScopes.indexOfLast { inLocal in it.owned }
			if (vIndex < 0) return false
			if (!(vIndex + 1 until mScopes.size).all { mScopes[it].loopId == null }) return false
			if (mReadCounts[inLocal] == 1) return true
			// Read elsewhere too, but this is the last time (`t = alloc; init(t); x.f = t`): nothing
			// that runs after this statement, in any scope up to the local's own, reads it again,
			// and nothing borrowed from it.
			if (!mHandsOverAtLastUse || inLocal in mLentOut) return false
			return (vIndex until mScopes.size).none { reads(mRests[it], inLocal) }
		}

		// ==================
		// MARK: Blocks
		// ==================

		fun block(inStatements: List<KStmt>, inScope: Scope): List<KStmt> {
			mScopes += inScope
			mRests += emptyList<KStmt>()
			val vOut = ArrayList<KStmt>()
			inStatements.forEachIndexed { vIndex, vStatement ->
				mRest = inStatements.subList(vIndex + 1, inStatements.size)
				mRests[mRests.size - 1] = mRest
				statement(vStatement, vOut)
			}
			mScopes.removeLast()
			mRests.removeLast()
			return declarations(inScope) + vOut + if (endsWithJump(vOut)) emptyList() else releases(inScope)
		}

		private fun loop(inLoop: KLoop): KLoop {
			val vScope = Scope(inLoop.id)
			mScopes += vScope
			mRests += emptyList<KStmt>()
			val vBody = ArrayList<KStmt>()
			val vLatch = ArrayList<KStmt>()
			inLoop.body.forEachIndexed { vIndex, vStatement ->
				mRest = inLoop.body.subList(vIndex + 1, inLoop.body.size) + inLoop.latch
				mRests[mRests.size - 1] = mRest
				statement(vStatement, vBody)
			}
			inLoop.latch.forEachIndexed { vIndex, vStatement ->
				mRest = inLoop.latch.subList(vIndex + 1, inLoop.latch.size)
				mRests[mRests.size - 1] = mRest
				statement(vStatement, vLatch)
			}
			mScopes.removeLast()
			mRests.removeLast()
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
			inValue is KNull || inValue is KZero || inValue is KStringLit || inValue is KStaticRead -> inValue
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
				// Read from a place the function called cannot overwrite: it stays there, and alive, for the call.
				if (!isRef(vArgument.type) || isSafeBorrow(vArgument) || stays(vArgument, lazy { mEffects.of(inValue.function) }, ::isSafeBorrow)) {
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
			// may have jumped past this point) it is cleared so the release there finds nothing. So is one
			// that is assigned again later: that assignment releases what it finds in the local.
			val vScope = mScopes.last()
			for (vLocal in mHandedOver) {
				if (vScope.loopId == null && vLocal in vScope.owned && vLocal !in mReassigned) vScope.spent += vLocal else outStatements += KSet(vLocal, KNull(vLocal.type))
			}
			mHandedOver.clear()
		}

		private fun translate(inStatement: KStmt, outStatements: MutableList<KStmt>) {
			when (inStatement) {
				is KLet -> {
					val vLocal = inStatement.local
					val vGiven = inStatement.init
					if (vGiven != null && isRef(vLocal.type) && vLocal.type !is KStructType && vLocal !in mReassigned &&
						(canBorrow(vGiven) || (!vGiven.isImpure() && canBorrowBriefly(vLocal, vGiven)))
					) {
						mBorrowed += vLocal
						outStatements += inStatement
						return
					}
					if (vGiven != null && vLocal.type is KRefType && vLocal !in mReassigned) {
						borrowedCall(vLocal, vGiven)?.let {
							mBorrowed += vLocal
							outStatements += KLet(vLocal, it)
							return
						}
					}
					val vInit = vGiven?.let { stored(it, vLocal.type, outStatements) }
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
					// The store keeps what is known of its index: tested already, it is not tested again by the runtime.
					outStatements += KIndexSet(inStatement.array, inStatement.index, stored(inStatement.value, inStatement.value.type, outStatements), vIsRef).also { it.isChecked = inStatement.isChecked }
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

		/**
		 * [inValue], the call that gives [inLocal] its value, as a call to the borrowed form of the
		 * function; null when the result has to be owned. It can be borrowed when the place it comes
		 * from, and the arguments it is reached through, stay as they are for the rest of the block.
		 */
		private fun borrowedCall(inLocal: KLocal, inValue: KExpr): KExpr? {
			val vCall = BorrowedReturns.callOf(inValue) ?: return null
			val vInfo = mReturns.of(vCall.function)
			if (vInfo == null) {
				if (System.getenv("KTC2_DEBUG_BORROW") != null) System.err.println("ktc2: borrow: call to ${vCall.function.cName}: its result is its own")
				return null
			}
			// In the borrowed form of a function, the calls its result comes from were checked when it qualified.
			if (mBorrowedForm?.callLocals?.get(inLocal) !== vCall.function) {
				val vRest = mEffects.of(mRest)
				if (vInfo.place.isWrittenBy(vRest)) {
					if (System.getenv("KTC2_DEBUG_BORROW") != null) {
						System.err.println("ktc2: borrow: result of ${vCall.function.cName} in ${inLocal.cName}: the rest of the block writes all=${vRest.all} elements=${vRest.elements && vInfo.place.elements} fields=${vInfo.place.fields.filter { vRest.writesField(it) }.map { it.cName }}")
					}
					return null
				}
				val vIsHeld = { vBase: KExpr -> (vBase is KRead && isSteady(vBase.local)) || vBase is KStaticRead || vBase is KStringLit || vBase is KNull }
				if (!vCall.arguments.all { !isRef(it.type) || stays(it, lazyOf(vRest), vIsHeld) }) {
					if (System.getenv("KTC2_DEBUG_BORROW") != null) System.err.println("ktc2: borrow: call to ${vCall.function.cName}: an argument may not stay")
					return null
				}
				if (System.getenv("KTC2_DEBUG_BORROW") != null) System.err.println("ktc2: borrow: call to ${vCall.function.cName}: borrowed")
			}
			// A mixed result may be an object of its own: it then also goes into a variable that owns it,
			// this function's (declared empty, released with the block) or, in a `__m` form, the caller's.
			val vKeep: KExpr? = when {
				!vInfo.isMixed -> null
				mBorrowedForm?.callLocals?.get(inLocal) === vCall.function -> KRead(mKeep!!)
				else -> KIntrinsic("ktc2_address", listOf(KRead(own(KObjType))), KPtrType).also { it.template = "&$0" }
			}
			fun rebuilt(inExpr: KExpr): KExpr = when (inExpr) {
				is KRefCast -> KRefCast(rebuilt(inExpr.operand), inExpr.type)
				else -> KCall(mReturns.form(vCall.function), vCall.arguments + listOfNotNull(vKeep))
			}
			return rebuilt(inValue)
		}

		private fun returnValue(inReturn: KReturn, outStatements: MutableList<KStmt>) {
			if (mBorrowedForm != null) {
				// What is returned stays held by the place it was read from: it goes out without a count.
				val vValue = inReturn.value!!
				if (mScopes.all { it.owned.isEmpty() }) {
					outStatements += KReturn(vValue)
					return
				}
				val vResult = KLocal("ktc2_r${mNextTemp++}", vValue.type)
				outStatements += KLet(vResult, vValue)
				for (vScope in mScopes.asReversed()) outStatements += releases(vScope)
				outStatements += KReturn(KRead(vResult))
				return
			}
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
