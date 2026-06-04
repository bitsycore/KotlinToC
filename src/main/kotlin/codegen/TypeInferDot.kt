package com.bitsycore.ktc.codegen

import com.bitsycore.ktc.ast.*
import com.bitsycore.ktc.codegen.emit.collectAllIfaceMethods
import com.bitsycore.ktc.types.KtcType

// Type inference for dot-access (field/property) and index expressions.

/* Resolve a named property's KtcType from a props list, wrapping nullable properties in KtcType.Nullable. */
private fun CCodeGen.resolvePropTypeKtc(props: List<Pair<String, TypeRef>>, name: String): KtcType? {
	val prop = props.find { it.first == name } ?: return null
	val base = resolveTypeName(prop.second)
	return if (prop.second.nullable) KtcType.Nullable(base) else base
	}

internal fun CCodeGen.inferDotType(e: DotExpr): String? = inferDotTypeKtc(e)?.toInternalStr

internal fun CCodeGen.inferDotTypeKtc(e: DotExpr): KtcType? {
	if (e.obj is NameExpr && isCInteropName(e.obj.name) && lookupVar(e.obj.name) == null) return null
	if (e.obj is NameExpr && e.obj.name == "Macro") {
		return when (e.name) {
			"FILE", "C_FILE" -> KtcType.Str
			"LINE", "C_LINE" -> KtcType.Prim(KtcType.PrimKind.Int)
			else             -> null
			}
		}
	if (e.obj is ClassRefExpr && e.name in setOf("simpleName", "qualifiedName")) return KtcType.Str
	if (e.obj is NameExpr && enums.containsKey(e.obj.name)) return parseResolvedTypeName(e.obj.name)
	val vDotObjInfo = resolveDotObjInfo(e)
	if (vDotObjInfo != null) {
		return resolvePropTypeKtc(vDotObjInfo.props, e.name)
		}
	val recvType        = inferExprType(e.obj) ?: return null
	val recvTypeKtc     = inferExprTypeKtc(e.obj)
	val recvTypeCoreKtc = recvTypeKtc.stripNullable
	// COpaque field access: we don't know the struct layout, default to Float
	// (fields like x/y/w/h on SDL_FRect). C code emits correct member access.
	if (recvTypeCoreKtc is KtcType.COpaque) return KtcType.Prim(KtcType.PrimKind.Float)
	if (recvType == "ktc_StrBuf" || recvType == "StringBuffer") {
		return when (e.name) {
			"buffer" -> parseResolvedTypeName("CharArray*?")
			"len"    -> KtcType.Prim(KtcType.PrimKind.Int)
			else     -> null
			}
		}
	if (e.name == "size" && recvTypeCoreKtc != null && recvTypeCoreKtc.isArrayLike) return KtcType.Prim(KtcType.PrimKind.Int)
	// .cPtr → raw C pointer: T* for Array, const ktc_Char* (Ref<Char>) for String. (.ptr is disallowed — E055.)
	if (e.name == "cPtr") {
		if (recvTypeCoreKtc?.isArrayLike == true) { val arr = recvTypeCoreKtc.asArr; if (arr != null) return KtcType.Ptr(arr.elem) }
		if (recvTypeCoreKtc is KtcType.Str) return KtcType.Ptr(KtcType.Prim(KtcType.PrimKind.Char))
		}
	if (e.name == "ptr") {
		if (recvTypeCoreKtc?.isArrayLike == true) {
			val arr = recvTypeCoreKtc.asArr
			if (arr != null) return KtcType.Ptr(arr.elem)
			}
		if (recvTypeCoreKtc is KtcType.Ptr && recvTypeCoreKtc.inner is KtcType.Arr) return parseResolvedTypeName(recvType)
		return if (recvTypeCoreKtc is KtcType.Ptr) parseResolvedTypeName(recvType) else parseResolvedTypeName("${recvType}*")
		}
	if (e.name == "refValue" && recvTypeCoreKtc is KtcType.Ptr) return recvTypeCoreKtc.inner
	if (e.name == "ptr"    && recvTypeCoreKtc is KtcType.Str) return KtcType.Ptr(KtcType.Prim(KtcType.PrimKind.Char))
	if (e.name == "length" && recvTypeCoreKtc is KtcType.Str) return KtcType.Prim(KtcType.PrimKind.Int)
	if (e.name == "runeLen" && recvTypeCoreKtc is KtcType.Str) return KtcType.Prim(KtcType.PrimKind.Int)
	if (e.name == "name"    && recvTypeCoreKtc is KtcType.User && recvTypeCoreKtc.kind == KtcType.UserKind.Enum) return KtcType.Str
	if (e.name == "ordinal" && recvTypeCoreKtc is KtcType.User && recvTypeCoreKtc.kind == KtcType.UserKind.Enum) return KtcType.Prim(KtcType.PrimKind.Int)
	// Full-enum ctor-param / body-property type lookup
	if (recvTypeCoreKtc is KtcType.User && recvTypeCoreKtc.kind == KtcType.UserKind.Enum) {
		val vEi = enums[recvTypeCoreKtc.baseName]
		if (vEi != null && !vEi.isSimple) {
			val vProp = (vEi.ctorParams + vEi.bodyProps).find { it.name == e.name }
			if (vProp != null) {
				val vKtc = resolveTypeName(vProp.typeRef)
				return if (vProp.typeRef.nullable) KtcType.Nullable(vKtc) else vKtc
				}
			}
		}
	val indirectBase = (recvTypeCoreKtc as? KtcType.Ptr)?.inner?.let { it as? KtcType.User }?.baseName
	if (indirectBase != null) {
		val ci = classes[indirectBase] ?: return null
		return resolvePropTypeKtc(ci.props, e.name)
		}
	// Strip a trailing `?` so `Path?.s` resolves to Path's `s` property.
	// `?.` codegen passes us the DotExpr wrapper of a nullable receiver and
	// expects us to dig out the underlying class's field type — without
	// removing the suffix we'd miss the class lookup and fall back to Int.
	val ci = classes[recvType.removeSuffix("?")] ?: return null
	return resolvePropTypeKtc(ci.props, e.name)
	}

