package com.bitsycore.ktc2.backend

import com.bitsycore.ktc2.kir.*

/** What a place holds when it was given objects of more than one class, or by code that cannot be seen. */
private val kAnything = Any()

/** `KTC2_SKIP=copies`: no function is copied for the classes it is called with. */
private val kSkipsCopies = "copies" in (System.getenv("KTC2_SKIP") ?: "").split(",").map { it.trim() }

/** A function with more statements than this is not copied. */
private const val kCopyStatements = 80

/** How many copies one function can have, and how many times the program is looked over for calls to copy for. */
private const val kCopiesPerFunction = 4
private const val kCopyRounds = 3

/**
 * Replaces calls through a dispatcher by calls to the implementation, where the program shows
 * which one it is. Runs first, on KIR as the importer left it: the passes after it then see an
 * ordinary call, which may turn out not to throw, to keep nothing of its arguments (so that an
 * iterator stays in the frame) or to have a result that is built in place or borrowed.
 *
 * A call is resolved when all the classes its receiver can be share one implementation:
 * - the receiver is known to be of exactly one class (below);
 * - or its static type is a class, and every class the program creates below it agrees;
 * - or the dispatcher has one implementation in the whole program and no default.
 *
 * The exact class of a value is followed through the program: a place (a local, a parameter,
 * a field of a class, a top-level property, the result of a function) is of exactly one class
 * when every value the program stores there is. An allocation is of its class; a parameter is
 * what every call passes, unless the function is also reached some other way (through a
 * dispatcher, from C, from the runtime), in which case nothing is known of it; the receiver
 * a dispatcher passes is of the class the implementation is listed for. Null says nothing: a
 * call on it does not happen.
 *
 * One thing limits what a static type says. The importer shares the code of a local class
 * between the instantiations of the generic class around it, and that code reads an object of
 * one instantiation through the type of another. Where the program does this (a cast between
 * two classes neither of which is above the other), the classes of the two families are taken
 * for one another: their fields are followed by name, and a static type stands for the family.
 *
 * A parameter that receives objects of several classes says nothing: `total(values: List<Int>)`
 * called with an `ArrayList` here and another list there. Such a function is *copied* for a
 * call that passes an object of a known class where the function calls something on it or
 * passes it on: the call goes to the copy, in which the parameter is of that class, and the
 * calls on it are direct there (the `for` over the list: its iterator, `hasNext`, `next`).
 * The function itself stays for the other calls. A small function only, a few copies of each,
 * and the program is looked over a few times, since a copy may call the next function with a
 * class that is now known.
 */
class Devirtualize(private val mModule: KModule) {

	/** For each place, the one class of the objects stored there, or [kAnything]; absent while nothing was stored. */
	private val mLocals = HashMap<KLocal, Any>()
	private val mFields = HashMap<Any, Any>()
	private val mGlobals = HashMap<KGlobal, Any>()
	private val mReturns = HashMap<KFunction, Any>()
	private var mChanged = false

	/** Classes the program creates. */
	private val mConcrete = mModule.classes.filter { it.isInstantiated }

	/**
	 * The families (see [KClass.family]) in which the program reads an object of one class through
	 * the type of another: the static type of a reference then only says which family it is of.
	 */
	private val mMixed = HashSet<Any>()

	private fun familyOf(inClass: KClass): Any = inClass.family ?: inClass

	private fun isRelated(inClass: KClass, inOther: KClass): Boolean = inClass.isSubclassOf(inOther) || inOther.isSubclassOf(inClass)

	/** Notes the two families when an object of [inFrom] is taken for one of [inTo] and neither class is above the other. */
	private fun noteMixed(inFrom: KType, inTo: KType) {
		val vFrom = (inFrom as? KClassType)?.cls ?: return
		val vTo = (inTo as? KClassType)?.cls ?: return
		if (isRelated(vFrom, vTo)) return
		mMixed += familyOf(vFrom)
		mMixed += familyOf(vTo)
	}

