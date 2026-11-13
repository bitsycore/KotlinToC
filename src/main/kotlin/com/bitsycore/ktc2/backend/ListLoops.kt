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
 *
 * A `HashMap` gets the same, for `for ((k, v) in map)`, `map.keys` and `map.values` (and what
 * is built on it: `LinkedHashMap`, the sets). There the loop is known by the class of its
 * iterator, whichever way it was obtained; the iterator is still made, once, and the map is
 * read from it:
 *
 * ```
 * let m = it.owner; let seen = m.changes; let coming = liveFrom(m, 0)
 * loop {
 *     if (coming < 0) break
 *     if (m.changes != seen) checkChanges(m, seen)        // throws
 *     let at = coming; coming = liveFrom(m, at + 1)
 *     let k = m.keyArray[at]; let v = m.valueArray[at]; body
 * }
 * ```
 *
 * which is `HashMapCursor` written out. For the entries, the entry object is not made: the
 * loop must only ask it for its key and its value (`component1`, `component2`, `key`, `value`).
 *
 * And `list[i]`, `list[i] = x` and `list.size` on a list known to be an `ArrayList`, wherever
 * they are, are written out as what `get`, `set` and `size` do:
 *
 * ```
 * if ((i < 0) | (i >= l.length)) checkIndex(l, i)         // throws
 * let x = l.array[i]
 * ```
 *
 * so that what is left on the way of an index in range is one test: no call, and no test for
 * an exception after it.
 */
class ListLoops(private val mModule: KModule) {

	/** Loops rewritten. Filled by [run]. */
	var rewritten = 0

	/** Calls to `get`, `set` and `size` written out. Filled by [run]. */
	var accesses = 0

	private var mNext = 0

	/** What the loop needs of a list class. */
	private class ListClass(val cls: KClass, val length: KField, val changes: KField, val array: KField, val check: KFunction, val checkIndex: KFunction?)

	private val mClasses = HashMap<KFunction, ListClass?>()

