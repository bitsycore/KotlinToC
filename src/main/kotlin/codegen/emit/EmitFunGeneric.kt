package com.bitsycore.ktc.codegen.emit

import com.bitsycore.ktc.ast.*
import com.bitsycore.ktc.codegen.*
import com.bitsycore.ktc.codegen.expr.emitStmt
import com.bitsycore.ktc.types.KtcType

// Generic function monomorphization and star-projection extension function emission.

/*
Emit monomorphized versions of a generic free function.
For `fun <T> sizeOfList(list: MutableList<T>)` called with MutableList<Int>,
emits `sizeOfList_Int(MutableList_Int* list)`.
*/
internal fun CCodeGen.emitGenericFunInstantiations(f: FunDecl) {
	if (f.isInline || f.isInfix) return  // inline/infix: expanded at call sites only
	val instantiations  = genericFunInstantiations[f.name] ?: return
	val prevSourceFile  = currentSourceFile
	declSourceFile[f.name]?.let { currentSourceFile = it }
	for (typeArgs in instantiations) {
		val subst    = f.typeParams.zip(typeArgs).toMap()
		val prevSubst = typeSubst
		typeSubst = subst
		val mangledName = "${f.name}_${typeArgs.joinToString("_")}"

		impl.appendLine("// ══ generic ${f.name}<${typeArgs.joinToString(", ")}> ($currentSourceFile) ══")
		val hasReceiver        = f.receiver != null
		val returnsSizedArray  = f.returnType != null && f.returnType.isSizedArray()
		val returnsSizedString = f.returnType != null && f.returnType.isSizedString()
		val vRetKtc    = if (f.returnType != null) resolveTypeName(f.returnType) else null // KtcType of return
		val returnsArray = !returnsSizedArray && (vRetKtc?.isArrayLike ?: false)           // non-sized array return
		val concreteRet  = genericFunConcreteReturn[mangledName]
		val cRet = when {
			returnsSizedArray  -> sizedArrayCTypeName(cTypeStr(vRetKtc!!.asArr!!.elem), f.returnType.getSizeAnnotation()!!)
			returnsSizedString -> sizedStringCTypeName(f.returnType.getSizeAnnotation()!!)
			returnsArray       -> {
				val vArrElem = vRetKtc!!.asArr?.elem ?: ((vRetKtc as? KtcType.Ptr)?.inner as KtcType.Arr).elem
				varArrTypeName(cTypeStr(vArrElem))
				}
			concreteRet != null -> typeFlatName(concreteRet)
			f.returnType != null -> cType(f.returnType)
			else -> "void"
			}
		val cName = if (hasReceiver) {
			val recvKtc  = resolveTypeName(f.receiver!!)
			val recvName = (recvKtc as? KtcType.Ptr)?.inner?.let { (it as? KtcType.User)?.baseName }
				?: recvKtc.toInternalStr.removeSuffix("*").removeSuffix("?")
			if (f.receiver.annotations.any { it.name == "Ptr" }) {
				val baseFlat = typeFlatName(recvName)
				"${baseFlat.removeSuffix("_$recvName")}_Ptr$${recvName}_${f.name}"
				} else "${typeFlatName(recvName)}_${f.name}"
			} else funCName(mangledName)
		val baseParams = expandParams(f.params)
		val selfParam  = if (hasReceiver) {
			val selfRecvKtc = resolveTypeName(f.receiver!!)
			val ct = if (f.receiver.nullable && selfRecvKtc !is KtcType.Ptr && selfRecvKtc !is KtcType.Nullable)
				optCTypeName(selfRecvKtc.toInternalStr) else cType(f.receiver)
			"$ct \$self"
			} else null
		val params = if (selfParam != null && baseParams.isNotEmpty()) "$selfParam, $baseParams" else selfParam ?: baseParams

		maybeEmitFunBanner(f.name)
		hdr.appendLine("$cRet $cName($params);")
		impl.appendLine("$cRet $cName($params) {")

		val prevState = saveFunState()
		currentFnReturnsArray       = returnsArray
		currentFnReturnsSizedArray  = returnsSizedArray
		if (returnsSizedArray) {
			currentFnSizedArraySize     = f.returnType.getSizeAnnotation()!!
			currentFnSizedArrayElemType = cTypeStr(vRetKtc!!.asArr!!.elem)
			}
		currentFnReturnsSizedString = returnsSizedString
		if (returnsSizedString) currentFnSizedStringSize = f.returnType.getSizeAnnotation()!!
		currentFnReturnType = concreteRet ?: if (f.returnType != null) {
			currentFnReturnKtcType = if (f.returnType.nullable) KtcType.Nullable(vRetKtc!!) else vRetKtc!!
			if (f.returnType.nullable) KtcType.Nullable(vRetKtc!!).toInternalStr else vRetKtc!!.toInternalStr
			} else ""

		pushScope()
		if (hasReceiver) {
			val recvResolved = resolveTypeName(f.receiver!!)
			val recvFull     = recvResolved.toInternalStr
			val recvName     = recvFull.removeSuffix("?")
			val isClassType  = classes.containsKey(recvName)
			currentExtRecvType = if (f.receiver.nullable) "${recvName}?" else recvName
			defineVar("\$self", if (f.receiver.nullable) "${recvName}?" else recvName)
			if (f.receiver.nullable && isValueNullableKtc(recvResolved as? KtcType.Nullable ?: KtcType.Nullable(recvResolved))) markOptional("\$self")
			if (isClassType) { currentClass = recvName; selfIsPointer = f.receiver.annotations.any { it.name == "Ptr" } }
			else             { currentClass = null;     selfIsPointer = false }
			}
		for (p in f.params) {
			val vKtcP = resolveTypeName(p.type)
			val vPStr = vKtcP.toInternalStr
			defineVar(p.name, when {
				p.isVararg      -> "${vPStr}Array"
				p.type.nullable -> "${vPStr}?"
				else            -> vPStr
				})
			if (p.type.nullable && isValueNullableKtc(KtcType.Nullable(vKtcP))) markOptional(p.name)
			}
		val savedTrampolined      = trampolinedParams.toHashSet();      trampolinedParams.clear()
		val savedSizedTrampolined = sizedArrayTrampolinedParams.toHashSet(); sizedArrayTrampolinedParams.clear()
		emitArrayParamCopies(f.params, "    ")
		val savedDefers = deferStack.toList(); deferStack.clear()
		if (f.body != null) for (s in f.body.stmts) emitStmt(s, "    ", insideMethod = false)
		if (f.body?.stmts?.lastOrNull() !is ReturnStmt) emitDeferredBlocks("    ", insideMethod = false)
		deferStack.clear();                deferStack.addAll(savedDefers)
		trampolinedParams.clear();          trampolinedParams.addAll(savedTrampolined)
		sizedArrayTrampolinedParams.clear(); sizedArrayTrampolinedParams.addAll(savedSizedTrampolined)
		popScope()
		restoreFunState(prevState)
		impl.appendLine("}")
		impl.appendLine()
		typeSubst = prevSubst
		}
	currentSourceFile = prevSourceFile
	}

