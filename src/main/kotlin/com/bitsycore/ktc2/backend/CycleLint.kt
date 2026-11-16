package com.bitsycore.ktc2.backend

import com.bitsycore.ktc2.kir.*

/**
 * Finds the classes whose instances can end up referring to themselves through owning
 * fields. Reference counting never frees such a cycle, so each one is reported with its path
 * (`Node.next -> Node`); the program answers with `@Weak`, `WeakRef`, or `@Acyclic` when it
 * knows the shape cannot occur.
 *
 * The check works on declared field types: a field of class `D` may hold `D` or any subclass.
 * A cycle needs at least one `var` on the way - an object reachable only through `val`s was
 * complete before its owner existed, so it cannot point back at it - or a field the owner gives
 * an object it makes and hands itself to: an `inner` object (`val key = Key()`), a lambda or an
 * object expression that captures `this` (`val handler = { update() }`), `Child(this)`. Those are
 * found in the bodies, and a field typed as a function or an interface is followed to the
 * classes stored in it that way. A field typed as an interface that a constructor (or any member)
 * sets from one of its parameters is followed to the object a caller hands it there when that is
 * the caller's own `this` (`PluginGeneratedSerialDescriptor(name, this)`): the object that comes
 * back. Other fields typed as an interface, `Any` or a type parameter (which is how collections hold
 * their elements) are not followed: classes that pass the check are cycle-free only as far as those
 * fields go.
 *
 * A cycle of two objects is freed by the runtime ([KClass.pairCycles]), for every pair of classes the
 * two fields can hold, unless the program has threads. Any other cycle is reported with the
 * property to mark `@Weak`: the one that leads back to where the cycle started.
 */
class CycleLint(private val mModule: KModule) {

	/** One line per cycle found. Filled by [run]. */
	val warnings = ArrayList<String>()

	private class Edge(val from: KClass, val field: KField, val to: KClass)

	private val mEdges = HashMap<KClass, MutableList<Edge>>()

	/** The fields given an object made with the owner's own `this` ([findSelfHandovers]): each can close a cycle though it is a `val`. */
	private val mGivenSelf = HashSet<KField>()

	/** For a function with a `self`: the fields of `self` it sets from its parameters, as the index of the parameter and the field. */
	private val mStoresParameter = HashMap<KFunction, List<Pair<Int, KField>>>()

	/** `KTC2_DEBUG_CYCLES=1`: the cycles of the library's own classes are reported too, to find what to mark weak there. */
	private val kReportsLibrary = System.getenv("KTC2_DEBUG_CYCLES") == "1"

	private fun isLibrary(inClass: KClass): Boolean {
		if (kReportsLibrary) return false
		val vPackage = inClass.unit.pkg
		return vPackage == "kotlin" || vPackage.startsWith("kotlin.") || vPackage == "ktc2" || vPackage.startsWith("ktc2.")
	}

	/** The class a field owns a reference to: its type, or the element type of an array of references. */
	private fun ownedClass(inType: KType): KClass? = when (inType) {
		is KClassType -> inType.cls
		is KArrayType -> ownedClass(inType.element)
		else -> null
	}

