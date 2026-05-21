package com.bitsycore.ktc.codegen.emit

import com.bitsycore.ktc.ast.*
import com.bitsycore.ktc.codegen.*
import com.bitsycore.ktc.codegen.expr.emitStmt
import com.bitsycore.ktc.codegen.expr.genExpr
import com.bitsycore.ktc.codegen.expr.inferBlockType
import com.bitsycore.ktc.types.KtcType

// Free function, extension function, and top-level property emission.
// Generic function monomorphization lives in EmitFunGeneric.kt.
// Enum emit lives in EmitEnum.kt.

internal fun CCodeGen.emitExtensionFun(f: FunDecl) {
	val recvTypeName    = f.receiver!!.name               // receiver base type name
	val recvIsNullable  = f.receiver.nullable
	val paramSig = f.params.joinToString(", ") { p -> "${p.name}: ${typeRefToStr(p.type)}" }
	val retSig   = f.returnType?.let { ": ${typeRefToStr(it)}" } ?: ""
	maybeEmitFunBanner(f.name)
	impl.appendLine("// ══ ext fun ${recvTypeName}.${f.name}($paramSig)$retSig ($currentSourceFile) ══")
	val returnsSizedArray  = f.returnType != null && f.returnType.isSizedArray()
	val returnsSizedString = f.returnType != null && f.returnType.isSizedString()
	val returnsNullable    = f.returnType != null && f.returnType.nullable
	val vRetKtc    = if (f.returnType != null) resolveTypeName(f.returnType) else null // KtcType of return
	val retResolved = vRetKtc?.toInternalStr ?: ""                                     // string for legacy helpers
	val optRetCType = if (returnsNullable) optCTypeName(retResolved) else ""
	val cRet = when {
		returnsSizedArray                               -> sizedArrayCTypeName(cTypeStr(vRetKtc!!.asArr!!.elem), f.returnType.getSizeAnnotation()!!)
		returnsSizedString                              -> sizedStringCTypeName(f.returnType.getSizeAnnotation()!!)
		returnsNullable && vRetKtc is KtcType.Any      -> "ktc_Any"
		returnsNullable                                -> optRetCType
		f.returnType != null                           -> cType(f.returnType)
		else                                           -> "void"
		}
	val isClassType = classes.containsKey(recvTypeName)
	val cRecvType   = cType(f.receiver)                   // honor @Ptr annotations
	val selfParam   = if (recvIsNullable) "${optCTypeName(recvTypeName)} \$self"
		else "$cRecvType \$self"
	val extraParams = expandParams(f.params)
	val allParts    = mutableListOf(selfParam)
	if (extraParams.isNotEmpty()) allParts += extraParams
	val allParams   = allParts.joinToString(", ")
	val cFnName     = "${typeFlatName(recvTypeName)}_${f.name}"

	hdr.appendLine("$cRet $cFnName($allParams);")
	impl.appendLine("$cRet $cFnName($allParams) {")

	val prevState = saveFunState()
	currentFnReturnsSizedArray  = returnsSizedArray
	currentFnOptReturnCTypeName = optRetCType
	if (returnsSizedArray) {
		currentFnSizedArraySize     = f.returnType.getSizeAnnotation()!!
		currentFnSizedArrayElemType = cTypeStr(vRetKtc!!.asArr!!.elem)
		}
	currentFnReturnsSizedString = returnsSizedString
	if (returnsSizedString) currentFnSizedStringSize = f.returnType.getSizeAnnotation()!!
	currentExtRecvType = if (recvIsNullable) "$recvTypeName?" else recvTypeName
	if (isClassType) { currentClass = recvTypeName; selfIsPointer = false }
	else             { currentClass = null;         selfIsPointer = false }

	pushScope()
	if (recvIsNullable) {
		defineVar("\$self", "${recvTypeName}?")
		markOptional("\$self")
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
	if (isClassType) {
		val ci = classes[recvTypeName]!!
		for ((name, type) in ci.props) {
			defineVarKtc(name, resolveTypeName(type))
			if (!ci.isValProp(name)) markMutable(name)
			}
		}
	val savedTrampolined      = trampolinedParams.toHashSet();      trampolinedParams.clear()
	val savedSizedTrampolined = sizedArrayTrampolinedParams.toHashSet(); sizedArrayTrampolinedParams.clear()
	emitArrayParamCopies(f.params, "    ")
	val savedDefers = deferStack.toList(); deferStack.clear()
	if (f.body != null) for (s in f.body.stmts) emitStmt(s, "    ", insideMethod = isClassType)
	if (f.body?.stmts?.lastOrNull() !is ReturnStmt) {
		emitDeferredBlocks("    ", insideMethod = isClassType)
		if (returnsNullable) {
			if (vRetKtc is KtcType.Any) impl.appendLine("    return (ktc_Any){0};")
			else impl.appendLine("    return ${optNone(optRetCType)};")
			}
		}
	deferStack.clear();                deferStack.addAll(savedDefers)
	trampolinedParams.clear();          trampolinedParams.addAll(savedTrampolined)
	sizedArrayTrampolinedParams.clear(); sizedArrayTrampolinedParams.addAll(savedSizedTrampolined)
	popScope()
	restoreFunState(prevState)
	impl.appendLine("}")
	impl.appendLine()
	}

internal fun CCodeGen.emitFun(f: FunDecl) {
	if (f.isInline) return  // inline funs are expanded at call sites only

	maybeEmitFunBanner(f.name)

	val paramSig = f.params.joinToString(", ") { p -> typeRefToStr(p.type) }
	val retSig   = f.returnType?.let { ": ${typeRefToStr(it)}" } ?: ""
	impl.appendLine("// ══ fun ${f.name}($paramSig)$retSig ($currentSourceFile) ══")
	val isMain          = f.name == "main"
	val siblings        = file.decls.filterIsInstance<FunDecl>()
	val overloadedName  = methodName(f, siblings)
	val baseName        = if (f.isPrivate) "PRIV_$overloadedName" else overloadedName

	val returnsNullable    = f.returnType != null && f.returnType.nullable
	val returnsSizedArray  = !returnsNullable && f.returnType != null && f.returnType.isSizedArray()
	val returnsSizedString = !returnsNullable && f.returnType != null && f.returnType.isSizedString()
	val vRetKtc     = if (f.returnType != null) resolveTypeName(f.returnType) else null // KtcType of return
	val returnsArray = !returnsNullable && !returnsSizedArray && (vRetKtc?.isArrayLike ?: false)
	val retResolved  = vRetKtc?.toInternalStr ?: f.body?.let { inferBlockType(it) } ?: ""
	val optRetCType  = if (returnsNullable) optCTypeName(retResolved) else ""
	val cRet = when {
		returnsSizedArray                               -> sizedArrayCTypeName(cTypeStr(vRetKtc!!.asArr!!.elem), f.returnType.getSizeAnnotation()!!)
		returnsSizedString                              -> sizedStringCTypeName(f.returnType.getSizeAnnotation()!!)
		returnsNullable && vRetKtc is KtcType.Any      -> "ktc_Any"
		returnsNullable                                -> optRetCType
		retResolved.isNotEmpty()                       -> cTypeStr(retResolved)
		else                                           -> "void"
		}
	val cName  = funCName(baseName)
	val base   = expandParams(f.params)
	val extra  = if (returnsArray) "ktc_Int* \$len_out" else null
	val params = if (extra != null) { if (base.isEmpty()) extra else "$base, $extra" } else base

	hdr.appendLine("$cRet $cName($params);")
	impl.appendLine("$cRet $cName($params) {")

	val prevState  = saveFunState()
	val prevIsMain = currentFnIsMain
	currentFnReturnsNullable    = returnsNullable
	currentFnReturnsArray       = returnsArray
	currentFnReturnsSizedArray  = returnsSizedArray
	currentFnOptReturnCTypeName = optRetCType
	if (returnsSizedArray) {
		currentFnSizedArraySize     = f.returnType.getSizeAnnotation()!!
		currentFnSizedArrayElemType = cTypeStr(vRetKtc!!.asArr!!.elem)
		}
	currentFnReturnsSizedString = returnsSizedString
	if (returnsSizedString) currentFnSizedStringSize = f.returnType.getSizeAnnotation()!!
	currentFnReturnType    = retResolved
	currentFnReturnKtcType = vRetKtc
	currentFnIsMain        = false

	pushScope()
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
	if (isMain) {
		// main's array params come from main.c already laid out — alias without copying
		for (vP in f.params) {
			if (!vP.type.isRawArray()) continue
			val vKtcMP  = resolveTypeName(vP.type)
			val vArrElem = vKtcMP.asArr!!.elem
			val vECType  = if (vArrElem is KtcType.Nullable) optCTypeName(vArrElem.inner.toInternalStr) else cTypeStr(vArrElem)
			impl.appendLine("    $vECType* local\$${vP.name} = ${vP.name}.ptr;")
			trampolinedParams += vP.name
			}
		} else {
		emitArrayParamCopies(f.params, "    ")
		}
	val savedDefers = deferStack.toList(); deferStack.clear()

	if (f.body != null) for (s in f.body.stmts) emitStmt(s, "    ")
	val lastStmt = f.body?.stmts?.lastOrNull()
	if (lastStmt !is ReturnStmt) emitDeferredBlocks("    ")
	if (isMain && objectsWithDispose.isNotEmpty()) {
		for (vCName in objectsWithDispose.distinct()) impl.appendLine("    ${vCName}_dispose();")
		}
	if (isMain && memTrack) {
		impl.appendLine("    fflush(stdout);")
		impl.appendLine("    ktc_core_mem_report();")
		}
	else if (returnsNullable && lastStmt !is ReturnStmt) {
		if (vRetKtc is KtcType.Any) impl.appendLine("    return (ktc_Any){0};")
		else impl.appendLine("    return ${optNone(optRetCType)};")
		}
	trampolinedParams.clear();          trampolinedParams.addAll(savedTrampolined)
	sizedArrayTrampolinedParams.clear(); sizedArrayTrampolinedParams.addAll(savedSizedTrampolined)
	popScope()
	deferStack.clear(); deferStack.addAll(savedDefers)
	restoreFunState(prevState)
	currentFnIsMain = prevIsMain
	impl.appendLine("}")
	impl.appendLine()
	}

internal fun CCodeGen.emitTopProp(d: PropDecl) {
	val vKtc = if (d.type != null) resolveTypeName(d.type) else inferExprTypeKtc(d.init) // KtcType of prop
	val t    = vKtc?.toInternalStr ?: (inferExprType(d.init) ?: "Int")
	val ct   = cTypeStr(t)
	val cName      = typeFlatName(d.name)
	val tls        = if (d.name in tlsProps) "ktc_core_tls " else ""
	val qual       = if (!d.mutable) "const " else ""
	val mutComment = if (d.mutable) "/*VAR*/ " else "/*VAL*/ "
	if (d.init != null) {
		hdr.appendLine("extern $tls$qual$ct $cName;")
		impl.appendLine("$tls$qual$mutComment$ct $cName = ${genExpr(d.init)};")
		} else {
		hdr.appendLine("extern $tls$ct $cName;")
		impl.appendLine("$tls$mutComment$ct $cName = ${defaultVal(parseResolvedTypeName(t))};")
		}
	impl.appendLine()
	}
