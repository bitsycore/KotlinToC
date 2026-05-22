package com.bitsycore.ktc.codegen.expr

import com.bitsycore.ktc.ast.*
import com.bitsycore.ktc.codegen.*
import com.bitsycore.ktc.codegen.emit.collectAllIfaceMethods
import com.bitsycore.ktc.types.KtcType

/* Record a generic extension function instantiation derived from a concrete interface name.
Resolves type args from mangledComponents and inserts them into genericFunInstantiations. */
private fun CCodeGen.recordIfaceExtInstantiation(decl: FunDecl, concrete: String) {
	val components = mangledComponents[concrete]?.second
	val tArgs = List(decl.typeParams.size) { i -> components?.getOrNull(i) ?: "Int" }
	genericFunInstantiations.getOrPut(decl.name) { mutableSetOf() }.add(tArgs)
	}

/* Search the interfaces implemented by [className] for a generic extension function named [method].
Returns (FunDecl, concreteIfaceName) if found, null otherwise.
The concrete interface name is resolved via genericTypeBindings when needed. */
private fun CCodeGen.findGenericExtOnIfaces(className: String, method: String): Pair<FunDecl, String>? {
	val ifaces = classInterfaces[className] ?: return null
	for (iface in ifaces) {
		val decl = genericFunDecls.find {
			it.name == method && it.receiver != null && (
				it.receiver.name == iface ||
				(genericIfaceDecls.containsKey(it.receiver.name) && iface.startsWith("${it.receiver.name}_"))
				)
			} ?: continue
		val concrete = if (iface.startsWith("${decl.receiver!!.name}_")) iface
		else {
			val binding = genericTypeBindings[className]
			if (binding != null) {
				val tArgs = decl.typeParams.map { binding[it] ?: "Int" }
				mangledGenericName(decl.receiver.name, tArgs)
				} else iface
			}
		return Pair(decl, concrete)
		}
	return null
	}

/* Emit an optional iface cast preStmt and return the temp var name.
When [ifaceConcrete] is null, returns [recv] unchanged.
Emits: optType t = (recv.tag == ktc_SOME) ? {SOME, baseName_as_iface(&recv.value)} : {NONE}; */
private fun CCodeGen.nullableIfaceCast(recv: String, baseName: String, ifaceConcrete: String?): String {
	if (ifaceConcrete == null) return recv
	val optType = optCTypeName("${ifaceConcrete}?")
	val t = tmp()
	preStmts += "$optType $t = ($recv.tag == ktc_SOME) ? ($optType){ktc_SOME, ${typeFlatName(baseName)}_as_$ifaceConcrete(&$recv.value)} : ($optType){ktc_NONE};"
	return t
	}

/* Compute the flat C name prefix for an extension function dispatch.
When [concrete] is set (iface extension), the prefix is based on the concrete iface name.
When [isPtrReceiver] is true, inserts _Ptr$ into the mangled name. */
private fun CCodeGen.resolveExtFlatBase(base: String, flat: String, isPtrReceiver: Boolean, concrete: String?): String {
	if (concrete != null) {
		val f = typeFlatName(concrete)
		return if (isPtrReceiver) "${f.removeSuffix("_$concrete")}_Ptr$$concrete" else f
		}
	return if (isPtrReceiver) "${flat.removeSuffix("_$base")}_Ptr$$base" else flat
	}

/* Compute the self-arg expression for an extension with a nullable receiver type declaration (isNullableRecv).
When the receiver is already a stored Optional var: perform nullable iface cast if needed.
Otherwise: wrap the value in optSome with optional iface conversion. */
private fun CCodeGen.genExtSelfArgForNullableRecv(recv: String, baseName: String, ifaceConcrete: String?, dotObj: Expr): String {
	val rName = (dotObj as? NameExpr)?.name
	val rKtc  = if (rName != null) lookupVarKtc(rName) else null
	if (rKtc is KtcType.Nullable && rName != null && isOptional(rName))
		return nullableIfaceCast(recv, baseName, ifaceConcrete)
	val optBase = ifaceConcrete ?: baseName
	val valExpr = if (ifaceConcrete != null) "${typeFlatName(baseName)}_as_$ifaceConcrete(&$recv)" else recv
	return optSome(optCTypeName("${optBase}?"), valExpr)
	}