	fun run() {
		val vClasses = mModule.classes
		for (vClass in vClasses) {
			val vOut = mEdges.getOrPut(vClass) { ArrayList() }
			for (vLevel in generateSequence(vClass) { it.superClass }) {
				for (vField in vLevel.fields) {
					if (vField.weakType != null || vField.isAcyclic || vField.isInline) continue
					val vDeclared = ownedClass(vField.type) ?: continue
					vClasses.filter { it.isSubclassOf(vDeclared) }.forEach { vOut += Edge(vClass, vField, it) }
				}
			}
		}
		findSelfHandovers()

		val vSeen = HashSet<String>()
		for (vClass in vClasses) {
			for (vEdge in mEdges.getValue(vClass)) {
				// An inherited field is reported once, for the class that declares it.
				if ((vEdge.field.isImmutable && vEdge.field !in mGivenSelf) || vEdge.field.owner !== vClass) continue
				val vBack = pathBetween(vEdge.to, vClass) ?: continue
				val vCycle = listOf(vEdge) + vBack
				// The same cycle is found from each of its `var` fields, and once per instantiation of a generic class.
				val vKey = vCycle.map { "${(it.field.owner ?: it.from).name}.${it.field.cName}" }.sorted().joinToString(",")
				if (!vSeen.add(vKey)) continue
				// Two objects that hold each other: the runtime frees them when nothing else holds either (KClass.pairCycles),
				// whichever classes of the two fields' types they are. Not in a program with threads, where another thread
				// may be taking one of them.
				val vPairable = vCycle.size == 2 && isPairable(vCycle[0]) && isPairable(vCycle[1])
				if (vPairable && !mModule.usesThreads) {
					// Every field of one that may hold the other, with every field of the other that may hold it back: an
					// object may be held by several of its partner's (`posValue` and `negValue` of the same cache).
					val (vThere, vBack) = vCycle
					val vForth = mEdges.getValue(vThere.from).filter { it.to === vThere.to && isPairable(it) }.map { it.field }.distinct()
					val vBackFields = mEdges.getValue(vBack.from).filter { it.to === vBack.to && isPairable(it) }.map { it.field }.distinct()
					for (vField in vForth) for (vOther in vBackFields) pair(vField, vOther)
					continue
				}
				// The library's own cycles are its design, answered in the library: not the program's to change.
				if (isLibrary(vClass)) continue
				val vPath = vCycle.joinToString(" -> ") { "${shownName(it.field.owner ?: it.from)}.${it.field.cName}" } + " -> ${shownName(vClass)}"
				warnings += "reference cycle possible: $vPath (never freed if it forms): ${advice(vCycle.last(), vPairable)}"
			}
		}
	}

	/**
	 * Finds, in every function, the objects made and handed the function's `self` (a field of
	 * theirs set to it, or `self` among the arguments of a call on them, their constructor's) and then stored in a
	 * field of `self`: `let t = new C; t.outer = self; self.key = t`, also through a variable given one such object or
	 * another (`val d = if (x) A(this) else B(this)`). Such a field gets an edge to each class even when its declared
	 * type is not followed, and may close a cycle.
	 */
	private fun findSelfHandovers() {
		val vFunctions = mModule.units.flatMap { it.functions }
		findParameterStores(vFunctions)
		findTypedStores(vFunctions)
		for (vFunction in vFunctions) {
			val vSelf = vFunction.params.firstOrNull()?.takeIf { it.cName == "self" } ?: continue
			val vSelfClass = (vSelf.type as? KClassType)?.cls ?: continue
			// The classes of the objects made here that a local may hold, and the locals whose objects were handed `self`.
			val vMade = HashMap<KLocal, MutableSet<KClass>>()
			val vHandedSelf = HashSet<KLocal>()
			fun isSelf(inExpr: KExpr): Boolean = unwrapped(inExpr).let { it is KRead && it.local === vSelf }
			fun madeLocal(inExpr: KExpr): KLocal? = (unwrapped(inExpr) as? KRead)?.local?.takeIf { it in vMade }
			// A local given what another holds: the same objects, handed `self` if those were.
			fun copy(inTarget: KLocal, inValue: KExpr) {
				val vSource = madeLocal(inValue) ?: return
				vMade.getOrPut(inTarget) { HashSet() } += vMade.getValue(vSource)
				if (vSource in vHandedSelf) vHandedSelf += inTarget
			}
			// Twice: a local copied before the object in it was handed `self` (the order of statements in two branches).
			repeat(2) {
				vFunction.body.anyStatement { vStatement ->
					if (vStatement is KLet && vStatement.init is KAlloc) vMade.getOrPut(vStatement.local) { HashSet() } += (vStatement.init as KAlloc).cls
					if (vStatement is KLet && vStatement.init != null) copy(vStatement.local, vStatement.init)
					if (vStatement is KSet) copy(vStatement.local, vStatement.value)
					if (vStatement is KFieldSet) {
						val vTarget = unwrapped(vStatement.target)
						val vValue = unwrapped(vStatement.value)
						if (vTarget is KRead && vTarget.local in vMade && isSelf(vValue)) vHandedSelf += vTarget.local
						if (isSelf(vTarget) && vValue is KRead && vValue.local in vHandedSelf) {
							val vField = vStatement.field
							if (vField.weakType == null && !vField.isAcyclic && !vField.isInline) {
								mGivenSelf += vField
								val vOut = mEdges.getOrPut(vSelfClass) { ArrayList() }
								for (vMadeClass in vMade.getValue(vValue.local)) {
									if (vOut.none { it.field === vField && it.to === vMadeClass }) vOut += Edge(vSelfClass, vField, vMadeClass)
								}
							}
						}
					}
					val vCall = ((vStatement as? KEval)?.expr ?: (vStatement as? KLet)?.init)?.let(::unwrapped) as? KCall
					if (vCall != null) {
						val vObject = vCall.arguments.firstOrNull()?.let(::unwrapped)
						if (vObject is KRead && vObject.local in vMade && vCall.arguments.drop(1).any(::isSelf)) vHandedSelf += vObject.local
						// `self` handed to a member that keeps it in a field typed as an interface: that field refers back to us.
						for ((vIndex, vField) in mStoresParameter[vCall.function] ?: emptyList()) {
							if (vIndex >= vCall.arguments.size || !isSelf(vCall.arguments[vIndex])) continue
							val vHolders = (vObject as? KRead)?.local?.let { vMade[it] } ?: listOfNotNull((vObject?.type as? KClassType)?.cls)
							for (vFrom in vHolders.flatMap(::instancesOf).distinct()) {
								if (vField.owner != null && !vFrom.isSubclassOf(vField.owner)) continue
								val vOut = mEdges.getOrPut(vFrom) { ArrayList() }
								for (vTo in instancesOf(vSelfClass)) if (vOut.none { it.field === vField && it.to === vTo }) vOut += Edge(vFrom, vField, vTo)
							}
						}
					}
					false
				}
			}
		}
	}