internal fun CCodeGen.inferDotTypeSafe(e: SafeDotExpr): String? {
	val base = inferDotTypeKtc(DotExpr(e.obj, e.name)) ?: return null
	return if (base is KtcType.Nullable) base.toInternalStr else KtcType.Nullable(base).toInternalStr
	}

internal fun CCodeGen.inferIndexType(e: IndexExpr): String? {
	val tRaw     = inferExprType(e.obj) ?: return null
	val tKtc     = inferExprTypeKtc(e.obj)
	val tKtcCore = tKtc.stripNullable
	val t = tRaw.removeSuffix("?")
	if (t == "String") return "Char"
	if (classes.containsKey(t)) {
		val methodDecl = classes[t]?.methods?.find { it.name == "get" && it.isOperator }
		if (methodDecl?.returnType != null) return resolveMethodReturnType(t, methodDecl.returnType)
		}
	val indirectBase = (tKtcCore as? KtcType.Ptr)?.inner?.let { it as? KtcType.User }?.baseName
	if (indirectBase != null && classes.containsKey(indirectBase)) {
		val methodDecl = classes[indirectBase]?.methods?.find { it.name == "get" && it.isOperator }
		if (methodDecl?.returnType != null) return resolveMethodReturnType(indirectBase, methodDecl.returnType)
		}
	if (interfaces.containsKey(t)) {
		val ifaceInfo   = interfaces[t]
		val ifaceMethod = ifaceInfo?.methods?.find { it.name == "get" && it.isOperator }
			?: collectAllIfaceMethods(ifaceInfo!!).find { it.name == "get" && it.isOperator }
		if (ifaceMethod?.returnType != null) return resolveMethodReturnType(t, ifaceMethod.returnType)
		}
	if (tKtcCore is KtcType.Ptr && tKtcCore.inner !is KtcType.Arr) return tKtcCore.inner.toInternalStr
	return tKtcCore?.let { arrayElementKtTypeKtc(it) } ?: "Int"
	}