/* Dispatch a method call on a dot receiver, handling built-ins, arrays, pointers,
interfaces, class methods, objects, enums and extension functions. */
internal fun CCodeGen.genMethodCall(dot: DotExpr, args: List<Arg>): String {
	val rawRecvType    = inferExprType(dot.obj)                                            // String? receiver type (string-based)
	val recvType       = rawRecvType?.removeSuffix("?")                                    // non-nullable receiver type string
	val rawRecvTypeKtc = inferExprTypeKtc(dot.obj)                                        // KtcType? receiver (may have Nullable wrapper)
	val recvTypeKtc    = (rawRecvTypeKtc as? KtcType.Nullable)?.inner ?: rawRecvTypeKtc   // KtcType? stripped of Nullable wrapper
	val rawRecv        = genExpr(dot.obj)
	val method         = dot.name
	val hasNullRecv    = hasNullableReceiverExt(recvType ?: "", method)
	val isValueNull    = rawRecvTypeKtc is KtcType.Nullable && isValueNullableKtc(rawRecvTypeKtc) && !hasNullRecv
	val recv           = if (isValueNull) "$rawRecv.value" else rawRecv
	val argStr         = args.joinToString(", ") { genExpr(it.expr) }

	genBuiltinMethodCallOrNull(dot, args, recv, recvType, recvTypeKtc)?.let { return it }

	// ── @Ptr / @Heap / @Value class pointer methods ───────────────────

	val pointerBase = (recvTypeKtc as? KtcType.Ptr)?.inner?.let { it as? KtcType.User }?.baseName
	val isIface     = pointerBase != null && interfaces.containsKey(pointerBase)
	if (pointerBase != null && !isIface) {
		val classHasMethod = classes[pointerBase]?.methods?.any { it.name == method } == true
		if (classHasMethod) {
			val methodDecl     = classes[pointerBase]?.let { findOverload(method, args, it.methods) }
			val isExt         = methodDecl?.receiver != null
			val recvArg       = if (isExt) "(*$recv)" else recv
			val expandedArgs2 = withTypeSubst(genericTypeBindings[pointerBase]) {
				if (methodDecl != null) prepareArgs(args, methodDecl, pointerBase) else argStr
				}
			val allArgs = if (expandedArgs2.isEmpty()) recvArg else "$recvArg, $expandedArgs2"
			if (methodDecl?.returnType?.nullable == true) {
				return genNullableMethodCall(pointerBase, "${typeFlatName(pointerBase)}_$method", allArgs, methodDecl)
				}
			return "${typeFlatName(pointerBase)}_$method($allArgs)"
			}
		when (method) {
			"value" -> {
				if (objects.containsKey(pointerBase)) codegenError("Cannot call .value() on object '${pointerBase}' — objects are always @Ptr")
				return "(*$recv)"
				}
			"set"  -> return "(*$recv = $argStr)"
			"copy" -> if (classes[pointerBase]?.isData == true) return genDataClassCopy(recv, pointerBase, args, heap = true)
			"ptr" -> return recv
			}
		// Check generic extension functions and interfaces for @Ptr receiver
		val genExt = genericFunDecls.find {
			it.name == method && it.receiver != null && (
				it.receiver.name == pointerBase ||
				(genericClassDecls.containsKey(it.receiver.name) && pointerBase.startsWith("${it.receiver.name}_"))
				)
			}
		var ifaceExt: FunDecl? = null
		var ifaceExtConcrete: String? = null
		if (genExt == null) findGenericExtOnIfaces(pointerBase, method)?.also { (d, c) -> ifaceExt = d; ifaceExtConcrete = c }
		val effectiveGenExt = genExt ?: ifaceExt
		if (ifaceExt != null && ifaceExtConcrete != null) recordIfaceExtInstantiation(ifaceExt, ifaceExtConcrete)
		val isPtrExt      = effectiveGenExt?.receiver?.annotations?.any { it.name == "Ptr" } == true
		val flatPtrBase   = resolveExtFlatBase(pointerBase, typeFlatName(pointerBase), isPtrExt, ifaceExtConcrete)
		val wrappedRecv = if (ifaceExtConcrete != null && isPtrExt) {
			val cConcrete  = typeFlatName(pointerBase)
			val typeId     = getTypeId(pointerBase)
			val t          = tmp()
			val isNullable = rawRecvTypeKtc is KtcType.Nullable
			if (isNullable) {
				preStmts += "ktc_IfacePtr $t = ($recv) ? ((ktc_IfacePtr){{$typeId}, (const void*)&${cConcrete}_${ifaceExtConcrete}_vt, (void*)($recv)}) : ((ktc_IfacePtr){0});"
				} else {
				preStmts += "ktc_IfacePtr $t = {{$typeId}, (const void*)&${cConcrete}_${ifaceExtConcrete}_vt, (void*)$recv};"
				}
			t
			} else recv
		val allArgs = if (argStr.isEmpty()) wrappedRecv else "$wrappedRecv, $argStr"
		return "${flatPtrBase}_$method($allArgs)"
		}

	// ── Interface method dispatch → vtable call ───────────────────────

	val vIfaceInfo = ifaceInfoFor(recvTypeKtc)
	if (vIfaceInfo != null) {
		val cIface          = typeFlatName(vIfaceInfo.name)
		val extFunOnIface   = extensionFuns[vIfaceInfo.baseName]?.find { it.name == method }
			?: extensionFuns[vIfaceInfo.flatName]?.find { it.name == method }
		if (extFunOnIface != null) {
			val allArgs = if (argStr.isEmpty()) recv else "$recv, $argStr"
			return "${vIfaceInfo.flatName}_$method($allArgs)"
			}
		val isIfacePtr  = rawRecvTypeKtc is KtcType.Ptr
		val vSelfArg    = if (isIfacePtr) "$recv.obj" else ifaceVtableSelf(vIfaceInfo.name, recv)
		val vtAccess    = if (isIfacePtr) "((${cIface}_vt*)$recv.vt)" else "$recv.vt"
		val ifaceMethod = vIfaceInfo.methods.find { it.name == method }
			?: collectAllIfaceMethods(vIfaceInfo).find { it.name == method }
		val vFilledArgStr = if (ifaceMethod != null) prepareArgs(args, ifaceMethod) else argStr
		val allArgs = if (vFilledArgStr.isEmpty()) vSelfArg else "$vSelfArg, $vFilledArgStr"
		if (ifaceMethod?.returnType?.nullable == true) {
			val retType = resolveTypeName(ifaceMethod.returnType).toInternalStr
			return tmpOptional(retType, "$vtAccess->$method($allArgs)")
			}
		return "$vtAccess->$method($allArgs)"
		}

	// .ptr() on nullable value type → produce @Ptr T? (nullable pointer)
	if (method == "ptr" && rawRecvTypeKtc is KtcType.Nullable && isValueNullableKtc(rawRecvTypeKtc)) {
		val innerKtc = rawRecvTypeKtc.inner
		val ct       = cTypeStr(innerKtc.toInternalStr)
		val t        = tmp()
		preStmts += "$ct* $t = (${rawRecv}.tag == ktc_SOME) ? &${rawRecv}.value : NULL;"
		return t
		}

	// ── Class method or extension function (stack value) ─────────────

	val vClassInfo = classInfoFor(recvTypeKtc)
	if (vClassInfo != null) {
		if (method == "copy" && vClassInfo.isData) return genDataClassCopy(recv, vClassInfo.baseName, args, heap = false)
		if (method == "ptr") {
			val t = tmp()
			preStmts += "${vClassInfo.flatName}* $t = &$recv;"
			return t
			}

		val methodDecl = findOverload(method, args, vClassInfo.methods)
		var genericExtDecl: FunDecl? = if (methodDecl == null) genericFunDecls.find {
			it.name == method && it.receiver != null && (
				it.receiver.name == vClassInfo.baseName ||
				(genericClassDecls.containsKey(it.receiver.name) && vClassInfo.baseName.startsWith("${it.receiver.name}_"))
				)
			} else null
		var ifaceConcrete: String? = null
		if (genericExtDecl == null) findGenericExtOnIfaces(vClassInfo.baseName, method)?.also { (d, c) -> genericExtDecl = d; ifaceConcrete = c }
		if (genericExtDecl != null && ifaceConcrete != null) recordIfaceExtInstantiation(genericExtDecl, ifaceConcrete)
		val effectiveDecl  = methodDecl ?: genericExtDecl
		val isExtFun       = effectiveDecl?.receiver != null
		val isPtrRecv      = effectiveDecl?.receiver?.annotations?.any { it.name == "Ptr" } == true
		val isNullableRecv = effectiveDecl?.receiver?.nullable == true
		val flatBase       = resolveExtFlatBase(vClassInfo.baseName, vClassInfo.flatName, isPtrRecv, ifaceConcrete)
		val nullableRecv = hasNullableReceiverExt(recvType ?: "", method)
		val selfArg = if (nullableRecv) {
			val recvName    = (dot.obj as? NameExpr)?.name
			val recvVarKtc2 = if (recvName != null) lookupVarKtc(recvName) else null
			val optSelfType = optCTypeName("${recvType}?")
			when {
				dot.obj is ThisExpr -> "\$self"
				recvVarKtc2 is KtcType.Nullable && isValueNullableKtc(recvVarKtc2)
						&& recvName != null && isOptional(recvName) -> {
					if (isExtFun) nullableIfaceCast(recv, vClassInfo.baseName, ifaceConcrete)
					else recv
					}

				isExtFun -> {
					if (isNullableRecv && !isPtrRecv) genExtSelfArgForNullableRecv(recv, vClassInfo.baseName, ifaceConcrete, dot.obj)
					else if (ifaceConcrete != null && !isPtrRecv) optSome(optSelfType, "${typeFlatName(vClassInfo.baseName)}_as_$ifaceConcrete(&$recv)")
					else optSome(optSelfType, recv)
					}

				else -> optSome(optSelfType, "&$recv")
				}
			} else if (isExtFun) {
			if (isNullableRecv && !isPtrRecv) genExtSelfArgForNullableRecv(recv, vClassInfo.baseName, ifaceConcrete, dot.obj)
			else if (ifaceConcrete != null && !isPtrRecv) "${typeFlatName(vClassInfo.baseName)}_as_$ifaceConcrete(&$recv)"
			else recv
			} else "&$recv"

		val expandedArgs = withTypeSubst(genericTypeBindings[vClassInfo.name]) {
			if (methodDecl != null) prepareArgs(args, methodDecl, vClassInfo.baseName) else argStr
			}
		val allArgs       = if (expandedArgs.isEmpty()) selfArg else "$selfArg, $expandedArgs"
		val fnPrefix       = methodDecl?.let { resolvedFnName(it, vClassInfo.methods) } ?: method
		val sizedClass = tryWrapSizedReturn("${vClassInfo.flatName}_$fnPrefix($allArgs)", methodDecl?.returnType)
		if (sizedClass != null) return sizedClass
		if (methodDecl?.returnType?.nullable == true) {
			return genNullableMethodCall(vClassInfo.baseName, "${flatBase}_$fnPrefix", allArgs, methodDecl)
			}
		return "${flatBase}_$fnPrefix($allArgs)"
		}

	// ── Object / Companion method ─────────────────────────────────────

	val vDotObjInfo  = resolveDotObjInfo(dot)
	val vDotObjCName = resolveDotObjCName(dot)
	if (vDotObjInfo != null && vDotObjCName != null) {
		val vObjMethod       = findOverload(method, args, vDotObjInfo.methods)
		val overloadedMethod = vObjMethod?.let { methodName(it, vDotObjInfo.methods) } ?: method
		val vObjArgs = when {
			vObjMethod != null -> prepareArgs(args, vObjMethod, vDotObjInfo.name)
			else               -> {
				/* Method not found in object — try extension functions for proper arg expansion. */
				val vExtForObj = extensionFuns[vDotObjInfo.name]?.find { it.name == method }
				if (vExtForObj != null) prepareArgs(args, vExtForObj, vDotObjInfo.name) else argStr
				}
			}
		val sizedObj = tryWrapSizedReturn("${vDotObjCName}_$overloadedMethod($vObjArgs)", vObjMethod?.returnType)
		if (sizedObj != null) return sizedObj
		return "${vDotObjCName}_$overloadedMethod($vObjArgs)"
		}

	// ── Enum method ───────────────────────────────────────────────────

	val vEnumInfo = enumInfoFor(recvTypeKtc)
	if (vEnumInfo != null) {
		when (method) {
			"values" -> {
				enumValuesCalled.add(vEnumInfo.baseName)
				return "{${vEnumInfo.flatName}_values, ${vEnumInfo.flatName}_values\$len}"
				}

			"valueOf" -> {
				val vValOfArgStr = args.joinToString(", ") { genExpr(it.expr) }
				enumValuesCalled.add(vEnumInfo.baseName)
				enumValueOfCalled.add(vEnumInfo.baseName)
				return "${vEnumInfo.flatName}_valueOf($vValOfArgStr)"
				}

			else -> return "${vEnumInfo.flatName}_$method"
			}
		}

	// ── Extension function on non-class type ─────────────────────────

	if (recvType != null) {
		var extFun      = extensionFuns[recvType]?.find { it.name == method }
		var extFunOwner: String = recvType
		if (extFun == null && (classes.containsKey(recvType) || objects.containsKey(recvType))) {
			val ifaces = classInterfaces[recvType] ?: emptyList()
			for (ifaceName in ifaces) {
				extFun = extensionFuns[ifaceName]?.find { it.name == method }
				if (extFun != null) { extFunOwner = ifaceName; break }
				}
			}
		if (extFun != null) {
			val nullableRecv = extFun.receiver?.nullable == true
			val recvArg = if (nullableRecv) {
				val recvName    = (dot.obj as? NameExpr)?.name
				val recvVarKtc  = if (recvName != null) lookupVarKtc(recvName) else null
				val optSelfType = optCTypeName("${recvType}?")
				when {
					dot.obj is ThisExpr -> "\$self"
					recvVarKtc != null && recvVarKtc is KtcType.Nullable && isValueNullableKtc(recvVarKtc)
							&& recvName != null && isOptional(recvName) -> recv

					else -> optSome(optSelfType, recv)
					}
				} else recv
			val allArgs = if (argStr.isEmpty()) recvArg else "$recvArg, $argStr"
			return "${typeFlatName(extFunOwner)}_$method($allArgs)"
			}
		if (method == "dispose" && (classes.containsKey(recvType) || enums.containsKey(recvType) || objects.containsKey(recvType))) {
			val selfExpr = if (recvTypeKtc is KtcType.Ptr) recv else "&$recv"
			val base     = (recvTypeKtc as? KtcType.Ptr)?.inner?.let { it as? KtcType.User }?.baseName ?: recvType
			return "${typeFlatName(base)}_dispose($selfExpr)"
			}
		if (classes.containsKey(recvType) && starExtFunDecls.any { it.name == method }) {
			val selfExpr = if (recvTypeKtc is KtcType.Ptr) recv else "&$recv"
			val base     = (recvTypeKtc as? KtcType.Ptr)?.inner?.let { it as? KtcType.User }?.baseName ?: recvType
			val allArgs  = if (argStr.isEmpty()) selfExpr else "$selfExpr, $argStr"
			return "${typeFlatName(base)}_$method($allArgs)"
			}
		}

	// .ptr() for value types (primitives, enums, etc.) — take address
	if (method == "ptr" && recvType != null) {
		val cType = cTypeStr(recvType)
		val t     = tmp()
		preStmts += "$cType* $t = &$recv;"
		return t
		}

	return "$recv.$method($argStr)"   // fallback
	}

