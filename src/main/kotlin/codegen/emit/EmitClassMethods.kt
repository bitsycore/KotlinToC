package com.bitsycore.ktc.codegen.emit

import com.bitsycore.ktc.ast.*
import com.bitsycore.ktc.codegen.*
import com.bitsycore.ktc.codegen.expr.*
import com.bitsycore.ktc.types.KtcType

// Method emit, struct field declarations, and primary constructor body.

internal fun CCodeGen.emitMethod(
	className:   String,
	f:           FunDecl,
	suppressHdr: Boolean = false,
	ifaceName:   String  = ""
	) {
	val cClass = typeFlatName(className)
	val siblings = classes[className]?.methods ?: emptyList()
	val overloadedName = methodName(f, siblings)
	val methodName = if (f.isPrivate) "PRIV_$overloadedName" else overloadedName

	val paramSig = f.params.joinToString(", ") { p -> "${p.name}: ${typeRefToStr(p.type)}" }
	val retSig = f.returnType?.let { ": ${typeRefToStr(it)}" } ?: ""
	val priv = if (f.isPrivate) "private " else ""
	impl.appendLine("// ══ ${priv}fun ${f.name}($paramSig)$retSig ══")
	val returnsNullable    = f.returnType != null && f.returnType.nullable
	val returnsSizedArray  = !returnsNullable && f.returnType != null && f.returnType.isSizedArray()
	val returnsSizedString = !returnsNullable && f.returnType != null && f.returnType.isSizedString()
	val vRetKtc      = if (f.returnType != null) resolveTypeName(f.returnType) else null // KtcType of return, or null
	val retResolved  = vRetKtc?.toInternalStr ?: f.body?.let { inferBlockType(it) } ?: "" // string for legacy helpers
	val optRetCType  = if (returnsNullable) optCTypeName(retResolved) else ""
	val cRet = when {
		returnsSizedArray  -> sizedArrayCTypeName(cTypeStr(vRetKtc!!.asArr!!.elem), f.returnType.getSizeAnnotation()!!)
		returnsSizedString -> sizedStringCTypeName(f.returnType.getSizeAnnotation()!!)
		returnsNullable && vRetKtc is KtcType.Any -> "ktc_Any"
		returnsNullable    -> optRetCType
		retResolved.isNotEmpty() -> cTypeStr(retResolved)
		else -> "void"
		}
	val selfParam   = "$cClass* \$self"
	val extraParams = expandParams(f.params)
	val allParts    = mutableListOf(selfParam)
	if (extraParams.isNotEmpty()) allParts += extraParams
	val allParams   = allParts.joinToString(", ")

	val vHdrSig = "KTC_METHOD($cRet, $methodName)(${allParams.replace(cClass, "KTC_TYPE_NAME")});"
	if (f.isPrivate) {
		implFwd.appendLine("$cRet ${cClass}_${methodName}($allParams);")
		} else if (suppressHdr) {
		deferredHdrLines.getOrPut(className) { mutableListOf() }.add(Pair(ifaceName, vHdrSig))
		} else {
		hdr.appendLine(vHdrSig)
		}
	impl.appendLine("$cRet ${cClass}_${methodName}($allParams) {")
	val vTrackDispose = disposedMode != "NO" || doubleDisposeMode != "NO"
	if (vTrackDispose && f.name == "dispose") impl.appendLine("    KTC_MARK_DISPOSED(\$self);")
	else if (disposedMode != "NO") impl.appendLine("    KTC_ASSERT_NOT_DISPOSED(\$self);")

	val prevState = saveFunState()
	currentFnReturnsNullable    = returnsNullable
	currentFnReturnsArray       = false
	currentFnReturnsSizedArray  = returnsSizedArray
	currentFnOptReturnCTypeName = optRetCType
	if (returnsSizedArray) {
		currentFnSizedArraySize     = f.returnType.getSizeAnnotation()!!
		currentFnSizedArrayElemType = cTypeStr(vRetKtc!!.asArr!!.elem)
		}
	currentFnReturnsSizedString = returnsSizedString
	if (returnsSizedString) currentFnSizedStringSize = f.returnType.getSizeAnnotation()!!
	currentFnReturnType = retResolved

	pushScope()
	for (p in f.params) {
		val vKtcParam = resolveTypeName(p.type)
		val vPStr     = vKtcParam.toInternalStr
		defineVar(p.name, when {
			p.type.nullable -> "${vPStr}?"
			else -> vPStr
			})
		if (p.type.nullable && isValueNullableKtc(KtcType.Nullable(vKtcParam))) markOptional(p.name)
		}
	val ci = classes[className]
	if (ci != null) for ((name, type) in ci.props) defineVarKtc(name, resolveTypeName(type))
	val savedTrampolined1 = trampolinedParams.toHashSet(); trampolinedParams.clear()
	val savedSizedTrampolined1 = sizedArrayTrampolinedParams.toHashSet(); sizedArrayTrampolinedParams.clear()
	emitArrayParamCopies(f.params, "    ")

	val savedDefers = deferStack.toList(); deferStack.clear()
	if (f.body != null) for (s in f.body.stmts) emitStmt(s, "    ", insideMethod = true)
	if (f.body?.stmts?.lastOrNull() !is ReturnStmt) {
		emitDeferredBlocks("    ", insideMethod = true)
		if (returnsNullable) {
			if (vRetKtc is KtcType.Any) impl.appendLine("    return (ktc_Any){0};")
			else impl.appendLine("    return ${optNone(optRetCType)};")
			}
		}
	deferStack.clear(); deferStack.addAll(savedDefers)
	trampolinedParams.clear(); trampolinedParams.addAll(savedTrampolined1)
	sizedArrayTrampolinedParams.clear(); sizedArrayTrampolinedParams.addAll(savedSizedTrampolined1)
	popScope()

	restoreFunState(prevState)
	impl.appendLine("}")
	impl.appendLine()
	}

