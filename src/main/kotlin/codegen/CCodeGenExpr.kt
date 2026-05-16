package com.bitsycore.ktc.codegen

import com.bitsycore.ktc.ast.*
import com.bitsycore.ktc.codegen.mapping.arrayElementCTypeKtc
import com.bitsycore.ktc.types.KtcType

/**
 * ── Expression Codegen ──────────────────────────────────────────────────
 *
 * Translates Kotlin expressions into C expressions. This is the largest
 * and most complex section of the transpiler. The central function is
 * [genExpr] which dispatches on the expression AST node type.
 *
 * ## Main entry points:
 *
 *   [genExpr]         — central expression dispatcher (see below for full list)
 *   [genName]         — resolve variable/field/this names with smart-cast unwrap
 *   [genBin]           — binary ops with special handling for String, Pair, null, in/!in
 *   [genCall]          — function/constructor call, built-ins (HeapAlloc, arrayOf...)
 *   [genMethodCall]    — method dispatch: built-in, class method, interface vtable, extension
 *   [genDot]           — field access, enum values, object properties, pointer deref
 *   [genSafeDot]       — nullable-safe field access (?.)
 *   [genNotNull]       — not-null assertion (!!)
 *   [genIfExpr]        — if-expression → C ternary or pre-stmt temp
 *   [genWhenExpr]      — when-expression → nested ternary or pre-stmt temp
 *   [genStrTemplate]   — string template → StrBuf-based concatenation
 *   [genToString]      — toString() dispatch (primitive, data class, default hash)
 *   [genSbAppend]      — StrBuf append for all types
 *   [genArrayOfExpr]   — array literal (arrayOf/byteArrayOf/etc.)
 *   [genNewArray]      — Array<T>(size) constructor → alloca
 *   [genLValue]         — l-value for assignment target
 *
 * ## genExpr dispatch table:
 *   IntLit, LongLit, DoubleLit, FloatLit, BoolLit, CharLit, StrLit, NullLit,
 *   ThisExpr, NameExpr, BinExpr, PrefixExpr, PostfixExpr, CallExpr, DotExpr,
 *   SafeDotExpr, IndexExpr, IfExpr, WhenExpr, NotNullExpr, ElvisExpr,
 *   StrTemplateExpr, IsCheckExpr, CastExpr, FunRefExpr, LambdaExpr
 *
 * ## State accessed:
 *   classes, interfaces, enums, objects, funSigs, extensionFuns, classInterfaces,
 *   scopes (lookupVar), lambdaParamSubst, currentClass, currentObject, selfIsPointer,
 *   currentStmtLine, currentInd, typeSubst, inlineFunDecls, inlineExtFunDecls,
 *   activeLambdas, pairTypes, pairTypeComponents, tripleTypeComponents,
 *   classArrayTypes, classCompanions, preStmts, hdr, impl
 *
 * ## Dependencies:
 *   Calls into CCodeGenStmts.kt (emitStmt, emitInlineCall, emitBlock)
 *   Calls into CCodeGenInfer.kt (inferExprType, inferMethodReturnType, ...)
 *   Calls into CCodeGenCTypes.kt (cType, cTypeStr, resolveTypeName, optNone, optSome, ...)
 */

// ═══════════════════════════ Expression codegen ═══════════════════

/** Generate an expression for use as a C function argument.
 *  String literals are emitted as raw C strings (not ktc_core_str wrapped). */
internal fun CCodeGen.genCArg(e: Expr): String = when (e) {
    is StrLit -> "\"${escapeStr(e.value)}\""
    else -> genExpr(e)
}

fun CCodeGen.genExpr(e: Expr): String = when (e) {
    is IntLit -> if (e.hex) "0x${e.value.toString(16)}" else "${e.value}"
    is LongLit -> if (e.hex) "0x${e.value.toString(16)}LL" else "${e.value}LL"
    is UIntLit -> if (e.hex) "0x${e.value.toString(16)}U" else "${e.value}U"
    is ULongLit -> if (e.hex) "0x${e.value.toString(16)}ULL" else "${e.value}ULL"
    is DoubleLit -> "${e.value}"
    is FloatLit -> "${e.value}f"
    is BoolLit -> if (e.value) "true" else "false"
    is CharLit -> "'${escapeC(e.value)}'"
    is StrLit -> "ktc_core_str(\"${escapeStr(e.value)}\")"
    is NullLit -> "NULL"
    is ThisExpr -> {
        val inlineThis = lambdaParamSubst["\$this"]
        if (inlineThis != null) return inlineThis
        val selfKtc = lookupVarKtc("\$self")
        if (selfKtc != null && isOptional("\$self") && selfKtc !is KtcType.Nullable) {
            "\$self.value"
        } else if (selfIsPointer) "(*\$self)" else "\$self"
    }

    is NameExpr -> genName(e)
    is BinExpr -> genBin(e)
    is PrefixExpr -> "(${e.op}${genExpr(e.expr)})"
    is PostfixExpr -> "(${genExpr(e.expr)}${e.op})"
    is CallExpr -> genCall(e)
    is DotExpr -> genDot(e)
    is SafeDotExpr -> genSafeDot(e)
    is IndexExpr -> {
        val objType = inferExprType(e.obj)                                            // String? object type
        val objTypeKtc = inferExprTypeKtc(e.obj)                                     // KtcType? object type
        val objTypeCoreKtc = (objTypeKtc as? KtcType.Nullable)?.inner ?: objTypeKtc  // KtcType? stripped Nullable
        val vIdxClassInfo = classInfoFor(objTypeCoreKtc)                              // non-null if object is a class
        val vIdxIfaceInfo = ifaceInfoFor(objTypeCoreKtc)                              // non-null if object is an interface
        if (objType == "String") {
            // String indexing: str[i] → str.ptr[i] (returns char)
            "${genExpr(e.obj)}.ptr[${genExpr(e.index)}]"
        } else if (vIdxClassInfo != null) {
            // Class with operator get() method → operator[] dispatch
            val methodDecl = vIdxClassInfo.methods.find { it.name == "get" && it.isOperator }
            if (methodDecl != null) {
                val recv = genExpr(e.obj)
                val idx = genExpr(e.index)
                if (methodDecl.returnType?.nullable == true) {
                    genNullableMethodCall(vIdxClassInfo.baseName, "${vIdxClassInfo.flatName}_get", "&$recv, $idx", methodDecl)
                } else {
                    "${vIdxClassInfo.flatName}_get(&$recv, $idx)"
                }
            } else {
                "${genExpr(e.obj)}.ptr[${genExpr(e.index)}]"
            }
        } else if (objTypeCoreKtc is KtcType.Ptr) {
            // Ptr<T>/Value<T> with operator get() → pointer-based dispatch
            val baseClass = objTypeCoreKtc.inner
            val baseName = (baseClass as? KtcType.User)?.baseName ?: baseClass.toInternalStr
            val methodDecl = classes[baseName]?.methods?.find { it.name == "get" && it.isOperator }
            if (methodDecl != null) {
                val recv = genExpr(e.obj)
                val idx = genExpr(e.index)
                if (methodDecl.returnType?.nullable == true) {
                    genNullableMethodCall(baseName, "${typeFlatName(baseName)}_get", "$recv, $idx", methodDecl)
                } else {
                    "${typeFlatName(baseName)}_get($recv, $idx)"
                }
            } else {
                "${genExpr(e.obj)}[${genExpr(e.index)}]"
            }
        } else if (vIdxIfaceInfo != null) {
            // Interface with operator get() in vtable → operator[] dispatch
            val ifaceMethod = vIdxIfaceInfo.methods.find { it.name == "get" && it.isOperator }
                ?: collectAllIfaceMethods(vIdxIfaceInfo).find { it.name == "get" && it.isOperator }
            if (ifaceMethod != null) {
                val recv = genExpr(e.obj)
                val idx = genExpr(e.index)
                val vIdxSelfArg = ifaceVtableSelf(vIdxIfaceInfo.name, recv)
                if (ifaceMethod.returnType?.nullable == true) {
                    val retBase = resolveMethodReturnType(vIdxIfaceInfo.baseName, ifaceMethod.returnType).removeSuffix("?")
                    val optType = optCTypeName("${retBase}?")
                    val t = tmp()
                    preStmts += "$optType $t = $recv.vt->get($vIdxSelfArg, $idx);"
                    markOptional(t)
                    defineVar(t, "${retBase}?")
                    t
                } else {
                    "$recv.vt->get($vIdxSelfArg, $idx)"
                }
            } else {
                "${genExpr(e.obj)}.ptr[${genExpr(e.index)}]"
            }
        } else if (objTypeCoreKtc != null && objTypeCoreKtc.isArrayLike) {
            // Typed pointer or array: direct indexing
            "${genExpr(e.obj)}[${genExpr(e.index)}]"
        } else {
            "${genExpr(e.obj)}.ptr[${genExpr(e.index)}]"
        }
    }

    is IfExpr -> genIfExpr(e)
    is WhenExpr -> genWhenExpr(e)
    is NotNullExpr -> genNotNull(e)
    is ElvisExpr -> {
        val lt = inferExprType(e.left)
        val l = genExpr(e.left)
        val rt = inferExprType(e.right)
        val rtKtc = inferExprTypeKtc(e.right)
        // If right side returns Nothing or Unit/void (e.g., error("msg")), emit non-null assertion
        if (rt != null && (rt == "Nothing" || rt == "Unit" || rt.removeSuffix("?") == "Nothing")) {
            val baseType = lt?.removeSuffix("?") ?: "void*"
            val ct = cTypeStr(baseType)
            val t = tmp()
            preStmts += "$ct $t = $l;"
            val r = genExpr(e.right)
            preStmts += "if (!$t) { $r; }"
            return t
        }
        val r = genExpr(e.right)
        val ltKtc = inferExprTypeKtc(e.left)
        if (ltKtc != null && isValueNullableKtc(ltKtc)) {
            "($l.tag == ktc_SOME ? $l.value : $r)"
        } else if (ltKtc is KtcType.Nullable && ltKtc.inner is KtcType.Ptr) {
            "($l != NULL ? $l : $r)"
        } else {
            "($l != NULL ? $l : $r)"
        }
    }

    is StrTemplateExpr -> genStrTemplate(e)
    is IsCheckExpr -> {
        val targetKtc = resolveTypeName(e.type)                                   // KtcType for is-check target
        val target = targetKtc.toInternalStr                                             // String for fallback/array checks
        val inner = genExpr(e.expr)
        val exprKtc = inferExprTypeKtc(e.expr)
        val exprKtcCore = (exprKtc as? KtcType.Nullable)?.inner ?: exprKtc
        // ktc_IfacePtr is a value struct even though the KTC type is Ptr<Interface>
        val isIfacePtr = exprKtcCore is KtcType.Ptr && exprKtcCore.inner is KtcType.User && (exprKtcCore.inner as KtcType.User).kind == KtcType.UserKind.Interface
        val memOp = if (exprKtcCore is KtcType.Ptr && !isIfacePtr) "->" else "."
        val vIsClassInfo = classInfoFor(targetKtc)                                    // non-null if target is a user class
        val vIsIfaceInfo = ifaceInfoFor(targetKtc)                                    // non-null if target is an interface
        val check = if (vIsClassInfo != null) {
            "${inner}${memOp}__base.typeId == ${vIsClassInfo.flatName}_TYPE_ID"
        } else if (vIsIfaceInfo != null) {
            val impls = classInterfaces.filter { (_, ifaces) -> target in ifaces }.keys
            if (impls.isEmpty()) "false"
            else impls.joinToString(" || ") { "${inner}${memOp}__base.typeId == ${typeFlatName(it)}_TYPE_ID" }
        } else if (targetKtc.isArrayLike) {
            if (exprKtcCore != null && exprKtcCore.isArrayLike) {
                if (exprKtcCore.toInternalStr == target) "true" else "false"
            } else {
                val arrayId = getTypeId(target)
                "(${inner}${memOp}__array_type_id == $arrayId)"
            }
        } else if (targetKtc !is KtcType.User || targetKtc.kind != KtcType.UserKind.Class) {
            val isSourceNullable = exprKtc is KtcType.Nullable
            val isSourceAny = exprKtcCore is KtcType.Any
            if (exprKtcCore != null && !isSourceAny && exprKtcCore !is KtcType.Ptr) {
                if (exprKtcCore.toInternalStr == target) {
                    // Nullable source: check non-null (tag check for value Optional, != NULL for pointers)
                    if (isSourceNullable && isValueNullableKtc(exprKtc)) "(${inner}.tag == ktc_SOME)"
                    else if (isSourceNullable) "(${inner} != NULL)"
                    else "true"
                } else "false"
            } else {
                val typeId = getTypeId(target)
                    "(${inner}${memOp}__base.typeId == $typeId)"
            }
        } else {
            "/* is-check: unknown type '${target}' */ true"
        }
        if (e.negated) "!($check)" else "($check)"
    }

    is CastExpr -> {
        val targetKtc = resolveTypeName(e.type)                                   // KtcType for cast target
        val target = targetKtc.toInternalStr                                             // String for fallback/cTypeStr calls
        val inner = genExpr(e.expr)
        val srcType = inferExprType(e.expr)?.removeSuffix("?")
        val srcKtc = inferExprTypeKtc(e.expr)
        val srcKtcCore = (srcKtc as? KtcType.Nullable)?.inner ?: srcKtc
        val isPtr = srcKtcCore is KtcType.Ptr
        val vCastClassInfo = classInfoFor(targetKtc)                                  // non-null if target is a user class
        val vCastIfaceInfo = ifaceInfoFor(targetKtc)                                  // non-null if target is an interface
        if (e.safe) {
            val optCType = optCTypeName("$target?")
            val memOp = if (isPtr) "->" else "."
            val check = if (vCastClassInfo != null) {
                "${inner}${memOp}__base.typeId == ${vCastClassInfo.flatName}_TYPE_ID"
            } else if (vCastIfaceInfo != null) {
                val impls = classInterfaces.filter { (_, ifaces) -> target in ifaces }.keys
                if (impls.isEmpty()) "false"
                else impls.joinToString(" || ") { "${inner}${memOp}__base.typeId == ${typeFlatName(it)}_TYPE_ID" }
            } else if (targetKtc !is KtcType.User || targetKtc.kind != KtcType.UserKind.Class) {
                val typeId = getTypeId(target)
                "${inner}${memOp}__base.typeId == $typeId"
            } else {
                "true"
            }
            val castVal = if (vCastIfaceInfo != null) {
                val srcFlatName2 = if (srcType != null && (classes.containsKey(srcType) || interfaces.containsKey(srcType))) typeFlatName(srcType) else vCastIfaceInfo.flatName
                val addrExpr2 = if ('(' in inner) { val vT = tmp(); preStmts += "${cTypeStr(srcType ?: "")} $vT = ($inner);"; "&$vT" } else "&($inner)"
                "${srcFlatName2}_as_${vCastIfaceInfo.baseName}($addrExpr2)"
            } else if (srcType != null && interfaces.containsKey(srcType) && classes.containsKey(target)) {
                // Source is an interface, target is a concrete class — extract from tagged union
                ifaceUnionAccess(srcType, target, inner)
            } else if (srcKtc is KtcType.Any || (srcKtcCore is KtcType.Ptr && srcKtcCore.inner is KtcType.Any)) {
                "(*(${cTypeStr(target)}*)(${inner}${memOp}data))"
            } else {
                "(${cTypeStr(target)})($inner)"
            }
            "($check) ? ${optSome(optCType, castVal)} : ${optNone(optCType)}"
        } else if (vCastIfaceInfo != null) {
            val srcFlatName = if (srcType != null && (classes.containsKey(srcType) || interfaces.containsKey(srcType))) typeFlatName(srcType) else vCastIfaceInfo.flatName
            // Rvalue (e.g. constructor call) needs a temp to take its address
            val addrExpr = if ('(' in inner) {
                val vTmp = tmp(); preStmts += "${cTypeStr(srcType ?: "")} $vTmp = ($inner);"; "&$vTmp"
            } else "&($inner)"
            "${srcFlatName}_as_${vCastIfaceInfo.baseName}($addrExpr)"
        } else if (srcType != null && interfaces.containsKey(srcType) && classes.containsKey(target)) {
            // Source is an interface, target is a concrete class — extract from tagged union
            ifaceUnionAccess(srcType, target, inner)
        } else if (srcKtc is KtcType.Any || (srcKtcCore is KtcType.Ptr && srcKtcCore.inner is KtcType.Any)) {
            val memOp = if (isPtr) "->" else "."
            "(*(${cTypeStr(target)}*)(${inner}${memOp}data))"
        } else {
            "(${cType(e.type)})($inner)"
        }
    }

    is FunRefExpr -> funCName(e.name)    // ::functionName → C function pointer
    is LambdaExpr -> error("Lambda can only be passed to an inline function, not used as a standalone expression")
}

// ── names (may resolve to enum, object field, self->field) ───────

internal fun CCodeGen.genName(e: NameExpr): String {
    val subst = lambdaParamSubst[e.name]
    if (subst != null) return subst
    val curType = lookupVar(e.name)
    val curKtc = lookupVarKtc(e.name)
    // Check if it's a known variable in scope
    if (curType != null) {
        if (currentClass != null && classes[currentClass]?.props?.any { it.first == e.name } == true) {
            val ci = classes[currentClass]!!
            val fieldName = if (e.name in ci.privateProps) "PRIV_${e.name}" else e.name
            val fieldRef = if (selfIsPointer) "\$self->${fieldName}" else "\$self.${fieldName}"
            // If field is stored as Optional but accessed after smart-cast (non-nullable context), unwrap
            val fieldType = ci.props.find { it.first == e.name }?.second
            if (fieldType?.nullable == true && curKtc !is KtcType.Nullable) {
                return "$fieldRef.value"
            }
            return fieldRef
        }
        val vCurObj = currentObject
        if (vCurObj != null && objects[vCurObj]?.props?.any { it.first == e.name } == true) {
            val objInfo = objects[vCurObj]!!
            val fn = if (e.name in objInfo.privateProps) "PRIV_${e.name}" else e.name
            return "${typeFlatName(vCurObj)}.$fn"
        }
        // Inside a class nested within an object: resolve to parent object's field
        val parentObj = currentClass?.substringBefore('$')
        if (parentObj != null && currentObject == null
            && objects[parentObj]?.props?.any { it.first == e.name } == true
        ) {
            val objInfo = objects[parentObj]!!
            val fn = if (e.name in objInfo.privateProps) "PRIV_${e.name}" else e.name
            return "${typeFlatName(parentObj)}.$fn"
        }
        // Trampolined array param: redirect to local stack copy
        if (e.name in trampolinedParams) return "local$${e.name}"
        // Any trampoline smart-cast: narrowed from Any, dereference .data
        if (curKtc !is KtcType.Any && isAnySmartCastVar(e.name)) {
            val ct = cTypeStr(curType)
            return "(*(($ct*)(${e.name}.data)))"
        }
        // Optional var smart-casted to non-nullable: unwrap to .value
        if (isOptional(e.name) && curKtc !is KtcType.Nullable) {
            return "${e.name}.value"
        }
        return e.name
    }
    // Top-level property: apply package prefix
    if (e.name in topProps) return typeFlatName(e.name)
    // Object singleton: resolve to global instance name
    if (e.name in objects) return typeFlatName(e.name)
    if (e.name in enums) return typeFlatName(e.name)
    // Inside a class nested within an object: resolve to parent object's field/function
    val parentObj2 = currentClass?.substringBefore('$')
    if (parentObj2 != null && currentObject == null) {
        val objInfo = objects[parentObj2]
        if (objInfo?.props?.any { it.first == e.name } == true) {
            val fn = if (e.name in objInfo.privateProps) "PRIV_${e.name}" else e.name
            return "${typeFlatName(parentObj2)}.$fn"
        }
    }
    // Bare field access when $self has been narrowed from interface in extension function
    if (currentExtRecvType != null && interfaces.containsKey(currentExtRecvType)) {
        val vNarrowedSelf = lookupVar("\$self")
        if (vNarrowedSelf != null && classes.containsKey(vNarrowedSelf)) {
            val vCi = classes[vNarrowedSelf]!!
            if (vCi.props.any { it.first == e.name }) {
                return "${ifaceUnionAccess(currentExtRecvType!!, vNarrowedSelf, "\$self")}.${e.name}"
            }
        }
    }
    return e.name
}

// ── binary ───────────────────────────────────────────────────────

