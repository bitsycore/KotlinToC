package com.bitsycore.ktc2.backend

import com.bitsycore.ktc2.kir.*

/**
 * Turns `for (x in list)` over a list known to be an `ArrayList` into a loop over its storage,
 * without an iterator. Runs after `Devirtualize`, which is what knows the class: the call to
 * `iterator()` is then a call to `ArrayList.iterator` itself.
 *
 * What the importer wrote, as K2 gives it:
 *
 * ```
 * let it = ArrayList_iterator(list)
 * loop { let t = hasNext(it); if not t break; let x = next(it); body }
 * ```
 *
 * becomes, when nothing else reads `it`:
 *
 * ```
 * let l = list; let i = 0; let seen = l.changes
 * loop {
 *     if (i == l.length) break
 *     if (l.changes != seen) checkChanges(l, seen)        // throws
 *     let x = l.array[i]; i = i + 1; body
 * }
 * ```
 *
 * which is the iterator of the platform layer (`ArrayListCursor`) written out: `hasNext`
 * compares the index with the size, `next` first fails with `ConcurrentModificationException`
 * when the list was changed, then hands out the element. The index needs no test against the
 * storage: it started at zero, is below the size, and the size cannot have gone down without
 * the count of changes going up. What is saved is the iterator, two calls per element and the
 * test for an exception after `next`.
 *
 * The class is recognized by its name and by what it must have for this: the fields `length`,
 * `changes` and `array`, and `checkChanges`.
 */
class ListLoops(private val mModule: KModule) {

	/** Loops rewritten. Filled by [run]. */
	var rewritten = 0

	private var mNext = 0

	/** What the loop needs of a list class. */
	private class ListClass(val cls: KClass, val length: KField, val changes: KField, val array: KField, val check: KFunction)

	private val mClasses = HashMap<KFunction, ListClass?>()

	fun run() {
		for (vUnit in mModule.units) {
			for (vFunction in vUnit.functions + listOfNotNull(vUnit.initializer)) {
				if (vFunction.isExternal || vFunction.dispatch != null) continue
				val vReads = HashMap<KLocal, Int>()
				val vSets = HashMap<KLocal, Int>()
				vFunction.body.anyStatement { vStatement ->
					if (vStatement is KSet) vSets.merge(vStatement.local, 1, Int::plus)
					if (vStatement is KConcat) vSets.merge(vStatement.target, 1, Int::plus)
					for (vRoot in vStatement.ownExpressions()) {
						vRoot.anyNode { if (it is KRead) vReads.merge(it.local, 1, Int::plus); false }
					}
					false
				}
				val vBody = block(vFunction.body, vReads, vSets)
				vFunction.body.clear()
				vFunction.body += vBody
			}
		}
	}

	/** The list class whose `iterator()` [inFunction] is; null for any other function. */
	private fun listClassOf(inFunction: KFunction): ListClass? = mClasses.getOrPut(inFunction) {
		val vClass = (inFunction.params.singleOrNull()?.type as? KClassType)?.cls ?: return@getOrPut null
		val vName = vClass.cName
		if (vName != "kotlin_collections_ArrayList" && !vName.startsWith("kotlin_collections_ArrayList__")) return@getOrPut null
		if (inFunction.cName != vName + "_iterator") return@getOrPut null
		fun field(inName: String): KField? = vClass.fields.firstOrNull { it.cName == inName }
		val vLength = field("length")?.takeIf { it.type == kInt } ?: return@getOrPut null
		val vChanges = field("changes")?.takeIf { it.type == kInt } ?: return@getOrPut null
		val vArray = field("array")?.takeIf { it.type is KArrayType && !it.isInline } ?: return@getOrPut null
		val vCheck = vClass.unit.functions.firstOrNull { it.cName == vName + "_checkChanges" && it.params.size == 2 } ?: return@getOrPut null
		ListClass(vClass, vLength, vChanges, vArray, vCheck)
	}

	private fun callOf(inValue: KExpr?): KCall? = when (inValue) {
		is KCall -> inValue
		is KRefCast -> callOf(inValue.operand)
		else -> null
	}

	/** True when [inCall] is `name` called on the iterator [inIterator] and nothing else. */
	private fun isOn(inCall: KCall?, inIterator: KLocal, inName: Regex): Boolean {
		if (inCall == null || inCall.arguments.size != 1 || !inName.containsMatchIn(inCall.function.cName)) return false
		var vReceiver = inCall.arguments[0]
		while (vReceiver is KRefCast) vReceiver = vReceiver.operand
		return (vReceiver as? KRead)?.local === inIterator
	}