	/** A field of a mixed family, in whichever of its classes: the same name is the same field. */
	private data class FamilyField(val family: Any, val name: String)

	/** What a field is known by: itself, or its name in the family when objects of the family's classes are taken for one another. */
	private fun keyOf(inField: KField): Any {
		val vFamily = familyOf(inField.owner!!)
		return if (vFamily in mMixed) FamilyField(vFamily, inField.cName) else inField
	}

	/** The classes the program creates that a reference of static type [inClass] can point to. */
	private fun createdBelow(inClass: KClass): List<KClass> {
		val vFamily = familyOf(inClass)
		if (vFamily !in mMixed) return mConcrete.filter { it.isSubclassOf(inClass) }
		return mConcrete.filter { vConcrete -> generateSequence(vConcrete) { it.superClass }.any { familyOf(it) === vFamily } }
	}

	/** Calls that were made direct. Filled by [run]. */
	var resolved = 0

	/** Copies of functions that were made. Filled by [run]. */
	var copied = 0

	fun run() {
		var vRound = 0
		while (true) {
			val vFunctions = mModule.units.flatMap { it.functions + listOfNotNull(it.initializer) }
			val vBodies = vFunctions.filter { !it.isExternal && it.dispatch == null }
			if (vRound == 0) findMixed(vFunctions)
			follow(vFunctions, vBodies)
			if (kSkipsCopies || vRound++ >= kCopyRounds || !copyForClasses(vBodies)) {
				for (vFunction in vBodies) {
					answerTests(vFunction)
					val vBody = rewrite(vFunction.body)
					vFunction.body.clear()
					vFunction.body += vBody
				}
				return
			}
		}
	}

	// ==================
	// MARK: Copies
	// ==================

	/** The copy of a function for the classes of some of its parameters (null: as it is). */
	private val mCopies = HashMap<Pair<KFunction, List<KClass?>>, KFunction>()
	private val mCopyCounts = HashMap<KFunction, Int>()
	private val mWorthKnowing = HashMap<KFunction, Set<Int>>()

	/** The parameters of [inFunction] whose class decides something there: a call on it through a dispatcher, or one it is passed on to. */
	private fun worthKnowing(inFunction: KFunction): Set<Int> = mWorthKnowing.getOrPut(inFunction) {
		val vFound = HashSet<Int>()
		var vStatements = 0
		fun paramOf(inValue: KExpr): Int = when (inValue) {
			is KRefCast -> paramOf(inValue.operand)
			is KRead -> inFunction.params.indexOf(inValue.local)
			else -> -1
		}
		inFunction.body.anyStatement { vStatement ->
			vStatements++
			for (vRoot in vStatement.ownExpressions()) {
				vRoot.anyNode { vNode ->
					if (vNode is KCall && !vNode.function.isExternal) {
						val vOnes = if (vNode.function.dispatch != null) vNode.arguments.take(1) else vNode.arguments
						vOnes.forEach { vArgument -> paramOf(vArgument).let { if (it >= 0) vFound += it } }
					}
					false
				}
			}
			false
		}
		if (vStatements > kCopyStatements) emptySet() else vFound
	}

	/**
	 * Points the calls that pass an object of a known class to a parameter of which nothing is
	 * known at a copy of the function for that class.
	 *
	 * @return true when a call was changed: what is known has to be worked out again
	 */
	private fun copyForClasses(inBodies: List<KFunction>): Boolean {
		var vChanged = false
		for (vCaller in inBodies) {
			vCaller.body.anyStatement { vStatement ->
				for (vRoot in vStatement.ownExpressions()) {
					vRoot.anyNode { vNode ->
						if (vNode is KCall && copyFor(vNode)) vChanged = true
						false
					}
				}
				false
			}
		}
		return vChanged
	}

