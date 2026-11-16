@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package kotlin.text

import kotlin.text.regex.*

/*
ktc2 platform layer: the end of a pattern of Kotlin/Native's regex engine (Harmony's). Its graph of
sets has cycles: a quantified group's end (its FSet, or the leaf of a quantified character) points
back at the quantifier, which holds the group. Kotlin/Native's collector frees them; reference
counting does not. When the Regex that owns the pattern ends (its cleaner, which Gradle adds to Native's
Regex.kt as it unpacks it: `unpackStdlibSources` in build.gradle.kts), every
set of the graph is found and its `next` cut, and the classes of characters built with operations
lose what holds them back, after which the graph is a tree that goes with the pattern. Nothing else holds a pattern: a match result holds its Regex.
*/

/** The cleaner of a Regex: [breakPatternCycles] of its [pattern] when the Regex ends. */
internal actual fun ktc2RegexCleaner(pattern: Pattern): Any = ktc2.createCleaner(pattern) { breakPatternCycles(it) }

/**
 * A class of characters of the pattern built with an operation on classes (`[\p{L}&&[^\p{Lu}]]`, a category added to
 * ranges) keeps what it tests in `nonBitSet`, an object that holds the class back: cut. The classes the engine caches
 * and shares between patterns are of ranges alone and have none.
 */
private fun cut(inClass: AbstractCharClass) {
	if (inClass is CharClass) inClass.nonBitSet = null
}

/** Cuts the links between the sets of [pattern], which nothing uses any more: see the top of the file. */
internal fun breakPatternCycles(pattern: Pattern) {
	val vSeen = HashSet<AbstractSet>()
	val vPending = ArrayList<AbstractSet>()
	fun visit(inSet: AbstractSet?) {
		// The shared sets of the engine (the placeholder `next`, the end of a possessive quantifier) belong to no pattern.
		if (inSet == null || inSet === AbstractSet.dummyNext || inSet === FSet.possessiveFSet) return
		if (vSeen.add(inSet)) vPending.add(inSet)
	}
	visit(pattern.startNode)
	pattern.capturingGroups.forEach { visit(it) }
	while (vPending.isNotEmpty()) {
		val vSet = vPending.removeAt(vPending.size - 1)
		visit(vSet.next)
		when (vSet) {
			is SingleSet -> {
				visit(vSet.kid)
				visit(vSet.fSet)
			}
			is JointSet -> {
				vSet.children.forEach { visit(it) }
				visit(vSet.fSet)
			}
			is QuantifierSet -> visit(vSet.innerSet)
			is CompositeRangeSet -> {
				visit(vSet.withoutSurrogates)
				visit(vSet.surrogates)
			}
			is RangeSet -> cut(vSet.chars)
			is SupplementaryRangeSet -> cut(vSet.chars)
			else -> {}
		}
	}
	// Every set is held by the list while the links are cut.
	for (vSet in vSeen) vSet.next = AbstractSet.dummyNext
}
