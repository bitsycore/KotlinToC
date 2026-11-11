package com.bitsycore.ktc2.backend

import com.bitsycore.ktc2.kir.*

/**
 * Finds the functions that only hand out a reference something else keeps: `list[i]`, a
 * getter, `iterator.next()` over such a list. By the general rule their result carries a
 * count, which the caller gives back a moment later. Each of them gets a second form (the
 * same name with `__b`) whose result is borrowed, and `ArcInsert` calls that form where the
 * caller can tell that the place the result came from is not overwritten while it is used.
 *
 * A function qualifies when every value it returns is
 * - read from a place — a field, an array element, a top-level property — reached from its
 *   parameters, or
 * - the result of a qualifying function called with arguments read that way,
 * and the function itself does not write any of those places. A virtual dispatcher qualifies
 * when every implementation behind it does.
 */
class BorrowedReturns(inModule: KModule, private val mEffects: Effects) {

	/** The places a borrowed result may have been read from: it lives as long as none of them is overwritten. */
	class Place {
		val fields = HashSet<String>()
		var elements = false
		val globals = HashSet<KGlobal>()

		fun add(inOther: Place) {
			fields += inOther.fields
			elements = elements || inOther.elements
			globals += inOther.globals
		}

		fun isWrittenBy(inWritten: Effects.Summary): Boolean =
			inWritten.all || (elements && inWritten.elements) || fields.any { it in inWritten.fields } || globals.any { it in inWritten.globals }

		fun sameAs(inOther: Place): Boolean = fields == inOther.fields && elements == inOther.elements && globals == inOther.globals
	}

	/**
	 * @property place where the result comes from
	 * @property callLocals the locals of the function that hold the result of a qualifying call, with the function called
	 */
	class Info(val place: Place, val callLocals: Map<KLocal, KFunction>)

	private val mInfos = HashMap<KFunction, Info>()
	private val mVariants = HashMap<KFunction, KFunction>()

	/** The borrowed forms created and not yet given a body, with the function each is a form of. */
	val pending = ArrayList<Pair<KFunction, KFunction>>()

	init {
		val vCandidates = inModule.units.flatMap { it.functions }
			.filter { it.returnType is KRefType && !it.isExternal && (it.dispatch != null || it.body.isNotEmpty()) }
		// Every candidate is first taken to qualify, with nothing known about its places; then each is
		// looked at again and again, losing its standing or gaining places, until nothing changes.
		for (vFunction in vCandidates) mInfos[vFunction] = Info(Place(), emptyMap())
		var vChanged = true
		while (vChanged) {
			vChanged = false
			for (vFunction in vCandidates) {
				val vKnown = mInfos[vFunction] ?: continue
				val vNow = analyze(vFunction)
				if (vNow == null) {
					mInfos.remove(vFunction)
					vChanged = true
				} else if (!vNow.place.sameAs(vKnown.place) || vNow.callLocals != vKnown.callLocals) {
					mInfos[vFunction] = vNow
					vChanged = true
				}
			}
		}
	}

	/** What is known of [inFunction] when its result can be borrowed; null otherwise. */
	fun of(inFunction: KFunction): Info? = mInfos[inFunction]

	/** The form of [inFunction] whose result is borrowed. */
	fun variant(inFunction: KFunction): KFunction = mVariants.getOrPut(inFunction) {
		KFunction("${inFunction.cName}__b", inFunction.unit, inFunction.params, inFunction.returnType).also {
			inFunction.unit.functions += it
			pending += it to inFunction
		}
	}

	// ==================
	// MARK: Analysis
	// ==================

	private fun analyze(inFunction: KFunction): Info? {
		val vDispatch = inFunction.dispatch
		if (vDispatch != null) {
			val vPlace = Place()
			for (vTarget in vDispatch.map { it.second } + listOfNotNull(inFunction.dispatchDefault)) {
				// An implementation that returns nothing only throws. One that returns a number is boxed by the dispatcher: a new object.
				if (vTarget.returnType == KUnitType || vTarget.returnType == KNothingType) continue
				val vKnown = mInfos[vTarget]
				if (vTarget.returnType !is KRefType || vKnown == null) {
					if (System.getenv("KTC2_DEBUG_BORROW") != null) System.err.println("ktc2: borrow: ${inFunction.cName} dispatches to ${vTarget.cName}, which returns ${vTarget.returnType}")
					return null
				}
				vPlace.add(vKnown.place)
			}
			return Info(vPlace, emptyMap())
		}

		val vReassigned = HashSet<KLocal>()
		val vDefinitions = HashMap<KLocal, KExpr>()
		val vReturned = ArrayList<KExpr?>()
		inFunction.body.anyStatement { vStatement ->
			when (vStatement) {
				is KSet -> vReassigned += vStatement.local
				is KConcat -> vReassigned += vStatement.target
				is KLet -> vStatement.init?.let { vDefinitions[vStatement.local] = it }
				is KReturn -> vReturned += vStatement.value
				else -> {}
			}
			false
		}
		val vPlace = Place()
		val vCallLocals = HashMap<KLocal, KFunction>()

		fun source(inValue: KExpr): Boolean = when (inValue) {
			is KNull, is KZero, is KStringLit, is KStaticRead -> true
			is KRefCast -> source(inValue.operand)
			is KFieldRead -> {
				if (inValue.field.isInline || inValue.field.weakType != null || inValue.needsCopy) {
					false
				} else {
					if (!inValue.field.isImmutable) vPlace.fields += inValue.field.cName
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
							if (vCallee == null || !vCall.arguments.all { !it.type.isCounted() || source(it) }) {
								false
							} else {
								vPlace.add(vCallee.place)
								vCallLocals[vLocal] = vCall.function
								true
							}
						}
					}
				}
			}
			else -> false
		}

		if (vReturned.isEmpty() || !vReturned.all { it != null && source(it) }) {
			if (System.getenv("KTC2_DEBUG_BORROW") != null) {
				val vWhy = vReturned.firstOrNull { it == null || !source(it) }
				val vLocal = (vWhy as? KRead)?.local
				System.err.println("ktc2: borrow: ${inFunction.cName} returns ${vWhy?.javaClass?.simpleName} ${vLocal?.cName ?: ""} reassigned=${vLocal in vReassigned} definition=${vDefinitions[vLocal]?.javaClass?.simpleName} callee=${vDefinitions[vLocal]?.let { callOf(it)?.function?.cName }}")
			}
			return null
		}
		// The function must leave alone what its result hangs from, up to the moment it returns.
		val vWritten = mEffects.of(inFunction)
		if (vPlace.isWrittenBy(vWritten)) {
			if (System.getenv("KTC2_DEBUG_BORROW") != null) {
				System.err.println("ktc2: borrow: ${inFunction.cName} writes its own place: all=${vWritten.all} elements=${vWritten.elements && vPlace.elements} fields=${vPlace.fields.intersect(vWritten.fields)}")
			}
			return null
		}
		return Info(vPlace, vCallLocals)
	}

	companion object {
		/** [inValue] as a call, looking through casts; null when it is something else. */
		fun callOf(inValue: KExpr): KCall? = when (inValue) {
			is KCall -> inValue
			is KRefCast -> callOf(inValue.operand)
			else -> null
		}
	}
}
