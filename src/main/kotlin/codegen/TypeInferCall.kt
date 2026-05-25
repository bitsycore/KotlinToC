package com.bitsycore.ktc.codegen

import com.bitsycore.ktc.ast.*
import com.bitsycore.ktc.ast.Annotation
import com.bitsycore.ktc.codegen.emit.collectAllIfaceMethods
import com.bitsycore.ktc.codegen.expression.inferInlineFunSubst
import com.bitsycore.ktc.types.KtcType

// Return-type inference for function calls and method calls.

internal fun CCodeGen.inferCallType(e: CallExpr): String? {
    // Nested class constructor: Outer.Inner(...) or A.B.C(...)
    if (e.callee is DotExpr) {
        fun flattenDotCallee(callee: Expr): String? {
            if (callee is NameExpr) return callee.name
            if (callee is DotExpr && callee.obj is NameExpr) return "${callee.obj.name}$${callee.name}"
            if (callee is DotExpr) {
                val left = flattenDotCallee(callee.obj)
                if (left != null) return "$left$${callee.name}"
            }
            return null
        }

        val flatCallee = flattenDotCallee(e.callee)
        if (flatCallee != null) {
            if (classes.containsKey(flatCallee)) return flatCallee
            if (genericClassDecls.containsKey(flatCallee)) {
                val resolvedArgs = e.typeArgs.map { t ->
                    val sub = substituteTypeParams(t)
                    if (sub.nullable) "${resolveTypeNameStr(sub)}?" else resolveTypeNameStr(sub)
                }
                return mangledGenericName(flatCallee, resolvedArgs)
            }
        }
        // allocWith → @Ptr ClassType
        if (e.callee.name == "allocWith" && e.callee.obj is NameExpr) {
            val objName = e.callee.obj.name
            val typeArgs = e.typeArgs
            val t = if (typeArgs.isNotEmpty()) TypeRef(
                objName,
                typeArgs = typeArgs,
                annotations = listOf(Annotation("Ptr"))
            )
            else TypeRef(objName, annotations = listOf(Annotation("Ptr")))
            return resolveTypeName(t).toInternalStr
        }
    }
    val name = (e.callee as? NameExpr)?.name
    if (name != null) {
        if (name == "StringBuffer" && !classes.containsKey("StringBuffer") && !genericClassDecls.containsKey("StringBuffer")) {
            return "ktc_StrBuf"
        }
        if (classes.containsKey(name) && classes[name]!!.isGeneric && e.typeArgs.isNotEmpty()) {
            val resolvedArgs = e.typeArgs.map { t ->
                val sub = substituteTypeParams(t)
                if (sub.nullable) "${resolveTypeNameStr(sub)}?" else resolveTypeNameStr(sub)
            }
            return mangledGenericName(name, resolvedArgs)
        }
        if (classes.containsKey(name) && classes[name]!!.isGeneric && e.args.isNotEmpty()
            && classes[name]!!.typeParams.size == e.args.size
        ) {
            val inferredArgs = e.args.map { inferExprType(it.expr) ?: "Int" }
            recordGenericInstantiation(name, inferredArgs)
            return mangledGenericName(name, inferredArgs)
        }
        if (classes.containsKey(name)) return name
        if (name == "HeapAlloc" || name == "HeapArrayZero" || name == "HeapArrayResize") {
            val ta = e.typeArgs.getOrNull(0) ?: heapAllocTargetType ?: return "void*"
            if (ta.name == "Array" && ta.typeArgs.isNotEmpty()) {
                val elemName = typeSubst[ta.typeArgs[0].name] ?: ta.typeArgs[0].name
                return "${elemName}Array"
            }
            if (ta.name == "RawArray" && ta.typeArgs.isNotEmpty()) {
                val elemName = typeSubst[ta.typeArgs[0].name] ?: ta.typeArgs[0].name
                return "${elemName}*"
            }
            if (ta.typeArgs.isNotEmpty() && classes.containsKey(ta.name) && classes[ta.name]!!.isGeneric) {
                val resolvedArgs = ta.typeArgs.map { t ->
                    val sub = substituteTypeParams(t)
                    if (sub.nullable) "${resolveTypeNameStr(sub)}?" else resolveTypeNameStr(sub)
                }
                return "${mangledGenericName(ta.name, resolvedArgs)}*"
            }
            val resolvedName = typeSubst[ta.name] ?: ta.name
            return "${resolvedName}*"
        }
        if (name == "byteArrayOf" || name == "ByteArray") return "ByteArray"
        if (name == "shortArrayOf" || name == "ShortArray") return "ShortArray"
        if (name == "intArrayOf" || name == "IntArray") return "IntArray"
        if (name == "longArrayOf" || name == "LongArray") return "LongArray"
        if (name == "floatArrayOf" || name == "FloatArray") return "FloatArray"
        if (name == "doubleArrayOf" || name == "DoubleArray") return "DoubleArray"
        if (name == "booleanArrayOf" || name == "BooleanArray") return "BooleanArray"
        if (name == "charArrayOf" || name == "CharArray") return "CharArray"
        if (name == "ubyteArrayOf" || name == "UByteArray") return "UByteArray"
        if (name == "ushortArrayOf" || name == "UShortArray") return "UShortArray"
        if (name == "uintArrayOf" || name == "UIntArray") return "UIntArray"
        if (name == "ulongArrayOf" || name == "ULongArray") return "ULongArray"
        if (name == "arrayOf") {
            if (e.typeArgs.isNotEmpty()) {
                val vTypeArg = e.typeArgs[0]
                val vElemName = typeSubst[vTypeArg.name] ?: vTypeArg.name
                if (vTypeArg.nullable) return primitiveToArrayOptionalType(vElemName)
                return primitiveToArrayType(vElemName)
            }
            val elemType = if (e.args.isNotEmpty()) inferExprType(e.args[0].expr) ?: "Int" else "Int"
            return primitiveToArrayType(elemType)
        }
        if (name == "arrayOfNulls") {
            if (e.typeArgs.isNotEmpty()) {
                val vTypeArg = e.typeArgs[0]
                val vElemName = typeSubst[vTypeArg.name] ?: vTypeArg.name
                return primitiveToArrayOptionalType(vElemName)
            }
            return "IntOptArray"
        }
        if (name == "enumValues") {
            if (e.typeArgs.isNotEmpty()) {
                val enumName = e.typeArgs[0].name
                val resolved = typeSubst[enumName] ?: enumName
                return "${resolved}Array"
            }
            return "IntArray"
        }
        if (name == "enumValueOf") {
            if (e.typeArgs.isNotEmpty()) {
                val enumName = e.typeArgs[0].name
                return typeSubst[enumName] ?: enumName
            }
            return "Int"
        }
        if (name == "Array" && e.typeArgs.isNotEmpty()) {
            val elemName = resolveTypeName(e.typeArgs[0]).toInternalStr
            return "${elemName}Array"
        }
        // Generic function call: resolve return type with type substitution
        val genFun = genericFunDecls.find { it.name == name }
        if (genFun != null && genFun.returnType != null) {
            val typeArgNames = if (e.typeArgs.isNotEmpty()) {
                e.typeArgs.map { resolveTypeName(it).toInternalStr }
            } else {
                val inferredSubst = mutableMapOf<String, String>()
                for ((i, param) in genFun.params.withIndex()) {
                    if (i >= e.args.size) break
                    val argType = inferExprType(e.args[i].expr) ?: continue
                    matchTypeParam(param.type, argType, genFun.typeParams.toSet(), inferredSubst)
                }
                if (inferredSubst.size == genFun.typeParams.size) genFun.typeParams.map { inferredSubst[it]!! } else null
            }
            if (typeArgNames != null) {
                val mangledName = "${name}_${typeArgNames.joinToString("_")}"
                val concreteRet = genericFunConcreteReturn[mangledName]
                if (concreteRet != null) return concreteRet
                val subst  = genFun.typeParams.zip(typeArgNames).toMap()
                val result = withTypeSubst(subst) { resolveTypeName(genFun.returnType) }
                return if (genFun.returnType.nullable) KtcType.Nullable(result).toInternalStr else result.toInternalStr
            }
        }
        activeLambdas[name]?.returnType?.let { return it.toInternalStr }
        val vInlineCandidates = inlineFunDecls[name]
        if (!vInlineCandidates.isNullOrEmpty()) {
            val vArgTypes = e.args.map { inferExprType(it.expr) }
            val vMatch = vInlineCandidates.find { decl ->
                decl.returnType != null && decl.receiver == null &&
                decl.params.size == vArgTypes.size &&
                decl.params.indices.all { i -> vArgTypes[i] == decl.params[i].type.name }
            } ?: vInlineCandidates.firstOrNull { it.returnType != null && it.receiver == null }
            if (vMatch?.returnType != null) return resolveTypeRefStr(vMatch.returnType)
        }
        funSigs[name]?.returnType?.let { return resolveTypeRefStr(it) }
        if (currentExtRecvType != null && interfaces.containsKey(currentExtRecvType)) {
            val vIfaceInfo = interfaces[currentExtRecvType]!!
            val vIfaceMethod = vIfaceInfo.methods.find { it.name == name }
                ?: collectAllIfaceMethods(vIfaceInfo).find { it.name == name }
            if (vIfaceMethod?.returnType != null) return resolveTypeRefStr(vIfaceMethod.returnType)
        }
    }
    if (e.callee is DotExpr) {
        // Package-qualified call: infer return type from cross-package function
        if (e.callee.obj is NameExpr) {
            val vPkgFun = findCrossPackageFun(e.callee.obj.name, e.callee.name)
            if (vPkgFun != null && vPkgFun.first.returnType != null)
                return resolveTypeRefStr(vPkgFun.first.returnType!!)
            }
        return inferMethodReturnType(e.callee, e.args)
        }
    if (e.callee is SafeDotExpr) {
        val retType = inferMethodReturnType(DotExpr(e.callee.obj, e.callee.name), e.args) ?: return null
        if (retType == "Unit") return retType
        val retKtc = parseResolvedTypeName(retType)
        return if (retKtc is KtcType.Nullable) retType else "${retType}?"
    }
    return null
}