/*
Emit star-projection extension functions — one per known generic instantiation.
For `fun MutableList<*>.sizeOf()`, if MutableList<Int> is known, emits
`MutableList_Int_sizeOf(MutableList_Int* $self)`.
*/
internal fun CCodeGen.emitStarExtFunInstantiations(f: FunDecl) {
	val recvBaseName   = f.receiver!!.name
	val instantiations = genericInstantiations[recvBaseName]
	if (instantiations == null && genericIfaceDecls.containsKey(recvBaseName)) {
		emitStarExtFunForGenericInterface(f, recvBaseName)
		return
		}
	if (instantiations == null) return
	val emitted = mutableSetOf<String>()
	for (typeArgs in instantiations) {
		val mangledRecvName  = mangledGenericName(recvBaseName, typeArgs)
		val key = "${mangledRecvName}_${f.name}"
		if (!emitted.add(key)) continue
		val concreteReceiver = TypeRef(mangledRecvName, f.receiver.nullable)
		val templateCi       = classes[recvBaseName] ?: continue
		val subst            = templateCi.typeParams.zip(typeArgs).toMap()
		val prevSubst        = typeSubst
		typeSubst = subst

		val recvIsNullable = concreteReceiver.nullable
		val cRet        = if (f.returnType != null) cType(f.returnType) else "void"
		val isClassType = classes.containsKey(mangledRecvName)
		val cRecvType   = typeFlatName(mangledRecvName)
		val selfParam   = if (isClassType) "$cRecvType* \$self" else "$cRecvType \$self"
		val nullableExtra = if (recvIsNullable) ", ktc_Bool \$self\$has" else ""
		val extraParams   = expandParams(f.params)
		val allParams     = if (extraParams.isEmpty()) "$selfParam$nullableExtra" else "$selfParam$nullableExtra, $extraParams"
		val cFnName       = "${typeFlatName(mangledRecvName)}_${f.name}"

		hdr.appendLine("$cRet $cFnName($allParams);")
		impl.appendLine("$cRet $cFnName($allParams) {")

		val prevClass        = currentClass
		val prevSelfIsPtr    = selfIsPointer
		val prevExtRecvType  = currentExtRecvType
		currentExtRecvType = if (recvIsNullable) "$mangledRecvName?" else mangledRecvName
		if (isClassType) { currentClass = mangledRecvName; selfIsPointer = true }
		else             { currentClass = null;            selfIsPointer = false }

		pushScope()
		for (p in f.params) {
			val vKtcP = resolveTypeName(p.type)
			val vPStr = vKtcP.toInternalStr
			defineVar(p.name, when {
				p.type.nullable              -> "${vPStr}?"
				classes.containsKey(vPStr)   -> "${vPStr}*"
				else                         -> vPStr
				})
			}
		if (isClassType) {
			val ci = classes[mangledRecvName]!!
			for ((name, type) in ci.props) defineVarKtc(name, resolveTypeName(type))
			}
		val savedTrampolined = trampolinedParams.toHashSet(); trampolinedParams.clear()
		emitArrayParamCopies(f.params, "    ")
		val savedDefers = deferStack.toList(); deferStack.clear()
		if (f.body != null) for (s in f.body.stmts) emitStmt(s, "    ", insideMethod = isClassType)
		if (f.body?.stmts?.lastOrNull() !is ReturnStmt) emitDeferredBlocks("    ", insideMethod = isClassType)
		deferStack.clear(); deferStack.addAll(savedDefers)
		trampolinedParams.clear(); trampolinedParams.addAll(savedTrampolined)
		popScope()

		currentClass       = prevClass
		selfIsPointer      = prevSelfIsPtr
		currentExtRecvType = prevExtRecvType
		impl.appendLine("}")
		impl.appendLine()

		extensionFuns.getOrPut(mangledRecvName) { mutableListOf() }
			.add(FunDecl(f.name, f.params, f.returnType, f.body, concreteReceiver))
		classes[mangledRecvName]?.methods?.add(
			FunDecl(f.name, f.params, f.returnType, f.body, concreteReceiver))
		typeSubst = prevSubst
		}
	}