internal fun CCodeGen.genBin(e: BinExpr): String {
    val lt = inferExprType(e.left)
    /* `to` infix → Pair; use stdlib path when stdlib Pair class is loaded, else intrinsic */
    if (e.op == "to") {
        val aType = inferExprType(e.left) ?: "Int" // left operand type
        val aTypeKtc = inferExprTypeKtc(e.left)
        val bType = inferExprType(e.right) ?: "Int" // right operand type
        val bTypeKtc = inferExprTypeKtc(e.right)
        if (genericClassDecls.containsKey("Pair")) {
            // stdlib Pair<A,B> is active — emit primaryConstructor call
            val vMangledName = recordGenericInstantiation("Pair", listOf(aType, bType)) // e.g. "Pair$2_Int_String"
            materializeGenericInstantiations() // ensure ClassInfo entry exists
            val vCi = classes[vMangledName] // concrete class info with correct pkg
            if (vCi != null) {
                val vLExpr = genExpr(e.left) // C expression for first
                val vRExpr = genExpr(e.right) // C expression for second
                return "${vCi.flatName}_primaryConstructor($vLExpr, $vRExpr)"
            }
        }
    }
    // User-defined infix inline extension function dispatch
    val vInfixDecl = inlineExtFunDecls[e.op]
    if (vInfixDecl != null) {
        val vRecvType = inferExprType(e.left) // receiver type string
        val vRecvTypeKtc = inferExprTypeKtc(e.left)
        val vArgType = inferExprType(e.right) // single argument type string
        val vArgTypeKtc = inferExprTypeKtc(e.right)
        val vSavedSubst = typeSubst // save outer typeSubst
        if (vInfixDecl.typeParams.isNotEmpty()) {
            typeSubst = inferInlineFunSubst(vInfixDecl, vRecvType, listOf(vArgType))
        }
        val vRetType = vInfixDecl.returnType // declared return TypeRef
        if (vRetType != null) {
            val vResultName = "\$ir${inlineCounter++}" // temp var for inline result
            impl.appendLine("$currentInd${cType(vRetType)} $vResultName;")
            val vRecvExpr = genExpr(e.left) // C expression for receiver
            emitInlineCall(
                vInfixDecl, listOf(Arg(expr = e.right)), currentInd, false,
                receiverExpr = vRecvExpr, receiverType = vRecvType?.removeSuffix("?"), resultVar = vResultName
            )
            typeSubst = vSavedSubst
            return vResultName
        } else {
            val vRecvExpr = genExpr(e.left)
            emitInlineCall(
                vInfixDecl, listOf(Arg(expr = e.right)), currentInd, false,
                receiverExpr = vRecvExpr, receiverType = vRecvType?.removeSuffix("?")
            )
            typeSubst = vSavedSubst
            return ""
        }
    }
    // null comparison
    if ((e.op == "==" || e.op == "!=") && (e.left is NullLit || e.right is NullLit)) {
        val nonNull = if (e.left is NullLit) e.right else e.left
        // Warn: comparing a non-nullable type to null — always true/false
        val nonNullType = inferExprType(nonNull)
        val nonNullKtc = inferExprTypeKtc(nonNull)
        if (nonNullKtc != null && nonNullKtc !is KtcType.Nullable) {
            val always = if (e.op == "==") "false" else "true"
            codegenWarning("Null check on non-nullable '$nonNullType' is always $always")
        }
        // this == null / this != null inside nullable-receiver extension
        if (nonNull is ThisExpr) {
            val thisKtc = inferExprTypeKtc(nonNull)
            if (thisKtc is KtcType.Nullable) {
                // @Ptr T? → compare pointer to NULL
                if (thisKtc.inner is KtcType.Ptr && thisKtc.inner.inner !is KtcType.Arr) {
                    if ((thisKtc.inner as KtcType.Ptr).inner is KtcType.User
                        && ((thisKtc.inner as KtcType.Ptr).inner as KtcType.User).kind == KtcType.UserKind.Interface)
                        return if (e.op == "==") "!\$self.vt" else "\$self.vt"
                    return if (e.op == "==") "\$self == NULL" else "\$self != NULL"
                }
                if (isValueNullableKtc(thisKtc)) {
                    return if (e.op == "==") "\$self.tag == ktc_NONE" else "\$self.tag == ktc_SOME"
                }
                return if (e.op == "==") "!\$self\$has" else "\$self\$has"
            }
        }
        val varName = (nonNull as? NameExpr)?.name
        val varKtc = if (varName != null) lookupVarKtc(varName) else null
        if (varKtc != null) {
            // @Ptr T? → compare pointer to NULL (exclude typed array pointers like IntArray)
            if (varKtc is KtcType.Nullable && varKtc.inner is KtcType.Ptr && varKtc.inner.inner !is KtcType.Arr) {
                // @Ptr InterfaceType → check vt for null (ktc_IfacePtr is a struct)
                if ((varKtc.inner as KtcType.Ptr).inner is KtcType.User
                    && ((varKtc.inner as KtcType.Ptr).inner as KtcType.User).kind == KtcType.UserKind.Interface)
                    return if (e.op == "==") "!${varName}.vt" else "${varName}.vt"
                return if (e.op == "==") "$varName == NULL" else "$varName != NULL"
            }
            // Any? nullable → compare data pointer to NULL
            if (varKtc is KtcType.Nullable && varKtc.inner is KtcType.Any) {
                return if (e.op == "==") "$varName.data == NULL" else "$varName.data != NULL"
            }
            // Value nullable → use Optional tag
            if (isValueNullableKtc(varKtc)) {
                return if (e.op == "==") "$varName.tag == ktc_NONE" else "$varName.tag == ktc_SOME"
            }
            // Trampolined array param: null is data == NULL — use local copy for consistency
            if (varName in trampolinedParams) {
                return if (e.op == "==") "local$$varName == NULL" else "local$$varName != NULL"
            }
            // Fallback for other nullable
            if (varKtc is KtcType.Nullable) {
                return if (e.op == "==") "$varName == NULL" else "$varName != NULL"
            }
        }
        // DotExpr on nullable field (e.g. np.x == null)
        val nonNullKtc2 = nonNullKtc ?: inferExprTypeKtc(nonNull)
        if (nonNull is DotExpr && nonNullKtc2 is KtcType.Nullable && isValueNullableKtc(nonNullKtc2)) {
            val dotExpr = genExpr(nonNull)
            return if (e.op == "==") "$dotExpr.tag == ktc_NONE" else "$dotExpr.tag == ktc_SOME"
        }
    }
    // Nullable T? vs non-null value: generate Optional-aware comparison
    if (e.op in setOf("==", "!=", "<", ">", "<=", ">=")) {
        val ltKtc = inferExprTypeKtc(e.left)
        val rtKtc = inferExprTypeKtc(e.right)
        // Both are nullable value types — compare tags and values
        if (ltKtc is KtcType.Nullable && isValueNullableKtc(ltKtc) &&
            rtKtc is KtcType.Nullable && isValueNullableKtc(rtKtc)) {
            val leftExpr = genExpr(e.left)
            val rightExpr = genExpr(e.right)
            val vIsStr = ltKtc.inner is KtcType.Str
            return when (e.op) {
                "==" -> if (vIsStr) "($leftExpr.tag == $rightExpr.tag && ($leftExpr.tag == ktc_NONE || ktc_core_string_eq($leftExpr.value, $rightExpr.value)))"
                        else "($leftExpr.tag == $rightExpr.tag && ($leftExpr.tag == ktc_NONE || $leftExpr.value == $rightExpr.value))"
                "!=" -> if (vIsStr) "($leftExpr.tag != $rightExpr.tag || ($leftExpr.tag == ktc_SOME && !ktc_core_string_eq($leftExpr.value, $rightExpr.value)))"
                        else "($leftExpr.tag != $rightExpr.tag || ($leftExpr.tag == ktc_SOME && $leftExpr.value != $rightExpr.value))"
                else -> "($leftExpr.tag == ktc_SOME && $rightExpr.tag == ktc_SOME && $leftExpr.value ${e.op} $rightExpr.value)"
            }
        }
        // Left is nullable value type, right is non-null → wrap in tag check
        if (ltKtc is KtcType.Nullable && isValueNullableKtc(ltKtc) &&
            rtKtc != null && rtKtc !is KtcType.Nullable && e.right !is NullLit) {
            val leftExpr = genExpr(e.left)
            val rightExpr = genExpr(e.right)
            val vIsStr = ltKtc.inner is KtcType.Str
            return when (e.op) {
                "==" -> if (vIsStr) "($leftExpr.tag == ktc_SOME && ktc_core_string_eq($leftExpr.value, $rightExpr))"
                        else "($leftExpr.tag == ktc_SOME && $leftExpr.value == $rightExpr)"
                "!=" -> if (vIsStr) "($leftExpr.tag != ktc_SOME || !ktc_core_string_eq($leftExpr.value, $rightExpr))"
                        else "($leftExpr.tag != ktc_SOME || $leftExpr.value != $rightExpr)"
                else -> "($leftExpr.tag == ktc_SOME && $leftExpr.value ${e.op} $rightExpr)"
            }
        }
        // Right is nullable value type, left is non-null
        if (rtKtc is KtcType.Nullable && isValueNullableKtc(rtKtc) &&
            ltKtc != null && ltKtc !is KtcType.Nullable && e.left !is NullLit) {
            val leftExpr = genExpr(e.left)
            val rightExpr = genExpr(e.right)
            val vIsStr = rtKtc.inner is KtcType.Str
            return when (e.op) {
                "==" -> if (vIsStr) "($rightExpr.tag == ktc_SOME && ktc_core_string_eq($leftExpr, $rightExpr.value))"
                        else "($rightExpr.tag == ktc_SOME && $leftExpr == $rightExpr.value)"
                "!=" -> if (vIsStr) "($rightExpr.tag != ktc_SOME || !ktc_core_string_eq($leftExpr, $rightExpr.value))"
                        else "($rightExpr.tag != ktc_SOME || $leftExpr != $rightExpr.value)"
                else -> "($rightExpr.tag == ktc_SOME && $leftExpr ${e.op} $rightExpr.value)"
            }
        }
    }
    // Class == / != → ClassName_equals (all classes, not just data)
    // Also handles @Ptr types by resolving to base class and dereferencing
    val ltKtc2 = inferExprTypeKtc(e.left)
    var isPtr = false
    val classKeyFromKtc: String? = when (ltKtc2) {
        is KtcType.Ptr -> { isPtr = true; (ltKtc2.inner as? KtcType.User)?.baseName }
        is KtcType.Nullable if ltKtc2.inner is KtcType.Ptr -> {
            isPtr = true
            (ltKtc2.inner.inner as? KtcType.User)?.baseName
        }
        is KtcType.User -> ltKtc2.baseName.takeIf { classes.containsKey(it) }
        else -> null
    }
    val classKey = classKeyFromKtc ?: lt?.takeIf { classes.containsKey(it) }
    if ((e.op == "==" || e.op == "!=") && classKey != null) {
        val leftExpr = genExpr(e.left)
        val rightExpr = genExpr(e.right)
        // Dereference if the operands are pointers (e.g. @Ptr Vec2)
        val l = if (isPtr) "(*$leftExpr)" else leftExpr
        val r = if (isPtr) "(*$rightExpr)" else rightExpr
        val eq = "${typeFlatName(classKey)}_equals($l, $r)"
        return if (e.op == "==") eq else "!$eq"
    }
    // String == → ktc_core_string_eq
    val ltKtc3 = inferExprTypeKtc(e.left)
    if (e.op == "==" && ltKtc3 is KtcType.Str) {
        return "ktc_core_string_eq(${genExpr(e.left)}, ${genExpr(e.right)})"
    }
    if (e.op == "!=" && ltKtc3 is KtcType.Str) {
        return "!ktc_core_string_eq(${genExpr(e.left)}, ${genExpr(e.right)})"
    }
    // String <, >, <=, >= → ktc_core_string_cmp
    if (ltKtc3 is KtcType.Str && e.op in listOf("<", ">", "<=", ">=")) {
        return "(ktc_core_string_cmp(${genExpr(e.left)}, ${genExpr(e.right)}) ${e.op} 0)"
    }
    // String + → ktc_core_string_cat
    if (e.op == "+" && (lt == "String" || inferExprType(e.right) == "String")) {
        return genStringConcat(e)
    }
    // in / !in → operator contains() dispatch on class or interface
    if (e.op == "in" || e.op == "!in") {
        val rt = inferExprType(e.right)                                               // String? right-side type
        val rtKtc = inferExprTypeKtc(e.right)                                         // KtcType? right-side type
        val rtCoreKtc = (rtKtc as? KtcType.Nullable)?.inner ?: rtKtc                 // KtcType? stripped Nullable
        val negated = e.op == "!in"
        val vContClassInfo = classInfoFor(rtCoreKtc)                                  // non-null if right side is a class
        val vContIfaceInfo = ifaceInfoFor(rtCoreKtc)                                  // non-null if right side is an interface
        if (vContClassInfo != null) {
            val containsMethod = vContClassInfo.methods.find { (it.name == "contains" || it.name == "containsKey") && it.isOperator }
            if (containsMethod != null) {
                val recv = genExpr(e.right)
                val elem = genExpr(e.left)
                val call = "${vContClassInfo.flatName}_${containsMethod.name}(&$recv, $elem)"
                return if (negated) "!$call" else call
            }
        }
        if (rtKtc is KtcType.Ptr || (rtKtc is KtcType.Nullable && rtKtc.inner is KtcType.Ptr)) {
            val ptrKtc = (rtKtc as? KtcType.Nullable)?.inner ?: rtKtc
            val baseName = (ptrKtc as KtcType.Ptr).inner
            val baseClass = (baseName as? KtcType.User)?.baseName ?: baseName.toInternalStr
            val containsMethod = classes[baseClass]?.methods?.find { (it.name == "contains" || it.name == "containsKey") && it.isOperator }
            if (containsMethod != null) {
                val recv = genExpr(e.right)
                val elem = genExpr(e.left)
                val call = "${typeFlatName(baseClass)}_${containsMethod.name}($recv, $elem)"
                return if (negated) "!$call" else call
            }
        }
        if (vContIfaceInfo != null) {
            val allMethods = collectAllIfaceMethods(vContIfaceInfo)
            val containsMethod = allMethods.find { (it.name == "contains" || it.name == "containsKey") && it.isOperator }
            if (containsMethod != null) {
                val recv = genExpr(e.right)
                val elem = genExpr(e.left)
                val call = "$recv.vt->${containsMethod.name}(${ifaceVtableSelf(vContIfaceInfo.name, recv)}, $elem)"
                return if (negated) "!$call" else call
            }
        }
        // Fallback: range-based `in` for IntRange, etc.
        if (rt != null && (rt == "IntRange" || rt.endsWith("Range"))) {
            val lo = genExpr((e.right as? BinExpr)?.left ?: e.right)
            val hi = genExpr((e.right as? BinExpr)?.right ?: e.right)
            val v = genExpr(e.left)
            return if (negated) "($v < $lo || $v > $hi)" else "($v >= $lo && $v <= $hi)"
        }
    }
    // Bitwise infix operators → C operators
    if (e.op == "and") return "(${genExpr(e.left)} & ${genExpr(e.right)})"
    if (e.op == "or") return "(${genExpr(e.left)} | ${genExpr(e.right)})"
    if (e.op == "xor") return "(${genExpr(e.left)} ^ ${genExpr(e.right)})"
    if (e.op == "shl") return "(${genExpr(e.left)} << ${genExpr(e.right)})"
    if (e.op == "shr") return "(${genExpr(e.left)} >> ${genExpr(e.right)})"
    if (e.op == "ushr") {
        val leftType = inferExprType(e.left)
        val leftTypeKtc = inferExprTypeKtc(e.left)
        val l = genExpr(e.left)
        val r = genExpr(e.right)
        // For unsigned types, >> is already unsigned; for signed, cast to unsigned first
        if (leftType == "UInt" || leftType == "UByte" || leftType == "UShort" || leftType == "ULong") {
            return "($l >> $r)"
        }
        if (leftType == "Long") return "(((ktc_ULong)$l) >> $r)"
        // Default: cast to unsigned equivalent
        return "(((ktc_UInt)$l) >> $r)"
    }
    // Check: division or modulo by constant zero
    if ((e.op == "/" || e.op == "%") && e.right is IntLit && e.right.value == 0L) {
        codegenError("Division by zero: constant 0 used as divisor in '${e.op}' expression")
    }
    return "(${genExpr(e.left)} ${e.op} ${genExpr(e.right)})"
}

/*
Infers a typeSubst map for a generic inline extension function from the receiver
and argument types at a call site. Handles only direct type-parameter references:
when the receiver type IS a type param (e.g. receiver = "A") or when a param type
IS a type param (e.g. param type = "B"). Nested generics require Phase-1 scanner work.
*/
internal fun inferInlineFunSubst(
    inDecl: FunDecl,
    inReceiverType: String?,
    inArgTypes: List<String?>
): Map<String, String> {
    val vSubst = mutableMapOf<String, String>() // type-param name → concrete type
    val vTypeParams = inDecl.typeParams.toSet() // set of type-param names for O(1) lookup
    if (inReceiverType != null && inDecl.receiver != null) {
        val vRecvParamName = inDecl.receiver.name // type param name used as receiver (e.g. "A")
        if (vRecvParamName in vTypeParams) vSubst[vRecvParamName] = inReceiverType.removeSuffix("?")
    }
    inDecl.params.forEachIndexed { vIdx, vParam ->
        val vArgType = inArgTypes.getOrNull(vIdx)?.removeSuffix("?") ?: return@forEachIndexed
        val vParamTypeName = vParam.type.name // type param name used as param type (e.g. "B")
        if (vParamTypeName in vTypeParams) vSubst[vParamTypeName] = vArgType
    }
    return vSubst
}

internal fun CCodeGen.genStringConcat(e: BinExpr): String {
    val buf = tmp()
    preStmts += "ktc_Char ${buf}[512];"
    return "ktc_core_string_cat($buf, sizeof($buf), ${genExpr(e.left)}, ${genExpr(e.right)})"
}

// ── function / constructor call ──────────────────────────────────

