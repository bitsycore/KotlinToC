package com.bitsycore.ktc.codegen

import com.bitsycore.ktc.ast.*
import com.bitsycore.ktc.types.KtcType

// ──────────────────────────────────────────────────────────
// class / data class — struct, ctor, methods, hashCode, toString
// ──────────────────────────────────────────────────────────

internal fun CCodeGen.emitClass(d: ClassDecl) {
    val ci = classes[d.name]!!
    val cName = ci.flatName
    val kind = if (d.isData) "data class" else "class"
    val vOptName = "${cName}\$Opt"

    // Block header comment + KTC_TYPE_NAME defines + struct
    hdr.appendLine(classBlockHeader(kind, d.name.replace('$', '.'), d.typeParams, d.superInterfaces, file.pkg ?: "", currentSourceFile, cName))
    hdr.appendLine("#define KTC_TYPE_NAME $cName")
    hdr.appendLine("#define KTC_OPT_TYPE_NAME $vOptName")
    hdr.appendLine("KTC_TYPE_ID(${typeIds[d.name]!!})")
    hdr.appendLine()
    hdr.appendLine("KTC_CLASS(")
    emitStructFields(ci)
    hdr.appendLine(");")
    hdr.appendLine()

    // Nested classes get an inline banner in the .c file (parent's banner comes from generate())
    if (d.name.contains('$')) {
        impl.appendLine(cSourceFileHeader(kind, d.name.replace('$', '.'), file.pkg ?: "", cName, currentSourceFile))
        impl.appendLine()
    }

    // Constructor section
    hdr.appendLine("// ════ constructors ════")
    impl.appendLine(boxSection("constructors"))
    impl.appendLine()
    emitConstructorBody(cName, ci)

    // Secondary constructors (also in constructor section)
    for (vSctor in d.secondaryCtors) emitSecondaryCtor(d.name, cName, vSctor)

    val vAnyMethodNames = setOf("dispose", "toString", "hashCode")

    // Build map: method name → interface display string, and ordered list of interfaces
    val vIfaceMethodToStr = mutableMapOf<String, String>()
    val vIfaceOrder = mutableListOf<String>()
    fun collectMethodsPerIface(ifaceRef: TypeRef) {
        val vIfaceName = resolveIfaceName(ifaceRef)
        val vIface = interfaces[vIfaceName] ?: return
        val vIfaceStr = typeRefToStr(ifaceRef)
        if (vIfaceStr !in vIfaceOrder) vIfaceOrder += vIfaceStr
        for (m in vIface.methods) if (m.name !in vIfaceMethodToStr) vIfaceMethodToStr[m.name] = vIfaceStr
        for (p in vIface.propDecls) if (p.name !in vIfaceMethodToStr) vIfaceMethodToStr[p.name] = vIfaceStr
        for (superRef in vIface.superInterfaces) collectMethodsPerIface(superRef)
    }
    for (vIfaceRef in d.superInterfaces) collectMethodsPerIface(vIfaceRef)

    // Group non-Any methods by interface (preserving declaration order within each group)
    val vMethodsByIface = linkedMapOf<String, MutableList<FunDecl>>()
    for (m in d.members) {
        if (m is FunDecl && m.receiver == null && m.name !in vAnyMethodNames)
            vMethodsByIface.getOrPut(vIfaceMethodToStr[m.name] ?: "") { mutableListOf() } += m
    }

    currentClass = d.name
    selfIsPointer = true
    pushScope()
    for ((name, type) in ci.props) {
        defineVarKtc(name, resolveTypeName(type))
        if (!ci.isValProp(name)) markMutable(name)
    }

    // Emit per-interface method sections in interface declaration order.
    // Only the FIRST section adds a leading blank; subsequent sections rely on the
    // trailing blank left by the previous emitMethod call to avoid double blanks.
    var vHasMethodSection = false
    for (vIfaceStr in vIfaceOrder) {
        val vMethods = vMethodsByIface[vIfaceStr] ?: continue
        if (!vHasMethodSection) impl.appendLine()
        impl.appendLine(boxSection("implements $vIfaceStr"))
        impl.appendLine()
        for (m in vMethods) emitMethod(d.name, m, suppressHdr = true, ifaceName = vIfaceStr)
        vHasMethodSection = true
    }
    // Emit non-interface methods in a "methods" section
    val vOtherMethods = vMethodsByIface[""] ?: emptyList()
    if (vOtherMethods.isNotEmpty()) {
        if (!vHasMethodSection) impl.appendLine()
        impl.appendLine(boxSection("methods"))
        impl.appendLine()
        for (m in vOtherMethods) emitMethod(d.name, m, suppressHdr = false, ifaceName = "")
        vHasMethodSection = true
    }
    popScope()
    currentClass = null

    // Deferred interface method declarations + vtable extern + as_ cast (per-interface sections)
    val vDeferredLines = deferredHdrLines.remove(d.name)
    if (d.superInterfaces.isNotEmpty()) {
        val vByIface = vDeferredLines?.groupBy { it.first } ?: emptyMap()
        for (vIfaceRef in d.superInterfaces) {
            val vIfaceName = resolveIfaceName(vIfaceRef)
            val vIface = interfaces[vIfaceName] ?: continue
            val vIfaceStr = typeRefToStr(vIfaceRef)
            val cIface = typeFlatName(vIfaceName)
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

    // Implements Any section — also emits explicit dispose/toString/hashCode overrides.
    // Only add leading blank if no prior method sections (those already end with a blank).
    hdr.appendLine()
    hdr.appendLine("// ════ implements Any (implicit) ════")
    if (!vHasMethodSection) impl.appendLine()
    impl.appendLine(boxSection("implements Any (implicit)"))
    impl.appendLine()
    emitClassEquals(cName, ci)
    if (d.isData) emitDataClassToString(d.name, cName, ci)
    if (d.members.none { it is FunDecl && it.name == "dispose" }) {
        if (disposedMode != "NO" || doubleDisposeMode != "NO") {
            hdr.appendLine("KTC_METHOD(void, dispose)(KTC_TYPE_NAME* \$self);")
            impl.appendLine("// ══ fun dispose() ══")
            impl.appendLine("void ${cName}_dispose($cName* \$self) { KTC_MARK_DISPOSED(\$self); }")
            impl.appendLine()
        } else {
            hdr.appendLine("#define ${cName}_dispose(self) ((void)(self))")
        }
    }
    emitImplicitHashCode(cName, ci, d.isData, isGenericClass = false, d.members)
    if (!d.isData && d.members.none { it is FunDecl && it.name == "toString" }) {
        emitDefaultToString(d.name, cName, ci)
    }
    // Emit explicit overrides of Any methods here (skipped in the methods loop above)
    pushScope()
    for ((name, type) in ci.props) {
        defineVarKtc(name, resolveTypeName(type))
        if (!ci.isValProp(name)) markMutable(name)
    }
    currentClass = d.name
    selfIsPointer = true
    for (m in d.members) {
        if (m is FunDecl && m.receiver == null && m.name in vAnyMethodNames) {
            emitMethod(d.name, m, suppressHdr = false, ifaceName = "")
        }
    }
    currentClass = null
    popScope()

    // Any cast section
    hdr.appendLine()
    hdr.appendLine("// ════ Any cast ════")
    emitAnyVtable(cName, ci.name, d.isData, d.members, isGenericClass = false)

    hdr.appendLine()
    hdr.appendLine("#undef KTC_TYPE_NAME")
    hdr.appendLine("#undef KTC_OPT_TYPE_NAME")
    hdr.appendLine(classBlockFooter(kind, d.name.replace('$', '.'), d.typeParams))
}

/** Generate a secondary constructor function name: ClassName_constructorWithType1_Type2 */
internal fun CCodeGen.secondaryCtorName(inCClass: String, inParams: List<Param>): String {
    if (inParams.isEmpty()) return "${inCClass}_emptyConstructor"
    val vTypes = inParams.map { resolveTypeName(it.type).toInternalStr.removeSuffix("*") }  // type name strings without pointer suffix
    return "${inCClass}_constructorWith${vTypes.joinToString("_")}"
}

/** Emit a secondary constructor that delegates to the primary constructor. */
internal fun CCodeGen.emitSecondaryCtor(className: String, cClass: String, sctor: SecondaryCtor) {
    val ctorName = secondaryCtorName(cClass, sctor.params)
    val extraParams = expandParams(sctor.params)

    hdr.appendLine("$cClass $ctorName($extraParams);")  // full name — KTC_METHOD would double-prefix
    impl.appendLine("$cClass $ctorName($extraParams) {")

    // Generate call to primary constructor for delegation
    // genExpr may accumulate preStmts (e.g. HeapAlloc tmp vars) — flush them first.
    val delegateArgs = sctor.delegation.args.joinToString(", ") { a -> genExpr(a.expr) }
    flushPreStmts("    ")
    impl.appendLine("    $cClass \$self = ${cClass}_primaryConstructor($delegateArgs);")

    // Emit body using $self as the implicit receiver
    pushScope()
    currentClass = className
    selfIsPointer = true
    for (p in sctor.params) {
        defineVarKtc(p.name, resolveTypeName(p.type))
    }
    val ci = classes[className]
    if (ci != null) for ((name, type) in ci.props) defineVarKtc(name, resolveTypeName(type))

    for (s in sctor.body.stmts) emitStmt(s, "    ", true)
    popScope()
    currentClass = null

    impl.appendLine("    return \$self;")
    impl.appendLine("}")
    impl.appendLine()
}

/*
Emit a concrete instantiation of a generic class.
typeSubst must be set before calling (e.g. {T → Int}).
[mangledName] is the concrete class name (e.g. "MyList_Int").
*/
internal fun CCodeGen.emitGenericClass(templateDecl: ClassDecl, mangledName: String) {
    val ci = classes[mangledName]!!
    val cName = ci.flatName
    val kind = if (templateDecl.isData) "data class" else "class"
    val (vGenBase, vTypeArgs) = mangledComponents[mangledName]!!
    val vGenOptName = genericOptionalCName(vGenBase, vTypeArgs)
    val vConcreteTypes = vTypeArgs.joinToString(", ")

    // Block header comment + KTC_TYPE_NAME defines + struct
    hdr.appendLine(classBlockHeader(kind, "${templateDecl.name.replace('$', '.')}<$vConcreteTypes>",
        emptyList(), templateDecl.superInterfaces, file.pkg ?: "", currentSourceFile, cName))
    hdr.appendLine("#define KTC_TYPE_NAME $cName")
    hdr.appendLine("#define KTC_OPT_TYPE_NAME $vGenOptName")
    hdr.appendLine("KTC_TYPE_ID(${typeIds[ci.name]!!})")
    hdr.appendLine()
    hdr.appendLine("KTC_CLASS(")
    emitStructFields(ci)
    hdr.appendLine(");")
    hdr.appendLine()

    // Constructor section
    hdr.appendLine("// ════ constructors ════")
    impl.appendLine(boxSection("constructors"))
    impl.appendLine()
    emitConstructorBody(cName, ci)

    // Secondary constructors
    for (vSctor in templateDecl.secondaryCtors) emitSecondaryCtor(mangledName, cName, vSctor)

    val vAnyMethodNamesGen = setOf("dispose", "toString", "hashCode")

    // Build map: method name → interface display string, and ordered list of interfaces
    val vIfaceMethodToStrGen = mutableMapOf<String, String>()
    val vIfaceOrderGen = mutableListOf<String>()
    fun collectMethodsPerIfaceGen(ifaceRef: TypeRef) {
        val vIfaceName = resolveIfaceName(ifaceRef)
        val vIface = interfaces[vIfaceName] ?: return
        val vIfaceStr = typeRefToStr(ifaceRef)
        if (vIfaceStr !in vIfaceOrderGen) vIfaceOrderGen += vIfaceStr
        for (m in vIface.methods) if (m.name !in vIfaceMethodToStrGen) vIfaceMethodToStrGen[m.name] = vIfaceStr
        for (p in vIface.propDecls) if (p.name !in vIfaceMethodToStrGen) vIfaceMethodToStrGen[p.name] = vIfaceStr
        for (superRef in vIface.superInterfaces) collectMethodsPerIfaceGen(superRef)
    }
    for (vIfaceRef in templateDecl.superInterfaces) collectMethodsPerIfaceGen(vIfaceRef)

    // Group non-Any methods by interface (preserving declaration order within each group)
    val vMethodsByIfaceGen = linkedMapOf<String, MutableList<FunDecl>>()
    for (m in templateDecl.members) {
        if (m is FunDecl && m.receiver == null && m.name !in vAnyMethodNamesGen)
            vMethodsByIfaceGen.getOrPut(vIfaceMethodToStrGen[m.name] ?: "") { mutableListOf() } += m
    }

    currentClass = mangledName
    selfIsPointer = true
    pushScope()
    for ((name, type) in ci.props) {
        defineVarKtc(name, resolveTypeName(type))
        if (!ci.isValProp(name)) markMutable(name)
    }

    // Emit per-interface method sections in interface declaration order.
    // Only the FIRST section adds a leading blank; subsequent sections rely on the
    // trailing blank left by the previous emitMethod call to avoid double blanks.
    var vHasMethodSectionGen = false
    for (vIfaceStr in vIfaceOrderGen) {
        val vMethods = vMethodsByIfaceGen[vIfaceStr] ?: continue
        if (!vHasMethodSectionGen) impl.appendLine()
        impl.appendLine(boxSection("implements $vIfaceStr"))
        impl.appendLine()
        for (m in vMethods) emitMethod(mangledName, m, suppressHdr = true, ifaceName = vIfaceStr)
        vHasMethodSectionGen = true
    }
    // Emit non-interface methods in a "methods" section
    val vOtherMethodsGen = vMethodsByIfaceGen[""] ?: emptyList()
    if (vOtherMethodsGen.isNotEmpty()) {
        if (!vHasMethodSectionGen) impl.appendLine()
        impl.appendLine(boxSection("methods"))
        impl.appendLine()
        for (m in vOtherMethodsGen) emitMethod(mangledName, m, suppressHdr = false, ifaceName = "")
        vHasMethodSectionGen = true
    }
    popScope()
    currentClass = null

    // Deferred interface method declarations + vtable extern + as_ cast (per-interface sections)
    val vGenDeferredLines = deferredHdrLines.remove(mangledName)
    if (templateDecl.superInterfaces.isNotEmpty()) {
        val vByIface = vGenDeferredLines?.groupBy { it.first } ?: emptyMap()
        for (vIfaceRef in templateDecl.superInterfaces) {
            val vIfaceName = resolveIfaceName(vIfaceRef)
            val vIface = interfaces[vIfaceName] ?: continue
            val vIfaceStr = typeRefToStr(vIfaceRef)
            val cIface = typeFlatName(vIfaceName)
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

    // Implements Any section — also emits explicit dispose/toString/hashCode overrides.
    // Only add leading blank if no prior method sections (those already end with a blank).
    hdr.appendLine()
    hdr.appendLine("// ════ implements Any (implicit) ════")
    if (!vHasMethodSectionGen) impl.appendLine()
    impl.appendLine(boxSection("implements Any"))
    impl.appendLine()
    if (templateDecl.members.none { it is FunDecl && it.name == "dispose" }) {
        if (disposedMode != "NO" || doubleDisposeMode != "NO") {
            hdr.appendLine("KTC_METHOD(void, dispose)(KTC_TYPE_NAME* \$self);")
            impl.appendLine("// ══ fun dispose() ══")
            impl.appendLine("void ${cName}_dispose($cName* \$self) { KTC_MARK_DISPOSED(\$self); }")
            impl.appendLine()
        } else {
            hdr.appendLine("#define ${cName}_dispose(self) ((void)(self))")
        }
    }
    emitImplicitHashCode(cName, ci, templateDecl.isData, isGenericClass = true, templateDecl.members)
    if (templateDecl.members.none { it is FunDecl && it.name == "equals" }) {
        emitClassEquals(cName, ci)
    }
    if (templateDecl.isData && templateDecl.members.none { it is FunDecl && it.name == "toString" }) {
        emitDataClassToString(templateDecl.name, cName, ci)
    }
    if (!templateDecl.isData && templateDecl.members.none { it is FunDecl && it.name == "toString" }) {
        emitDefaultToString(ci.name, cName, ci)
    }
    // Emit explicit overrides of Any methods here (skipped in the methods loop above)
    pushScope()
    for ((name, type) in ci.props) {
        defineVarKtc(name, resolveTypeName(type))
        if (!ci.isValProp(name)) markMutable(name)
    }
    currentClass = mangledName
    selfIsPointer = true
    for (m in templateDecl.members) {
        if (m is FunDecl && m.receiver == null && m.name in vAnyMethodNamesGen) {
            emitMethod(mangledName, m, suppressHdr = false, ifaceName = "")
        }
    }
    currentClass = null
    popScope()

    // Any cast section
    hdr.appendLine()
    hdr.appendLine("// ════ Any cast ════")
    emitAnyVtable(cName, ci.name, templateDecl.isData, templateDecl.members, isGenericClass = true)

    hdr.appendLine()
    hdr.appendLine("#undef KTC_TYPE_NAME")
    hdr.appendLine("#undef KTC_OPT_TYPE_NAME")
    hdr.appendLine(classBlockFooter(kind, templateDecl.name.replace('$', '.'), vTypeArgs.map { it }))
}

internal fun CCodeGen.emitClassEquals(cName: String, ci: ClassInfo) {
    hdr.appendLine("KTC_METHOD(ktc_Bool, equals)(KTC_TYPE_NAME a, KTC_TYPE_NAME b);")
    impl.appendLine("// ══ fun equals ══")
    impl.appendLine("ktc_Bool ${cName}_equals($cName a, $cName b) {")
    val eqs = ci.props.filter { (_, type) ->
        // Skip @Ptr interface fields — ktc_IfacePtr can't be compared with ==
        !(type.annotations.any { it.name == "Ptr" } && interfaces.containsKey(type.name))
    }.joinToString(" && ") { (name, type) ->
        val fieldName = if (name in ci.privateProps) "PRIV_$name" else name
        val vKtcEq = resolveTypeName(type)          // KtcType for equals dispatch
        val vTStr  = vKtcEq.toInternalStr            // string for class lookup
        when {
            type.nullable -> {
                val vInnerName = type.name
                val vValueCmp = when {
                    vInnerName == "String" -> "ktc_core_string_eq(KTC_UNWRAP(a.$fieldName), KTC_UNWRAP(b.$fieldName))"
                    classes[vInnerName]?.isData == true -> "${typeFlatName(vInnerName)}_equals(KTC_UNWRAP(a.$fieldName), KTC_UNWRAP(b.$fieldName))"
                    else -> "KTC_UNWRAP(a.$fieldName) == KTC_UNWRAP(b.$fieldName)"
                }
                "(KTC_IS_SOME(a.$fieldName) == KTC_IS_SOME(b.$fieldName) && (KTC_IS_NONE(a.$fieldName) || $vValueCmp))"
            }
            vTStr == "String" -> "ktc_core_string_eq(a.$fieldName, b.$fieldName)"
            classes[vTStr]?.isData == true -> "${typeFlatName(vTStr)}_equals(a.$fieldName, b.$fieldName)"
            else -> "a.$fieldName == b.$fieldName"
        }
    }
    impl.appendLine("    return ${eqs.ifEmpty { "true" }};")
    impl.appendLine("}")
    impl.appendLine()
}

internal fun CCodeGen.emitDataClassToString(ktName: String, cName: String, ci: ClassInfo) {
    val maxLen = toStringMaxLen(ci.name)
    val maxComment = if (maxLen != null) " // max output: $maxLen chars" else ""
    hdr.appendLine("KTC_METHOD(void, toString)(KTC_TYPE_NAME* \$self, ktc_StrBuf* sb);${maxComment}")
    impl.appendLine("// ══ fun toString() ══")
    impl.appendLine("void ${cName}_toString($cName* \$self, ktc_StrBuf* sb) {")
    for ((i, prop) in ci.props.withIndex()) {
        val (name, type) = prop
        val fieldName = if (name in ci.privateProps) "PRIV_$name" else name
        val vKtcTs = resolveTypeName(type)                            // KtcType for toString dispatch
        val tFull  = if (type.nullable) vKtcTs.nullable else vKtcTs   // KtcType, wrap nullable if needed
        val prefix = if (i == 0) "$ktName($name=" else ", $name="
        impl.appendLine("    ktc_core_sb_append_str(sb, ktc_core_str(\"$prefix\"));")
        impl.appendLine("    ${genSbAppendKtc("sb", "\$self->$fieldName", tFull)}")
    }
    impl.appendLine("    ktc_core_sb_append_char(sb, ')');")
    impl.appendLine("}")
    impl.appendLine()
}

internal fun CCodeGen.emitMethod(className: String, f: FunDecl, suppressHdr: Boolean = false, ifaceName: String = "") {
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
    val vRetKtc     = if (f.returnType != null) resolveTypeName(f.returnType) else null  // KtcType of return, or null
    val retResolved = vRetKtc?.toInternalStr ?: f.body?.let { inferBlockType(it) } ?: "" // string for legacy helpers
    val optRetCType = if (returnsNullable) optCTypeName(retResolved) else ""
    val cRet = when {
        returnsSizedArray  -> sizedArrayCTypeName(cTypeStr(vRetKtc!!.asArr!!.elem), f.returnType.getSizeAnnotation()!!)
        returnsSizedString -> sizedStringCTypeName(f.returnType.getSizeAnnotation()!!)
        returnsNullable && vRetKtc is KtcType.Any -> "ktc_Any"
        returnsNullable -> optRetCType
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
        // Private: forward decl only in .c, not in .h
        implFwd.appendLine("$cRet ${cClass}_${methodName}($allParams);")
    } else if (suppressHdr) {
        deferredHdrLines.getOrPut(className) { mutableListOf() }.add(Pair(ifaceName, vHdrSig))
    } else {
        hdr.appendLine(vHdrSig)
    }
    impl.appendLine("$cRet ${cClass}_${methodName}($allParams) {")
    val vTrackDispose = disposedMode != "NO" || doubleDisposeMode != "NO" // dispose tracking active
    if (vTrackDispose && f.name == "dispose") impl.appendLine("    KTC_MARK_DISPOSED(\$self);")
    else if (disposedMode != "NO") impl.appendLine("    KTC_ASSERT_NOT_DISPOSED(\$self);")

    val prevState = saveFunState()
    currentFnReturnsNullable   = returnsNullable
    currentFnReturnsArray      = false
    currentFnReturnsSizedArray = returnsSizedArray
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
        val vKtcParam = resolveTypeName(p.type)                     // KtcType of this parameter
        val vPStr     = vKtcParam.toInternalStr                     // string for nullable/isValueNullable checks
        defineVar(p.name, when {
            p.type.nullable -> "${vPStr}?"
            else -> vPStr
        })
        if (p.type.nullable && isValueNullableKtc(KtcType.Nullable(vKtcParam))) markOptional(p.name)
    }
    // class props accessible via self->
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

/** Emit implicit hashCode for a class. Uses field-based hash for data classes, identity hash otherwise. */
internal fun CCodeGen.emitImplicitHashCode(cName: String, ci: ClassInfo, isData: Boolean, isGenericClass: Boolean, members: List<Decl>) {
    if (members.any { it is FunDecl && it.name == "hashCode" }) return
    hdr.appendLine("KTC_METHOD(ktc_Int, hashCode)(KTC_TYPE_NAME* \$self);")
    impl.appendLine("// ══ fun hashCode(): Int ══")
    impl.appendLine("ktc_Int ${cName}_hashCode($cName* \$self) {")
    if (isData && ci.props.isNotEmpty()) {
        impl.appendLine("    ktc_Int h = 0;")
        for ((name, type) in ci.props) {
            val vKtcHash = resolveTypeName(type)
            val fieldName = if (name in ci.privateProps) "PRIV_$name" else name
            val hashExpr = if (type.nullable && vKtcHash !is KtcType.Ptr) {
                val valueExpr = "\$self->$fieldName"
                "(KTC_IS_SOME($valueExpr) ? ${hashFieldExprKtc(vKtcHash, "KTC_UNWRAP($valueExpr)")} : 0)"
            } else {
                hashFieldExprKtc(vKtcHash, "\$self->$fieldName")
            }
            impl.appendLine("    h = h * 31 + $hashExpr;")
        }
        impl.appendLine("    return h;")
    } else if (isGenericClass) {
        impl.appendLine("    uintptr_t p = (uintptr_t)\$self; p >>= 4;")
        impl.appendLine("    ktc_UInt lo = (ktc_UInt)p;")
        impl.appendLine("    ktc_UInt hi = (ktc_UInt)(p >> 32);")
        impl.appendLine("    ktc_UInt t = (ktc_UInt)\$self->__base.typeId * 0x9e3779b1U;")
        impl.appendLine("    ktc_UInt h = lo ^ hi ^ t;")
        impl.appendLine("    h = ktc_core_fmix32(h);")
        impl.appendLine("    return (ktc_Int)h;")
    } else {
        impl.appendLine("    uintptr_t x = (uintptr_t)\$self;")
        impl.appendLine("    return (ktc_Int)(x ^ (x >> 32));")
    }
    impl.appendLine("}")
    impl.appendLine()
}

/** Emit default toString for non-data classes: ClassName@hexHashCode */
internal fun CCodeGen.emitDefaultToString(ktName: String, cName: String, ci: ClassInfo) {
    val maxLen = toStringMaxLen(ci.name)
    val maxComment = if (maxLen != null) " // max output: $maxLen chars" else ""
    hdr.appendLine("KTC_METHOD(void, toString)(KTC_TYPE_NAME* \$self, ktc_StrBuf* sb);${maxComment}")
    impl.appendLine("// ══ fun toString() ══")
    impl.appendLine("void ${cName}_toString($cName* \$self, ktc_StrBuf* sb) {")
    if (maxLen != null && maxLen <= 64) {
        impl.appendLine("    ktc_Char buf[$maxLen];")
        impl.appendLine("    snprintf(buf, $maxLen, \"%s@%x\", \"${ktDisplayName(ktName)}\", ${cName}_hashCode(\$self));")
        impl.appendLine("    ktc_core_sb_append_cstr(sb, buf);")
    } else {
        impl.appendLine("    ktc_Char buf[64];")
        impl.appendLine("    snprintf(buf, 64, \"%s@%x\", \"${ktDisplayName(ktName)}\", ${cName}_hashCode(\$self));")
        impl.appendLine("    ktc_core_sb_append_cstr(sb, buf);")
    }
    impl.appendLine("}")
    impl.appendLine()
}

/**
 * Emit Any vtable + _as_Any wrapper for a class.
 * Generates thin wrapper functions (void* → ClassName*) for vtable dispatch,
 * a static ktc_core_AnyVt, and a ClassName_as_Any function.
 */
internal fun CCodeGen.emitAnyVtable(cName: String, className: String, isData: Boolean, members: List<Decl>, isGenericClass: Boolean) {
    // Thin wrapper functions for type-erased vtable dispatch
    impl.appendLine(boxSection("cast to Any"))
    impl.appendLine()
    // toString wrapper
    impl.appendLine("static void ${cName}_toString_any(void* \$self, ktc_StrBuf* sb) {")
    impl.appendLine("    ${cName}_toString(($cName*)\$self, sb);")
    impl.appendLine("}")
    impl.appendLine()
    // hashCode wrapper
    impl.appendLine("static ktc_Int ${cName}_hashCode_any(void* \$self) {")
    impl.appendLine("    return ${cName}_hashCode(($cName*)\$self);")
    impl.appendLine("}")
    impl.appendLine()
    // equals wrapper
    impl.appendLine("static ktc_Bool ${cName}_equals_any(void* \$self, void* other) {")
    impl.appendLine("    return ${cName}_equals(*($cName*)\$self, *($cName*)other);")
    impl.appendLine("}")
    impl.appendLine()
    // dispose wrapper
    if (members.none { it is FunDecl && it.name == "dispose" }) {
        impl.appendLine("static void ${cName}_dispose_any(void* \$self) {")
        if (disposedMode != "NO" || doubleDisposeMode != "NO")
            impl.appendLine("    KTC_MARK_DISPOSED(($cName*)\$self);")
        else
            impl.appendLine("    (void)\$self;")
        impl.appendLine("}")
    } else {
        impl.appendLine("static void ${cName}_dispose_any(void* \$self) {")
        impl.appendLine("    ${cName}_dispose(($cName*)\$self);")
        impl.appendLine("}")
    }
    impl.appendLine()
    // copyWith wrapper
    impl.appendLine("static void* ${cName}_copyWith_any(void* \$self, void* alloc) {")
    impl.appendLine("    ktc_std_Allocator* a = (ktc_std_Allocator*)alloc;")
    impl.appendLine("    $cName* dst = ($cName*)a->vt->allocMem(a, sizeof($cName), ${ktSrcStr()});")
    impl.appendLine("    if (dst) *dst = *($cName*)\$self;")
    impl.appendLine("    return dst;")
    impl.appendLine("}")
    impl.appendLine()

    // Static vtable
    hdr.appendLine("extern const ktc_core_AnyVt KTC_RELATED(AnyVt);")
    impl.appendLine("const ktc_core_AnyVt ${cName}_AnyVt = {")
    impl.appendLine("    (void (*)(void*, void*)) ${cName}_toString_any,")
    impl.appendLine("    (ktc_Int (*)(void*)) ${cName}_hashCode_any,")
    impl.appendLine("    (ktc_Bool (*)(void*, void*)) ${cName}_equals_any,")
    impl.appendLine("    (void (*)(void*)) ${cName}_dispose_any,")
    impl.appendLine("    (void* (*)(void*, void*)) ${cName}_copyWith_any,")
    impl.appendLine("};")
    impl.appendLine()

    // as_Any — emitted inline after AnyVt (no ordering constraint)
    hdr.appendLine("KTC_METHOD(ktc_Any, as_Any)(KTC_TYPE_NAME* \$self);")
    impl.appendLine("ktc_Any ${cName}_as_Any($cName* \$self) {")
    impl.appendLine("    return (ktc_Any){{.typeId = ${cName}_TYPE_ID}, (void*)\$self, &${cName}_AnyVt};")
    impl.appendLine("}")
    impl.appendLine()
}

/** KtcType-based overload. */
/** Emit struct field declarations (shared by emitClass and emitGenericClass). */
internal fun CCodeGen.emitStructFields(ci: ClassInfo) {
    hdr.appendLine("    ktc_core_AnyData __base;")
    for ((name, type) in ci.props)
        {
        val vFieldName = if (name in ci.privateProps) "PRIV_$name" else name  // C field name
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

/* Emit primary constructor header declaration + impl body.
Callers are expected to have already emitted the KTC_OPT_TYPE_NAME typedef and the section header. */
internal fun CCodeGen.emitConstructorBody(cName: String, ci: ClassInfo) {
    val vAllCtorParams = ci.ctorProps + ci.ctorPlainParams
    val vParamStr = expandCtorParams(vAllCtorParams)
    val vParamDecl = vParamStr.ifEmpty { "void" }
    hdr.appendLine("KTC_METHOD(KTC_TYPE_NAME, primaryConstructor)($vParamDecl);")
    impl.appendLine("$cName ${cName}_primaryConstructor($vParamDecl) {")
    if (ci.bodyProps.isEmpty() && ci.ctorPlainParams.isEmpty() && ci.ctorProps.none { resolveTypeName(it.typeRef).isArrayLike || it.typeRef.nullable }) {
        impl.appendLine("    return ($cName){{${cName}_TYPE_ID}, ${ci.ctorProps.joinToString(", ") { it.name }}};")
    } else {
        impl.appendLine("    $cName \$self = {0};")
        impl.appendLine("    \$self.__base.typeId = ${cName}_TYPE_ID;")
        for (vProp in ci.ctorProps) {
            val vName = vProp.name
            val vType = vProp.typeRef
            val vFieldName = if (vName in ci.privateProps) "PRIV_$vName" else vName
            val vKtcProp = resolveTypeName(vType)
            val vSizeAnn = vType.getSizeAnnotation()
            if (vSizeAnn != null) {
                val vElemType = cTypeStr(vKtcProp.asArr!!.elem)
                impl.appendLine("    memcpy(\$self.$vFieldName, $vName, $vSizeAnn * sizeof($vElemType));")
            } else if (vKtcProp.isArrayLike) {
                impl.appendLine("    \$self.$vFieldName = $vName;")
                impl.appendLine("    \$self.${vFieldName}\$len = ${vName}\$len;")
            } else if (vType.nullable) {
                impl.appendLine("    \$self.$vFieldName = $vName;")
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

internal fun CCodeGen.hashFieldExprKtc(ktc: KtcType, valueExpr: String): String = when (ktc) { // Nullable value types: hash tag + value (or 0 if null)
    is KtcType.Nullable if isValueNullableKtc(ktc) -> {
        "(KTC_IS_SOME($valueExpr) ? ${hashFieldExprKtc(ktc.inner, "KTC_UNWRAP($valueExpr)")} : 0)"
    }

    is KtcType.Prim -> when (ktc.kind) {
        KtcType.PrimKind.Byte -> "ktc_core_hash_i8($valueExpr)"
        KtcType.PrimKind.Short -> "ktc_core_hash_i16($valueExpr)"
        KtcType.PrimKind.Int -> "ktc_core_hash_i32($valueExpr)"
        KtcType.PrimKind.Long -> "ktc_core_hash_i64($valueExpr)"
        KtcType.PrimKind.Float -> "ktc_core_hash_f32($valueExpr)"
        KtcType.PrimKind.Double -> "ktc_core_hash_f64($valueExpr)"
        KtcType.PrimKind.Boolean -> "ktc_core_hash_bool($valueExpr)"
        KtcType.PrimKind.Char -> "ktc_core_hash_char($valueExpr)"
        KtcType.PrimKind.UByte -> "ktc_core_hash_u8($valueExpr)"
        KtcType.PrimKind.UShort -> "ktc_core_hash_u16($valueExpr)"
        KtcType.PrimKind.UInt -> "ktc_core_hash_u32($valueExpr)"
        KtcType.PrimKind.ULong -> "ktc_core_hash_u64($valueExpr)"
        KtcType.PrimKind.Rune -> "ktc_core_hash_i32($valueExpr)"
    }

    is KtcType.Str -> "ktc_core_hash_str($valueExpr)"
    is KtcType.Ptr -> "((ktc_Int)(uintptr_t)($valueExpr))"
    is KtcType.User, is KtcType.Arr, is KtcType.Nullable -> "($valueExpr).__base.typeId"
    else -> "($valueExpr).__base.typeId"
}