/*
Expand a star-projection extension on a generic interface into concrete implementations.
For ArrayList_Int (implements List_Int) → ArrayList_Int_sizeOf, etc.
*/
internal fun CCodeGen.emitStarExtFunForGenericInterface(f: FunDecl, ifaceBaseName: String) {
	val emitted = mutableSetOf<String>()
	for ((className, ifaceList) in classInterfaces) {
		val matchingIface = ifaceList.find { it.startsWith("${ifaceBaseName}_") || it.startsWith("${ifaceBaseName}\$") }
			?: continue
		val ci  = classes[className] ?: continue
		val key = "${className}_${f.name}"
		if (!emitted.add(key)) continue

		val prevSubst = typeSubst
		typeSubst = genericTypeBindings[className] ?: emptyMap()

		val cRet        = if (f.returnType != null) cType(f.returnType) else "void"
		val cRecvType   = typeFlatName(className)
		val selfParam   = "$cRecvType* \$self"
		val extraParams = expandParams(f.params)
		val allParams   = if (extraParams.isEmpty()) selfParam else "$selfParam, $extraParams"
		val cFnName     = "${cRecvType}_${f.name}"

		hdr.appendLine("$cRet $cFnName($allParams);")
		impl.appendLine("$cRet $cFnName($allParams) {")

		val prevClass        = currentClass
		val prevSelfIsPtr    = selfIsPointer
		val prevExtRecvType  = currentExtRecvType
		currentExtRecvType = className
		currentClass       = className
		selfIsPointer      = true

		pushScope()
		for (p in f.params) {
			val vKtcP = resolveTypeName(p.type)
			val vPStr = vKtcP.toInternalStr
			defineVar(p.name, when {
				p.type.nullable            -> "${vPStr}?"
				classes.containsKey(vPStr) -> "${vPStr}*"
				else                       -> vPStr
				})
			}
		for ((name, type) in ci.props) defineVarKtc(name, resolveTypeName(type))
		val savedTrampolined = trampolinedParams.toHashSet(); trampolinedParams.clear()
		emitArrayParamCopies(f.params, "    ")
		val savedDefers = deferStack.toList(); deferStack.clear()
		if (f.body != null) for (s in f.body.stmts) emitStmt(s, "    ", insideMethod = true)
		if (f.body?.stmts?.lastOrNull() !is ReturnStmt) emitDeferredBlocks("    ", insideMethod = true)
		deferStack.clear(); deferStack.addAll(savedDefers)
		trampolinedParams.clear(); trampolinedParams.addAll(savedTrampolined)
		popScope()

		currentClass       = prevClass
		selfIsPointer      = prevSelfIsPtr
		currentExtRecvType = prevExtRecvType
		impl.appendLine("}")
		impl.appendLine()

		val concreteReceiver = TypeRef(className, f.receiver!!.nullable)
		extensionFuns.getOrPut(className) { mutableListOf() }
			.add(FunDecl(f.name, f.params, f.returnType, f.body, concreteReceiver))
		ci.methods.add(FunDecl(f.name, f.params, f.returnType, f.body, concreteReceiver))
		typeSubst = prevSubst
		}
	}