internal fun CCodeGen.genCall(e: CallExpr): String {
    // Method call: DotExpr(receiver, method)(args)
    if (e.callee is DotExpr) {
        // Inline extension function call in value position
        val inlineExt = inlineExtFunDecls[e.callee.name]
        if (inlineExt != null) {
            val recvExpr = genExpr(e.callee.obj)
            val recvKtType = inferExprType(e.callee.obj)?.removeSuffix("?")
            val recvKtTypeKtc = inferExprTypeKtc(e.callee.obj)
            val retType = inlineExt.returnType
            // Set up typeSubst for generic inline extension functions so return-type resolution works
            val vSavedSubst = typeSubst
            if (inlineExt.typeParams.isNotEmpty()) {
                val vArgTypes = e.args.map { inferExprType(it.expr) } // concrete arg types at call site
                typeSubst = inferInlineFunSubst(inlineExt, recvKtType, vArgTypes)
            }
            if (retType == null) {
                emitInlineCall(inlineExt, e.args, currentInd, false, receiverExpr = recvExpr, receiverType = recvKtType)
                typeSubst = vSavedSubst
                return ""
            }
            val resultName = "\$ir${inlineCounter++}"
            impl.appendLine("$currentInd${cType(retType)} $resultName;")
            emitInlineCall(inlineExt, e.args, currentInd, false, receiverExpr = recvExpr, receiverType = recvKtType, resultVar = resultName)
            typeSubst = vSavedSubst
            return resultName
        }
        // ClassName.allocWith(allocator, args...) → allocator-based heap construction
        if (e.callee.name == "allocWith" && e.callee.obj is NameExpr && e.args.isNotEmpty()) {
            val className = e.callee.obj.name
            // Array.allocWith(allocator, size) → typed heap array allocation
            if (className == "Array" || className == "RawArray") {
                val elemName = when {
                    e.typeArgs.isNotEmpty() ->
                        typeSubst[e.typeArgs[0].name] ?: e.typeArgs[0].name
                    heapAllocTargetType != null && heapAllocTargetType!!.name == className && heapAllocTargetType!!.typeArgs.isNotEmpty() ->
                        typeSubst[heapAllocTargetType!!.typeArgs[0].name] ?: heapAllocTargetType!!.typeArgs[0].name
                    heapAllocTargetType != null && heapAllocTargetType!!.name == "Array" && heapAllocTargetType!!.typeArgs.isNotEmpty() ->
                        typeSubst[heapAllocTargetType!!.typeArgs[0].name] ?: heapAllocTargetType!!.typeArgs[0].name
                    else -> "Int"
                }
                val elemC = cTypeStr(elemName)
                val sizeExpr = genExpr(e.args[1].expr)
                val allocExpr = genExpr(e.args[0].expr)
                val t = tmp()
                val allocObjName = (e.args[0].expr as? NameExpr)?.name

                // Use trampoline dispatch for allocWith.
                // If allocator is already @Ptr (trampoline), use directly.
                // If it's a concrete object name, wrap into ktc_IfacePtr.
                val allocArgKtc = inferExprTypeKtc(e.args[0].expr)
                val allocArgCore = (allocArgKtc as? KtcType.Nullable)?.inner ?: allocArgKtc
                val isTrampoline = allocArgCore is KtcType.Ptr && allocArgCore.inner is KtcType.User && (allocArgCore.inner as KtcType.User).kind == KtcType.UserKind.Interface
                val ifExpr: String
                if (isTrampoline) {
                    ifExpr = allocExpr
                } else if (allocObjName != null && objects.containsKey(allocObjName)) {
                    val cConcrete = typeFlatName(allocObjName)
                    val typeId = getTypeId(allocObjName)
                    preStmts += "ktc_IfacePtr $t = {{$typeId}, (const void*)&${cConcrete}_Allocator_vt, (void*)&$allocExpr};"
                    ifExpr = t
                } else {
                    // Assume it's already a trampoline or compatible
                    ifExpr = allocExpr
                }
                preStmts += "$elemC* ${t}_ptr = ($elemC*)((ktc_std_Allocator_vt*)$ifExpr.vt)->allocMem($ifExpr.obj, sizeof($elemC) * (size_t)($sizeExpr));"
                if (className == "Array") preStmts += "const ktc_Int ${t}_ptr\$len = $sizeExpr;"
                return "${t}_ptr"
            }
            if (classes.containsKey(className) && !classes[className]!!.isGeneric) {
                val cName = typeFlatName(className)
                val allocExpr = genExpr(e.args[0].expr)
                val ctorArgs = e.args.drop(1).joinToString(", ") { genExpr(it.expr) }
                val t = tmp()
                val allocObjName = (e.args[0].expr as? NameExpr)?.name
                val isAllocObj = allocObjName != null && classInterfaces[allocObjName]?.contains("Allocator") == true
                val allocInit = if (isAllocObj) {
                    "${typeFlatName(allocObjName!!)}_as_Allocator(&$allocExpr)"
                } else {
                    allocExpr
                }
                preStmts += "ktc_std_Allocator $t = $allocInit;"
                val dataField = ifaceDataName(allocObjName ?: className)
                preStmts += "$cName* ${t}_ptr = ($cName*)${t}.vt->allocMem((void*)&${t}.${dataField}, sizeof($cName));"
                preStmts += "if (${t}_ptr) *${t}_ptr = ${cName}_primaryConstructor($ctorArgs);"
                return "${t}_ptr"
            }
            if (genericClassDecls.containsKey(className)) {
                val typeArgs = e.typeArgs.ifEmpty { heapAllocTargetType?.typeArgs ?: emptyList() }
                if (typeArgs.isNotEmpty()) {
                    val resolvedArgs = typeArgs.map { t ->
                        val sub = substituteTypeParams(t)
                        if (sub.nullable) "${resolveTypeNameStr(sub)}?" else resolveTypeNameStr(sub)
                    }
                    val mangled = mangledGenericName(className, resolvedArgs)
                    if (classes.containsKey(mangled)) {
                        val cName = typeFlatName(mangled)
                        val allocExpr = genExpr(e.args[0].expr)
                        val ctorArgs = e.args.drop(1).joinToString(", ") { arg ->
                            val argExpr = genExpr(arg.expr)
                            val argName = (arg.expr as? NameExpr)?.name
                            if (argName != null && objects.containsKey(argName)) {
                                val cConcrete = typeFlatName(argName); val typeId = getTypeId(argName)
                                val tCtor = tmp()
                                preStmts += "ktc_IfacePtr $tCtor = {{$typeId}, (const void*)&${cConcrete}_Allocator_vt, (void*)&$argExpr};"
                                tCtor
                            } else argExpr
                        }
                        val t = tmp()
                        val allocObjName = (e.args[0].expr as? NameExpr)?.name
                        val isAllocObj = allocObjName != null && classInterfaces[allocObjName]?.contains("Allocator") == true
                        val allocInit = if (isAllocObj) {
                            "${typeFlatName(allocObjName!!)}_as_Allocator(&$allocExpr)"
                        } else { allocExpr }
                        preStmts += "ktc_std_Allocator $t = $allocInit;"
                        val dataField = ifaceDataName(allocObjName ?: className)
                        preStmts += "$cName* ${t}_ptr = ($cName*)${t}.vt->allocMem((void*)&${t}.${dataField}, sizeof($cName));"
                        preStmts += "if (${t}_ptr) *${t}_ptr = ${cName}_primaryConstructor($ctorArgs);"
                        return "${t}_ptr"
                    }
                }
            }
        }
        // C package passthrough: c.printf(...) → printf(...)
        // String literals are emitted as raw C strings (not ktc_core_str wrapped)
        if (e.callee.obj is NameExpr && e.callee.obj.name == "c" && lookupVar(e.callee.obj.name) == null) {
            val cFnName = e.callee.name
            // Route malloc/free/realloc through tracking wrappers when mem-track enabled
            val fnName = when {
                memTrack && cFnName == "malloc" -> "ktc_core_malloc"
                memTrack && cFnName == "free" -> "ktc_core_free"
                memTrack && cFnName == "realloc" -> "ktc_core_realloc"
                else -> cFnName
            }
            val argStr = e.args.joinToString(", ") { genCArg(it.expr) }
            val extra = if (memTrack && cFnName in setOf("malloc", "free", "realloc")) ", ${ktSrc()}" else ""
            return "$fnName($argStr$extra)"
        }
        // Nested class constructor: Outer.Inner(...) or A.B.C(...) → flat name
        fun flattenDotCallee(callee: Expr): String? {
            if (callee is NameExpr) return callee.name
            if (callee is DotExpr && callee.obj is NameExpr)
                return "${callee.obj.name}$${callee.name}"
            if (callee is DotExpr) {
                val left = flattenDotCallee(callee.obj)
                if (left != null) return "$left$${callee.name}"
            }
            return null
        }

        val flatCallee = flattenDotCallee(e.callee)
        if (flatCallee != null && (classes.containsKey(flatCallee) || genericClassDecls.containsKey(flatCallee))) {
            val synthCall = CallExpr(NameExpr(flatCallee), e.args, e.typeArgs)
            return genCall(synthCall)
        }
        // Reject non-safe call on nullable receiver (unless the extension accepts nullable receiver,
        // or the nullable is a Ptr/Heap/Value<Array<T>> where deref() etc. are valid on nullable)
        val recvKtc = inferExprTypeKtc(e.callee.obj)
        if (recvKtc is KtcType.Nullable) {
            val innerKtc = recvKtc.inner
            val isIndirectArray = innerKtc is KtcType.Ptr && innerKtc.inner is KtcType.Arr
            if (!hasNullableReceiverExt(innerKtc.toInternalStr, e.callee.name) && !isIndirectArray && innerKtc !is KtcType.Arr) {
                // .ptr() on nullable value type → produces nullable pointer (NULL if NONE)
                if (e.callee.name == "ptr") return genMethodCall(e.callee, e.args)
                val recvSrc = (e.callee.obj as? NameExpr)?.name ?: e.callee.obj.toString()
                val recvType = recvKtc.toInternalStr
                codegenError("Only safe (?.) calls are allowed on a nullable receiver of type '$recvType': $recvSrc.${e.callee.name}()")
            }
        }
        return genMethodCall(e.callee, e.args)
    }
    if (e.callee is SafeDotExpr) return genSafeMethodCall(e.callee, e.args)

    val name = (e.callee as? NameExpr)?.name ?: return "${genExpr(e.callee)}(${e.args.joinToString(", ") { genExpr(it.expr) }})"
    val args = e.args

    // Inline function call in value position — emit body as C block, capture return via result var
    val inlineCandidates = inlineFunDecls[name]
    val inlineDecl = when {
        inlineCandidates == null -> null
        inlineCandidates.size == 1 -> inlineCandidates[0]
        else -> {
            // Overloaded: pick by exact argument count match, or the nearest
            val exact = inlineCandidates.find { it.params.size == args.size }
            exact ?: inlineCandidates.minByOrNull { kotlin.math.abs(it.params.size - args.size) }
        }
    }
    if (inlineDecl != null) {
        // Set up typeSubst for generic inline functions so T → concrete type
        val vSavedSubst = typeSubst
        if (inlineDecl.typeParams.isNotEmpty()) {
            val vSubst = mutableMapOf<String, String>()
            for ((i, param) in inlineDecl.params.withIndex()) {
                if (i >= args.size) break
                val argType = inferExprType(args[i].expr)?.removeSuffix("?") ?: continue
                val argTypeKtc = inferExprTypeKtc(args[i].expr)
                matchTypeParam(param.type, argType, inlineDecl.typeParams.toSet(), vSubst)
            }
            if (vSubst.isNotEmpty()) typeSubst = vSubst
        }
        val retType = inlineDecl.returnType
        if (retType == null) {
            emitInlineCall(inlineDecl, e.args, currentInd, false)
            typeSubst = vSavedSubst
            return ""
        }
        val resultName = "\$ir${inlineCounter++}"
        impl.appendLine("$currentInd${cType(retType)} $resultName;")
        emitInlineCall(inlineDecl, e.args, currentInd, false, resultVar = resultName)
        typeSubst = vSavedSubst
        return resultName
    }

    // Active lambda call in value position (inside inline body expansion)
    val activeLambda = activeLambdas[name]
    if (activeLambda != null) {
        val savedSubst = lambdaParamSubst.toMap()
        val savedTypes = lambdaParamTypes.toMap()
        activeLambda.expr.params.forEachIndexed { i, pName ->
            val arg = e.args.getOrNull(i)
            if (arg != null) {
                lambdaParamSubst[pName] = genExpr(arg.expr)
                val t = (if (arg.expr is ThisExpr) lambdaParamTypes["\$this"] else null)
                    ?: inferExprType(arg.expr)
                    ?: activeLambda.paramTypes.getOrElse(i) { "" }
                if (t.isNotEmpty()) lambdaParamTypes[pName] = t
            }
        }
        val body = activeLambda.expr.body
        val allButLast = if (body.size > 1) body.dropLast(1) else emptyList()
        for (stmt in allButLast) emitStmt(stmt, currentInd)
        val result = when (val last = body.lastOrNull()) {
            is ExprStmt -> genExpr(last.expr)
            is ReturnStmt -> if (last.value != null) genExpr(last.value) else ""
            null -> ""
            else -> {
                emitStmt(last, currentInd); ""
            }
        }
        lambdaParamSubst.clear(); lambdaParamSubst.putAll(savedSubst)
        lambdaParamTypes.clear(); lambdaParamTypes.putAll(savedTypes)
        return result
    }

    // Built-in functions
    when (name) {
        "println" -> return genPrintln(args)
        "print" -> return genPrint(args)
        "HeapAlloc" -> {
            if (e.typeArgs.isNotEmpty()) {
                val ta = e.typeArgs[0]
                // HeapAlloc<RawArray<T>>(n) / HeapAlloc<Array<T>>(n) → typed allocation
                if (ta.name == "RawArray" && ta.typeArgs.isNotEmpty() ||
                    ta.name == "Array" && ta.typeArgs.isNotEmpty()) {
                    val elemName = typeSubst[ta.typeArgs[0].name] ?: ta.typeArgs[0].name
                    val elemC = cTypeStr(elemName)
                    val sizeExpr = genExpr(args[0].expr)
                    val t = tmp()
                    preStmts += "$elemC* $t = ($elemC*)${tMalloc("sizeof($elemC) * (size_t)($sizeExpr)")};"
                    if (ta.name == "Array") preStmts += "const ktc_Int ${t}\$len = $sizeExpr;"
                    return t
                }
                var typeName = typeSubst[ta.name] ?: ta.name
                // Resolve generic class: HeapAlloc<MyList<Int>>(...) → MyList_Int_new(...)
                if (ta.typeArgs.isNotEmpty() && classes.containsKey(typeName) && classes[typeName]!!.isGeneric) {
                    typeName = mangledGenericName(typeName, ta.typeArgs.map { it.name })
                }
                // Class heap constructor: HeapAlloc<MyClass>(args) → inline malloc + primaryConstructor
                if (classes.containsKey(typeName)) {
                    val cName = typeFlatName(typeName)
                    val argStr = args.joinToString(", ") { genExpr(it.expr) }
                    val t = tmp()
                    preStmts += "$cName* $t = ($cName*)${tMalloc("sizeof($cName)")};"
                    preStmts += "if ($t) *$t = ${cName}_primaryConstructor($argStr);"
                    return t
                }
                // HeapAlloc<T>() with no args → single element: (T*)malloc(sizeof(T))
                val elemC = cTypeStr(typeName)
                if (args.isEmpty()) {
                    return "($elemC*)${tMalloc("sizeof($elemC)")}"
                }
                // HeapAlloc<T>(n) → array allocation: (T*)malloc(sizeof(T) * (size_t)(n))
                return "($elemC*)${tMalloc("sizeof($elemC) * (size_t)(${genExpr(args[0].expr)})")}"
            }
            if (heapAllocTargetType != null) {
                val tt = heapAllocTargetType!!
                if (tt.name == "RawArray" && tt.typeArgs.isNotEmpty() ||
                    tt.name == "Array" && tt.typeArgs.isNotEmpty()) {
                    val elemName = typeSubst[tt.typeArgs[0].name] ?: tt.typeArgs[0].name
                    val elemC = cTypeStr(elemName)
                    val sizeExpr = genExpr(args[0].expr)
                    val t = tmp()
                    preStmts += "$elemC* $t = ($elemC*)${tMalloc("sizeof($elemC) * (size_t)($sizeExpr)")};"
                    if (tt.name == "Array") preStmts += "const ktc_Int ${t}\$len = $sizeExpr;"
                    return t
                }
                var typeName = typeSubst[tt.name] ?: tt.name
                if (tt.typeArgs.isNotEmpty() && classes.containsKey(typeName) && classes[typeName]!!.isGeneric) {
                    typeName = mangledGenericName(typeName, tt.typeArgs.map { it.name })
                }
                if (classes.containsKey(typeName)) {
                    val cName = typeFlatName(typeName)
                    val argStr = args.joinToString(", ") { genExpr(it.expr) }
                    val t = tmp()
                    preStmts += "$cName* $t = ($cName*)${tMalloc("sizeof($cName)")};"
                    preStmts += "if ($t) *$t = ${cName}_primaryConstructor($argStr);"
                    return t
                }
                val elemC = cTypeStr(typeName)
                if (args.isEmpty()) {
                    return "($elemC*)${tMalloc("sizeof($elemC)")}"
                }
                return "($elemC*)${tMalloc("sizeof($elemC) * (size_t)(${genExpr(args[0].expr)})")}"
            }
            return tMalloc("(size_t)(${genExpr(args[0].expr)})")
        }

        "HeapArrayZero" -> {
            fun genHeapArrayZeroBranch(ta: TypeRef): String {
                val isArray = ta.name == "Array" && ta.typeArgs.isNotEmpty()
                val isRawArray = ta.name == "RawArray" && ta.typeArgs.isNotEmpty()
                val elemName = if (isArray || isRawArray) {
                    typeSubst[ta.typeArgs[0].name] ?: ta.typeArgs[0].name
                } else {
                    typeSubst[ta.name] ?: ta.name
                }
                val elemC = cTypeStr(elemName)
                val sizeExpr = genExpr(args[0].expr)
                val t = tmp()
                preStmts += "$elemC* $t = ($elemC*)${tCalloc("(size_t)($sizeExpr)", "sizeof($elemC)")};"
                if (isArray) preStmts += "const ktc_Int ${t}\$len = $sizeExpr;"
                return t
            }
            if (e.typeArgs.isNotEmpty()) return genHeapArrayZeroBranch(e.typeArgs[0])
            if (heapAllocTargetType != null) return genHeapArrayZeroBranch(heapAllocTargetType!!)
            return tCalloc("(size_t)(${genExpr(args[0].expr)})", "(size_t)(${genExpr(args[1].expr)})")
        }

        "HeapArrayResize" -> {
            if (e.typeArgs.isNotEmpty()) {
                val ta = e.typeArgs[0]
                val isArray = ta.name == "Array" && ta.typeArgs.isNotEmpty()
                val elemName = if (isArray) {
                    typeSubst[ta.typeArgs[0].name] ?: ta.typeArgs[0].name
                } else {
                    typeSubst[ta.name] ?: ta.name
                }
                val elemC = cTypeStr(elemName)
                val ptrExpr = genExpr(args[0].expr)
                val sizeExpr = genExpr(args[1].expr)
                val t = tmp()
                preStmts += "$elemC* $t = ($elemC*)${tRealloc(ptrExpr, "sizeof($elemC) * (size_t)($sizeExpr)")};"
                if (isArray) {
                    preStmts += "const ktc_Int ${t}\$len = $sizeExpr;"
                }
                return t
            }
            if (heapAllocTargetType != null) {
                val tt = heapAllocTargetType!!
                val isArray = tt.name == "Array" && tt.typeArgs.isNotEmpty()
                val elemName = if (isArray) {
                    typeSubst[tt.typeArgs[0].name] ?: tt.typeArgs[0].name
                } else {
                    typeSubst[tt.name] ?: tt.name
                }
                val elemC = cTypeStr(elemName)
                val ptrExpr = genExpr(args[0].expr)
                val sizeExpr = genExpr(args[1].expr)
                val t = tmp()
                preStmts += "$elemC* $t = ($elemC*)${tRealloc(ptrExpr, "sizeof($elemC) * (size_t)($sizeExpr)")};"
                if (isArray) {
                    preStmts += "const ktc_Int ${t}\$len = $sizeExpr;"
                }
                return t
            }
            return tRealloc(genExpr(args[0].expr), "(size_t)(${genExpr(args[1].expr)})")
        }

        "HeapFree" -> return tFree(genExpr(args[0].expr))
        "byteArrayOf", "shortArrayOf", "intArrayOf", "longArrayOf",
        "floatArrayOf", "doubleArrayOf", "booleanArrayOf", "charArrayOf",
        "ubyteArrayOf", "ushortArrayOf", "uintArrayOf", "ulongArrayOf" -> {
            // handled in emitVarDecl; if used as expr, wrap in compound literal
            return genArrayOfExpr(name, args)
        }

        "arrayOf" -> {
            return genArrayOfExpr(name, args, e.typeArgs.getOrNull(0))
        }

        "heapArrayOf" -> {
            return genHeapArrayOfExpr(args, e.typeArgs.getOrNull(0))
        }

        "arrayOfNulls" -> {
            val typeArg = e.typeArgs.getOrNull(0)
            val elemName = typeSubst[typeArg?.name ?: "Int"] ?: (typeArg?.name ?: "Int")
            val optCType = optCTypeName("${elemName}?")
            return genNewArray(optCType, args)
        }

        "enumValues" -> {
            if (e.typeArgs.isNotEmpty()) {
                val enumName = e.typeArgs[0].name
                val resolved = typeSubst[enumName] ?: enumName
                enumValuesCalled.add(resolved)
                return "${typeFlatName(resolved)}_values"
            }
            error("enumValues requires a type argument")
        }

        "enumValueOf" -> {
            if (e.typeArgs.isNotEmpty() && e.args.isNotEmpty()) {
                val enumName = e.typeArgs[0].name
                val resolved = typeSubst[enumName] ?: enumName
                enumValuesCalled.add(resolved)
                enumValueOfCalled.add(resolved)
                val nameExpr = genExpr(e.args[0].expr)
                return "${typeFlatName(resolved)}_valueOf($nameExpr)"
            }
            error("enumValueOf requires a type argument and a name")
        }

        "ByteArray" -> return genNewArray("ktc_Byte", args)
        "ShortArray" -> return genNewArray("ktc_Short", args)
        "IntArray" -> return genNewArray("ktc_Int", args)
        "LongArray" -> return genNewArray("ktc_Long", args)
        "FloatArray" -> return genNewArray("ktc_Float", args)
        "DoubleArray" -> return genNewArray("ktc_Double", args)
        "BooleanArray" -> return genNewArray("ktc_Bool", args)
        "CharArray" -> return genNewArray("ktc_Char", args)
        "UByteArray" -> return genNewArray("ktc_UByte", args)
        "UShortArray" -> return genNewArray("ktc_UShort", args)
        "UIntArray" -> return genNewArray("ktc_UInt", args)
        "ULongArray" -> return genNewArray("ktc_ULong", args)
        // Generic Array<T>(size) constructor — stack-allocated like IntArray(size)
        "Array" -> {
            if (e.typeArgs.isNotEmpty()) {
                val elemC = cTypeStr(resolveTypeName(e.typeArgs[0]))  // KtcType for element C type
                if (args.size >= 2 && args[1].expr is LambdaExpr) {
                    return genNewArrayWithLambda(elemC, args)
                }
                return genNewArray(elemC, args)
            }
        }
    }

    // StringBuffer constructor (intrinsic — only when no user-defined class named StringBuffer)
    if (name == "StringBuffer" && args.size == 2
        && !classes.containsKey("StringBuffer") && !genericClassDecls.containsKey("StringBuffer")
    ) {
        val ptrExpr = genExpr(args[0].expr)
        val lenExpr = genExpr(args[1].expr)
        val capExpr = when (args[0].expr) {
            is NullLit -> "0"
            // array.ptr() — .ptr() is a DotExpr called as method with no extra args
            is DotExpr if (args[0].expr as DotExpr).name == "ptr" -> {
                val arrExpr = genExpr((args[0].expr as DotExpr).obj)
                "$arrExpr\$len"
            }
            // array.ptr() — wrapped in CallExpr from method-call syntax
            is CallExpr if (args[0].expr as CallExpr).callee is DotExpr
                    && ((args[0].expr as CallExpr).callee as DotExpr).name == "ptr" -> {
                val dot = (args[0].expr as CallExpr).callee as DotExpr
                val arrExpr = genExpr(dot.obj)
                "$arrExpr\$len"
            }

            else -> {
                val ptrKtc = inferExprTypeKtc(args[0].expr)
                if (ptrKtc is KtcType.Ptr && ptrKtc.inner is KtcType.Arr)
                    "${ptrExpr}\$len"
                else
                    "0x7FFFFFFF"
            }
        }
        return "(ktc_StrBuf){$ptrExpr, $lenExpr, $capExpr}"
    }

    // Function pointer call: variable with function type → just call it
    val varType = lookupVar(name)
    if (varType != null && isFuncType(varType)) {
        val argStr = args.joinToString(", ") { genExpr(it.expr) }
        return "$name($argStr)"
    }

    // Constructor call (known class)
    // Resolve nested class name within current object/class scope
    var resolvedName = name
    if (!classes.containsKey(name)) {
        val parent = currentObject ?: currentClass
        if (parent != null) {
            val nested = "$parent$${name}"
            if (classes.containsKey(nested)) resolvedName = nested
        }
    }
    // Handle generic class constructor: explicit type args or LHS inference
    val effectiveTypeArgs = e.typeArgs.ifEmpty { heapAllocTargetType?.typeArgs ?: emptyList() }
    if (classes.containsKey(resolvedName) && classes[resolvedName]!!.isGeneric && effectiveTypeArgs.isNotEmpty()) {
        val resolvedTypeArgs = effectiveTypeArgs.map { t ->
            val sub = substituteTypeParams(t)
            if (sub.nullable) "${resolveTypeNameStr(sub)}?" else resolveTypeNameStr(sub)
        }
        val mangledName = mangledGenericName(resolvedName, resolvedTypeArgs)
        val ci = classes[mangledName] ?: error("Generic class '$mangledName' not materialized (typeSubst=$typeSubst)")
        val templateDecl = genericClassDecls[resolvedName]
        val vAllParams = ci.ctorProps + ci.ctorPlainParams
        val vCtorParamList = vAllParams.map { Param(it.name, it.typeRef) }
        val vFilledArgs = fillDefaults(args, vCtorParamList, vAllParams.associate {
            val vCp = templateDecl?.ctorParams?.find { vP -> vP.name == it.name }
            it.name to vCp?.default
        }, resolvedName, strict = true)
        val expandedArgs = expandCallArgs(vFilledArgs, vCtorParamList, isCtorCall = true)
        return "${ci.flatName}_primaryConstructor($expandedArgs)"
    }
    // Handle generic class constructor: MyList<Int>(8) → MyList_Int_primaryConstructor(8)
    if (classes.containsKey(resolvedName) && classes[resolvedName]!!.isGeneric && e.typeArgs.isNotEmpty()) {
        // Apply typeSubst so type params (e.g. T) resolve to concrete types (e.g. Int)
        // when inside a generic function body
        val resolvedTypeArgs = e.typeArgs.map { t ->
            val sub = substituteTypeParams(t)
            if (sub.nullable) "${resolveTypeNameStr(sub)}?" else resolveTypeNameStr(sub)
        }
        val mangledName = mangledGenericName(resolvedName, resolvedTypeArgs)
        val ci = classes[mangledName] ?: error("Generic class '$mangledName' not materialized (typeSubst=$typeSubst)")
        val templateDecl = genericClassDecls[resolvedName]
        val vAllParams = ci.ctorProps + ci.ctorPlainParams                        // all ctor parameters
        val vCtorParamList = vAllParams.map { Param(it.name, it.typeRef) }        // as Param list
        val vFilledArgs = fillDefaults(args, vCtorParamList, vAllParams.associate {
            val vCp = templateDecl?.ctorParams?.find { vP -> vP.name == it.name }  // matching ctor param
            it.name to vCp?.default
        }, resolvedName, strict = true)
        val expandedArgs = expandCallArgs(vFilledArgs, vCtorParamList, isCtorCall = true)
        return "${ci.flatName}_primaryConstructor($expandedArgs)"                   // ci.flatName replaces pfx(mangledName)
    }
    // Generic class constructor without explicit type args: infer from arguments
    if (classes.containsKey(resolvedName) && classes[resolvedName]!!.isGeneric && e.args.isNotEmpty()) {
        val genParams = classes[resolvedName]!!.typeParams
        if (genParams.size != e.args.size) { /* skip — ctor args != type params */ } else {
        val inferredArgs = e.args.map { inferExprType(it.expr) ?: "Int" }
        val mangledName = recordGenericInstantiation(resolvedName, inferredArgs)
        materializeGenericInstantiations()
        val ci = classes[mangledName]
        if (ci != null) {
            val vAllParams2 = ci.ctorProps + ci.ctorPlainParams               // all ctor parameters
            val vCtorParamList2 = vAllParams2.map { Param(it.name, it.typeRef) } // as Param list
            val vFilledArgs2 = fillDefaults(args, vCtorParamList2, vAllParams2.associate {
                it.name to null
            }, resolvedName, strict = true)
            val expandedArgs = expandCallArgs(vFilledArgs2, vCtorParamList2, isCtorCall = true)
            return "${ci.flatName}_primaryConstructor($expandedArgs)"               // ci.flatName replaces pfx(mangledName)
        }
        }
    }
    if (classes.containsKey(resolvedName)) {
        val ci = classes[resolvedName]!!
        // Check secondary constructors by argument count (skip those with same count as primary)
        val declClass = file.decls.filterIsInstance<ClassDecl>().find { c -> c.name == resolvedName }
        val primaryParamCount = ci.ctorProps.size + ci.ctorPlainParams.size
        val sctor = declClass?.secondaryCtors?.find { it.params.size == args.size && it.params.size != primaryParamCount }
        if (sctor != null) {
            val types = sctor.params.map { resolveTypeName(it.type).toInternalStr.removeSuffix("*") }
            val suffix = if (types.isEmpty()) "emptyConstructor" else "constructorWith${types.joinToString("_")}"
            val argStr = args.joinToString(", ") { genExpr(it.expr) }
            return "${ci.flatName}_$suffix($argStr)"                               // ci.flatName replaces pfx(resolvedName)
        }
        val vAllParams3 = ci.ctorProps + ci.ctorPlainParams                        // all ctor parameters
        val vCtorParamList3 = vAllParams3.map { Param(it.name, it.typeRef) }        // as Param list
        val vFilledArgs3 = fillDefaults(args, vCtorParamList3, vAllParams3.associate {
            // find matching ctor param default
            val vCp = (file.decls.filterIsInstance<ClassDecl>().find { c -> c.name == resolvedName })
                ?.ctorParams?.find { p -> p.name == it.name }                       // matching ctor param
            it.name to vCp?.default
        }, resolvedName, strict = true)
        val expandedArgs = expandCallArgs(vFilledArgs3, vCtorParamList3, isCtorCall = true)
        return "${ci.flatName}_primaryConstructor($expandedArgs)"                   // ci.flatName replaces pfx(resolvedName)
    }

    // Enum access (should be handled as DotExpr, but just in case)

    // Generic function call: sizeOfList(list) → sizeOfList_Int(list)
    val genFun = genericFunDecls.find { it.name == name }
    if (genFun != null) {
        val typeArgNames = if (e.typeArgs.isNotEmpty()) {
            e.typeArgs.map { it.name }
        } else {
            // Infer type args from argument types
            val subst = mutableMapOf<String, String>()
            for ((i, param) in genFun.params.withIndex()) {
                if (i >= args.size) break
                val argType = inferExprType(args[i].expr) ?: continue
                val argTypeKtc = inferExprTypeKtc(args[i].expr)
                matchTypeParam(param.type, argType, genFun.typeParams.toSet(), subst)
            }
            if (subst.size == genFun.typeParams.size) genFun.typeParams.map { subst[it]!! }
            // Fallback: if inference fails (e.g. null literal), use existing instantiation
            else genericFunInstantiations[name]?.firstOrNull()?.toList()
        }
        if (typeArgNames != null) {
            val mangledName = "${name}_${typeArgNames.joinToString("_")}"
            // Record for late emission if not already known
            genericFunInstantiations.getOrPut(name) { mutableSetOf() }.add(typeArgNames)
            // Set typeSubst so expandCallArgs resolves param types correctly (T→Int etc.)
            val prevSubst = typeSubst
            typeSubst = genFun.typeParams.zip(typeArgNames).toMap()
            // Check for @Size array return
            if (genFun.returnType != null && isSizedArrayTypeRef(genFun.returnType)) {
                val vRetKtcSized = resolveTypeName(genFun.returnType)
                val retType = vRetKtcSized.toInternalStr
                val elemCType = arrayElementCTypeKtc(vRetKtcSized)
                val size = getSizeAnnotation(genFun.returnType)!!
                val t = tmp()
                preStmts += "$elemCType ${t}[$size];"
                preStmts += "const ktc_Int ${t}\$len = $size;"
                val filledArgs = fillDefaults(args, genFun.params, genFun.params.associate { it.name to it.default }, genFun.name, strict = true)
                val expandedArgs2 = expandCallArgs(filledArgs, genFun.params)
                val allArgs = if (expandedArgs2.isEmpty()) t else "$expandedArgs2, $t"
                preStmts += "${funCName(mangledName)}($allArgs);"
                typeSubst = prevSubst
                defineVar(t, retType)
                return t
            }
            // Fill in default arguments
            val filledArgs = fillDefaults(args, genFun.params, genFun.params.associate { it.name to it.default }, genFun.name, strict = true)
            val expandedArgs2 = expandCallArgs(filledArgs, genFun.params)
            typeSubst = prevSubst
            return "${funCName(mangledName)}($expandedArgs2)"
        }
    }

    // Regular function call with default arg filling
    val sig = funSigs[name]
    val filledArgs = if (sig != null) {
        fillDefaults(args, sig.params, sig.params.associate { it.name to it.default })
    } else args

    val expandedArgs = expandCallArgs(filledArgs, sig?.params)

    // Value-nullable functions now return Optional directly; no hoisting needed.
    // The variable declaration code handles wrapping for already-Opt values.

    // Inside a class method or extension: bare method call resolves to $self.method()
    if (currentClass != null) {
        val ci = classes[currentClass]
        val methodDecl = ci?.let { findOverload(name, args, it.methods) }
        if (methodDecl != null) {
            val overloadedName = methodName(methodDecl, ci.methods)
            val fnName = if (methodDecl.isPrivate) "PRIV_$overloadedName" else overloadedName
            // Re-expand args with the method's actual param types (ensures $len is added for @Ptr arrays)
            val filledArgs = fillDefaults(args, methodDecl.params, methodDecl.params.associate { it.name to it.default }, methodDecl.name, strict = true)
            val expandedArgs2 = expandCallArgs(filledArgs, methodDecl.params)
            val selfArg = if (selfIsPointer) "\$self" else "&\$self"
            val allArgs = if (expandedArgs2.isEmpty()) selfArg else "$selfArg, $expandedArgs2"
            // @Size(N) return → out-parameter ABI
            if (methodDecl.returnType != null && isSizedArrayTypeRef(methodDecl.returnType)) {
                val vRetKtcSz2 = resolveTypeName(methodDecl.returnType)
                val retType = vRetKtcSz2.toInternalStr
                val elemCType = arrayElementCTypeKtc(vRetKtcSz2)
                val size = getSizeAnnotation(methodDecl.returnType)!!
                val t = tmp()
                preStmts += "$elemCType ${t}[$size];"
                preStmts += "const ktc_Int ${t}\$len = $size;"
                preStmts += "${typeFlatName(currentClass!!)}_$fnName($allArgs, $t);"
                defineVar(t, retType)
                return t
            }
            return "${typeFlatName(currentClass!!)}_$fnName($allArgs)"
        }
        // Inside a class nested within an object: resolve to parent object's method
        val parentObj = currentClass?.substringBefore('$')
        if (parentObj != null && objects.containsKey(parentObj)) {
            val methodDecl = objects[parentObj]?.let { findOverload(name, args, it.methods) }
            if (methodDecl != null) {
                val overloadedName = methodName(methodDecl, objects[parentObj]!!.methods)
                val fnName = if (methodDecl.isPrivate) "PRIV_$overloadedName" else overloadedName
                val filledArgs = fillDefaults(args, methodDecl.params, methodDecl.params.associate { it.name to it.default }, methodDecl.name, strict = true)
                val expandedArgs2 = expandCallArgs(filledArgs, methodDecl.params)
                // @Size(N) return → out-parameter ABI
                if (methodDecl.returnType != null && isSizedArrayTypeRef(methodDecl.returnType)) {
                val vRetKtcSize = resolveTypeName(methodDecl.returnType)
                val retType = vRetKtcSize.toInternalStr
                val elemCType = arrayElementCTypeKtc(vRetKtcSize)
                    val size = getSizeAnnotation(methodDecl.returnType)!!
                    val t = tmp()
                    preStmts += "$elemCType ${t}[$size];"
                    preStmts += "const ktc_Int ${t}\$len = $size;"
                    preStmts += "${typeFlatName(parentObj)}_$fnName($expandedArgs2, $t);"
                    defineVar(t, retType)
                    return t
                }
                return "${typeFlatName(parentObj)}_$fnName($expandedArgs2)"
            }
        }
    }

    // Inside an extension on an interface: bare method call → vtable dispatch on $self
    if (currentExtRecvType != null && interfaces.containsKey(currentExtRecvType)) {
        val extIfaceInfo = interfaces[currentExtRecvType]!!
        val ifaceMethod = extIfaceInfo.methods.find { it.name == name }
            ?: collectAllIfaceMethods(extIfaceInfo).find { it.name == name }
        if (ifaceMethod != null) {
            val vSelfVtArg = ifaceVtableSelf(extIfaceInfo.name, "\$self")              // pointer to concrete data inside $self
            val allArgs = if (expandedArgs.isEmpty()) vSelfVtArg else "$vSelfVtArg, $expandedArgs"
            return "\$self.vt->$name($allArgs)"
        }
    }

    // Inside an object method: bare method call resolves to object's method
    if (currentObject != null) {
        val oi = objects[currentObject]
        val methodDecl = oi?.let { findOverload(name, args, it.methods) }
        if (methodDecl != null) {
            val overloadedName = methodName(methodDecl, oi.methods)
            val fnName = if (methodDecl.isPrivate) "PRIV_$overloadedName" else overloadedName
            val filledArgs = fillDefaults(args, methodDecl.params, methodDecl.params.associate { it.name to it.default }, methodDecl.name, strict = true)
            val expandedArgs2 = expandCallArgs(filledArgs, methodDecl.params)
            // @Size(N) return → out-parameter ABI
            if (methodDecl.returnType != null && isSizedArrayTypeRef(methodDecl.returnType)) {
                val vRetKtcSz2 = resolveTypeName(methodDecl.returnType)
                val retType = vRetKtcSz2.toInternalStr
                val elemCType = arrayElementCTypeKtc(vRetKtcSz2)
                val size = getSizeAnnotation(methodDecl.returnType)!!
                val t = tmp()
                preStmts += "$elemCType ${t}[$size];"
                preStmts += "const ktc_Int ${t}\$len = $size;"
                preStmts += "${typeFlatName(currentObject!!)}_$fnName($expandedArgs2, $t);"
                defineVar(t, retType)
                return t
            }
            return "${typeFlatName(currentObject!!)}_$fnName($expandedArgs2)"
        }
    }

    // Inside an object method but method not found in object directly — use object prefix anyway
    // for private/internal calls that were registered in funSigs
    if (currentObject != null && funSigs.containsKey(name)) {
        return "${typeFlatName(currentObject!!)}_${name}($expandedArgs)"
    }

    // Top-level @Size(N) return → out-parameter ABI
    if (sig?.returnType != null && isSizedArrayTypeRef(sig.returnType)) {
        val vRetKtcTop = resolveTypeName(sig.returnType)
        val retType = vRetKtcTop.toInternalStr
        val elemCType = arrayElementCTypeKtc(vRetKtcTop)
        val size = getSizeAnnotation(sig.returnType)!!
        val t = tmp()
        preStmts += "$elemCType ${t}[$size];"
        preStmts += "const ktc_Int ${t}\$len = $size;"
        val allArgs = if (expandedArgs.isEmpty()) t else "$expandedArgs, $t"
        preStmts += "${funCName(name)}($allArgs);"
        defineVar(t, retType)
        return t
    }

    // Top-level function overload resolution
    val topFuns = file.decls.filterIsInstance<FunDecl>()
    val topOvr = findOverload(name, args, topFuns)
    if (topOvr != null && topFuns.count { it.name == name } > 1) {
        val overloadedName = methodName(topOvr, topFuns)
        val fnName = if (topOvr.isPrivate) "PRIV_$overloadedName" else overloadedName
        val filledArgs = fillDefaults(args, topOvr.params, topOvr.params.associate { it.name to it.default }, topOvr.name, strict = true)
        val expandedArgs2 = expandCallArgs(filledArgs, topOvr.params)
        // @Size(N) return → out-parameter ABI
        if (topOvr.returnType != null && isSizedArrayTypeRef(topOvr.returnType)) {
            val vRetKtcOvr = resolveTypeName(topOvr.returnType)
            val retType = vRetKtcOvr.toInternalStr
            val elemCType = arrayElementCTypeKtc(vRetKtcOvr)
            val size = getSizeAnnotation(topOvr.returnType)!!
            val t = tmp()
            preStmts += "$elemCType ${t}[$size];"
            preStmts += "const ktc_Int ${t}\$len = $size;"
            val allArgs = if (expandedArgs2.isEmpty()) t else "$expandedArgs2, $t"
            preStmts += "${funCName(fnName)}($allArgs);"
            defineVar(t, retType)
            return t
        }
        return "${funCName(fnName)}($expandedArgs2)"
    }

    return "${funCName(name)}($expandedArgs)"
}