	private val mListClasses = HashMap<KClass, ListClass?>()

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
		if (inFunction.cName != vClass.cName + "_iterator") return@getOrPut null
		listClass(vClass)
	}

	/** What is known of [inClass] when it is an `ArrayList`; null for another class. */
	private fun listClass(inClass: KClass): ListClass? = mListClasses.getOrPut(inClass) {
		val vClass = inClass
		val vName = vClass.cName
		if (vName != "kotlin_collections_ArrayList" && !vName.startsWith("kotlin_collections_ArrayList__")) return@getOrPut null
		fun field(inName: String): KField? = vClass.fields.firstOrNull { it.cName == inName }
		val vLength = field("length")?.takeIf { it.type == kInt } ?: return@getOrPut null
		val vChanges = field("changes")?.takeIf { it.type == kInt } ?: return@getOrPut null
		val vArray = field("array")?.takeIf { it.type is KArrayType && !it.isInline } ?: return@getOrPut null
		val vCheck = vClass.unit.functions.firstOrNull { it.cName == vName + "_checkChanges" && it.params.size == 2 } ?: return@getOrPut null
		val vCheckIndex = vClass.unit.functions.firstOrNull { it.cName == vName + "_checkIndex" && it.params.size == 2 && it.params[1].type == kInt }
		ListClass(vClass, vLength, vChanges, vArray, vCheck, vCheckIndex)
	}

	/**
	 * What replaces [inStatement] when its value is a call to `get`, `set` or `size` of an
	 * `ArrayList`: the same thing written out, the statement last. Null for any other statement.
	 */
	private fun accessor(inStatement: KStmt): List<KStmt>? {
		val vGiven = valueOf(inStatement) ?: return null
		val vCall = callOf(vGiven) ?: return null
		val vFunction = vCall.function
		if (vFunction.isExternal || vFunction.dispatch != null) return null
		val vList = (vFunction.params.firstOrNull()?.type as? KClassType)?.cls?.let { listClass(it) } ?: return null
		val vName = vFunction.cName.removePrefix(vList.cls.cName)
		val vElement = (vList.array.type as KArrayType).element
		val vOut = ArrayList<KStmt>()
		/** [inValue] where it can be read again: itself when it is a local, else a local that holds it. */
		fun again(inValue: KExpr, inType: KType, inWhat: String): () -> KExpr {
			var vInner = inValue
			while (vInner is KRefCast) vInner = vInner.operand
			val vLocal = if (vInner is KRead && inValue.type == inType) vInner.local else {
				KLocal("ktc2_$inWhat${mNext++}", inType).also { vOut += KLet(it, if (inValue.type == inType) inValue else KRefCast(inValue, inType)) }
			}
			return { if (vLocal.type == inType) KRead(vLocal) else KRefCast(KRead(vLocal), inType) }
		}
		fun inPlace(inValue: KExpr, inWith: KExpr): KExpr = if (inValue is KRefCast) KRefCast(inPlace(inValue.operand, inWith), inValue.type) else inWith
		val vListType = KClassType(vList.cls)
		if (vName == "__get_size_" && vCall.arguments.size == 1 && vFunction.returnType == kInt) {
			accesses++
			return listOf(withValue(inStatement, inPlace(vGiven, KFieldRead(vCall.arguments[0], vList.length)))!!)
		}
		val vCheckIndex = vList.checkIndex ?: return null
		val vIsGet = vName == "_get" && vCall.arguments.size == 2 && vFunction.returnType == vElement
		val vIsSet = vName == "_set" && vCall.arguments.size == 3 && inStatement is KEval && vFunction.params[2].type == vElement
		if (!vIsGet && !vIsSet) return null
		if (vFunction.params[1].type != kInt) return null
		val vHeld = again(vCall.arguments[0], vListType, "list")
		val vAt = again(vCall.arguments[1], kInt, "at")
		vOut += KIf(
			KBinary(
				KBinaryOp.OR,
				KBinary(KBinaryOp.LT, vAt(), KConst(0, kInt), KPrim.INT, kBool),
				KBinary(KBinaryOp.GE, vAt(), KFieldRead(vHeld(), vList.length), KPrim.INT, kBool),
				KPrim.BOOLEAN, kBool,
			),
			listOf(KEval(KCall(vCheckIndex, listOf(vHeld(), vAt())))),
			emptyList(),
		)
		vOut += if (vIsGet) {
			withValue(inStatement, inPlace(vGiven, KIndexRead(KFieldRead(vHeld(), vList.array), vAt(), vElement).also { it.isChecked = true }))!!
		} else {
			KIndexSet(KFieldRead(vHeld(), vList.array), vAt(), vCall.arguments[2]).also { it.isChecked = true }
		}
		accesses++
		return vOut
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
			val vOver = if (vStatement is KLet && vLoop != null) over(vStatement, vLoop, inReads, inSets) ?: overMap(vStatement, vLoop, inReads, inSets) else null
			if (vOver != null) {
				vOut += vOver.dropLast(1)
				val vNew = vOver.last() as KLoop
				vOut += KLoop(vNew.id, block(vNew.body, inReads, inSets), block(vNew.latch, inReads, inSets))
				vIndex += 2
				continue
			}
			val vWritten = accessor(vStatement)
			if (vWritten != null) {
				vOut += vWritten
				vIndex++
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

	// ==================
	// MARK: Maps
	// ==================

	/** What the loop needs of a map class and of the class of one of its iterators. */
	private class MapClass(val owner: KField, val keys: KField, val values: KField, val changes: KField, val liveFrom: KFunction, val check: KFunction)

	private val mMapClasses = HashMap<KClass, MapClass?>()

	/** What is known of the map behind the iterator class [inIterator]; null when it is no iterator of a `HashMap`. */
	private fun mapClassOf(inIterator: KClass): MapClass? = mMapClasses.getOrPut(inIterator) {
		val vOwner = inIterator.fields.firstOrNull { it.cName == "owner" } ?: return@getOrPut null
		val vMap = (vOwner.type as? KClassType)?.cls ?: return@getOrPut null
		val vName = vMap.cName
		if (vName != "kotlin_collections_HashMap" && !vName.startsWith("kotlin_collections_HashMap__")) return@getOrPut null
		fun array(inName: String): KField? = vMap.fields.firstOrNull { it.cName == inName && it.type is KArrayType && !it.isInline }
		val vKeys = array("keyArray") ?: return@getOrPut null
		val vValues = array("valueArray") ?: return@getOrPut null
		val vChanges = vMap.fields.firstOrNull { it.cName == "changes" && it.type == kInt } ?: return@getOrPut null
		val vLiveFrom = vMap.unit.functions.firstOrNull { it.cName == vName + "_liveFrom" && it.params.size == 2 && it.returnType == kInt } ?: return@getOrPut null
		val vCheck = vMap.unit.functions.firstOrNull { it.cName == vName + "_checkChanges" && it.params.size == 2 } ?: return@getOrPut null
		MapClass(vOwner, vKeys, vValues, vChanges, vLiveFrom, vCheck)
	}

	/** Which of the three iterators of a map the class named [inName] is: "Entry", "Key", "Value"; null for another class. */
	private fun iteratorKind(inName: String): String? = listOf("Entry", "Key", "Value").firstOrNull {
		val vClass = "kotlin_collections_HashMap${it}Iterator"
		inName == vClass || inName.startsWith(vClass + "__")
	}

	/** [inStatement] with [inValue] where its own value was; null for a statement that has none. */
	private fun withValue(inStatement: KStmt, inValue: KExpr): KStmt? = when (inStatement) {
		is KLet -> KLet(inStatement.local, inValue)
		is KSet -> KSet(inStatement.local, inValue, inStatement.releasesOld)
		is KGlobalSet -> KGlobalSet(inStatement.global, inValue, inStatement.releasesOld)
		is KFieldSet -> KFieldSet(inStatement.target, inStatement.field, inValue, inStatement.releasesOld)
		is KIndexSet -> KIndexSet(inStatement.array, inStatement.index, inValue, inStatement.releasesOld).also { it.isChecked = inStatement.isChecked }
		is KEval -> KEval(inValue)
		is KReturn -> KReturn(inValue)
		else -> null
	}

	private fun valueOf(inStatement: KStmt): KExpr? = when (inStatement) {
		is KLet -> inStatement.init
		is KSet -> inStatement.value
		is KGlobalSet -> inStatement.value
		is KFieldSet -> inStatement.value
		is KIndexSet -> inStatement.value
		is KEval -> inStatement.expr
		is KReturn -> inStatement.value
		else -> null
	}

	/**
	 * [inBlock] with each call that asks the entry [inEntry] for its key or its value replaced by
	 * what [inKey] and [inValue] give. [outCount] counts them; it is set to a negative number
	 * when one of them does not have the type of what would replace it.
	 */
	private fun withoutEntry(inBlock: List<KStmt>, inEntry: KLocal, inKey: () -> KExpr, inValue: () -> KExpr, outCount: IntArray): List<KStmt> = inBlock.map { vStatement ->
		when (vStatement) {
			is KIf -> KIf(vStatement.condition, withoutEntry(vStatement.thenBlock, inEntry, inKey, inValue, outCount), withoutEntry(vStatement.elseBlock, inEntry, inKey, inValue, outCount))
			is KLoop -> KLoop(vStatement.id, withoutEntry(vStatement.body, inEntry, inKey, inValue, outCount), withoutEntry(vStatement.latch, inEntry, inKey, inValue, outCount))
			is KTryRegion -> KTryRegion(vStatement.id, withoutEntry(vStatement.body, inEntry, inKey, inValue, outCount))
			else -> {
				val vGiven = valueOf(vStatement)
				val vCall = callOf(vGiven)
				val vPart = when {
					isOn(vCall, inEntry, kEntryKey) -> inKey()
					isOn(vCall, inEntry, kEntryValue) -> inValue()
					else -> null
				}
				if (vPart == null || vCall == null) {
					vStatement
				} else if (vPart.type != vCall.function.returnType || outCount[0] < 0) {
					outCount[0] = -1
					vStatement
				} else {
					outCount[0]++
					fun inPlace(inExpr: KExpr): KExpr = if (inExpr is KRefCast) KRefCast(inPlace(inExpr.operand), inExpr.type) else vPart
					withValue(vStatement, inPlace(vGiven!!))!!
				}
			}
		}
	}

	/** As [over], for a loop over the entries, the keys or the values of a `HashMap`. */
	private fun overMap(inLet: KLet, inLoop: KLoop, inReads: Map<KLocal, Int>, inSets: Map<KLocal, Int>): List<KStmt>? {
		val vIterator = inLet.local
		if (inReads[vIterator] != 2 || vIterator in inSets || inLet.init == null) return null
		val vAsk = inLoop.body.getOrNull(0) as? KLet ?: return null
		val vAskCall = callOf(vAsk.init)
		if (vAsk.local in inSets || inReads[vAsk.local] != 1 || !isOn(vAskCall, vIterator, kHasNext)) return null
		val vIteratorClass = (vAskCall!!.function.params[0].type as? KClassType)?.cls ?: return null
		val vKind = iteratorKind(vIteratorClass.cName) ?: return null
		val vMap = mapClassOf(vIteratorClass) ?: return null
		val vLeave = inLoop.body.getOrNull(1) as? KIf ?: return null
		val vNot = vLeave.condition as? KUnary ?: return null
		if (vNot.op != KUnaryOp.NOT || (vNot.operand as? KRead)?.local !== vAsk.local) return null
		if (vLeave.elseBlock.isNotEmpty() || (vLeave.thenBlock.singleOrNull() as? KBreak)?.loopId != inLoop.id) return null
		val vTake = inLoop.body.getOrNull(2) as? KLet ?: return null
		val vNextCall = callOf(vTake.init) ?: return null
		if (!isOn(vNextCall, vIterator, kNext) || vNextCall.function.params[0].type != KClassType(vIteratorClass)) return null

		val vNumber = mNext++
		val vHeld = KLocal("ktc2_map$vNumber", vMap.owner.type)
		val vSeen = KLocal("ktc2_seen$vNumber", kInt)
		val vComing = KLocal("ktc2_coming$vNumber", kInt)
		val vAt = KLocal("ktc2_at$vNumber", kInt)
		fun map(): KExpr = KRead(vHeld)
		fun element(inArray: KField): KExpr =
			KIndexRead(KFieldRead(map(), inArray), KRead(vAt), (inArray.type as KArrayType).element).also { it.isChecked = true }

		val vRest = inLoop.body.drop(3)
		val vElements: List<KStmt> = if (vKind == "Entry") {
			// The entry is only asked for its key and its value: it is not made.
			val vEntry = vTake.local
			if (vEntry in inSets) return null
			val vCount = IntArray(1)
			val vBody = withoutEntry(vRest, vEntry, { element(vMap.keys) }, { element(vMap.values) }, vCount)
			if (vCount[0] < 0 || vCount[0] != (inReads[vEntry] ?: 0) || inLoop.latch.any { vLatch -> listOf(vLatch).anyStatement { vInner -> vInner.ownExpressions().any { vRoot -> vRoot.anyNode { it is KRead && it.local === vEntry } } } }) return null
			vBody
		} else {
			val vArray = if (vKind == "Key") vMap.keys else vMap.values
			if (vNextCall.function.returnType != (vArray.type as KArrayType).element) return null
			val vRead = element(vArray)
			fun inPlace(inValue: KExpr): KExpr = if (inValue is KRefCast) KRefCast(inPlace(inValue.operand), inValue.type) else vRead
			listOf<KStmt>(KLet(vTake.local, inPlace(vTake.init!!))) + vRest
		}
		val vBody = listOf(
			KIf(KBinary(KBinaryOp.LT, KRead(vComing), KConst(0, kInt), KPrim.INT, kBool), listOf(KBreak(inLoop.id)), emptyList()),
			KIf(
				KBinary(KBinaryOp.NE, KFieldRead(map(), vMap.changes), KRead(vSeen), KPrim.INT, kBool),
				listOf(KEval(KCall(vMap.check, listOf(map(), KRead(vSeen))))),
				emptyList(),
			),
			KLet(vAt, KRead(vComing)),
			KSet(vComing, KCall(vMap.liveFrom, listOf(map(), KBinary(KBinaryOp.ADD, KRead(vAt), KConst(1, kInt), KPrim.INT, kInt)))),
		) + vElements
		rewritten++
		return listOf(
			inLet,
			KLet(vHeld, KFieldRead(KRefCast(KRead(vIterator), KClassType(vIteratorClass)), vMap.owner)),
			KLet(vSeen, KFieldRead(map(), vMap.changes)),
			KLet(vComing, KCall(vMap.liveFrom, listOf(map(), KConst(0, kInt)))),
			KLoop(inLoop.id, vBody, inLoop.latch),
		)
	}

	private companion object {
		val kEntryKey = Regex("component1__Entry|_get_key_")
		val kEntryValue = Regex("component2__Entry|_get_value_")
		val kInt = KPrimType(KPrim.INT)
		val kBool = KPrimType(KPrim.BOOLEAN)
		val kHasNext = Regex("_hasNext(__|$)")
		val kNext = Regex("_next(__|$)")
	}
}
