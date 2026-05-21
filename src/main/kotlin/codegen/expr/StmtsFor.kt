package com.bitsycore.ktc.codegen.expr

import com.bitsycore.ktc.ast.*
import com.bitsycore.ktc.ast.Annotation
import com.bitsycore.ktc.codegen.*
import com.bitsycore.ktc.codegen.emit.collectAllIfaceMethods
import com.bitsycore.ktc.codegen.emit.ifaceDataName
import com.bitsycore.ktc.types.KtcType

// ── for ──────────────────────────────────────────────────────────

internal fun CCodeGen.emitFor(s: ForStmt, ind: String, method: Boolean) {
    loopDepth++
    val iter = s.iter
    // Unwrap "step" wrapper: (rangeExpr step N)
    val step: String?
    val rangeExpr: Expr
    if (iter is BinExpr && iter.op == "step") {
        step = genExpr(iter.right)
        rangeExpr = iter.left
    } else {
        step = null
        rangeExpr = iter
    }
    // for (i in a..b)   inclusive range
    when (rangeExpr) {
        is BinExpr if rangeExpr.op == ".." -> {
            val inc = if (step != null) "${s.varName} += $step" else "${s.varName}++"
            impl.appendLine("${ind}for (ktc_Int ${s.varName} = ${genExpr(rangeExpr.left)}; ${s.varName} <= ${genExpr(rangeExpr.right)}; $inc) {")
            pushScope(); defineVar(s.varName, "Int")
            emitBlock(s.body, ind, method)
            popScope()
            impl.appendLine("$ind}")
        }
        // for (i in a until b)  or  for (i in a..<b)
        is BinExpr if (rangeExpr.op == "until" || rangeExpr.op == "..<") -> {
            val inc = if (step != null) "${s.varName} += $step" else "${s.varName}++"
            impl.appendLine("${ind}for (ktc_Int ${s.varName} = ${genExpr(rangeExpr.left)}; ${s.varName} < ${genExpr(rangeExpr.right)}; $inc) {")
            pushScope(); defineVar(s.varName, "Int")
            emitBlock(s.body, ind, method)
            popScope()
            impl.appendLine("$ind}")
        }
        // for (i in a downTo b)
        is BinExpr if rangeExpr.op == "downTo" -> {
            val dec = if (step != null) "${s.varName} -= $step" else "${s.varName}--"
            impl.appendLine("${ind}for (ktc_Int ${s.varName} = ${genExpr(rangeExpr.left)}; ${s.varName} >= ${genExpr(rangeExpr.right)}; $dec) {")
            pushScope(); defineVar(s.varName, "Int")
            emitBlock(s.body, ind, method)
            popScope()
            impl.appendLine("$ind}")
        }
        // for (item in array/collection)  — iterate over elements
        else -> {
            val arrType = inferExprType(rangeExpr)
            val arrTypeKtc = inferExprTypeKtc(rangeExpr)
            val iterInfo = findOperatorIterator(arrType)
            if (iterInfo != null) {
                // Iterator-based: val $it = obj.iterator(); while($it.hasNext()) { val item = $it.next(); ... }
                val (iterClass, iterCType, elemKtType, isPointer) = iterInfo
                val arrExpr = genExpr(rangeExpr)
                flushPreStmts(ind)
                val iterVar = tmp()
                val selfArg = if (isPointer) arrExpr else "&$arrExpr"
                // For interface types, dispatch through vtable
                val isPtrIface = isPointer && arrType != null && run {
                    val ktc = parseResolvedTypeName(arrType)
                    val inner = (ktc as? KtcType.Ptr)?.inner as? KtcType.User
                    inner != null && inner.kind == KtcType.UserKind.Interface
                }
                if (arrType != null && interfaces.containsKey(arrType)) {
                    impl.appendLine("$ind$iterCType $iterVar = $arrExpr.vt->iterator(${ifaceVtableSelf(arrType, arrExpr)});")
                } else if (isPtrIface) {
                    val arrKtc = parseResolvedTypeName(arrType)
                    val ifaceName = ((arrKtc as KtcType.Ptr).inner as KtcType.User).baseName
                    val cIface = typeFlatName(ifaceName)
                    impl.appendLine("$ind$iterCType $iterVar = ((${cIface}_vt*)$arrExpr.vt)->iterator($arrExpr.obj);")
                } else {
                    val baseClass = if (isPointer) {
                        val arrKtc = parseResolvedTypeName(arrType!!)
                        ((arrKtc as? KtcType.Ptr)?.inner as? KtcType.User)?.baseName ?: arrType
                    } else arrType!!
                    impl.appendLine("$ind$iterCType $iterVar = ${typeFlatName(baseClass)}_iterator($selfArg);")
                }
                val isIfaceIter = interfaces.containsKey(iterClass)
                if (isIfaceIter) {
                    impl.appendLine("${ind}while (${iterVar}.vt->hasNext(${ifaceVtableSelf(iterClass, iterVar)})) {")
                    val elemCType = cTypeStr(elemKtType)
                    impl.appendLine("$ind    $elemCType ${s.varName} = ${iterVar}.vt->next(${ifaceVtableSelf(iterClass, iterVar)});")
                } else {
                    impl.appendLine("${ind}while (${typeFlatName(iterClass)}_hasNext(&$iterVar)) {")
                    val elemCType = cTypeStr(elemKtType)
                    impl.appendLine("$ind    $elemCType ${s.varName} = ${typeFlatName(iterClass)}_next(&$iterVar);")
                }
                pushScope(); defineVar(s.varName, elemKtType)
                emitBlock(s.body, ind, method)
                popScope()
                impl.appendLine("$ind}")
            } else {
                // Array: use .len / trampoline size and direct or .ptr indexing
                val arrExpr     = genExpr(rangeExpr)
                val idx         = tmp()
                val elemType    = if (arrTypeKtc != null) arrayElementCTypeKtc(arrTypeKtc) else "ktc_Int"
                val arrOrigName = (rangeExpr as? NameExpr)?.name
                val vIsTrampolined = arrOrigName != null && arrOrigName in trampolinedParams // @Size trampolined: local ptr
                val vIsSizedArr    = (arrTypeKtc as? KtcType.Arr)?.sized != null            // fixed-size C array
                val sizeExpr    = if (vIsTrampolined) arrayParamSizeExpr(arrOrigName!!) else "${arrExpr}.len"
                val vElemAccess = if (vIsTrampolined || vIsSizedArr) "$arrExpr[$idx]" else "$arrExpr.ptr[$idx]"
                impl.appendLine("${ind}for (ktc_Int $idx = 0; $idx < $sizeExpr; $idx++) {")
                impl.appendLine("$ind    $elemType ${s.varName} = $vElemAccess;")
                pushScope(); defineVar(s.varName, if (arrTypeKtc != null) arrayElementKtTypeKtc(arrTypeKtc) else "Int")
                emitBlock(s.body, ind, method)
                popScope()
                impl.appendLine("$ind}")
            }
        }
    }
    loopDepth--
}

