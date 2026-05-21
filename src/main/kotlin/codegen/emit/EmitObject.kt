package com.bitsycore.ktc.codegen.emit

import com.bitsycore.ktc.ast.*
import com.bitsycore.ktc.codegen.*
import com.bitsycore.ktc.types.KtcType
import com.bitsycore.ktc.codegen.expr.emitStmt
import com.bitsycore.ktc.codegen.expr.genExpr
import com.bitsycore.ktc.codegen.expr.inferInitType
import com.bitsycore.ktc.codegen.expr.toStringMaxLen

// ──────────────────────────────────────────────────────────
// singleton object — lazy init, methods, Any vtable, as_Any
// ──────────────────────────────────────────────────────────

// ── object ───────────────────────────────────────────────────────

internal fun CCodeGen.emitObject(d: ObjectDecl) {
    val oi = objects[d.name]!!
    val cName = oi.flatName
    val props = d.members.filterIsInstance<PropDecl>()
    val initBlocks = d.members.filterIsInstance<FunDecl>().filter { it.name == "init" }
    val methods = d.members.filterIsInstance<FunDecl>().filter { it.name != "init" }
    val privPrefix = { p: PropDecl -> if (p.isPrivate) "PRIV_" else "" }

    // Determine display name and kind for banner: "Foo$Companion" → "companion object Foo.Companion"
    val vParts = d.name.split('$')
    val vDisplayName = vParts.joinToString(".")
    val vKind = if (vParts.size > 1 && vParts.last() == "Companion") "companion object" else "object"
    val vPkg = oi.pkg.trimEnd('_').replace('_', '.')

    // Forward-declare private methods into implFwd so they are grouped at the top of the .c file
    for (m in methods) {
        if (m.isPrivate && !m.isInline) {
            val vRetKtcFwd  = if (m.returnType != null) resolveTypeName(m.returnType) else null
            val vFwdRetStr  = vRetKtcFwd?.toInternalStr ?: ""
            val cRet = when {
                m.returnType != null && m.returnType.isSizedArray() -> "void"
                vFwdRetStr.isNotEmpty() -> cTypeStr(vFwdRetStr)
                else -> "void"
            }
            val fwdParams = expandParams(m.params)
            val overloadedName = methodName(m, methods)
            implFwd.appendLine("$cRet ${cName}_PRIV_$overloadedName($fwdParams);")
        }
    }

    // Emit nested classes BEFORE the object block so they don't conflict with #define KTC_TYPE_NAME.
    // Their impl output is captured separately so it appears after the object's own body in the .c file.
    val vNestedClassImpl = StringBuilder()
    val vSavedImplForNested = impl
    impl = vNestedClassImpl
    for (nested in d.members.filterIsInstance<ClassDecl>()) {
        if (nested.typeParams.isEmpty()) {
            val vNestedFlatName = "${d.name}$${nested.name}" // mangled nested class name
            emitClass(
                ClassDecl(
                    vNestedFlatName, nested.isData,
                    nested.ctorParams, nested.members, nested.initBlocks,
                    nested.superInterfaces, nested.typeParams, nested.secondaryCtors
                )
            )
            hdr.appendLine()
            val vNestedKind = if (nested.isData) "data class" else "class" // kind label for footer
            impl.appendLine(classBlockFooter(vNestedKind, vNestedFlatName.replace('$', '.'), emptyList()))
            impl.appendLine()
        }
    }
    impl = vSavedImplForNested

    hdr.appendLine(classBlockHeader(vKind, vDisplayName, emptyList(), emptyList(), vPkg, currentSourceFile, cName))
    hdr.appendLine("#define KTC_TYPE_NAME $cName")
    val typeIdValue = typeIds.getOrPut(d.name) { nextTypeId++ }
    hdr.appendLine("KTC_TYPE_ID($typeIdValue)")
    hdr.appendLine()

    val vTls = if (d.name in tlsObjects) "ktc_core_tls " else ""  // TLS qualifier string for .c file
    hdr.appendLine(if (vTls.isNotEmpty()) "KTC_TLS_OBJECT(" else "KTC_OBJECT(")
    hdr.appendLine("    ktc_core_AnyData __base;")
    if (props.isEmpty()) hdr.appendLine("    ktc_Char _dummy;")
    for (p in props) {
        val pType     = p.type ?: inferInitType(p.init)
        val vKtcObj   = resolveTypeName(pType)                          // KtcType for struct field
        val sizeAnn   = pType.getSizeAnnotation()
        if (vKtcObj.isArrayLike && sizeAnn != null) {
            val vElemType = cTypeStr(vKtcObj.asArr!!.elem)              // element C type for sized array
            val fn = privPrefix(p) + p.name
            val mutComment = if (p.mutable) "/*VAR*/ " else "/*VAL*/ "
            hdr.appendLine("    $mutComment$vElemType ${fn}[${sizeAnn}];")
        } else if (vKtcObj.isArrayLike) {
            val fn         = privPrefix(p) + p.name
            val mutComment = if (p.mutable) "/*VAR*/ " else "/*VAL*/ "
            val vArrElem   = vKtcObj.asArr?.elem ?: ((vKtcObj as? KtcType.Ptr)?.inner as KtcType.Arr).elem
            val vElemCType = if (vArrElem is KtcType.Nullable) optCTypeName(vArrElem.inner.toInternalStr) else cTypeStr(vArrElem)
            hdr.appendLine("    $mutComment${varArrTypeName(vElemCType)} ${fn};")
        } else {
            val fn = privPrefix(p) + p.name
            val mutComment = if (p.mutable) "/*VAR*/ " else "/*VAL*/ "
            hdr.appendLine("    ${mutComment}${cTypeStr(vKtcObj)} ${fn};${ptrNullComment(vKtcObj)}")
        }
    }
    hdr.appendLine(");")
    hdr.appendLine()

    // Companion/nested objects get an inline banner in the .c file (parent's banner comes from generate())
    if (d.name.contains('$')) {
        impl.appendLine(cSourceFileHeader(vKind, vDisplayName, vPkg, cName, currentSourceFile))
        impl.appendLine()
    }

    // global instance (zero-initialized), thread-once control block
    impl.appendLine("${vTls}${cName}_t $cName = {0};")
    impl.appendLine("static ktc_thread_once_t ${cName}\$once = KTC_THREAD_ONCE_INIT;")
    impl.appendLine()

    // static init function — body run exactly once across all threads
    val prevObject = currentObject
    currentObject = d.name
    pushScope()
    for (p in props) {
        defineVarKtc(p.name, resolveTypeName(p.type ?: inferInitType(p.init)))
        if (p.mutable) markMutable(p.name)
    }
    impl.appendLine("static void ${cName}_init(void) {")
    impl.appendLine("    $cName.__base.typeId = ${cName}_TYPE_ID;")
    for (p in props) {
        if (p.init != null) {
            val pType       = p.type ?: inferInitType(p.init)
            val vKtcObjInit = resolveTypeName(pType)                   // KtcType for array checks
            val sizeAnn     = pType.getSizeAnnotation()
            heapAllocTargetType = pType
            val expr        = genExpr(p.init)
            heapAllocTargetType = null
            flushPreStmts("    ")
            if (vKtcObjInit.isArrayLike && sizeAnn != null) {
                val vElemType = cTypeStr(vKtcObjInit.asArr!!.elem)     // element C type for sized array
                val fn = privPrefix(p) + p.name
                impl.appendLine("    memcpy($cName.$fn, $expr, $sizeAnn * sizeof($vElemType));")
            } else {
                val fn = privPrefix(p) + p.name
                impl.appendLine("    $cName.$fn = $expr;")
            }
        }
    }
    for (ib in initBlocks) {
        if (ib.body != null) for (s in ib.body.stmts) emitStmt(s, "    ")
    }
    popScope()
    currentObject = prevObject
    impl.appendLine("}")
    impl.appendLine()

    // $ensure_init: thread-safe lazy initializer — declared in header so other TUs can call it
    hdr.appendLine("void ${cName}_\$ensure_init(void);")
    impl.appendLine("void ${cName}_\$ensure_init(void) {")
    impl.appendLine("    ktc_thread_call_once(&${cName}\$once, ${cName}_init);")
    impl.appendLine("}")
    impl.appendLine()

    // Methods are split into three groups, each landing in its own source section:
    //   - interface overrides  → "implements X" section (one per interface)
    //   - Any overrides        → "implements Any" section
    //   - everything else      → "methods" section
    val vAnyMethodNames  = setOf("toString", "hashCode", "dispose")
    val vIfaceMethodNames = d.superInterfaces.flatMap { ref ->
        val vN = resolveIfaceName(ref)
        val vI = interfaces[vN] ?: return@flatMap emptyList<String>()
        collectAllIfaceMethods(vI).map { it.name }
    }.toSet()
    val vRegularMethods = methods.filter { it.name !in vAnyMethodNames && it.name !in vIfaceMethodNames }
    val vAnyOverrides   = methods.filter { it.name in vAnyMethodNames }
    val vHasToString = vAnyOverrides.any { it.name == "toString" }
    val vHasHashCode = vAnyOverrides.any { it.name == "hashCode" }
    val vHasDispose  = vAnyOverrides.any { it.name == "dispose" }

    // Regular (non-Any) methods section
    if (vRegularMethods.isNotEmpty()) {
        impl.appendLine(boxSection("methods"))
        impl.appendLine()
    }
    val prevObjectForMethods = currentObject
    currentObject = d.name

    /* Shared body for emitting one object method — used for both regular and any-override passes.
    inHdrLines: if non-null, collect hdr declaration strings instead of writing to hdr directly. */
    fun emitOneObjMethod(m: FunDecl, inHdrLines: MutableList<String>?) {
        if (m.isInline) return  // expanded at call sites via inlineFunDecls, not emitted as C functions
        val returnsSizedArray  = m.returnType != null && m.returnType.isSizedArray()
        val returnsSizedString = m.returnType != null && m.returnType.isSizedString()
        val vRetKtcM           = if (m.returnType != null) resolveTypeName(m.returnType) else null
        val returnsArray       = !returnsSizedArray && (vRetKtcM?.isArrayLike ?: false)
        val retResolved        = vRetKtcM?.toInternalStr ?: ""
        val cRet = when {
            returnsSizedArray  -> sizedArrayCTypeName(cTypeStr(vRetKtcM!!.asArr!!.elem), m.returnType.getSizeAnnotation()!!)
            returnsSizedString -> sizedStringCTypeName(m.returnType.getSizeAnnotation()!!)
            retResolved.isNotEmpty() -> cTypeStr(retResolved)
            else -> "void"
        }
        val overloadedName = methodName(m, methods)
        val fnName = if (m.isPrivate) "PRIV_$overloadedName" else overloadedName
        val baseParams = expandParams(m.params)
        val extraParam = when {
            returnsArray -> "ktc_Int* \$len_out"
            else -> null
        }
        val params = if (extraParam != null) {
            if (baseParams.isEmpty()) extraParam else "$baseParams, $extraParam"
        } else baseParams
        if (m.isPrivate) {
            impl.appendLine("$cRet ${cName}_$fnName($params);")
        } else {
            val vParamsOrVoid = params.ifEmpty { "void" }
            val vHdrLine = "KTC_METHOD($cRet, $fnName)($vParamsOrVoid);"
            if (inHdrLines != null) inHdrLines += vHdrLine else hdr.appendLine(vHdrLine)
        }
        val vRetSuffix = if (m.returnType != null) ": ${typeRefToStr(m.returnType)}" else ""
        impl.appendLine("// ══ fun ${m.name}()$vRetSuffix ══")
        impl.appendLine("$cRet ${cName}_$fnName($params) {")
        impl.appendLine("    ${cName}_\$ensure_init();")
        val prevState = saveFunState()
        val prevObjectM = currentObject
        currentFnReturnsArray      = returnsArray
        currentFnReturnsSizedArray = returnsSizedArray
        currentFnReturnType        = retResolved
        currentFnReturnKtcType     = vRetKtcM
        if (returnsSizedArray) {
            currentFnSizedArraySize     = m.returnType.getSizeAnnotation()!!
            currentFnSizedArrayElemType = cTypeStr(vRetKtcM!!.asArr!!.elem)
        }
        currentFnReturnsSizedString = returnsSizedString
        if (returnsSizedString) currentFnSizedStringSize = m.returnType.getSizeAnnotation()!!
        pushScope()
        for (p in props) {
            defineVarKtc(p.name, resolveTypeName(p.type ?: inferInitType(p.init)))
            if (p.mutable) markMutable(p.name)
        }
        for (p in m.params) {
            val vKtcObjParam = resolveTypeName(p.type)
            defineVar(p.name, if (p.isVararg) "${vKtcObjParam.toInternalStr}Array" else vKtcObjParam.toInternalStr)
        }
        val savedTrampolined      = trampolinedParams.toHashSet(); trampolinedParams.clear()
        val savedSizedTrampolined = sizedArrayTrampolinedParams.toHashSet(); sizedArrayTrampolinedParams.clear()
        emitArrayParamCopies(m.params, "    ")
        val savedDefers = deferStack.toList(); deferStack.clear()
        if (m.body != null) for (s in m.body.stmts) emitStmt(s, "    ")
        if (m.body?.stmts?.lastOrNull() !is ReturnStmt) emitDeferredBlocks("    ")
        deferStack.clear(); deferStack.addAll(savedDefers)
        trampolinedParams.clear(); trampolinedParams.addAll(savedTrampolined)
        sizedArrayTrampolinedParams.clear(); sizedArrayTrampolinedParams.addAll(savedSizedTrampolined)
        popScope()
        restoreFunState(prevState)
        currentObject = prevObjectM
        impl.appendLine("}")
        impl.appendLine()
    }

    for (m in vRegularMethods) emitOneObjMethod(m, null)

    // Buffer Any-override impl bodies and collect their hdr declarations.
    // impl is temporarily redirected so bodies land in the "implements Any" section later.
    val vAnyOverrideHdrLines = mutableListOf<String>()
    val vAnyOverrideImplBuf  = StringBuilder()
    val vSavedImpl = impl
    impl = vAnyOverrideImplBuf
    for (m in vAnyOverrides) emitOneObjMethod(m, vAnyOverrideHdrLines)
    impl = vSavedImpl

    // Buffer interface method bodies per interface — consumed by the implsOnly pass in
    // emitInterfaceVtablesForClass so they appear inside "implements X" in the .c file.
    for (ifaceRef in d.superInterfaces) {
        val vIfaceKey = resolveIfaceName(ifaceRef)
        val vIface = interfaces[vIfaceKey] ?: continue
        val vIfaceMethodSet = collectAllIfaceMethods(vIface).map { it.name }.toSet()
        val vImplMethods = methods.filter { it.name in vIfaceMethodSet }
        if (vImplMethods.isNotEmpty()) {
            val vBuf = StringBuilder()
            val vSaved2 = impl
            impl = vBuf
            for (m in vImplMethods) emitOneObjMethod(m, null)
            impl = vSaved2
            deferredObjIfaceMethods[Pair(d.name, vIfaceKey)] = vBuf
        }
    }

    currentObject = prevObjectForMethods

    // Interface implementation sections — inside the object block, before #undef KTC_TYPE_NAME
    if (d.superInterfaces.isNotEmpty()) {
        emitInterfaceVtablesForClass(d.name, d.superInterfaces, declsOnly = true)
    }

    // Header: "implements Any" — overrides first, then auto-generated, then equals/copyWith
    hdr.appendLine()
    hdr.appendLine("// ════ implements Any (implicit) ════")
    for (vLine in vAnyOverrideHdrLines) hdr.appendLine(vLine)
    if (!vHasToString) {
        val vMaxLen = toStringMaxLen(d.name)
        val vMaxComment = if (vMaxLen != null) " // max output: $vMaxLen chars" else ""
        hdr.appendLine("KTC_METHOD(void, toString)(${cName}_t* \$self, ktc_StrBuf* sb);${vMaxComment}")
    }
    if (!vHasHashCode) hdr.appendLine("KTC_METHOD(ktc_Int, hashCode)(void);")
    if (!vHasDispose) {
        if (disposedMode != "NO" || doubleDisposeMode != "NO") hdr.appendLine("KTC_METHOD(void, dispose)(void);")
        else hdr.appendLine("#define ${cName}_dispose() ((void)0)")
    }
    hdr.appendLine("KTC_METHOD(ktc_Bool, equals)(${cName}_t* \$self, void* other);")
    hdr.appendLine("KTC_METHOD(void*, copyWith)(${cName}_t* \$self, void* alloc);")

    hdr.appendLine()
    hdr.appendLine("// ════ Any cast ════")
    hdr.appendLine("extern const ktc_core_AnyVt KTC_RELATED(AnyVt);")
    hdr.appendLine("ktc_Any ${cName}_as_Any(${cName}_t* \$self);")

    hdr.appendLine()
    hdr.appendLine("#undef KTC_TYPE_NAME")
    hdr.appendLine(classBlockFooter(vKind, vDisplayName, emptyList()))

    // Impl: "implements Any" section — overrides first, then auto-generated
    val vDisplaySimple = d.name.substringAfterLast('$')
    impl.appendLine(boxSection("implements Any (implicit)"))
    impl.appendLine()

    impl.append(vAnyOverrideImplBuf)  // user-defined Any overrides (toString/hashCode/dispose)

    if (!vHasToString) {
        impl.appendLine("// ══ fun toString() ══")
        impl.appendLine("void ${cName}_toString(${cName}_t* \$self, ktc_StrBuf* sb) {")
        impl.appendLine("    (void)\$self;")
        impl.appendLine("    ${cName}_\$ensure_init();")
        val vMaxLen = toStringMaxLen(d.name)
        if (vMaxLen != null) {
            impl.appendLine("    ktc_Char buf[$vMaxLen];")
            impl.appendLine("    snprintf(buf, $vMaxLen, \"%s@%x\", \"$vDisplaySimple\", ${cName}_hashCode());")
            impl.appendLine("    ktc_core_sb_append_cstr(sb, buf);")
        } else {
            impl.appendLine("    ktc_Char buf[64];")
            impl.appendLine("    snprintf(buf, 64, \"%s@%x\", \"$vDisplaySimple\", ${cName}_hashCode());")
            impl.appendLine("    ktc_core_sb_append_cstr(sb, buf);")
        }
        impl.appendLine("}")
        impl.appendLine()
    }
    if (!vHasHashCode) {
        impl.appendLine("// ══ fun hashCode(): Int ══")
        impl.appendLine("ktc_Int ${cName}_hashCode() {")
        impl.appendLine("    ${cName}_\$ensure_init();")
        impl.appendLine("    return (ktc_Int)${cName}_TYPE_ID;")
        impl.appendLine("}")
        impl.appendLine()
    }
    if (!vHasDispose && (disposedMode != "NO" || doubleDisposeMode != "NO")) {
        impl.appendLine("// ══ fun dispose() ══")
        impl.appendLine("void ${cName}_dispose() {")
        impl.appendLine("    ${cName}_\$ensure_init();")
        impl.appendLine("    KTC_MARK_DISPOSED(&$cName);")
        impl.appendLine("}")
        impl.appendLine()
    }
    impl.appendLine("// ══ fun equals() ══")
    impl.appendLine("ktc_Bool ${cName}_equals(${cName}_t* \$self, void* other) {")
    impl.appendLine("    return (void*)\$self == other;")
    impl.appendLine("}")
    impl.appendLine()
    impl.appendLine("// ══ fun copyWith() ══")
    impl.appendLine("void* ${cName}_copyWith(${cName}_t* \$self, void* alloc) {")
    impl.appendLine("    (void)alloc;")
    impl.appendLine("    return \$self;")
    impl.appendLine("}")
    impl.appendLine()

    // cast to Any — thin wrappers delegating to the public Any methods above
    impl.appendLine(boxSection("cast to Any"))
    impl.appendLine()

    impl.appendLine("static void ${cName}_toString_any(void* \$self, ktc_StrBuf* sb) {")
    if (vHasToString) {
        impl.appendLine("    (void)\$self;")
        impl.appendLine("    ktc_String s = ${cName}_toString();")
        impl.appendLine("    ktc_core_sb_append_str(sb, s);")
    } else {
        impl.appendLine("    ${cName}_toString((${cName}_t*)\$self, sb);")
    }
    impl.appendLine("}")
    impl.appendLine()
    impl.appendLine("static ktc_Int ${cName}_hashCode_any(void* \$self) {")
    impl.appendLine("    (void)\$self;")
    impl.appendLine("    return ${cName}_hashCode();")
    impl.appendLine("}")
    impl.appendLine()
    impl.appendLine("static ktc_Bool ${cName}_equals_any(void* \$self, void* other) {")
    impl.appendLine("    return ${cName}_equals((${cName}_t*)\$self, other);")
    impl.appendLine("}")
    impl.appendLine()
    impl.appendLine("static void ${cName}_dispose_any(void* \$self) {")
    impl.appendLine("    (void)\$self;")
    impl.appendLine("    ${cName}_dispose();")
    impl.appendLine("}")
    impl.appendLine()
    impl.appendLine("static void* ${cName}_copyWith_any(void* \$self, void* alloc) {")
    impl.appendLine("    return ${cName}_copyWith((${cName}_t*)\$self, alloc);")
    impl.appendLine("}")
    impl.appendLine()

    impl.appendLine("const ktc_core_AnyVt ${cName}_AnyVt = {")
    impl.appendLine("    (void (*)(void*, void*)) ${cName}_toString_any,")
    impl.appendLine("    (ktc_Int (*)(void*)) ${cName}_hashCode_any,")
    impl.appendLine("    (ktc_Bool (*)(void*, void*)) ${cName}_equals_any,")
    impl.appendLine("    (void (*)(void*)) ${cName}_dispose_any,")
    impl.appendLine("    (void* (*)(void*, void*)) ${cName}_copyWith_any,")
    impl.appendLine("};")
    impl.appendLine()
    impl.appendLine("ktc_Any ${cName}_as_Any(${cName}_t* \$self) {")
    impl.appendLine("    return (ktc_Any){{.typeId = ${cName}_TYPE_ID}, (void*)\$self, &${cName}_AnyVt};")
    impl.appendLine("}")
    impl.appendLine()

    // Nested class implementations follow the object's own body
    if (vNestedClassImpl.isNotEmpty()) impl.append(vNestedClassImpl)
}
