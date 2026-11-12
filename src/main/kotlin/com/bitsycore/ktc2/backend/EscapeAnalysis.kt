package com.bitsycore.ktc2.backend

import com.bitsycore.ktc2.kir.*

/** Largest array (in elements) that is placed on the stack. */
private const val kStackArrayLimit = 64

/**
 * Finds the objects that never leave the block they are created in, and marks their
 * allocation to use the stack. Runs before `ArcInsert`.
 *
 * A reference *escapes* when it is returned, stored into a field, a global or an array
 * element, or passed to a parameter that escapes. One store into a field is not an escape:
 * into an object this function made and that does not escape itself (a closure taking what
 * its lambda uses, mostly). The object stored then lives as long as that holder may, as if
 * the holder were one more local it is copied to - provided nothing that reads the field
 * anywhere in the program lets the value escape (a field that does is *leaky*). Whether a parameter escapes is a summary
 * computed for every function to a fixpoint over the whole program; a dispatcher's summary is
 * the union of its targets', and a runtime function is assumed to keep what it is given.
 *
 * An allocation is moved to the stack when neither the local that receives it nor any local
 * it is copied to escapes, and all of those are declared inside the block of the allocation -
 * so nothing can still refer to the object when that block ends. This covers class instances
 * (closures and cells included), strings built by concatenation, and arrays of a small
 * constant size.
 *
 * It also covers the object a function *returns*. A function whose result is always the one
 * object it allocates, and which lets it go nowhere else, gets a second form that builds the
 * object in room supplied by the caller (`make__into(slot, …)`). A call whose result stays in
 * its block then passes a slot of its own stack frame, and the object is never on the heap.
 */
class EscapeAnalysis(private val mModule: KModule) {

	/** For each function, its in-place form and the class it builds; null when it has none. */
	private val mInPlace = HashMap<KFunction, Pair<KFunction, KClass>?>()

	/** In-place forms created during promotion, which still have to be promoted themselves. */
	private val mNewFunctions = ArrayList<KFunction>()

	/** Parameters and locals whose reference may outlive the block that holds them, with the first reason found. */
	private val mEscaping = HashMap<KLocal, String>()

	/**
	 * The fields some read of which lets the value escape, by [fieldKey]: what is stored in one
	 * may go anywhere.
	 */
	private val mLeaky = HashSet<Any>()

	/**
	 * For a parameter that a function stores into a field of the object another of its
	 * parameters refers to (a constructor keeping what it is given, a setter): the index of that
	 * other parameter and the field, by [fieldKey]. What a caller passes then goes where the
	 * object it passes for the other parameter goes, and no further - as long as the field is
	 * not leaky.
	 */
	private val mParamHeld = HashMap<KLocal, MutableSet<Pair<Int, Any>>>()

	/**
	 * For a parameter that a function stores into the new object it returns (`iterator()` keeping
	 * the list it goes over): the fields, by [fieldKey]. What a caller passes then lives as long
	 * as what it does with the result, and no longer.
	 */
	private val mResultHeld = HashMap<KLocal, MutableSet<Any>>()

	/**
	 * What a field is known by: itself, or its name in its family of generic instantiations,
	 * whose code may read an object of one instantiation through the field of another.
	 */
	private fun fieldKey(inField: KField): Any = inField.owner?.family?.let { it to inField.cName } ?: inField

	/** The fields of objects whose content [inValue] is: read directly, under casts and choices. */
	private fun fieldReads(inValue: KExpr?, outFields: MutableList<KField>) {
		when (inValue) {
			is KRefCast -> fieldReads(inValue.operand, outFields)
			is KRetain -> fieldReads(inValue.operand, outFields)
			is KSelect -> {
				fieldReads(inValue.whenTrue, outFields)
				fieldReads(inValue.whenFalse, outFields)
			}
			is KStructNew -> inValue.values.forEach { fieldReads(it, outFields) }
			is KFieldRead -> if (inValue.target.type is KStructType) fieldReads(inValue.target, outFields) else if (inValue.type.isCounted()) outFields += inValue.field
			else -> {}
		}
	}

	/** One line per allocation left on the heap: where, what, and why. Filled by [run]. */
	val heapReport = ArrayList<String>()

