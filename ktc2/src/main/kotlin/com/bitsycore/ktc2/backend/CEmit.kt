package com.bitsycore.ktc2.backend

import com.bitsycore.ktc2.kir.*
import java.io.File

private val kRuntimeFiles = listOf("ktc2_core.h", "ktc2_core.c")

/**
 * Prints a [KModule] as C11: one `.h` / `.c` pair per [KUnit], the runtime, `ktc2_types.h`
 * (type ids) and `ktc2_main.c` (type table, startup, `main`).
 * KIR is already in statement form, so this is a direct print with no analysis.
 */
class CEmitter(private val mModule: KModule) {

	/** Classes the program instantiates, in type-id order. */
	private val mConcrete = mModule.classes.filter { it.isInstantiated }

	/**
	 * Writes all files under [inRoot].
	 *
	 * @return the `.c` files to compile, relative to [inRoot]
	 */
	fun emit(inRoot: File): List<String> {
		val vSources = ArrayList<String>()
		write(File(inRoot, "ktc2_types.h"), typeIds())
		for (vUnit in mModule.units) {
			write(File(inRoot, vUnit.path + ".h"), header(vUnit))
			write(File(inRoot, vUnit.path + ".c"), source(vUnit))
			vSources += vUnit.path + ".c"
		}
		for (vName in kRuntimeFiles) {
			val vText = javaClass.getResourceAsStream("/ktc2/$vName")!!.use { it.readBytes().decodeToString() }
			write(File(inRoot, vName), vText)
			if (vName.endsWith(".c")) vSources += vName
		}
		mModule.entry?.let { vEntry ->
			write(File(inRoot, "ktc2_main.c"), program(vEntry))
			vSources += "ktc2_main.c"
		}
		return vSources
	}

	/** Leaves an unchanged file untouched so the C build stays incremental. */
	private fun write(inFile: File, inText: String) {
		if (inFile.isFile && inFile.readText() == inText) return
		inFile.parentFile.mkdirs()
		inFile.writeText(inText)
	}

	// ==================
	// MARK: Program files
	// ==================

	private fun typeId(inClass: KClass): String = "KTC2_TYPE_${inClass.cName}"

	private fun typeIds(): String = buildString {
		append("#ifndef KTC2_TYPES_H\n#define KTC2_TYPES_H\n\n#include \"ktc2_core.h\"\n\n")
		append("/* Type ids of the classes this program instantiates. */\nenum {\n")
		mConcrete.forEachIndexed { vIndex, vClass ->
			append("\t${typeId(vClass)} = KTC2_TYPE_FIRST_USER${if (vIndex == 0) "" else " + $vIndex"},\n")
		}
		append("\tKTC2_TYPE_COUNT = KTC2_TYPE_FIRST_USER + ${mConcrete.size}\n};\n\n#endif\n")
	}

	private fun program(inEntry: KFunction): String = buildString {
		val vStartup = mModule.units.mapNotNull { it.initializer }
		val vGlobals = mModule.units.flatMap { it.globals }.filter { it.type is KRefType }
		val vSingletons = mModule.classes.filter { it.isSingleton }
		val vUnits = vStartup.map { it.unit } + mConcrete.map { it.unit } + vSingletons.map { it.unit } + vGlobals.map { it.unit } + inEntry.unit
		append("#include \"ktc2_types.h\"\n")
		vUnits.distinct().forEach { append("#include \"${it.path}.h\"\n") }

		append("\nconst ktc2_TypeInfo ktc2_types[] = {\n\tKTC2_BUILTIN_TYPES,\n")
		mConcrete.forEach { append("\t[${typeId(it)}] = { ${cString(it.name)}, ${it.cName}__destroy },\n") }
		append("};\n\nconst uint32_t ktc2_type_count = KTC2_TYPE_COUNT;\n")

		append("\nint main(void)\n{\n")
		vStartup.forEach { append("\t${it.cName}();\n") }
		append("\t${inEntry.cName}();\n")
		vGlobals.asReversed().forEach { append("\tktc2_release(${it.cName});\n") }
		vSingletons.asReversed().forEach { append("\t${it.cName}__destroy((ktc2_Obj*)&${it.cName}_instance);\n") }
		append("\treturn ktc2_exit();\n}\n")
	}