/** Expand call arguments: array → (arg, arg$len); nullable → (arg, arg$has); class→interface wrapping; vararg packing. */
/** If expr accesses a @Size(N) array field, return N. */
private fun CCodeGen.getSizedArrayFieldSize(expr: Expr): Int? {
    val fieldName = when (expr) {
        is DotExpr -> expr.name.removePrefix("PRIV_")
        is NameExpr -> expr.name
        else -> return null
    }
    val ci = currentClass?.let { classes[it] }
    val bp = ci?.bodyProps?.find { it.name == fieldName }
    if (bp != null && hasSizeAnnotation(bp.typeRef)) {
        return getSizeAnnotation(bp.typeRef)
    }
    return null
}

internal fun CCodeGen.expandCallArgs(args: List<Arg>, params: List<Param>?, isCtorCall: Boolean = false): String {
    val parts = mutableListOf<String>()
    if (params == null) {
        for (arg in args) parts += genExpr(arg.expr)
        return parts.joinToString(", ")
    }

    var argIdx = 0
    for (param in params) {
        val paramType = resolveTypeName(param.type).toInternalStr   // string type (for structural checks: endsWith, isArray, etc.)
        val paramTypeKtc = resolveTypeName(param.type)      // KtcType (for C type emission)
        if (param.isVararg) {
            // Consume remaining args for vararg
            val remaining = args.subList(argIdx, args.size)
            val elemCType = cTypeStr(paramTypeKtc)  // C type from KtcType
            if (remaining.size == 1 && remaining[0].isSpread) {
                val spreadExpr = genExpr(remaining[0].expr)
                parts += spreadExpr
                parts += "${spreadExpr}\$len"
            } else if (remaining.isEmpty()) {
                parts += "NULL"
                parts += "0"
            } else {
                val t = tmp()
                val argExprs = remaining.map { genExpr(it.expr) }
                preStmts += "$elemCType ${t}[] = {${argExprs.joinToString(", ")}};"
                parts += t
                parts += "${remaining.size}"
            }
            argIdx = args.size
        } else if (argIdx < args.size) {
            val arg = args[argIdx]
            val expr = genExpr(arg.expr)
            val hasAtPtr = param.type.annotations.any { it.name == "Ptr" }
            // User-class pointer (e.g. @Ptr Vec2 → Vec2*), NOT a typed array pointer (IntArray → Ptr<Arr<Int>>)
            val isPtrOrArrayPtr = paramTypeKtc is KtcType.Ptr && paramTypeKtc.inner !is KtcType.Arr
            if (hasAtPtr || isPtrOrArrayPtr) {
                // @Ptr-annotated type — pass raw pointer (NULL for null)
                if (arg.expr is NullLit) {
                    if (paramTypeKtc is KtcType.Ptr && paramTypeKtc.inner is KtcType.User
                        && (paramTypeKtc.inner as KtcType.User).kind == KtcType.UserKind.Interface)
                        parts += "(ktc_IfacePtr){0}"   // zero-init for iface trampoline
                    else parts += "NULL"
                    if (isArrayType(paramType)) parts += "0"
                } else if ((paramTypeKtc as? KtcType.Ptr)?.inner is KtcType.Any) {
                    // @Ptr Any → wrap as ktc_Any fat pointer, pass pointer to it.
                    val argType = inferExprType(arg.expr)?.removeSuffix("?") ?: "Int"
                    val typeId = getTypeId(argType)
                    val ct = cTypeStr(argType)
                    val dataRef: String
                    if (arg.expr is NameExpr) {
                        dataRef = "&$expr"
                    } else {
                        val tVal = tmp()
                        preStmts += "$ct $tVal = $expr;"
                        dataRef = "&$tVal"
                    }
                    val tAny = tmp()
                    preStmts += "ktc_Any $tAny = {{$typeId}, (void*)$dataRef};"
                    parts += "&$tAny"
                } else if ((paramTypeKtc as? KtcType.Ptr)?.inner is KtcType.User && interfaces.containsKey((paramTypeKtc.inner as KtcType.User).baseName)) {
                    // @Ptr InterfaceType → wrap into ktc_IfacePtr trampoline
                    val ifaceName = (paramTypeKtc.inner as KtcType.User).baseName
                    val cIface = typeFlatName(ifaceName)
                    val argKtc = inferExprTypeKtc(arg.expr)
                    val argKtcCore = (argKtc as? KtcType.Nullable)?.inner ?: argKtc
                    val concreteName = when {
                        arg.expr is NameExpr && classes.containsKey(arg.expr.name) -> arg.expr.name
                        arg.expr is NameExpr && objects.containsKey(arg.expr.name) -> arg.expr.name
                        argKtcCore is KtcType.User -> argKtcCore.baseName
                        (argKtcCore as? KtcType.Ptr)?.inner is KtcType.User -> (argKtcCore.inner as KtcType.User).baseName
                        else -> null
                    }
                    if (concreteName != null) {
                        val cConcrete = typeFlatName(concreteName)
                        val typeId = getTypeId(concreteName)
                        // Objects are always @Ptr (genName returns &objName), so just cast
                        val objPtr: String = if (arg.expr is NameExpr && objects.containsKey(arg.expr.name)) {
                            "(void*)&$expr"
                        } else if (arg.expr is NameExpr && argKtcCore is KtcType.Ptr) {
                            "$expr"
                        } else if (arg.expr is NameExpr) {
                            "$expr"
                        } else if (argKtcCore is KtcType.Ptr) {
                            "$expr"
                        } else {
                            val tVal = tmp()
                            val ct = cTypeStr(argKtcCore?.toInternalStr ?: "Int")
                            preStmts += "$ct $tVal = $expr;"
                            "&$tVal"
                        }
                        val tIface = tmp()
                        val argIsNullable = argKtc is KtcType.Nullable || inferExprTypeKtc(arg.expr) is KtcType.Nullable
                        if (argIsNullable) {
                            preStmts += "ktc_IfacePtr $tIface = ($expr) ? ((ktc_IfacePtr){{$typeId}, (const void*)&${cConcrete}_${ifaceName}_vt, (void*)($expr)}) : ((ktc_IfacePtr){0});"
                        } else {
                            preStmts += "ktc_IfacePtr $tIface = {{$typeId}, (const void*)&${cConcrete}_${ifaceName}_vt, $objPtr};"
                        }
                        parts += tIface
                    } else {
                        parts += expr
                    }
                } else {
                    parts += expr
                    if (isArrayType(paramType)) {
                        // Check if argument is a @Size array (fixed-size, no $len member)
                        val sizeFromAnn = getSizedArrayFieldSize(arg.expr)
                        parts += sizeFromAnn?.toString() ?: "${expr}\$len"
                    }
                }
            } else if (isArrayType(paramType)) {
                if (hasSizeAnnotation(param.type)) {
                    // @Size fixed array — passed as raw pointer
                    parts += expr
                } else if (arg.expr is NullLit && !isCtorCall) {
                    // Both nullable and non-nullable use ktc_ArrayTrampoline; NULL is data == NULL
                    parts += "(ktc_ArrayTrampoline){.size = 0, .data = NULL}"
                } else {
                    // Non-null array (nullable or not): pack as ktc_ArrayTrampoline
                    val argName = (arg.expr as? NameExpr)?.name
                    val sizeExpr = if (argName != null && argName in trampolinedParams) "$argName.size" else "${expr}\$len"
                    if (isCtorCall) {
                        parts += expr
                        parts += sizeExpr
                    } else {
                        parts += "(ktc_ArrayTrampoline){.size = $sizeExpr, .data = $expr}"
                    }
                }
            } else if ((param.type.nullable || paramTypeKtc is KtcType.Nullable) && isValueNullableKtc(paramTypeKtc.let { if (it is KtcType.Nullable) it else KtcType.Nullable(it) })) {
                // Value-nullable param → pass as Optional struct
                val optBase = if (paramType.endsWith("?")) paramType.dropLast(1) else paramType
                val optType = optCTypeName("${optBase}?")
                if (arg.expr is NullLit) {
                    parts += optNone(optType)
                } else {
                    val argVarName = (arg.expr as? NameExpr)?.name
                    val argVarKtc = if (argVarName != null) lookupVarKtc(argVarName) else null
                    val wrapped = if (argVarKtc is KtcType.Nullable && isValueNullableKtc(argVarKtc)
                        && (argVarName != null && isOptional(argVarName))
                    ) {
                        // Already an Optional var — check if needs interface conversion
                        val ifaceName2 = paramType.removeSuffix("?")
                        if (interfaces.containsKey(ifaceName2) && classes.containsKey(argVarKtc.inner.toInternalStr)
                            && classInterfaces[argVarKtc.inner.toInternalStr]?.contains(ifaceName2) == true) {
                            val baseFlat = typeFlatName(argVarKtc.inner.toInternalStr)
                            val t = tmp()
                            val optType2 = optCTypeName("${paramType}?")
                            preStmts += "$optType2 $t = ($expr.tag == ktc_SOME) ? ($optType2){ktc_SOME, ${baseFlat}_as_$ifaceName2(&$expr.value)} : ($optType2){ktc_NONE};"
                            t
                        } else expr
                    } else {
                        // Check if needs as_Iface conversion for class→interface
                        val ifaceName = paramType
                        val innerKtc = paramTypeKtc
                        val argKtc = inferExprTypeKtc(arg.expr)
                        val argKtcCore = (argKtc as? KtcType.Nullable)?.inner ?: argKtc
                        val baseArg = (argKtcCore as? KtcType.User)?.baseName ?: argKtcCore?.toInternalStr
                        val isIfImpl = baseArg != null && interfaces.containsKey(ifaceName)
                            && (classes.containsKey(baseArg) || objects.containsKey(baseArg))
                            && classInterfaces[baseArg]?.contains(ifaceName) == true
                        val valExpr = if (isIfImpl) {
                            if (argKtcCore is KtcType.Ptr || objects.containsKey(baseArg)) "${typeFlatName(baseArg)}_as_$ifaceName($expr)"
                            else "${typeFlatName(baseArg)}_as_$ifaceName(&$expr)"
                        } else expr
                        optSome(optType, valExpr)
                    }
                    parts += wrapped
                }
            } else if (interfaces.containsKey(paramType)) {
                val argKtc = inferExprTypeKtc(arg.expr)
                val argKtcCore = (argKtc as? KtcType.Nullable)?.inner ?: argKtc
                val baseArgType = argKtcCore?.let {
                    if (it is KtcType.User) it.baseName else it.toInternalStr
                }
                val isClassImpl = baseArgType != null && classes.containsKey(baseArgType) && classInterfaces[baseArgType]?.contains(paramType) == true
                val isObjImpl = baseArgType != null && objects.containsKey(baseArgType) && classInterfaces[baseArgType]?.contains(paramType) == true
                parts += if (isClassImpl || isObjImpl) {
                    if (argKtcCore is KtcType.Ptr) {
                        "${typeFlatName(baseArgType)}_as_$paramType($expr)"
                    } else if (isObjImpl) {
                        "${typeFlatName(baseArgType)}_as_$paramType(&$expr)"
                    } else {
                        "${typeFlatName(baseArgType)}_as_$paramType(&$expr)"
                    }
                } else {
                    expr
                }
            } else if (paramTypeKtc is KtcType.Any) {
                if (arg.expr is NullLit) {
                    parts += "(ktc_Any){0}"
                } else {
                    val argType = inferExprType(arg.expr)?.removeSuffix("?") ?: "Int"
                    val argKtc = inferExprTypeKtc(arg.expr)
                    val argKtcCore = (argKtc as? KtcType.Nullable)?.inner ?: argKtc
                    // If already Any/Any?, pass directly (no re-wrap)
                    if (argKtcCore is KtcType.Any) {
                        parts += expr
                    } else {
                        val typeId = getTypeId(argType)
                        val ct = cTypeStr(argType)
                        val tVal = tmp()
                        preStmts += "$ct $tVal = $expr;"
                        parts += "(ktc_Any){{$typeId}, (void*)&$tVal}"
                    }
                }
            } else {
                // Auto-cast any pointer to AnyPtr / Byte* (for freeMem, reallocMem, etc.)
                val argKtc = inferExprTypeKtc(arg.expr)
                val argKtcCore = (argKtc as? KtcType.Nullable)?.inner ?: argKtc
                if (paramType == "void*" || (paramType == "Byte*" && argKtcCore is KtcType.Ptr)) {
                    parts += "(void*)($expr)"
                } else {
                    parts += expr
                }
            }
            argIdx++
        }
    }
    // Handle remaining args if more args than params (shouldn't happen normally)
    while (argIdx < args.size) {
        parts += genExpr(args[argIdx].expr)
        argIdx++
    }
    return parts.joinToString(", ")
}


