package com.bitsycore.ktc.codegen

import com.bitsycore.ktc.ast.*
import com.bitsycore.ktc.types.KtcType

/**
 * ── Declaration Emission ────────────────────────────────────────────────
 *
 * Emits C structs, function signatures, and bodies for all top-level
 * Kotlin declarations: classes, interfaces, enums, objects, properties,
 * and functions (both regular and generic/monomorphized).
 *
 * ## Main entry points:
 *
 *   [emitClass]                  — class/data class struct + ctor + methods
 *   [emitGenericClass]           — monomorphized generic class instantiation
 *   [emitMethod]                 — method body with scope + defer handling
 *   [emitFun]                    — top-level fun (including main)
 *   [emitExtensionFun]           — fun Type.method() extension
 *   [emitGenericFunInstantiations] — monomorphized generic fun
 *   [emitStarExtFunInstantiations] — star-projection extension expansion
 *   [emitEnum] / [emitEnumValuesData] — enum typedef + values/valueOf
 *   [emitObject]                 — singleton object with lazy init
 *   [emitInterface]              — interface vtable struct
 *   [emitInterfaceVtablesForClass] — class → interface vtable + _as_ wrapper
 *   [emitTopProp]                — top-level property
 *
 * ## State accessed:
 *   classes, interfaces, enums, objects, funSigs, extensionFuns, classInterfaces,
 *   interfaceImplementors, typeIds, genericTypeBindings, hdr/impl/implFwd,
 *   prefix, pfx(), deferredHdrLines, classCompanions, objectsWithDispose
 *
 * ## Dependencies:
 *   Calls into CCodeGenExpr.kt  (genExpr, genSbAppend)
 *   Calls into CCodeGenStmts.kt (emitStmt)
 *   Calls into CCodeGenInfer.kt (inferBlockType)
 *   Calls into CCodeGenCTypes.kt (cType, cTypeStr, resolveTypeName, expandParams, ...)
 */

// ═══════════════════════════ Emit declarations ════════════════════

// ── class / data class ───────────────────────────────────────────