/* Resolve a method return type as a String, applying generic bindings for concrete generic instantiations. */
internal fun CCodeGen.resolveMethodReturnType(className: String, returnType: TypeRef?): String {
    if (returnType == null) return "Unit"
    return withTypeSubst(genericTypeBindings[className]) { resolveTypeRefStr(returnType) }
}

/* KtcType variant — avoids the toInternalStr round-trip at call sites that already want KtcType. */
internal fun CCodeGen.resolveMethodReturnTypeKtc(inClassName: String, inReturnType: TypeRef?): KtcType {
    if (inReturnType == null) return KtcType.Void
    val vKtc = withTypeSubst(genericTypeBindings[inClassName]) { resolveTypeName(inReturnType) }
    return if (inReturnType.nullable) KtcType.Nullable(vKtc) else vKtc
}

internal fun CCodeGen.inferMethodReturnType(dot: DotExpr, args: List<Arg>): String? {
    if (dot.obj is NameExpr && dot.obj.name == "c" && lookupVar(dot.obj.name) == null) return null
    val vDotObjName = (dot.obj as? NameExpr)?.name
    val vCompanionName = vDotObjName?.let { classCompanions[it] }
    if (vCompanionName != null) {
        val vMethod = objects[vCompanionName]?.methods?.find { it.name == dot.name }
        if (vMethod?.returnType != null) return resolveTypeRefStr(vMethod.returnType)
        return null
    }
    val recvType = inferExprType(dot.obj) ?: return null
    val recvKtcPtr = inferExprTypeKtc(dot.obj)
    val recvKtcCorePtr = recvKtcPtr.stripNullable
    val method = dot.name
    if (method == "toString") return "String"
    if (method == "trimIndent") return "String"
    if (method == "trimMargin") return "String"
    if (method == "runeAt") return "Rune"
    if (method == "toInt") return "Int"
    if (method == "toLong") return "Long"
    if (method == "toFloat") return "Float"
    if (method == "toDouble") return "Double"
    if (method == "toIntOrNull") return "Int?"
    if (method == "toLongOrNull") return "Long?"
    if (method == "toFloatOrNull") return "Float?"
    if (method == "toDoubleOrNull") return "Double?"
    if (method == "hashCode") return "Int"
    if (method == "inv") return recvType
    val recvKtc = parseResolvedTypeName(recvType)
    val isArrayPtr = recvKtc.isArrayLike
    if (isArrayPtr) {
        return when (method) {
            "size" -> "Int"
            "get" -> {
                val base = recvType.removeSuffix("?")
                if (base.endsWith("Array*")) base.removeSuffix("Array*") else base.removeSuffix("*")
            }

            "ptr", "copyWith" -> recvType
            "set" -> "Unit"
            else -> null
        }
    }
    if (recvType.removeSuffix("?") == "String") {
        return when (method) {
            "substring" -> "String"
            "startsWith", "endsWith", "contains", "isEmpty", "isNotEmpty" -> "Boolean"
            "indexOf" -> "Int"
            else -> null
        }
    }
    val pointerBase = (recvKtcCorePtr as? KtcType.Ptr)?.inner?.let { it as? KtcType.User }?.baseName
    if (pointerBase != null) {
        val classMethod = classes[pointerBase]?.methods?.find { it.name == method }
        if (classMethod != null) return resolveMethodReturnType(pointerBase, classMethod.returnType)
        val extFun = extensionFuns[pointerBase]?.find { it.name == method }
        if (extFun != null) return if (extFun.returnType != null) resolveTypeName(extFun.returnType).toInternalStr else "Unit"
        return when (method) {
            "value" -> pointerBase
            "set" -> "Unit"
            "copy" -> pointerBase
            "ptr" -> "${pointerBase}*"
            else -> null
        }
    }
    val baseClass = recvType.removeSuffix("?")
    if (classes.containsKey(baseClass)) {
        if (method == "copy") return baseClass
        if (method == "ptr") return "${baseClass}*"
    }
    val iface = interfaces[recvType]
    if (iface != null) {
        val m = iface.methods.find { it.name == method }
        if (m != null && m.returnType != null) return resolveMethodReturnType(recvType, m.returnType)
    }
    if (objects.containsKey(baseClass)) {
        val m = objects[baseClass]!!.methods.find { it.name == method }
        if (m != null) return resolveMethodReturnType(baseClass, m.returnType)
    }
    val ci = classes[recvType]
    if (ci != null) {
        val m = ci.methods.find { it.name == method }
        if (m != null) return resolveMethodReturnType(recvType, m.returnType)
    }
    val extFun = extensionFuns[recvType]?.find { it.name == method }
    if (extFun != null) {
        if (extFun.typeParams.isNotEmpty() && extFun.returnType != null) {
            val vArgTypes = args.map { inferExprType(it.expr) }
            return withTypeSubst(inferInlineFunSubst(extFun, recvType, vArgTypes)) { resolveTypeName(extFun.returnType).toInternalStr }
        }
        return if (extFun.returnType != null) resolveTypeName(extFun.returnType).toInternalStr else "Unit"
    }
    if (recvType in enums) {
        when (method) {
            "values" -> return "${recvType}Array"
            "valueOf" -> return recvType
        }
    }
    return null
}
