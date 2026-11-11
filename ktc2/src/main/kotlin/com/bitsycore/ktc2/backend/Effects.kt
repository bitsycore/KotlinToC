package com.bitsycore.ktc2.backend

import com.bitsycore.ktc2.kir.*

/**
 * What code can overwrite: which fields, whether array elements, which top-level properties.
 * `ArcInsert` asks it whether a reference read from such a place stays there for the length
 * of a call or of a block, in which case the reader may borrow it instead of taking a count.
 *
 * A field is the field of its class (a write to `next` of one class says nothing of another's);
 * elements of arrays of references are not told apart at all: the answer errs towards "written".
 */
class Effects(inModule: KModule) {

	/** What a function or a block may write. [all] stands for code that cannot be looked into. */
	class Summary {
		var all = false
		var elements = false
		val fields = HashSet<KField>()
		val globals = HashSet<KGlobal>()

		fun writesField(inField: KField): Boolean = all || inField in fields
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

	/**
	 * A function without a body here. The runtime's own write nothing a reference is borrowed
	 * from — except that starting or joining a thread, and entering or leaving a monitor, let
	 * another thread's writes through: what was read before may be gone after. Anything else
	 * (C called by the program) might do anything.
	 */
	private fun opaque(inFunction: KFunction): Summary = Summary().also {
		if (!inFunction.cName.startsWith("ktc2_") || inFunction.cName in kOtherThreads) it.all = true
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
				// Assigning to inline storage copies a whole object over the one that is there.
				is KFieldSet -> if (vStatement.field.isInline) outSummary.all = true else outSummary.fields += vStatement.field
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
		/** Runtime functions after which another thread may have changed anything. */
		val kOtherThreads = setOf("ktc2_threadStart", "ktc2_threadJoin", "ktc2_monitorEnter", "ktc2_monitorExit")

		/** Runtime helpers that are impure (they allocate, or deal with the exception in flight) but store into no array. */
		val kHarmless = listOf("ktc2_box_", "ktc2_unbox_", "ktc2_throw", "ktc2_take_pending", "ktc2_slot_take", "ktc2_fail_")
	}

	private fun isObject(inPart: KPrintPart): Boolean =
		inPart is KPrintValue && inPart.value.type.isCounted() && inPart.value.type != KStringType
}