	private fun block(inBlock: List<KStmt>, inReads: Map<KLocal, Int>, inSets: Map<KLocal, Int>): List<KStmt> {
		val vOut = ArrayList<KStmt>()
		var vIndex = 0
		while (vIndex < inBlock.size) {
			val vStatement = inBlock[vIndex]
			val vLoop = inBlock.getOrNull(vIndex + 1) as? KLoop
			val vOver = if (vStatement is KLet && vLoop != null) over(vStatement, vLoop, inReads, inSets) else null
			if (vOver != null) {
				vOut += vOver.dropLast(1)
				val vNew = vOver.last() as KLoop
				vOut += KLoop(vNew.id, block(vNew.body, inReads, inSets), block(vNew.latch, inReads, inSets))
				vIndex += 2
				continue
			}
			vOut += when (vStatement) {
				is KIf -> KIf(vStatement.condition, block(vStatement.thenBlock, inReads, inSets), block(vStatement.elseBlock, inReads, inSets))
				is KLoop -> KLoop(vStatement.id, block(vStatement.body, inReads, inSets), block(vStatement.latch, inReads, inSets))
				is KTryRegion -> KTryRegion(vStatement.id, block(vStatement.body, inReads, inSets))
				else -> vStatement
			}
			vIndex++
		}
		return vOut
	}

	/**
	 * The statements that replace [inLet] and [inLoop] when they are the loop described above:
	 * the declarations, then the loop, last. Null when they are something else.
	 */
	private fun over(inLet: KLet, inLoop: KLoop, inReads: Map<KLocal, Int>, inSets: Map<KLocal, Int>): List<KStmt>? {
		val vIterator = inLet.local
		val vMake = callOf(inLet.init) ?: return null
		val vList = listClassOf(vMake.function) ?: return null
		// The iterator is only asked `hasNext` and `next`, once each, by this loop.
		if (inReads[vIterator] != 2 || vIterator in inSets) return null
		val vAsk = inLoop.body.getOrNull(0) as? KLet ?: return null
		if (vAsk.local in inSets || inReads[vAsk.local] != 1 || !isOn(callOf(vAsk.init), vIterator, kHasNext)) return null
		val vLeave = inLoop.body.getOrNull(1) as? KIf ?: return null
		val vNot = vLeave.condition as? KUnary ?: return null
		if (vNot.op != KUnaryOp.NOT || (vNot.operand as? KRead)?.local !== vAsk.local) return null
		if (vLeave.elseBlock.isNotEmpty() || (vLeave.thenBlock.singleOrNull() as? KBreak)?.loopId != inLoop.id) return null
		val vTake = inLoop.body.getOrNull(2) as? KLet ?: return null
		val vNextCall = callOf(vTake.init) ?: return null
		val vElement = (vList.array.type as KArrayType).element
		if (!isOn(vNextCall, vIterator, kNext) || vNextCall.function.returnType != vElement) return null

		val vNumber = mNext++
		val vHeld = KLocal("ktc2_list$vNumber", KClassType(vList.cls))
		val vAt = KLocal("ktc2_at$vNumber", kInt)
		val vSeen = KLocal("ktc2_seen$vNumber", kInt)
		val vGiven = vMake.arguments[0]
		fun list(): KExpr = KRead(vHeld)
		// The element, where the call to `next` was: under the same casts.
		val vRead = KIndexRead(KFieldRead(list(), vList.array), KRead(vAt), vElement).also { it.isChecked = true }
		fun inPlace(inValue: KExpr): KExpr = if (inValue is KRefCast) KRefCast(inPlace(inValue.operand), inValue.type) else vRead
		val vBody = listOf(
			KIf(KBinary(KBinaryOp.EQ, KRead(vAt), KFieldRead(list(), vList.length), KPrim.INT, kBool), listOf(KBreak(inLoop.id)), emptyList()),
			KIf(
				KBinary(KBinaryOp.NE, KFieldRead(list(), vList.changes), KRead(vSeen), KPrim.INT, kBool),
				listOf(KEval(KCall(vList.check, listOf(list(), KRead(vSeen))))),
				emptyList(),
			),
			KLet(vTake.local, inPlace(vTake.init!!)),
			KSet(vAt, KBinary(KBinaryOp.ADD, KRead(vAt), KConst(1, kInt), KPrim.INT, kInt)),
		) + inLoop.body.drop(3)
		rewritten++
		return listOf(
			KLet(vHeld, if (vGiven.type == vHeld.type) vGiven else KRefCast(vGiven, vHeld.type)),
			KLet(vAt, KConst(0, kInt)),
			KLet(vSeen, KFieldRead(list(), vList.changes)),
			KLoop(inLoop.id, vBody, inLoop.latch),
		)
	}

	private companion object {
		val kInt = KPrimType(KPrim.INT)
		val kBool = KPrimType(KPrim.BOOLEAN)
		val kHasNext = Regex("_hasNext(__|$)")
		val kNext = Regex("_next(__|$)")
	}
}
