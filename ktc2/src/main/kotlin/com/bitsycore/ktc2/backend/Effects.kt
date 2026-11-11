package com.bitsycore.ktc2.backend

import com.bitsycore.ktc2.kir.*

/**
 * What code can overwrite: which fields, whether array elements, which top-level properties.
 * `ArcInsert` asks it whether a reference read from such a place stays there for the length
 * of a call or of a block, in which case the reader may borrow it instead of taking a count.
 *
 * Fields are told apart by name only (a write to `next` of any class counts for every `next`),
 * elements of arrays of references not at all: the answer errs towards "written".
 */
class Effects(inModule: KModule) {

	/** What a function or a block may write. [all] stands for code that cannot be looked into. */
	class Summary {
		var all = false
		var elements = false
		val fields = HashSet<String>()
		val globals = HashSet<KGlobal>()

		fun writesField(inField: KField): Boolean = all || inField.cName in fields
		fun writesElements(): Boolean = all || elements
		fun writesGlobal(inGlobal: KGlobal): Boolean = all || inGlobal in globals

		/** Adds what [inOther] writes; true when that changed this summary. */
		fun add(inOther: Summary): Boolean {
			var vChanged = false
			if (inOther.all && !all) {
				all = true
				vChanged = true
			}
			if (inOther.elements && !elements) {
				elements = true
				vChanged = true
			}
			if (fields.addAll(inOther.fields)) vChanged = true
			if (globals.addAll(inOther.globals)) vChanged = true
			return vChanged
		}
	}

	private val mSummaries = HashMap<KFunction, Summary>()
	private val mCallees = HashMap<KFunction, Set<KFunction>>()

	init {
		val vFunctions = inModule.units.flatMap { it.functions + listOfNotNull(it.initializer) }
		for (vFunction in vFunctions) {
			val vCallees = HashSet<KFunction>()
			mSummaries[vFunction] = own(vFunction, vCallees)
			mCallees[vFunction] = vCallees
		}
		// A function writes what the functions it calls write: repeated until nothing is added.
		var vChanged = true
		while (vChanged) {
			vChanged = false
			for (vFunction in vFunctions) {
				val vSummary = mSummaries.getValue(vFunction)
				for (vCallee in mCallees.getValue(vFunction)) {
					if (vSummary.add(of(vCallee))) vChanged = true
				}
			}
		}
	}

	/** What a call to [inFunction] may write, the functions it calls included. */
	fun of(inFunction: KFunction): Summary = mSummaries[inFunction] ?: opaque(inFunction)

	/** What running [inBlock] may write. */
	fun of(inBlock: List<KStmt>): Summary {
		val vSummary = Summary()
		val vCallees = HashSet<KFunction>()
		scan(inBlock, vSummary, vCallees)
		vCallees.forEach { vSummary.add(of(it)) }
		return vSummary
	}

	/** A function without a body here: the runtime's own write no field of the program, anything else might do anything. */
	private fun opaque(inFunction: KFunction): Summary = Summary().also {
		if (inFunction.cName.startsWith("ktc2_")) it.elements = true else it.all = true
	}

	private fun own(inFunction: KFunction, outCallees: MutableSet<KFunction>): Summary {
		val vDispatch = inFunction.dispatch
		return when {
			vDispatch != null -> Summary().also {
				vDispatch.forEach { (_, vTarget) -> outCallees += vTarget }
				inFunction.dispatchDefault?.let { vTarget -> outCallees += vTarget }
			}
			inFunction.isExternal -> opaque(inFunction)
			else -> Summary().also { scan(inFunction.body, it, outCallees) }
		}
	}

	private fun scan(inBlock: List<KStmt>, outSummary: Summary, outCallees: MutableSet<KFunction>) {
		inBlock.anyStatement { vStatement ->
			when (vStatement) {
				is KFieldSet -> outSummary.fields += vStatement.field.cName
				// Only an array of references can hold what a borrowed reference depends on.
				is KIndexSet -> if (vStatement.value.type.isCounted()) outSummary.elements = true
				is KGlobalSet -> outSummary.globals += vStatement.global
				// Printing or joining an object calls its `toString`, which is the program's code.
				is KPrint -> if (vStatement.parts.any { isObject(it) }) outSummary.all = true
				is KConcat -> if (vStatement.parts.any { isObject(it) }) outSummary.all = true
				else -> {}
			}
			for (vRoot in vStatement.ownExpressions()) {
				vRoot.anyNode { vNode ->
					when (vNode) {
						is KCall -> outCallees += vNode.function
						is KIntrinsic -> when {
							// C written by the program, or a call through a C pointer: it may call back into anything.
							vNode.template != null || vNode.functions.isNotEmpty() || vNode.name == "ktc2_call_pointer" -> outSummary.all = true
							!vNode.isPure && kHarmless.none { vNode.name.startsWith(it) } -> outSummary.elements = true
						}
						else -> {}
					}
					false
				}
			}
			false
		}
	}

	private companion object {
		/** Runtime helpers that are impure (they allocate, or deal with the exception in flight) but store into no array. */
		val kHarmless = listOf("ktc2_box_", "ktc2_unbox_", "ktc2_throw", "ktc2_take_pending", "ktc2_slot_take", "ktc2_fail_")
	}

	private fun isObject(inPart: KPrintPart): Boolean =
		inPart is KPrintValue && inPart.value.type.isCounted() && inPart.value.type != KStringType
}