internal fun CCodeGen.genMethodCall(dot: DotExpr, args: List<Arg>): String {
    val rawRecvType = inferExprType(dot.obj)                                          // String? receiver type (string-based)
    val recvType = rawRecvType?.removeSuffix("?")                                     // String? non-nullable receiver
    val rawRecvTypeKtc = inferExprTypeKtc(dot.obj)                                   // KtcType? receiver (may have Nullable wrapper)
    val recvTypeKtc = (rawRecvTypeKtc as? KtcType.Nullable)?.inner ?: rawRecvTypeKtc // KtcType? stripped of Nullable wrapper
    val rawRecv = genExpr(dot.obj)
    val method = dot.name
    val hasNullRecv = hasNullableReceiverExt(recvType ?: "", method)
    val isValueNull = rawRecvTypeKtc is KtcType.Nullable && isValueNullableKtc(rawRecvTypeKtc) && !hasNullRecv
    val recv = if (isValueNull) "$rawRecv.value" else rawRecv
    val argStr = args.joinToString(", ") { genExpr(it.expr) }

    // Built-in methods
    when (method) {
        "trimIndent" -> {
            // Fold at transpile time if called on a string literal
            if (dot.obj is StrLit) {
                val str = dot.obj.value
                val trimmed = trimIndentImpl(str)
                return "ktc_core_str(\"${escapeStr(trimmed)}\")"
            }
            // Runtime: not supported
            codegenError("trimIndent() only supported on string literals")
        }

        "trimMargin" -> {
            // Fold at transpile time if called on a string literal
            if (dot.obj is StrLit) {
                val str = dot.obj.value
                val marginPrefix = if (args.isNotEmpty()) {
                    (args[0].expr as? StrLit)?.value ?: "|"
                } else "|"
                val trimmed = trimMarginImpl(str, marginPrefix)
                return "ktc_core_str(\"${escapeStr(trimmed)}\")"
            }
            // Runtime: not supported
            codegenError("trimMargin() only supported on string literals")
        }

        "runeAt" -> {
            if (recvTypeKtc is KtcType.Str && args.size == 1) {
                return "ktc_core_str_runeAt($recv, ${genExpr(args[0].expr)})"
            }
            codegenError("runeAt() only supported on String with a byteIndex argument")
        }

        "toString" -> {
            if (args.size == 1) {
                val argType = inferExprType(args[0].expr)
                val argTypeKtc = inferExprTypeKtc(args[0].expr)
                if (argType == "ktc_StrBuf" || argType == "StringBuffer") {
                    return genToStringInto(recv, recvType ?: "Int", genExpr(args[0].expr))
                }
            }
            return genToString(recv, recvType ?: "Int")
        }

        "toInt" -> {
            if (recvTypeKtc is KtcType.Str) return "ktc_core_str_toInt($recv)"
            return "((ktc_Int)($recv))"
        }

        "toLong" -> {
            if (recvTypeKtc is KtcType.Str) return "ktc_core_str_toLong($recv)"
            return "((ktc_Long)($recv))"
        }

        "toFloat" -> {
            if (recvTypeKtc is KtcType.Str) return "((ktc_Float)ktc_core_str_toDouble($recv))"
            return "((ktc_Float)($recv))"
        }

        "toDouble" -> {
            if (recvTypeKtc is KtcType.Str) return "ktc_core_str_toDouble($recv)"
            return "((ktc_Double)($recv))"
        }

        "toByte" -> return "((ktc_Byte)($recv))"
        "toShort" -> return "((ktc_Short)($recv))"
        "toUByte" -> return "((ktc_UByte)($recv))"
        "toUShort" -> return "((ktc_UShort)($recv))"
        "toUInt" -> return "((ktc_UInt)($recv))"
        "toULong" -> return "((ktc_ULong)($recv))"
        "toChar" -> return "((ktc_Char)($recv))"
        "inv" -> return "(~($recv))"
        // Nullable string-to-number: toIntOrNull, toLongOrNull, toFloatOrNull, toDoubleOrNull
        "toIntOrNull" -> if (recvTypeKtc is KtcType.Str) {
            val t = tmp()
            preStmts += "ktc_Int ${t}_val;"
            preStmts += "ktc_Int\$Opt $t;"
            preStmts += "$t.tag = ktc_core_str_toIntOrNull($recv, &${t}_val) ? ktc_SOME : ktc_NONE;"
            preStmts += "$t.value = ${t}_val;"
            markOptional(t)
        }

        "toLongOrNull" -> if (recvTypeKtc is KtcType.Str) {
            val t = tmp()
            preStmts += "ktc_Long ${t}_val;"
            preStmts += "ktc_Long\$Opt $t;"
            preStmts += "$t.tag = ktc_core_str_toLongOrNull($recv, &${t}_val) ? ktc_SOME : ktc_NONE;"
            preStmts += "$t.value = ${t}_val;"
            markOptional(t)
        }

        "toDoubleOrNull" -> if (recvTypeKtc is KtcType.Str) {
            val t = tmp()
            preStmts += "ktc_Double ${t}_val;"
            preStmts += "ktc_Double\$Opt $t;"
            preStmts += "$t.tag = ktc_core_str_toDoubleOrNull($recv, &${t}_val) ? ktc_SOME : ktc_NONE;"
            preStmts += "$t.value = ${t}_val;"
            markOptional(t)
        }

        "toFloatOrNull" -> if (recvTypeKtc is KtcType.Str) {
            val t = tmp()
            preStmts += "ktc_Double ${t}_d;"
            preStmts += "ktc_Float\$Opt $t;"
            preStmts += "$t.tag = ktc_core_str_toDoubleOrNull($recv, &${t}_d) ? ktc_SOME : ktc_NONE;"
            preStmts += "$t.value = (ktc_Float)${t}_d;"
            markOptional(t)
        }

        "substring" -> if (recvTypeKtc is KtcType.Str) {
            val from = genExpr(args[0].expr)
            val to = if (args.size >= 2) genExpr(args[1].expr) else "$recv.len"
            return "ktc_core_string_substring($recv, $from, $to)"
        }

        "startsWith" -> if (recvTypeKtc is KtcType.Str) {
            val prefix = genExpr(args[0].expr)
            return "($recv.len >= $prefix.len && memcmp($recv.ptr, $prefix.ptr, (size_t)$prefix.len) == 0)"
        }

        "endsWith" -> if (recvTypeKtc is KtcType.Str) {
            val suffix = genExpr(args[0].expr)
            return "($recv.len >= $suffix.len && memcmp($recv.ptr + $recv.len - $suffix.len, $suffix.ptr, (size_t)$suffix.len) == 0)"
        }

        "contains" -> if (recvTypeKtc is KtcType.Str) {
            val sub = genExpr(args[0].expr)
            val t = tmp()
            preStmts += "ktc_Bool $t = false;"
            preStmts += "for (ktc_Int ${t}_i = 0; ${t}_i <= $recv.len - $sub.len; ${t}_i++) { if (memcmp($recv.ptr + ${t}_i, $sub.ptr, (size_t)$sub.len) == 0) { $t = true; break; } }"
            return t
        }

        "indexOf" -> if (recvTypeKtc is KtcType.Str) {
            val sub = genExpr(args[0].expr)
            val t = tmp()
            preStmts += "ktc_Int $t = -1;"
            preStmts += "for (ktc_Int ${t}_i = 0; ${t}_i <= $recv.len - $sub.len; ${t}_i++) { if (memcmp($recv.ptr + ${t}_i, $sub.ptr, (size_t)$sub.len) == 0) { $t = ${t}_i; break; } }"
            return t
        }

        "isEmpty" -> if (recvTypeKtc is KtcType.Str) {
            return "($recv.len == 0)"
        }

        "isNotEmpty" -> if (recvTypeKtc is KtcType.Str) {
            return "($recv.len > 0)"
        }

        "hashCode" -> {
            if (recvTypeKtc != null) {
                return when (recvTypeKtc) {
                    is KtcType.Prim -> when (recvTypeKtc.kind) {
                        KtcType.PrimKind.Byte -> "ktc_core_hash_i8($recv)"
                        KtcType.PrimKind.Short -> "ktc_core_hash_i16($recv)"
                        KtcType.PrimKind.Int -> "ktc_core_hash_i32($recv)"
                        KtcType.PrimKind.Long -> "ktc_core_hash_i64($recv)"
                        KtcType.PrimKind.Float -> "ktc_core_hash_f32($recv)"
                        KtcType.PrimKind.Double -> "ktc_core_hash_f64($recv)"
                        KtcType.PrimKind.Boolean -> "ktc_core_hash_bool($recv)"
                        KtcType.PrimKind.Char -> "ktc_core_hash_char($recv)"
                        KtcType.PrimKind.UByte -> "ktc_core_hash_u8($recv)"
                        KtcType.PrimKind.UShort -> "ktc_core_hash_u16($recv)"
                        KtcType.PrimKind.UInt -> "ktc_core_hash_u32($recv)"
                        KtcType.PrimKind.ULong -> "ktc_core_hash_u64($recv)"
                        KtcType.PrimKind.Rune -> "ktc_core_hash_i32($recv)"
                    }

                    is KtcType.Str -> "ktc_core_hash_str($recv)"
                    else -> {
                        // @Ptr class pointer → call ClassName_hashCode(pointer)
                        val pointerBase = (recvTypeKtc as? KtcType.Ptr)?.inner?.let { it as? KtcType.User }?.baseName
                        if (pointerBase != null) {
                            "${typeFlatName(pointerBase)}_hashCode($recv)"
                        } else {
                            "${typeFlatName(recvType!!)}_hashCode(&($recv))"
                        }
                    }
                }
            }
            "${typeFlatName(recvType!!)}_hashCode(&($recv))"
        }
    }

    // Array .size → trampolined param uses trampoline size field; others use $len
    if (method == "size" && recvTypeKtc != null && recvTypeKtc.isArrayLike) {
        val dotName = (dot.obj as? NameExpr)?.name
        return if (dotName != null && dotName in trampolinedParams) "$dotName.size" else "${recv}\$len"
    }
    // Array .ptr() → just the pointer (already a pointer type)
    if (method == "ptr" && recvTypeKtc != null && recvTypeKtc.isArrayLike) {
        return recv
    }
    // Array .toHeap() → malloc + memcpy to heap
    if (method == "toHeap" && recvTypeKtc != null && recvTypeKtc.isArrayLike) {
        val elemC = arrayElementCTypeKtc(recvTypeKtc)
        val lenExpr = when {
            dot.obj is NameExpr && dot.obj.name in trampolinedParams -> "${dot.obj.name}.size"
            else -> "${recv}\$len"
        }
        val t = tmp()
        preStmts += "$elemC* $t = ($elemC*)${tMalloc("sizeof($elemC) * (size_t)($lenExpr)")};"
        preStmts += "if ($t) memcpy($t, $recv, sizeof($elemC) * (size_t)($lenExpr));"
        preStmts += "ktc_Int ${t}\$len = $lenExpr;"
        return t
    }
    // Array / RawArray .resizeWith(allocator, newSize) → allocator-based realloc
    if (method == "resizeWith" && recvTypeKtc != null && recvTypeKtc.isArrayLike && args.size >= 2) {
        val elemC = arrayElementCTypeKtc(recvTypeKtc)
        val allocExpr = genExpr(args[0].expr)
        val newSizeExpr = genExpr(args[1].expr)
        val t = tmp()
        val allocArgKtc = inferExprTypeKtc(args[0].expr)
        val allocArgCore = (allocArgKtc as? KtcType.Nullable)?.inner ?: allocArgKtc
        val isTrampoline = allocArgCore is KtcType.Ptr && allocArgCore.inner is KtcType.User && (allocArgCore.inner as KtcType.User).kind == KtcType.UserKind.Interface
        val ifExpr: String
        if (isTrampoline) {
            ifExpr = allocExpr
        } else {
            val allocObjName = (args[0].expr as? NameExpr)?.name
            if (allocObjName != null && objects.containsKey(allocObjName)) {
                val cConcrete = typeFlatName(allocObjName); val typeId = getTypeId(allocObjName)
                preStmts += "ktc_IfacePtr $t = {{$typeId}, (const void*)&${cConcrete}_Allocator_vt, (void*)&$allocExpr};"
                ifExpr = t
            } else { ifExpr = allocExpr }
        }
        preStmts += "$elemC* ${t}_ptr = ($elemC*)((ktc_std_Allocator_vt*)$ifExpr.vt)->reallocMem($ifExpr.obj, $recv, sizeof($elemC) * (size_t)($newSizeExpr));"
        val isRawArray = recvTypeKtc.asArr == null && recvTypeKtc is KtcType.Ptr
        if (!isRawArray) preStmts += "ktc_Int ${t}_ptr\$len = $newSizeExpr;"
        return "${t}_ptr"
    }
    // Array pointer .get(i) → recv[i] and .set(i,v) → recv[i] = v
    if ((method == "get" || method == "set") && recvTypeKtc != null && recvTypeKtc.isArrayLike) {
        val idx = args.getOrNull(0)?.let { genExpr(it.expr) } ?: "0"
        if (method == "get") return "${recv}[$idx]"
        val valExpr = args.getOrNull(1)?.let { genExpr(it.expr) } ?: "0"
        return "(${recv}[$idx] = $valExpr)"
    }
    // Ptr<Array<T>> .deref() → dereference to get the array
    if (method == "deref" && recvTypeKtc != null && recvTypeKtc.isArrayLike && recvTypeKtc is KtcType.Ptr) {
        return recv
    }

    // @Ptr/@Heap/@Value-annotated class pointer methods
    val pointerBase = (recvTypeKtc as? KtcType.Ptr)?.inner?.let { it as? KtcType.User }?.baseName
    // Skip if pointerBase is an interface — those go through vtable dispatch below
    val isIface = pointerBase != null && interfaces.containsKey(pointerBase)
    if (pointerBase != null && !isIface) {
        // If class defines the method, delegate to it
        val classHasMethod = classes[pointerBase]?.methods?.any { it.name == method } == true
        if (classHasMethod) {
            val methodDecl = classes[pointerBase]?.let { findOverload(method, args, it.methods) }
            val isExt = methodDecl?.receiver != null
            val recvArg = if (isExt) "(*$recv)" else recv
            // For generic materialized classes, set typeSubst so nullable type args resolve at call sites
            val savedSubst2 = typeSubst
            val classBindings2 = genericTypeBindings[pointerBase]
            if (classBindings2 != null && classBindings2.isNotEmpty()) {
                typeSubst = classBindings2
            }
            val expandedArgs2 = if (methodDecl != null) {
                val filled2 = fillDefaults(args, methodDecl.params, methodDecl.params.associate { it.name to it.default }, methodDecl.name, strict = true)
                expandCallArgs(filled2, methodDecl.params)
            } else argStr
            if (classBindings2 != null && classBindings2.isNotEmpty()) {
                typeSubst = savedSubst2
            }
            val allArgs = if (expandedArgs2.isEmpty()) recvArg else "$recvArg, $expandedArgs2"
            if (methodDecl?.returnType?.nullable == true) {
                return genNullableMethodCall(pointerBase, "${typeFlatName(pointerBase)}_$method", allArgs, methodDecl)
            }
            return "${typeFlatName(pointerBase)}_$method($allArgs)"
        }
        when (method) {
            "value" -> {
                if (objects.containsKey(pointerBase)) codegenError("Cannot call .value() on object '${pointerBase}' — objects are always @Ptr")
                return "(*$recv)"
            }
            "deref" -> {
                if (objects.containsKey(pointerBase)) codegenError("Cannot call .deref() on object '${pointerBase}' — objects are always @Ptr")
                return "(*$recv)"
            }
            "set" -> return "(*$recv = $argStr)"
            "copy" -> if (classes[pointerBase]?.isData == true) {
                return genDataClassCopy(recv, pointerBase, args, heap = true)
            }

            "toHeap", "ptr" -> return recv
        }
        // general class method — pointer passed directly
        // Check if this is a generic extension function with @Ptr receiver
        val genExt = genericFunDecls.find {
            it.name == method && it.receiver != null && (
                it.receiver.name == pointerBase ||
                (genericClassDecls.containsKey(it.receiver.name) && pointerBase.startsWith("${it.receiver.name}\$"))
            )
        }
        // Also search interfaces implemented by the class for @Ptr generic ext funs
        var ifaceExt: FunDecl? = null
        var ifaceExtConcrete: String? = null
        if (genExt == null) {
            val ifaces = classInterfaces[pointerBase] ?: emptyList()
            for (iface in ifaces) {
                val m = genericFunDecls.find {
                    it.name == method && it.receiver != null && (
                        it.receiver.name == iface ||
                        (genericIfaceDecls.containsKey(it.receiver.name) && iface.startsWith("${it.receiver.name}\$"))
                    )
                }
                if (m != null) {
                    ifaceExt = m
                    ifaceExtConcrete = if (iface.startsWith("${m.receiver!!.name}\$")) iface
                        else {
                            val binding = genericTypeBindings[pointerBase]
                            if (binding != null) {
                                val tArgs = m.typeParams.map { binding[it] ?: "Int" }
                                mangledGenericName(m.receiver!!.name, tArgs)
                            } else iface
                        }
                    break
                }
            }
        }
        val effectiveGenExt = genExt ?: ifaceExt
        if (ifaceExt != null && ifaceExtConcrete != null) {
            /* Extract type args from "$N_A1_A2" format: strip base name + "$", then skip arity + "_" */
            val tArgs = ifaceExt.typeParams.map { tp ->
                val dollarPrefix = "${ifaceExt.receiver!!.name}\$"
                if (ifaceExtConcrete!!.startsWith(dollarPrefix)) {
                    val afterDollar = ifaceExtConcrete!!.removePrefix(dollarPrefix) // "1_Int" or "2_Int_String"
                    val uIdx = afterDollar.indexOf('_')
                    if (uIdx >= 0) afterDollar.substring(uIdx + 1) else afterDollar
                } else "Int"
            }
            genericFunInstantiations.getOrPut(ifaceExt.name) { mutableSetOf() }.add(tArgs)
        }
        val isPtrExt = effectiveGenExt?.receiver?.annotations?.any { it.name == "Ptr" } == true
        val flatPtrBase = if (ifaceExtConcrete != null) {
            val f = typeFlatName(ifaceExtConcrete)
            if (isPtrExt) "${f.removeSuffix("_$ifaceExtConcrete")}_Ptr_${ifaceExtConcrete}" else f
        } else if (isPtrExt) "${typeFlatName(pointerBase).removeSuffix("_$pointerBase")}_Ptr_${pointerBase}" else typeFlatName(pointerBase)
        val wrappedRecv = if (ifaceExtConcrete != null && isPtrExt) {
            val cConcrete = typeFlatName(pointerBase)
            val typeId = getTypeId(pointerBase)
            val t = tmp()
            val isNullable = rawRecvTypeKtc is KtcType.Nullable
            if (isNullable) {
                preStmts += "ktc_IfacePtr $t = ($recv) ? ((ktc_IfacePtr){{$typeId}, (const void*)&${cConcrete}_${ifaceExtConcrete}_vt, (void*)($recv)}) : ((ktc_IfacePtr){0});"
            } else {
                preStmts += "ktc_IfacePtr $t = {{$typeId}, (const void*)&${cConcrete}_${ifaceExtConcrete}_vt, (void*)$recv};"
            }
            t
        } else recv
        val allArgs = if (argStr.isEmpty()) wrappedRecv else "$wrappedRecv, $argStr"
        return "${flatPtrBase}_$method($allArgs)"
    }

    // Interface method dispatch → d.vt->method(data_ptr, args)
    val vIfaceInfo = ifaceInfoFor(recvTypeKtc)
    if (vIfaceInfo != null) {
        val cIface = typeFlatName(vIfaceInfo.name)
        val extFunOnIface = extensionFuns[vIfaceInfo.baseName]?.find { it.name == method }
            ?: extensionFuns[vIfaceInfo.flatName]?.find { it.name == method }
        if (extFunOnIface != null) {
            val allArgs = if (argStr.isEmpty()) recv else "$recv, $argStr"
            return "${vIfaceInfo.flatName}_$method($allArgs)"
        }
        // @Ptr interface uses trampoline (value): recv.vt->method(recv.obj, ...)
        // Value interface uses tagged union: recv.vt->method(&recv.data, ...)
        val isIfacePtr = rawRecvTypeKtc is KtcType.Ptr
        val vSelfArg = if (isIfacePtr) "$recv.obj" else ifaceVtableSelf(vIfaceInfo.name, recv)
        val allArgs = if (argStr.isEmpty()) vSelfArg else "$vSelfArg, $argStr"
        val vtAccess = if (isIfacePtr) "((${cIface}_vt*)$recv.vt)"
                       else "$recv.vt"
        val ifaceMethod = vIfaceInfo.methods.find { it.name == method }
            ?: collectAllIfaceMethods(vIfaceInfo).find { it.name == method }
        if (ifaceMethod?.returnType?.nullable == true) {
            val retType = resolveTypeName(ifaceMethod.returnType).toInternalStr
            val optType = optCTypeName("${retType}?")
            val t = tmp()
            preStmts += "$optType $t = $vtAccess->$method($allArgs);"
            markOptional(t)
            defineVar(t, "${retType}?")
            return t
        }
        return "$vtAccess->$method($allArgs)"
    }

    // .ptr() on nullable value type → produce @Ptr T? (nullable pointer)
    if (method == "ptr" && rawRecvTypeKtc is KtcType.Nullable && isValueNullableKtc(rawRecvTypeKtc)) {
        val innerKtc = rawRecvTypeKtc.inner
        val ct = cTypeStr(innerKtc.toInternalStr)
        val t = tmp()
        preStmts += "$ct* $t = (${rawRecv}.tag == ktc_SOME) ? &${rawRecv}.value : NULL;"
        return t
    }

    // Class method or extension function on class type (stack value)
    val vClassInfo = classInfoFor(recvTypeKtc)                                        // non-null if receiver is a known user-defined class
    if (vClassInfo != null) {
        // .copy() on data class
        if (method == "copy" && vClassInfo.isData) {
            return genDataClassCopy(recv, vClassInfo.baseName, args, heap = false)
        }
        // .toHeap() → inline malloc + struct copy
        if (method == "toHeap") {
            val cName = vClassInfo.flatName                                            // C struct name with package prefix
            val t = tmp()
            preStmts += "$cName* $t = ($cName*)${tMalloc("sizeof($cName)")};"
            preStmts += "if ($t) *$t = $recv;"
            return t
        }
        // .ptr() → &value
        if (method == "ptr") {
            val t = tmp()
            preStmts += "${vClassInfo.flatName}* $t = &$recv;"
            return t
        }
        val methodDecl = findOverload(method, args, vClassInfo.methods)
        // Also check generic extension functions (fun <T> ArrayList<T>.method())
        var genericExtDecl = if (methodDecl == null) genericFunDecls.find {
            it.name == method && it.receiver != null && (
                it.receiver.name == vClassInfo.baseName ||
                (genericClassDecls.containsKey(it.receiver.name) && vClassInfo.baseName.startsWith("${it.receiver.name}\$"))
            )
        } else null
        // Also check interfaces implemented by the class for generic ext funs
        var ifaceConcrete: String? = null
        if (genericExtDecl == null) {
            val ifaces = classInterfaces[vClassInfo.baseName] ?: emptyList()
            for (iface in ifaces) {
                val match = genericFunDecls.find {
                    it.name == method && it.receiver != null && (
                        it.receiver.name == iface ||
                        (genericIfaceDecls.containsKey(it.receiver.name) && iface.startsWith("${it.receiver.name}\$"))
                    )
                }
                if (match != null) {
                    genericExtDecl = match
                    // Determine the concrete interface name (e.g. List$1_Int)
                    ifaceConcrete = if (iface.startsWith("${match.receiver!!.name}\$")) iface
                        else {
                            // Infer from the class name: ArrayList$1_Int → T=Int → List$1_Int
                            val binding = genericTypeBindings[vClassInfo.baseName]
                            if (binding != null) {
                                val tArgs = match.typeParams.map { binding[it] ?: "Int" }
                                mangledGenericName(match.receiver!!.name, tArgs)
                            } else iface
                        }
                    break
                }
            }
        }
        // Register generic instantiation if found via interface
        if (genericExtDecl != null && ifaceConcrete != null) {
            /* Extract type args from "$N_A1_A2" format: strip base name + "$", then skip arity + "_" */
            val tArgs = genericExtDecl.typeParams.map { tp ->
                val dollarPrefix = "${genericExtDecl.receiver!!.name}\$"
                if (ifaceConcrete.startsWith(dollarPrefix)) {
                    val afterDollar = ifaceConcrete.removePrefix(dollarPrefix) // "1_Int"
                    val uIdx = afterDollar.indexOf('_')
                    if (uIdx >= 0) afterDollar.substring(uIdx + 1) else afterDollar
                } else "Int"
            }
            genericFunInstantiations.getOrPut(genericExtDecl.name) { mutableSetOf() }.add(tArgs)
        }
        val effectiveDecl = methodDecl ?: genericExtDecl
        val isExtFun = effectiveDecl?.receiver != null
        val isPtrRecv = effectiveDecl?.receiver?.annotations?.any { it.name == "Ptr" } == true
        val isNullableRecv = effectiveDecl?.receiver?.nullable == true
        val flatBase = if (ifaceConcrete != null) {
            val f = typeFlatName(ifaceConcrete)
            if (isPtrRecv) "${f.removeSuffix("_$ifaceConcrete")}_Ptr_${ifaceConcrete}" else f
        } else if (isPtrRecv) "${vClassInfo.flatName.removeSuffix("_${vClassInfo.baseName}")}_Ptr_${vClassInfo.baseName}" else vClassInfo.flatName
        val nullableRecv = hasNullableReceiverExt(recvType ?: "", method)
        val selfArg = if (nullableRecv) {
            val recvName = (dot.obj as? NameExpr)?.name
            val recvVarKtc2 = if (recvName != null) lookupVarKtc(recvName) else null
            val optSelfType = optCTypeName("${recvType}?")
            when {
                dot.obj is ThisExpr -> "\$self"
                recvVarKtc2 is KtcType.Nullable && isValueNullableKtc(recvVarKtc2)
                        && recvName != null && isOptional(recvName) -> {
                    if (ifaceConcrete != null && isExtFun) {
                        val optBase = ifaceConcrete
                        val t = tmp()
                        val optType2 = optCTypeName("${optBase}?")
                        preStmts += "$optType2 $t = ($recv.tag == ktc_SOME) ? ($optType2){ktc_SOME, ${typeFlatName(vClassInfo.baseName)}_as_$ifaceConcrete(&$recv.value)} : ($optType2){ktc_NONE};"
                        t
                    } else recv
                }

                isExtFun -> {
                    if (isNullableRecv && !isPtrRecv) {
                        val rName2 = (dot.obj as? NameExpr)?.name
                        val rKtc2 = if (rName2 != null) lookupVarKtc(rName2) else null
                        if (rKtc2 is KtcType.Nullable && rName2 != null && isOptional(rName2)) {
                            if (ifaceConcrete != null) {
                                val optBase = ifaceConcrete
                                val t = tmp()
                                val optType2 = optCTypeName("${optBase}?")
                                preStmts += "$optType2 $t = ($recv.tag == ktc_SOME) ? ($optType2){ktc_SOME, ${typeFlatName(vClassInfo.baseName)}_as_$ifaceConcrete(&$recv.value)} : ($optType2){ktc_NONE};"
                                t
                            } else recv
                        } else {
                            val valExpr = if (ifaceConcrete != null) "${typeFlatName(vClassInfo.baseName)}_as_$ifaceConcrete(&$recv)" else recv
                            val optBase = ifaceConcrete ?: vClassInfo.baseName
                            optSome(optCTypeName("${optBase}?"), valExpr)
                        }
                    } else if (ifaceConcrete != null && !isPtrRecv) optSome(optSelfType, "${typeFlatName(vClassInfo.baseName)}_as_$ifaceConcrete(&$recv)")
                    else optSome(optSelfType, recv)
                }
                else -> optSome(optSelfType, "&$recv")
            }
        } else if (isExtFun) {
            if (isNullableRecv && !isPtrRecv) {
                val rName = (dot.obj as? NameExpr)?.name
                val rKtc = if (rName != null) lookupVarKtc(rName) else null
                if (rKtc is KtcType.Nullable && rName != null && isOptional(rName)) {
                    if (ifaceConcrete != null) {
                        // Convert Optional<ArrayList> to Optional<List> via as_Iface
                        val optBase = ifaceConcrete
                        val t = tmp()
                        val optType = optCTypeName("${optBase}?")
                        preStmts += "$optType $t = ($recv.tag == ktc_SOME) ? ($optType){ktc_SOME, ${typeFlatName(vClassInfo.baseName)}_as_$ifaceConcrete(&$recv.value)} : ($optType){ktc_NONE};"
                        t
                    } else recv
                }
                else {
                    val optBase = ifaceConcrete ?: vClassInfo.baseName
                    val valExpr = if (ifaceConcrete != null) "${typeFlatName(vClassInfo.baseName)}_as_$ifaceConcrete(&$recv)" else recv
                    optSome(optCTypeName("${optBase}?"), valExpr)
                }
            } else if (ifaceConcrete != null && !isPtrRecv) "${typeFlatName(vClassInfo.baseName)}_as_$ifaceConcrete(&$recv)"
            else recv
        } else "&$recv"
        // Use expandCallArgs for proper @Ptr expansion and default arg filling
        // For generic materialized classes, temporarily set typeSubst from the class bindings
        // so that nullable type args (e.g., T=Int?) are properly resolved at call sites
        val savedSubst = typeSubst
        val classBindings = genericTypeBindings[vClassInfo.name]
        if (classBindings != null && classBindings.isNotEmpty()) {
            typeSubst = classBindings
        }
        val expandedArgs = if (methodDecl != null) {
            val filled = fillDefaults(args, methodDecl.params, methodDecl.params.associate { it.name to it.default }, methodDecl.name, strict = true)
            expandCallArgs(filled, methodDecl.params)
        } else argStr
        if (classBindings != null && classBindings.isNotEmpty()) {
            typeSubst = savedSubst
        }
        val allArgs = if (expandedArgs.isEmpty()) selfArg else "$selfArg, $expandedArgs"
        val overloadedName = methodDecl?.let { methodName(it, vClassInfo.methods) } ?: method
        val fnPrefix = if (methodDecl?.isPrivate == true) "PRIV_$overloadedName" else overloadedName
        // @Size(N) return → out-parameter ABI
        if (methodDecl?.returnType != null && isSizedArrayTypeRef(methodDecl.returnType)) {
                val vRetKtcSz = resolveTypeName(methodDecl.returnType)
                val retType = vRetKtcSz.toInternalStr
                val elemCType = arrayElementCTypeKtc(vRetKtcSz)

            val size = getSizeAnnotation(methodDecl.returnType)!!
            val t = tmp()
            preStmts += "$elemCType ${t}[$size];"
            preStmts += "const ktc_Int ${t}\$len = $size;"
            preStmts += "${vClassInfo.flatName}_$fnPrefix($allArgs, $t);"
            defineVar(t, retType)
            return t
        }
        // Nullable return: use out-pointer pattern
        if (methodDecl?.returnType?.nullable == true) {
            return genNullableMethodCall(vClassInfo.baseName, "${flatBase}_$fnPrefix", allArgs, methodDecl)
        }
        return "${flatBase}_$fnPrefix($allArgs)"
    }
    // Object / Companion method
    val vDotObjInfo = resolveDotObjInfo(dot)
    val vDotObjCName = resolveDotObjCName(dot)
    if (vDotObjInfo != null && vDotObjCName != null) {
        val vObjMethod = findOverload(method, args, vDotObjInfo.methods)
        val overloadedMethod = vObjMethod?.let { methodName(it, vDotObjInfo.methods) } ?: method
        val vObjArgs = if (vObjMethod != null) {
            val filled = fillDefaults(args, vObjMethod.params, vObjMethod.params.associate { it.name to it.default }, vObjMethod.name, strict = true)
            expandCallArgs(filled, vObjMethod.params)
        } else argStr
        // @Size(N) return → out-parameter ABI
        if (vObjMethod?.returnType != null && isSizedArrayTypeRef(vObjMethod.returnType)) {
            val vRetKtcObj = resolveTypeName(vObjMethod.returnType)
            val retType = vRetKtcObj.toInternalStr
            val elemCType = arrayElementCTypeKtc(vRetKtcObj)
            val size = getSizeAnnotation(vObjMethod.returnType)!!
            val t = tmp()
            preStmts += "$elemCType ${t}[$size];"
            preStmts += "const ktc_Int ${t}\$len = $size;"
            preStmts += "${vDotObjCName}_$overloadedMethod($vObjArgs, $t);"
            defineVar(t, retType)
            return t
        }
        return "${vDotObjCName}_$overloadedMethod($vObjArgs)"
    }
    // Enum → static method/field access
    val vEnumInfo = enumInfoFor(recvTypeKtc)                                          // non-null if receiver is a known enum
    if (vEnumInfo != null) {
        when (method) {
            "values" -> {
                enumValuesCalled.add(vEnumInfo.baseName)
                return "${vEnumInfo.flatName}_values"
            }

            "valueOf" -> {
                val vValOfArgStr = args.joinToString(", ") { genExpr(it.expr) }
                enumValuesCalled.add(vEnumInfo.baseName)
                enumValueOfCalled.add(vEnumInfo.baseName)
                return "${vEnumInfo.flatName}_valueOf($vValOfArgStr)"
            }

            else -> return "${vEnumInfo.flatName}_$method"
        }
    }

    // Extension function on non-class type (Int, String, etc.)
    if (recvType != null) {
        var extFun = extensionFuns[recvType]?.find { it.name == method }
        var extFunOwner: String = recvType
        // Also check implemented interfaces for class/object receiver types
        if (extFun == null && (classes.containsKey(recvType) || objects.containsKey(recvType))) {
            val ifaces = classInterfaces[recvType] ?: emptyList()
            for (ifaceName in ifaces) {
                extFun = extensionFuns[ifaceName]?.find { it.name == method }
                if (extFun != null) { extFunOwner = ifaceName; break }
            }
        }
        if (extFun != null) {
            val nullableRecv = extFun.receiver?.nullable == true
            val recvArg = if (nullableRecv) {
                val recvName = (dot.obj as? NameExpr)?.name
                val recvVarKtc = if (recvName != null) lookupVarKtc(recvName) else null
                val optSelfType = optCTypeName("${recvType}?")
                when {
                    dot.obj is ThisExpr -> "\$self"
                    recvVarKtc != null && recvVarKtc is KtcType.Nullable && isValueNullableKtc(recvVarKtc)
                            && recvName != null && isOptional(recvName) -> recv

                    else -> optSome(optSelfType, recv)
                }
            } else recv
            // Use the receiver's actual interface type for the call
            val allArgs = if (argStr.isEmpty()) recvArg else "$recvArg, $argStr"
            return "${typeFlatName(extFunOwner)}_$method($allArgs)"
        }
        // Implicit dispose — always emitted as no-op
        if (method == "dispose" && (classes.containsKey(recvType) || enums.containsKey(recvType) || objects.containsKey(recvType))) {
            val selfExpr = if (recvTypeKtc is KtcType.Ptr) recv else "&$recv"
            val base = (recvTypeKtc as? KtcType.Ptr)?.inner?.let { it as? KtcType.User }?.baseName ?: recvType
            return "${typeFlatName(base)}_dispose($selfExpr)"
        }
        // Per-class star-projection extension (e.g. fun Map<K,V>.tryDispose())
        if (classes.containsKey(recvType) && starExtFunDecls.any { it.name == method }) {
            val selfExpr = if (recvTypeKtc is KtcType.Ptr) recv else "&$recv"
            val base = (recvTypeKtc as? KtcType.Ptr)?.inner?.let { it as? KtcType.User }?.baseName ?: recvType
            val allArgs = if (argStr.isEmpty()) selfExpr else "$selfExpr, $argStr"
            return "${typeFlatName(base)}_$method($allArgs)"
        }
    }

    // .ptr() for value types (primitives, enums, etc.) — take address
    if (method == "ptr" && recvType != null) {
        val cType = cTypeStr(recvType)
        val t = tmp()
        preStmts += "$cType* $t = &$recv;"
        return t
    }

    return "$recv.$method($argStr)"   // fallback
}