	private fun copyFor(inCall: KCall): Boolean {
		val vFunction = inCall.function
		if (vFunction.dispatch != null || vFunction.isExternal || vFunction.body.isEmpty()) return false
		val vWorth = worthKnowing(vFunction)
		if (vWorth.isEmpty()) return false
		val vClasses = vFunction.params.mapIndexed { vIndex, vParam ->
			if (vIndex !in vWorth || vParam.type !is KRefType || mLocals[vParam] !== kAnything) null else classOf(inCall.arguments[vIndex]) as? KClass
		}
		if (vClasses.all { it == null }) return false
		val vKey = vFunction to vClasses
		var vCopy = mCopies[vKey]
		if (vCopy == null) {
			val vCount = mCopyCounts[vFunction] ?: 0
			if (vCount >= kCopiesPerFunction) return false
			mCopyCounts[vFunction] = vCount + 1
			vCopy = vFunction.copyAs("${vFunction.cName}__c$vCount")
			vFunction.unit.functions += vCopy
			mCopies[vKey] = vCopy
			copied++
		}
		inCall.function = vCopy
		return true
	}

	/** Works out what every place holds, from nothing: the fixpoint described above. */
	private fun follow(vFunctions: List<KFunction>, vBodies: List<KFunction>) {
		mLocals.clear()
		mFields.clear()
		mGlobals.clear()
		mReturns.clear()
		mOnly.clear()
		openFunctions(vFunctions).forEach { vFunction -> vFunction.params.forEach { mLocals[it] = kAnything } }
		// A dispatcher hands an implementation a receiver of the class it is listed for, and arguments nobody followed.
		for (vDispatcher in vFunctions) {
			for ((vClass, vTarget) in vDispatcher.dispatch ?: continue) {
				vTarget.params.forEachIndexed { vIndex, vParam ->
					if (vIndex == 0 && vParam.type is KRefType) join(mLocals, vParam, vClass) else mLocals[vParam] = kAnything
				}
			}
		}
		// Twice to a standstill. A call through a dispatcher whose implementation is not known says
		// nothing of its result at first: the class of its receiver may still be on its way (the
		// iterator of a view of a map, itself the result of a call). Saying "anything" too early
		// would stay said. Once nothing moves, the calls that are still not known do say it.
		mIsSettling = false
		do {
			mChanged = false
			for (vFunction in vBodies) scan(vFunction)
		} while (mChanged)
		mIsSettling = true
		do {
			mChanged = false
			for (vFunction in vBodies) scan(vFunction)
		} while (mChanged)
	}

	/** False while classes are still being followed for the first time: see [follow]. */
	private var mIsSettling = false

	/** `is` tests answered. Filled by [run]. */
	var answered = 0

	/**
	 * Answers the `is` tests (and with them the tests of `as`) on a value whose exact class is
	 * known: an object of that class passes or it does not, whatever the test would find.
	 */
	private fun answerTests(inFunction: KFunction) {
		inFunction.body.anyStatement { vStatement ->
			for (vRoot in vStatement.ownExpressions()) {
				vRoot.anyNode { vNode ->
					if (vNode is KInstanceOf) {
						val vClass = classOf(vNode.operand) as? KClass
						val vPasses = when (val vTarget = vNode.target) {
							is KClassTarget -> vClass?.let { vTarget.holds(it) }
							is KInterfaceTarget -> vClass?.implements(vTarget.iface)
							// A string or a boxed number is no object of a class of the program.
							KStringTarget, is KBoxTarget -> if (vClass != null) false else null
						}
						if (vPasses != null) {
							vNode.answer = vPasses
							answered++
						}
					}
					false
				}
			}
			false
		}
	}

	// ==================
	// MARK: Exact classes
	// ==================

