package com.bitsycore.ktc.codegen

import com.bitsycore.ktc.ast.*
import com.bitsycore.ktc.ast.Annotation
import com.bitsycore.ktc.codegen.emit.collectAllIfaceMethods
import com.bitsycore.ktc.codegen.statement.bindLambdaReturnTypeParams
import com.bitsycore.ktc.types.KtcType

// Return-type inference for function calls and method calls.

// Infer a generic class's type arguments from its constructor argument types when no explicit type
// args are given. A `null` inference means the type genuinely can't be determined (e.g. `Box(null)`),
// so error with E045 instead of silently materializing the wrong monomorphization (T=Int), which
// produces wrong-size / uncompilable C. Shared by the inference and codegen constructor paths. (B9)
internal fun CCodeGen.inferGenericCtorTypeArgs(inName: String, inArgs: List<Arg>): List<String> =
	inArgs.mapIndexed { i, inArg ->
		inferExprType(inArg.expr) ?: codegenError("E045",
			"Cannot infer type argument for generic class '$inName' from constructor argument ${i + 1}; " +
			"specify it explicitly, e.g. $inName<...>(...)")
	}

/* Built-in method names whose return type is fixed regardless of receiver type.
   Used by inferMethodReturnType to short-circuit before reaching class/iface lookup. */
private val kBuiltinMethodReturns: Map<String, String> = mapOf(
	"toString"   to "String",
	"trimIndent" to "String",
	"trimMargin" to "String",
	"runeAt"     to "Rune",
	"toInt"      to "Int",
	"toLong"     to "Long",
	"toFloat"    to "Float",
	"toDouble"   to "Double",
	"toIntOrNull"    to "Int?",
	"toLongOrNull"   to "Long?",
	"toFloatOrNull"  to "Float?",
	"toDoubleOrNull" to "Double?",
	"hashCode"   to "Int",
	)

/* Built-in String method return types. Falls through to extension-function
   resolution when the method isn't in this table. */
private val kStringBuiltinReturns: Map<String, String> = mapOf(
	"substring" to "String", "reversed" to "String", "lowercase" to "String",
	"uppercase" to "String", "repeat" to "String", "replace" to "String",
	"padStart" to "String", "padEnd" to "String",
	"startsWith" to "Boolean", "endsWith" to "Boolean", "contains" to "Boolean",
	"isEmpty" to "Boolean", "isNotEmpty" to "Boolean", "toBooleanStrict" to "Boolean",
	"toBooleanStrictOrNull" to "Boolean?",
	"firstOrNull" to "Char?", "lastOrNull" to "Char?", "getOrNull" to "Char?",
	"indexOf" to "Int", "lastIndexOf" to "Int", "compareTo" to "Int",
	)

/* Builder-fn and ctor-fn names that produce a primitive typed array. Looking up
   "intArrayOf" or "IntArray" yields "IntArray" as the result type. */
private val kPrimitiveArrayCtorTypes: Map<String, String> = mapOf(
	"byteArrayOf"    to "ByteArray",    "ByteArray"    to "ByteArray",
	"shortArrayOf"   to "ShortArray",   "ShortArray"   to "ShortArray",
	"intArrayOf"     to "IntArray",     "IntArray"     to "IntArray",
	"longArrayOf"    to "LongArray",    "LongArray"    to "LongArray",
	"floatArrayOf"   to "FloatArray",   "FloatArray"   to "FloatArray",
	"doubleArrayOf"  to "DoubleArray",  "DoubleArray"  to "DoubleArray",
	"booleanArrayOf" to "BooleanArray", "BooleanArray" to "BooleanArray",
	"charArrayOf"    to "CharArray",    "CharArray"    to "CharArray",
	"ubyteArrayOf"   to "UByteArray",   "UByteArray"   to "UByteArray",
	"ushortArrayOf"  to "UShortArray",  "UShortArray"  to "UShortArray",
	"uintArrayOf"    to "UIntArray",    "UIntArray"    to "UIntArray",
	"ulongArrayOf"   to "ULongArray",   "ULongArray"   to "ULongArray",
	)