	/** Uses of inline storage that would need a copy the program has to ask for itself. Filled by [run]. */
	val errors = ArrayList<String>()

	private fun inlineRead(inValue: KExpr?): KFieldRead? = when (inValue) {
		is KFieldRead -> inValue.takeIf { it.field.isInline }
		is KRefCast -> inlineRead(inValue.operand)
		else -> null
	}

	/**
	 * A reference into inline storage has to be kept beyond its block: the value is copied to
	 * the heap. That is invisible for an immutable object; for a mutable one or an array the
	 * copy would be a different object than on the JVM, so it is refused.
	 */
	private fun copyOut(inRead: KFieldRead, inFunction: KFunction, inReason: String) {
		val vClass = (inRead.field.type as? KClassType)?.cls
		val vIsImmutable = vClass != null && generateSequence(vClass) { it.superClass }.all { vLevel -> vLevel.fields.all { it.isImmutable } }
		if (vIsImmutable) {
			inRead.needsCopy = true
		} else {
			val vWhat = if (vClass != null) "an object with 'var' properties" else "an array"
			errors += "${inFunction.cName}: inline field '${inRead.field.cName}' holds $vWhat and is $inReason; copy it explicitly (copy() / copyOf())"
		}
	}

	fun run() {
		val vFunctions = mModule.units.flatMap { it.functions + listOfNotNull(it.initializer) }
		val vBodies = vFunctions.filter { !it.isExternal && it.dispatch == null }
		do {
			var vChanged = false
			for (vFunction in vFunctions) {
				if (vFunction.dispatch != null && summarizeDispatcher(vFunction)) vChanged = true
			}
			for (vFunction in vBodies) {
				if (FunctionScan(vFunction).markEscapes()) vChanged = true
			}
		} while (vChanged)
		vBodies.forEach { FunctionScan(it).promote() }
		// The in-place forms are functions like the others, made while the others were promoted.
		var vIndex = 0
		while (vIndex < mNewFunctions.size) FunctionScan(mNewFunctions[vIndex++]).promote(inReports = false)
	}

	/** The in-place form of [inFunction], made on first request; null when the function does not qualify. */
	private fun inPlaceForm(inFunction: KFunction): Pair<KFunction, KClass>? {
		if (inFunction in mInPlace) return mInPlace[inFunction]
		// Not known yet: a call to itself inside the function is left as it is.
		mInPlace[inFunction] = null
		// The result may be declared as an interface (`iterator()`): what counts is that the one object returned is built here.
		if (inFunction.isExternal || inFunction.dispatch != null || (inFunction.returnType !is KClassType && inFunction.returnType != KObjType)) return null
		val vForm = FunctionScan(inFunction).buildInPlaceForm() ?: return null
		mInPlace[inFunction] = vForm
		mNewFunctions += vForm.first
		return vForm
	}

	/** A parameter of a dispatcher escapes when it does in any implementation (or in the runtime default). */
	private fun summarizeDispatcher(inFunction: KFunction): Boolean {
		var vChanged = false
		inFunction.params.forEachIndexed { vIndex, vParam ->
			// An implementation that stores it into the object of another parameter keeps it, as far as a dispatcher can tell.
			val vEscapes = inFunction.dispatchDefault != null ||
				inFunction.dispatch!!.any { (_, vTarget) -> vTarget.params.getOrNull(vIndex)?.let { it in mEscaping || it in mParamHeld || it in mResultHeld } != false }
			if (vEscapes && vParam !in mEscaping) {
				mEscaping[vParam] = "kept by an implementation"
				vChanged = true
			}
		}
		return vChanged
	}

	private fun parameterEscapes(inFunction: KFunction, inIndex: Int): Boolean =
		inFunction.isExternal || inFunction.params.getOrNull(inIndex)?.let { it in mEscaping } != false