/** Generate a method call that returns nullable via out-pointer. */
internal fun CCodeGen.genNullableMethodCall(className: String, fnExpr: String, allArgs: String, methodDecl: FunDecl): String {
    val retBase = resolveMethodReturnType(className, methodDecl.returnType).removeSuffix("?")
    val optType = optCTypeName("${retBase}?")
    val t = tmp()
    preStmts += "$optType $t = $fnExpr($allArgs);"
    markOptional(t)
    defineVar(t, "${retBase}?")
    return t
}

/** Generate data class copy. `heap` = true when receiver is a heap pointer. */
internal fun CCodeGen.genDataClassCopy(recv: String, className: String, args: List<Arg>, heap: Boolean): String {
    val cName = typeFlatName(className)
    val src = if (heap) "(*$recv)" else recv
    if (args.isEmpty()) {
        // Simple copy — struct value copy
        return src
    }
    // copy(field = val, ...) — hoist to temp, override named fields
    val t = tmp()
    preStmts += "$cName $t = $src;"
    val ci = classes[className]
    val props = ci?.props?.associate { it.first to it.second } ?: emptyMap()
    for (arg in args) {
        val fieldName = arg.name ?: continue
        val fieldType = props[fieldName]
        val value = genExpr(arg.expr)
        if (fieldType != null && fieldType.nullable) {
            val baseType = resolveTypeName(fieldType).toInternalStr.removeSuffix("?")
            val optType = optCTypeName("${baseType}?")
            val optExpr = if (arg.expr is NullLit) optNone(optType) else optSome(optType, value)
            preStmts += "$t.$fieldName = $optExpr;"
        } else {
            preStmts += "$t.$fieldName = $value;"
        }
    }
    return t
}

internal fun CCodeGen.genSafeMethodCall(dot: SafeDotExpr, args: List<Arg>): String {
    val recvName = (dot.obj as? NameExpr)?.name
    val recvTypeKtc = if (recvName != null) lookupVarKtc(recvName) else inferExprTypeKtc(dot.obj)
    val recvTypeCoreKtc = (recvTypeKtc as? KtcType.Nullable)?.inner ?: recvTypeKtc
    val recvType = recvTypeKtc?.toInternalStr
    // Warn: ?. method call on a non-nullable receiver (and not a pointer)
    if (recvTypeKtc != null && recvTypeKtc !is KtcType.Nullable && recvTypeCoreKtc !is KtcType.Ptr) {
        val vSrc = recvName ?: "expression"
        codegenWarning("Safe call '?.' on non-nullable '$recvType' ($vSrc.${dot.name}) is redundant; use '.' instead")
    }
    val isValueNullRecv = recvTypeKtc is KtcType.Nullable && isValueNullableKtc(recvTypeKtc)

    // Handle intermediate safe-call chains (e.g., a?.b()?.c())
    if (recvName == null) {
        val recvExpr = genExpr(dot.obj)  // pre-compute, returns temp name from inner safe-call
        val dotExpr2 = DotExpr(NameExpr(recvExpr), dot.name)
        val call2 = genMethodCall(dotExpr2, args)
        val guard2 = if (recvTypeKtc is KtcType.Nullable) nullGuardExpr(recvTypeKtc, recvExpr, recvExpr, isThis = false) else "true"
        val retType2 = inferMethodReturnType(dotExpr2, args)
        if (retType2 == null || retType2 == "Unit") {
            return "($guard2 ? ($call2, 0) : 0)"
        }
        val retKtc2 = parseResolvedTypeName(retType2)
        if (retKtc2 is KtcType.Ptr) {
            val t2 = tmp()
            preStmts += "${cTypeStr(retType2)} $t2 = $guard2 ? $call2 : NULL;"
            defineVar(t2, "${retType2}?")
            return t2
        }
        val optType2 = optCTypeName("${retType2}?")
        val t2 = tmp()
        preStmts += "$optType2 $t2 = $guard2 ? ($optType2){ktc_SOME, $call2} : ${optNone(optType2)};"
        markOptional(t2)
        defineVar(t2, "${retType2}?")
        return t2
    }

    val dotExpr = DotExpr(dot.obj, dot.name)

    // Handle .ptr() safe-call: guard first, then take address
    if (dot.name == "ptr" && isValueNullRecv && recvName != null) {
        val baseClass = recvType!!.removeSuffix("?")
        val cName = typeFlatName(baseClass)
        val t = tmp()
        preStmts += "$cName* $t = ($recvName.tag == ktc_SOME ? &${recvName}.value : NULL);"
        defineVar(t, "${baseClass}*?")
        return t
    }
    if (dot.name == "ptr" && recvType != null && recvName != null) {
        val cleanType = recvType.removeSuffix("?")
        if (recvTypeCoreKtc != null && recvTypeCoreKtc.isArrayLike) {
            val t = tmp()
            val guard = if (recvTypeKtc is KtcType.Nullable) "${recvName}\$has" else "true"
            val arrCType = cTypeStr(cleanType)
            preStmts += "$arrCType $t = $guard ? $recvName : NULL;"
            preStmts += "ktc_Int ${t}\$len = $guard ? ${recvName}\$len : 0;"
            defineVar(t, "${cleanType}*?")
            return t
        }
    }

    val call = genMethodCall(dotExpr, args)
    // Determine the null guard expression
    val guard = if (recvName != null && recvTypeKtc != null) nullGuardExpr(recvTypeKtc, recvName, recvName, isThis = false) else "${recvName}\$has"
    // Determine the return type
    val retType = inferMethodReturnType(dotExpr, args)
    if (retType == null || retType == "Unit") {
        return "($guard ? ($call, 0) : 0)"
    }
    // Pointer return (@Ptr): use NULL for null, no Optional wrapping
    val retKtc = parseResolvedTypeName(retType)
    if (retKtc is KtcType.Ptr) {
        val t = tmp()
        preStmts += "${cTypeStr(retType)} $t = $guard ? $call : NULL;"
        defineVar(t, "${retType}?")
        return t
    }
    // Emit temp as Optional
    val optType = optCTypeName("${retType}?")
    val t = tmp()
    preStmts += "$optType $t = $guard ? ($optType){ktc_SOME, $call} : ${optNone(optType)};"
    markOptional(t)
    defineVar(t, "${retType}?")
    return t
}

// ── dot access (property, enum) ──────────────────────────────────

internal fun CCodeGen.genDot(e: DotExpr): String {
    // C package passthrough: c.EXIT_SUCCESS → EXIT_SUCCESS, c.NULL → NULL
    if (e.obj is NameExpr && e.obj.name == "c" && lookupVar("c") == null) {
        return e.name
    }

    val recvType = inferExprType(e.obj)                                               // String? receiver type (string-based)
    val recvTypeKtc = inferExprTypeKtc(e.obj)                                         // KtcType? receiver type
    val recvTypeCoreKtc = (recvTypeKtc as? KtcType.Nullable)?.inner ?: recvTypeKtc   // KtcType? stripped of Nullable wrapper
    val recv = genExpr(e.obj)

    // Reject non-safe access on nullable receiver (enum/object/companion are never nullable)
    // Allow array types (plain or indirect) where size/index access is safe
    val isEnumOrObj = e.obj is NameExpr && (enums.containsKey(e.obj.name) || objects.containsKey(e.obj.name) || classCompanions.containsKey(e.obj.name))
    if (recvTypeKtc is KtcType.Nullable && !isEnumOrObj) {
        val innerKtc = recvTypeKtc.inner
        val isIndirectArray = innerKtc is KtcType.Ptr && innerKtc.inner is KtcType.Arr
        if (!isIndirectArray && innerKtc !is KtcType.Arr) {
            val recvSrc = (e.obj as? NameExpr)?.name ?: e.obj.toString()
            codegenError("Only safe (?.) access is allowed on a nullable receiver of type '$recvType': $recvSrc.${e.name}")
        }
    }

    // Enum entry: Color.RED → game_Color_RED
    if (e.obj is NameExpr && enums.containsKey(e.obj.name)) {
        val vDotEnumInfo = enums[e.obj.name]!!                                        // EnumInfo for C name
        return "${vDotEnumInfo.flatName}_${e.name}"
    }
    // Object / Companion field: ensure lazy init, then return flatName.field
    val vDotObjCName = resolveDotObjCName(e)
    if (vDotObjCName != null) {
        // Visibility check: can't access private props from outside the object
        val objInfo = resolveDotObjInfo(e)
        if (objInfo != null && objInfo.name != currentObject && objInfo.privateProps.contains(e.name)) {
            val displayName = objInfo.name.replace('$', '.')
            codegenError("Cannot access '${e.name}': it is private in object '$displayName'")
        }
        preStmts += "${vDotObjCName}_\$ensure_init();"
        val fieldName = if (objInfo != null && objInfo.privateProps.contains(e.name)) "PRIV_${e.name}" else e.name
        return "${vDotObjCName}.${fieldName}"
    }
    // Array .size → trampolined param uses trampoline struct field; others use $len
    if (e.name == "size" && e.obj is NameExpr && e.obj.name in trampolinedParams) return "${e.obj.name}.size"
    if (e.name == "size" && recvTypeCoreKtc != null && recvTypeCoreKtc.isArrayLike) return "${recv}\$len"
    if (e.name == "length" && recvTypeKtc is KtcType.Str) return "$recv.len"
    if (e.name == "runeLen" && recvTypeKtc is KtcType.Str) return "ktc_core_str_runeLen($recv)"
    // Enum .ordinal → the int value itself
    val vOrdinalEnumInfo = enumInfoFor(recvTypeCoreKtc)                               // non-null if receiver is an enum (for ordinal/name)
    if (e.name == "ordinal" && vOrdinalEnumInfo != null) return recv
    // Enum .name → lookup in names array
    if (e.name == "name" && vOrdinalEnumInfo != null) return "${vOrdinalEnumInfo.flatName}_names[($recv)]"

    // p->field (auto-deref through pointer)
    if (recvTypeCoreKtc is KtcType.Ptr) {
        val fieldName = if (currentClass != null && e.obj is ThisExpr) {
            val ci = classes[currentClass]!!
            if (e.name in ci.privateProps) "PRIV_${e.name}" else e.name
        } else e.name
        return "$recv->${fieldName}"
    }

    // Interface property access via vtable: list.size → list.vt->size(data_ptr)
    val vIfaceDotInfo = ifaceInfoFor(recvTypeCoreKtc)                                 // non-null if receiver is a known interface
    if (vIfaceDotInfo != null) {
        val allProps = collectAllIfaceProperties(vIfaceDotInfo)
        if (allProps.any { it.name == e.name }) {
            return "$recv.vt->${e.name}(${ifaceVtableSelf(vIfaceDotInfo.name, recv)})"
        }
    }

    // StringBuffer field access: sb.buffer → sb.ptr (the raw char pointer)
    if (recvType == "ktc_StrBuf" || recvType == "StringBuffer") {
        if (e.name == "buffer") return "$recv.ptr"
        if (e.name == "len") return "$recv.len"
    }

    val fieldName = if (currentClass != null && e.obj is ThisExpr) {
        val ci = classes[currentClass]!!
        if (e.name in ci.privateProps) "PRIV_${e.name}" else e.name
    } else e.name

    // Smart-cast from interface to class: redirect field access through union data
    if (e.obj is NameExpr) {
        val vOrigIface = isIfaceSmartCastVar(e.obj.name)
        if (vOrigIface != null) {
            val vNarrowedType = lookupVar(e.obj.name)!!
            return "${ifaceUnionAccess(vOrigIface, vNarrowedType, e.obj.name)}.$fieldName"
        }
    }
    // $self narrowed from interface in extension (this.field)
    if (e.obj is ThisExpr && currentExtRecvType != null && interfaces.containsKey(currentExtRecvType)) {
        val vNarrowedSelf = lookupVar("\$self")
        if (vNarrowedSelf != null && classes.containsKey(vNarrowedSelf)) {
            return "${ifaceUnionAccess(currentExtRecvType!!, vNarrowedSelf, "\$self")}.$fieldName"
        }
    }

    return "$recv.${fieldName}"
}

internal fun CCodeGen.genSafeDot(e: SafeDotExpr): String {
    val recvType = inferExprType(e.obj)
    val recvTypeKtc = inferExprTypeKtc(e.obj)
    val recvTypeCoreKtc = (recvTypeKtc as? KtcType.Nullable)?.inner ?: recvTypeKtc
    // Warn: ?. on a receiver that is already non-nullable (and not a pointer)
    if (recvTypeKtc != null && recvTypeKtc !is KtcType.Nullable && recvTypeCoreKtc !is KtcType.Ptr) {
        val vSrc = (e.obj as? NameExpr)?.name ?: "expression"
        codegenWarning("Safe call '?.' on non-nullable '$recvType' ($vSrc) is redundant; use '.' instead")
    }
    val recv = genExpr(e.obj)
    val recvName = (e.obj as? NameExpr)?.name
    val isThis = e.obj is ThisExpr
    val isValueNullRecv = recvTypeKtc is KtcType.Nullable && isValueNullableKtc(recvTypeKtc)

    // Determine the null guard expression
    val guard = if (isThis || recvName != null) nullGuardExpr(recvTypeKtc ?: KtcType.Prim(KtcType.PrimKind.Int), recv, recvName ?: recv, isThis) else "${recv}\$has"

    // Unwrapped receiver expression for field access (unwrap Optional if needed)
    val recvVal = if (isValueNullRecv) "$recv.value" else recv

    // Determine field access expression (same logic as genDot but without nullable check)
    val fieldAccess = when {
        recvTypeCoreKtc is KtcType.Ptr -> "$recvVal->${e.name}"
        e.name == "size" && recvTypeCoreKtc != null && recvTypeCoreKtc.isArrayLike -> "${recvVal}\$len"
        e.name == "length" && recvTypeCoreKtc is KtcType.Str -> "$recvVal.len"
        else -> "$recvVal.${e.name}"
    }

    // Infer field type for proper default and C type
    val fieldKtc = inferDotTypeKtc(DotExpr(e.obj, e.name))
    val fieldType = fieldKtc?.toInternalStr

    // Emit temp as Optional for value-nullable field results
    val t = tmp()
    val isFieldValueNull = fieldKtc is KtcType.Nullable && isValueNullableKtc(fieldKtc)
    if (isFieldValueNull) {
        val optType = optCTypeName(fieldType!!)
        preStmts += "$optType $t = $guard ? $fieldAccess : ${optNone(optType)};"
        markOptional(t)
        defineVar(t, fieldType)
    } else {
        val optType = if (fieldType != null) optCTypeName("${fieldType}?") else "ktc_Int\$Opt"
        preStmts += "$optType $t = $guard ? ($optType){ktc_SOME, $fieldAccess} : ${optNone(optType)};"
        markOptional(t)
        defineVar(t, "${fieldType ?: "Int"}?")
    }
    return t
}

// ── !! (not-null assertion) ─────────────────────────────────────────

internal fun CCodeGen.genNotNull(e: NotNullExpr): String {
    val inner = genExpr(e.expr)
    val innerType = inferExprType(e.expr)
    val innerKtc = inferExprTypeKtc(e.expr)
    val innerKtcCore = (innerKtc as? KtcType.Nullable)?.inner ?: innerKtc
    val loc = "$sourceFileName:$currentStmtLine"

    // Pointer-nullable: type ends with "*", "^", or "&"
    val baseType = innerType?.removeSuffix("?") ?: ""
    val isPtr = innerKtcCore is KtcType.Ptr || isAllocCall(e.expr)

    if (isPtr) {
        val ct = cTypeStr(baseType.ifEmpty { "void*" })
        // Simple name — no temp needed
        if (e.expr is NameExpr) {
            preStmts += "if (!$inner) { fprintf(stderr, \"NullPointerException: $loc\\n\"); exit(1); }"
            return inner
        }
        val t = tmp()
        preStmts += "$ct $t = $inner;"
        if (isArrayType(baseType) || isAllocArrayCall(e.expr)) {
            preStmts += "const ktc_Int ${t}\$len = ${inner}\$len;"
        }
        preStmts += "if (!$t) { fprintf(stderr, \"NullPointerException: $loc\\n\"); exit(1); }"
        return t
    }

    // Value-nullable variable: check Optional tag
    if (innerKtc is KtcType.Nullable && isValueNullableKtc(innerKtc) && e.expr is NameExpr) {
        val name = e.expr.name
        preStmts += "if ($name.tag == ktc_NONE) { fprintf(stderr, \"NullPointerException: $loc\\n\"); exit(1); }"
        // Return the unwrapped value
        return "$name.value"
    }

    // Check: !! on a type that inference knows is non-nullable — always a bug
    // Exclude smart-cast variables: they are stored as Optional but narrowed in scope (isOptional guard).
    val isSmartCastNarrowed = e.expr is NameExpr && isOptional(e.expr.name)
    if (innerKtc != null && innerKtc !is KtcType.Nullable && !isSmartCastNarrowed) {
        codegenError("Non-null assertion '!!' has no effect on non-nullable type '$innerType'")
    }

    // Fallback: no check (non-nullable expression)
    return inner
}

/** Find a common interface implemented by both types, or null. */
internal fun CCodeGen.findCommonInterface(type1: String?, type2: String?): String? {
    if (type1 == null || type2 == null || type1 == type2) return null
    val ifaces1 = classInterfaces[type1]?.toSet() ?: return null
    val ifaces2 = classInterfaces[type2]?.toSet() ?: return null
    val common = ifaces1.intersect(ifaces2)
    return common.firstOrNull()
}

/** Emit block statements into preStmts, wrapping the last expression into an interface struct. */
internal fun CCodeGen.emitBlockIntoTempIface(b: Block, tempVar: String, concreteType: String, ifaceName: String, indent: String) {
    val cConcrete = typeFlatName(concreteType)
    val impls = interfaceImplementors[ifaceName] ?: emptyList()
    val dataName = ifaceDataName(concreteType)
    val fieldPath = when {
        impls.size <= 1 -> ".$dataName"
        else -> ".data.$dataName"
    }
    for ((i, s) in b.stmts.withIndex()) {
        if (i == b.stmts.lastIndex) {
            val expr = when (s) {
                is ExprStmt -> s.expr
                is ReturnStmt -> s.value
                else -> null
            }
            if (expr != null) {
                val valExpr = genExpr(expr)
                preStmts += "$indent$tempVar$fieldPath = $valExpr;"
                preStmts += "$indent$tempVar.vt = &${cConcrete}_${ifaceName}_vt;"
            } else {
                emitStmtToPreStmts(s, indent)
            }
        } else {
            emitStmtToPreStmts(s, indent)
        }
    }
}

