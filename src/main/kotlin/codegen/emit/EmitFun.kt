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
	val recvTypeName   = f.receiver!!.name
	val recvIsNullable = f.receiver.nullable
	val paramSig = f.params.joinToString(", ") { p -> "${p.name}: ${typeRefToStr(p.type)}" }
	val retSig   = f.returnType?.let { ": ${typeRefToStr(it)}" } ?: ""
	maybeEmitFunBanner(f.name)
	impl.appendLine("// ══ ext fun ${recvTypeName}.${f.name}($paramSig)$retSig ($currentSourceFile) ══")
	val isClassType = classes.containsKey(recvTypeName)
	val cRecvType   = cType(f.receiver)
	val selfParam   = if (recvIsNullable) "${optCTypeName(recvTypeName)} \$self" else "$cRecvType \$self"
	val extraParams = expandParams(f.params)
	val allParams   = if (extraParams.isNotEmpty()) "$selfParam, $extraParams" else selfParam
	val cFnName     = "${typeFlatName(recvTypeName)}_${f.name}"

	val prevState = saveFunState()
	val cRet = computeReturnInfo(f)
	currentExtRecvType = if (recvIsNullable) "$recvTypeName?" else recvTypeName
	if (isClassType) { currentClass = recvTypeName; selfIsPointer = false }
	else             { currentClass = null;         selfIsPointer = false }

	hdr.appendLine("$cRet $cFnName($allParams);")
	impl.appendLine("$cRet $cFnName($allParams) {")

	pushScope()
	if (recvIsNullable) {
		defineVar("\$self", "${recvTypeName}?")
		markOptional("\$self")
		}
	registerParams(f.params)
	if (isClassType) registerClassFields(classes[recvTypeName]!!, "\$self.")
	emitArrayParamCopies(f.params, "    ")
	if (f.body != null) for (s in f.body.stmts) emitStmt(s, "    ", insideMethod = isClassType)
	if (f.body?.stmts?.lastOrNull() !is ReturnStmt) {
		emitDeferredBlocks("    ", insideMethod = isClassType)
		emitImplicitNullReturn("    ")
		}
	closeFunBody(prevState)
	}

internal fun CCodeGen.emitFun(f: FunDecl) {
	if (f.isInline) return  // inline funs are expanded at call sites only

	maybeEmitFunBanner(f.name)

	val paramSig = f.params.joinToString(", ") { p -> typeRefToStr(p.type) }
	val retSig   = f.returnType?.let { ": ${typeRefToStr(it)}" } ?: ""
	impl.appendLine("// ══ fun ${f.name}($paramSig)$retSig ($currentSourceFile) ══")
	val isMain         = f.name == "main"
	val siblings       = file.decls.filterIsInstance<FunDecl>()
	val overloadedName = methodName(f, siblings)
	val baseName       = if (f.isPrivate) "PRIV_$overloadedName" else overloadedName
	val cName          = funCName(baseName)
	val params         = expandParams(f.params)

	val prevState = saveFunState()
	val cRet = computeReturnInfo(f, f.body?.let { inferBlockType(it) })
	currentFnIsMain = false

	hdr.appendLine("$cRet $cName($params);")
	impl.appendLine("$cRet $cName($params) {")

	pushScope()
	registerParams(f.params)
	if (isMain) {
		// main's array params arrive pre-laid-out from main.c — alias to local pointers without copying
		for (vP in f.params) {
			if (!vP.type.isRawArray()) continue
			val vKtcMP   = resolveTypeName(vP.type)
			val vArrElem = vKtcMP.asArr!!.elem
			val vECType  = if (vArrElem is KtcType.Nullable) optCTypeName(vArrElem.inner.toInternalStr) else cTypeStr(vArrElem)
			impl.appendLine("    $vECType* local\$${vP.name} = ${vP.name}.ptr;")
			trampolinedParams += vP.name
			}
		} else {
		emitArrayParamCopies(f.params, "    ")
		}

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
	else if (lastStmt !is ReturnStmt) emitImplicitNullReturn("    ")
	closeFunBody(prevState)
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
