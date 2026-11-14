package com.bitsycore.ktc2.backend

import com.bitsycore.ktc2.kir.*

/**
 * What code can overwrite: which fields, whether array elements, which top-level properties.
 * `ArcInsert` asks it whether a reference read from such a place stays there for the length
 * of a call or of a block, in which case the reader may borrow it instead of taking a count.
 *
 * A field is the field of its class (a write to `next` of one class says nothing of another's);
 * elements of arrays of references are not told apart at all: the answer errs towards "written".
 *
 * Giving up a reference can run the program's code: the `deinit` of the object when that was
 * the last one, and of whatever dies with it. So in a program that has a `deinit`, a statement
 * that may give up a reference (a store over an old value, a local that ends with its block,
 * a result nobody takes) writes what every `deinit` writes - unless the type of the reference
 * rules out that anything with a `deinit` hangs on it: a string, an array of numbers, a class
 * whose objects hold only such things. A program without `deinit` is not affected.
 */
class Effects(inModule: KModule) {

	/** Every `deinit` of the program. */
	private val mDeinits: List<KFunction> = inModule.classes.mapNotNull { it.deinit }.distinct()

	/** The classes whose instances, and what they alone keep alive, have no `deinit`. */
	private val mQuiet: Set<KClass> = quietClasses(inModule)

	/**
	 * The classes that giving up a reference of their type cannot lead to a `deinit`. A class is
	 * out when it has one, when a field may hold something that is out, and when a subclass or
	 * another instantiation of the same generic class is out (a reference of its type may be to
	 * one of those).
	 */
	private fun quietClasses(inModule: KModule): Set<KClass> {
		val vQuiet = HashSet<KClass>()
		if (mDeinits.isEmpty()) return vQuiet
		vQuiet += inModule.classes
		// For each class, the classes that are out once it is.
		val vDependents = HashMap<KClass, MutableList<KClass>>()
		fun depends(inClass: KClass, inOn: KClass) {
			vDependents.getOrPut(inOn) { ArrayList() } += inClass
		}
		val vFamilies = inModule.classes.filter { it.family != null }.groupBy { it.family }
		val vPending = ArrayList<KClass>()
		for (vClass in inModule.classes) {
			vClass.superClass?.let {
				depends(vClass, it)
				depends(it, vClass)
			}
			vFamilies[vClass.family]?.forEach { if (it !== vClass) depends(vClass, it) }
			var vIsOut = vClass.deinit != null
			for (vField in vClass.fields) {
				classesOf(vField.type) { vHeld -> if (vHeld == null || vHeld !in vQuiet) vIsOut = true else depends(vClass, vHeld) }
			}
			if (vIsOut) vPending += vClass
		}
		while (vPending.isNotEmpty()) {
			val vClass = vPending.removeLast()
			if (vQuiet.remove(vClass)) vDependents[vClass]?.let { vPending += it }
		}
		return vQuiet
	}

	/** Passes to [inFound] the classes a value of type [inType] may refer to; null stands for any class. */
	private fun classesOf(inType: KType, inFound: (KClass?) -> Unit) {
		when (inType) {
			KStringType -> {}
			is KClassType -> inFound(inType.cls)
			is KArrayType -> classesOf(inType.element, inFound)
			is KStructType -> inType.struct.fields.forEach { classesOf(it.type, inFound) }
			is KRefType -> inFound(null)
			else -> {}
		}
	}

	private fun mayDeinit(inType: KType, inQuiet: Set<KClass>): Boolean {
		var vMay = false
		classesOf(inType) { if (it == null || it !in inQuiet) vMay = true }
		return vMay
	}

	/** True when giving up a reference of type [inType] may run a `deinit`. */
	fun mayDeinit(inType: KType): Boolean = mDeinits.isNotEmpty() && mayDeinit(inType, mQuiet)

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
	 * What [inStatement] may write before its own store takes place: for an assignment, what
	 * computing the value does. Anything else is taken whole.
	 */
	fun beforeStore(inStatement: KStmt): Summary {
		when (inStatement) {
			is KLet, is KSet, is KGlobalSet, is KFieldSet, is KIndexSet, is KEval, is KReturn -> {}
			else -> return of(listOf(inStatement))
		}
		val vSummary = Summary()
		val vCallees = HashSet<KFunction>()
		scanExpressions(inStatement, vSummary, vCallees)
		vCallees.forEach { vSummary.add(of(it)) }
		return vSummary
	}

	/**
	 * A function without a body here. The runtime's own write nothing a reference is borrowed
	 * from - except that starting or joining a thread, and entering or leaving a monitor, let
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
		// What is given up with a reference of [inType]: see the class comment.
		fun givesUp(inType: KType) {
			if (mayDeinit(inType)) outCallees += mDeinits
		}
		inBlock.anyStatement { vStatement ->
			when (vStatement) {
				// Assigning to inline storage copies a whole object over the one that is there.
				is KFieldSet -> if (vStatement.field.isInline) outSummary.all = true else {
					outSummary.fields += vStatement.field
					givesUp(vStatement.field.type)
				}
				// Only an array of references can hold what a borrowed reference depends on.
				is KIndexSet -> if (vStatement.value.type.isCounted()) {
					outSummary.elements = true
					givesUp(vStatement.value.type)
				}
				is KGlobalSet -> {
					outSummary.globals += vStatement.global
					givesUp(vStatement.global.type)
				}
				// A local gives up what it held when it is assigned again, and what it holds when its block ends.
				is KSet -> givesUp(vStatement.local.type)
				is KLet -> givesUp(vStatement.local.type)
				// A result nobody takes is given up at once.
				is KEval -> givesUp(vStatement.expr.type)
				is KRelease -> givesUp(vStatement.operand.type)
				// Printing or joining an object calls its `toString`, which is the program's code.
				is KPrint -> if (vStatement.parts.any { isObject(it) }) outSummary.all = true
				is KConcat -> if (vStatement.parts.any { isObject(it) }) outSummary.all = true
				else -> {}
			}
			scanExpressions(vStatement, outSummary, outCallees)
			false
		}
	}

	/** What the expressions of [inStatement] itself call and do. */
	private fun scanExpressions(inStatement: KStmt, outSummary: Summary, outCallees: MutableSet<KFunction>) {
		for (vRoot in inStatement.ownExpressions()) {
			vRoot.anyNode { vNode ->
				when (vNode) {
					is KCall -> {
						outCallees += vNode.function
						// The runtime may give up what hangs on the references it is handed.
						if (vNode.function.isExternal && vNode.function.params.any { mayDeinit(it.type) }) outCallees += mDeinits
					}
					is KIntrinsic -> when {
						// C written by the program, or a call through a C pointer: it may call back into anything.
						vNode.template != null || vNode.functions.isNotEmpty() || vNode.name == "ktc2_call_pointer" -> outSummary.all = true
						// While a coroutine waits, anything may run.
						vNode.isSuspendPoint() -> outSummary.all = true
						!vNode.isPure && kHarmless.none { vNode.name.startsWith(it) } -> {
							outSummary.elements = true
							// Storing over elements gives up the ones that were there.
							outCallees += mDeinits
						}
					}
					else -> {}
				}
				false
			}
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