internal fun CCodeGen.emitClass(d: ClassDecl) {
    val ci = classes[d.name]!!
    val cName = ci.flatName

    val kind = if (d.isData) "data class" else "class"
    impl.appendLine("// ══ $kind ${d.name} ($currentSourceFile) ══")
    impl.appendLine()

    // --- header: typedef struct ---
    hdr.appendLine("// ══ $kind ${d.name} ($currentSourceFile) ══")
    hdr.appendLine("#define ${cName}_TYPE_ID ${typeIds[d.name]!!}")
    hdr.appendLine("typedef struct {")
    emitStructFields(ci)
    hdr.appendLine("} $cName;")
    emitConstructorBody(cName, ci)
    // --- class extras: equals (all classes), toString (data only) ---
    emitClassEquals(cName, ci)
    if (d.isData) {
        emitDataClassToString(d.name, cName, ci)
    }

    // --- methods ---
    currentClass = d.name
    selfIsPointer = true
    pushScope()
    for ((name, type) in ci.props) {
        defineVarKtc(name, resolveTypeName(type))
        if (!ci.isValProp(name)) markMutable(name)
    }
    // Build set of interface method names to suppress their hdr here (emitted under implements section)
    val ifaceMethodNames = d.superInterfaces.flatMap { ifaceRef ->
        val ifaceName = resolveIfaceName(ifaceRef)
        val iface = interfaces[ifaceName] ?: return@flatMap emptyList()
        (collectAllIfaceMethods(iface).map { it.name } + collectAllIfaceProperties(iface).map { it.name }).toSet()
    }.toSet()

    for (m in d.members) {
        if (m is FunDecl && m.receiver == null) {
            emitMethod(d.name, m, suppressHdr = m.name in ifaceMethodNames)
        }
    }
    // Implicit no-op dispose if not overridden
    if (d.members.none { it is FunDecl && it.name == "dispose" }) {
        hdr.appendLine("#define ${cName}_dispose(self) ((void)(self))")
    }
    // Implicit hashCode — default returns __type_id, data classes hash all fields
    emitImplicitHashCode(cName, ci, d.isData, isGenericClass = false, d.members)
    // Implicit toString for non-data classes (data classes already emitted above)
    if (!d.isData && d.members.none { it is FunDecl && it.name == "toString" }) {
        emitDefaultToString(d.name, cName, ci)
    }
    // Any vtable + _as_Any wrapper
    emitAnyVtable(cName, ci.name, d.isData, d.members, isGenericClass = false)
    popScope()
    currentClass = null

    // Secondary constructors
    for (sctor in d.secondaryCtors) {
        emitSecondaryCtor(d.name, cName, sctor)
    }
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

    hdr.appendLine("$cClass $ctorName($extraParams);")
    impl.appendLine("$cClass $ctorName($extraParams) {")

    // Generate call to primary constructor for delegation
    val delegateArgs = sctor.delegation.args.joinToString(", ") { a ->
        genExpr(a.expr)
    }
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

/**
 * Emit a concrete instantiation of a generic class.
 * typeSubst must be set before calling (e.g. {T → Int}).
 * [mangledName] is the concrete class name (e.g. "MyList_Int").
 */
internal fun CCodeGen.emitGenericClass(templateDecl: ClassDecl, mangledName: String) {
    val ci = classes[mangledName]!!
    val cName = ci.flatName

    val kind = if (templateDecl.isData) "data class" else "class"
    val concreteTypes = mangledName.removePrefix(templateDecl.name).removePrefix("_").replace("_", ", ")
    impl.appendLine("// ══ $kind ${templateDecl.name}<$concreteTypes> ($currentSourceFile) ══")
    impl.appendLine()

    // --- header: struct definition (forward typedef already emitted) ---
    hdr.appendLine("// ══ $kind ${templateDecl.name}<$concreteTypes> ($currentSourceFile) ══")
    hdr.appendLine("#define ${cName}_TYPE_ID ${typeIds[ci.name]!!}")
    hdr.appendLine("struct $cName {")
    emitStructFields(ci)
    hdr.appendLine("};")
    emitConstructorBody(cName, ci)
    // --- methods (from template AST, but with typeSubst active) ---
    currentClass = mangledName
    selfIsPointer = true
    pushScope()
    for ((name, type) in ci.props) {
        defineVarKtc(name, resolveTypeName(type))
        if (!ci.isValProp(name)) markMutable(name)
    }
    for (m in templateDecl.members) {
        if (m is FunDecl && m.receiver == null) emitMethod(mangledName, m)
    }
    // Implicit no-op dispose if not overridden
    if (templateDecl.members.none { it is FunDecl && it.name == "dispose" }) {
        hdr.appendLine("#define ${cName}_dispose(self) ((void)(self))")
    }
    // Implicit hashCode
    emitImplicitHashCode(cName, ci, templateDecl.isData, isGenericClass = true, templateDecl.members)
    // Implicit equals for all classes
    if (templateDecl.members.none { it is FunDecl && it.name == "equals" }) {
        emitClassEquals(cName, ci)
    }
    // Implicit toString for data classes
    if (templateDecl.isData && templateDecl.members.none { it is FunDecl && it.name == "toString" }) {
        emitDataClassToString(templateDecl.name, cName, ci)
    }
    // Implicit toString for non-data classes
    if (!templateDecl.isData && templateDecl.members.none { it is FunDecl && it.name == "toString" }) {
        emitDefaultToString(ci.name, cName, ci)
    }
    // Any vtable + _as_Any wrapper
    emitAnyVtable(cName, ci.name, templateDecl.isData, templateDecl.members, isGenericClass = true)
    popScope()
    currentClass = null

    // Secondary constructors
    for (sctor in templateDecl.secondaryCtors) {
        emitSecondaryCtor(mangledName, cName, sctor)
    }
}

internal fun CCodeGen.emitClassEquals(cName: String, ci: ClassInfo) {
    hdr.appendLine("ktc_Bool ${cName}_equals($cName a, $cName b);")
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
                    vInnerName == "String" -> "ktc_core_string_eq(a.$fieldName.value, b.$fieldName.value)"
                    classes[vInnerName]?.isData == true -> "${typeFlatName(vInnerName)}_equals(a.$fieldName.value, b.$fieldName.value)"
                    else -> "a.$fieldName.value == b.$fieldName.value"
                }
                "(a.$fieldName.tag == b.$fieldName.tag && (a.$fieldName.tag == ktc_NONE || $vValueCmp))"
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
    hdr.appendLine("void ${cName}_toString($cName* \$self, ktc_StrBuf* sb);${maxComment}")
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

internal fun CCodeGen.emitMethod(className: String, f: FunDecl, suppressHdr: Boolean = false) {
    val cClass = typeFlatName(className)
    val siblings = classes[className]?.methods ?: emptyList()
    val overloadedName = methodName(f, siblings)
    val methodName = if (f.isPrivate) "PRIV_$overloadedName" else overloadedName

    val paramSig = f.params.joinToString(", ") { p -> "${p.name}: ${typeRefToStr(p.type)}" }
    val retSig = f.returnType?.let { ": ${typeRefToStr(it)}" } ?: ""
    val priv = if (f.isPrivate) "private " else ""
    impl.appendLine("// ══ ${priv}fun ${f.name}($paramSig)$retSig ══")
    impl.appendLine()

    val returnsNullable = f.returnType != null && f.returnType.nullable
    val returnsSizedArray = !returnsNullable && f.returnType != null && isSizedArrayTypeRef(f.returnType)
    val vRetKtc     = if (f.returnType != null) resolveTypeName(f.returnType) else null  // KtcType of return, or null
    val retResolved = vRetKtc?.toInternalStr ?: f.body?.let { inferBlockType(it) } ?: "" // string for legacy helpers
    val optRetCType = if (returnsNullable) optCTypeName(retResolved) else ""
    val cRet = when {
        returnsSizedArray -> "void"
        returnsNullable && vRetKtc is KtcType.Any -> "ktc_Any"
        returnsNullable -> optRetCType
        retResolved.isNotEmpty() -> cTypeStr(retResolved)
        else -> "void"
    }
    val selfParam = "$cClass* \$self"
    val extraParams = expandParams(f.params)
    val outParam: String? = if (returnsSizedArray) {
        val elemCType = cTypeStr(vRetKtc!!.asArr!!.elem)
        "$elemCType* \$out"
    } else null
    val allParts = mutableListOf(selfParam)
    if (extraParams.isNotEmpty()) allParts += extraParams
    if (outParam != null) allParts += outParam
    val allParams = allParts.joinToString(", ")

    if (f.isPrivate) {
        // Private: forward decl only in .c, not in .h
        implFwd.appendLine("$cRet ${cClass}_${methodName}($allParams);")
    } else if (suppressHdr) {
        deferredHdrLines.getOrPut(className) { mutableListOf() }.add("$cRet ${cClass}_${methodName}($allParams);")
    } else {
        hdr.appendLine("$cRet ${cClass}_${methodName}($allParams);")
    }
    impl.appendLine("$cRet ${cClass}_${methodName}($allParams) {")

    val prevState = saveFunState()
    currentFnReturnsNullable = returnsNullable
    currentFnReturnsArray = false
    currentFnReturnsSizedArray = returnsSizedArray
    currentFnOptReturnCTypeName = optRetCType
    if (returnsSizedArray) {
        currentFnSizedArraySize = getSizeAnnotation(f.returnType)!!
        currentFnSizedArrayElemType = cTypeStr(vRetKtc!!.asArr!!.elem)
    }
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
    popScope()

    restoreFunState(prevState)

    impl.appendLine("}")
    impl.appendLine()
}

// ── extension function ───────────────────────────────────────────

internal fun CCodeGen.emitExtensionFun(f: FunDecl) {
    val recvTypeName = f.receiver!!.name
    val recvIsNullable = f.receiver.nullable
    val paramSig = f.params.joinToString(", ") { p -> "${p.name}: ${typeRefToStr(p.type)}" }
    val retSig = f.returnType?.let { ": ${typeRefToStr(it)}" } ?: ""
    impl.appendLine("// ══ ext fun ${recvTypeName}.${f.name}($paramSig)$retSig ($currentSourceFile) ══")
    impl.appendLine()
    val returnsSizedArray = f.returnType != null && isSizedArrayTypeRef(f.returnType)
    val returnsNullable = f.returnType != null && f.returnType.nullable
    val vRetKtcExt  = if (f.returnType != null) resolveTypeName(f.returnType) else null  // KtcType of return, or null
    val retResolved = vRetKtcExt?.toInternalStr ?: ""                                    // string for legacy helpers
    val optRetCType = if (returnsNullable) optCTypeName(retResolved) else ""
    val cRet = when {
        returnsSizedArray -> "void"
        returnsNullable && vRetKtcExt is KtcType.Any -> "ktc_Any"
        returnsNullable -> optRetCType
        f.returnType != null -> cType(f.returnType)
        else -> "void"
    }
    val isClassType = classes.containsKey(recvTypeName)
    val cRecvType = cType(f.receiver!!)    // use TypeRef to honor @Ptr annotations
    // Nullable receiver: pass as Optional struct (value) or OptionalPtr (pointer type)
    val selfParam = if (recvIsNullable) {
        val recvOptType = optCTypeName(recvTypeName)
        "$recvOptType \$self"
    } else "$cRecvType \$self"
    val extraParams = expandParams(f.params)
    val outParam = if (returnsSizedArray) {
        val elemCType = cTypeStr(vRetKtcExt!!.asArr!!.elem)
        "$elemCType* \$out"
    } else null
    val allParts = mutableListOf(selfParam)
    if (extraParams.isNotEmpty()) allParts += extraParams
    if (outParam != null) allParts += outParam
    val allParams = allParts.joinToString(", ")
    val cFnName = "${typeFlatName(recvTypeName)}_${f.name}"

    hdr.appendLine("$cRet $cFnName($allParams);")
    impl.appendLine("$cRet $cFnName($allParams) {")

    val prevState = saveFunState()
    currentFnReturnsSizedArray = returnsSizedArray
    currentFnOptReturnCTypeName = optRetCType
    if (returnsSizedArray) {
        currentFnSizedArraySize = getSizeAnnotation(f.returnType)!!
        currentFnSizedArrayElemType = cTypeStr(vRetKtcExt!!.asArr!!.elem)
    }
    currentExtRecvType = if (recvIsNullable) "$recvTypeName?" else recvTypeName
    if (isClassType) {
        currentClass = recvTypeName
        selfIsPointer = false
    } else {
        currentClass = null
        selfIsPointer = false
    }
    pushScope()
    // If receiver is nullable, $self is an Optional struct — mark it so genName works
    if (recvIsNullable) {
        defineVar("\$self", "${recvTypeName}?")
        markOptional("\$self")
    }
    for (p in f.params) {
        val vKtcExtParam = resolveTypeName(p.type)              // KtcType of this parameter
        val vExtPStr     = vKtcExtParam.toInternalStr           // string for vararg/nullable/isValueNullable checks
        defineVar(p.name, when {
            p.isVararg -> "${vExtPStr}Array"
            p.type.nullable -> "${vExtPStr}?"
            else -> vExtPStr
        })
        if (p.type.nullable && isValueNullableKtc(KtcType.Nullable(vKtcExtParam))) markOptional(p.name)
    }
    if (isClassType) {
        val ci = classes[recvTypeName]!!
        for ((name, type) in ci.props) {
            defineVarKtc(name, resolveTypeName(type))
            if (!ci.isValProp(name)) markMutable(name)
        }
    }
    val savedTrampolined2 = trampolinedParams.toHashSet(); trampolinedParams.clear()
    emitArrayParamCopies(f.params, "    ")
    val savedDefers2 = deferStack.toList(); deferStack.clear()
    if (f.body != null) for (s in f.body.stmts) emitStmt(s, "    ", insideMethod = isClassType)
    if (f.body?.stmts?.lastOrNull() !is ReturnStmt) {
        emitDeferredBlocks("    ", insideMethod = isClassType)
        if (returnsNullable) {
            if (vRetKtcExt is KtcType.Any) impl.appendLine("    return (ktc_Any){0};")
            else impl.appendLine("    return ${optNone(optRetCType)};")
        }
    }
    deferStack.clear(); deferStack.addAll(savedDefers2)
    trampolinedParams.clear(); trampolinedParams.addAll(savedTrampolined2)
    popScope()

    restoreFunState(prevState)

    impl.appendLine("}")
    impl.appendLine()
}

// ── generic function monomorphization ────────────────────────────

/**
 * Emit monomorphized versions of a generic free function.
 * For `fun <T> sizeOfList(list: MutableList<T>)`, if called with MutableList<Int>,
 * emits `sizeOfList_Int(MutableList_Int* list)`.
 *
 * Instantiations are found by scanning call sites in the AST.
 */
internal fun CCodeGen.emitGenericFunInstantiations(f: FunDecl) {
    if (f.isInline || f.isInfix) return  // inline/infix: expanded at call sites only, not emitted as C functions
    val instantiations = genericFunInstantiations[f.name] ?: return
    // Switch source file attribution for mem-track if this function came from another file
    val prevSourceFile = currentSourceFile
    declSourceFile[f.name]?.let { currentSourceFile = it }
    for (typeArgs in instantiations) {
        val subst = f.typeParams.zip(typeArgs).toMap()
        val prevSubst = typeSubst
        typeSubst = subst
        val mangledName = "${f.name}_${typeArgs.joinToString("_")}"

        impl.appendLine("// ══ generic ${f.name}<${typeArgs.joinToString(", ")}> ($currentSourceFile) ══")
        impl.appendLine()

        // Prepend receiver as $self parameter for generic extensions
        val hasReceiver = f.receiver != null
        // Resolve return type and params under substitution
        val returnsSizedArray = f.returnType != null && isSizedArrayTypeRef(f.returnType)
        val vRetKtcGen   = if (f.returnType != null) resolveTypeName(f.returnType) else null   // KtcType of return, or null
        val returnsArray = !returnsSizedArray && (vRetKtcGen?.isArrayLike ?: false)             // true if return is non-sized array
        val concreteRet = genericFunConcreteReturn[mangledName]
        val cRet = when {
            returnsSizedArray -> "void"
            concreteRet != null -> typeFlatName(concreteRet)
            f.returnType != null -> cType(f.returnType)
            else -> "void"
        }
        val cName = if (hasReceiver) {
            val recvKtc = resolveTypeName(f.receiver)
            val recvName = (recvKtc as? KtcType.Ptr)?.inner?.let { (it as? KtcType.User)?.baseName }
                ?: recvKtc.toInternalStr.removeSuffix("*").removeSuffix("?")
            if (f.receiver!!.annotations.any { it.name == "Ptr" }) {
                val baseFlat = typeFlatName(recvName)
                "${baseFlat.removeSuffix("_$recvName")}_Ptr_${recvName}_${f.name}"
            } else {
                "${typeFlatName(recvName)}_${f.name}"
            }
        } else funCName(mangledName)
        val baseParams = expandParams(f.params)
        val selfParam = if (hasReceiver) {
            val selfRecvKtc = resolveTypeName(f.receiver!!)
            val ct = if (f.receiver!!.nullable && selfRecvKtc !is KtcType.Ptr && selfRecvKtc !is KtcType.Nullable)
                optCTypeName(selfRecvKtc.toInternalStr)
            else cType(f.receiver!!)
            "$ct \$self"
        } else null
        val params = when {
            returnsSizedArray -> {
                val elemCType = cTypeStr(vRetKtcGen!!.asArr!!.elem)
                val extra = "$elemCType* \$out"
                val p = if (selfParam != null && baseParams.isNotEmpty()) "$selfParam, $baseParams" else selfParam ?: baseParams
                if (p.isNotEmpty()) "$p, $extra" else extra
            }
            returnsArray -> {
                val extra = "ktc_Int* \$len_out"
                val p = if (selfParam != null && baseParams.isNotEmpty()) "$selfParam, $baseParams" else selfParam ?: baseParams
                if (p.isNotEmpty()) "$p, $extra" else extra
            }
            else -> if (selfParam != null && baseParams.isNotEmpty()) "$selfParam, $baseParams" else selfParam ?: baseParams
        }

        hdr.appendLine("$cRet $cName($params);")
        impl.appendLine("$cRet $cName($params) {")

        val prevState = saveFunState()
        currentFnReturnsArray = returnsArray
        currentFnReturnsSizedArray = returnsSizedArray
        if (returnsSizedArray) {
            currentFnSizedArraySize = getSizeAnnotation(f.returnType)!!
            currentFnSizedArrayElemType = cTypeStr(vRetKtcGen!!.asArr!!.elem)
        }
        currentFnReturnType = concreteRet
            ?: if (f.returnType != null) {
                val vKtc = vRetKtcGen!!
                currentFnReturnKtcType = if (f.returnType.nullable) KtcType.Nullable(vKtc) else vKtc
                if (f.returnType.nullable) KtcType.Nullable(vKtc).toInternalStr else vKtc.toInternalStr
            } else ""

        pushScope()
        // Set up receiver context for generic extension functions
        if (hasReceiver) {
            val recvResolved = resolveTypeName(f.receiver!!)
            val recvFull = recvResolved.toInternalStr
            val recvName = recvFull.removeSuffix("?")
            val isClassType = classes.containsKey(recvName)
            currentExtRecvType = if (f.receiver!!.nullable) "${recvName}?" else recvName
            defineVar("\$self", if (f.receiver!!.nullable) "${recvName}?" else recvName)
            if (f.receiver!!.nullable && isValueNullableKtc(recvResolved as? KtcType.Nullable ?: KtcType.Nullable(recvResolved))) markOptional("\$self")
            if (isClassType) {
                currentClass = recvName
                selfIsPointer = f.receiver!!.annotations.any { it.name == "Ptr" }
            } else {
                currentClass = null
                selfIsPointer = false
            }
        }
        for (p in f.params) {
            val vKtcGenParam = resolveTypeName(p.type)             // KtcType of this parameter
            val vGenPStr     = vKtcGenParam.toInternalStr          // string for vararg/nullable/class checks
            defineVar(p.name, when {
                p.isVararg -> "${vGenPStr}Array"  // vararg params are arrays (ptr + $len)
                p.type.nullable -> "${vGenPStr}?"
                else -> vGenPStr
            })
            if (p.type.nullable && isValueNullableKtc(KtcType.Nullable(vKtcGenParam))) markOptional(p.name)
        }
        val savedTrampolined3 = trampolinedParams.toHashSet(); trampolinedParams.clear()
        emitArrayParamCopies(f.params, "    ")
        val savedDefers = deferStack.toList(); deferStack.clear()
        if (f.body != null) for (s in f.body.stmts) emitStmt(s, "    ", insideMethod = false)
        if (f.body?.stmts?.lastOrNull() !is ReturnStmt) emitDeferredBlocks("    ", insideMethod = false)
        deferStack.clear(); deferStack.addAll(savedDefers)
        trampolinedParams.clear(); trampolinedParams.addAll(savedTrampolined3)
        popScope()

        restoreFunState(prevState)
        impl.appendLine("}")
        impl.appendLine()

        typeSubst = prevSubst
    }
    currentSourceFile = prevSourceFile
}

/**
 * Emit star-projection extension functions — one per known instantiation.
 * For `fun MutableList<*>.sizeOf()`, if MutableList<Int> is known, emits
 * `MutableList_Int_sizeOf(MutableList_Int* $self)`.
 */
internal fun CCodeGen.emitStarExtFunInstantiations(f: FunDecl) {
    val recvBaseName = f.receiver!!.name
    val instantiations = genericInstantiations[recvBaseName]

    // If the receiver is a generic interface (not a class), expand per implementing class
    if (instantiations == null && genericIfaceDecls.containsKey(recvBaseName)) {
        emitStarExtFunForGenericInterface(f, recvBaseName)
        return
    }
    if (instantiations == null) return
    val emitted = mutableSetOf<String>()
    for (typeArgs in instantiations) {
        val mangledRecvName = mangledGenericName(recvBaseName, typeArgs)
        val key = "${mangledRecvName}_${f.name}"
        if (!emitted.add(key)) continue  // avoid duplicates
        // Build a concrete FunDecl with the mangled receiver name
        val concreteReceiver = TypeRef(mangledRecvName, f.receiver.nullable)
        // Set typeSubst from the generic class's type params
        val templateCi = classes[recvBaseName] ?: continue
        val subst = templateCi.typeParams.zip(typeArgs).toMap()
        val prevSubst = typeSubst
        typeSubst = subst

        val recvIsNullable = concreteReceiver.nullable
        val cRet = if (f.returnType != null) cType(f.returnType) else "void"
        val isClassType = classes.containsKey(mangledRecvName)
        val cRecvType = typeFlatName(mangledRecvName)
        val selfParam = if (isClassType) "$cRecvType* \$self" else "$cRecvType \$self"
        val nullableExtra = if (recvIsNullable) ", ktc_Bool \$self\$has" else ""
        val extraParams = expandParams(f.params)
        val allParams = if (extraParams.isEmpty()) "$selfParam$nullableExtra" else "$selfParam$nullableExtra, $extraParams"
        val cFnName = "${typeFlatName(mangledRecvName)}_${f.name}"

        hdr.appendLine("$cRet $cFnName($allParams);")
        impl.appendLine("$cRet $cFnName($allParams) {")

        val prevClass = currentClass
        val prevSelfIsPointer = selfIsPointer
        val prevExtRecvType = currentExtRecvType
        currentExtRecvType = if (recvIsNullable) "$mangledRecvName?" else mangledRecvName
        if (isClassType) {
            currentClass = mangledRecvName
            selfIsPointer = true
        } else {
            currentClass = null
            selfIsPointer = false
        }

        pushScope()
        for (p in f.params) {
            val vKtcStarParam = resolveTypeName(p.type)             // KtcType of this parameter
            val vStarPStr     = vKtcStarParam.toInternalStr         // string for nullable/class checks
            defineVar(p.name, when {
                p.type.nullable -> "${vStarPStr}?"
                classes.containsKey(vStarPStr) -> "${vStarPStr}*"
                else -> vStarPStr
            })
        }
        if (isClassType) {
            val ci = classes[mangledRecvName]!!
            for ((name, type) in ci.props) defineVarKtc(name, resolveTypeName(type))
        }
        val savedTrampolined4 = trampolinedParams.toHashSet(); trampolinedParams.clear()
        emitArrayParamCopies(f.params, "    ")
        val savedDefers = deferStack.toList(); deferStack.clear()
        if (f.body != null) for (s in f.body.stmts) emitStmt(s, "    ", insideMethod = isClassType)
        if (f.body?.stmts?.lastOrNull() !is ReturnStmt) emitDeferredBlocks("    ", insideMethod = isClassType)
        deferStack.clear(); deferStack.addAll(savedDefers)
        trampolinedParams.clear(); trampolinedParams.addAll(savedTrampolined4)
        popScope()

        currentClass = prevClass
        selfIsPointer = prevSelfIsPointer
        currentExtRecvType = prevExtRecvType

        impl.appendLine("}")
        impl.appendLine()

        // Register as extension function on the mangled name for call resolution
        extensionFuns.getOrPut(mangledRecvName) { mutableListOf() }.add(
            FunDecl(f.name, f.params, f.returnType, f.body, concreteReceiver)
        )
        classes[mangledRecvName]?.methods?.add(
            FunDecl(f.name, f.params, f.returnType, f.body, concreteReceiver)
        )

        typeSubst = prevSubst
    }
}

/**
 * Expand a star-projection extension on a generic interface (e.g., List<*>.sizeOf())
 * into concrete implementations for each class that implements a monomorphized version.
 * For ArrayList_Int (implements List_Int) → ArrayList_Int_sizeOf
 * For ArrayList_Vec2 (implements List_Vec2) → ArrayList_Vec2_sizeOf
 */
internal fun CCodeGen.emitStarExtFunForGenericInterface(f: FunDecl, ifaceBaseName: String) {
    val emitted = mutableSetOf<String>()
    // Find all classes that implement a monomorphized version of this interface
    for ((className, ifaceList) in classInterfaces) {
        // Check if this class implements any monomorphized version of the interface
        val matchingIface = ifaceList.find { it.startsWith("${ifaceBaseName}\$") }
        if (matchingIface == null) continue
        val ci = classes[className] ?: continue
        val key = "${className}_${f.name}"
        if (!emitted.add(key)) continue

        // Set up type substitution from the class's own type bindings
        val prevSubst = typeSubst
        typeSubst = genericTypeBindings[className] ?: emptyMap()

        val cRet = if (f.returnType != null) cType(f.returnType) else "void"
        val cRecvType = typeFlatName(className)
        val selfParam = "$cRecvType* \$self"
        val extraParams = expandParams(f.params)
        val allParams = if (extraParams.isEmpty()) selfParam else "$selfParam, $extraParams"
        val cFnName = "${cRecvType}_${f.name}"

        hdr.appendLine("$cRet $cFnName($allParams);")
        impl.appendLine("$cRet $cFnName($allParams) {")

        val prevClass = currentClass
        val prevSelfIsPointer = selfIsPointer
        val prevExtRecvType = currentExtRecvType
        currentExtRecvType = className
        currentClass = className
        selfIsPointer = true

        pushScope()
        for (p in f.params) {
            val vKtcIfaceParam = resolveTypeName(p.type)            // KtcType of this parameter
            val vIfacePStr     = vKtcIfaceParam.toInternalStr       // string for nullable/class checks
            defineVar(p.name, when {
                p.type.nullable -> "${vIfacePStr}?"
                classes.containsKey(vIfacePStr) -> "${vIfacePStr}*"
                else -> vIfacePStr
            })
        }
        for ((name, type) in ci.props) defineVarKtc(name, resolveTypeName(type))
        val savedTrampolined5 = trampolinedParams.toHashSet(); trampolinedParams.clear()
        emitArrayParamCopies(f.params, "    ")
        val savedDefers = deferStack.toList(); deferStack.clear()
        if (f.body != null) for (s in f.body.stmts) emitStmt(s, "    ", insideMethod = true)
        if (f.body?.stmts?.lastOrNull() !is ReturnStmt) emitDeferredBlocks("    ", insideMethod = true)
        deferStack.clear(); deferStack.addAll(savedDefers)
        trampolinedParams.clear(); trampolinedParams.addAll(savedTrampolined5)
        popScope()

        currentClass = prevClass
        selfIsPointer = prevSelfIsPointer
        currentExtRecvType = prevExtRecvType

        impl.appendLine("}")
        impl.appendLine()

        // Register as extension function for call resolution
        val concreteReceiver = TypeRef(className, f.receiver!!.nullable)
        extensionFuns.getOrPut(className) { mutableListOf() }.add(
            FunDecl(f.name, f.params, f.returnType, f.body, concreteReceiver)
        )
        ci.methods.add(FunDecl(f.name, f.params, f.returnType, f.body, concreteReceiver))

        typeSubst = prevSubst
    }
}

// ── enum class ───────────────────────────────────────────────────

internal fun CCodeGen.emitEnum(d: EnumDecl) {
    val ei = enums[d.name]!!
    val cName = ei.flatName
    hdr.appendLine("typedef enum {")
    for ((i, e) in d.entries.withIndex()) {
        hdr.append("    ${cName}_$e")
        if (i < d.entries.lastIndex) hdr.append(",")
        hdr.appendLine()
    }
    hdr.appendLine("} $cName;")
    val n = d.entries.size
    val nameInits = d.entries.joinToString(", ") { "ktc_core_str(\"$it\")" }
    hdr.appendLine("extern const ktc_String ${cName}_names[$n];")
    hdr.appendLine()
    impl.appendLine("const ktc_String ${cName}_names[$n] = {$nameInits};")
    impl.appendLine()
}

internal fun CCodeGen.emitEnumValuesData() {
    for (enumName in enumValuesCalled) {
        val info = enums[enumName] ?: continue
        val cName = typeFlatName(enumName)
        val entryNames = info.entries.joinToString(", ") { "${cName}_${it}" }
        val n = info.entries.size
        // extern declarations in header
        hdr.appendLine("extern const $cName ${cName}_values[$n];")
        hdr.appendLine("extern const ktc_Int ${cName}_values\$len;")
        // definitions in source
        impl.appendLine("const $cName ${cName}_values[] = {$entryNames};")
        impl.appendLine("const ktc_Int ${cName}_values\$len = $n;")
    }
    for (enumName in enumValueOfCalled) {
        val info = enums[enumName] ?: continue
        val cName = typeFlatName(enumName)
        // valueOf function forward declaration in header
        hdr.appendLine("$cName ${cName}_valueOf(ktc_String name);")
        // valueOf function body in source
        val body = StringBuilder()
        body.appendLine("$cName ${cName}_valueOf(ktc_String name) {")
        for (entry in info.entries) {
            body.appendLine("    if (ktc_core_string_eq(name, ktc_core_str(\"$entry\"))) return ${cName}_$entry;")
        }
        body.appendLine("    return ${cName}_${info.entries.first()};")
        body.append("}")
        impl.appendLine(body.toString())
        impl.appendLine()
    }
    enumValuesCalled.clear()
    enumValueOfCalled.clear()
}

// ── object ───────────────────────────────────────────────────────

internal fun CCodeGen.emitObject(d: ObjectDecl) {
    val oi = objects[d.name]!!
    val cName = oi.flatName
    val props = d.members.filterIsInstance<PropDecl>()
    val initBlocks = d.members.filterIsInstance<FunDecl>().filter { it.name == "init" }
    val methods = d.members.filterIsInstance<FunDecl>().filter { it.name != "init" }
    val privPrefix = { p: PropDecl -> if (p.isPrivate) "PRIV_" else "" }

    impl.appendLine("// ══ object ${d.name} ($currentSourceFile) ══")
    impl.appendLine()

    hdr.appendLine("// ══ object ${d.name} ($currentSourceFile) ══")
    val typeIdValue = typeIds.getOrPut(d.name) { nextTypeId++ }
    hdr.appendLine("#define ${cName}_TYPE_ID $typeIdValue")
    hdr.appendLine("typedef struct {")
    if (props.isEmpty()) hdr.appendLine("    ktc_Char _dummy;")
    for (p in props) {
        val pType     = p.type ?: inferInitType(p.init)
        val vKtcObj   = resolveTypeName(pType)                          // KtcType for struct field
        val sizeAnn   = getSizeAnnotation(pType)
        if (vKtcObj.isArrayLike && sizeAnn != null) {
            val vElemType = cTypeStr(vKtcObj.asArr!!.elem)              // element C type for sized array
            val fn = privPrefix(p) + p.name
            val mutComment = if (p.mutable) "/*VAR*/ " else "/*VAL*/ "
            hdr.appendLine("    $mutComment$vElemType ${fn}[${sizeAnn}];")
            hdr.appendLine("    ktc_Int ${fn}\$len;")
        } else if (vKtcObj.isArrayLike) {
            val fn = privPrefix(p) + p.name
            val mutComment = if (p.mutable) "/*VAR*/ " else "/*VAL*/ "
            hdr.appendLine("    $mutComment${cTypeStr(vKtcObj)} ${fn};${ptrNullComment(vKtcObj)}")
            hdr.appendLine("    ktc_Int ${fn}\$len;")
        } else {
            val fn = privPrefix(p) + p.name
            val mutComment = if (p.mutable) "/*VAR*/ " else "/*VAL*/ "
            hdr.appendLine("    ${mutComment}${cTypeStr(vKtcObj)} ${fn};${ptrNullComment(vKtcObj)}")
        }
    }
    hdr.appendLine("} ${cName}_t;")
    val tls = if (d.name in tlsObjects) "ktc_core_tls " else ""
    hdr.appendLine("extern ${tls}${cName}_t $cName;")
    hdr.appendLine()

    // global instance (zero-initialized), init flag + ensure_init are internal
    impl.appendLine("${tls}${cName}_t $cName = {0};")
    impl.appendLine("${tls}static ktc_Bool ${cName}\$init = false;")
    impl.appendLine()

    // $ensure_init: lazy initialization function
    impl.appendLine("static void ${cName}_\$ensure_init(void) {")
    impl.appendLine("    if (${cName}\$init) return;")
    impl.appendLine("    ${cName}\$init = true;")
    val prevObject = currentObject
    currentObject = d.name
    pushScope()
    for (p in props) defineVarKtc(p.name, resolveTypeName(p.type ?: inferInitType(p.init)))
    for (p in props) {
        if (p.init != null) {
            val pType       = p.type ?: inferInitType(p.init)
            val vKtcObjInit = resolveTypeName(pType)                   // KtcType for array checks
            val sizeAnn     = getSizeAnnotation(pType)
            heapAllocTargetType = pType
            val expr        = genExpr(p.init)
            heapAllocTargetType = null
            flushPreStmts("    ")
            if (vKtcObjInit.isArrayLike && sizeAnn != null) {
                val vElemType = cTypeStr(vKtcObjInit.asArr!!.elem)     // element C type for sized array
                val fn = privPrefix(p) + p.name
                impl.appendLine("    memcpy($cName.$fn, $expr, $sizeAnn * sizeof($vElemType));")
                impl.appendLine("    $cName.${fn}\$len = ${sizeAnn};")
            } else {
                val fn = privPrefix(p) + p.name
                impl.appendLine("    $cName.$fn = $expr;")
            }
        }
    }
    // emit init { } block bodies
    for (ib in initBlocks) {
        if (ib.body != null) for (s in ib.body.stmts) emitStmt(s, "    ")
    }
    popScope()
    currentObject = prevObject
    impl.appendLine("}")
    impl.appendLine()

    // Forward-declare private methods so nested classes can call them
    for (m in methods) {
        if (m.isPrivate) {
            val vRetKtcFwd  = if (m.returnType != null) resolveTypeName(m.returnType) else null  // KtcType for fwd decl
            val vFwdRetStr  = vRetKtcFwd?.toInternalStr ?: ""                                    // string for cTypeStr
            val cRet = when {
                m.returnType != null && isSizedArrayTypeRef(m.returnType) -> "void"
                vFwdRetStr.isNotEmpty() -> cTypeStr(vFwdRetStr)
                else -> "void"
            }
            val fwdParams = expandParams(m.params)
            val overloadedName = methodName(m, methods)
            impl.appendLine("$cRet ${cName}_PRIV_$overloadedName($fwdParams);")
        }
    }

    // Emit nested classes BEFORE methods so method return types can reference them
    for (nested in d.members.filterIsInstance<ClassDecl>()) {
        if (nested.typeParams.isEmpty()) {
            impl.appendLine()
            hdr.appendLine()
            emitClass(
                ClassDecl(
                    "${d.name}$${nested.name}", nested.isData,
                    nested.ctorParams, nested.members, nested.initBlocks,
                    nested.superInterfaces, nested.typeParams, nested.secondaryCtors
                )
            )
        }
    }

    // methods — inject $ensure_init() at the top of each
    val prevObjectForMethods = currentObject
    currentObject = d.name
    for (m in methods) {
        val returnsSizedArray  = m.returnType != null && isSizedArrayTypeRef(m.returnType)
        val vRetKtcM           = if (m.returnType != null) resolveTypeName(m.returnType) else null  // KtcType of return
        val returnsArray       = !returnsSizedArray && (vRetKtcM?.isArrayLike ?: false)             // true if return is non-sized array
        val retResolved        = vRetKtcM?.toInternalStr ?: ""                                      // string for cTypeStr
        val cRet = when {
            returnsSizedArray -> "void"
            retResolved.isNotEmpty() -> cTypeStr(retResolved)
            else -> "void"
        }
        val overloadedName = methodName(m, methods)
        val fnName = if (m.isPrivate) "PRIV_$overloadedName" else overloadedName
        val baseParams = expandParams(m.params)
        val extraParam = when {
            returnsSizedArray -> {
                val elemCType = cTypeStr(vRetKtcM!!.asArr!!.elem)
                "$elemCType* \$out"
            }
            returnsArray -> "ktc_Int* \$len_out"
            else -> null
        }
        val params = if (extraParam != null) {
            if (baseParams.isEmpty()) extraParam else "$baseParams, $extraParam"
        } else baseParams
        if (m.isPrivate) {
            impl.appendLine("$cRet ${cName}_$fnName($params);")
        } else {
            hdr.appendLine("$cRet ${cName}_$fnName($params);")
        }
        impl.appendLine("$cRet ${cName}_$fnName($params) {")
        impl.appendLine("    ${cName}_\$ensure_init();")
        val prevState = saveFunState()
        val prevObjectM = currentObject
        currentFnReturnsArray = returnsArray
        currentFnReturnsSizedArray = returnsSizedArray
    currentFnReturnType = retResolved
    currentFnReturnKtcType = vRetKtcM
        if (returnsSizedArray) {
            currentFnSizedArraySize = getSizeAnnotation(m.returnType)!!
            currentFnSizedArrayElemType = cTypeStr(vRetKtcM!!.asArr!!.elem)
        }
        pushScope()
        for (p in props) defineVarKtc(p.name, resolveTypeName(p.type ?: inferInitType(p.init)))
        for (p in m.params) {
            val vKtcObjParam = resolveTypeName(p.type)             // KtcType of this method parameter
            defineVar(p.name, if (p.isVararg) "${vKtcObjParam.toInternalStr}Array" else vKtcObjParam.toInternalStr)
        }
        val savedTrampolined6 = trampolinedParams.toHashSet(); trampolinedParams.clear()
        emitArrayParamCopies(m.params, "    ")
        val savedDefers3 = deferStack.toList(); deferStack.clear()
        if (m.body != null) for (s in m.body.stmts) emitStmt(s, "    ")
        if (m.body?.stmts?.lastOrNull() !is ReturnStmt) emitDeferredBlocks("    ")
        deferStack.clear(); deferStack.addAll(savedDefers3)
        trampolinedParams.clear(); trampolinedParams.addAll(savedTrampolined6)
        popScope()
        restoreFunState(prevState)
        currentObject = prevObjectM
        impl.appendLine("}")
        impl.appendLine()
    }
    currentObject = prevObjectForMethods
    // (nested classes already emitted above, before methods)
}

// ── interface ────────────────────────────────────────────────────

/**
 * Emit a vtable struct + fat-pointer typedef for an interface.
 *
 * interface Drawable { fun draw(); fun area(): Float }
 * →
 * typedef struct {
 *     void (*draw)(void* $self);
 *     float (*area)(void* $self);
 * } game_Drawable_vt;
 *
 * typedef struct {
 *     void* obj;
 *     const game_Drawable_vt* vt;
 * } game_Drawable;
 */
/** Emit only the vtable struct + TYPE_ID for an interface (used early, before class structs). */
internal fun CCodeGen.emitInterface(d: InterfaceDecl) {
    val info = interfaces[d.name] ?: return
    emitInterfaceVtable(info)
}

/**
 * Emit interface vtable struct + TYPE_ID define.
 * Handles inherited methods/properties from super interfaces.
 */
internal fun CCodeGen.emitInterfaceVtable(info: IfaceInfo) {
    val cName = info.flatName
    hdr.appendLine("// ══ interface ${info.name} ($currentSourceFile) ══")
    hdr.appendLine("#define ${cName}_TYPE_ID ${typeIds[info.name]!!}")
    // Collect all methods/properties including inherited from super interfaces
    val allMethods = collectAllIfaceMethods(info)
    val allProps = collectAllIfaceProperties(info)
    // vtable struct (named so it can be forward-declared)
    hdr.appendLine("typedef struct ${cName}_vt {")
    // Properties → getter function pointers
    for (p in allProps) {
        val ct = if (p.type != null) cType(p.type) else "ktc_Int"
        hdr.appendLine("    $ct (*${p.name})(void* \$self);")
    }
    for (m in allMethods) {
        val mReturnsNullable = m.returnType != null && m.returnType.nullable
        val vMRetKtc   = if (m.returnType != null) resolveTypeName(m.returnType) else null  // KtcType of method return
        val mRetResolved = vMRetKtc?.toInternalStr ?: ""                                    // string for optCTypeName
        val cRet = if (mReturnsNullable) optCTypeName(mRetResolved) else if (m.returnType != null) cType(m.returnType) else "void"
        val extraParams = m.params.joinToString("") { p ->
            val vKtcVtParam  = resolveTypeName(p.type)                  // KtcType of vtable param
            val vVtParamStr  = vKtcVtParam.toInternalStr                // string for optCTypeName
            if (p.type.nullable) ", ${optCTypeName(vVtParamStr)} ${p.name}"
            else ", ${cType(p.type)} ${p.name}"
        }
        hdr.appendLine("    $cRet (*${m.name})(void* \$self$extraParams);")
    }
    if (allMethods.none { it.name == "dispose" }) {
        hdr.appendLine("    void (*dispose)(void* \$self);")
    }
    hdr.appendLine("} ${cName}_vt;")
    hdr.appendLine()
}

/**
 * Emit a concrete (non-generic) interface struct: tagged union containing all implementing classes.
 * Falls back to void* obj only for interfaces with zero known implementors.
 * Must be called after all class structs and vtables have been emitted.
 */
internal fun CCodeGen.emitIfaceInfo(info: IfaceInfo) {
    val cName = info.flatName
    val impls = interfaceImplementors[info.name] ?: emptyList()
    fun implCType(name: String) = if (objects.containsKey(name)) "${typeFlatName(name)}_t" else typeFlatName(name)
    hdr.appendLine("// ══ interface ${info.name} — tagged union ($currentSourceFile) ══")
    hdr.appendLine("typedef struct $cName {")
    if (impls.isEmpty()) {
        hdr.appendLine("    void* obj;")
    } else if (impls.size == 1) {
        hdr.appendLine("    ktc_core_AnySupertype __base;")
        hdr.appendLine("    ${implCType(impls[0])} ${ifaceDataName(impls[0])};")
    } else {
        hdr.appendLine("    ktc_core_AnySupertype __base;")
        hdr.appendLine("    union {")
        for (className in impls) {
            hdr.appendLine("        ${implCType(className)} ${ifaceDataName(className)};")
        }
        hdr.appendLine("    } data;")
    }
    hdr.appendLine("    const ${cName}_vt* vt;")
    hdr.appendLine("} $cName;")
    // Emit $Opt wrapper for nullable interface use
    val vIfaceComponents = mangledComponents[info.name]
    if (vIfaceComponents != null) {
        val (vGenBase, vTypeArgs) = vIfaceComponents
        val vOptName = genericOptionalCName(vGenBase, vTypeArgs)
        hdr.appendLine("typedef struct $vOptName { ktc_OptionalTag tag; $cName value; } $vOptName;")
    } else {
        hdr.appendLine("KTC_DEFINE_OPT($cName);")
    }
    hdr.appendLine()
}

/** Collect all methods for an interface, including inherited from super interfaces (depth-first). */
internal fun CCodeGen.collectAllIfaceMethods(info: IfaceInfo): List<FunDecl> {
    val result = mutableListOf<FunDecl>()
    val seen = mutableSetOf<String>()
    fun collect(i: IfaceInfo) {
        for (superRef in i.superInterfaces) {
            val superName = resolveIfaceName(superRef)
            val superInfo = interfaces[superName] ?: continue
            collect(superInfo)
        }
        for (m in i.methods) {
            if (m.name !in seen) { result += m; seen += m.name }
        }
    }
    collect(info)
    return result
}

/** Collect all properties for an interface, including inherited from super interfaces. */
internal fun CCodeGen.collectAllIfaceProperties(info: IfaceInfo): List<PropDecl> {
    val result = mutableListOf<PropDecl>()
    val seen = mutableSetOf<String>()
    fun collect(i: IfaceInfo) {
        for (superRef in i.superInterfaces) {
            val superName = resolveIfaceName(superRef)
            val superInfo = interfaces[superName] ?: continue
            collect(superInfo)
        }
        for (p in i.propDecls) {
            if (p.name !in seen) { result += p; seen += p.name }
        }
    }
    collect(info)
    return result
}

/** Data member name for a class inside a tagged union or single-field interface struct. */
internal fun CCodeGen.ifaceDataName(className: String): String = "${typeFlatName(className)}_data"

/** Emit implicit hashCode for a class. Uses field-based hash for data classes, identity hash otherwise. */
internal fun CCodeGen.emitImplicitHashCode(cName: String, ci: ClassInfo, isData: Boolean, isGenericClass: Boolean, members: List<Decl>) {
    if (members.any { it is FunDecl && it.name == "hashCode" }) return
    hdr.appendLine("ktc_Int ${cName}_hashCode($cName* \$self);")
    impl.appendLine("ktc_Int ${cName}_hashCode($cName* \$self) {")
    if (isData && ci.props.isNotEmpty()) {
        impl.appendLine("    ktc_Int h = 0;")
        for ((name, type) in ci.props) {
            val vKtcHash = resolveTypeName(type)
            val fieldName = if (name in ci.privateProps) "PRIV_$name" else name
            val hashExpr = if (type.nullable && vKtcHash !is KtcType.Ptr) {
                val valueExpr = "\$self->$fieldName"
                "(${valueExpr}.tag == ktc_SOME ? ${hashFieldExprKtc(vKtcHash, "${valueExpr}.value")} : 0)"
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
    hdr.appendLine("void ${cName}_toString($cName* \$self, ktc_StrBuf* sb);${maxComment}")
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
    impl.appendLine("// ── Any vtable wrappers ──")
    // toString wrapper
    impl.appendLine("static void ${cName}_toString_any(void* \$self, ktc_StrBuf* sb) {")
    impl.appendLine("    ${cName}_toString(($cName*)\$self, sb);")
    impl.appendLine("}")
    // hashCode wrapper
    impl.appendLine("static ktc_Int ${cName}_hashCode_any(void* \$self) {")
    impl.appendLine("    return ${cName}_hashCode(($cName*)\$self);")
    impl.appendLine("}")
    // equals wrapper
    impl.appendLine("static ktc_Bool ${cName}_equals_any(void* \$self, void* other) {")
    impl.appendLine("    return ${cName}_equals(*($cName*)\$self, *($cName*)other);")
    impl.appendLine("}")
    // dispose wrapper
    if (members.none { it is FunDecl && it.name == "dispose" }) {
        impl.appendLine("static void ${cName}_dispose_any(void* \$self) {")
        impl.appendLine("    (void)\$self;")
        impl.appendLine("}")
    } else {
        impl.appendLine("static void ${cName}_dispose_any(void* \$self) {")
        impl.appendLine("    ${cName}_dispose(($cName*)\$self);")
        impl.appendLine("}")
    }
    // copyWith wrapper
    impl.appendLine("static void* ${cName}_copyWith_any(void* \$self, void* alloc) {")
    impl.appendLine("    ktc_std_Allocator* a = (ktc_std_Allocator*)alloc;")
    impl.appendLine("    $cName* dst = ($cName*)a->vt->allocMem(a, sizeof($cName));")
    impl.appendLine("    if (dst) *dst = *($cName*)\$self;")
    impl.appendLine("    return dst;")
    impl.appendLine("}")
    impl.appendLine()

    // Static vtable
    hdr.appendLine("extern const ktc_core_AnyVt ${cName}_AnyVt;")
    impl.appendLine("const ktc_core_AnyVt ${cName}_AnyVt = {")
    impl.appendLine("    (void (*)(void*, void*)) ${cName}_toString_any,")
    impl.appendLine("    (ktc_Int (*)(void*)) ${cName}_hashCode_any,")
    impl.appendLine("    (ktc_Bool (*)(void*, void*)) ${cName}_equals_any,")
    impl.appendLine("    (void (*)(void*)) ${cName}_dispose_any,")
    impl.appendLine("    (void* (*)(void*, void*)) ${cName}_copyWith_any,")
    impl.appendLine("};")
    impl.appendLine()

    // _as_Any wrapper
    hdr.appendLine("ktc_Any ${cName}_as_Any($cName* \$self);")
    impl.appendLine("ktc_Any ${cName}_as_Any($cName* \$self) {")
    impl.appendLine("    return (ktc_Any){{.typeId = ${cName}_TYPE_ID}, (void*)\$self, &${cName}_AnyVt};")
    impl.appendLine("}")
    impl.appendLine()
}

/** KtcType-based overload. */
/** Emit struct field declarations (shared by emitClass and emitGenericClass). */
internal fun CCodeGen.emitStructFields(ci: ClassInfo) {
    hdr.appendLine("    ktc_core_AnySupertype __base;")
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
            val vSizeAnn = getSizeAnnotation(type)
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

/** Emit primary constructor body (shared by emitClass and emitGenericClass). */
internal fun CCodeGen.emitConstructorBody(cName: String, ci: ClassInfo) {
    val vComponents = mangledComponents[ci.name]
    if (vComponents != null) {
        // Generic instance: emit $Opt$N_ wrapper — distinct from the class name itself.
        val (vGenBase, vTypeArgs) = vComponents
        val vOptName = genericOptionalCName(vGenBase, vTypeArgs)
        hdr.appendLine("typedef struct $vOptName { ktc_OptionalTag tag; $cName value; } $vOptName;")
    } else {
        hdr.appendLine("KTC_DEFINE_OPT($cName);")
    }
    hdr.appendLine()
    val vAllCtorParams = ci.ctorProps + ci.ctorPlainParams
    val vParamStr = expandCtorParams(vAllCtorParams)
    val vParamDecl = vParamStr.ifEmpty { "void" }
    hdr.appendLine("$cName ${cName}_primaryConstructor($vParamDecl);")
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
            val vSizeAnn = getSizeAnnotation(vType)
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
                val vSizeAnn = getSizeAnnotation(vBp.typeRef)
                if (vSizeAnn != null && isSizedArrayTypeRef(vBp.typeRef)) {
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
    impl.appendLine()
}

/** Emit vtable struct for a class implementing an interface (shared by two interface-emission paths). */
internal fun CCodeGen.emitVtable(cClass: String, cIface: String, ifaceName: String, className: String, props: List<PropDecl>, methods: List<FunDecl>) {
    val isObject = objects.containsKey(className)

    // For objects, emit thin wrapper functions matching vtable signatures.
    // Object methods have no $self param but vtables require void* $self first.
    if (isObject) {
        if (methods.none { it.name == "dispose" } && !hasDisposeOverride(className)) {
            impl.appendLine("static void ${cClass}_${ifaceName}_dispose_vt(void* \$self) { (void)\$self; }")
        }
        for (m in methods) {
            val mReturnsNullable = m.returnType != null && m.returnType.nullable
            val vMRetKtc = if (m.returnType != null) resolveTypeName(m.returnType) else null
            val mRetResolved = vMRetKtc?.toInternalStr ?: ""
            val cRet = if (mReturnsNullable) optCTypeName(mRetResolved) else if (m.returnType != null) cType(m.returnType) else "void"
            val castExtra = m.params.joinToString("") { p ->
                val vKtcParam = resolveTypeName(p.type); val vPStr = vKtcParam.toInternalStr
                if (p.type.nullable) ", ${optCTypeName(vPStr)} ${p.name}" else ", ${cType(p.type)} ${p.name}"
            }
            val extraArgs = m.params.joinToString(", ") { it.name }
            val vtName = "${cClass}_${ifaceName}_${m.name}_vt"
            val targetFn = if (m.name == "dispose" && !hasDisposeOverride(className))
                null  // no-op for dispose without override
            else "${cClass}_${m.name}"
            impl.appendLine("static $cRet $vtName(void* \$self$castExtra) {")
            impl.appendLine("    (void)\$self;")
            if (targetFn != null) {
                if (cRet != "void") impl.appendLine("    return $targetFn($extraArgs);")
                else impl.appendLine("    $targetFn($extraArgs);")
            }
            impl.appendLine("}")
        }
    }

    impl.appendLine("const ${cIface}_vt ${cClass}_${ifaceName}_vt = {")
    for (p in props) {
        val ct = if (p.type != null) cType(p.type) else "ktc_Int"
        impl.appendLine("    ($ct (*)(void*)) ${cClass}_${p.name}_get,")
    }
    for (m in methods) {
        val mReturnsNullable = m.returnType != null && m.returnType.nullable
        val vMRetKtc = if (m.returnType != null) resolveTypeName(m.returnType) else null
        val mRetResolved = vMRetKtc?.toInternalStr ?: ""
        val cRet = if (mReturnsNullable) optCTypeName(mRetResolved) else if (m.returnType != null) cType(m.returnType) else "void"
        val extraCast = m.params.joinToString("") { p ->
            val vKtcParam = resolveTypeName(p.type); val vPStr = vKtcParam.toInternalStr
            if (p.type.nullable) ", ${optCTypeName(vPStr)}" else ", ${cType(p.type)}"
        }
        val fn = if (isObject) "${cClass}_${ifaceName}_${m.name}_vt"
                 else if (m.name == "dispose" && !hasDisposeOverride(className)) "ktc_core_noop_dispose"
                 else "${cClass}_${m.name}"
        impl.appendLine("    ($cRet (*)(void*$extraCast)) $fn,")
    }
    if (methods.none { it.name == "dispose" }) {
        val fnDispose = if (isObject) "${cClass}_${ifaceName}_dispose_vt"
                         else if (!hasDisposeOverride(className)) "ktc_core_noop_dispose"
                         else "${cClass}_dispose"
        impl.appendLine("    (void (*)(void*)) $fnDispose,")
    }
    impl.appendLine("};")
    impl.appendLine()
}

/** Returns true if a class or object has an explicit dispose() override. */
internal fun CCodeGen.hasDisposeOverride(className: String): Boolean {
    classes[className]?.methods?.any { it.name == "dispose" }?.let { return it }
    objects[className]?.methods?.any { it.name == "dispose" }?.let { return it }
    return false
}

internal fun CCodeGen.hashFieldExprKtc(ktc: KtcType, valueExpr: String): String = when (ktc) { // Nullable value types: hash tag + value (or 0 if null)
    is KtcType.Nullable if isValueNullableKtc(ktc) -> {
        "(${valueExpr}.tag == ktc_SOME ? ${hashFieldExprKtc(ktc.inner, "${valueExpr}.value")} : 0)"
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

/**
 * Generate the designated-initializer return expression for ClassName_as_IfaceName().
 * Format depends on how many implementors the interface has:
 *   0: (void*) shallow pointer  (fallback, no union)
 *   1: .ClassName = *$self     (single struct field, no data wrapper)
 *   2+: .data.ClassName = *$self  (tagged union)
 */
internal fun CCodeGen.ifaceAsInit(cIface: String, cClass: String, className: String, ifaceName: String): String {
    val impls = interfaceImplementors[ifaceName]
    val dataName = ifaceDataName(className)
    val typeIdField = ".__base.typeId = ${cClass}_TYPE_ID"
    return when {
        impls.isNullOrEmpty() -> "($cIface){(void*)\$self, &${cClass}_${ifaceName}_vt}"
        impls.size == 1 -> "($cIface){$typeIdField, .$dataName = *\$self, .vt = &${cClass}_${ifaceName}_vt}"
        else -> "($cIface){$typeIdField, .data.$dataName = *\$self, .vt = &${cClass}_${ifaceName}_vt}"
    }
}

/**
 * Emit vtables for a concrete class name implementing the given super interfaces.
 * Works for both non-generic and monomorphized generic classes.
 * @param declsOnly if true, only emit header declarations (for grouping with class struct).
 * @param implsOnly if true, only emit .c implementations (skip hdr lines).
 */
internal fun CCodeGen.emitInterfaceVtablesForClass(className: String, superIfaceRefs: List<TypeRef>, declsOnly: Boolean = false, implsOnly: Boolean = false) {
    val cClass = typeFlatName(className)
    val isObject = objects.containsKey(className)
    val cSelfType = if (isObject) "${cClass}_t" else cClass
    val cSelfPtr = "$cSelfType*"
    for (ifaceRef in superIfaceRefs) {
        val ifaceName = resolveIfaceName(ifaceRef)
        val iface = interfaces[ifaceName] ?: continue
        val cIface = typeFlatName(ifaceName)
        val allMethods = collectAllIfaceMethods(iface)
        val allProps = collectAllIfaceProperties(iface)

        if (!implsOnly) hdr.appendLine()
        if (!implsOnly) hdr.appendLine("// ── $className implements $ifaceName ──")
        if (!declsOnly) impl.appendLine("// ── $className implements $ifaceName ──")

        // Emit property getter wrappers
        for (p in allProps) {
            val ct = if (p.type != null) cType(p.type) else "ktc_Int"
            val getterName = "${cClass}_${p.name}_get"
            if (!implsOnly) hdr.appendLine("$ct $getterName($cSelfPtr \$self);")
            if (!declsOnly) {
                if (isObject) {
                    impl.appendLine("$ct $getterName($cSelfPtr \$self) { (void)\$self; return ${cClass}.${p.name}; }")
                } else {
                    impl.appendLine("$ct $getterName($cSelfPtr \$self) { return \$self->${p.name}; }")
                }
                impl.appendLine()
            }
        }

        // static vtable instance
        if (!implsOnly) hdr.appendLine("extern const ${cIface}_vt ${cClass}_${ifaceName}_vt;")
        if (!declsOnly) {
            emitVtable(cClass, cIface, ifaceName, className, allProps, allMethods)
        }

        // wrapping function: ClassName_as_IfaceName
        if (!implsOnly) hdr.appendLine("$cIface ${cClass}_as_${ifaceName}($cSelfPtr \$self);")
        if (!implsOnly) {
            val lines = deferredHdrLines[className]
            if (lines != null) {
                for (line in lines) hdr.appendLine(line)
                deferredHdrLines.remove(className)
            }
        }
        if (!declsOnly) {
            impl.appendLine("$cIface ${cClass}_as_${ifaceName}($cSelfPtr \$self) {")
            if (isObject) impl.appendLine("    (void)\$self;")
            impl.appendLine("    return ${ifaceAsInit(cIface, cClass, className, ifaceName)};")
            impl.appendLine("}")
            impl.appendLine()
        }

        if (!declsOnly) emitTransitiveInterfaceVtables(className, cClass, iface)
    }
}

/**
 * For interface inheritance chains, emit vtables for parent interfaces.
 * E.g., if ArrayList_Int implements MutableList_Int which extends List_Int,
 * emit ArrayList_Int_as_List_Int with the List_Int subset of the vtable.
 */
internal fun CCodeGen.emitTransitiveInterfaceVtables(className: String, cClass: String, iface: IfaceInfo) {
    for (superRef in iface.superInterfaces) {
        val superName = resolveIfaceName(superRef)
        val superIface = interfaces[superName] ?: continue
        val cSuper = typeFlatName(superName)
        val superMethods = collectAllIfaceMethods(superIface)
        val superProps = collectAllIfaceProperties(superIface)

        hdr.appendLine("// ── $className implements $superName (transitive) ──")
        impl.appendLine("// ── $className implements $superName (transitive) ──")

        // Register this class as also implementing the parent interface
        val existing = classInterfaces[className]?.toMutableList() ?: mutableListOf()
        if (superName !in existing) {
            existing += superName
            classInterfaces[className] = existing
            // Also update the reverse map for tagged-union emission
            interfaceImplementors.getOrPut(superName) { mutableListOf() }.add(className)
        }

        // static vtable instance (same class methods, but only the parent's slots)
        hdr.appendLine("extern const ${cSuper}_vt ${cClass}_${superName}_vt;")
        emitVtable(cClass, cSuper, superName, className, superProps, superMethods)
        // wrapping function
        hdr.appendLine("$cSuper ${cClass}_as_${superName}($cClass* \$self);")
        impl.appendLine("$cSuper ${cClass}_as_${superName}($cClass* \$self) {")
        impl.appendLine("    return ${ifaceAsInit(cSuper, cClass, className, superName)};")
        impl.appendLine("}")
        impl.appendLine()

        // Recurse for deeper inheritance
        emitTransitiveInterfaceVtables(className, cClass, superIface)
    }
}



// ── top-level fun ────────────────────────────────────────────────

internal fun CCodeGen.emitFun(f: FunDecl) {
    if (f.isInline) return  // inline funs are expanded at call sites, not emitted as C functions

    if (f.name != "main") {
        val paramSig = f.params.joinToString(", ") { p -> typeRefToStr(p.type) }
        val retSig = f.returnType?.let { ": ${typeRefToStr(it)}" } ?: ""
        impl.appendLine("// ══ fun ${f.name}($paramSig)$retSig ($currentSourceFile) ══")
        impl.appendLine()
    }
    val isMain = f.name == "main"
    val isMainWithArgs = isMain && f.params.size == 1 &&
            f.params[0].type.name == "Array" &&
            f.params[0].type.typeArgs.singleOrNull()?.name == "String"
    // Get siblings for overload detection
    val siblings = file.decls.filterIsInstance<FunDecl>()
    val overloadedName = methodName(f, siblings)
    val baseName = if (f.isPrivate) "PRIV_$overloadedName" else overloadedName

    val returnsNullable = !isMain && f.returnType != null && f.returnType.nullable
    val returnsSizedArray = !isMain && !returnsNullable && f.returnType != null && isSizedArrayTypeRef(f.returnType)
    val vRetKtcFun   = if (!isMain && f.returnType != null) resolveTypeName(f.returnType) else null  // KtcType of return
    val returnsArray = !isMain && !returnsNullable && !returnsSizedArray && (vRetKtcFun?.isArrayLike ?: false)
    val retResolved  = vRetKtcFun?.toInternalStr ?: f.body?.let { inferBlockType(it) } ?: ""         // string for legacy helpers
    val optRetCType = if (returnsNullable) optCTypeName(retResolved) else ""
    val cRet  = if (isMain) "int" else if (returnsSizedArray) "void" else if (returnsNullable && vRetKtcFun is KtcType.Any) "ktc_Any" else if (returnsNullable) optRetCType else if (retResolved.isNotEmpty()) cTypeStr(retResolved) else "void"
    val cName = if (isMain) "main" else funCName(baseName)
    val params = when {
        isMainWithArgs -> "int argc, char** argv"
        isMain         -> "void"
        else           -> {
            val base = expandParams(f.params)
            val extra = when {
                returnsSizedArray -> {
                    val elemCType = cTypeStr(vRetKtcFun!!.asArr!!.elem)
                    "$elemCType* \$out"
                }
                returnsArray -> "ktc_Int* \$len_out"
                else -> null
            }
            if (extra != null) {
                if (base.isEmpty()) extra else "$base, $extra"
            } else base
        }
    }

    hdr.appendLine("$cRet $cName($params);")
    impl.appendLine("$cRet $cName($params) {")

    if (isMain) {
        impl.appendLine("    ktc_core_mainInit();")
    }

    val prevState = saveFunState()
    val prevIsMain = currentFnIsMain
    currentFnReturnsNullable = returnsNullable
    currentFnReturnsArray = returnsArray
    currentFnReturnsSizedArray = returnsSizedArray
    currentFnOptReturnCTypeName = optRetCType
    if (returnsSizedArray) {
        currentFnSizedArraySize = getSizeAnnotation(f.returnType)!!
        currentFnSizedArrayElemType = cTypeStr(vRetKtcFun!!.asArr!!.elem)
    }
    currentFnReturnType = retResolved
    currentFnReturnKtcType = vRetKtcFun
    currentFnIsMain = isMain

    pushScope()
    if (isMainWithArgs) {
        // Convert argc/argv → ktc_StringArray (skip argv[0] = program name)
        val argName = f.params[0].name
        impl.appendLine("    ktc_String \$args_buf[256];")
        impl.appendLine("    ktc_Int \$nargs = (argc > 1) ? (ktc_Int)(argc - 1) : 0;")
        impl.appendLine("    if (\$nargs > 256) \$nargs = 256;")
        impl.appendLine("    for (ktc_Int \$i = 0; \$i < \$nargs; \$i++) {")
        impl.appendLine("        \$args_buf[\$i] = (ktc_String){argv[\$i + 1], (ktc_Int)strlen(argv[\$i + 1])};")
        impl.appendLine("    }")
        impl.appendLine("    ktc_String* $argName = \$args_buf;")
        impl.appendLine("    ktc_Int ${argName}\$len = \$nargs;")
        defineVar(argName, "StringArray")
    } else {
        for (p in f.params) {
            val vKtcFunParam = resolveTypeName(p.type)                  // KtcType of this parameter
            val vFunPStr     = vKtcFunParam.toInternalStr               // string for vararg/nullable/isValueNullable
            defineVar(p.name, when {
                p.isVararg -> "${vFunPStr}Array"  // vararg params are arrays (ptr + $len)
                p.type.nullable -> "${vFunPStr}?"
                else -> vFunPStr
            })
            if (p.type.nullable && isValueNullableKtc(KtcType.Nullable(vKtcFunParam))) markOptional(p.name)
        }
    }
    val savedTrampolined7 = trampolinedParams.toHashSet(); trampolinedParams.clear()
    if (!isMain) emitArrayParamCopies(f.params, "    ")
    val savedDefers = deferStack.toList()
    deferStack.clear()

    if (f.body != null) for (s in f.body.stmts) emitStmt(s, "    ")
    // Emit deferred blocks at end unless last stmt was a return (already emitted there)
    val lastStmt = f.body?.stmts?.lastOrNull()
    if (lastStmt !is ReturnStmt) emitDeferredBlocks("    ")
    if (isMain && objectsWithDispose.isNotEmpty()) {
        for (cName in objectsWithDispose.distinct()) {
            impl.appendLine("    ${cName}_dispose();")
        }
    }
    if (isMain && memTrack) {
        impl.appendLine("    fflush(stdout);")
        impl.appendLine("    ktc_core_mem_report();")
    }
    if (isMain) impl.appendLine("    return 0;")
    else if (returnsNullable && lastStmt !is ReturnStmt) {
        if (vRetKtcFun is KtcType.Any) impl.appendLine("    return (ktc_Any){0};")
        else impl.appendLine("    return ${optNone(optRetCType)};")
    }
    trampolinedParams.clear(); trampolinedParams.addAll(savedTrampolined7)
    popScope()

    deferStack.clear()
    deferStack.addAll(savedDefers)
    restoreFunState(prevState)
    currentFnIsMain = prevIsMain
    impl.appendLine("}")
    impl.appendLine()
}

// ── top-level property ───────────────────────────────────────────

internal fun CCodeGen.emitTopProp(d: PropDecl) {
    val vKtcTop = if (d.type != null) resolveTypeName(d.type) else inferExprTypeKtc(d.init)  // KtcType of prop type, or inferred
    val t       = vKtcTop?.toInternalStr ?: (inferExprType(d.init) ?: "Int")  // string for cTypeStr/defaultVal
    val ct      = cTypeStr(t)
    val cName = typeFlatName(d.name)  // top-level prop — typeFlatName falls back to prefix+name
    val tls = if (d.name in tlsProps) "ktc_core_tls " else ""
    val qual = if (!d.mutable) "const " else ""
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
