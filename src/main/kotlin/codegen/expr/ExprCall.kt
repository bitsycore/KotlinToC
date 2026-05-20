package com.bitsycore.ktc.codegen.expr

import com.bitsycore.ktc.ast.*
import com.bitsycore.ktc.codegen.*
import com.bitsycore.ktc.codegen.emit.collectAllIfaceMethods
import com.bitsycore.ktc.types.KtcType

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
                val isTrampoline = allocArgCore is KtcType.Ptr && allocArgCore.inner is KtcType.User && allocArgCore.inner.kind == KtcType.UserKind.Interface
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
                preStmts += "$elemC* ${t}_ptr = ($elemC*)((ktc_std_Allocator_vt*)$ifExpr.vt)->allocMem($ifExpr.obj, sizeof($elemC) * (size_t)($sizeExpr), ${ktSrcStr()});"
                if (className == "Array") preStmts += "const ktc_Int ${t}_ptr\$len = $sizeExpr;"
                return "${t}_ptr"
            }
            if (classes.containsKey(className) && !classes[className]!!.isGeneric) {
                val cName = typeFlatName(className)
                val allocExpr = genExpr(e.args[0].expr)
                val ctorArgs = e.args.drop(1).joinToString(", ") { genExpr(it.expr) }
                val t = tmp()
                val allocObjName = (e.args[0].expr as? NameExpr)?.name
                // Resolve allocator class name from type inference — allocObjName is a variable name,
                // not a class name, so classInterfaces must be keyed by the inferred type.
                val allocArgKtc2 = inferExprTypeKtc(e.args[0].expr)
                val allocArgCore2 = (allocArgKtc2 as? KtcType.Nullable)?.inner ?: allocArgKtc2
                val allocArgClassName = (allocArgCore2 as? KtcType.User)?.baseName
                val isAllocObj = allocObjName != null && objects.containsKey(allocObjName) && classInterfaces[allocObjName]?.contains("Allocator") == true
                val isAllocClass = allocArgClassName != null && classes.containsKey(allocArgClassName) && classInterfaces[allocArgClassName]?.contains("Allocator") == true
                // @Ptr Allocator (ktc_IfacePtr): .obj points to original — mutations propagate correctly.
                val isAllocTrampoline = run { val i = (allocArgCore2 as? KtcType.Ptr)?.inner; i is KtcType.User && i.kind == KtcType.UserKind.Interface }
                val ifaceCreated: Boolean
                val ifExpr: String
                when {
                    isAllocObj -> {
                        val cConcrete = typeFlatName(allocObjName); val typeId = getTypeId(allocObjName)
                        preStmts += "ktc_IfacePtr $t = {{$typeId}, (const void*)&${cConcrete}_Allocator_vt, (void*)&$allocExpr};"
                        ifaceCreated = true; ifExpr = t
                    }
                    isAllocClass -> {
                        val cConcrete = typeFlatName(allocArgClassName); val typeId = getTypeId(allocArgClassName)
                        preStmts += "ktc_IfacePtr $t = {{$typeId}, (const void*)&${cConcrete}_Allocator_vt, (void*)&$allocExpr};"
                        ifaceCreated = true; ifExpr = t
                    }
                    else -> { ifaceCreated = false; ifExpr = allocExpr }
                }
                val tPtr = tmp()
                if (ifaceCreated || isAllocTrampoline) {
                    preStmts += "$cName* ${tPtr}_ptr = ($cName*)((ktc_std_Allocator_vt*)$ifExpr.vt)->allocMem($ifExpr.obj, sizeof($cName), ${ktSrcStr()});"
                } else {
                    preStmts += "$cName* ${tPtr}_ptr = ($cName*)$ifExpr.vt->allocMem((void*)&$ifExpr.data, sizeof($cName), ${ktSrcStr()});"
                }
                preStmts += "if (${tPtr}_ptr) *${tPtr}_ptr = ${cName}_primaryConstructor($ctorArgs);"
                return "${tPtr}_ptr"
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
                        val allocArgKtc3 = inferExprTypeKtc(e.args[0].expr)
                        val allocArgCore3 = (allocArgKtc3 as? KtcType.Nullable)?.inner ?: allocArgKtc3
                        val allocArgClassName3 = (allocArgCore3 as? KtcType.User)?.baseName
                        val isAllocObj = allocObjName != null && objects.containsKey(allocObjName) && classInterfaces[allocObjName]?.contains("Allocator") == true
                        val isAllocClass3 = allocArgClassName3 != null && classes.containsKey(allocArgClassName3) && classInterfaces[allocArgClassName3]?.contains("Allocator") == true
                        val isAllocTrampoline3 = run { val i = (allocArgCore3 as? KtcType.Ptr)?.inner; i is KtcType.User && i.kind == KtcType.UserKind.Interface }
                        val ifaceCreated3: Boolean
                        val ifExpr3: String
                        when {
                            isAllocObj -> {
                                val cConcrete = typeFlatName(allocObjName); val typeId = getTypeId(allocObjName)
                                preStmts += "ktc_IfacePtr $t = {{$typeId}, (const void*)&${cConcrete}_Allocator_vt, (void*)&$allocExpr};"
                                ifaceCreated3 = true; ifExpr3 = t
                            }
                            isAllocClass3 -> {
                                val cConcrete = typeFlatName(allocArgClassName3); val typeId = getTypeId(allocArgClassName3)
                                preStmts += "ktc_IfacePtr $t = {{$typeId}, (const void*)&${cConcrete}_Allocator_vt, (void*)&$allocExpr};"
                                ifaceCreated3 = true; ifExpr3 = t
                            }
                            else -> { ifaceCreated3 = false; ifExpr3 = allocExpr }
                        }
                        val tPtr3 = tmp()
                        if (ifaceCreated3 || isAllocTrampoline3) {
                            preStmts += "$cName* ${tPtr3}_ptr = ($cName*)((ktc_std_Allocator_vt*)$ifExpr3.vt)->allocMem($ifExpr3.obj, sizeof($cName), ${ktSrcStr()});"
                        } else {
                            preStmts += "$cName* ${tPtr3}_ptr = ($cName*)$ifExpr3.vt->allocMem((void*)&$ifExpr3.data, sizeof($cName), ${ktSrcStr()});"
                        }
                        preStmts += "if (${tPtr3}_ptr) *${tPtr3}_ptr = ${cName}_primaryConstructor($ctorArgs);"
                        return "${tPtr3}_ptr"
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

    // StringBuffer(ptr, len, cap) — explicit capacity variant (e.g. arena-backed buffer)
    if (name == "StringBuffer" && args.size == 3
        && !classes.containsKey("StringBuffer") && !genericClassDecls.containsKey("StringBuffer")
    ) {
        val vPtrExpr = genExpr(args[0].expr)  // char* pointer
        val vLenExpr = genExpr(args[1].expr)  // initial length
        val vCapExpr = genExpr(args[2].expr)  // capacity
        return "(ktc_StrBuf){$vPtrExpr, $vLenExpr, $vCapExpr}"
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
        // allClassDecls covers stdlib classes too (file.decls only has the current file's decls)
        val declClass = allClassDecls[resolvedName]
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
            // find matching ctor param default — allClassDecls covers stdlib classes too
            val vCp = declClass?.ctorParams?.find { p -> p.name == it.name }       // matching ctor param
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
            if (genFun.returnType != null && genFun.returnType.isSizedArray()) {
                val vRetKtcSized  = resolveTypeName(genFun.returnType)
                val retType       = vRetKtcSized.toInternalStr
                val elemCType     = arrayElementCTypeKtc(vRetKtcSized)
                val size          = genFun.returnType.getSizeAnnotation()!!
                val structType    = sizedArrayCTypeRef(elemCType, size)
                val filledArgs    = fillDefaults(args, genFun.params, genFun.params.associate { it.name to it.default }, genFun.name, strict = true)
                val expandedArgs2 = expandCallArgs(filledArgs, genFun.params)
                val tStruct = tmp(); val tPtr = tmp()
                preStmts += "$structType $tStruct = ${funCName(mangledName)}($expandedArgs2);"
                preStmts += "$elemCType* $tPtr = $tStruct.arr;"
                preStmts += "const ktc_Int ${tPtr}\$len = $size;"
                typeSubst = prevSubst
                defineVar(tPtr, retType)
                return tPtr
            }
            if (genFun.returnType != null && genFun.returnType.isSizedString()) {
                val size          = genFun.returnType.getSizeAnnotation()!!
                val structType    = sizedStringCTypeRef(size)
                val filledArgs    = fillDefaults(args, genFun.params, genFun.params.associate { it.name to it.default }, genFun.name, strict = true)
                val expandedArgs2 = expandCallArgs(filledArgs, genFun.params)
                val tStruct = tmp(); val tStr = tmp()
                preStmts += "$structType $tStruct = ${funCName(mangledName)}($expandedArgs2);"
                preStmts += "ktc_String $tStr = {$tStruct.buf, $tStruct.len};"
                typeSubst = prevSubst
                defineVar(tStr, "String")
                return tStr
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
            val filledArgs = fillDefaults(args, methodDecl.params, effectiveDefaults(methodDecl, currentClass), methodDecl.name, strict = true)
            val expandedArgs2 = expandCallArgs(filledArgs, methodDecl.params)
            val selfArg = if (selfIsPointer) "\$self" else "&\$self"
            val allArgs = if (expandedArgs2.isEmpty()) selfArg else "$selfArg, $expandedArgs2"
            if (methodDecl.returnType != null && methodDecl.returnType.isSizedArray()) {
                val vRetKtcSz2 = resolveTypeName(methodDecl.returnType)
                val retType    = vRetKtcSz2.toInternalStr
                val elemCType  = arrayElementCTypeKtc(vRetKtcSz2)
                val size       = methodDecl.returnType.getSizeAnnotation()!!
                val structType = sizedArrayCTypeRef(elemCType, size)
                val tStruct = tmp(); val tPtr = tmp()
                preStmts += "$structType $tStruct = ${typeFlatName(currentClass!!)}_$fnName($allArgs);"
                preStmts += "$elemCType* $tPtr = $tStruct.arr;"
                preStmts += "const ktc_Int ${tPtr}\$len = $size;"
                defineVar(tPtr, retType)
                return tPtr
            }
            if (methodDecl.returnType != null && methodDecl.returnType.isSizedString()) {
                val size       = methodDecl.returnType.getSizeAnnotation()!!
                val structType = sizedStringCTypeRef(size)
                val tStruct = tmp(); val tStr = tmp()
                preStmts += "$structType $tStruct = ${typeFlatName(currentClass!!)}_$fnName($allArgs);"
                preStmts += "ktc_String $tStr = {$tStruct.buf, $tStruct.len};"
                defineVar(tStr, "String")
                return tStr
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
                val filledArgs = fillDefaults(args, methodDecl.params, effectiveDefaults(methodDecl, parentObj), methodDecl.name, strict = true)
                val expandedArgs2 = expandCallArgs(filledArgs, methodDecl.params)
                if (methodDecl.returnType != null && methodDecl.returnType.isSizedArray()) {
                    val vRetKtcSize = resolveTypeName(methodDecl.returnType)
                    val retType     = vRetKtcSize.toInternalStr
                    val elemCType   = arrayElementCTypeKtc(vRetKtcSize)
                    val size        = methodDecl.returnType.getSizeAnnotation()!!
                    val structType  = sizedArrayCTypeRef(elemCType, size)
                    val tStruct = tmp(); val tPtr = tmp()
                    preStmts += "$structType $tStruct = ${typeFlatName(parentObj)}_$fnName($expandedArgs2);"
                    preStmts += "$elemCType* $tPtr = $tStruct.arr;"
                    preStmts += "const ktc_Int ${tPtr}\$len = $size;"
                    defineVar(tPtr, retType)
                    return tPtr
                }
                if (methodDecl.returnType != null && methodDecl.returnType.isSizedString()) {
                    val size       = methodDecl.returnType.getSizeAnnotation()!!
                    val structType = sizedStringCTypeRef(size)
                    val tStruct = tmp(); val tStr = tmp()
                    preStmts += "$structType $tStruct = ${typeFlatName(parentObj)}_$fnName($expandedArgs2);"
                    preStmts += "ktc_String $tStr = {$tStruct.buf, $tStruct.len};"
                    defineVar(tStr, "String")
                    return tStr
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
            val filledArgs = fillDefaults(args, methodDecl.params, effectiveDefaults(methodDecl, currentObject), methodDecl.name, strict = true)
            val expandedArgs2 = expandCallArgs(filledArgs, methodDecl.params)
            if (methodDecl.returnType != null && methodDecl.returnType.isSizedArray()) {
                val vRetKtcSz2 = resolveTypeName(methodDecl.returnType)
                val retType    = vRetKtcSz2.toInternalStr
                val elemCType  = arrayElementCTypeKtc(vRetKtcSz2)
                val size       = methodDecl.returnType.getSizeAnnotation()!!
                val structType = sizedArrayCTypeRef(elemCType, size)
                val tStruct    = tmp()
                val tPtr       = tmp()
                preStmts += "$structType $tStruct = ${typeFlatName(currentObject!!)}_$fnName($expandedArgs2);"
                preStmts += "$elemCType* $tPtr = $tStruct.arr;"
                preStmts += "const ktc_Int ${tPtr}\$len = $size;"
                defineVar(tPtr, retType)
                return tPtr
            }
            if (methodDecl.returnType != null && methodDecl.returnType.isSizedString()) {
                val size       = methodDecl.returnType.getSizeAnnotation()!!
                val structType = sizedStringCTypeRef(size)
                val tStruct = tmp(); val tStr = tmp()
                preStmts += "$structType $tStruct = ${typeFlatName(currentObject!!)}_$fnName($expandedArgs2);"
                preStmts += "ktc_String $tStr = {$tStruct.buf, $tStruct.len};"
                defineVar(tStr, "String")
                return tStr
            }
            return "${typeFlatName(currentObject!!)}_$fnName($expandedArgs2)"
        }
    }

    // Inside an object method but method not found in object directly — use object prefix anyway
    // for private/internal calls that were registered in funSigs
    if (currentObject != null && funSigs.containsKey(name)) {
        return "${typeFlatName(currentObject!!)}_${name}($expandedArgs)"
    }

    // Top-level function overload resolution
    val topFuns = file.decls.filterIsInstance<FunDecl>()
    val vIsOverloaded = topFuns.count { it.name == name } > 1
    // Sized-return via sig: only for non-overloaded functions (overloads use topOvr below)
    if (!vIsOverloaded && sig?.returnType != null && sig.returnType.isSizedArray()) {
        val vRetKtcTop = resolveTypeName(sig.returnType)
        val retType    = vRetKtcTop.toInternalStr
        val elemCType  = arrayElementCTypeKtc(vRetKtcTop)
        val size       = sig.returnType.getSizeAnnotation()!!
        val structType = sizedArrayCTypeRef(elemCType, size)
        val tStruct    = tmp()
        val tPtr       = tmp()
        preStmts += "$structType $tStruct = ${funCName(name)}($expandedArgs);"
        preStmts += "$elemCType* $tPtr = $tStruct.arr;"
        preStmts += "const ktc_Int ${tPtr}\$len = $size;"
        defineVar(tPtr, retType)
        return tPtr
    }
    if (!vIsOverloaded && sig?.returnType != null && sig.returnType.isSizedString()) {
        val size       = sig.returnType.getSizeAnnotation()!!
        val structType = sizedStringCTypeRef(size)
        val tStruct    = tmp()
        val tStr       = tmp()
        preStmts += "$structType $tStruct = ${funCName(name)}($expandedArgs);"
        preStmts += "ktc_String $tStr = {$tStruct.buf, $tStruct.len};"
        defineVar(tStr, "String")
        return tStr
    }
    val topOvr = findOverload(name, args, topFuns)
    if (topOvr != null && vIsOverloaded) {
        val overloadedName = methodName(topOvr, topFuns)
        val fnName = if (topOvr.isPrivate) "PRIV_$overloadedName" else overloadedName
        val filledArgs = fillDefaults(args, topOvr.params, topOvr.params.associate { it.name to it.default }, topOvr.name, strict = true)
        val expandedArgs2 = expandCallArgs(filledArgs, topOvr.params)
        if (topOvr.returnType != null && topOvr.returnType.isSizedArray()) {
            val vRetKtcOvr = resolveTypeName(topOvr.returnType)
            val retType    = vRetKtcOvr.toInternalStr
            val elemCType  = arrayElementCTypeKtc(vRetKtcOvr)
            val size       = topOvr.returnType.getSizeAnnotation()!!
            val structType = sizedArrayCTypeRef(elemCType, size)
            val tStruct    = tmp()
            val tPtr       = tmp()
            preStmts += "$structType $tStruct = ${funCName(fnName)}($expandedArgs2);"
            preStmts += "$elemCType* $tPtr = $tStruct.arr;"
            preStmts += "const ktc_Int ${tPtr}\$len = $size;"
            defineVar(tPtr, retType)
            return tPtr
        }
        if (topOvr.returnType != null && topOvr.returnType.isSizedString()) {
            val size       = topOvr.returnType.getSizeAnnotation()!!
            val structType = sizedStringCTypeRef(size)
            val tStruct    = tmp()
            val tStr       = tmp()
            preStmts += "$structType $tStruct = ${funCName(fnName)}($expandedArgs2);"
            preStmts += "ktc_String $tStr = {$tStruct.buf, $tStruct.len};"
            defineVar(tStr, "String")
            return tStr
        }
        return "${funCName(fnName)}($expandedArgs2)"
    }

    return "${funCName(name)}($expandedArgs)"
}