	/**
	 * A field typed as an interface or `Any` given, anywhere, a value whose class is known (`left.other = right`): an
	 * edge from the classes that have the field to the classes the value may be.
	 */
	private fun findTypedStores(inFunctions: List<KFunction>) {
		for (vFunction in inFunctions) {
			vFunction.body.anyStatement { vStatement ->
				val vField = (vStatement as? KFieldSet)?.field
				val vClass = ((vStatement as? KFieldSet)?.value?.let(::unwrapped)?.type as? KClassType)?.cls
				if (vField != null && vClass != null && vField.type == KObjType && vField.owner != null && vField.weakType == null && !vField.isAcyclic && !vField.isInline) {
					for (vFrom in instancesOf(vField.owner)) {
						val vOut = mEdges.getOrPut(vFrom) { ArrayList() }
						for (vTo in instancesOf(vClass)) if (vOut.none { it.field === vField && it.to === vTo }) vOut += Edge(vFrom, vField, vTo)
					}
				}
				false
			}
		}
	}

	/** The classes the program makes that are [inClass] or below it: what a reference typed as [inClass] may hold. */
	private fun instancesOf(inClass: KClass): List<KClass> = mModule.classes.filter { it.isSubclassOf(inClass) && it.isInstantiated }

	/**
	 * Fills [mStoresParameter]: the fields typed as an interface or `Any` that a function sets on its `self` from one
	 * of its parameters, directly, through a local that only copies it, or through a call on `self` that does
	 * (a subclass's constructor handing it to its superclass's): repeated until nothing is added.
	 */
	private fun findParameterStores(inFunctions: List<KFunction>) {
		var vChanged = true
		while (vChanged) {
			vChanged = false
			for (vFunction in inFunctions) {
				val vSelf = vFunction.params.firstOrNull()?.takeIf { it.cName == "self" } ?: continue
				val vCopies = HashMap<KLocal, KLocal>()
				fun parameterOf(inExpr: KExpr): KLocal? {
					val vRead = unwrapped(inExpr) as? KRead ?: return null
					return vCopies[vRead.local] ?: vRead.local.takeIf { it in vFunction.params && it !== vSelf }
				}
				val vStores = LinkedHashSet(mStoresParameter[vFunction] ?: emptyList())
				val vBefore = vStores.size
				vFunction.body.anyStatement { vStatement ->
					if (vStatement is KLet && vStatement.init != null) parameterOf(vStatement.init)?.let { vCopies[vStatement.local] = it }
					if (vStatement is KFieldSet && (unwrapped(vStatement.target) as? KRead)?.local === vSelf) {
						val vField = vStatement.field
						val vParameter = parameterOf(vStatement.value)
						if (vParameter != null && vField.type == KObjType && vField.weakType == null && !vField.isAcyclic && !vField.isInline) {
							vStores += vFunction.params.indexOf(vParameter) to vField
						}
					}
					val vCall = ((vStatement as? KEval)?.expr ?: (vStatement as? KLet)?.init)?.let(::unwrapped) as? KCall
					if (vCall != null && vCall.function !== vFunction && (vCall.arguments.firstOrNull()?.let(::unwrapped) as? KRead)?.local === vSelf) {
						for ((vIndex, vField) in mStoresParameter[vCall.function] ?: emptyList()) {
							val vParameter = vCall.arguments.getOrNull(vIndex)?.let(::parameterOf) ?: continue
							vStores += vFunction.params.indexOf(vParameter) to vField
						}
					}
					false
				}
				if (vStores.size != vBefore) {
					mStoresParameter[vFunction] = vStores.toList()
					vChanged = true
				}
			}
		}
	}