	/** The functions that are entered otherwise than by a call written in KIR: nothing is known of their arguments. */
	private fun openFunctions(inFunctions: List<KFunction>): Set<KFunction> {
		val vOpen = HashSet<KFunction>()
		mModule.entry?.let { vOpen += it }
		vOpen += mModule.throwers.values
		// The runtime calls these when an object ends.
		vOpen += mModule.classes.mapNotNull { it.deinit }
		for (vFunction in inFunctions) {
			// The runtime calls the platform layer by name.
			if (vFunction.cName.startsWith("ktc2_internal_")) vOpen += vFunction
			vFunction.dispatchDefault?.let { vOpen += it }
			vFunction.body.anyStatement { vStatement ->
				for (vRoot in vStatement.ownExpressions()) {
					vRoot.anyNode { vNode ->
						// A function C calls: through a pointer, or by the name written in the program's C text.
						if (vNode is KIntrinsic) {
							vOpen += vNode.functions
							vNode.trampoline?.let { vOpen += it.target }
						}
						false
					}
				}
				false
			}
		}
		return vOpen
	}

	/** Fills [mMixed]: from the casts between classes that are not related, and the implementations listed for a class that is not theirs. */
	private fun findMixed(inFunctions: List<KFunction>) {
		for (vFunction in inFunctions) {
			vFunction.dispatch?.forEach { (vClass, vTarget) -> vTarget.params.firstOrNull()?.let { noteMixed(KClassType(vClass), it.type) } }
			vFunction.body.anyStatement { vStatement ->
				for (vRoot in vStatement.ownExpressions()) {
					vRoot.anyNode { vNode ->
						if (vNode is KRefCast) noteMixed(vNode.operand.type, vNode.type)
						if (vNode is KCall) vNode.function.params.zip(vNode.arguments).forEach { (vParam, vArgument) -> noteMixed(vArgument.type, vParam.type) }
						false
					}
				}
				false
			}
		}
		// What is true of the objects is true of what their fields hold: the field of one class of a
		// mixed family is read where the code expects the field of another, of another type.
		val vByFamily = mModule.classes.groupBy { familyOf(it) }
		do {
			val vBefore = mMixed.size
			for (vFamily in mMixed.toList()) {
				val vFields = vByFamily[vFamily]?.flatMap { it.fields } ?: continue
				for (vSameName in vFields.groupBy { it.cName }.values) {
					for (vField in vSameName) noteMixed(vField.type, vSameName[0].type)
				}
			}
		} while (mMixed.size != vBefore)
	}

	private fun <K> join(inMap: MutableMap<K, Any>, inKey: K, inValue: Any?) {
		if (inValue == null) return
		val vOld = inMap[inKey]
		if (vOld === inValue || vOld === kAnything) return
		inMap[inKey] = if (vOld == null) inValue else kAnything
		mChanged = true
	}

	/** True for a field whose every store is a `KFieldSet`: a reference field of a class, held as a reference. */
	private fun isFollowed(inField: KField): Boolean =
		inField.owner != null && !inField.isInline && inField.weakType == null && inField.type is KRefType

	/** For each class, the one class the program creates at or below it; null when there are several or none. */
	private val mOnly = HashMap<KClass, KClass?>()

	/** The class [inValue] is exactly of, [kAnything], or null when it is null or nothing is known to be stored there yet. */
	private fun classOf(inValue: KExpr): Any? {
		val vFollowed = followedClass(inValue)
		if (vFollowed is KClass || inValue is KNull) return vFollowed
		// Nothing was learnt from where the value comes from (`this` in a method, say), but its type
		// may leave no choice: a class nothing extends, or with one subclass the program creates.
		val vStatic = (inValue.type as? KClassType)?.cls ?: return vFollowed
		return mOnly.getOrPut(vStatic) { createdBelow(vStatic).singleOrNull() } ?: vFollowed
	}