/**
 * Check if a type has an `operator fun iterator()` method.
 * Returns (iteratorClassName, iteratorCType, elementKtType, isPointer) or null.
 */
internal fun CCodeGen.findOperatorIterator(type: String?): IteratorInfo? {
    if (type == null) return null
    // Direct class
    if (classes.containsKey(type)) {
        val vIterCI = classes[type]!!                                                  // ClassInfo for the iterable type
        val iterMethod = vIterCI.methods.find { it.name == "iterator" && it.isOperator }
        if (iterMethod?.returnType != null) {
            val iterType = resolveMethodReturnType(type, iterMethod.returnType)
            if (classes.containsKey(iterType)) {
                val vIterTypeCI = classes[iterType]!!                                  // ClassInfo for the iterator type
                val nextMethod = vIterTypeCI.methods.find { it.name == "next" }
                if (nextMethod?.returnType != null) {
                    val elemType = resolveMethodReturnType(iterType, nextMethod.returnType)
                    return IteratorInfo(iterType, vIterTypeCI.flatName, elemType, false)
                }
            } else if (interfaces.containsKey(iterType)) {
                // Iterator returns an interface — use interface type with vtable dispatch
                val vIterTypeII = interfaces[iterType]!!                               // IfaceInfo for the iterator interface
                val allMethods = collectAllIfaceMethods(vIterTypeII)
                val nextMethod = allMethods.find { it.name == "next" && it.isOperator }
                if (nextMethod?.returnType != null) {
                    val elemType = resolveMethodReturnType(iterType, nextMethod.returnType)
                    return IteratorInfo(iterType, vIterTypeII.flatName, elemType, false)
                }
            }
        }
    }
    // Heap/Ptr/Value class
    val indirectBase = (parseResolvedTypeName(type) as? KtcType.Ptr)?.inner?.let { it as? KtcType.User }?.baseName
    if (indirectBase != null && classes.containsKey(indirectBase)) {
        val vIndirectCI = classes[indirectBase]!!                                      // ClassInfo for the heap class
        val iterMethod = vIndirectCI.methods.find { it.name == "iterator" && it.isOperator }
        if (iterMethod?.returnType != null) {
            val iterType = resolveMethodReturnType(indirectBase, iterMethod.returnType)
            if (classes.containsKey(iterType)) {
                val vIndirectIterCI = classes[iterType]!!                              // ClassInfo for the iterator type
                val nextMethod = vIndirectIterCI.methods.find { it.name == "next" }
                if (nextMethod?.returnType != null) {
                    val elemType = resolveMethodReturnType(iterType, nextMethod.returnType)
                    return IteratorInfo(iterType, vIndirectIterCI.flatName, elemType, true)
                }
            }
        }
    }
    // Ptr to interface (e.g. @Ptr List<T> → List_Int*)
    val isMonoIface = indirectBase != null && genericIfaceDecls.keys.any { indirectBase.startsWith(it + "_") }
    if ((indirectBase != null && interfaces.containsKey(indirectBase)) || isMonoIface) {
        val ifaceName = if (isMonoIface) indirectBase else indirectBase
        val vIndirectIfaceII = interfaces[ifaceName]
        val allIfaceMethods = if (vIndirectIfaceII != null) collectAllIfaceMethods(vIndirectIfaceII)
            else {
                // For monomorphized ifaces, look up methods from the template + concrete vtable info
                val tmplName = genericIfaceDecls.keys.first { ifaceName.startsWith(it + "_") }
                val tmplIID = interfaces[tmplName]
                if (tmplIID != null) collectAllIfaceMethods(tmplIID) else emptyList()
            }
        val iterMethod = allIfaceMethods.find { it.name == "iterator" && it.isOperator }
        if (iterMethod?.returnType != null) {
            val iterType = resolveMethodReturnType(indirectBase, iterMethod.returnType)
            if (classes.containsKey(iterType)) {
                val vIndirectIterCI = classes[iterType]!!
                val nextMethod = vIndirectIterCI.methods.find { it.name == "next" }
                if (nextMethod?.returnType != null) {
                    val elemType = resolveMethodReturnType(iterType, nextMethod.returnType)
                    return IteratorInfo(iterType, vIndirectIterCI.flatName, elemType, true)
                }
            } else if (interfaces.containsKey(iterType)) {
                val vIndirectIterII = interfaces[iterType]!!
                val allIterMethods = collectAllIfaceMethods(vIndirectIterII)
                val nextMethod = allIterMethods.find { it.name == "next" && it.isOperator }
                if (nextMethod?.returnType != null) {
                    val elemType = resolveMethodReturnType(iterType, nextMethod.returnType)
                    return IteratorInfo(iterType, vIndirectIterII.flatName, elemType, true)
                }
            }
        }
    }
    // Interface
    interfaces[type]?.let {
        val vIfaceII = interfaces[type]!!                                               // IfaceInfo for the iterable interface
        val allMethods = collectAllIfaceMethods(vIfaceII)
        val iterMethod = allMethods.find { it.name == "iterator" && it.isOperator }
        if (iterMethod?.returnType != null) {
            val iterType = resolveMethodReturnType(type, iterMethod.returnType)
            if (classes.containsKey(iterType)) {
                val vIfaceIterCI = classes[iterType]!!                                 // ClassInfo for the iterator type
                val nextMethod = vIfaceIterCI.methods.find { it.name == "next" }
                if (nextMethod?.returnType != null) {
                    val elemType = resolveMethodReturnType(iterType, nextMethod.returnType)
                    return IteratorInfo(iterType, vIfaceIterCI.flatName, elemType, false)
                }
            }
        }
    }
    return null
}