	// ==================
	// MARK: Units
	// ==================

	private fun unitOf(inType: KType): KUnit? = (inType as? KStructType)?.struct?.unit

	private fun cType(inType: KType): String = when (inType) {
		is KPrimType -> inType.prim.cName
		is KOptType -> "ktc2_Opt_${inType.prim.cName}"
		is KStructType -> inType.struct.cName
		KStringType -> "ktc2_String*"
		is KClassType -> "${inType.cls.cName}*"
		KObjType -> "ktc2_Obj*"
		is KArrayType -> "ktc2_Array*"
		KUnitType, KNothingType -> "void"
	}

	private fun signature(inFunction: KFunction): String {
		val vParams = if (inFunction.params.isEmpty()) "void" else inFunction.params.joinToString(", ") { "${cType(it.type)} ${it.cName}" }
		return "${cType(inFunction.returnType)} ${inFunction.cName}($vParams)"
	}

	private fun allFunctions(inUnit: KUnit): List<KFunction> = inUnit.functions + listOfNotNull(inUnit.initializer)

	/** Reference fields of [inClass] itself (not inherited). */
	private fun refFields(inClass: KClass): List<KField> = inClass.fields.filter { it.type is KRefType }

	private fun header(inUnit: KUnit): String = buildString {
		val vGuard = "KTC2_" + inUnit.path.uppercase().map { if (it.isLetterOrDigit()) it else '_' }.joinToString("") + "_H"
		append("#ifndef $vGuard\n#define $vGuard\n\n#include \"ktc2_core.h\"\n")

		// By-value types need the full definition; classes are only used through pointers,
		// so a forward declaration is enough and headers never include each other in a cycle.
		val vTypes = ArrayList<KType>()
		inUnit.struct?.fields?.forEach { vTypes += it.type }
		inUnit.cls?.fields?.forEach { vTypes += it.type }
		inUnit.globals.forEach { vTypes += it.type }
		for (vFunction in allFunctions(inUnit)) {
			vTypes += vFunction.returnType
			vFunction.params.forEach { vTypes += it.type }
		}
		val vIncludes = LinkedHashSet<KUnit>()
		vTypes.forEach { vType -> unitOf(vType)?.let(vIncludes::add) }
		inUnit.cls?.superClass?.let { vIncludes += it.unit }
		vIncludes.filter { it !== inUnit }.forEach { append("#include \"${it.path}.h\"\n") }
		append("\n")
		val vForward = LinkedHashSet<KClass>()
		inUnit.cls?.let(vForward::add)
		vTypes.forEach { vType -> (vType as? KClassType)?.let { vForward += it.cls } }
		vForward.forEach { append("typedef struct ${it.cName} ${it.cName};\n") }
		if (vForward.isNotEmpty()) append("\n")

		inUnit.struct?.let { vStruct ->
			append("typedef struct ${vStruct.cName} {\n")
			if (vStruct.fields.isEmpty()) append("\tchar _empty;\n")
			vStruct.fields.forEach { append("\t${cType(it.type)} ${it.cName};\n") }
			append("} ${vStruct.cName};\n\n")
		}
		inUnit.cls?.let { vClass ->
			append("struct ${vClass.cName} {\n")
			append(vClass.superClass?.let { "\t${it.cName} _super;\n" } ?: "\tktc2_Obj _header;\n")
			vClass.fields.forEach { append("\t${cType(it.type)} ${it.cName};\n") }
			append("};\n\n")
			if (vClass.isSingleton) append("extern ${vClass.cName} ${vClass.cName}_instance;\n")
			append("/** Releases the references held by the fields of an instance. */\n")
			append("void ${vClass.cName}__destroy(ktc2_Obj* object);\n")
		}
		inUnit.globals.forEach { append("extern ${cType(it.type)} ${it.cName};\n") }
		allFunctions(inUnit).forEach { append(signature(it)).append(";\n") }
		append("\n#endif\n")
	}