// ── if expression (as C ternary or temp) ─────────────────────────

internal fun CCodeGen.genIfExpr(e: IfExpr): String {
    val thenType = inferBlockType(e.then)
    val elseType = if (e.els != null) inferBlockType(e.els) else null
    val commonIface = findCommonInterface(thenType, elseType)

    // Interface coercion: branches return different types sharing a common interface
    if (commonIface != null) {
        val t = tmp()
        val cIface = typeFlatName(commonIface)
        preStmts += "$cIface $t;"
        preStmts += "if (${genExpr(e.cond)}) {"
        emitBlockIntoTempIface(e.then, t, thenType!!, commonIface, "    ")
        if (e.els != null) {
            preStmts += "} else {"
            emitBlockIntoTempIface(e.els, t, elseType!!, commonIface, "    ")
        }
        preStmts += "}"
        return t
    }

    // Simple case: both branches are single expressions → ternary
    val thenExpr = blockAsSingleExpr(e.then)
    val elseExpr = if (e.els != null) blockAsSingleExpr(e.els) else null
    if (thenExpr != null && (e.els == null || elseExpr != null)) {
        val thenStr = genExpr(thenExpr)
        val elseStr = if (elseExpr != null) genExpr(elseExpr) else "0"
        return "(${genExpr(e.cond)} ? $thenStr : $elseStr)"
    }

    // Complex case: multi-statement bodies → hoist to temp
    val t = tmp()
    val retType = inferIfExprType(e) ?: "Int"
    val ct = cTypeStr(retType)
    preStmts += "$ct $t;"
    preStmts += "if (${genExpr(e.cond)}) {"
    emitBlockIntoTemp(e.then, t, "    ")
    if (e.els != null) {
        preStmts += "} else {"
        emitBlockIntoTemp(e.els, t, "    ")
    }
    preStmts += "}"
    return t
}

/** Try to extract a single expression from a block (last stmt as expr). */
internal fun blockAsSingleExpr(b: Block): Expr? {
    if (b.stmts.size == 1) {
        val s = b.stmts[0]
        if (s is ExprStmt) return s.expr
    }
    return null
}

/** Emit block statements into preStmts, assigning last expression to tempVar. */
internal fun CCodeGen.emitBlockIntoTemp(b: Block, tempVar: String, indent: String) {
    for ((i, s) in b.stmts.withIndex()) {
        if (i == b.stmts.lastIndex) {
            // Last statement: assign its value to temp
            val expr = when (s) {
                is ExprStmt -> s.expr
                is ReturnStmt -> s.value
                else -> null
            }
            if (expr != null) {
                preStmts += "$indent$tempVar = ${genExpr(expr)};"
            } else {
                // Non-expression last statement — just emit it
                emitStmtToPreStmts(s, indent)
            }
        } else {
            emitStmtToPreStmts(s, indent)
        }
    }
}

/** Emit a statement into preStmts (for hoisting into if/when expression bodies). */
internal fun CCodeGen.emitStmtToPreStmts(s: Stmt, indent: String) {
    when (s) {
        is ExprStmt -> {
            val expr = genExpr(s.expr)
            preStmts += "$indent$expr;"
        }

        is VarDeclStmt -> {
            val t = if (s.type != null) resolveTypeName(s.type).toInternalStr else (inferExprType(s.init) ?: "Int")
            val ct = cTypeStr(t)
            val initExpr = if (s.init != null) genExpr(s.init) else defaultVal(parseResolvedTypeName(t))
            preStmts += "$indent$ct ${s.name} = $initExpr;"
            defineVar(s.name, t)
        }

        else -> preStmts += "$indent/* unsupported stmt in expr block */;"
    }
}

internal fun CCodeGen.inferIfExprType(e: IfExpr): String? {
    val thenType = inferBlockType(e.then)
    val elseType = if (e.els != null) inferBlockType(e.els) else null
    val commonIface = findCommonInterface(thenType, elseType)
    if (commonIface != null) return commonIface
    return thenType ?: elseType
}

internal fun CCodeGen.inferBlockType(b: Block): String? {
    val last = b.stmts.lastOrNull() ?: return null
    return when (last) {
        is ExprStmt -> inferExprType(last.expr)
        is ReturnStmt -> if (last.value != null) inferExprType(last.value) else null
        else -> null
    }
}

// ── when expression (nested ternary or temp) ──────────────────────

internal fun CCodeGen.genWhenExpr(e: WhenExpr): String {
    // ThisExpr subject maps to $self; NameExpr subject maps to its variable name
    val subjName = when (e.subject) {
        is NameExpr -> e.subject.name
        is ThisExpr -> "\$self"
        else -> null
    }
    // Check if branches need interface coercion (different types sharing a common interface)
    val branchTypes = e.branches.map { inferBlockType(it.body) }
    val distinctTypes = branchTypes.filterNotNull().distinct()
    val commonIface = if (distinctTypes.size > 1) {
        var common: Set<String>? = null
        for (t in distinctTypes) {
            val ifaces = classInterfaces[t]?.toSet() ?: break
            common = common?.intersect(ifaces) ?: ifaces
            if (common.isEmpty()) break
        }
        common?.firstOrNull()
    } else null

    if (commonIface != null) {
        // Interface coercion: use temp with interface type, wrap each branch
        val t = tmp()
        val cIface = typeFlatName(commonIface)
        preStmts += "$cIface $t;"
        for ((bi, br) in e.branches.withIndex()) {
            if (br.conds == null) {
                preStmts += if (bi > 0) "} else {" else "{"
            } else {
                val condStr = br.conds.joinToString(" || ") { genWhenCond(it, e.subject) }
                val keyword = if (bi == 0) "if" else "} else if"
                preStmts += "$keyword ($condStr) {"
            }
            val narrowedType = narrowSubjectForBranch(br, subjName)
            if (narrowedType != null) {
                preStmts += "    // smart-cast: '$subjName' narrowed to '$narrowedType'"
                pushScope(); defineVar(subjName!!, narrowedType)
            }
            val brType = branchTypes[bi]
            if (brType != null && classes.containsKey(brType)) {
                emitBlockIntoTempIface(br.body, t, brType, commonIface, "    ")
            } else {
                emitBlockIntoTemp(br.body, t, "    ")
            }
            if (narrowedType != null) popScope()
        }
        preStmts += "}"
        return t
    }

    // If any branch needs is-narrowing, avoid ternary: accessing wrong union member is UB
    val hasNarrowingBranch = e.branches.any { narrowSubjectForBranch(it, subjName) != null }
    // Check if all branches are single-expression → nested ternary (only when no narrowing needed)
    val allSimple = !hasNarrowingBranch && e.branches.all { blockAsSingleExpr(it.body) != null }
    if (allSimple) {
        val sb = StringBuilder()
        for (br in e.branches) {
            val narrowedType = narrowSubjectForBranch(br, subjName)
            if (narrowedType != null) {
                pushScope(); defineVar(subjName!!, narrowedType)
            }
            val expr = genExpr(blockAsSingleExpr(br.body)!!)
            if (narrowedType != null) popScope()
            if (br.conds == null) {
                sb.append(expr)
            } else {
                val cond = br.conds.joinToString(" || ") { genWhenCond(it, e.subject) }
                sb.append("($cond) ? $expr : ")
            }
        }
        return sb.toString()
    }

    // Complex case: hoist to temp
    val t = tmp()
    val retType = inferWhenExprType(e) ?: "Int"
    val ct = cTypeStr(retType)
    preStmts += "$ct $t;"
    for ((bi, br) in e.branches.withIndex()) {
        if (br.conds == null) {
            preStmts += if (bi > 0) "} else {"
            else "{"
        } else {
            val condStr = br.conds.joinToString(" || ") { genWhenCond(it, e.subject) }
            val keyword = if (bi == 0) "if" else "} else if"
            preStmts += "$keyword ($condStr) {"
        }
        val narrowedType = narrowSubjectForBranch(br, subjName)
        if (narrowedType != null) {
            preStmts += "    // smart-cast: '$subjName' narrowed to '$narrowedType'"
            pushScope(); defineVar(subjName!!, narrowedType)
        }
        emitBlockIntoTemp(br.body, t, "    ")
        if (narrowedType != null) popScope()
    }
    preStmts += "}"
    return t
}

internal fun CCodeGen.narrowSubjectForBranch(br: WhenBranch, subjName: String?): String? {
    if (br.conds == null || subjName == null || isMutable(subjName)) return null
    val isCond = br.conds.find { it is IsCond && !it.negated } as? IsCond ?: return null
    val target = resolveTypeName(isCond.type).toInternalStr
    // $self in extension function: use currentExtRecvType as the base type when not in scope
    val currentKtc = if (subjName == "\$self") (lookupVarKtc("\$self") ?: (currentExtRecvType?.let { parseResolvedTypeName(it) })) ?: return null
                      else lookupVarKtc(subjName) ?: return null
    val current = currentKtc.toInternalStr
    // Don't narrow pointer types (Any* etc.) — they need original type for ->data dereference
    if (currentKtc is KtcType.Ptr) return null
    return if (current != target) target else null
}

internal fun CCodeGen.inferWhenExprType(e: WhenExpr): String? {
    val types = e.branches.mapNotNull { inferBlockType(it.body) }
    if (types.isEmpty()) return null
    if (types.distinct().size > 1) {
        var common: Set<String>? = null
        for (t in types) {
            val ifaces = classInterfaces[t]?.toSet() ?: break
            common = common?.intersect(ifaces) ?: ifaces
            if (common.isEmpty()) break
        }
        if (!common.isNullOrEmpty()) return common.first()
    }
    return types.first()
}

/* Returns true when expr contains no function calls — safe to evaluate multiple times without side effects. */
internal fun isSimpleCExpr(inExpr: String) = '(' !in inExpr

// ── println / print (expression context — rare) ──────────────────

internal fun CCodeGen.genPrintln(args: List<Arg>): String {
    if (args.isEmpty()) return "printf(\"\\n\")"
    return genPrintCall(args, newline = true)
}

internal fun CCodeGen.genPrint(args: List<Arg>): String {
    if (args.isEmpty()) return "(void)0"
    return genPrintCall(args, newline = false)
}

internal fun CCodeGen.genPrintCall(args: List<Arg>, newline: Boolean): String {
    val arg = args[0].expr
    val nl = if (newline) "\\n" else ""

    // String template → direct printf
    if (arg is StrTemplateExpr) {
        return genPrintfFromTemplate(arg, nl)
    }

    val t = inferExprType(arg) ?: "Int"
    val tKtc = inferExprTypeKtc(arg) ?: KtcType.Prim(KtcType.PrimKind.Int)
    val tKtcCore = (tKtc as? KtcType.Nullable)?.inner ?: tKtc
    val expr = genExpr(arg)

    // Nullable → ternary: $has ? printf(value) : printf("null")
    if (tKtc is KtcType.Nullable) {
        // Materialize only when complex to avoid repeated evaluation
        val safeExpr = if (!isSimpleCExpr(expr)) {
            val vTmp = tmp(); preStmts += "${cTypeStr(t)} $vTmp = ($expr);"; vTmp
        } else expr
        val isPtrNull = tKtc.inner is KtcType.Ptr && !isValueNullableKtc(tKtc)
        val hasExpr = if (isPtrNull) "$safeExpr != NULL" else if (isValueNullableKtc(tKtc)) "$safeExpr.tag == ktc_SOME" else "${safeExpr}\$has"
        val fmt = printfFmt(tKtcCore) + nl
        val a = printfArg(safeExpr, tKtcCore)
        return "($hasExpr ? printf(\"$fmt\", $a) : printf(\"null$nl\"))"
    }

    // data class → two-pass StrBuf for exact-size alloca (or fixed buffer if bounded)
    if (classes.containsKey(t) && classes[t]!!.isData) {
        val maxLen = toStringMaxLen(t)
        if (maxLen != null && maxLen <= 512) {
            val buf = tmp()
            val vTmp = tmp()
            preStmts += "${cTypeStr(t)} $vTmp = ($expr);"
            preStmts += "ktc_Char ${buf}[$maxLen];"
            preStmts += "ktc_StrBuf ${buf}_sb = {${buf}, 0, $maxLen};"
            preStmts += "${typeFlatName(t)}_toString(&$vTmp, &${buf}_sb);"
            return "printf(\"%.*s$nl\", (ktc_Int)${buf}_sb.len, ${buf}_sb.ptr)"
        }
        val buf = tmp()
        val vTmp = tmp()
        preStmts += "${cTypeStr(t)} $vTmp = ($expr);"
        preStmts += "ktc_StrBuf ${buf}_sb = {NULL, 0, 0};"
        preStmts += "${typeFlatName(t)}_toString(&$vTmp, &${buf}_sb);"
        preStmts += "ktc_Char* $buf = (ktc_Char*)ktc_core_alloca(${buf}_sb.len + 1);"
        preStmts += "${buf}_sb = (ktc_StrBuf){${buf}, 0, ${buf}_sb.len + 1};"
        preStmts += "${typeFlatName(t)}_toString(&$vTmp, &${buf}_sb);"
        return "printf(\"%.*s$nl\", (ktc_Int)${buf}_sb.len, ${buf}_sb.ptr)"
    }
    // Non-data class/object/interface → use toString()
    if (classes.containsKey(t) || objects.containsKey(t) || interfaces.containsKey(t)) {
        val str = genToString(expr, t)
        val tmpStr = tmp()
        preStmts += "ktc_String $tmpStr = $str;"
        return "printf(\"%.*s$nl\", (ktc_Int)${tmpStr}.len, ${tmpStr}.ptr)"
    }
    // Heap/Ptr/Value to data class → pass pointer directly
    val indirectBase = (tKtc as? KtcType.Ptr)?.inner?.let { it as? KtcType.User }?.baseName
    if (indirectBase != null && classes[indirectBase]?.isData == true) {
        val maxLen = toStringMaxLen(indirectBase)
        if (maxLen != null && maxLen <= 512) {
            val buf = tmp()
            preStmts += "ktc_Char ${buf}[$maxLen];"
            preStmts += "ktc_StrBuf ${buf}_sb = {${buf}, 0, $maxLen};"
            preStmts += "${typeFlatName(indirectBase)}_toString($expr, &${buf}_sb);"
            return "printf(\"%.*s$nl\", (ktc_Int)${buf}_sb.len, ${buf}_sb.ptr)"
        }
        val buf = tmp()
        preStmts += "ktc_StrBuf ${buf}_sb = {NULL, 0, 0};"
        preStmts += "${typeFlatName(indirectBase)}_toString($expr, &${buf}_sb);"
        preStmts += "ktc_Char* $buf = (ktc_Char*)ktc_core_alloca(${buf}_sb.len + 1);"
        preStmts += "${buf}_sb = (ktc_StrBuf){${buf}, 0, ${buf}_sb.len + 1};"
        preStmts += "${typeFlatName(indirectBase)}_toString($expr, &${buf}_sb);"
        return "printf(\"%.*s$nl\", (ktc_Int)${buf}_sb.len, ${buf}_sb.ptr)"
    }

    // String / enum: printfArg expands expr twice (.len + .ptr or names[x] twice) — materialize if complex
    if (t == "String") {
        val safeExpr = if (!isSimpleCExpr(expr)) { val vTmp = tmp(); preStmts += "ktc_String $vTmp = ($expr);"; vTmp } else expr
        return "printf(\"%.*s$nl\", (ktc_Int)($safeExpr).len, ($safeExpr).ptr)"
    }
    if (t in enums) {
        val cName = typeFlatName(t)
        val safeExpr = if (!isSimpleCExpr(expr)) { val vTmp = tmp(); preStmts += "$cName $vTmp = ($expr);"; vTmp } else expr
        return "printf(\"%.*s$nl\", (ktc_Int)${cName}_names[$safeExpr].len, ${cName}_names[$safeExpr].ptr)"
    }
    val fmt = printfFmt(tKtcCore) + nl
    val a = printfArg(expr, tKtcCore)
    return "printf(\"$fmt\", $a)"
}

internal fun CCodeGen.genPrintfFromTemplate(tmpl: StrTemplateExpr, nl: String): String {
    val fmt = StringBuilder()
    val argsList = mutableListOf<String>()
    for (part in tmpl.parts) {
        when (part) {
            is LitPart -> fmt.append(escapeStr(part.text))
            is ExprPart -> {
                val tKtc = inferExprTypeKtc(part.expr) ?: KtcType.Prim(KtcType.PrimKind.Int)
                val tKtcCore = (tKtc as? KtcType.Nullable)?.inner ?: tKtc
                fmt.append(printfFmt(tKtcCore))
                val exprStr = genExpr(part.expr)
                // String / enum: materialize if complex to avoid double-evaluation
                when (tKtcCore) {
                    is KtcType.Str -> {
                        val s = if (!isSimpleCExpr(exprStr)) {
                            val v = tmp(); preStmts += "ktc_String $v = ($exprStr);"; v
                        } else exprStr
                        argsList += "(ktc_Int)($s).len, ($s).ptr"
                    }

                    is KtcType.User if tKtcCore.kind == KtcType.UserKind.Enum -> {
                        val cName = typeFlatName(tKtcCore.baseName)
                        val s = if (!isSimpleCExpr(exprStr)) {
                            val v = tmp(); preStmts += "$cName $v = ($exprStr);"; v
                        } else exprStr
                        argsList += "(ktc_Int)${cName}_names[$s].len, ${cName}_names[$s].ptr"
                    }

                    else -> {
                        argsList += printfArg(exprStr, tKtcCore)
                    }
                }
            }
        }
    }
    fmt.append(nl)
    val argsStr = if (argsList.isNotEmpty()) ", " + argsList.joinToString(", ") else ""
    return "printf(\"$fmt\"$argsStr)"
}

// ── string template (returns ktc_String via preStmts) ─────────────

internal fun CCodeGen.genStrTemplate(e: StrTemplateExpr): String {
    val buf = tmp()

    // Pre-compute expressions (genExpr increments tmp counter, so do this once)
    data class PartData(val lit: String? = null, val sbAppend: String? = null)

    val parts = mutableListOf<PartData>()
    for (part in e.parts) {
        when (part) {
            is LitPart -> {
                val last = parts.lastOrNull()
                if (last?.lit != null) {
                    parts[parts.lastIndex] = PartData(lit = last.lit + part.text)
                } else {
                    parts += PartData(lit = part.text)
                }
            }

            is ExprPart -> {
                val tKtc = inferExprTypeKtc(part.expr) ?: KtcType.Prim(KtcType.PrimKind.Int)
                val expr = genExpr(part.expr)
                parts += PartData(sbAppend = genSbAppendKtc("&${buf}_sb", expr, tKtc))
            }
        }
    }
    val maxLen = templateMaxLen(e)
    if (maxLen != null && maxLen <= 512) {
        // Single pass with alloca buffer (alloca = function-frame lifetime, safe when pointer escapes block)
        preStmts += "ktc_Char* $buf = (ktc_Char*)ktc_core_alloca($maxLen);"
        preStmts += "ktc_StrBuf ${buf}_sb = {${buf}, 0, $maxLen};"
        for (p in parts) {
            when {
                p.lit != null -> preStmts += "ktc_core_sb_append_str(&${buf}_sb, ktc_core_str(\"${escapeStr(p.lit)}\"));"
                p.sbAppend != null -> preStmts += p.sbAppend
            }
        }
        return "ktc_core_sb_to_string(&${buf}_sb)"
    }
    // First pass: count length with NULL buffer
    preStmts += "ktc_StrBuf ${buf}_sb = {NULL, 0, 0};"
    for (p in parts) {
        when {
            p.lit != null -> preStmts += "ktc_core_sb_append_str(&${buf}_sb, ktc_core_str(\"${escapeStr(p.lit)}\"));"
            p.sbAppend != null -> preStmts += p.sbAppend
        }
    }
    // Allocate exact size
    preStmts += "ktc_Char* $buf = (ktc_Char*)ktc_core_alloca(${buf}_sb.len + 1);"
    preStmts += "${buf}_sb = (ktc_StrBuf){${buf}, 0, ${buf}_sb.len + 1};"
    // Second pass: write to real buffer
    for (p in parts) {
        when {
            p.lit != null -> preStmts += "ktc_core_sb_append_str(&${buf}_sb, ktc_core_str(\"${escapeStr(p.lit)}\"));"
            p.sbAppend != null -> preStmts += p.sbAppend
        }
    }
    return "ktc_core_sb_to_string(&${buf}_sb)"
}

// ── compile-time toString max length inference ──────────────────

private val toStringPrimitiveMaxLen = mapOf(
    "Boolean" to 5,   // "false"
    "Byte" to 4,   // "-128"
    "UByte" to 3,   // "255"
    "Short" to 6,   // "-32768"
    "UShort" to 5,   // "65535"
    "Int" to 11,  // "-2147483648"
    "UInt" to 10,  // "4294967295"
    "Long" to 20,  // "-9223372036854775808"
    "ULong" to 20,  // "18446744073709551615"
    "Float" to 64,  // %f via ktc_core_sb_append_double
    "Double" to 64,  // %f via ktc_core_sb_append_double
    "Char" to 8    // %c format buffer
)

internal fun CCodeGen.toStringMaxLen(baseType: String, visited: MutableSet<String> = mutableSetOf()): Int? {
    val t = baseType.removeSuffix("?").removeSuffix("*")
    if (t in visited) return null
    visited.add(t)

    val primMax = toStringPrimitiveMaxLen[t]
    if (primMax != null) {
        visited.remove(t)
        return primMax
    }

    // Data class: compute from field types recursively
    val ci = classes[t]
    if (ci != null && ci.isData) {
        var total = t.length + 2  // "Name(" + ")"
        for ((i, prop) in ci.props.withIndex()) {
            val (name, propType) = prop
            val tBase = resolveTypeName(propType).toInternalStr
            val baseClean = tBase.removeSuffix("?")
            val fieldMax = toStringMaxLen(baseClean, visited)
            if (fieldMax == null) {
                visited.remove(t); return null
            }
            val prefixLen = name.length + if (i == 0) 1 else 3  // "name=" or ", name="
            total += prefixLen
            total += if (propType.nullable) maxOf(fieldMax, 4) else fieldMax
        }
        visited.remove(t)
        return total
    }

    // Default class / object / interface: "Name@XXXXXXXX"
    if (classes.containsKey(t) || objects.containsKey(t) || interfaces.containsKey(t)) {
        visited.remove(t)
        return ktDisplayName(t).length + 10
    }

    visited.remove(t)
    return null
}

/** Compute max output length for a string template, or null if any part is unbounded. */
internal fun CCodeGen.templateMaxLen(tmpl: StrTemplateExpr): Int? {
    var total = 0
    for (part in tmpl.parts) {
        when (part) {
            is LitPart -> total += part.text.length
            is ExprPart -> {
                val t = inferExprType(part.expr) ?: return null
                val tKtc = inferExprTypeKtc(part.expr)
                val max = toStringMaxLen(t) ?: return null
                total += max
            }
        }
    }
    return total
}

// ── toString dispatch ────────────────────────────────────────────

internal fun CCodeGen.genToStringKtc(recv: String, type: KtcType): String = genToString(recv, type.toInternalStr)