/* Generate a method call that returns nullable via out-pointer. */
internal fun CCodeGen.genNullableMethodCall(
	className: String,
	fnExpr: String,
	allArgs: String,
	methodDecl: FunDecl
	): String {
	val retBase = resolveMethodReturnType(className, methodDecl.returnType).removeSuffix("?")
	return tmpOptional(retBase, "$fnExpr($allArgs)")
	}

/* Generate data class copy. heap = true when receiver is a heap pointer. */
internal fun CCodeGen.genDataClassCopy(
	recv: String,
	className: String,
	args: List<Arg>,
	heap: Boolean
	): String {
	val cName = typeFlatName(className)
	val src   = if (heap) "(*$recv)" else recv
	if (args.isEmpty()) return src   // simple struct value copy

	// copy(field = val, ...) — hoist to temp, override named fields
	val t     = tmp()
	preStmts += "$cName $t = $src;"
	val ci    = classes[className]
	val props = ci?.props?.associate { it.first to it.second } ?: emptyMap()
	for (arg in args) {
		val fieldName = arg.name ?: continue
		val fieldType = props[fieldName]
		val value     = genExpr(arg.expr)
		if (fieldType != null && fieldType.nullable) {
			val baseType = resolveTypeName(fieldType).toInternalStr.removeSuffix("?")
			val optType  = optCTypeName("${baseType}?")
			val optExpr  = if (arg.expr is NullLit) optNone(optType) else optSome(optType, value)
			preStmts += "$t.$fieldName = $optExpr;"
			} else {
			preStmts += "$t.$fieldName = $value;"
			}
		}
	return t
	}
