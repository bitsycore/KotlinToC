package com.bitsycore.ktc.codegen

import com.bitsycore.ktc.ast.*
import com.bitsycore.ktc.codegen.mapping.arrayElementCTypeKtc
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
                        val cConcrete = typeFlatName(allocObjName!!); val typeId = getTypeId(allocObjName)
                        preStmts += "ktc_IfacePtr $t = {{$typeId}, (const void*)&${cConcrete}_Allocator_vt, (void*)&$allocExpr};"
                        ifaceCreated = true; ifExpr = t
                    }
                    isAllocClass -> {
                        val cConcrete = typeFlatName(allocArgClassName!!); val typeId = getTypeId(allocArgClassName)
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
                                val cConcrete = typeFlatName(allocObjName!!); val typeId = getTypeId(allocObjName)
                                preStmts += "ktc_IfacePtr $t = {{$typeId}, (const void*)&${cConcrete}_Allocator_vt, (void*)&$allocExpr};"
                                ifaceCreated3 = true; ifExpr3 = t
                            }
                            isAllocClass3 -> {
                                val cConcrete = typeFlatName(allocArgClassName3!!); val typeId = getTypeId(allocArgClassName3)
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

/* Returns the size expression for a trampolined param's array length.
For sized struct params (in sizedArrayTrampolinedParams), the local$name$len constant is used.
For regular ktc_ArrayTrampoline params, the trampoline .size field is used. */
internal fun CCodeGen.arrayParamSizeExpr(inName: String): String =
    if (inName in sizedArrayTrampolinedParams) "local\$${inName}\$len" else "$inName.size"

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
    if (bp != null && bp.typeRef.hasSizeAnnotation()) {
        return bp.typeRef.getSizeAnnotation()
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
            } else if (param.type.isSizedString()) {
                // @Size(N) String param — wrap ktc_String into ktc_String_N value struct
                val vSize = param.type.getSizeAnnotation()!!  // annotation size
                val vStructType = sizedStringCTypeRef(vSize)
                val vWrap = tmp()
                preStmts += "$vStructType $vWrap;"
                preStmts += "memcpy($vWrap.buf, ($expr).ptr, ($expr).len * sizeof(ktc_Char)); $vWrap.len = ($expr).len;"
                parts += vWrap
            } else if (isArrayType(paramType)) {
                if (param.type.hasSizeAnnotation()) {
                    // @Size(N) array param — wrap pointer into ktc_Array_T_N value struct
                    val vElemKtc   = paramTypeKtc.asArr!!.elem  // element KtcType
                    val vElemCType = cTypeStr(vElemKtc)         // element C type
                    val vSize      = param.type.getSizeAnnotation()!!
                    val vStructType = sizedArrayCTypeRef(vElemCType, vSize)
                    val vWrap = tmp()
                    preStmts += "$vStructType $vWrap;"
                    preStmts += "memcpy($vWrap.arr, $expr, $vSize * sizeof($vElemCType));"
                    parts += vWrap
                } else if (arg.expr is NullLit && !isCtorCall) {
                    // Both nullable and non-nullable use ktc_ArrayTrampoline; NULL is data == NULL
                    parts += "(ktc_ArrayTrampoline){.size = 0, .data = NULL}"
                } else {
                    // Non-null array (nullable or not): pack as ktc_ArrayTrampoline
                    val argName = (arg.expr as? NameExpr)?.name
                    val sizeExpr = if (argName != null && argName in trampolinedParams) arrayParamSizeExpr(argName) else "${expr}\$len"
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

    // Array .size → sized-struct param uses local$name$len; trampoline uses .size field; others use $len
    if (method == "size" && recvTypeKtc != null && recvTypeKtc.isArrayLike) {
        val dotName = (dot.obj as? NameExpr)?.name
        return if (dotName != null && dotName in trampolinedParams) arrayParamSizeExpr(dotName) else "${recv}\$len"
    }
    // Array .ptr() → just the pointer (already a pointer type)
    if (method == "ptr" && recvTypeKtc != null && recvTypeKtc.isArrayLike) {
        return recv
    }
    // Array .toHeap() → malloc + memcpy to heap
    if (method == "toHeap" && recvTypeKtc != null && recvTypeKtc.isArrayLike) {
        val elemC = arrayElementCTypeKtc(recvTypeKtc)
        val lenExpr = when {
            dot.obj is NameExpr && dot.obj.name in trampolinedParams -> arrayParamSizeExpr(dot.obj.name)
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
        preStmts += "$elemC* ${t}_ptr = ($elemC*)((ktc_std_Allocator_vt*)$ifExpr.vt)->reallocMem($ifExpr.obj, $recv, sizeof($elemC) * (size_t)($newSizeExpr), ${ktSrcStr()});"
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
                val filled2 = fillDefaults(args, methodDecl.params, effectiveDefaults(methodDecl, pointerBase), methodDecl.name, strict = true)
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
                (genericClassDecls.containsKey(it.receiver.name) && pointerBase.startsWith("${it.receiver.name}_"))
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
                        (genericIfaceDecls.containsKey(it.receiver.name) && iface.startsWith("${it.receiver.name}_"))
                    )
                }
                if (m != null) {
                    ifaceExt = m
                    ifaceExtConcrete = if (iface.startsWith("${m.receiver!!.name}_")) iface
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
            /* Extract type args via mangledComponents lookup */
            val tArgComponents = mangledComponents[ifaceExtConcrete!!]?.second
            val tArgs = ifaceExt.typeParams.mapIndexed { i, _ ->
                tArgComponents?.getOrNull(i) ?: "Int"
            }
            genericFunInstantiations.getOrPut(ifaceExt.name) { mutableSetOf() }.add(tArgs)
        }
        val isPtrExt = effectiveGenExt?.receiver?.annotations?.any { it.name == "Ptr" } == true
        val flatPtrBase = if (ifaceExtConcrete != null) {
            val f = typeFlatName(ifaceExtConcrete)
            if (isPtrExt) "${f.removeSuffix("_$ifaceExtConcrete")}_Ptr$${ifaceExtConcrete}" else f
        } else if (isPtrExt) "${typeFlatName(pointerBase).removeSuffix("_$pointerBase")}_Ptr$${pointerBase}" else typeFlatName(pointerBase)
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
        val vtAccess = if (isIfacePtr) "((${cIface}_vt*)$recv.vt)"
                       else "$recv.vt"
        val ifaceMethod = vIfaceInfo.methods.find { it.name == method }
            ?: collectAllIfaceMethods(vIfaceInfo).find { it.name == method }
        // Fill defaults from the interface method (the origin of default values in Kotlin)
        val vFilledArgStr = if (ifaceMethod != null) {
            val vFilled = fillDefaults(args, ifaceMethod.params, ifaceMethod.params.associate { it.name to it.default }, method, strict = true)
            expandCallArgs(vFilled, ifaceMethod.params)
        } else argStr
        val allArgs = if (vFilledArgStr.isEmpty()) vSelfArg else "$vSelfArg, $vFilledArgStr"
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
                (genericClassDecls.containsKey(it.receiver.name) && vClassInfo.baseName.startsWith("${it.receiver.name}_"))
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
                        (genericIfaceDecls.containsKey(it.receiver.name) && iface.startsWith("${it.receiver.name}_"))
                    )
                }
                if (match != null) {
                    genericExtDecl = match
                    // Determine the concrete interface name (e.g. List$1_Int)
                    ifaceConcrete = if (iface.startsWith("${match.receiver!!.name}_")) iface
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
            /* Extract type args via mangledComponents lookup */
            val ifaceComponents = mangledComponents[ifaceConcrete]?.second
            val tArgs = genericExtDecl.typeParams.mapIndexed { i, _ ->
                ifaceComponents?.getOrNull(i) ?: "Int"
            }
            genericFunInstantiations.getOrPut(genericExtDecl.name) { mutableSetOf() }.add(tArgs)
        }
        val effectiveDecl = methodDecl ?: genericExtDecl
        val isExtFun = effectiveDecl?.receiver != null
        val isPtrRecv = effectiveDecl?.receiver?.annotations?.any { it.name == "Ptr" } == true
        val isNullableRecv = effectiveDecl?.receiver?.nullable == true
        val flatBase = if (ifaceConcrete != null) {
            val f = typeFlatName(ifaceConcrete)
            if (isPtrRecv) "${f.removeSuffix("_$ifaceConcrete")}_Ptr$${ifaceConcrete}" else f
        } else if (isPtrRecv) "${vClassInfo.flatName.removeSuffix("_${vClassInfo.baseName}")}_Ptr$${vClassInfo.baseName}" else vClassInfo.flatName
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
            val filled = fillDefaults(args, methodDecl.params, effectiveDefaults(methodDecl, vClassInfo.baseName), methodDecl.name, strict = true)
            expandCallArgs(filled, methodDecl.params)
        } else argStr
        if (classBindings != null && classBindings.isNotEmpty()) {
            typeSubst = savedSubst
        }
        val allArgs = if (expandedArgs.isEmpty()) selfArg else "$selfArg, $expandedArgs"
        val overloadedName = methodDecl?.let { methodName(it, vClassInfo.methods) } ?: method
        val fnPrefix = if (methodDecl?.isPrivate == true) "PRIV_$overloadedName" else overloadedName
        if (methodDecl?.returnType != null && methodDecl.returnType.isSizedArray()) {
            val vRetKtcSz  = resolveTypeName(methodDecl.returnType)
            val retType    = vRetKtcSz.toInternalStr
            val elemCType  = arrayElementCTypeKtc(vRetKtcSz)
            val size       = methodDecl.returnType.getSizeAnnotation()!!
            val structType = sizedArrayCTypeRef(elemCType, size)
            val tStruct = tmp(); val tPtr = tmp()
            preStmts += "$structType $tStruct = ${vClassInfo.flatName}_$fnPrefix($allArgs);"
            preStmts += "$elemCType* $tPtr = $tStruct.arr;"
            preStmts += "const ktc_Int ${tPtr}\$len = $size;"
            defineVar(tPtr, retType)
            return tPtr
        }
        if (methodDecl?.returnType != null && methodDecl.returnType.isSizedString()) {
            val size       = methodDecl.returnType.getSizeAnnotation()!!
            val structType = sizedStringCTypeRef(size)
            val tStruct = tmp(); val tStr = tmp()
            preStmts += "$structType $tStruct = ${vClassInfo.flatName}_$fnPrefix($allArgs);"
            preStmts += "ktc_String $tStr = {$tStruct.buf, $tStruct.len};"
            defineVar(tStr, "String")
            return tStr
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
            val filled = fillDefaults(args, vObjMethod.params, effectiveDefaults(vObjMethod, vDotObjInfo.name), vObjMethod.name, strict = true)
            expandCallArgs(filled, vObjMethod.params)
        } else argStr
        if (vObjMethod?.returnType != null && vObjMethod.returnType.isSizedArray()) {
            val vRetKtcObj = resolveTypeName(vObjMethod.returnType)
            val retType    = vRetKtcObj.toInternalStr
            val elemCType  = arrayElementCTypeKtc(vRetKtcObj)
            val size       = vObjMethod.returnType.getSizeAnnotation()!!
            val structType = sizedArrayCTypeRef(elemCType, size)
            val tStruct = tmp(); val tPtr = tmp()
            preStmts += "$structType $tStruct = ${vDotObjCName}_$overloadedMethod($vObjArgs);"
            preStmts += "$elemCType* $tPtr = $tStruct.arr;"
            preStmts += "const ktc_Int ${tPtr}\$len = $size;"
            defineVar(tPtr, retType)
            return tPtr
        }
        if (vObjMethod?.returnType != null && vObjMethod.returnType.isSizedString()) {
            val size       = vObjMethod.returnType.getSizeAnnotation()!!
            val structType = sizedStringCTypeRef(size)
            val tStruct = tmp(); val tStr = tmp()
            preStmts += "$structType $tStruct = ${vDotObjCName}_$overloadedMethod($vObjArgs);"
            preStmts += "ktc_String $tStr = {$tStruct.buf, $tStruct.len};"
            defineVar(tStr, "String")
            return tStr
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
        preStmts += "$optType2 $t2 = $guard2 ? ${optSome(optType2, call2)} : ${optNone(optType2)};"
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
    preStmts += "$optType $t = $guard ? ${optSome(optType, call)} : ${optNone(optType)};"
    markOptional(t)
    defineVar(t, "${retType}?")
    return t
}

// ── dot access (property, enum) ──────────────────────────────────

internal fun CCodeGen.effectiveDefaults(
	inMethodDecl: FunDecl,
	inOwnerName: String?
): Map<String, Expr?> {
	val vLocal = inMethodDecl.params.associate { it.name to it.default } // defaults on the override itself
	if (!inMethodDecl.isOverride || inOwnerName == null) return vLocal
	val vIfaces = classInterfaces[inOwnerName] ?: return vLocal
	for (vIfaceName in vIfaces) {
		val vIfaceInfo = interfaces[vIfaceName] ?: continue
		val vIfaceMethod = collectAllIfaceMethods(vIfaceInfo).find { it.name == inMethodDecl.name }
			?: continue
		val vIfaceDefaults = vIfaceMethod.params.associate { it.name to it.default }
		return vLocal.mapValues { (vKey, vVal) -> vVal ?: vIfaceDefaults[vKey] } // local wins, iface fills gaps
	}
	return vLocal
}

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