internal fun CCodeGen.inferCallType(e: CallExpr): String? {
    if (e.callee is DotExpr) {
        // expr.cast<T>() reinterprets to the (last) type argument — mirror the codegen in Call.kt so
        // `val x = e.cast<T>()` infers x as T instead of falling back to Int. (D6)
        if (e.callee.name == "cast" && e.typeArgs.isNotEmpty() && e.args.isEmpty())
            return resolveTypeName(e.typeArgs.last()).toInternalStr
    }
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
            if (genericClassDecls.containsKey(flatCallee) && e.typeArgs.isNotEmpty()) {
                val resolvedArgs = e.typeArgs.map { t ->
                    val sub = substituteTypeParams(t)
                    if (sub.nullable) "${resolveTypeNameStr(sub)}?" else resolveTypeNameStr(sub)
                }
                return mangledGenericName(flatCallee, resolvedArgs)
            }
            // Generic nested class ctor without explicit type args: infer from typeSubst
            if (genericClassDecls.containsKey(flatCallee) && e.typeArgs.isEmpty() && typeSubst.isNotEmpty()) {
                val decl = genericClassDecls[flatCallee]!!
                val resolvedArgs = decl.typeParams.map { typeSubst[it] ?: it }
                if (resolvedArgs.none { it in allGenericTypeParamNames })
                    return mangledGenericName(flatCallee, resolvedArgs)
            }
            if (classes.containsKey(flatCallee)) return flatCallee
        }
        // Class(args).allocWith(allocator) → Ref<ClassType> (type/args come from the receiver ctor call)
        if (e.callee.name == "allocWith" && e.callee.obj is CallExpr) {
            val objName = (e.callee.obj.callee as? NameExpr)?.name
            if (objName != null) {
                val typeArgs = e.callee.obj.typeArgs
                val t = if (typeArgs.isNotEmpty()) TypeRef(
                    objName,
                    typeArgs = typeArgs,
                    annotations = listOf(Annotation("Ptr"))
                )
                else TypeRef(objName, annotations = listOf(Annotation("Ptr")))
                return resolveTypeName(t).toInternalStr
            }
        }
    }
    val name = (e.callee as? NameExpr)?.name
    if (name != null) {
        // thread { } closure form (trailing block) — same return type as the thread() declaration (a Thread).
        if (name == "thread" && e.args.lastOrNull()?.expr is LambdaExpr) {
            funSigs["thread"]?.returnType?.let { return resolveTypeRefStr(it) }
            return "Thread"
        }
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
            val inferredArgs = inferGenericCtorTypeArgs(name, e.args)
            recordGenericInstantiation(name, inferredArgs)
            return mangledGenericName(name, inferredArgs)
        }
        if (classes.containsKey(name)) return name
        kPrimitiveArrayCtorTypes[name]?.let { return it }
        if (name == "arrayOf") {
            if (e.typeArgs.isNotEmpty()) {
                val vTypeArg = e.typeArgs[0]
                val vElemName = resolveTypeNameInnerStr(vTypeArg)
                if (vTypeArg.nullable) return primitiveToArrayOptionalType(vElemName)
                return primitiveToArrayType(vElemName)
            }
            val elemType = if (e.args.isNotEmpty()) inferExprType(e.args[0].expr) ?: "Int" else "Int"
            return primitiveToArrayType(elemType)
        }
        if (name == "arrayOfNulls") {
            if (e.typeArgs.isNotEmpty()) {
                val vTypeArg = e.typeArgs[0]
                val vElemName = resolveTypeNameInnerStr(vTypeArg)
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
                // Compare against the RESOLVED param type ("Foo*"/"IntArray"/...) — the raw TypeRef.name
                // ("Foo"/"Array"/"T") never matches a resolved arg type. A null (un-inferable) arg is a wildcard.
                decl.params.indices.all { i -> vArgTypes[i]?.let { it == resolveTypeRefStr(decl.params[i].type) } ?: true }
            } ?: vInlineCandidates.firstOrNull { it.returnType != null && it.receiver == null }
            if (vMatch?.returnType != null) {
                // Bind the matched function's type params (explicit args, then value-type inference,
                // then lambda-return inference) so a generic return like `R` resolves to its concrete
                // type rather than the bare param name (which would mistype the binding as e.g. `P_R`).
                if (vMatch.typeParams.isNotEmpty()) {
                    val vSubst = typeSubst.toMutableMap()
                    if (e.typeArgs.isNotEmpty() && e.typeArgs.size == vMatch.typeParams.size)
                        vMatch.typeParams.zip(e.typeArgs).forEach { (tp, ta) -> vSubst[tp] = ta.name }
                    else
                        for ((vI, vP) in vMatch.params.withIndex()) {
                            val vAt = vArgTypes.getOrNull(vI)?.removeSuffix("?") ?: continue
                            matchTypeParam(vP.type, vAt, vMatch.typeParams.toSet(), vSubst)
                        }
                    bindLambdaReturnTypeParams(vMatch, e.args, null, vSubst)
                    return withTypeSubst(vSubst) { resolveTypeRefStr(vMatch.returnType) }
                }
                return resolveTypeRefStr(vMatch.returnType)
            }
        }
        funSigs[name]?.returnType?.let { return resolveTypeRefStr(it) }
        if (currentExtRecvType != null && interfaces.containsKey(currentExtRecvType)) {
            val vIfaceInfo = interfaces[currentExtRecvType]!!
            val vIfaceMethod = vIfaceInfo.methods.find { it.name == name }
                ?: collectAllIfaceMethods(vIfaceInfo).find { it.name == name }
            if (vIfaceMethod?.returnType != null) return resolveTypeRefStr(vIfaceMethod.returnType)
        }
        val vCurClass = currentClass
        if (vCurClass != null) {
            val vClassMethod = classes[vCurClass]?.methods?.find { it.name == name }
            if (vClassMethod?.returnType != null) return resolveMethodReturnType(vCurClass, vClassMethod.returnType)
        }
    }
    if (e.callee is DotExpr) {
        // Package-qualified call: infer return type from cross-package function
        if (e.callee.obj is NameExpr) {
            val vPkgFun = findCrossPackageFun(e.callee.obj.name, e.callee.name)
            if (vPkgFun != null && vPkgFun.first.returnType != null)
                return resolveTypeRefStr(vPkgFun.first.returnType!!)
            // Companion method with explicit type args: substitute into return type
            val vCompName = classCompanions[e.callee.obj.name]
            if (vCompName != null && e.typeArgs.isNotEmpty()) {
                val vMethod = objects[vCompName]?.methods?.find { it.name == e.callee.name }
                if (vMethod?.returnType != null && vMethod.typeParams.isNotEmpty()) {
                    val vSubst = mutableMapOf<String, String>()
                    vMethod.typeParams.zip(e.typeArgs).forEach { (tp, ta) -> vSubst[tp] = resolveTypeNameStr(ta) }
                    return withTypeSubst(vSubst) { resolveTypeRefStr(vMethod.returnType) }
                }
            }
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
    if (dot.obj is NameExpr && isCInteropName(dot.obj.name) && lookupVar(dot.obj.name) == null) return null
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
    kBuiltinMethodReturns[method]?.let { return it }
    if (method == "inv") return recvType
    val recvKtc = parseResolvedTypeName(recvType)
    // RawArray<T> (T*): asArray(n) → Ref<Array<T>>; resizeWith returns the bare pointer unchanged.
    if (method == "asArray" || method == "resizeWith") {
        val core = recvKtc.stripNullable
        if (core is KtcType.Ptr && core.inner !is KtcType.Arr)
            return if (method == "asArray") "${core.inner.toInternalStr}Array" else recvType
    }
    val isArrayPtr = recvKtc.isArrayLike
    if (isArrayPtr) {
        return when (method) {
            "size" -> "Int"
            "get" -> arrayElementKtTypeKtc(recvKtc)  // true element type (e.g. Int / Vec2), via the structured KtcType
            "asRef", "copyWith", "resizeWith" -> recvType
            "asRaw" -> "${arrayElementKtTypeKtc(recvKtc)}*"
            "set" -> "Unit"
            else -> null
        }
    }
    if (recvType.removeSuffix("?") == "String") {
        // Only short-circuit on a builtin match. Otherwise fall through so
        // user-defined extension functions on String (take/drop/trim/...
        // from stdlib Strings.kt) can be resolved via extensionFuns below.
        kStringBuiltinReturns[method]?.let { return it }
    }
    val pointerBase = (recvKtcCorePtr as? KtcType.Ptr)?.inner?.let { it as? KtcType.User }?.baseName
    if (pointerBase != null) {
        val classMethod = classes[pointerBase]?.methods?.find { it.name == method }
        if (classMethod != null) return resolveMethodReturnType(pointerBase, classMethod.returnType)
        val extFun = extensionFuns[pointerBase]?.find { it.name == method }
        if (extFun != null) return if (extFun.returnType != null) resolveTypeName(extFun.returnType).toInternalStr else "Unit"
        return when (method) {
            "refValue" -> pointerBase
            "set" -> "Unit"
            "copy" -> pointerBase
            "asRef" -> "${pointerBase}*"
            else -> null
        }
    }
    val baseClass = recvType.removeSuffix("?")
    if (classes.containsKey(baseClass)) {
        if (method == "copy") return baseClass
        if (method == "asRef") return "${baseClass}*"
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
            // When the receiver is a generic type (e.g. Pair<T,T>, Triple<T,T,T>) matched
            // against a monomorphized form (e.g. Triple_Int_Int_Int), inferInlineFunSubst
            // can't deduce T (it only handles bare-type-param receivers like `T.foo()`).
            // Use matchTypeParam, which walks typeArgs and consults genericTypeBindings.
            val subst = mutableMapOf<String, String>()
            val typeParamSet = extFun.typeParams.toSet()
            extFun.receiver?.let { matchTypeParam(it, recvType.removeSuffix("?"), typeParamSet, subst) }
            for ((i, p) in extFun.params.withIndex()) {
                val argType = args.getOrNull(i)?.expr?.let { inferExprType(it) } ?: continue
                matchTypeParam(p.type, argType, typeParamSet, subst)
            }
            // A type param appearing only in a lambda param's return position (`block: () -> R`)
            // isn't reachable from the value-type matching above — infer it from the lambda body.
            bindLambdaReturnTypeParams(extFun, args, recvType.removeSuffix("?"), subst)
            // Prefer the concrete-return inference (e.g. ArrayList_Int) over the raw template return.
            val typeArgNames = extFun.typeParams.map { subst[it] ?: it }
            val mangledName  = "${extFun.name}_${typeArgNames.joinToString("_")}"
            genericFunConcreteReturn[mangledName]?.let { return it }
            val result = withTypeSubst(subst) { resolveTypeName(extFun.returnType) }
            return if (extFun.returnType.nullable) KtcType.Nullable(result).toInternalStr else result.toInternalStr
        }
        if (extFun.returnType != null) {
            val result = resolveTypeName(extFun.returnType)
            return if (extFun.returnType.nullable) KtcType.Nullable(result).toInternalStr else result.toInternalStr
        }
        return "Unit"
    }
    // Generic extension on a generic class — e.g. `fun <T> Pair<T,T>.toList(...)` applied to
    // Pair_Int_Int. The flat receiver type (Pair_Int_Int) is in `classes` (monomorphized) but
    // the extension lives in `genericFunDecls` keyed by the template name (Pair). Mirror the
    // lookup done in CallMethod.kt's dispatch, and consult genericFunConcreteReturn so the
    // caller infers the concrete return (e.g. ArrayList_Int) rather than the unsubstituted
    // template return.
    run {
        val recvBase = recvType.removeSuffix("?")
        val genericExt = genericFunDecls.find { gf ->
            gf.name == method && gf.receiver != null && (
                gf.receiver.name == recvBase ||
                (genericClassDecls.containsKey(gf.receiver.name) && recvBase.startsWith("${gf.receiver.name}_")) ||
                (genericIfaceDecls.containsKey(gf.receiver.name) && recvBase.startsWith("${gf.receiver.name}_"))
            )
        }
        if (genericExt != null && genericExt.returnType != null) {
            val subst = mutableMapOf<String, String>()
            val typeParamSet = genericExt.typeParams.toSet()
            matchTypeParam(genericExt.receiver!!, recvBase, typeParamSet, subst)
            for ((i, p) in genericExt.params.withIndex()) {
                val argType = args.getOrNull(i)?.expr?.let { inferExprType(it) } ?: continue
                matchTypeParam(p.type, argType, typeParamSet, subst)
            }
            // Infer a type param that appears only in a lambda param's return position (`block: () -> R`).
            bindLambdaReturnTypeParams(genericExt, args, recvBase, subst)
            val typeArgNames = genericExt.typeParams.map { subst[it] ?: it }
            val mangledName  = "${genericExt.name}_${typeArgNames.joinToString("_")}"
            genericFunConcreteReturn[mangledName]?.let { return it }
            val result = withTypeSubst(subst) { resolveTypeName(genericExt.returnType) }
            return if (genericExt.returnType.nullable) KtcType.Nullable(result).toInternalStr else result.toInternalStr
        }
    }
    if (recvType in enums) {
        val ei = enums[recvType]!!
        when (method) {
            "values" -> return "${recvType}Array"
            "valueOf" -> return recvType
        }
        // Instance method on a full enum — look up the declared return type.
        val vEnumMethod = ei.enumMethods.find { it.name == method }
        if (vEnumMethod != null) return resolveMethodReturnType(recvType, vEnumMethod.returnType)
    }
    return null
}
