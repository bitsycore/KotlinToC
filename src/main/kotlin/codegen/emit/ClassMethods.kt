package com.bitsycore.ktc.codegen.emit

import com.bitsycore.ktc.ast.*
import com.bitsycore.ktc.codegen.*
import com.bitsycore.ktc.codegen.expression.inferBlockType
import com.bitsycore.ktc.codegen.statement.emitBodyPropLenIfArray
import com.bitsycore.ktc.types.KtcType


// Method emit, struct field declarations, primary constructor body, and shared class emit helpers.

/* Emit implicit dispose declaration/stub if the class declares no explicit dispose. */
internal fun CCodeGen.emitImplicitDispose(cName: String, members: List<Decl>) {
	if (members.none { it is FunDecl && it.name == "dispose" }) {
		if (disposedMode != "NO" || doubleDisposeMode != "NO") {
			hdr.appendLine("KTC_METHOD(void, dispose)(KTC_TYPE_NAME* \$self);")
			impl.appendLine("// ══ fun dispose() ══")
			impl.appendLine("void ${cName}_dispose($cName* \$self) { KTC_MARK_DISPOSED(\$self); }")
			impl.appendLine()
			} else {
			hdr.appendLine("#define ${cName}_dispose(self) ((void)(self))")
			}
		}
	}

/* Emit header declarations for each super interface a class implements. */
internal fun CCodeGen.emitSuperInterfaceHdrDecls(
	superInterfaces: List<TypeRef>,
	deferredLines:   List<Pair<String, String>>?
	) {
	if (superInterfaces.isEmpty()) return
	val vByIface = deferredLines?.groupBy { it.first } ?: emptyMap()
	for (vIfaceRef in superInterfaces) {
		val vIfaceName = resolveIfaceName(vIfaceRef)
		val vIface     = interfaces[vIfaceName] ?: continue
		val vIfaceStr  = typeRefToStr(vIfaceRef)
		val cIface     = typeFlatName(vIfaceName)
		hdr.appendLine()
		hdr.appendLine("// ════ implements $vIfaceStr ════")
		val vLines = vByIface[vIfaceStr]
		if (vLines != null) for ((_, vLine) in vLines) hdr.appendLine(vLine)
		for (vProp in vIface.propDecls) {
			val vCt = if (vProp.type != null) cType(vProp.type) else "ktc_Int"
			hdr.appendLine("KTC_METHOD($vCt, ${vProp.name}_get)(KTC_TYPE_NAME* \$self);")
			}
		hdr.appendLine("extern const ${cIface}_vt KTC_RELATED(${vIfaceName}_vt);")
		hdr.appendLine("KTC_METHOD($cIface, as_${vIfaceName})(KTC_TYPE_NAME* \$self);")
		emitTransitiveIfaceHdrDecls(vIface, vByIface)
		}
	}

internal fun CCodeGen.emitMethod(
	className:   String,
	f:           FunDecl,
	suppressHdr: Boolean = false,
	ifaceName:   String  = ""
	) {
	val cClass         = typeFlatName(className)
	val siblings       = classes[className]?.methods ?: emptyList()
	val methodName     = resolvedFnName(f, siblings)
	val selfParam      = "$cClass* \$self"
	val extraParams    = expandParams(f.params)
	val allParams      = if (extraParams.isNotEmpty()) "$selfParam, $extraParams" else selfParam

	val paramSig = f.params.joinToString(", ") { p -> "${p.name}: ${typeRefToStr(p.type)}" }
	val retSig   = f.returnType?.let { ": ${typeRefToStr(it)}" } ?: ""
	val priv     = if (f.isPrivate) "private " else ""
	impl.appendLine("// ══ ${priv}fun ${f.name}($paramSig)$retSig ══")

	val prevState = saveFunState()
	val cRet = computeReturnInfo(f, f.body?.let { inferBlockType(it) })

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

	pushScope()
	registerParams(f.params)
	val ci = classes[className]
	if (ci != null) {
		val selfDot = if (selfIsPointer) "\$self->" else "\$self."
		registerClassFields(ci, selfDot)
		}
	// For nested classes (Obj$Inner), pre-populate parent object fields; class fields take priority
	val vParentObjName = if ('$' in className) className.substringBefore('$') else null
	val vParentOi = if (vParentObjName != null && currentObject == null) objects[vParentObjName] else null
	if (vParentOi != null) {
		val vParentCName = typeFlatName(vParentObjName!!)
		for ((name, type) in vParentOi.props) {
			if (scopes.last().containsKey(name)) continue // class field or param takes priority
			val vKtc   = resolveTypeName(type)
			val vFn    = if (name in vParentOi.privateProps) "PRIV_$name" else name
			val vIsOpt = type.nullable && !type.annotations.any { it.name == "Ptr" } && !vKtc.isArrayLike
			defineVar(name, LocalVar(ktc = vKtc, mutable = true, optional = vIsOpt, cName = "$vParentCName.$vFn"))
			}
		}
	emitArrayParamCopies(f.params, "    ")
	emitFunBodyAndClose(f, prevState, insideMethod = true)
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
				val vArrElem   = vKtcField.asArr?.elem ?: ((vKtcField as? KtcType.Ptr)?.inner as KtcType.Arr).elem
				val vElemCType = elemCTypeStr(vArrElem)
				hdr.appendLine("    $vMutComment${varArrTypeName(vElemCType)} $vFieldName;${ptrNullComment(vKtcField)}")
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
				} else {
				impl.appendLine("    \$self.$vFieldName = $vName;")
				}
			}
		for (vBp in ci.bodyProps) {
			if (vBp.initExpr != null) {
				if (vBp.line > 0) currentStmtLine = vBp.line
				val vBodyFieldName = if (vBp.isPrivate) "PRIV_${vBp.name}" else vBp.name
				val vSizeAnn = vBp.typeRef.getSizeAnnotation()
				if (vSizeAnn != null && vBp.typeRef.isSizedArray()) {
					val vIsZeroInit = vBp.initExpr is CallExpr && (vBp.initExpr.callee as? NameExpr)?.name?.endsWith("Array") == true &&
						vBp.initExpr.args.size == 1 && vBp.initExpr.args[0].expr !is LambdaExpr
					if (!vIsZeroInit) {
						val vExpr = genExprWithHeapTarget(vBp.initExpr, vBp.typeRef)
						flushPreStmts("    ")
						val vElemType = cTypeStr(resolveTypeName(vBp.typeRef).asArr!!.elem)
						val vSrcKtc  = inferExprTypeKtc(vBp.initExpr)
						val vSrcExpr = if (vSrcKtc != null && vSrcKtc.isArrayLike && vSrcKtc.asArr?.sized == null) "($vExpr).ptr" else vExpr
						impl.appendLine("    memcpy(\$self.$vBodyFieldName, $vSrcExpr, $vSizeAnn * sizeof($vElemType));")
						}
					} else {
					val vExpr = genExprWithHeapTarget(vBp.initExpr, vBp.typeRef)
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
