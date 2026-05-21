package com.bitsycore.ktc.codegen.expr

import com.bitsycore.ktc.ast.*
import com.bitsycore.ktc.codegen.*
import com.bitsycore.ktc.codegen.emit.collectAllIfaceMethods
import com.bitsycore.ktc.types.KtcType

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
			val isExt          = methodDecl?.receiver != null
			val recvArg        = if (isExt) "(*$recv)" else recv
			val savedSubst2    = typeSubst
			val classBindings2 = genericTypeBindings[pointerBase]
			if (classBindings2.isNullOrEmpty().not()) typeSubst = classBindings2
			val expandedArgs2 = if (methodDecl != null) {
				val filled2 = fillDefaults(args, methodDecl.params, effectiveDefaults(methodDecl, pointerBase), methodDecl.name, strict = true)
				expandCallArgs(filled2, methodDecl.params)
				} else argStr
			if (classBindings2.isNullOrEmpty().not()) typeSubst = savedSubst2
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
		if (genExt == null) {
			val ifaces = classInterfaces[pointerBase] ?: emptyList()
			for (iface in ifaces) {
				val m = genericFunDecls.find {
					it.name == method && it.receiver != null && (
						it.receiver.name == iface ||
						(genericIfaceDecls.containsKey(it.receiver.name) && iface.startsWith("${it.receiver.name}_"))
						)
					}
				if (m != null) {
					ifaceExt         = m
					ifaceExtConcrete = if (iface.startsWith("${m.receiver!!.name}_")) iface
					else {
						val binding = genericTypeBindings[pointerBase]
						if (binding != null) {
							val tArgs = m.typeParams.map { binding[it] ?: "Int" }
							mangledGenericName(m.receiver.name, tArgs)
							} else iface
						}
					break
					}
				}
			}
		val effectiveGenExt = genExt ?: ifaceExt
		if (ifaceExt != null && ifaceExtConcrete != null) {
			val tArgComponents = mangledComponents[ifaceExtConcrete]?.second
			val tArgs          = ifaceExt.typeParams.mapIndexed { i, _ -> tArgComponents?.getOrNull(i) ?: "Int" }
			genericFunInstantiations.getOrPut(ifaceExt.name) { mutableSetOf() }.add(tArgs)
			}
		val isPtrExt      = effectiveGenExt?.receiver?.annotations?.any { it.name == "Ptr" } == true
		val flatPtrBase   = if (ifaceExtConcrete != null) {
			val f = typeFlatName(ifaceExtConcrete)
			if (isPtrExt) "${f.removeSuffix("_$ifaceExtConcrete")}_Ptr$${ifaceExtConcrete}" else f
			} else if (isPtrExt) "${typeFlatName(pointerBase).removeSuffix("_$pointerBase")}_Ptr$${pointerBase}" else typeFlatName(pointerBase)
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
		val vFilledArgStr = if (ifaceMethod != null) {
			val vFilled = fillDefaults(args, ifaceMethod.params, ifaceMethod.params.associate { it.name to it.default }, method, strict = true)
			expandCallArgs(vFilled, ifaceMethod.params)
			} else argStr
		val allArgs = if (vFilledArgStr.isEmpty()) vSelfArg else "$vSelfArg, $vFilledArgStr"
		if (ifaceMethod?.returnType?.nullable == true) {
			val retType = resolveTypeName(ifaceMethod.returnType).toInternalStr
			val optType = optCTypeName("${retType}?")
			val t       = tmp()
			preStmts += "$optType $t = $vtAccess->$method($allArgs);"
			defineVar(t, "${retType}?")
			markOptional(t)
			return t
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
		if (genericExtDecl == null) {
			val ifaces = classInterfaces[vClassInfo.baseName] ?: emptyList()
			for (iface in ifaces) {
				val match = genericFunDecls.find {
					it.name == method && it.receiver != null && (
						it.receiver.name == iface ||
						(genericIfaceDecls.containsKey(it.receiver.name) && iface.startsWith("${it.receiver.name}_"))
						)
					}
				if (match != null) {
					genericExtDecl = match
					ifaceConcrete  = if (iface.startsWith("${match.receiver!!.name}_")) iface
					else {
						val binding = genericTypeBindings[vClassInfo.baseName]
						if (binding != null) {
							val tArgs = match.typeParams.map { binding[it] ?: "Int" }
							mangledGenericName(match.receiver.name, tArgs)
							} else iface
						}
					break
					}
				}
			}
		if (genericExtDecl != null && ifaceConcrete != null) {
			val ifaceComponents = mangledComponents[ifaceConcrete]?.second
			val tArgs           = List(genericExtDecl.typeParams.size) { i -> ifaceComponents?.getOrNull(i) ?: "Int" }
			genericFunInstantiations.getOrPut(genericExtDecl.name) { mutableSetOf() }.add(tArgs)
			}
		val effectiveDecl  = methodDecl ?: genericExtDecl
		val isExtFun       = effectiveDecl?.receiver != null
		val isPtrRecv      = effectiveDecl?.receiver?.annotations?.any { it.name == "Ptr" } == true
		val isNullableRecv = effectiveDecl?.receiver?.nullable == true
		val flatBase       = if (ifaceConcrete != null) {
			val f = typeFlatName(ifaceConcrete)
			if (isPtrRecv) "${f.removeSuffix("_$ifaceConcrete")}_Ptr$${ifaceConcrete}" else f
			} else if (isPtrRecv) "${vClassInfo.flatName.removeSuffix("_${vClassInfo.baseName}")}_Ptr$${vClassInfo.baseName}" else vClassInfo.flatName
		val nullableRecv = hasNullableReceiverExt(recvType ?: "", method)
		val selfArg = if (nullableRecv) {
			val recvName    = (dot.obj as? NameExpr)?.name
			val recvVarKtc2 = if (recvName != null) lookupVarKtc(recvName) else null
			val optSelfType = optCTypeName("${recvType}?")
			when {
				dot.obj is ThisExpr -> "\$self"
				recvVarKtc2 is KtcType.Nullable && isValueNullableKtc(recvVarKtc2)
						&& recvName != null && isOptional(recvName) -> {
					if (ifaceConcrete != null && isExtFun) {
						val optBase  = ifaceConcrete
						val t        = tmp()
						val optType2 = optCTypeName("${optBase}?")
						preStmts += "$optType2 $t = ($recv.tag == ktc_SOME) ? ($optType2){ktc_SOME, ${typeFlatName(vClassInfo.baseName)}_as_$ifaceConcrete(&$recv.value)} : ($optType2){ktc_NONE};"
						t
						} else recv
					}

				isExtFun -> {
					if (isNullableRecv && !isPtrRecv) {
						val rName2 = (dot.obj as? NameExpr)?.name
						val rKtc2  = if (rName2 != null) lookupVarKtc(rName2) else null
						if (rKtc2 is KtcType.Nullable && rName2 != null && isOptional(rName2)) {
							if (ifaceConcrete != null) {
								val optBase  = ifaceConcrete
								val t        = tmp()
								val optType2 = optCTypeName("${optBase}?")
								preStmts += "$optType2 $t = ($recv.tag == ktc_SOME) ? ($optType2){ktc_SOME, ${typeFlatName(vClassInfo.baseName)}_as_$ifaceConcrete(&$recv.value)} : ($optType2){ktc_NONE};"
								t
								} else recv
							} else {
							val valExpr = if (ifaceConcrete != null) "${typeFlatName(vClassInfo.baseName)}_as_$ifaceConcrete(&$recv)" else recv
							val optBase = ifaceConcrete ?: vClassInfo.baseName
							optSome(optCTypeName("${optBase}?"), valExpr)
							}
						} else if (ifaceConcrete != null && !isPtrRecv) optSome(optSelfType, "${typeFlatName(vClassInfo.baseName)}_as_$ifaceConcrete(&$recv)")
					else optSome(optSelfType, recv)
					}

				else -> optSome(optSelfType, "&$recv")
				}
			} else if (isExtFun) {
			if (isNullableRecv && !isPtrRecv) {
				val rName = (dot.obj as? NameExpr)?.name
				val rKtc  = if (rName != null) lookupVarKtc(rName) else null
				if (rKtc is KtcType.Nullable && rName != null && isOptional(rName)) {
					if (ifaceConcrete != null) {
						val optBase  = ifaceConcrete
						val t        = tmp()
						val optType  = optCTypeName("${optBase}?")
						preStmts += "$optType $t = ($recv.tag == ktc_SOME) ? ($optType){ktc_SOME, ${typeFlatName(vClassInfo.baseName)}_as_$ifaceConcrete(&$recv.value)} : ($optType){ktc_NONE};"
						t
						} else recv
					} else {
					val optBase = ifaceConcrete ?: vClassInfo.baseName
					val valExpr = if (ifaceConcrete != null) "${typeFlatName(vClassInfo.baseName)}_as_$ifaceConcrete(&$recv)" else recv
					optSome(optCTypeName("${optBase}?"), valExpr)
					}
				} else if (ifaceConcrete != null && !isPtrRecv) "${typeFlatName(vClassInfo.baseName)}_as_$ifaceConcrete(&$recv)"
			else recv
			} else "&$recv"

		val savedSubst    = typeSubst
		val classBindings = genericTypeBindings[vClassInfo.name]
		if (classBindings != null && classBindings.isNotEmpty()) typeSubst = classBindings
		val expandedArgs = if (methodDecl != null) {
			val filled = fillDefaults(args, methodDecl.params, effectiveDefaults(methodDecl, vClassInfo.baseName), methodDecl.name, strict = true)
			expandCallArgs(filled, methodDecl.params)
			} else argStr
		if (classBindings != null && classBindings.isNotEmpty()) typeSubst = savedSubst
		val allArgs       = if (expandedArgs.isEmpty()) selfArg else "$selfArg, $expandedArgs"
		val overloadedName = methodDecl?.let { methodName(it, vClassInfo.methods) } ?: method
		val fnPrefix       = if (methodDecl?.isPrivate == true) "PRIV_$overloadedName" else overloadedName
		if (methodDecl?.returnType != null && methodDecl.returnType.isSizedArray()) {
			val vRetKtcSz  = resolveTypeName(methodDecl.returnType)
			val retType    = vRetKtcSz.toInternalStr
			val elemCType  = arrayElementCTypeKtc(vRetKtcSz)
			val size       = methodDecl.returnType.getSizeAnnotation()!!
			val structType = sizedArrayCTypeRef(elemCType, size)
			val tStruct = tmp(); val vT = tmp()
			preStmts += "$structType $tStruct = ${vClassInfo.flatName}_$fnPrefix($allArgs);"
			val vVarArrType = varArrTypeName(elemCType)
			preStmts += "$vVarArrType $vT = {$tStruct.arr, $size};"
			defineVar(vT, retType)
			return vT
			}
		if (methodDecl?.returnType != null && methodDecl.returnType.isSizedString()) {
			val size       = methodDecl.returnType.getSizeAnnotation()!!
			val structType = sizedStringCTypeRef(size)
			val tStruct = tmp(); val tStr = tmp()
			preStmts += "$structType $tStruct = ${vClassInfo.flatName}_$fnPrefix($allArgs);"
			preStmts += "ktc_String $tStr = {$tStruct.buf, $tStruct.len};"
			defineVar(tStr, "String")
			return tStr
			}
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
		val vObjArgs = if (vObjMethod != null) {
			val filled = fillDefaults(args, vObjMethod.params, effectiveDefaults(vObjMethod, vDotObjInfo.name), vObjMethod.name, strict = true)
			expandCallArgs(filled, vObjMethod.params)
			} else {
			/* Method not found in object — try extension functions for proper arg expansion. */
			val vExtForObj = extensionFuns[vDotObjInfo.name]?.find { it.name == method }
			if (vExtForObj != null) {
				val vFilled = fillDefaults(args, vExtForObj.params, effectiveDefaults(vExtForObj, vDotObjInfo.name), vExtForObj.name, strict = true)
				expandCallArgs(vFilled, vExtForObj.params)
				} else argStr
			}
		if (vObjMethod?.returnType != null && vObjMethod.returnType.isSizedArray()) {
			val vRetKtcObj = resolveTypeName(vObjMethod.returnType)
			val retType    = vRetKtcObj.toInternalStr
			val elemCType  = arrayElementCTypeKtc(vRetKtcObj)
			val size       = vObjMethod.returnType.getSizeAnnotation()!!
			val structType = sizedArrayCTypeRef(elemCType, size)
			val tStruct = tmp(); val vT = tmp()
			preStmts += "$structType $tStruct = ${vDotObjCName}_$overloadedMethod($vObjArgs);"
			val vVarArrType = varArrTypeName(elemCType)
			preStmts += "$vVarArrType $vT = {$tStruct.arr, $size};"
			defineVar(vT, retType)
			return vT
			}
		if (vObjMethod?.returnType != null && vObjMethod.returnType.isSizedString()) {
			val size       = vObjMethod.returnType.getSizeAnnotation()!!
			val structType = sizedStringCTypeRef(size)
			val tStruct = tmp(); val tStr = tmp()
			preStmts += "$structType $tStruct = ${vDotObjCName}_$overloadedMethod($vObjArgs);"
			preStmts += "ktc_String $tStr = {$tStruct.buf, $tStruct.len};"
			defineVar(tStr, "String")
			return tStr
			}
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
	val optType = optCTypeName("${retBase}?")
	val t       = tmp()
	preStmts += "$optType $t = $fnExpr($allArgs);"
	defineVar(t, "${retBase}?")
	markOptional(t)
	return t
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
