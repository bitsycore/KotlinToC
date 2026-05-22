package com.bitsycore.ktc.codegen.expr

import com.bitsycore.ktc.ast.*
import com.bitsycore.ktc.codegen.*
import com.bitsycore.ktc.codegen.emit.collectAllIfaceMethods
import com.bitsycore.ktc.types.KtcType

// ── Call expression codegen ───────────────────────────────────────
// Routing hub: delegates built-ins to ExprCallBuiltins.kt,
// allocWith/ctor dispatch to ExprCallAlloc.kt.

internal fun CCodeGen.genCall(e: CallExpr): String {
    // ── Method call: DotExpr receiver ────────────────────────────
    if (e.callee is DotExpr) {
        // Inline extension function call in value position
        val vInlineExt = inlineExtFunDecls[e.callee.name]
        if (vInlineExt != null) {
            val vRecvExpr = genExpr(e.callee.obj)
            val vRecvKtType = inferExprType(e.callee.obj)?.removeSuffix("?")
            val vRetType = vInlineExt.returnType
            val vSubst = if (vInlineExt.typeParams.isNotEmpty()) inferInlineFunSubst(vInlineExt, vRecvKtType, e.args.map { inferExprType(it.expr) }) else null
            return withTypeSubst(vSubst) {
                if (vRetType == null) {
                    emitInlineCall(vInlineExt, e.args, currentInd, false, receiverExpr = vRecvExpr, receiverType = vRecvKtType)
                    ""
                } else {
                    val vResultName = "\$ir${inlineCounter++}"
                    impl.appendLine("$currentInd${cType(vRetType)} $vResultName;")
                    emitInlineCall(vInlineExt, e.args, currentInd, false, receiverExpr = vRecvExpr, receiverType = vRecvKtType, resultVar = vResultName)
                    vResultName
                }
            }
        }

        // ClassName.allocWith(allocator, ...) — allocator-based heap construction
        if (e.callee.name == "allocWith" && e.callee.obj is NameExpr && e.args.isNotEmpty()) {
            val vResult = genAllocWithCallOrNull(e)
            if (vResult != null) return vResult
        }

        // c.printf(...) → passthrough to raw C
        if (e.callee.obj is NameExpr && e.callee.obj.name == "c" && lookupVar(e.callee.obj.name) == null) {
            val vCFnName = e.callee.name
            val vFnName = when {
                memTrack && vCFnName == "malloc" -> "ktc_core_malloc"
                memTrack && vCFnName == "free" -> "ktc_core_free"
                memTrack && vCFnName == "realloc" -> "ktc_core_realloc"
                else -> vCFnName
            }
            val vArgStr = e.args.joinToString(", ") { genCArg(it.expr) }
            val vExtra = if (memTrack && vCFnName in setOf("malloc", "free", "realloc")) ", ${ktSrc()}" else ""
            return "$vFnName($vArgStr$vExtra)"
        }

        // Nested class constructor: Outer.Inner(...) or A.B.C(...)
        fun flattenDotCallee(inCallee: Expr): String? {
            if (inCallee is NameExpr) return inCallee.name
            if (inCallee is DotExpr && inCallee.obj is NameExpr) return "${inCallee.obj.name}$${inCallee.name}"
            if (inCallee is DotExpr) {
                val vLeft = flattenDotCallee(inCallee.obj)
                if (vLeft != null) return "$vLeft$${inCallee.name}"
            }
            return null
        }

        val vFlatCallee = flattenDotCallee(e.callee)
        if (vFlatCallee != null && (classes.containsKey(vFlatCallee) || genericClassDecls.containsKey(vFlatCallee))) {
            return genCall(CallExpr(NameExpr(vFlatCallee), e.args, e.typeArgs))
        }

        // Reject non-safe call on nullable receiver
        val vRecvKtc = inferExprTypeKtc(e.callee.obj)
        if (vRecvKtc is KtcType.Nullable) {
            val vInnerKtc = vRecvKtc.inner
            val vIsIndirectArray = vInnerKtc is KtcType.Ptr && vInnerKtc.inner is KtcType.Arr
            if (!hasNullableReceiverExt(
                    vInnerKtc.toInternalStr,
                    e.callee.name
                ) && !vIsIndirectArray && vInnerKtc !is KtcType.Arr
            ) {
                if (e.callee.name == "ptr") return genMethodCall(e.callee, e.args)
                val vRecvSrc = (e.callee.obj as? NameExpr)?.name ?: e.callee.obj.toString()
                val vRecvType = vRecvKtc.toInternalStr
                codegenError("Only safe (?.) calls are allowed on a nullable receiver of type '$vRecvType': $vRecvSrc.${e.callee.name}()")
            }
        }
        return genMethodCall(e.callee, e.args)
    }

    if (e.callee is SafeDotExpr) return genSafeMethodCall(e.callee, e.args)

    val vName = (e.callee as? NameExpr)?.name
        ?: return "${genExpr(e.callee)}(${e.args.joinToString(", ") { genExpr(it.expr) }})"
    val vArgs = e.args

    // ── Inline function call in value position ────────────────────
    val vInlineCandidates = inlineFunDecls[vName]
    val vInlineDecl = when {
        vInlineCandidates == null -> null
        vInlineCandidates.size == 1 -> vInlineCandidates[0]
        else -> {
            val vExact = vInlineCandidates.find { it.params.size == vArgs.size }
            vExact ?: vInlineCandidates.minByOrNull { kotlin.math.abs(it.params.size - vArgs.size) }
        }
    }
    if (vInlineDecl != null) {
        val vSubst = if (vInlineDecl.typeParams.isNotEmpty()) {
            val s = mutableMapOf<String, String>()
            for ((vI, vParam) in vInlineDecl.params.withIndex()) {
                if (vI >= vArgs.size) break
                val vArgType = inferExprType(vArgs[vI].expr)?.removeSuffix("?") ?: continue
                matchTypeParam(vParam.type, vArgType, vInlineDecl.typeParams.toSet(), s)
            }
            s.ifEmpty { null }
        } else null
        val vRetType = vInlineDecl.returnType
        return withTypeSubst(vSubst) {
            if (vRetType == null) {
                emitInlineCall(vInlineDecl, e.args, currentInd, false)
                ""
            } else {
                val vResultName = "\$ir${inlineCounter++}"
                impl.appendLine("$currentInd${cType(vRetType)} $vResultName;")
                emitInlineCall(vInlineDecl, e.args, currentInd, false, resultVar = vResultName)
                vResultName
            }
        }
    }

    // ── Active lambda call ────────────────────────────────────────
    val vActiveLambda = activeLambdas[vName]
    if (vActiveLambda != null) {
        val vSavedSubst = lambdaParamSubst.toMap()
        val vSavedTypes = lambdaParamTypes.toMap()
        vActiveLambda.expr.params.forEachIndexed { vI, vPName ->
            val vArg = e.args.getOrNull(vI)
            if (vArg != null) {
                lambdaParamSubst[vPName] = genExpr(vArg.expr)
                val vT = (if (vArg.expr is ThisExpr) lambdaParamTypes["\$this"] else null)
                    ?: inferExprType(vArg.expr)
                    ?: vActiveLambda.paramTypes.getOrNull(vI)?.toInternalStr ?: ""
                if (vT.isNotEmpty()) lambdaParamTypes[vPName] = vT
            }
        }
        val vBody = vActiveLambda.expr.body
        val vAllButLast = if (vBody.size > 1) vBody.dropLast(1) else emptyList()
        for (vStmt in vAllButLast) emitStmt(vStmt, currentInd)
        val vResult = when (val vLast = vBody.lastOrNull()) {
            is ExprStmt -> genExpr(vLast.expr)
            is ReturnStmt -> if (vLast.value != null) genExpr(vLast.value) else ""
            null -> ""
            else -> {
                emitStmt(vLast, currentInd); ""
            }
        }
        lambdaParamSubst.clear(); lambdaParamSubst.putAll(vSavedSubst)
        lambdaParamTypes.clear(); lambdaParamTypes.putAll(vSavedTypes)
        return vResult
    }

    // ── Built-in / intrinsic names ────────────────────────────────
    genBuiltinCallOrNull(vName, vArgs, e)?.let { return it }

    // ── Function pointer call ─────────────────────────────────────
    val vVarType = lookupVar(vName)
    if (vVarType != null && isFuncType(vVarType)) {
        return "$vName(${vArgs.joinToString(", ") { genExpr(it.expr) }})"
    }

    // ── Constructor dispatch ──────────────────────────────────────
    genCtorCallOrNull(vName, vArgs, e)?.let { return it }

    // ── Generic function call ─────────────────────────────────────
    val vGenFun = genericFunDecls.find { it.name == vName }
    if (vGenFun != null) {
        val vTypeArgNames = if (e.typeArgs.isNotEmpty()) {
            e.typeArgs.map { it.name }
        } else {
            val vSubst = mutableMapOf<String, String>()
            for ((vI, vParam) in vGenFun.params.withIndex()) {
                if (vI >= vArgs.size) break
                val vArgType = inferExprType(vArgs[vI].expr) ?: continue
                matchTypeParam(vParam.type, vArgType, vGenFun.typeParams.toSet(), vSubst)
            }
            if (vSubst.size == vGenFun.typeParams.size) vGenFun.typeParams.map { vSubst[it]!! }
            else genericFunInstantiations[vName]?.firstOrNull()?.toList()
        }
        if (vTypeArgNames != null) {
            val vMangled   = "${vName}_${vTypeArgNames.joinToString("_")}"
            genericFunInstantiations.getOrPut(vName) { mutableSetOf() }.add(vTypeArgNames)
            val vNewSubst = vGenFun.typeParams.zip(vTypeArgNames).toMap()
            val (vExpandedArgs, vSized) = withTypeSubst(vNewSubst) {
                val ea = prepareArgs(vArgs, vGenFun)
                Pair(ea, tryWrapSizedReturn("${funCName(vMangled)}($ea)", vGenFun.returnType))
            }
            if (vSized != null) return vSized
            return "${funCName(vMangled)}($vExpandedArgs)"
        }
    }

    // ── Regular function call — fill defaults and dispatch ────────
    val vSig = funSigs[vName]
    val vFilledArgs =
        if (vSig != null) fillDefaults(vArgs, vSig.params, vSig.params.associate { it.name to it.default }) else vArgs
    val vExpandedArgs = expandCallArgs(vFilledArgs, vSig?.params)

    // Inside a class method or extension: bare call resolves to $self.method()
    if (currentClass != null) {
        val vCi = classes[currentClass]
        val vMethodDecl = vCi?.let { findOverload(vName, vArgs, it.methods) }
        if (vMethodDecl != null) {
            val vFnName    = resolvedFnName(vMethodDecl, vCi.methods)
            val vExpanded2 = prepareArgs(vArgs, vMethodDecl, currentClass!!)
            val vSelfArg   = if (selfIsPointer) "\$self" else "&\$self"
            val vAllArgs   = if (vExpanded2.isEmpty()) vSelfArg else "$vSelfArg, $vExpanded2"
            val vSizedM =
                tryWrapSizedReturn("${typeFlatName(currentClass!!)}_$vFnName($vAllArgs)", vMethodDecl.returnType)
            if (vSizedM != null) return vSizedM
            return "${typeFlatName(currentClass!!)}_$vFnName($vAllArgs)"
        }
        // Inside a class nested in an object: try parent object's methods
        val vParentObj = currentClass?.substringBefore('$')
        if (vParentObj != null && objects.containsKey(vParentObj)) {
            val vParentMethodDecl = objects[vParentObj]?.let { findOverload(vName, vArgs, it.methods) }
            if (vParentMethodDecl != null) {
                val vFnName    = resolvedFnName(vParentMethodDecl, objects[vParentObj]!!.methods)
                val vExpanded2 = prepareArgs(vArgs, vParentMethodDecl, vParentObj)
                val vSizedP = tryWrapSizedReturn(
                    "${typeFlatName(vParentObj)}_$vFnName($vExpanded2)",
                    vParentMethodDecl.returnType
                )
                if (vSizedP != null) return vSizedP
                return "${typeFlatName(vParentObj)}_$vFnName($vExpanded2)"
            }
        }
    }

    // Inside an extension on an interface: bare call → vtable dispatch on $self
    if (currentExtRecvType != null && interfaces.containsKey(currentExtRecvType)) {
        val vExtIfaceInfo = interfaces[currentExtRecvType]!!
        val vIfaceMethod = vExtIfaceInfo.methods.find { it.name == vName }
            ?: collectAllIfaceMethods(vExtIfaceInfo).find { it.name == vName }
        if (vIfaceMethod != null) {
            val vSelfVtArg = ifaceVtableSelf(vExtIfaceInfo.name, "\$self")
            val vAllArgs = if (vExpandedArgs.isEmpty()) vSelfVtArg else "$vSelfVtArg, $vExpandedArgs"
            return "\$self.vt->$vName($vAllArgs)"
        }
    }

    // Inside an object method: bare call resolves to object's method
    if (currentObject != null) {
        val vOi = objects[currentObject]
        val vMethodDecl = vOi?.let { findOverload(vName, vArgs, it.methods) }
        if (vMethodDecl != null) {
            val vFnName    = resolvedFnName(vMethodDecl, vOi.methods)
            val vExpanded2 = prepareArgs(vArgs, vMethodDecl, currentObject!!)
            val vSizedO =
                tryWrapSizedReturn("${typeFlatName(currentObject!!)}_$vFnName($vExpanded2)", vMethodDecl.returnType)
            if (vSizedO != null) return vSizedO
            return "${typeFlatName(currentObject!!)}_$vFnName($vExpanded2)"
        }
    }

    // Object method with name registered in funSigs (private/internal call)
    if (currentObject != null && funSigs.containsKey(vName)) {
        return "${typeFlatName(currentObject!!)}_${vName}($vExpandedArgs)"
    }

    // ── Top-level function overload resolution ────────────────────
    val vTopFuns = file.decls.filterIsInstance<FunDecl>()
    val vIsOverloaded = vTopFuns.count { it.name == vName } > 1
    if (!vIsOverloaded) {
        val vSizedTop = tryWrapSizedReturn("${funCName(vName)}($vExpandedArgs)", vSig?.returnType)
        if (vSizedTop != null) return vSizedTop
    }
    val vTopOvr = findOverload(vName, vArgs, vTopFuns)
    if (vTopOvr != null && vIsOverloaded) {
        val vFnName    = resolvedFnName(vTopOvr, vTopFuns)
        val vExpanded2 = prepareArgs(vArgs, vTopOvr)
        val vSizedOvr  = tryWrapSizedReturn("${funCName(vFnName)}($vExpanded2)", vTopOvr.returnType)
        if (vSizedOvr != null) return vSizedOvr
        return "${funCName(vFnName)}($vExpanded2)"
    }

    return "${funCName(vName)}($vExpandedArgs)"
}
