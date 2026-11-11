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
 * complete before its owner existed, so it cannot point back at it. Fields typed as an
 * interface, `Any` or a type parameter (which is how collections hold their elements) are not
 * followed: classes that pass the check are cycle-free only as far as their class-typed
 * fields go.
 */
class CycleLint(private val mModule: KModule) {

	/** One line per cycle found. Filled by [run]. */
	val warnings = ArrayList<String>()

	private class Edge(val from: KClass, val field: KField, val to: KClass)

	private val mEdges = HashMap<KClass, MutableList<Edge>>()

	private fun isLibrary(inClass: KClass): Boolean {
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

		val vSeen = HashSet<String>()
		for (vClass in vClasses) {
			if (isLibrary(vClass)) continue
			for (vEdge in mEdges.getValue(vClass)) {
				// An inherited field is reported once, for the class that declares it.
				if (vEdge.field.isImmutable || vEdge.field.owner !== vClass) continue
				val vBack = pathBetween(vEdge.to, vClass) ?: continue
				val vCycle = listOf(vEdge) + vBack
				// The same cycle is found from each of its `var` fields, and once per instantiation of a generic class.
				val vKey = vCycle.map { "${(it.field.owner ?: it.from).name}.${it.field.cName}" }.sorted().joinToString(",")
				if (!vSeen.add(vKey)) continue
				val vPath = vCycle.joinToString(" -> ") { "${(it.field.owner ?: it.from).name}.${it.field.cName}" } + " -> ${vClass.name}"
				warnings += "reference cycle possible: $vPath (never freed if it forms; use @Weak or WeakRef, or @Acyclic if it cannot)"
			}
		}
	}

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