	/** The locals whose reference [inValue] may be. */
	private fun sources(inValue: KExpr?, outLocals: MutableSet<KLocal>) {
		when (inValue) {
			is KRead -> outLocals += inValue.local
			is KRefCast -> sources(inValue.operand, outLocals)
			is KSelect -> {
				sources(inValue.whenTrue, outLocals)
				sources(inValue.whenFalse, outLocals)
			}
			is KRetain -> sources(inValue.operand, outLocals)
			// A struct carries the references put into it, and gives them back when a field is read.
			is KStructNew -> inValue.values.forEach { sources(it, outLocals) }
			is KFieldRead -> if (inValue.target.type is KStructType) sources(inValue.target, outLocals)
			else -> {}
		}
	}

	private fun sourcesOf(inValue: KExpr?): Set<KLocal> = HashSet<KLocal>().also { sources(inValue, it) }

	/** A heap allocation that could move to the stack. */
	private class Candidate(
		val local: KLocal,
		val what: String,
		val origin: String?,
		val unfit: String?,
		val inline: KFieldRead? = null,
		/** `@Size` or `@Value` on the local: the program requires the stack, so staying on the heap is an error. */
		val forcedBy: String? = null,
		/** A call whose result could be built in this frame, rather than an allocation written here. */
		val isCall: Boolean = false,
		val promote: () -> Unit,
	)