	private fun followedClass(inValue: KExpr): Any? = when (inValue) {
		is KAlloc -> inValue.cls
		is KStaticRead -> inValue.static.cls
		is KRefCast -> classOf(inValue.operand)
		is KNull -> null
		is KRead -> if (inValue.local.type is KRefType) mLocals[inValue.local] else kAnything
		is KFieldRead -> if (isFollowed(inValue.field)) mFields[keyOf(inValue.field)] else kAnything
		is KGlobalRead -> if (inValue.global.type is KRefType) mGlobals[inValue.global] else kAnything
		is KSelect -> {
			val vTrue = classOf(inValue.whenTrue)
			val vFalse = classOf(inValue.whenFalse)
			when {
				vTrue == null -> vFalse
				vFalse == null || vFalse === vTrue -> vTrue
				else -> kAnything
			}
		}
		is KCall -> {
			val vTarget = if (inValue.function.dispatch != null) implementationFor(inValue) else inValue.function
			when {
				// Not known which implementation, yet or at all.
				vTarget == null -> if (mIsSettling) kAnything else null
				vTarget.isExternal || vTarget.dispatch != null -> kAnything
				else -> mReturns[vTarget]
			}
		}
		else -> kAnything
	}

	private fun scan(inFunction: KFunction) {
		inFunction.body.anyStatement { vStatement ->
			when (vStatement) {
				is KLet -> vStatement.init?.let { join(mLocals, vStatement.local, classOf(it)) }
				is KSet -> join(mLocals, vStatement.local, classOf(vStatement.value))
				// A text built from parts: a string, of no class of the program.
				is KConcat -> join(mLocals, vStatement.target, kAnything)
				is KFieldSet -> if (isFollowed(vStatement.field)) join(mFields, keyOf(vStatement.field), classOf(vStatement.value))
				is KGlobalSet -> join(mGlobals, vStatement.global, classOf(vStatement.value))
				is KReturn -> vStatement.value?.let { join(mReturns, inFunction, classOf(it)) }
				else -> {}
			}
			for (vRoot in vStatement.ownExpressions()) {
				vRoot.anyNode { vNode ->
					if (vNode is KCall && vNode.function.dispatch == null && !vNode.function.isExternal) {
						// What is passed is what the parameter holds.
						vNode.function.params.zip(vNode.arguments).forEach { (vParam, vArgument) ->
							if (vParam.type is KRefType) join(mLocals, vParam, classOf(vArgument))
						}
					}
					false
				}
			}
			false
		}
	}

	// ==================
	// MARK: Resolution
	// ==================

	/** The class the static type of [inValue] names, looking through casts; null for `Any` and interfaces. */
	private fun staticClass(inValue: KExpr): KClass? =
		(inValue.type as? KClassType)?.cls ?: (inValue as? KRefCast)?.let { staticClass(it.operand) }

	/** The one implementation the dispatcher call [inCall] can reach, by what is known of its receiver now; null when there are several. */
	private fun implementationFor(inCall: KCall): KFunction? {
		val vDispatcher = inCall.function
		val vEntries = vDispatcher.dispatch ?: return null
		val vReceiver = inCall.arguments.firstOrNull() ?: return null
		val vExact = classOf(vReceiver) as? KClass
		val vClasses = if (vExact != null) listOf(vExact) else staticClass(vReceiver)?.let { createdBelow(it) }
		val vTargets = if (vClasses != null) {
			if (vClasses.isEmpty()) return null
			vClasses.map { vClass -> vEntries.firstOrNull { it.first === vClass }?.second ?: vDispatcher.dispatchDefault ?: return null }.distinct()
		} else {
			// Without a default every receiver is of a class listed: one implementation serves them all.
			if (vDispatcher.dispatchDefault != null) return null
			vEntries.map { it.second }.distinct()
		}
		return vTargets.singleOrNull()
	}

	/** True when a value of [inFrom] is passed as one of [inTo] as it is, or with a cast between pointers: no boxing. */
	private fun fits(inFrom: KType, inTo: KType): Boolean = inFrom == inTo || (inFrom is KRefType && inTo is KRefType)