internal fun CCodeGen.genToString(recv: String, type: String): String {
    if (classes.containsKey(type) && classes[type]!!.isData) {
        val maxLen = toStringMaxLen(type)
        if (maxLen != null && maxLen <= 512) {
            // Single pass with fixed stack buffer (maxLen known at compile time)
            val buf = tmp()
            val vTmp = tmp()
            preStmts += "${cTypeStr(type)} $vTmp = ($recv);"
            preStmts += "ktc_Char ${buf}[$maxLen];"
            preStmts += "ktc_StrBuf ${buf}_sb = {${buf}, 0, $maxLen};"
            preStmts += "${typeFlatName(type)}_toString(&$vTmp, &${buf}_sb);"
            return "ktc_core_sb_to_string(&${buf}_sb)"
        }
        // Two-pass with alloca (unbounded fields or too large)
        val buf = tmp()
        val vTmp = tmp()
        preStmts += "${cTypeStr(type)} $vTmp = ($recv);"
        preStmts += "ktc_StrBuf ${buf}_sb = {NULL, 0, 0};"
        preStmts += "${typeFlatName(type)}_toString(&$vTmp, &${buf}_sb);"
        preStmts += "ktc_Char* $buf = (ktc_Char*)ktc_core_alloca(${buf}_sb.len + 1);"
        preStmts += "${buf}_sb = (ktc_StrBuf){${buf}, 0, ${buf}_sb.len + 1};"
        preStmts += "${typeFlatName(type)}_toString(&$vTmp, &${buf}_sb);"
        return "ktc_core_sb_to_string(&${buf}_sb)"
    }
    return when (type) {
        "Byte" -> {
            val buf = tmp()
            val sz = 6   // "-128\0" = 5+1
            preStmts += "ktc_Char ${buf}[$sz];"
            preStmts += "ktc_StrBuf ${buf}_sb = {${buf}, 0, $sz};"
            preStmts += "ktc_core_sb_append_byte(&${buf}_sb, $recv);"
            "ktc_core_sb_to_string(&${buf}_sb)"
        }

        "Short" -> {
            val buf = tmp()
            val sz = 7   // "-32768\0" = 6+1
            preStmts += "ktc_Char ${buf}[$sz];"
            preStmts += "ktc_StrBuf ${buf}_sb = {${buf}, 0, $sz};"
            preStmts += "ktc_core_sb_append_short(&${buf}_sb, $recv);"
            "ktc_core_sb_to_string(&${buf}_sb)"
        }

        "Int" -> {
            val buf = tmp()
            val sz = 12   // "-2147483648\0" = 11+1
            preStmts += "ktc_Char ${buf}[$sz];"
            preStmts += "ktc_StrBuf ${buf}_sb = {${buf}, 0, $sz};"
            preStmts += "ktc_core_sb_append_int(&${buf}_sb, $recv);"
            "ktc_core_sb_to_string(&${buf}_sb)"
        }

        "Long" -> {
            val buf = tmp()
            val sz = 21   // "-9223372036854775808\0" = 20+1
            preStmts += "ktc_Char ${buf}[$sz];"
            preStmts += "ktc_StrBuf ${buf}_sb = {${buf}, 0, $sz};"
            preStmts += "ktc_core_sb_append_long(&${buf}_sb, $recv);"
            "ktc_core_sb_to_string(&${buf}_sb)"
        }

        "UByte" -> {
            val buf = tmp()
            val sz = 4   // "255\0" = 3+1
            preStmts += "ktc_Char ${buf}[$sz];"
            preStmts += "ktc_StrBuf ${buf}_sb = {${buf}, 0, $sz};"
            preStmts += "ktc_core_sb_append_ubyte(&${buf}_sb, $recv);"
            "ktc_core_sb_to_string(&${buf}_sb)"
        }

        "UShort" -> {
            val buf = tmp()
            val sz = 6   // "65535\0" = 5+1
            preStmts += "ktc_Char ${buf}[$sz];"
            preStmts += "ktc_StrBuf ${buf}_sb = {${buf}, 0, $sz};"
            preStmts += "ktc_core_sb_append_ushort(&${buf}_sb, $recv);"
            "ktc_core_sb_to_string(&${buf}_sb)"
        }

        "UInt" -> {
            val buf = tmp()
            val sz = 11   // "4294967295\0" = 10+1
            preStmts += "ktc_Char ${buf}[$sz];"
            preStmts += "ktc_StrBuf ${buf}_sb = {${buf}, 0, $sz};"
            preStmts += "ktc_core_sb_append_uint(&${buf}_sb, $recv);"
            "ktc_core_sb_to_string(&${buf}_sb)"
        }

        "ULong" -> {
            val buf = tmp()
            val sz = 21   // "18446744073709551615\0" = 20+1
            preStmts += "ktc_Char ${buf}[$sz];"
            preStmts += "ktc_StrBuf ${buf}_sb = {${buf}, 0, $sz};"
            preStmts += "ktc_core_sb_append_ulong(&${buf}_sb, $recv);"
            "ktc_core_sb_to_string(&${buf}_sb)"
        }

        "Float" -> {
            val buf = tmp()
            preStmts += "ktc_Char ${buf}[32];"
            preStmts += "snprintf($buf, 32, \"%g\", (ktc_Double)($recv));"
            "ktc_core_str($buf)"
        }

        "Double" -> {
            val buf = tmp()
            preStmts += "ktc_Char ${buf}[32];"
            preStmts += "snprintf($buf, 32, \"%g\", $recv);"
            "ktc_core_str($buf)"
        }

        "Boolean" -> {
            val buf = tmp()
            val sz = 6   // "false\0" = 5+1
            preStmts += "ktc_Char ${buf}[$sz];"
            preStmts += "snprintf($buf, $sz, \"%s\", ($recv) ? \"true\" : \"false\");"
            "ktc_core_str($buf)"
        }

        "Char" -> {
            val buf = tmp()
            preStmts += "ktc_Char ${buf}[8];"
            preStmts += "snprintf($buf, 8, \"%c\", (ktc_Char)($recv));"
            "ktc_core_str($buf)"
        }

        "String" -> recv
        else -> {
            // Default toString: ClassName@hexHashCode (Java-like)
            val base = type.removeSuffix("*").removeSuffix("?")
            val hasHash = classes.containsKey(base) || objects.containsKey(base)
            val hasIface = interfaces.containsKey(base)
            val maxLen = toStringMaxLen(base)  // name@XXXXXXXX
            val sz = if (maxLen != null) maxLen + 2 else 64
            if (hasHash) {
                val cName = typeFlatName(base)
                val buf = tmp()
                val selfExpr = if (parseResolvedTypeName(type) is KtcType.Ptr) recv else "&$recv"
                preStmts += "ktc_Char ${buf}[$sz];"
                preStmts += "snprintf($buf, $sz, \"%s@%x\", \"${ktDisplayName(base)}\", ${cName}_hashCode($selfExpr));"
                "ktc_core_str($buf)"
            } else if (hasIface) {
                val buf = tmp()
                preStmts += "ktc_Char ${buf}[$sz];"
                preStmts += "snprintf($buf, $sz, \"%s@%x\", \"${ktDisplayName(base)}\", $recv.vt->hashCode(${ifaceVtableSelf(base, recv)}));"
                "ktc_core_str($buf)"
            } else {
                "ktc_core_str(\"<$type>\")"
            }
        }
    }
}

// ── toString into a StringBuffer (single pass) ────────────────────

internal fun CCodeGen.genToStringInto(recv: String, type: String, sb: String): String {
    if (classes.containsKey(type) && classes[type]!!.isData) {
        val vTmp = tmp()
        preStmts += "${cTypeStr(type)} $vTmp = ($recv);"
        preStmts += "${typeFlatName(type)}_toString(&$vTmp, &$sb);"
        return "ktc_core_sb_to_string(&$sb)"
    }
    // For primitives and other types, append to the given StrBuf
    when (type) {
        "Byte" -> preStmts += "ktc_core_sb_append_byte(&$sb, $recv);"
        "Short" -> preStmts += "ktc_core_sb_append_short(&$sb, $recv);"
        "Int" -> preStmts += "ktc_core_sb_append_int(&$sb, $recv);"
        "Long" -> preStmts += "ktc_core_sb_append_long(&$sb, $recv);"
        "Float" -> preStmts += "ktc_core_sb_append_float(&$sb, $recv);"
        "Double" -> preStmts += "ktc_core_sb_append_double(&$sb, $recv);"
        "Boolean" -> preStmts += "ktc_core_sb_append_bool(&$sb, $recv);"
        "Char" -> preStmts += "ktc_core_sb_append_char(&$sb, $recv);"
        "String" -> return recv
        "UByte" -> preStmts += "ktc_core_sb_append_ubyte(&$sb, $recv);"
        "UShort" -> preStmts += "ktc_core_sb_append_ushort(&$sb, $recv);"
        "UInt" -> preStmts += "ktc_core_sb_append_uint(&$sb, $recv);"
        "ULong" -> preStmts += "ktc_core_sb_append_ulong(&$sb, $recv);"
        else -> {
            val base = type.removeSuffix("*").removeSuffix("?")
            val hasHash = classes.containsKey(base) || objects.containsKey(base)
            val hasIface = interfaces.containsKey(base)
            if (hasHash) {
                val cName = typeFlatName(base)
                val selfExpr = if (parseResolvedTypeName(type) is KtcType.Ptr) recv else "&$recv"
                val buf = tmp()
                preStmts += "ktc_Char ${buf}[64];"
                preStmts += "snprintf($buf, 64, \"%s@%x\", \"${ktDisplayName(base)}\", ${cName}_hashCode($selfExpr));"
                preStmts += "ktc_core_sb_append_cstr(&$sb, $buf);"
            } else if (hasIface) {
                val buf = tmp()
                preStmts += "ktc_Char ${buf}[64];"
                preStmts += "snprintf($buf, 64, \"%s@%x\", \"${ktDisplayName(base)}\", $recv.vt->hashCode(${ifaceVtableSelf(base, recv)}));"
                preStmts += "ktc_core_sb_append_cstr(&$sb, $buf);"
            } else {
                preStmts += "ktc_core_sb_append_str(&$sb, ktc_core_str(\"<$type>\"));"
            }
        }
    }
    return "ktc_core_sb_to_string(&$sb)"
}

// ── StrBuf append helper ─────────────────────────────────────────

internal fun CCodeGen.genSbAppend(sbRef: String, expr: String, type: String): String = genSbAppendKtc(sbRef, expr, parseResolvedTypeName(type))

/** KtcType-based overload — delegates to the same logic with pattern matching on KtcType. */
internal fun CCodeGen.genSbAppendKtc(sbRef: String, expr: String, type: KtcType): String {
    // Nullable → conditionally append "null" or the value
    if (type is KtcType.Nullable) {
        val base = type.inner
        if (isValueNullableKtc(type)) {
            val inner = genSbAppendKtc(sbRef, "($expr).value", base).removeSuffix(";")
            return "if (($expr).tag == ktc_SOME) { $inner; } else { ktc_core_sb_append_str($sbRef, ktc_core_str(\"null\")); }"
        } else if (base is KtcType.Ptr) {
            val inner = genSbAppendKtc(sbRef, expr, base).removeSuffix(";")
            return "if ($expr != NULL) { $inner; } else { ktc_core_sb_append_str($sbRef, ktc_core_str(\"null\")); }"
        } else {
            val inner = genSbAppendKtc(sbRef, expr, base).removeSuffix(";")
            return "if (${expr}\$has) { $inner; } else { ktc_core_sb_append_str($sbRef, ktc_core_str(\"null\")); }"
        }
    }
    return when (type) {
        is KtcType.Prim -> when (type.kind) {
            KtcType.PrimKind.Byte -> "ktc_core_sb_append_byte($sbRef, $expr);"
            KtcType.PrimKind.Short -> "ktc_core_sb_append_short($sbRef, $expr);"
            KtcType.PrimKind.Int -> "ktc_core_sb_append_int($sbRef, $expr);"
            KtcType.PrimKind.Long -> "ktc_core_sb_append_long($sbRef, $expr);"
            KtcType.PrimKind.Float -> "ktc_core_sb_append_float($sbRef, $expr);"
            KtcType.PrimKind.Double -> "ktc_core_sb_append_double($sbRef, $expr);"
            KtcType.PrimKind.Boolean -> "ktc_core_sb_append_bool($sbRef, $expr);"
            KtcType.PrimKind.Char -> "ktc_core_sb_append_char($sbRef, $expr);"
            KtcType.PrimKind.UByte -> "ktc_core_sb_append_ubyte($sbRef, $expr);"
            KtcType.PrimKind.UShort -> "ktc_core_sb_append_ushort($sbRef, $expr);"
            KtcType.PrimKind.UInt -> "ktc_core_sb_append_uint($sbRef, $expr);"
            KtcType.PrimKind.ULong -> "ktc_core_sb_append_ulong($sbRef, $expr);"
            KtcType.PrimKind.Rune -> "ktc_core_sb_append_int($sbRef, $expr);"
        }
        is KtcType.Str -> "ktc_core_sb_append_str($sbRef, $expr);"
        is KtcType.User -> {
            if (type.kind == KtcType.UserKind.DataClass) {
                val baseName = type.baseName
                if (classes.containsKey(baseName)) {
                    val vTmp = tmp()
                    "{ ${type.toCType()} $vTmp = ($expr); ${typeFlatName(baseName)}_toString(&$vTmp, $sbRef); }"
                } else {
                    "ktc_core_sb_append_str($sbRef, ktc_core_str(\"<${type.toCType()}>\"));"
                }
            } else {
                val baseName = type.baseName
                val typeStr = type.toCType()
                if (classes.containsKey(baseName) || objects.containsKey(baseName)) {
                    val buf = tmp()
                    val cName = typeFlatName(baseName)
                    preStmts += "ktc_Char ${buf}[64];"
                    preStmts += "snprintf($buf, 64, \"%s@%x\", \"${ktDisplayName(baseName)}\", ${cName}_hashCode(&$expr));"
                    "ktc_core_sb_append_cstr($sbRef, $buf);"
                } else if (interfaces.containsKey(baseName)) {
                    val buf = tmp()
                    preStmts += "ktc_Char ${buf}[64];"
                    preStmts += "snprintf($buf, 64, \"%s@%x\", \"${ktDisplayName(baseName)}\", $expr.vt->hashCode(${ifaceVtableSelf(baseName, expr)}));"
                    "ktc_core_sb_append_cstr($sbRef, $buf);"
                } else {
                    "ktc_core_sb_append_str($sbRef, ktc_core_str(\"<$typeStr>\"));"
                }
            }
        }
        is KtcType.Ptr -> {
            val base = type.inner
            val baseStr = base.toInternalStr
            // Data class pointer → use proper toString
            if (base is KtcType.User && base.kind == KtcType.UserKind.DataClass && classes.containsKey(baseStr)) {
                val vTmp = tmp()
                "{ ${base.toCType()} $vTmp = (*$expr); ${typeFlatName(baseStr)}_toString(&$vTmp, $sbRef); }"
            } else if (classes.containsKey(baseStr) || objects.containsKey(baseStr)) {
                val buf = tmp()
                val cName = typeFlatName(baseStr)
                preStmts += "ktc_Char ${buf}[64];"
                preStmts += "snprintf($buf, 64, \"%s@%x\", \"${ktDisplayName(baseStr)}\", ${cName}_hashCode($expr));"
                "ktc_core_sb_append_cstr($sbRef, $buf);"
            } else {
                "ktc_core_sb_append_str($sbRef, ktc_core_str(\"<${baseStr}>\"));"
            }
        }
        else -> "ktc_core_sb_append_str($sbRef, ktc_core_str(\"<${type.toCType()}>\"));"
    }
}

// ── arrayOf helpers ──────────────────────────────────────────────

internal fun CCodeGen.genArrayOfExpr(
    name: String,
    args: List<Arg>,
    inTypeArg: TypeRef? = null
): String { // inTypeArg — explicit type argument from the call site, e.g. arrayOf<Int?>(...)
    // arrayOf<T?>(v1, null, v2) → nullable element array; each element wrapped in Optional struct
    if (name == "arrayOf" && inTypeArg?.nullable == true) {
        val vElemName = typeSubst[inTypeArg.name] ?: inTypeArg.name
        val vOptCType = optCTypeName("${vElemName}?")
        val vVals = args.joinToString(", ") { vArg ->
            if (vArg.expr is NullLit) "($vOptCType){ktc_NONE}"
            else "($vOptCType){ktc_SOME, ${genExpr(vArg.expr)}}"
        }
        val vTmp = tmp()
        preStmts += "$vOptCType ${vTmp}[] = {$vVals};"
        preStmts += "const ktc_Int ${vTmp}\$len = ${args.size};"
        return vTmp
    }
    val elemType = when (name) {
        "byteArrayOf" -> "ktc_Byte"; "shortArrayOf" -> "ktc_Short"
        "intArrayOf" -> "ktc_Int"; "longArrayOf" -> "ktc_Long"
        "floatArrayOf" -> "ktc_Float"; "doubleArrayOf" -> "ktc_Double"
        "booleanArrayOf" -> "ktc_Bool"; "charArrayOf" -> "ktc_Char"
        "ubyteArrayOf" -> "ktc_UByte"; "ushortArrayOf" -> "ktc_UShort"
        "uintArrayOf" -> "ktc_UInt"; "ulongArrayOf" -> "ktc_ULong"
        "arrayOf" -> {
            if (inTypeArg != null) cTypeStr(typeSubst[inTypeArg.name] ?: inTypeArg.name)
            else {
                val elemKt = if (args.isNotEmpty()) inferExprType(args[0].expr) ?: "Int" else "Int"; cTypeStr(elemKt)
            }
        }

        else -> "ktc_Int"
    }
    val vals = args.joinToString(", ") { genExpr(it.expr) }
    val n = args.size
    val t = tmp()
    preStmts += "$elemType ${t}[] = {$vals};"
    preStmts += "const ktc_Int ${t}\$len = $n;"
    return t
}

internal fun CCodeGen.genNewArray(elemCType: String, args: List<Arg>): String {
    val size = if (args.isNotEmpty()) genExpr(args[0].expr) else "0"
    val t = tmp()
    preStmts += "$elemCType* $t = ($elemCType*)ktc_core_alloca(sizeof($elemCType) * (size_t)($size));"
    preStmts += "const ktc_Int ${t}\$len = $size;"
    return t
}

/** Array<T>(size) { init } — stack-allocated with inline lambda init loop. */
internal fun CCodeGen.genNewArrayWithLambda(elemCType: String, args: List<Arg>): String {
    val size = genExpr(args[0].expr)
    val lambda = args[1].expr as LambdaExpr
    val itName = lambda.params.firstOrNull() ?: "it"
    val t = tmp()
    preStmts += "$elemCType* $t = ($elemCType*)ktc_core_alloca(sizeof($elemCType) * (size_t)($size));"
    preStmts += "const ktc_Int ${t}\$len = $size;"
    preStmts += "for (ktc_Int $itName = 0; $itName < $size; $itName++) {"
    // Lambda body: must be a single expression producing the element value
    val bodyExpr = when {
        lambda.body.size == 1 && lambda.body[0] is ExprStmt -> (lambda.body[0] as ExprStmt).expr
        else -> {
            codegenError("Array init lambda must be a single expression")
        }
    }
    preStmts += "    $t[$itName] = ${genExpr(bodyExpr)};"
    preStmts += "}"
    return t
}

internal fun CCodeGen.genHeapArrayOfExpr(args: List<Arg>, inTypeArg: TypeRef? = null): String {
    val elemType = if (inTypeArg != null) cTypeStr(typeSubst[inTypeArg.name] ?: inTypeArg.name)
    else if (args.isNotEmpty()) {
        val inferred = inferExprType(args[0].expr) ?: "Int"
        val inferredKtc = inferExprTypeKtc(args[0].expr) ?: KtcType.Prim(KtcType.PrimKind.Int)
        cTypeStr(inferred)
    } else "ktc_Int"
    val n = args.size
    val t = tmp()
    preStmts += "$elemType* $t = ($elemType*)${tMalloc("sizeof($elemType) * $n")};"
    val vals = args.mapIndexed { i, arg -> "$t[$i] = ${genExpr(arg.expr)};" }.joinToString(" ")
    if (vals.isNotEmpty()) preStmts += vals
    preStmts += "const ktc_Int ${t}\$len = $n;"
    return t
}


// ── fill default arguments ───────────────────────────────────────

/*
strict=true: called after overload resolution is already committed to this specific FunDecl.
strict=false (default): called speculatively (e.g. funSigs pre-check before overload resolution).
Only strict mode emits the missing-required-args error to avoid false positives during resolution.
*/
internal fun CCodeGen.fillDefaults(
    args: List<Arg>,
    params: List<Param>,
    defaults: Map<String, Expr?>,
    funName: String = "<unknown>",
    strict: Boolean = false
): List<Arg> {
    val hasVararg = params.any { it.isVararg }
    val nonVarargCount = params.count { !it.isVararg }

    // Check: too many positional arguments (only for non-vararg functions)
    if (!hasVararg && args.none { it.name != null } && args.size > nonVarargCount) {
        codegenError("Too many arguments for '$funName': expected $nonVarargCount, got ${args.size}")
    }

    if (args.size >= params.size) return args

    // Check: required arguments missing (no default, not vararg)
    // Only in strict mode to avoid false positives from the funSigs pre-resolution path.
    if (strict) {
        val requiredMissing = params
            .drop(args.size)
            .filter { !it.isVararg && defaults[it.name] == null }
        if (requiredMissing.isNotEmpty()) {
            codegenError("Missing required argument(s) for '$funName': ${requiredMissing.joinToString(", ") { it.name }}")
        }
    }

    // Named args: reorder
    val hasNamed = args.any { it.name != null }
    if (hasNamed) {
        val result = params.mapNotNull { p ->
            if (p.isVararg) return@mapNotNull null  // vararg handled by expandCallArgs
            val explicit = args.find { it.name == p.name }
            explicit ?: Arg(p.name, defaults[p.name] ?: IntLit(0))
        }
        return result
    }
    // Positional: fill missing from defaults (skip vararg params)
    val result = args.toMutableList()
    for (i in args.size until params.size) {
        if (params[i].isVararg) continue  // vararg handled by expandCallArgs
        val def = defaults[params[i].name]
        result += Arg(null, def ?: IntLit(0))
    }
    return result
}

// ── l-value generation (for assignments) ─────────────────────────

internal fun CCodeGen.genLValue(e: Expr, method: Boolean): String {
    return when (e) {
        is NameExpr -> {
            if (method && currentClass != null && classes[currentClass]?.props?.any { it.first == e.name } == true) {
                val ci = classes[currentClass]!!
                val fieldName = if (e.name in ci.privateProps) "PRIV_${e.name}" else e.name
                if (selfIsPointer) "\$self->${fieldName}" else "\$self.${fieldName}"
            } else if (currentObject.let { vCO -> vCO != null && objects[vCO]?.props?.any { it.first == e.name } == true })
                "${typeFlatName(currentObject!!)}.${e.name}"
            else e.name
        }

        is DotExpr -> {
            if (e.obj is NameExpr && objects.containsKey(e.obj.name))
                "${typeFlatName(e.obj.name)}.${e.name}"
            else if (e.obj is NameExpr && classCompanions.containsKey(e.obj.name)) {
                val vCompanionName = classCompanions[e.obj.name]!!
                "${typeFlatName(vCompanionName)}.${e.name}"
            } else {
                val recvKtc = inferExprTypeKtc(e.obj)
                val recvKtcCore = (recvKtc as? KtcType.Nullable)?.inner ?: recvKtc
                val op = if (recvKtcCore is KtcType.Ptr) "->" else "."
                "${genExpr(e.obj)}$op${e.name}"
            }
        }

        is IndexExpr -> {
            val objKtc = inferExprTypeKtc(e.obj)
            val objKtcCore = (objKtc as? KtcType.Nullable)?.inner ?: objKtc
            if (objKtcCore != null && (objKtcCore is KtcType.Ptr || objKtcCore.isArrayLike)) {
                "${genExpr(e.obj)}[${genExpr(e.index)}]"
            } else {
                "${genExpr(e.obj)}.ptr[${genExpr(e.index)}]"
            }
        }

        else -> genExpr(e)
    }
}

// ── String helpers for transpile-time folding ──────────────────────

private fun trimIndentImpl(raw: String): String {
    val lines = raw.split("\n").toMutableList()
    // Remove leading blank line if present
    if (lines.isNotEmpty() && lines[0].isBlank()) lines.removeAt(0)
    // Remove trailing blank line if present
    if (lines.isNotEmpty() && lines.last().isBlank()) lines.removeAt(lines.lastIndex)
    // Find minimum indent (only consider non-blank lines)
    val minIndent = lines.filter { it.isNotBlank() }.minOfOrNull { it.indentCount() } ?: 0
    // Remove min indent from each line
    return lines.joinToString("\n") { if (it.length >= minIndent) it.substring(minIndent) else it }
}

private fun trimMarginImpl(raw: String, marginPrefix: String): String {
    val lines = raw.split("\n").toMutableList()
    // Remove leading blank line if present
    if (lines.isNotEmpty() && lines[0].isBlank()) lines.removeAt(0)
    // Remove trailing blank line if present
    if (lines.isNotEmpty() && lines.last().isBlank()) lines.removeAt(lines.lastIndex)
    return lines.joinToString("\n") { line ->
        val idx = line.indexOf(marginPrefix)
        if (idx >= 0 && line.substring(0, idx).all { it == ' ' || it == '\t' }) {
            line.substring(idx + marginPrefix.length)
        } else {
            line
        }
    }
}

private fun String.indentCount(): Int {
    var count = 0
    for (c in this) {
        if (c == ' ') count++ else if (c == '\t') count++ else break
    }
    return count
}