	private inner class FunctionScan(private val mFunction: KFunction) {
		/** For each local, the locals its reference is copied to. */
		private val mFlows = HashMap<KLocal, MutableSet<KLocal>>()

		/** The block (identified by its statement list) each local is declared in, and each block's parent. */
		private val mBlockOf = HashMap<KLocal, List<KStmt>>()
		private val mParent = HashMap<List<KStmt>, List<KStmt>?>()
		private val mCandidates = ArrayList<Candidate>()
		private var mChanged = false

		/** Set while promoting: only then are calls looked at as objects that could be built in place. */
		private var mIsPromoting = false

		/** Locals that escape some other way than by being returned. */
		private val mKept = HashSet<KLocal>()

		/** Every value each local is given. */
		private val mDefinitions = HashMap<KLocal, ArrayList<KExpr?>>()

		/** For each local, the locals whose object (made here) holds its reference in a field. */
		private val mHeldBy = HashMap<KLocal, MutableSet<KLocal>>()

		/** For each local, where it is stored into the object of a parameter of this function: see [mParamHeld]. */
		private val mParamHolds = HashMap<KLocal, MutableSet<Pair<Int, Any>>>()

		/** For each local, the fields through which the new object this function returns holds it: see [mResultHeld]. */
		private val mResultHolds = HashMap<KLocal, MutableSet<Any>>()

		/** What was stored into a field of an object made here, and what is read from fields into locals, by [fieldKey]. */
		private val mStored = ArrayList<Pair<Any, KLocal>>()
		private val mRead = ArrayList<Pair<Any, KLocal>>()

		private fun definitions(inBlock: List<KStmt>) {
			for (vStatement in inBlock) {
				when (vStatement) {
					is KLet -> mDefinitions.getOrPut(vStatement.local) { ArrayList() } += vStatement.init
					is KSet -> mDefinitions.getOrPut(vStatement.local) { ArrayList() } += vStatement.value
					is KConcat -> mDefinitions.getOrPut(vStatement.target) { ArrayList() } += null
					is KIf -> {
						definitions(vStatement.thenBlock)
						definitions(vStatement.elseBlock)
					}
					is KLoop -> {
						definitions(vStatement.body)
						definitions(vStatement.latch)
					}
					else -> {}
				}
			}
		}

		/**
		 * The one object [inLocal] only ever holds, when it is known where that comes from: the
		 * local it was allocated into here, or a parameter of this function (itself, or what the
		 * local is a copy of). Null when it may hold anything else.
		 */
		private fun rootOf(inLocal: KLocal): KLocal? {
			var vLocal = inLocal
			repeat(16) {
				if (vLocal in mFunction.params) return vLocal.takeIf { it !in mDefinitions }
				val vValue = mDefinitions[vLocal]?.singleOrNull() ?: return null
				when (val vCore = coreOf(vValue)) {
					is KAlloc -> return vLocal
					is KRead -> vLocal = vCore.local
					else -> return null
				}
			}
			return null
		}

		/**
		 * [inValue] is stored into field [inField] (by [fieldKey]) of the object [inHolder] refers
		 * to. Into an object made here, or the object of a parameter, it is held by that object;
		 * anywhere else, or in a leaky field, it escapes for [inReason].
		 */
		private fun hold(inValue: KExpr?, inHolder: KExpr?, inField: Any, inReason: String) {
			val vRoot = if (inField in mLeaky) null else (coreOf(inHolder) as? KRead)?.local?.let { rootOf(it) }
			if (vRoot == null) {
				escape(inValue, inReason)
				return
			}
			// Held by that object: it goes where the object goes, and no further.
			leak(inValue)
			val vParam = mFunction.params.indexOf(vRoot)
			for (vSource in sourcesOf(inValue)) {
				if (vParam >= 0) {
					mParamHolds.getOrPut(vSource) { HashSet() } += vParam to inField
				} else {
					mHeldBy.getOrPut(vSource) { HashSet() } += vRoot
					mStored += inField to vSource
				}
			}
		}

		/** The fields [inValue] is read from are leaky: the value goes where it cannot be followed. */
		private fun leak(inValue: KExpr?) {
			val vFields = ArrayList<KField>()
			fieldReads(inValue, vFields)
			for (vField in vFields) if (mLeaky.add(fieldKey(vField))) mChanged = true
		}

		/** Scans the whole function, then lets what was stored into a field flow to the locals that read that field here. */
		private fun scanAll() {
			definitions(mFunction.body)
			scan(mFunction.body, null)
			for ((vField, vSource) in mStored) {
				for ((vOther, vReader) in mRead) if (vOther == vField) mFlows.getOrPut(vSource) { HashSet() } += vReader
			}
		}

		private fun escape(inValue: KExpr?, inReason: String) {
			leak(inValue)
			for (vLocal in sourcesOf(inValue)) {
				if (inReason != "returned") mKept += vLocal
				if (vLocal !in mEscaping) {
					mEscaping[vLocal] = inReason
					mChanged = true
				}
			}
		}

		/** Looks at what the call [inValue] does with its arguments; [inTarget] is the local that receives its result, if one does. */
		private fun call(inValue: KExpr?, inTarget: KLocal? = null) {
			when (inValue) {
				is KRefCast -> call(inValue.operand, inTarget)
				is KCall -> inValue.arguments.forEachIndexed { vIndex, vArgument ->
					val vFunction = inValue.function
					if (parameterEscapes(vFunction, vIndex)) {
						escape(vArgument, "passed to ${vFunction.cName}, which keeps it")
					} else {
						// Stored by the function into the object of another argument: held by that one.
						mParamHeld[vFunction.params[vIndex]]?.toList()?.forEach { (vHolder, vField) ->
							hold(vArgument, inValue.arguments.getOrNull(vHolder), vField, "passed to ${vFunction.cName}, which keeps it")
						}
						// Stored by the function into the new object it returns: held by the local that gets it, when
						// that local holds nothing else. Returned or dropped, the result is out of sight.
						mResultHeld[vFunction.params[vIndex]]?.toList()?.forEach { vField ->
							if (vField in mLeaky || inTarget == null || mDefinitions[inTarget]?.size != 1) {
								escape(vArgument, "passed to ${vFunction.cName}, whose result keeps it")
							} else {
								leak(vArgument)
								for (vSource in sourcesOf(vArgument)) {
									mHeldBy.getOrPut(vSource) { HashSet() } += inTarget
									mStored += vField to vSource
								}
							}
						}
					}
				}
				is KIntrinsic -> if (!inValue.isPure) inValue.arguments.forEach { escape(it, "passed to ${inValue.name}") }
				else -> {}
			}
		}

		private fun flow(inTarget: KLocal, inValue: KExpr?) {
			for (vSource in sourcesOf(inValue)) mFlows.getOrPut(vSource) { HashSet() } += inTarget
			val vFields = ArrayList<KField>()
			fieldReads(inValue, vFields)
			vFields.forEach { mRead += fieldKey(it) to inTarget }
			call(inValue, inTarget)
		}

		private fun candidate(inLocal: KLocal, inValue: KExpr?) {
			when (inValue) {
				is KAlloc -> mCandidates += Candidate(inLocal, "a ${inValue.cls.name}", inValue.origin, null, forcedBy = inValue.forcedBy) { inValue.onStack = true }
				is KArrayNew -> {
					val vLength = (inValue.length as? KConst)?.value as? Int
					// The size limit is the compiler's caution; an array the program asked for is taken as it is.
					val vUnfit = when {
						// A size known only when the program runs: the frame keeps room for a small one, a larger one goes to the heap.
						vLength == null && inValue.forcedBy != null -> "its size is not a constant"
						vLength == null -> null
						vLength > kStackArrayLimit && inValue.forcedBy == null -> "it has more than $kStackArrayLimit elements"
						else -> null
					}
					mCandidates += Candidate(inLocal, "an array", inValue.origin, vUnfit, forcedBy = inValue.forcedBy) { inValue.onStack = true }
				}
				is KRefCast -> candidate(inLocal, inValue.operand)
				// The result of a function that can build it in place: built in this frame if it stays here.
				is KCall -> if (mIsPromoting) inPlaceForm(inValue.function)?.let { (vForm, vClass) ->
					mCandidates += Candidate(inLocal, "a ${vClass.name}", null, null, isCall = true) {
						inValue.arguments = listOf<KExpr>(KStackSlot(vClass)) + inValue.arguments
						inValue.function = vForm
					}
				}
				// A pointer into inline storage is a borrow that has to stay in its block, like a stack object.
				is KFieldRead -> if (inValue.field.isInline) mCandidates += Candidate(inLocal, "an inline value", null, null, inValue) {}
				else -> {}
			}
		}

		private fun scan(inBlock: List<KStmt>, inParentBlock: List<KStmt>?) {
			mParent[inBlock] = inParentBlock
			for (vStatement in inBlock) {
				when (vStatement) {
					is KLet -> {
						mBlockOf[vStatement.local] = inBlock
						flow(vStatement.local, vStatement.init)
						candidate(vStatement.local, vStatement.init)
					}
					is KSet -> flow(vStatement.local, vStatement.value)
					is KGlobalSet -> {
						escape(vStatement.value, "stored in a top-level property")
						call(vStatement.value)
					}
					is KFieldSet -> {
						// An inline field copies the content of the value: the reference goes nowhere.
						if (!vStatement.field.isInline) {
							val vReason = "stored in field '${vStatement.field.cName}'"
							if (vStatement.field.weakType != null || vStatement.target.type !is KClassType) {
								escape(vStatement.value, vReason)
							} else {
								hold(vStatement.value, vStatement.target, fieldKey(vStatement.field), vReason)
							}
						}
						call(vStatement.value)
					}
					is KIndexSet -> {
						escape(vStatement.value, "stored in an array")
						call(vStatement.value)
					}
					is KEval -> call(vStatement.expr)
					is KReturn -> {
						escape(vStatement.value, "returned")
						call(vStatement.value)
					}
					is KIf -> {
						scan(vStatement.thenBlock, inBlock)
						scan(vStatement.elseBlock, inBlock)
					}
					is KLoop -> {
						scan(vStatement.body, inBlock)
						scan(vStatement.latch, inBlock)
					}
					is KConcat -> {
						mBlockOf[vStatement.target] = inBlock
						mCandidates += Candidate(vStatement.target, "a string", vStatement.origin, null) { vStatement.onStack = true }
					}
					is KPrint, is KBreak, is KContinue, is KRelease -> {}
					is KThrow, is KTryRegion -> error("exceptions are lowered before this pass")
				}
			}
		}

		/**
		 * Marks the locals of this function that escape, following copies between locals.
		 *
		 * @return true when a parameter or local was newly marked
		 */
		fun markEscapes(): Boolean {
			scanAll()
			// The locals whose object goes somewhere else than back to the caller: kept, copied into one
			// that is, or held by one that is.
			val vKept = HashSet(mKept)
			do {
				var vGrew = false
				for (vEdges in listOf(mFlows, mHeldBy)) {
					for ((vSource, vTargets) in vEdges) if (vSource !in vKept && vTargets.any { it in vKept }) {
						vKept += vSource
						vGrew = true
					}
				}
			} while (vGrew)
			// A reference copied into a local that escapes escapes too; so does one held by an object that
			// escapes - unless that object only goes back to the caller: what it holds is then held by
			// the result, which the caller follows.
			do {
				var vGrew = false
				for ((vSource, vTargets) in mFlows) {
					val vVia = vTargets.firstOrNull { it in mEscaping }
					if (vSource !in mEscaping && vVia != null) {
						mEscaping[vSource] = mEscaping.getValue(vVia)
						vGrew = true
						mChanged = true
					}
					for (vTarget in vTargets) {
						val vHolds = mResultHolds[vTarget] ?: continue
						if (mResultHolds.getOrPut(vSource) { HashSet() }.addAll(vHolds)) vGrew = true
					}
				}
				for ((vSource, vTargets) in mHeldBy) {
					val vVia = vTargets.firstOrNull { it in mEscaping && it in vKept }
					if (vVia != null) {
						if (vSource !in mEscaping) {
							mEscaping[vSource] = mEscaping.getValue(vVia)
							vGrew = true
							mChanged = true
						}
						continue
					}
					for (vTarget in vTargets) {
						// Returned and nothing else, or itself held by what is returned.
						if (vTarget !in mEscaping && vTarget !in mResultHolds) continue
						val vFields = mStored.filter { it.second === vSource }.mapTo(HashSet()) { it.first }
						mResultHolds[vTarget]?.let { vFields.addAll(it) }
						if (mResultHolds.getOrPut(vSource) { HashSet() }.addAll(vFields)) vGrew = true
					}
				}
			} while (vGrew)
			for (vParam in mFunction.params) {
				val vHolds = mResultHolds[vParam] ?: continue
				if (vParam !in mEscaping && mResultHeld.getOrPut(vParam) { HashSet() }.addAll(vHolds)) mChanged = true
			}
			// A field read into a local that escapes is leaky.
			for ((vField, vReader) in mRead) if (vReader in mEscaping && mLeaky.add(vField)) mChanged = true
			// What is stored into the object of a parameter: so is whatever is copied into it, or held by it.
			do {
				var vGrew = false
				for (vEdges in listOf(mFlows, mHeldBy)) {
					for ((vSource, vTargets) in vEdges) {
						for (vTarget in vTargets) {
							val vHolds = mParamHolds[vTarget] ?: continue
							if (mParamHolds.getOrPut(vSource) { HashSet() }.addAll(vHolds)) vGrew = true
						}
					}
				}
			} while (vGrew)
			for (vParam in mFunction.params) {
				val vHolds = mParamHolds[vParam] ?: continue
				if (vParam !in mEscaping && mParamHeld.getOrPut(vParam) { HashSet() }.addAll(vHolds)) mChanged = true
			}
			return mChanged
		}

		/** Every local the object first held by [inLocal] may be referred to through: its copies, the objects that hold it, and theirs. */
		private fun reachOf(inLocal: KLocal): Set<KLocal> {
			val vSeen = LinkedHashSet<KLocal>()
			val vPending = ArrayDeque(listOf(inLocal))
			while (vPending.isNotEmpty()) {
				val vNext = vPending.removeFirst()
				if (!vSeen.add(vNext)) continue
				mFlows[vNext]?.let { vPending += it }
				mHeldBy[vNext]?.let { vPending += it }
			}
			return vSeen
		}

		private fun isInside(inBlock: List<KStmt>?, inAncestor: List<KStmt>): Boolean {
			var vBlock = inBlock
			while (vBlock != null) {
				if (vBlock === inAncestor) return true
				vBlock = mParent[vBlock]
			}
			return false
		}

		/** Every local the reference first held by [inLocal] can reach through copies. */
		private fun aliasesOf(inLocal: KLocal): Set<KLocal> {
			val vSeen = LinkedHashSet<KLocal>()
			val vPending = ArrayDeque(listOf(inLocal))
			while (vPending.isNotEmpty()) {
				val vNext = vPending.removeFirst()
				if (vSeen.add(vNext)) mFlows[vNext]?.let { vPending += it }
			}
			return vSeen
		}

		/** Inline reads used directly as a value that is kept (returned by an accessor, for example). */
		private fun directInlineUses(inBlock: List<KStmt>) {
			for (vStatement in inBlock) {
				when (vStatement) {
					is KReturn -> inlineRead(vStatement.value)?.let { copyOut(it, mFunction, "returned") }
					is KGlobalSet -> inlineRead(vStatement.value)?.let { copyOut(it, mFunction, "stored in a top-level property") }
					is KFieldSet -> if (!vStatement.field.isInline) inlineRead(vStatement.value)?.let { copyOut(it, mFunction, "stored in another field") }
					is KIndexSet -> inlineRead(vStatement.value)?.let { copyOut(it, mFunction, "stored in an array") }
					is KIf -> {
						directInlineUses(vStatement.thenBlock)
						directInlineUses(vStatement.elseBlock)
					}
					is KLoop -> {
						directInlineUses(vStatement.body)
						directInlineUses(vStatement.latch)
					}
					else -> {}
				}
			}
		}

		// ============
		//  Results built in place

		private fun returnsIn(inBlock: List<KStmt>, outReturns: MutableList<KReturn>) {
			for (vStatement in inBlock) {
				when (vStatement) {
					is KReturn -> outReturns += vStatement
					is KIf -> {
						returnsIn(vStatement.thenBlock, outReturns)
						returnsIn(vStatement.elseBlock, outReturns)
					}
					is KLoop -> {
						returnsIn(vStatement.body, outReturns)
						returnsIn(vStatement.latch, outReturns)
					}
					else -> {}
				}
			}
		}

		/** What [inInit] is under its casts. */
		private fun coreOf(inInit: KExpr?): KExpr? {
			var vCore = inInit
			while (vCore is KRefCast) vCore = vCore.operand
			return vCore
		}

		/** The class of the new object [inInit] yields - an allocation, or a call that can build in place - or null. */
		private fun builtClass(inInit: KExpr?): KClass? = when (val vCore = coreOf(inInit)) {
			is KAlloc -> vCore.cls.takeIf { vCore.forcedBy == null }
			is KCall -> inPlaceForm(vCore.function)?.second
			else -> null
		}

		/** The statements of [inBlock] that create a new object, loops excluded (one in a loop could be reached twice). */
		private fun allocationsIn(inBlock: List<KStmt>, outLets: MutableList<KLet>) {
			for (vStatement in inBlock) {
				if (vStatement is KLet) {
					if (builtClass(vStatement.init) != null) outLets += vStatement
				} else if (vStatement is KIf) {
					allocationsIn(vStatement.thenBlock, outLets)
					allocationsIn(vStatement.elseBlock, outLets)
				}
			}
		}

		/** How many times each local is given a value in [inBlock]. */
		private fun countAssignments(inBlock: List<KStmt>, outCounts: MutableMap<KLocal, Int>) {
			for (vStatement in inBlock) {
				when (vStatement) {
					is KLet -> if (vStatement.init != null) outCounts.merge(vStatement.local, 1, Int::plus)
					is KSet -> outCounts.merge(vStatement.local, 1, Int::plus)
					is KConcat -> outCounts.merge(vStatement.target, 1, Int::plus)
					is KIf -> {
						countAssignments(vStatement.thenBlock, outCounts)
						countAssignments(vStatement.elseBlock, outCounts)
					}
					is KLoop -> {
						// Whatever is assigned in a loop may be assigned again.
						val vInside = HashMap<KLocal, Int>()
						countAssignments(vStatement.body, vInside)
						countAssignments(vStatement.latch, vInside)
						vInside.keys.forEach { outCounts.merge(it, 2, Int::plus) }
					}
					else -> {}
				}
			}
		}

		/** [inBlock] with the statement [inOld] replaced by [inNew]; blocks that do not contain it are shared. */
		private fun replaced(inBlock: List<KStmt>, inOld: KStmt, inNew: KStmt): List<KStmt> = inBlock.map { vStatement ->
			when {
				vStatement === inOld -> inNew
				vStatement is KIf && (vStatement.thenBlock.anyStatement { it === inOld } || vStatement.elseBlock.anyStatement { it === inOld }) ->
					KIf(vStatement.condition, replaced(vStatement.thenBlock, inOld, inNew), replaced(vStatement.elseBlock, inOld, inNew))
				else -> vStatement
			}
		}

		/**
		 * The form of this function that builds its result in room given by the caller, when
		 * every result it returns is the one object it allocates and that object goes nowhere
		 * else. Left without a result (an exception) it returns null as before.
		 */
		fun buildInPlaceForm(): Pair<KFunction, KClass>? {
			scanAll()
			val vReturns = ArrayList<KReturn>()
			returnsIn(mFunction.body, vReturns)
			val vLets = ArrayList<KLet>()
			allocationsIn(mFunction.body, vLets)
			// The one new object that reaches a `return`, directly or through locals it is copied to.
			val vLet = vLets.singleOrNull { vCandidate -> vReturns.any { vReturn -> sourcesOf(vReturn.value).any { it in aliasesOf(vCandidate.local) } } } ?: return null
			val vAliases = aliasesOf(vLet.local)
			for (vReturn in vReturns) {
				val vValue = vReturn.value
				if (vValue == null || vValue is KZero || vValue is KNull) continue
				val vSources = sourcesOf(vValue)
				if (vSources.isEmpty() || !vAliases.containsAll(vSources)) return null
			}
			// Each of those locals holds that object and nothing else, and none lets it go elsewhere.
			val vAssignments = HashMap<KLocal, Int>()
			countAssignments(mFunction.body, vAssignments)
			if (vAliases.any { vAssignments[it] != 1 || it in mKept || it in mFunction.params }) return null
			val vClass = builtClass(vLet.init) ?: return null

			val vSlot = KLocal("ktc2_slot", KClassType(vClass))
			val vForm = KFunction(mFunction.cName + "__into", mFunction.unit, listOf(vSlot) + mFunction.params, mFunction.returnType)
			val vCore = coreOf(vLet.init)
			val vBuilt: KExpr = if (vCore is KCall) {
				// The object comes from a function that builds in place itself: the room is passed on.
				val vInner = inPlaceForm(vCore.function)!!.first
				KCall(vInner, listOf<KExpr>(KRead(vSlot)) + vCore.arguments)
			} else {
				KIntrinsic("ktc2_slot_take", listOf(KRead(vSlot)), KClassType(vClass), isPure = false)
			}
			fun rewrap(inInit: KExpr): KExpr = if (inInit is KRefCast) KRefCast(rewrap(inInit.operand), inInit.type) else vBuilt
			vForm.body += replaced(mFunction.body, vLet, KLet(vLet.local, rewrap(vLet.init!!)))
			mFunction.unit.functions += vForm
			return vForm to vClass
		}

		/** Moves to the stack the allocations whose object provably dies with its block; reports the others. */
		fun promote(inReports: Boolean = true) {
			mIsPromoting = true
			scanAll()
			directInlineUses(mFunction.body)
			for (vCandidate in mCandidates) {
				val vBlock = mBlockOf[vCandidate.local] ?: continue
				val vAliases = reachOf(vCandidate.local)
				val vReason = vCandidate.unfit
					?: vAliases.firstNotNullOfOrNull { mEscaping[it] }
					// Held by an object the caller passed: that one outlives this frame.
					?: (if (vAliases.any { it in mParamHolds }) "stored in an object that was passed in" else null)
					?: if (vAliases.any { !isInside(mBlockOf[it], vBlock) }) "kept in a variable of an enclosing block" else null
				if (vCandidate.inline != null) {
					if (vReason != null) copyOut(vCandidate.inline, mFunction, vReason)
				} else if (vReason == null) {
					vCandidate.promote()
				} else if (vCandidate.isCall || !inReports) {
					// Nothing to say: the call keeps returning a heap object, as it did.
				} else if (vCandidate.forcedBy != null) {
					errors += "${vCandidate.origin ?: mFunction.cName}: ${vCandidate.what} marked ${vCandidate.forcedBy} cannot be kept on the stack: $vReason"
				} else {
					heapReport += "${vCandidate.origin ?: mFunction.cName}: ${vCandidate.what} is allocated on the heap: $vReason"
				}
			}
		}
	}
}