	private fun source(inUnit: KUnit): String {
		val vBodies = StringBuilder()
		val vState = UnitState()
		val vPrinter = FunctionPrinter(vBodies, vState)

		inUnit.cls?.let { vClass ->
			if (vClass.isSingleton) {
				val vPath = generateSequence(vClass) { it.superClass }.drop(1).joinToString("") { "._super" } + "._header"
				vBodies.append("\n${vClass.cName} ${vClass.cName}_instance = { $vPath = { KTC2_IMMORTAL, ${typeId(vClass)} } };\n")
			}
			vBodies.append("\nvoid ${vClass.cName}__destroy(ktc2_Obj* object)\n{\n")
			val vOwn = refFields(vClass)
			if (vOwn.isNotEmpty()) vBodies.append("\t${vClass.cName}* self = (${vClass.cName}*)object;\n")
			vOwn.forEach { vBodies.append("\tktc2_release(self->${it.cName});\n") }
			vClass.superClass?.let {
				vState.includes += it.unit
				vBodies.append("\t${it.cName}__destroy(object);\n")
			}
			if (vOwn.isEmpty() && vClass.superClass == null) vBodies.append("\t(void)object;\n")
			vBodies.append("}\n")
		}
		if (inUnit.globals.isNotEmpty()) vBodies.append("\n")
		for (vGlobal in inUnit.globals) {
			val vInit = vGlobal.constInit?.let { " = ${vPrinter.constant(it)}" } ?: ""
			vBodies.append("${cType(vGlobal.type)} ${vGlobal.cName}$vInit;\n")
		}
		for (vFunction in allFunctions(inUnit)) {
			vBodies.append("\n").append(signature(vFunction)).append("\n{\n")
			if (vFunction.dispatch != null) vPrinter.dispatcher(vFunction) else vPrinter.block(vFunction.body, 1)
			vBodies.append("}\n")
		}

		return buildString {
			append("#include \"${inUnit.path}.h\"\n#include \"ktc2_types.h\"\n")
			vState.includes.filter { it !== inUnit && it.name != "ktc2_core" }.forEach { append("#include \"${it.path}.h\"\n") }
			if (vState.literals.isNotEmpty()) append("\n")
			vState.literals.forEach { (vText, vName) ->
				append("KTC2_STRING_LIT($vName, ${vText.encodeToByteArray().size}, ${vText.length}, ${cString(vText)});\n")
			}
			vState.typeTests.forEachIndexed { vIndex, vIds ->
				append("\nstatic bool is_$vIndex(const void* object)\n{\n\tif (!object) return false;\n")
				if (vIds.isEmpty()) {
					append("\treturn false;\n}\n")
				} else {
					append("\tswitch (((const ktc2_Obj*)object)->typeId) {\n")
					vIds.forEach { append("\tcase $it:\n") }
					append("\t\treturn true;\n\tdefault:\n\t\treturn false;\n\t}\n}\n")
				}
			}
			append(vBodies)
		}
	}

	/** What a `.c` file collects while its functions are printed. */
	private class UnitState {
		val includes = LinkedHashSet<KUnit>()
		val literals = LinkedHashMap<String, String>()
		val typeTests = ArrayList<List<String>>()
	}

	/** A C string literal holding the UTF-8 bytes of [inText]. */
	private fun cString(inText: String): String = buildString {
		append('"')
		for (vByte in inText.encodeToByteArray()) {
			val vCode = vByte.toInt() and 0xFF
			when {
				vCode == '"'.code -> append("\\\"")
				vCode == '\\'.code -> append("\\\\")
				vCode == '\n'.code -> append("\\n")
				vCode == '\t'.code -> append("\\t")
				vCode in 32..126 && vCode != '?'.code -> append(vCode.toChar())
				else -> append('\\').append(vCode.toString(8).padStart(3, '0'))
			}
		}
		append('"')
	}