	/**
	 * True when [inArgument], given to a dispatcher whose parameter is [inFrom], can go straight to an implementation
	 * whose parameter is [inTo]: an object where the implementation takes only one of its class has to be of it (the
	 * dispatcher tests that, as the JVM's bridge does), so the call is direct only when the object is known to be.
	 */
	private fun takes(inTo: KLocal, inFrom: KType, inArgument: KExpr): Boolean {
		val vClass = (inTo.type as? KClassType)?.cls ?: inTo.boundClass ?: return true
		if (inFrom != KObjType || inArgument is KNull) return true
		val vKnown = classOf(inArgument) as? KClass ?: return false
		return vKnown.isSubclassOf(vClass) || vKnown.isOfFamilyOf(vClass)
	}

	/** [inValue] with the call it is (under its casts) made direct, when it is a dispatcher call that can be. */
	private fun direct(inValue: KExpr): KExpr = when (inValue) {
		is KRefCast -> {
			val vOperand = direct(inValue.operand)
			if (vOperand === inValue.operand) inValue else KRefCast(vOperand, inValue.type)
		}
		is KCall -> {
			val vDispatcher = inValue.function
			val vTarget = implementationFor(inValue)
			if (vTarget == null || vTarget.dispatch != null || vTarget.params.size != vDispatcher.params.size ||
				!vDispatcher.params.zip(vTarget.params).all { (vFrom, vTo) -> fits(vFrom.type, vTo.type) } || !fits(vTarget.returnType, vDispatcher.returnType) ||
				!inValue.arguments.indices.drop(1).all { vIndex -> takes(vTarget.params[vIndex], vDispatcher.params[vIndex].type, inValue.arguments[vIndex]) }
			) {
				inValue
			} else {
				resolved++
				val vArguments = inValue.arguments.zip(vTarget.params).map { (vArgument, vParam) ->
					if (vArgument.type == vParam.type) vArgument else KRefCast(vArgument, vParam.type)
				}
				val vCall = KCall(vTarget, vArguments)
				if (vTarget.returnType == vDispatcher.returnType) vCall else KRefCast(vCall, vDispatcher.returnType)
			}
		}
		else -> inValue
	}

	private fun rewrite(inBlock: List<KStmt>): List<KStmt> = inBlock.map { vStatement ->
		when (vStatement) {
			is KLet -> vStatement.init?.let { vInit -> direct(vInit).let { if (it === vInit) vStatement else KLet(vStatement.local, it) } } ?: vStatement
			is KSet -> direct(vStatement.value).let { if (it === vStatement.value) vStatement else KSet(vStatement.local, it, vStatement.releasesOld) }
			is KGlobalSet -> direct(vStatement.value).let { if (it === vStatement.value) vStatement else KGlobalSet(vStatement.global, it, vStatement.releasesOld) }
			is KFieldSet -> direct(vStatement.value).let { if (it === vStatement.value) vStatement else KFieldSet(vStatement.target, vStatement.field, it, vStatement.releasesOld) }
			is KIndexSet -> direct(vStatement.value).let { vValue ->
				if (vValue === vStatement.value) vStatement
				else KIndexSet(vStatement.array, vStatement.index, vValue, vStatement.releasesOld).also { it.isChecked = vStatement.isChecked }
			}
			is KEval -> direct(vStatement.expr).let { if (it === vStatement.expr) vStatement else KEval(it) }
			is KReturn -> vStatement.value?.let { vValue -> direct(vValue).let { if (it === vValue) vStatement else KReturn(it) } } ?: vStatement
			is KThrow -> direct(vStatement.value).let { if (it === vStatement.value) vStatement else KThrow(it) }
			is KIf -> KIf(vStatement.condition, rewrite(vStatement.thenBlock), rewrite(vStatement.elseBlock))
			is KLoop -> KLoop(vStatement.id, rewrite(vStatement.body), rewrite(vStatement.latch))
			is KTryRegion -> KTryRegion(vStatement.id, rewrite(vStatement.body))
			else -> vStatement
		}
	}
}
