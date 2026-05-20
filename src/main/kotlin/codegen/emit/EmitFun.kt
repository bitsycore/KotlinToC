package com.bitsycore.ktc.codegen.emit

import com.bitsycore.ktc.ast.*
import com.bitsycore.ktc.codegen.*
import com.bitsycore.ktc.codegen.expr.emitStmt
import com.bitsycore.ktc.codegen.expr.genExpr
import com.bitsycore.ktc.codegen.expr.inferBlockType
import com.bitsycore.ktc.types.KtcType

// ──────────────────────────────────────────────────────────
// free functions, extension funs, generic instantiation, enum, top-level props
// ──────────────────────────────────────────────────────────

// ── extension function ───────────────────────────────────────────

internal fun CCodeGen.emitExtensionFun(f: FunDecl) {
    val recvTypeName = f.receiver!!.name
    val recvIsNullable = f.receiver.nullable
    val paramSig = f.params.joinToString(", ") { p -> "${p.name}: ${typeRefToStr(p.type)}" }
    val retSig = f.returnType?.let { ": ${typeRefToStr(it)}" } ?: ""
    maybeEmitFunBanner(f.name)
    impl.appendLine("// ══ ext fun ${recvTypeName}.${f.name}($paramSig)$retSig ($currentSourceFile) ══")
    val returnsSizedArray  = f.returnType != null && f.returnType.isSizedArray()
    val returnsSizedString = f.returnType != null && f.returnType.isSizedString()
    val returnsNullable    = f.returnType != null && f.returnType.nullable
    val vRetKtcExt  = if (f.returnType != null) resolveTypeName(f.returnType) else null  // KtcType of return, or null
    val retResolved = vRetKtcExt?.toInternalStr ?: ""                                    // string for legacy helpers
    val optRetCType = if (returnsNullable) optCTypeName(retResolved) else ""
    val cRet = when {
        returnsSizedArray  -> sizedArrayCTypeName(cTypeStr(vRetKtcExt!!.asArr!!.elem), f.returnType.getSizeAnnotation()!!)
        returnsSizedString -> sizedStringCTypeName(f.returnType.getSizeAnnotation()!!)
        returnsNullable && vRetKtcExt is KtcType.Any -> "ktc_Any"
        returnsNullable -> optRetCType
        f.returnType != null -> cType(f.returnType)
        else -> "void"
    }
    val isClassType = classes.containsKey(recvTypeName)
    val cRecvType = cType(f.receiver)    // use TypeRef to honor @Ptr annotations
    // Nullable receiver: pass as Optional struct (value) or OptionalPtr (pointer type)
    val selfParam = if (recvIsNullable) {
        val recvOptType = optCTypeName(recvTypeName)
        "$recvOptType \$self"
    } else "$cRecvType \$self"
    val extraParams = expandParams(f.params)
    val allParts = mutableListOf(selfParam)
    if (extraParams.isNotEmpty()) allParts += extraParams
    val allParams = allParts.joinToString(", ")
    val cFnName = "${typeFlatName(recvTypeName)}_${f.name}"

    hdr.appendLine("$cRet $cFnName($allParams);")
    impl.appendLine("$cRet $cFnName($allParams) {")

    val prevState = saveFunState()
    currentFnReturnsSizedArray = returnsSizedArray
    currentFnOptReturnCTypeName = optRetCType
    if (returnsSizedArray) {
        currentFnSizedArraySize = f.returnType.getSizeAnnotation()!!
        currentFnSizedArrayElemType = cTypeStr(vRetKtcExt!!.asArr!!.elem)
    }
    currentFnReturnsSizedString = returnsSizedString
    if (returnsSizedString) currentFnSizedStringSize = f.returnType.getSizeAnnotation()!!
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
    val savedTrampolined2      = trampolinedParams.toHashSet(); trampolinedParams.clear()
    val savedSizedTrampolined2 = sizedArrayTrampolinedParams.toHashSet(); sizedArrayTrampolinedParams.clear()
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
    sizedArrayTrampolinedParams.clear(); sizedArrayTrampolinedParams.addAll(savedSizedTrampolined2)
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
        // Prepend receiver as $self parameter for generic extensions
        val hasReceiver = f.receiver != null
        // Resolve return type and params under substitution
        val returnsSizedArray  = f.returnType != null && f.returnType.isSizedArray()
        val returnsSizedString = f.returnType != null && f.returnType.isSizedString()
        val vRetKtcGen   = if (f.returnType != null) resolveTypeName(f.returnType) else null   // KtcType of return, or null
        val returnsArray = !returnsSizedArray && (vRetKtcGen?.isArrayLike ?: false)             // true if return is non-sized array
        val concreteRet = genericFunConcreteReturn[mangledName]
        val cRet = when {
            returnsSizedArray  -> sizedArrayCTypeName(cTypeStr(vRetKtcGen!!.asArr!!.elem), f.returnType.getSizeAnnotation()!!)
            returnsSizedString -> sizedStringCTypeName(f.returnType.getSizeAnnotation()!!)
            concreteRet != null -> typeFlatName(concreteRet)
            f.returnType != null -> cType(f.returnType)
            else -> "void"
        }
        val cName = if (hasReceiver) {
            val recvKtc = resolveTypeName(f.receiver)
            val recvName = (recvKtc as? KtcType.Ptr)?.inner?.let { (it as? KtcType.User)?.baseName }
                ?: recvKtc.toInternalStr.removeSuffix("*").removeSuffix("?")
            if (f.receiver.annotations.any { it.name == "Ptr" }) {
                val baseFlat = typeFlatName(recvName)
                "${baseFlat.removeSuffix("_$recvName")}_Ptr$${recvName}_${f.name}"
            } else {
                "${typeFlatName(recvName)}_${f.name}"
            }
        } else funCName(mangledName)
        val baseParams = expandParams(f.params)
        val selfParam = if (hasReceiver) {
            val selfRecvKtc = resolveTypeName(f.receiver)
            val ct = if (f.receiver.nullable && selfRecvKtc !is KtcType.Ptr && selfRecvKtc !is KtcType.Nullable)
                optCTypeName(selfRecvKtc.toInternalStr)
            else cType(f.receiver)
            "$ct \$self"
        } else null
        val params = when {
            returnsArray -> {
                val extra = "ktc_Int* \$len_out"
                val p = if (selfParam != null && baseParams.isNotEmpty()) "$selfParam, $baseParams" else selfParam ?: baseParams
                if (p.isNotEmpty()) "$p, $extra" else extra
            }
            else -> if (selfParam != null && baseParams.isNotEmpty()) "$selfParam, $baseParams" else selfParam ?: baseParams
        }

        maybeEmitFunBanner(f.name)
        hdr.appendLine("$cRet $cName($params);")
        impl.appendLine("$cRet $cName($params) {")

        val prevState = saveFunState()
        currentFnReturnsArray = returnsArray
        currentFnReturnsSizedArray = returnsSizedArray
        if (returnsSizedArray) {
            currentFnSizedArraySize     = f.returnType.getSizeAnnotation()!!
            currentFnSizedArrayElemType = cTypeStr(vRetKtcGen!!.asArr!!.elem)
        }
        currentFnReturnsSizedString = returnsSizedString
        if (returnsSizedString) currentFnSizedStringSize = f.returnType.getSizeAnnotation()!!
        currentFnReturnType = concreteRet
            ?: if (f.returnType != null) {
                val vKtc = vRetKtcGen!!
                currentFnReturnKtcType = if (f.returnType.nullable) KtcType.Nullable(vKtc) else vKtc
                if (f.returnType.nullable) KtcType.Nullable(vKtc).toInternalStr else vKtc.toInternalStr
            } else ""

        pushScope()
        // Set up receiver context for generic extension functions
        if (hasReceiver) {
            val recvResolved = resolveTypeName(f.receiver)
            val recvFull = recvResolved.toInternalStr
            val recvName = recvFull.removeSuffix("?")
            val isClassType = classes.containsKey(recvName)
            currentExtRecvType = if (f.receiver.nullable) "${recvName}?" else recvName
            defineVar("\$self", if (f.receiver.nullable) "${recvName}?" else recvName)
            if (f.receiver.nullable && isValueNullableKtc(recvResolved as? KtcType.Nullable ?: KtcType.Nullable(recvResolved))) markOptional("\$self")
            if (isClassType) {
                currentClass = recvName
                selfIsPointer = f.receiver.annotations.any { it.name == "Ptr" }
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
        val savedTrampolined3      = trampolinedParams.toHashSet(); trampolinedParams.clear()
        val savedSizedTrampolined3 = sizedArrayTrampolinedParams.toHashSet(); sizedArrayTrampolinedParams.clear()
        emitArrayParamCopies(f.params, "    ")
        val savedDefers = deferStack.toList(); deferStack.clear()
        if (f.body != null) for (s in f.body.stmts) emitStmt(s, "    ", insideMethod = false)
        if (f.body?.stmts?.lastOrNull() !is ReturnStmt) emitDeferredBlocks("    ", insideMethod = false)
        deferStack.clear(); deferStack.addAll(savedDefers)
        trampolinedParams.clear(); trampolinedParams.addAll(savedTrampolined3)
        sizedArrayTrampolinedParams.clear(); sizedArrayTrampolinedParams.addAll(savedSizedTrampolined3)
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
        val matchingIface = ifaceList.find { it.startsWith("${ifaceBaseName}_") || it.startsWith("${ifaceBaseName}\$") }
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
    val n = d.entries.size
    hdr.appendLine(classBlockHeader("enum class", d.name, emptyList(), emptyList(), file.pkg ?: "", currentSourceFile, cName))
    hdr.appendLine("#define KTC_TYPE_NAME $cName")
    hdr.appendLine()
    hdr.appendLine("KTC_ENUM(")
    for ((i, e) in d.entries.withIndex()) {
        hdr.append("    KTC_RELATED($e)")
        if (i < d.entries.lastIndex) hdr.append(",")
        hdr.appendLine()
    }
    hdr.appendLine(");")
    hdr.appendLine()
    hdr.appendLine("extern const ktc_String KTC_RELATED(names[$n]);")
    hdr.appendLine("extern const KTC_TYPE_NAME KTC_RELATED(values[$n]);")
    hdr.appendLine("extern const ktc_Int KTC_RELATED(values\$len);")
    hdr.appendLine("KTC_METHOD(KTC_TYPE_NAME, valueOf)(ktc_String name);")
    hdr.appendLine()
    hdr.appendLine("#undef KTC_TYPE_NAME")
    hdr.appendLine(classBlockFooter("enum class", d.name, emptyList()))
    val nameInits = d.entries.joinToString(", ") { "ktc_core_str(\"$it\")" }
    impl.appendLine(boxSection("names"))
    impl.appendLine()
    impl.appendLine("const ktc_String ${cName}_names[$n] = {$nameInits};")
    impl.appendLine()
}

internal fun CCodeGen.emitEnumValuesData() {
    for (enumName in enumValuesCalled) {
        val info = enums[enumName] ?: continue
        val cName = typeFlatName(enumName)
        val entryNames = info.entries.joinToString(", ") { "${cName}_${it}" }
        val n = info.entries.size
        // definitions go into the enum's own .c file
        captureForDecl(enumName) {
            impl.appendLine(boxSection("values"))
            impl.appendLine()
            impl.appendLine("const $cName ${cName}_values[] = {$entryNames};")
            impl.appendLine("const ktc_Int ${cName}_values\$len = $n;")
            impl.appendLine()
        }
    }
    for (enumName in enumValueOfCalled) {
        val info = enums[enumName] ?: continue
        val cName = typeFlatName(enumName)
        // valueOf body goes into the enum's own .c file
        captureForDecl(enumName) {
            impl.appendLine(boxSection("valueOf"))
            impl.appendLine()
            impl.appendLine("$cName ${cName}_valueOf(ktc_String name) {")
            for (entry in info.entries) {
                impl.appendLine("    if (ktc_core_string_eq(name, ktc_core_str(\"$entry\"))) return ${cName}_$entry;")
            }
            impl.appendLine("    return ${cName}_${info.entries.first()};")
            impl.appendLine("}")
            impl.appendLine()
        }
    }
    enumValuesCalled.clear()
    enumValueOfCalled.clear()
}

internal fun CCodeGen.emitFun(f: FunDecl) {
    if (f.isInline) return  // inline funs are expanded at call sites, not emitted as C functions

    maybeEmitFunBanner(f.name)

    val paramSig = f.params.joinToString(", ") { p -> typeRefToStr(p.type) }
    val retSig = f.returnType?.let { ": ${typeRefToStr(it)}" } ?: ""
    impl.appendLine("// ══ fun ${f.name}($paramSig)$retSig ($currentSourceFile) ══")
    val isMain = f.name == "main"                                       // true for the Kotlin main function
    // Get siblings for overload detection
    val siblings = file.decls.filterIsInstance<FunDecl>()
    val overloadedName = methodName(f, siblings)
    val baseName = if (f.isPrivate) "PRIV_$overloadedName" else overloadedName

    val returnsNullable    = f.returnType != null && f.returnType.nullable
    val returnsSizedArray  = !returnsNullable && f.returnType != null && f.returnType.isSizedArray()
    val returnsSizedString = !returnsNullable && f.returnType != null && f.returnType.isSizedString()
    val vRetKtcFun   = if (f.returnType != null) resolveTypeName(f.returnType) else null  // KtcType of return
    val returnsArray = !returnsNullable && !returnsSizedArray && (vRetKtcFun?.isArrayLike ?: false)
    val retResolved  = vRetKtcFun?.toInternalStr ?: f.body?.let { inferBlockType(it) } ?: ""  // string for legacy helpers
    val optRetCType = if (returnsNullable) optCTypeName(retResolved) else ""
    val cRet = when {
        returnsSizedArray  -> sizedArrayCTypeName(cTypeStr(vRetKtcFun!!.asArr!!.elem), f.returnType.getSizeAnnotation()!!)
        returnsSizedString -> sizedStringCTypeName(f.returnType.getSizeAnnotation()!!)
        returnsNullable && vRetKtcFun is KtcType.Any -> "ktc_Any"
        returnsNullable -> optRetCType
        retResolved.isNotEmpty() -> cTypeStr(retResolved)
        else -> "void"
    }
    val cName = funCName(baseName)                                      // always prefixed, including main
    val base = expandParams(f.params)
    val extra = when {
        returnsArray -> "ktc_Int* \$len_out"
        else -> null
    }
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
        currentFnSizedArrayElemType = cTypeStr(vRetKtcFun!!.asArr!!.elem)
    }
    currentFnReturnsSizedString = returnsSizedString
    if (returnsSizedString) currentFnSizedStringSize = f.returnType.getSizeAnnotation()!!
    currentFnReturnType    = retResolved
    currentFnReturnKtcType = vRetKtcFun
    currentFnIsMain = false                                             // main is now a regular void function

    pushScope()
    for (p in f.params) {
        val vKtcFunParam = resolveTypeName(p.type)                      // KtcType of this parameter
        val vFunPStr     = vKtcFunParam.toInternalStr                   // string for vararg/nullable/isValueNullable
        defineVar(p.name, when {
            p.isVararg -> "${vFunPStr}Array"  // vararg params are arrays (ptr + $len)
            p.type.nullable -> "${vFunPStr}?"
            else -> vFunPStr
        })
        if (p.type.nullable && isValueNullableKtc(KtcType.Nullable(vKtcFunParam))) markOptional(p.name)
    }
    val savedTrampolined7      = trampolinedParams.toHashSet(); trampolinedParams.clear()
    val savedSizedTrampolined7 = sizedArrayTrampolinedParams.toHashSet(); sizedArrayTrampolinedParams.clear()
    if (isMain) {
        /* main's array params come from main.c already laid out in memory — alias without copying. */
        for (vP in f.params) {
            if (!vP.type.isRawArray()) continue
            val vKtcMP   = resolveTypeName(vP.type)
            val vArrElem = vKtcMP.asArr!!.elem                           // element KtcType
            val vECType  = if (vArrElem is KtcType.Nullable)
                               optCTypeName(vArrElem.inner.toInternalStr)
                           else cTypeStr(vArrElem)
            impl.appendLine("    $vECType* local\$${vP.name} = ($vECType*)${vP.name}.data;")
            trampolinedParams += vP.name
            }
        } else {
        emitArrayParamCopies(f.params, "    ")
        }
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
    else if (returnsNullable && lastStmt !is ReturnStmt) {
        if (vRetKtcFun is KtcType.Any) impl.appendLine("    return (ktc_Any){0};")
        else impl.appendLine("    return ${optNone(optRetCType)};")
    }
    trampolinedParams.clear(); trampolinedParams.addAll(savedTrampolined7)
    sizedArrayTrampolinedParams.clear(); sizedArrayTrampolinedParams.addAll(savedSizedTrampolined7)
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
