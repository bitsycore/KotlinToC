package com.bitsycore.ktc.codegen.emit

import com.bitsycore.ktc.ast.*
import com.bitsycore.ktc.codegen.*
import com.bitsycore.ktc.codegen.expression.genExpr
import com.bitsycore.ktc.codegen.expression.inferBlockType
import com.bitsycore.ktc.codegen.statement.emitStmt


// Free function, extension function, and top-level property emission.
// Generic function monomorphization lives in FunGeneric.kt.
// Enum emit lives in Enum.kt.

internal fun CCodeGen.emitExtensionFun(f: FunDecl) {
	validateTailrec(f)
	suggestTailrec(f)
	val vRawRecvName        = f.receiver!!.name
		// Resolve dotted receiver (e.g. "SDL3.Window" → "SDL3$Window", also nested objects)
		val recvTypeName        = vRawRecvName.replace('.', '$').let { if (classes.containsKey(it) || objects.containsKey(it)) it else vRawRecvName }
	val recvIsNullable = f.receiver.nullable
	val paramSig = f.params.joinToString(", ") { p -> "${p.name}: ${typeRefToStr(p.type)}" }
	val retSig   = f.returnType?.let { ": ${typeRefToStr(it)}" } ?: ""
	maybeEmitFunBanner(f.name)
	impl.appendLine("// ══ ext fun ${recvTypeName}.${f.name}($paramSig)$retSig ($currentSourceFile) ══")
	val isClassType    = classes.containsKey(recvTypeName)
	val isObjectType   = objects.containsKey(recvTypeName)
	val isNamespaceObj = namespaceObjects.contains(recvTypeName)
	val cRecvType      = cType(f.receiver)
	val cSelfType      = if (isObjectType) "${cRecvType}_t" else cRecvType
	val selfParam      = if (recvIsNullable) "${optCTypeName(recvTypeName)} \$self" else "$cSelfType \$self"
	val extraParams    = expandParams(f.params)
	// @Namespace objects have no C representation — extension functions become free functions (no $self)
	val allParams = when {
		isNamespaceObj              -> extraParams.ifEmpty { "void" }
		extraParams.isNotEmpty()    -> "$selfParam, $extraParams"
		else                        -> selfParam
	}
	// Overload resolution for extension functions
		val vExtSiblings = extensionFuns[recvTypeName]?.filter { it.name == f.name }?.distinctBy { it.params.map { p -> resolveTypeName(p.type).toInternalStr } } ?: listOf(f)
		val vCName       = resolvedFnName(f, vExtSiblings)
		val cFnName     = "${typeFlatName(recvTypeName)}_$vCName"

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
	val selfCType = if (recvIsNullable) optCTypeName(recvTypeName) else cSelfType
	setupTailrec(f, hasReceiver = !isNamespaceObj, selfCType = selfCType)
	emitFunBodyAndClose(f, prevState, insideMethod = isClassType)
	}

internal fun CCodeGen.emitFun(f: FunDecl) {
	if (f.isInline) return  // inline funs are expanded at call sites only
	validateTailrec(f)
	suggestTailrec(f)

	maybeEmitFunBanner(f.name)

	val paramSig = f.params.joinToString(", ") { p -> typeRefToStr(p.type) }
	val retSig   = f.returnType?.let { ": ${typeRefToStr(it)}" } ?: ""
	impl.appendLine("// ══ fun ${f.name}($paramSig)$retSig ($currentSourceFile) ══")
	val isMain         = f.name == "main"
	val siblings       = file.decls.filterIsInstance<FunDecl>()
	val baseName       = resolvedFnName(f, siblings)
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
			val vECType  = elemCTypeStr(vArrElem)
			impl.appendLine("    $vECType* local\$${vP.name} = ${vP.name}.ptr;")
			trampolinedParams += vP.name
			}
		} else {
		emitArrayParamCopies(f.params, "    ")
		}

	setupTailrec(f)
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

/* Emit function body statements, trailing deferred blocks, optional implicit return, then close the function. */
internal fun CCodeGen.emitFunBodyAndClose(
	f:                  FunDecl,
	prevState:          CCodeGen.FunState,
	insideMethod:       Boolean = false,
	withImplicitReturn: Boolean = true
	) {
	if (f.body != null) for (s in f.body.stmts) emitStmt(s, "    ", insideMethod = insideMethod)
	if (f.body?.stmts?.lastOrNull() !is ReturnStmt) {
		emitDeferredBlocks("    ", insideMethod = insideMethod)
		if (withImplicitReturn) emitImplicitNullReturn("    ")
		}
	closeFunBody(prevState)
	}

internal fun CCodeGen.emitTopProp(d: PropDecl) {
	// by lazy { body } — thread-safe lazy initialization
	if (d.lazyInit != null) {
		emitLazyTopProp(d)
		return
	}
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

private fun CCodeGen.emitLazyTopProp(d: PropDecl) {
	val block = d.lazyInit!!
	val stmts = block.stmts
	val lastStmt = stmts.lastOrNull()
	val lastExpr = when (lastStmt) {
		is ExprStmt   -> lastStmt.expr
		is ReturnStmt -> lastStmt.value
		else -> null
	} ?: codegenError("lazy initializer for '${d.name}' must end with an expression")

	val vKtc = if (d.type != null) resolveTypeName(d.type) else inferExprTypeKtc(lastExpr)
		?: codegenError("Cannot infer type for lazy property '${d.name}' — add an explicit type annotation")
	val ct = cTypeStr(vKtc)
	val cName = typeFlatName(d.name)

	lazyTopProps.add(d.name)

	// Static storage: once flag + cache
	impl.appendLine("static ktc_thread_once_t ${cName}\$once = KTC_THREAD_ONCE_INIT;")
	impl.appendLine("static $ct ${cName}\$cache;")
	impl.appendLine()

	// Init function — runs exactly once across all threads
	impl.appendLine("static void ${cName}_lazy_init(void) {")
	pushScope()
	for (i in 0 until stmts.size - 1) {
		emitStmt(stmts[i], "    ")
	}
	val initExpr = genExpr(lastExpr)
	flushPreStmts("    ")
	impl.appendLine("    ${cName}\$cache = $initExpr;")
	popScope()
	impl.appendLine("}")
	impl.appendLine()

	// Ensure-init function called before each access (ktc_thread_call_once is fast-path after first init)
	hdr.appendLine("void ${cName}_\$ensure_init(void);")
	impl.appendLine("void ${cName}_\$ensure_init(void) {")
	impl.appendLine("    ktc_thread_call_once(&${cName}\$once, ${cName}_lazy_init);")
	impl.appendLine("}")
	impl.appendLine()
	}