/** Emit struct field declarations (shared by emitClass and emitGenericClass). */
internal fun CCodeGen.emitStructFields(ci: ClassInfo) {
	hdr.appendLine("    ktc_core_AnyData __base;")
	for ((name, type) in ci.props) {
		val vFieldName = if (name in ci.privateProps) "PRIV_$name" else name
		val vKtcField = if (type.name == "RawArray" && type.typeArgs.isNotEmpty())
			KtcType.Ptr(resolveTypeName(type.typeArgs[0]))
		else resolveTypeName(type)
		val vMutComment = if (ci.isValProp(name)) "/*VAL*/ " else "/*VAR*/ "
		if (vKtcField is KtcType.Func) {
			hdr.appendLine("    $vMutComment${cFuncPtrDecl(vKtcField, vFieldName)};")
			} else if (vKtcField.isArrayLike) {
			val vSizeAnn = type.getSizeAnnotation()
			if (vSizeAnn != null) {
				val vElemCt = cTypeStr(vKtcField.asArr!!.elem)
				hdr.appendLine("    $vMutComment$vElemCt $vFieldName[${vSizeAnn}];")
				} else {
				hdr.appendLine("    $vMutComment${cTypeStr(vKtcField)} $vFieldName;${ptrNullComment(vKtcField)}")
				hdr.appendLine("    ktc_Int ${vFieldName}\$len;")
				}
			} else if (type.nullable) {
			hdr.appendLine("    $vMutComment${optCTypeName(vKtcField.toInternalStr)} $vFieldName;")
			} else {
			hdr.appendLine("    $vMutComment${cTypeStr(vKtcField)} $vFieldName;${ptrNullComment(vKtcField)}")
			}
		}
	}

/* Emit primary constructor header declaration + impl body. */
internal fun CCodeGen.emitConstructorBody(cName: String, ci: ClassInfo) {
	val vAllCtorParams = ci.ctorProps + ci.ctorPlainParams
	val vParamStr      = expandCtorParams(vAllCtorParams)
	val vParamDecl     = vParamStr.ifEmpty { "void" }
	hdr.appendLine("KTC_METHOD(KTC_TYPE_NAME, primaryConstructor)($vParamDecl);")
	impl.appendLine("$cName ${cName}_primaryConstructor($vParamDecl) {")
	if (ci.bodyProps.isEmpty() && ci.ctorPlainParams.isEmpty() && ci.ctorProps.none { resolveTypeName(it.typeRef).isArrayLike || it.typeRef.nullable }) {
		impl.appendLine("    return ($cName){{${cName}_TYPE_ID}, ${ci.ctorProps.joinToString(", ") { it.name }}};")
		} else {
		impl.appendLine("    $cName \$self = {0};")
		impl.appendLine("    \$self.__base.typeId = ${cName}_TYPE_ID;")
		for (vProp in ci.ctorProps) {
			val vName      = vProp.name
			val vType      = vProp.typeRef
			val vFieldName = if (vName in ci.privateProps) "PRIV_$vName" else vName
			val vKtcProp   = resolveTypeName(vType)
			val vSizeAnn   = vType.getSizeAnnotation()
			if (vSizeAnn != null) {
				val vElemType = cTypeStr(vKtcProp.asArr!!.elem)
				impl.appendLine("    memcpy(\$self.$vFieldName, $vName, $vSizeAnn * sizeof($vElemType));")
				} else if (vKtcProp.isArrayLike) {
				impl.appendLine("    \$self.$vFieldName = $vName;")
				impl.appendLine("    \$self.${vFieldName}\$len = ${vName}\$len;")
				} else {
				impl.appendLine("    \$self.$vFieldName = $vName;")
				}
			}
		for (vBp in ci.bodyProps) {
			if (vBp.initExpr != null) {
				if (vBp.line > 0) currentStmtLine = vBp.line
				heapAllocTargetType = vBp.typeRef
				val vBodyFieldName = if (vBp.isPrivate) "PRIV_${vBp.name}" else vBp.name
				val vSizeAnn = vBp.typeRef.getSizeAnnotation()
				if (vSizeAnn != null && vBp.typeRef.isSizedArray()) {
					val vIsZeroInit = vBp.initExpr is CallExpr && (vBp.initExpr.callee as? NameExpr)?.name?.endsWith("Array") == true &&
						vBp.initExpr.args.size == 1 && vBp.initExpr.args[0].expr !is LambdaExpr
					if (!vIsZeroInit) {
						val vExpr = genExpr(vBp.initExpr)
						heapAllocTargetType = null
						flushPreStmts("    ")
						val vElemType = cTypeStr(resolveTypeName(vBp.typeRef).asArr!!.elem)
						impl.appendLine("    memcpy(\$self.$vBodyFieldName, $vExpr, $vSizeAnn * sizeof($vElemType));")
						} else {
						heapAllocTargetType = null
						}
					} else {
					val vExpr = genExpr(vBp.initExpr)
					heapAllocTargetType = null
					flushPreStmts("    ")
					impl.appendLine("    \$self.$vBodyFieldName = $vExpr;")
					}
				emitBodyPropLenIfArray(vBp)
				}
			}
		impl.appendLine("    return \$self;")
		}
	impl.appendLine("}")
	}
