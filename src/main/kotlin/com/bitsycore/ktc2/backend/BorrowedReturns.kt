package com.bitsycore.ktc2.backend

import com.bitsycore.ktc2.kir.*

/**
 * Finds the functions that only hand out a reference something else keeps: `list[i]`, a
 * getter, `iterator.next()` over such a list. By the general rule their result carries a
 * count, which the caller gives back a moment later. Each of them gets a second form whose
 * result is borrowed, and `ArcInsert` calls that form where the caller can tell that the
 * place the result came from is not overwritten while it is used.
 *
 * A function qualifies when every value it returns is
 * - read from a place - a field, an array element, a top-level property - reached from its
 *   parameters, or
 * - the result of a qualifying function called with arguments read that way,
 * and the function itself does not write any of those places.
 *
 * A virtual dispatcher qualifies when at least one implementation behind it does. When all
 * do, its borrowed form is `name__b`. When some return an object of their own (a list that
 * computes its elements, a boxed number), the form is `name__m`, which takes one more
 * argument: the address of a variable of the caller. The result is borrowed where the
 * implementation allows it; otherwise the owned result is also stored in that variable, which
 * the caller releases when it is done. A function whose result comes from such a call passes
 * the address on, and is `__m` itself.
 */
class BorrowedReturns(inModule: KModule, private val mEffects: Effects) {

	/** The places a borrowed result may have been read from: it lives as long as none of them is overwritten. */
	class Place {
		val fields = HashSet<KField>()
		var elements = false
		val globals = HashSet<KGlobal>()

		fun add(inOther: Place) {
			fields += inOther.fields
			elements = elements || inOther.elements
			globals += inOther.globals
		}

		fun isWrittenBy(inWritten: Effects.Summary): Boolean =
			inWritten.all || (elements && inWritten.elements) || fields.any { inWritten.writesField(it) } || globals.any { it in inWritten.globals }

		fun sameAs(inOther: Place): Boolean = fields == inOther.fields && elements == inOther.elements && globals == inOther.globals
	}

	/**
	 * @property place where a borrowed result comes from
	 * @property isMixed the result may also be an object of its own: the borrowed form is the `__m` one
	 * @property callLocals the locals of the function that hold the result of a qualifying call, with the function called
	 * @property borrowedTargets for a dispatcher, the implementations whose result is borrowed
	 */
	class Info(
		val place: Place,
		val isMixed: Boolean,
		val callLocals: Map<KLocal, KFunction>,
		val borrowedTargets: List<Pair<KClass, KFunction>>,
	) {
		fun sameAs(inOther: Info): Boolean =
			place.sameAs(inOther.place) && isMixed == inOther.isMixed && callLocals == inOther.callLocals && borrowedTargets == inOther.borrowedTargets
	}

	/** A borrowed form that still has to be given a body: [form] of [function]; [keep] is the extra parameter of a `__m` form. */
	class Pending(val form: KFunction, val function: KFunction, val keep: KLocal?)

	private val mInfos = HashMap<KFunction, Info>()
	private val mForms = HashMap<KFunction, KFunction>()

	val pending = ArrayList<Pending>()

	init {
		val vCandidates = inModule.units.flatMap { it.functions }
			// A dispatcher that builds its result in the caller's frame hands out an object of the caller's own.
			.filter { it.returnType is KRefType && !it.isExternal && it.slotClasses == null && (it.dispatch != null || it.body.isNotEmpty()) }
		// Every candidate is first taken to qualify, with nothing known about its places; then each is
		// looked at again and again, losing standing or gaining places, until nothing changes.
		for (vFunction in vCandidates) mInfos[vFunction] = Info(Place(), false, emptyMap(), vFunction.dispatch?.toList() ?: emptyList())
		var vChanged = true
		while (vChanged) {
			vChanged = false
			for (vFunction in vCandidates) {
				val vKnown = mInfos[vFunction] ?: continue
				val vNow = analyze(vFunction)
				if (vNow == null) {
					mInfos.remove(vFunction)
					vChanged = true
				} else if (!vNow.sameAs(vKnown)) {
					mInfos[vFunction] = vNow
					vChanged = true
				}
			}
		}
	}

	/** What is known of [inFunction] when its result can be borrowed; null otherwise. */
	fun of(inFunction: KFunction): Info? = mInfos[inFunction]

	/** The form of [inFunction] whose result is borrowed: `__b`, or `__m` with one more parameter when the result is mixed. */
	fun form(inFunction: KFunction): KFunction = mForms.getOrPut(inFunction) {
		val vKeep = if (mInfos.getValue(inFunction).isMixed) KLocal("ktc2_keep", KPtrType) else null
		val vName = inFunction.cName + if (vKeep != null) "__m" else "__b"
		KFunction(vName, inFunction.unit, inFunction.params + listOfNotNull(vKeep), inFunction.returnType).also {
			inFunction.unit.functions += it
			pending += Pending(it, inFunction, vKeep)
		}
	}

	// ==================
	// MARK: Analysis
	// ==================

	private fun debug(inMessage: () -> String) {
		if (System.getenv("KTC2_DEBUG_BORROW") != null) System.err.println("ktc2: borrow: ${inMessage()}")
	}

	/** True when the dispatcher [inFunction] has to box an argument for [inTarget]: the box does not outlive the call. */
	private fun boxesArguments(inFunction: KFunction, inTarget: KFunction): Boolean =
		inFunction.params.zip(inTarget.params).any { (vFrom, vTo) -> vFrom.type !is KRefType && vTo.type is KRefType }