	// ==================
	// MARK: Function bodies
	// ==================

	private inner class FunctionPrinter(
		private val mOut: StringBuilder,
		private val mState: UnitState,
	) {
		/** Enclosing loops, innermost last. */
		private val mLoops = ArrayList<KLoop>()
		private val mBreakLabels = HashSet<Int>()
		private val mContinueLabels = HashSet<Int>()

		fun block(inStatements: List<KStmt>, inDepth: Int) {
			inStatements.forEach { statement(it, inDepth) }
		}

		private fun line(inDepth: Int, inText: String) {
			repeat(inDepth) { mOut.append('\t') }
			mOut.append(inText).append('\n')
		}

		private fun need(inType: KType) {
			when (inType) {
				is KStructType -> mState.includes += inType.struct.unit
				is KClassType -> mState.includes += inType.cls.unit
				else -> {}
			}
		}

		/** Body of a virtual dispatcher: a switch on the receiver's type id. */
		fun dispatcher(inFunction: KFunction) {
			val vSelf = inFunction.params[0].cName
			val vIsVoid = inFunction.returnType == KUnitType
			fun call(inTarget: KFunction): String {
				mState.includes += inTarget.unit
				val vArguments = inFunction.params.zip(inTarget.params).joinToString(", ") { (vFrom, vTo) ->
					if (vFrom.type == vTo.type) vFrom.cName else "(${cType(vTo.type)})${vFrom.cName}"
				}
				val vCall = "${inTarget.cName}($vArguments)"
				if (vIsVoid) return "$vCall; return;"
				return if (inTarget.returnType == inFunction.returnType) "return $vCall;" else "return (${cType(inFunction.returnType)})$vCall;"
			}
			line(1, "switch (((ktc2_Obj*)$vSelf)->typeId) {")
			for ((vClass, vTarget) in inFunction.dispatch!!) {
				mState.includes += vClass.unit
				line(1, "case ${typeId(vClass)}: ${call(vTarget)}")
			}
			line(1, "default: ${inFunction.dispatchDefault?.let { call(it) } ?: "ktc2_fail_dispatch();"}")
			line(1, "}")
		}

		private fun statement(inStatement: KStmt, inDepth: Int) {
			when (inStatement) {
				is KLet -> {
					need(inStatement.local.type)
					val vInit = inStatement.init?.let { " = ${bare(expr(it))}" } ?: ""
					line(inDepth, "${cType(inStatement.local.type)} ${inStatement.local.cName}$vInit;")
				}
				is KSet -> store(inStatement.local.cName, inStatement.value, inStatement.releasesOld, inDepth)
				is KGlobalSet -> {
					mState.includes += inStatement.global.unit
					store(inStatement.global.cName, inStatement.value, inStatement.releasesOld, inDepth)
				}
				is KFieldSet -> store(field(inStatement.target, inStatement.field), inStatement.value, inStatement.releasesOld, inDepth)
				is KIndexSet -> store(element(inStatement.array, inStatement.index, inStatement.value.type), inStatement.value, inStatement.releasesOld, inDepth)
				is KEval -> line(inDepth, "${bare(expr(inStatement.expr))};")
				is KRelease -> line(inDepth, "ktc2_release(${bare(expr(inStatement.operand))});")
				is KReturn -> line(inDepth, inStatement.value?.let { "return ${bare(expr(it))};" } ?: "return;")
				is KIf -> ifChain(inStatement, inDepth)
				is KLoop -> loop(inStatement, inDepth)
				is KBreak -> {
					if (mLoops.last().id == inStatement.loopId) {
						line(inDepth, "break;")
					} else {
						mBreakLabels += inStatement.loopId
						line(inDepth, "goto break_${inStatement.loopId};")
					}
				}
				is KContinue -> {
					val vInner = mLoops.last()
					if (vInner.id == inStatement.loopId && vInner.latch.isEmpty()) {
						line(inDepth, "continue;")
					} else {
						mContinueLabels += inStatement.loopId
						line(inDepth, "goto continue_${inStatement.loopId};")
					}
				}
				is KPrint -> print(inStatement, inDepth)
				is KConcat -> concat(inStatement, inDepth)
			}
		}

		private fun store(inSlot: String, inValue: KExpr, inReleasesOld: Boolean, inDepth: Int) {
			val vValue = bare(expr(inValue))
			line(inDepth, if (inReleasesOld) "KTC2_ASSIGN($inSlot, $vValue);" else "$inSlot = $vValue;")
		}

		private fun ifChain(inIf: KIf, inDepth: Int) {
			var vCurrent = inIf
			var vKeyword = "if"
			while (true) {
				line(inDepth, "$vKeyword (${bare(expr(vCurrent.condition))}) {")
				block(vCurrent.thenBlock, inDepth + 1)
				val vElse = vCurrent.elseBlock
				if (vElse.isEmpty()) break
				val vOnly = vElse.singleOrNull() as? KIf
				if (vOnly == null) {
					line(inDepth, "} else {")
					block(vElse, inDepth + 1)
					break
				}
				vCurrent = vOnly
				vKeyword = "} else if"
			}
			line(inDepth, "}")
		}

		private fun loop(inLoop: KLoop, inDepth: Int) {
			line(inDepth, "for (;;) {")
			// Loop ids are per function: forget labels requested by an earlier function's loop.
			mBreakLabels -= inLoop.id
			mContinueLabels -= inLoop.id
			mLoops += inLoop
			block(inLoop.body, inDepth + 1)
			if (inLoop.id in mContinueLabels) line(inDepth, "continue_${inLoop.id}: ;")
			block(inLoop.latch, inDepth + 1)
			mLoops.removeLast()
			line(inDepth, "}")
			if (inLoop.id in mBreakLabels) line(inDepth, "break_${inLoop.id}: ;")
		}

		// ============
		//  Text output

		private fun valueSuffix(inPrim: KPrim): String = when (inPrim) {
			KPrim.BOOLEAN -> "bool"
			KPrim.BYTE, KPrim.SHORT, KPrim.INT -> "int"
			KPrim.LONG -> "long"
			KPrim.CHAR -> "char"
			KPrim.FLOAT -> "float"
			KPrim.DOUBLE -> "double"
			KPrim.UBYTE, KPrim.USHORT, KPrim.UINT -> "uint"
			KPrim.ULONG -> "ulong"
		}

		/**
		 * Prints [inParts] through `<inPrefix>_<kind>(<inFirst>value)` calls: `ktc2_print` for
		 * stdout, `ktc2_sb` (with the builder as first argument) for string building.
		 */
		private fun parts(inParts: List<KPrintPart>, inPrefix: String, inFirst: String, inTrailer: String, inDepth: Int) {
			val vText = StringBuilder()
			fun flush() {
				if (vText.isEmpty()) return
				val vString = vText.toString()
				line(inDepth, "${inPrefix}_text($inFirst${cString(vString)}, ${vString.encodeToByteArray().size}, ${vString.length});")
				vText.clear()
			}
			for (vPart in inParts) {
				when (vPart) {
					is KPrintText -> vText.append(vPart.text)
					is KPrintValue -> {
						flush()
						val vValue = bare(expr(vPart.value))
						when (val vType = vPart.value.type) {
							is KPrimType -> line(inDepth, "${inPrefix}_${valueSuffix(vType.prim)}($inFirst$vValue);")
							is KOptType -> {
								line(inDepth, "if (${expr(vPart.value)}.has) {")
								line(inDepth + 1, "${inPrefix}_${valueSuffix(vType.prim)}($inFirst${expr(vPart.value)}.v);")
								line(inDepth, "} else {")
								line(inDepth + 1, "${inPrefix}_text($inFirst\"null\", 4, 4);")
								line(inDepth, "}")
							}
							else -> line(inDepth, "${inPrefix}_string($inFirst$vValue);")
						}
					}
				}
			}
			vText.append(inTrailer)
			flush()
		}

		private fun print(inPrint: KPrint, inDepth: Int) {
			parts(inPrint.parts, "ktc2_print", "", if (inPrint.newline) "\n" else "", inDepth)
		}

		private fun concat(inConcat: KConcat, inDepth: Int) {
			line(inDepth, "{")
			line(inDepth + 1, "ktc2_Sb sb;")
			line(inDepth + 1, "ktc2_sb_init(&sb);")
			parts(inConcat.parts, "ktc2_sb", "&sb, ", "", inDepth + 1)
			line(inDepth + 1, "${inConcat.target.cName} = ktc2_sb_finish(&sb);")
			line(inDepth, "}")
		}

		// ============
		//  Expressions

		/** Drops one pair of redundant outer parentheses. */
		private fun bare(inText: String): String {
			if (!inText.startsWith("(") || !inText.endsWith(")")) return inText
			var vDepth = 0
			for (vIndex in inText.indices) {
				if (inText[vIndex] == '(') vDepth++ else if (inText[vIndex] == ')') vDepth--
				if (vDepth == 0 && vIndex < inText.length - 1) return inText
			}
			return inText.substring(1, inText.length - 1)
		}

		private fun arguments(inArguments: List<KExpr>): String = inArguments.joinToString(", ") { bare(expr(it)) }

		private fun field(inTarget: KExpr, inField: KField): String {
			inField.owner?.let { mState.includes += it.unit }
			return "${expr(inTarget)}${if (inTarget.type is KClassType) "->" else "."}${inField.cName}"
		}

		/** The bounds-checked element `array[index]`, usable as a value or as a slot. */
		private fun element(inArray: KExpr, inIndex: KExpr, inType: KType): String {
			need(inType)
			return "KTC2_ARRAY_AT(${bare(expr(inArray))}, ${cType(inType)}, ${bare(expr(inIndex))})"
		}

		private fun arrayTypeId(inElement: KType): String = when (inElement) {
			is KPrimType -> "KTC2_TYPE_ARRAY_${inElement.prim.name}"
			is KRefType -> "KTC2_TYPE_ARRAY_REF"
			else -> "KTC2_TYPE_ARRAY_VALUE"
		}

		private fun typeTest(inTest: KInstanceOf): String {
			val vIds = when (val vTarget = inTest.target) {
				KStringTarget -> listOf("KTC2_TYPE_STRING")
				is KClassTarget -> mConcrete.filter { it.isSubclassOf(vTarget.cls) }.map { typeId(it) }
				is KInterfaceTarget -> mConcrete.filter { it.implements(vTarget.iface) }.map { typeId(it) }
			}
			var vIndex = mState.typeTests.indexOf(vIds)
			if (vIndex < 0) {
				vIndex = mState.typeTests.size
				mState.typeTests += vIds
			}
			return "is_$vIndex(${bare(expr(inTest.operand))})"
		}

		private fun expr(inExpr: KExpr): String = when (inExpr) {
			is KConst -> constant(inExpr)
			KUnitValue -> "((void)0)"
			is KNull -> if (inExpr.type is KOptType) "((${cType(inExpr.type)}){ false })" else "NULL"
			is KStringLit -> "KTC2_STR(${mState.literals.getOrPut(inExpr.text) { "lit_${mState.literals.size}" }})"
			is KRead -> inExpr.local.cName
			is KGlobalRead -> {
				mState.includes += inExpr.global.unit
				inExpr.global.cName
			}
			is KSingletonRead -> {
				mState.includes += inExpr.cls.unit
				"(&${inExpr.cls.cName}_instance)"
			}
			is KUnary -> unary(inExpr)
			is KBinary -> binary(inExpr)
			is KRefEquals -> {
				val vOp = if (inExpr.negated) "!=" else "=="
				when {
					inExpr.right is KNull -> "(${expr(inExpr.left)} $vOp NULL)"
					inExpr.left is KNull -> "(${expr(inExpr.right)} $vOp NULL)"
					else -> "((void*)${expr(inExpr.left)} $vOp (void*)${expr(inExpr.right)})"
				}
			}
			is KConvert -> convert(inExpr)
			is KOptSome -> "((${cType(inExpr.type)}){ true, ${bare(expr(inExpr.operand))} })"
			is KOptHas -> "${expr(inExpr.operand)}.has"
			is KOptValue -> "${expr(inExpr.operand)}.v"
			is KSelect -> when {
				(inExpr.whenFalse as? KConst)?.value == false -> "(${expr(inExpr.condition)} && ${expr(inExpr.whenTrue)})"
				(inExpr.whenTrue as? KConst)?.value == true -> "(${expr(inExpr.condition)} || ${expr(inExpr.whenFalse)})"
				else -> "(${expr(inExpr.condition)} ? ${expr(inExpr.whenTrue)} : ${expr(inExpr.whenFalse)})"
			}
			is KFieldRead -> field(inExpr.target, inExpr.field)
			is KStructNew -> {
				mState.includes += inExpr.struct.unit
				val vFields = inExpr.struct.fields.zip(inExpr.values).joinToString(", ") { (vField, vValue) -> ".${vField.cName} = ${bare(expr(vValue))}" }
				"(${inExpr.struct.cName}){ ${vFields.ifEmpty { "0" }} }"
			}
			is KRefCast -> {
				need(inExpr.type)
				"((${cType(inExpr.type)})${expr(inExpr.operand)})"
			}
			is KInstanceOf -> typeTest(inExpr)
			is KIndexRead -> element(inExpr.array, inExpr.index, inExpr.type)
			is KArrayNew -> {
				need(inExpr.type.element)
				"ktc2_array_new(${bare(expr(inExpr.length))}, sizeof(${cType(inExpr.type.element)}), ${arrayTypeId(inExpr.type.element)})"
			}
			is KIntrinsic -> "${inExpr.name}(${arguments(inExpr.arguments)})"
			is KAlloc -> {
				mState.includes += inExpr.cls.unit
				"ktc2_alloc(sizeof(${inExpr.cls.cName}), ${typeId(inExpr.cls)})"
			}
			is KCall -> {
				mState.includes += inExpr.function.unit
				"${inExpr.function.cName}(${arguments(inExpr.arguments)})"
			}
			is KRetain -> "ktc2_retain(${bare(expr(inExpr.operand))})"
		}

		fun constant(inConst: KConst): String = when (val vValue = inConst.value) {
			is Number if inConst.type.prim.isUnsigned -> when (inConst.type.prim) {
				KPrim.UBYTE -> "((uint8_t)${vValue.toInt() and 0xFF})"
				KPrim.USHORT -> "((uint16_t)${vValue.toInt() and 0xFFFF})"
				KPrim.UINT -> "UINT32_C(${vValue.toInt().toUInt()})"
				else -> "UINT64_C(${vValue.toLong().toULong()})"
			}
			is Boolean -> vValue.toString()
			is Char -> "((uint16_t)${vValue.code})"
			is Byte -> "((int8_t)${vValue.toInt()})"
			is Short -> "((int16_t)${vValue.toInt()})"
			is Int -> if (vValue == Int.MIN_VALUE) "INT32_MIN" else if (vValue < 0) "($vValue)" else "$vValue"
			is Long -> if (vValue == Long.MIN_VALUE) "INT64_MIN" else "INT64_C($vValue)"
			is Float -> when {
				vValue.isNaN() -> "NAN"
				vValue.isInfinite() -> if (vValue > 0) "INFINITY" else "(-INFINITY)"
				else -> if (vValue < 0 || 1 / vValue < 0) "(${vValue}f)" else "${vValue}f"
			}
			is Double -> when {
				vValue.isNaN() -> "((double)NAN)"
				vValue.isInfinite() -> if (vValue > 0) "((double)INFINITY)" else "(-(double)INFINITY)"
				else -> if (vValue < 0 || 1 / vValue < 0) "($vValue)" else "$vValue"
			}
			else -> error("unexpected constant ${vValue::class}")
		}

		private fun intPrefix(inPrim: KPrim): String? = when (inPrim) {
			KPrim.INT -> "ktc2_i"
			KPrim.LONG -> "ktc2_l"
			KPrim.UINT -> "ktc2_u"
			KPrim.ULONG -> "ktc2_ul"
			else -> null
		}

		private fun unary(inExpr: KUnary): String {
			val vOperand = expr(inExpr.operand)
			return when (inExpr.op) {
				KUnaryOp.NOT -> "(!$vOperand)"
				KUnaryOp.INV -> "(~$vOperand)"
				KUnaryOp.NEG -> intPrefix(inExpr.type.prim)?.let { "${it}neg(${bare(vOperand)})" } ?: "(-$vOperand)"
			}
		}

		private fun binary(inExpr: KBinary): String {
			val vLeft = expr(inExpr.left)
			val vRight = expr(inExpr.right)
			val vInt = intPrefix(inExpr.operandType)
			fun helper(inName: String) = "$vInt$inName(${bare(vLeft)}, ${bare(vRight)})"
			fun infix(inOp: String) = "($vLeft $inOp $vRight)"
			return when (inExpr.op) {
				KBinaryOp.ADD -> if (vInt != null) helper("add") else infix("+")
				KBinaryOp.SUB -> if (vInt != null) helper("sub") else infix("-")
				KBinaryOp.MUL -> if (vInt != null) helper("mul") else infix("*")
				KBinaryOp.DIV -> if (vInt != null) helper("div") else infix("/")
				KBinaryOp.REM -> when {
					vInt != null -> helper("rem")
					inExpr.operandType == KPrim.FLOAT -> "fmodf(${bare(vLeft)}, ${bare(vRight)})"
					else -> "fmod(${bare(vLeft)}, ${bare(vRight)})"
				}
				KBinaryOp.SHL -> helper("shl")
				KBinaryOp.SHR -> helper("shr")
				KBinaryOp.USHR -> helper("ushr")
				KBinaryOp.AND -> infix("&")
				KBinaryOp.OR -> infix("|")
				KBinaryOp.XOR -> infix("^")
				KBinaryOp.LT -> infix("<")
				KBinaryOp.LE -> infix("<=")
				KBinaryOp.GT -> infix(">")
				KBinaryOp.GE -> infix(">=")
				KBinaryOp.EQ -> infix("==")
				KBinaryOp.NE -> infix("!=")
				KBinaryOp.CMP -> "KTC2_CMP($vLeft, $vRight)"
			}
		}

		private fun convert(inExpr: KConvert): String {
			val vOperand = expr(inExpr.operand)
			if (inExpr.from.isFloating && !inExpr.to.isFloating) {
				val vHelper = when (inExpr.to) {
					KPrim.LONG -> "ktc2_d2l"
					KPrim.ULONG -> "ktc2_d2ul"
					KPrim.UBYTE, KPrim.USHORT, KPrim.UINT -> "ktc2_d2u"
					else -> "ktc2_d2i"
				}
				val vSaturated = "$vHelper(${bare(vOperand)})"
				val vExact = inExpr.to == KPrim.INT || inExpr.to == KPrim.LONG || inExpr.to == KPrim.UINT || inExpr.to == KPrim.ULONG
				return if (vExact) vSaturated else "((${inExpr.to.cName})$vSaturated)"
			}
			return "((${inExpr.to.cName})$vOperand)"
		}
	}
}