	/** True when [inEdge] is a plain reference field (not an array), what a pair check can read. */
	private fun isPairable(inEdge: Edge): Boolean {
		val vType = inEdge.field.type
		return vType is KClassType || vType == KObjType
	}

	/**
	 * Registers the cycles of two objects through [inThere] and [inBack]: for each class `A` that has
	 * [inThere] and may hold in it a `B` whose [inBack] may hold an `A`, `A` checks for a `B` there
	 * (the runtime compares the class exactly, so every pair of classes is one entry).
	 */
	private fun pair(inThere: KField, inBack: KField) {
		fun register(inField: KField, inOther: KField) {
			for ((vFrom, vEdges) in mEdges) {
				for (vEdge in vEdges) {
					// Only a class the program makes has a type the runtime can compare with.
					if (vEdge.field !== inField || !vFrom.isInstantiated || !vEdge.to.isInstantiated) continue
					val vReturns = mEdges[vEdge.to]?.any { it.field === inOther && it.to === vFrom } == true
					if (!vReturns || vFrom.pairCycles.any { it.field === inField && it.partner === vEdge.to && it.back === inOther }) continue
					vFrom.pairCycles += KPairCycle(inField, vEdge.to, inOther)
				}
			}
		}
		register(inThere, inBack)
		if (inBack !== inThere) register(inBack, inThere)
	}

	/** What a warning of a cycle asks of the program: [inBack] is the reference that closes it. */
	private fun advice(inBack: Edge, inPairable: Boolean): String {
		val vOwner = shownName(inBack.field.owner ?: inBack.from)
		val vName = inBack.field.cName
		val vWhat = when {
			vOwner.startsWith("(") -> "capture a WeakRef of ${shownName(inBack.to)} in the $vOwner rather than the object itself"
			// The reference of an inner object to its outer one (KirImport: `outer`, or `outer_` when a property has that name).
			vName.trimEnd('_') == "outer" && inBack.field.isImmutable -> "mark the inner class $vOwner @Weak (its reference to the outer object)"
			else -> "mark $vOwner.$vName @Weak if it does not own what it refers to"
		}
		val vThreads = if (inPairable) "; a cycle of two objects is freed by itself only in a program without threads" else ""
		return "$vWhat, use WeakRef, or @Acyclic if the cycle cannot form$vThreads"
	}

	/** The name of [inClass] in a warning: a lambda and an object expression have none of their own. */
	private fun shownName(inClass: KClass): String = when {
		inClass.name == "lambda" -> "(lambda)"
		inClass.name.endsWith("<no name provided>") -> "(object)"
		else -> inClass.name
	}

	private fun unwrapped(inExpr: KExpr): KExpr = if (inExpr is KRefCast) unwrapped(inExpr.operand) else inExpr

	/** The shortest chain of fields leading from [inFrom] to [inTo]; empty when they are the same class. */
	private fun pathBetween(inFrom: KClass, inTo: KClass): List<Edge>? {
		if (inFrom === inTo) return emptyList()
		val vCameBy = HashMap<KClass, Edge>()
		val vPending = ArrayDeque(listOf(inFrom))
		while (vPending.isNotEmpty()) {
			val vNext = vPending.removeFirst()
			for (vEdge in mEdges[vNext] ?: continue) {
				if (vEdge.to === inFrom || vEdge.to in vCameBy) continue
				vCameBy[vEdge.to] = vEdge
				if (vEdge.to === inTo) {
					val vPath = ArrayList<Edge>()
					var vAt = inTo
					while (vAt !== inFrom) {
						val vStep = vCameBy.getValue(vAt)
						vPath += vStep
						vAt = vStep.from
					}
					return vPath.reversed()
				}
				vPending += vEdge.to
			}
		}
		return null
	}
}