	private fun analyzeDispatcher(inFunction: KFunction, inDispatch: List<Pair<KClass, KFunction>>): Info? {
		val vPlace = Place()
		val vBorrowed = ArrayList<Pair<KClass, KFunction>>()
		var vIsMixed = inFunction.dispatchDefault != null
		for ((vClass, vTarget) in inDispatch) {
			// An implementation that returns nothing only throws.
			val vOnlyThrows = vTarget.returnType == KUnitType || vTarget.returnType == KNothingType
			val vKnown = mInfos[vTarget]
			if (!vOnlyThrows && (vTarget.returnType !is KRefType || vKnown == null || boxesArguments(inFunction, vTarget))) {
				// Its result is an object of its own, or a number the dispatcher boxes.
				vIsMixed = true
				continue
			}
			vBorrowed += vClass to vTarget
			if (vKnown != null && !vOnlyThrows) {
				vPlace.add(vKnown.place)
				if (vKnown.isMixed) vIsMixed = true
			}
		}
		if (vBorrowed.none { mInfos[it.second] != null }) {
			debug { "${inFunction.cName}: no implementation hands out what something else keeps" }
			return null
		}
		// In a mixed dispatcher the implementations that only throw are left to the ordinary one.
		val vTargets = if (vIsMixed) vBorrowed.filter { mInfos[it.second] != null && it.second.returnType is KRefType } else vBorrowed
		return Info(vPlace, vIsMixed, emptyMap(), vTargets)
	}

	private fun analyze(inFunction: KFunction): Info? {
		inFunction.dispatch?.let { return analyzeDispatcher(inFunction, it) }

		val vReassigned = HashSet<KLocal>()
		val vDefinitions = HashMap<KLocal, KExpr>()
		val vInLoop = HashSet<KLocal>()
		val vReturned = ArrayList<KExpr?>()
		fun scan(inBlock: List<KStmt>, inIsLoop: Boolean) {
			for (vStatement in inBlock) {
				when (vStatement) {
					is KSet -> vReassigned += vStatement.local
					is KConcat -> vReassigned += vStatement.target
					is KLet -> {
						vStatement.init?.let { vDefinitions[vStatement.local] = it }
						if (inIsLoop) vInLoop += vStatement.local
					}
					is KReturn -> vReturned += vStatement.value
					is KIf -> {
						scan(vStatement.thenBlock, inIsLoop)
						scan(vStatement.elseBlock, inIsLoop)
					}
					is KLoop -> {
						scan(vStatement.body, true)
						scan(vStatement.latch, true)
					}
					else -> {}
				}
			}
		}
		scan(inFunction.body, false)
		val vPlace = Place()
		val vCallLocals = HashMap<KLocal, KFunction>()
		var vMixedCalls = 0

		fun source(inValue: KExpr): Boolean = when (inValue) {
			is KNull, is KZero, is KStringLit, is KStaticRead -> true
			is KRefCast -> source(inValue.operand)
			is KFieldRead -> {
				if (!isPlainField(inValue)) {
					false
				} else {
					if (!inValue.field.isImmutable) vPlace.fields += inValue.field
					source(inValue.target)
				}
			}
			is KIndexRead -> {
				vPlace.elements = true
				source(inValue.array)
			}
			is KGlobalRead -> {
				vPlace.globals += inValue.global
				true
			}
			is KRead -> {
				val vLocal = inValue.local
				val vDefinition = vDefinitions[vLocal]
				when {
					vLocal in vReassigned -> false
					vLocal in inFunction.params -> true
					vDefinition == null -> false
					else -> {
						val vCall = callOf(vDefinition)
						if (vCall == null) {
							source(vDefinition)
						} else {
							val vCallee = mInfos[vCall.function]
							when {
								vCallee == null || !vCall.arguments.all { !it.type.isCounted() || source(it) } -> false
								// The one variable of the caller can take one owned result: not two, not one per round of a loop.
								vCallee.isMixed && vLocal !in vCallLocals && (vLocal in vInLoop || ++vMixedCalls > 1) -> false
								else -> {
									vPlace.add(vCallee.place)
									vCallLocals[vLocal] = vCall.function
									true
								}
							}
						}
					}
				}
			}
			else -> false
		}

		if (vReturned.isEmpty() || !vReturned.all { it != null && source(it) }) {
			debug { "${inFunction.cName}: returns an object of its own" }
			return null
		}
		// The function must leave alone what its result hangs from, up to the moment it returns.
		val vWritten = mEffects.of(inFunction)
		if (vPlace.isWrittenBy(vWritten)) {
			debug { "${inFunction.cName}: writes where its result comes from (all=${vWritten.all} elements=${vWritten.elements && vPlace.elements} fields=${vPlace.fields.filter { vWritten.writesField(it) }.map { it.cName }})" }
			return null
		}
		return Info(vPlace, vCallLocals.values.any { mInfos[it]?.isMixed == true }, vCallLocals, emptyList())
	}

	companion object {
		/** [inValue] as a call, looking through casts; null when it is something else. */
		fun callOf(inValue: KExpr): KCall? = when (inValue) {
			is KCall -> inValue
			is KRefCast -> callOf(inValue.operand)
			else -> null
		}

		/**
		 * True when [inRead] reads an ordinary field holding a reference. Not one stored inline,
		 * nor a weak one; and not a field of a class that is itself stored inline somewhere, since
		 * assigning to that storage overwrites every field of the object, `val` or not.
		 */
		fun isPlainField(inRead: KFieldRead): Boolean =
			!inRead.field.isInline && inRead.field.weakType == null && !inRead.needsCopy && inRead.field.owner?.isUsedInline != true
	}
}
