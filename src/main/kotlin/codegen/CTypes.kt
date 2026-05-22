package com.bitsycore.ktc.codegen

import com.bitsycore.ktc.ast.TypeRef
import com.bitsycore.ktc.ast.getSizeAnnotation
import com.bitsycore.ktc.types.BuiltinTypeDef
import com.bitsycore.ktc.types.KtcType
import com.bitsycore.ktc.types.TypeDef

// Core C type resolution: TypeRef → KtcType → C type string.
// Parameter expansion lives in CTypesParams.kt.
// Sized-array/printf helpers live in CTypesSized.kt.

// ═══════════════════════════ KtcType utilities ════════════════════

/* Strip the Nullable wrapper if present; returns the inner type (or the original when not nullable). */
internal val KtcType.stripNullable: KtcType get() = (this as? KtcType.Nullable)?.inner ?: this
@get:JvmName("stripNullableOrNull")
internal val KtcType?.stripNullable: KtcType? get() = (this as? KtcType.Nullable)?.inner ?: this

// ═══════════════════════════ TypeRef utilities ════════════════════

/* Recursively substitute type parameters throughout a TypeRef tree. */
internal fun SymbolReader.substituteTypeParams(t: TypeRef): TypeRef {
	if (typeSubst.isEmpty()) return t
	val rawNewName = typeSubst[t.name] ?: t.name                 // substituted name (may carry '?')
	val hasNullableSuffix = rawNewName.endsWith("?")
	val newName = if (hasNullableSuffix) rawNewName.dropLast(1) else rawNewName
	val newTypeArgs     = t.typeArgs.map { substituteTypeParams(it) }
	val newFuncParams   = t.funcParams?.map { substituteTypeParams(it) }
	val newFuncReturn   = t.funcReturn?.let { substituteTypeParams(it) }
	val newFuncReceiver = t.funcReceiver?.let { substituteTypeParams(it) }
	val newNullable     = t.nullable || hasNullableSuffix
	return if (
		newName != t.name || newNullable != t.nullable
		|| newTypeArgs != t.typeArgs || newFuncParams != t.funcParams
		|| newFuncReturn != t.funcReturn || newFuncReceiver != t.funcReceiver
		)
		TypeRef(newName, newNullable, newTypeArgs, newFuncParams, newFuncReturn, newFuncReceiver, t.annotations)
	else t
	}

/* Human-readable TypeRef string (debug / error messages). */
internal fun SymbolReader.typeRefToStr(t: TypeRef?): String {
	if (t == null) return "Unit"
	val ann      = if (t.annotations.any { it.name == "Ptr" }) "@Ptr " else ""
	val args     = if (t.typeArgs.isNotEmpty()) "<${t.typeArgs.joinToString(", ") { typeRefToStr(it) }}>" else ""
	val nullable = if (t.nullable) "?" else ""
	return "$ann${t.name}$args$nullable"
	}

// ═══════════════════════════ resolveTypeName ══════════════════════

/*
Primary entry point: resolve a TypeRef to a KtcType.
Handles function types directly; bridges through resolveTypeNameStr for all others.
*/
internal fun CCodeGen.resolveTypeName(inT: TypeRef?): KtcType {
	if (inT == null) return KtcType.Prim(KtcType.PrimKind.Int) // default to Int for null TypeRef
	val vSubstituted = substituteTypeParams(inT)                 // apply type-param substitutions
	// Function types: build KtcType.Func directly to preserve param info and receiver
	if (vSubstituted.funcParams != null) {
		val vReceiver = vSubstituted.funcReceiver?.let { resolveTypeName(it) } // extension receiver or null
		val vParams   = vSubstituted.funcParams.map { resolveTypeName(it) }    // non-receiver params
		val vRet      = resolveTypeName(vSubstituted.funcReturn)               // return type
		return KtcType.Func(vParams, vRet, receiver = vReceiver)
		}
	val vResolved = resolveTypeNameStr(inT)                      // string-based resolution (legacy bridge)
	val base      = parseResolvedTypeName(vResolved, inT)
	// Preserve nullability from type substitution (e.g. T→Int? produces nullable=true on vSubstituted)
	val isSubstNullable = vSubstituted.nullable && !inT.nullable
	return if (isSubstNullable && base !is KtcType.Ptr && base !is KtcType.Nullable) KtcType.Nullable(base) else base
	}

/* Resolve a TypeRef to an internal string, wrapping in nullable notation when typeRef.nullable is true. */
internal fun CCodeGen.resolveTypeRefStr(typeRef: TypeRef): String {
	val base = resolveTypeName(typeRef)
	return if (typeRef.nullable) KtcType.Nullable(base).toInternalStr else base.toInternalStr
	}

/* Resolve a TypeRef to its internal string type name (legacy bridge). */
internal fun CCodeGen.resolveTypeNameStr(t: TypeRef?): String {
	if (t == null) return "Int"
	val vSubstituted = substituteTypeParams(t)                   // type-param-substituted copy
	// RawArray<T> → T* (raw pointer, no $len companion)
	if (vSubstituted.name == "RawArray" && vSubstituted.typeArgs.isNotEmpty())
		return resolveTypeNameStr(vSubstituted.typeArgs[0]) + "*"
	val vBase = resolveTypeNameInnerStr(vSubstituted)            // raw resolved name
	val vIsPtr = vSubstituted.annotations.any { it.name == "Ptr" }
	if (vIsPtr) {
		// @Ptr InterfaceType → ktc_IfacePtr (trampoline value, carries vt+obj)
		if (interfaces.containsKey(vSubstituted.name)) {
			if (vSubstituted.typeArgs.isNotEmpty()) return "ktc_IfacePtr:${vBase}"
			return "ktc_IfacePtr"
			}
		return "${vBase}*"
		}
	return vBase
	}

/* Internal string-based type resolution after @Ptr stripping (legacy bridge). */
internal fun CCodeGen.resolveTypeNameInnerStr(t: TypeRef): String {
	// c.SDL_Window → "c:SDL_Window" (C interop external type passthrough)
	if (t.name.startsWith("c.")) return "c:${t.name.removePrefix("c.")}"
	// Function type: (P1, P2) -> R → "Fun(P1,P2)->R"
	if (t.funcParams != null) {
		val vReceiver = t.funcReceiver?.let { resolveTypeNameStr(it) + "|" } ?: ""
		val vParams   = t.funcParams.joinToString(",") { resolveTypeNameStr(it) }
		val vRet      = resolveTypeNameStr(t.funcReturn)
		return "Fun($vReceiver$vParams)->$vRet"
		}
	// Nested class: Outer.Inner → Outer$Inner
	if (t.name.contains('.') && !t.name.startsWith("ktc_")) {
		val vFlatName = t.name.replace('.', '$')
		if (classes.containsKey(vFlatName) || genericClassDecls.containsKey(vFlatName) || interfaces.containsKey(vFlatName)) {
			val vSynthetic = TypeRef(vFlatName, t.nullable, t.typeArgs, t.funcParams, t.funcReturn, t.funcReceiver, t.annotations)
			return resolveTypeNameInnerStr(vSynthetic)
			}
		}
	// User-defined generic class takes priority over built-in aliases
	if (t.typeArgs.isNotEmpty() && classes.containsKey(t.name) && classes[t.name]!!.isGeneric) {
		val vTypeArgNames = t.typeArgs.map { typeRef ->
			if (typeRef.nullable) "${resolveTypeNameStr(typeRef)}?" else resolveTypeNameStr(typeRef)
			}
		if (vTypeArgNames.any { it in allGenericTypeParamNames })
			return mangledGenericName(t.name, vTypeArgNames)
		return recordGenericInstantiation(t.name, vTypeArgNames)
		}
	// User-defined generic interface takes priority over built-in aliases
	if (t.typeArgs.isNotEmpty() && genericIfaceDecls.containsKey(t.name)) {
		val vTypeArgNames = t.typeArgs.map { resolveTypeNameStr(it) }
		return mangledGenericName(t.name, vTypeArgNames)
		}
	if (t.name == "AnyPtr" && t.typeArgs.isEmpty()) return "void*"
	if (t.name == "StringBuffer" && t.typeArgs.isEmpty()
		&& !classes.containsKey("StringBuffer") && !genericClassDecls.containsKey("StringBuffer"))
		return "ktc_StrBuf"
	if (t.name == "Array" && t.typeArgs.isNotEmpty()) {
		val vElemRef      = t.typeArgs[0]
		val vNullableElem = vElemRef.nullable
		return if (vNullableElem) primitiveToArrayOptionalType(vElemRef.name) else primitiveToArrayType(vElemRef.name)
		}
	if (t.name == "RawArray" && t.typeArgs.isNotEmpty())
		return resolveTypeNameStr(t.typeArgs[0]) + "*"
	if (t.typeArgs.isNotEmpty()
		&& !classes.containsKey(t.name) && !interfaces.containsKey(t.name)
		&& !genericClassDecls.containsKey(t.name) && !genericIfaceDecls.containsKey(t.name)
		&& t.name !in setOf("Array", "RawArray", "AnyPtr"))
		codegenError("Unknown type '${t.name}<...>'. Use @Ptr for pointer types.")
	// Resolve nested class within current object/class scope (e.g. Context → Sha256$Context)
	if (!classes.containsKey(t.name) && !enums.containsKey(t.name)
		&& !interfaces.containsKey(t.name) && !genericClassDecls.containsKey(t.name)) {
		val vParent = currentObject ?: currentClass
		if (vParent != null) {
			val vNestedName = "$vParent$${t.name}"
			if (classes.containsKey(vNestedName) || genericClassDecls.containsKey(vNestedName))
				return vNestedName
			}
		for (vObj in objects.keys) {
			val vNestedName = "$vObj$${t.name}"
			if (classes.containsKey(vNestedName) || genericClassDecls.containsKey(vNestedName))
				return vNestedName
			}
		}
	return t.name
	}

// ═══════════════════════════ KtcType constructors ══════════════════

/* Create KtcType.User by looking up the TypeDef from symbol tables, or creating a BuiltinTypeDef. */
internal fun CCodeGen.userType(inName: String, inKind: KtcType.UserKind = KtcType.UserKind.Class): KtcType.User {
	val vTypeDef: TypeDef = when {
		classes.containsKey(inName)    -> classes[inName]!!
		objects.containsKey(inName)    -> objects[inName]!!
		interfaces.containsKey(inName) -> interfaces[inName]!!
		enums.containsKey(inName)      -> enums[inName]!!
		else -> {
			val vFullPfx = typeFlatName(inName)
			val vPkg     = if (vFullPfx.endsWith(inName)) vFullPfx.removeSuffix(inName) else ""
			BuiltinTypeDef(baseName = inName, pkg = vPkg, kind = inKind)
			}
		}
	return KtcType.User(vTypeDef)
	}

internal fun CCodeGen.parseResolvedTypeName(resolved: String, t: TypeRef? = null): KtcType {
	if (resolved.startsWith("ktc_IfacePtr")) {
		val concreteName = when {
			resolved.startsWith("ktc_IfacePtr:") && resolved.length > "ktc_IfacePtr:".length ->
				resolved.substring("ktc_IfacePtr:".length)
			t != null && interfaces.containsKey(t.name) && t.typeArgs.isNotEmpty() ->
				mangledGenericName(t.name, t.typeArgs.map { resolveTypeNameInnerStr(it) })
			else -> t?.name ?: "Any"
			}
		return KtcType.Ptr(userType(concreteName, KtcType.UserKind.Interface))
		}
	if (resolved.endsWith("*?")) {
		val base = resolved.dropLast(2)
		return if (base.endsWith("Array")) KtcType.Nullable(parseResolvedTypeName(base, t))
		else KtcType.Nullable(KtcType.Ptr(parseResolvedTypeName(base, t)))
		}
	if (resolved.endsWith("*")) {
		val base = resolved.dropLast(1)
		if (base.endsWith("Array")) return parseResolvedTypeName(base, t)
		if (base.startsWith("ktc_IfacePtr")) return parseResolvedTypeName(base, t)
		return KtcType.Ptr(parseResolvedTypeName(base, t))
		}
	if (resolved.endsWith("?")) return KtcType.Nullable(parseResolvedTypeName(resolved.dropLast(1)))
	if (resolved.startsWith("Fun(")) return KtcType.Func(emptyList(), KtcType.Void)
	// "c:SDL_Window" → COpaque (produced by resolveTypeNameInnerStr for c.* type refs)
	if (resolved.startsWith("c:")) return KtcType.COpaque(resolved.removePrefix("c:"))
	for (kind in KtcType.PrimKind.entries) {
		if (resolved == kind.name) return KtcType.Prim(kind)
		}
	if (resolved == "String")   return KtcType.Str
	if (resolved == "void" || resolved == "Nothing" || resolved == "Unit") return KtcType.Void
	if (resolved == "Any")      return KtcType.Any
	if (resolved == "ktc_StrBuf") return userType("ktc_StrBuf")
	// Optional arrays: IntOptArray → Ptr(Arr(Nullable(elem)))
	if (resolved.endsWith("OptArray")) {
		val elemName = resolved.removeSuffix("OptArray")
		return KtcType.Ptr(KtcType.Arr(KtcType.Nullable(parseResolvedTypeName(elemName))))
		}
	// Array types: IntArray, ByteArray, Vec2Array, etc.
	if (resolved.endsWith("Array") && resolved.length > 5) {
		val elemName = resolved.removeSuffix("Array")
		val elem = when (elemName) {
			in KtcType.PrimKind.entries.map { it.name } -> KtcType.Prim(KtcType.PrimKind.valueOf(elemName))
			"String" -> KtcType.Str
			else -> userType(elemName)
			}
		val sized         = t?.getSizeAnnotation()
		val isTypedArray  = elemName in KtcType.PrimKind.entries.map { it.name } || elemName == "String"
			|| classArrayTypes.contains(elemName) || enums.containsKey(elemName)
		val arr = KtcType.Arr(elem, sized = sized)
		return if (isTypedArray) KtcType.Ptr(arr) else arr
		}
	if (classes.containsKey(resolved) || objects.containsKey(resolved) || interfaces.containsKey(resolved)) {
		val kind = when {
			classes[resolved]?.isData == true -> KtcType.UserKind.DataClass
			objects.containsKey(resolved)     -> KtcType.UserKind.Object
			enums.containsKey(resolved)       -> KtcType.UserKind.Enum
			interfaces.containsKey(resolved)  -> KtcType.UserKind.Interface
			else                              -> KtcType.UserKind.Class
			}
		return userType(resolved, kind)
		}
	return userType(resolved)
	}

// ═══════════════════════════ C type strings ════════════════════════

internal fun CCodeGen.cTypeStr(t: String): String {
	if (t == "ktc_IfacePtr" || t.startsWith("ktc_IfacePtr:")) return "ktc_IfacePtr"
	return cTypeStr(parseResolvedTypeName(t))
	}

/* C type string from KtcType, uses pfx for user types. */
internal fun SymbolReader.cTypeStr(ktc: KtcType): String = when (ktc) {
	is KtcType.Prim -> ktc.toCType()
	is KtcType.Str  -> ktc.toCType()
	is KtcType.Void -> ktc.toCType()
	is KtcType.Any  -> ktc.toCType()
	is KtcType.User -> when (ktc.baseName) {
		"Any"       -> "ktc_Any"
		"ktc_StrBuf" -> "ktc_StrBuf"
		"AnyPtr"    -> "void*"
		else        -> ktc.toCType()
		}
	is KtcType.Ptr -> {
		if (ktc.inner is KtcType.Arr) {
			"${elemCTypeStr(ktc.inner.elem)}*"
			} else if (ktc.inner is KtcType.User && ktc.inner.kind == KtcType.UserKind.Interface) {
			"ktc_IfacePtr"
			} else "${cTypeStr(ktc.inner)}*"
		}
	is KtcType.Arr -> {
		val elem = cTypeStr(ktc.elem)
		if (ktc.sized != null) elem else "ktc_ArrayTrampoline"
		}
	is KtcType.Nullable -> {
		val inner = ktc.inner
		if (inner is KtcType.Ptr) cTypeStr(inner) else optCTypeName(inner.toInternalStr)
		}
	is KtcType.Func -> "void*"
	is KtcType.COpaque -> ktc.cName
	}

/* C type string for an array element — handles nullable elements as Optional types. */
internal fun SymbolReader.elemCTypeStr(elemKtc: KtcType): String =
	if (elemKtc is KtcType.Nullable) optCTypeName(elemKtc.inner.toInternalStr) else cTypeStr(elemKtc)

internal fun CCodeGen.defaultVal(t: KtcType): String = when (t) {
	is KtcType.Prim -> when (t.kind) {
		KtcType.PrimKind.Int, KtcType.PrimKind.Long -> "0"
		KtcType.PrimKind.Float   -> "0.0f"
		KtcType.PrimKind.Double  -> "0.0"
		KtcType.PrimKind.Boolean -> "false"
		KtcType.PrimKind.Char    -> "'\\0'"
		else                     -> "0"
		}
	is KtcType.Str  -> "ktc_core_str(\"\")"
	is KtcType.Ptr    -> "NULL"
	is KtcType.COpaque -> "(${t.cName}){0}"
	else -> {
		val ct = cTypeStr(t.toInternalStr.removeSuffix("?"))
		"($ct){0}"
		}
	}

// ═══════════════════════════ printf helpers ════════════════════════

/* KtcType → printf format specifier string (e.g. Int → "%\" PRId32 \""). */
internal fun SymbolReader.printfFmt(ktc: KtcType): String = when (ktc) {
	is KtcType.Prim -> when (ktc.kind) {
		KtcType.PrimKind.Byte    -> "%\" PRId8 \""
		KtcType.PrimKind.Short   -> "%\" PRId16 \""
		KtcType.PrimKind.Int     -> "%\" PRId32 \""
		KtcType.PrimKind.Long    -> "%\" PRId64 \""
		KtcType.PrimKind.Float   -> "%f"
		KtcType.PrimKind.Double  -> "%f"
		KtcType.PrimKind.Boolean -> "%s"
		KtcType.PrimKind.Char    -> "%c"
		KtcType.PrimKind.Rune    -> "%\" PRId32 \""
		KtcType.PrimKind.UByte   -> "%\" PRIu8 \""
		KtcType.PrimKind.UShort  -> "%\" PRIu16 \""
		KtcType.PrimKind.UInt    -> "%\" PRIu32 \""
		KtcType.PrimKind.ULong   -> "%\" PRIu64 \""
		}
	is KtcType.Str      -> "%.*s"
	is KtcType.Ptr      -> "%p"
	is KtcType.Nullable -> if (ktc.inner is KtcType.Ptr) "%p" else printfFmt(ktc.inner)
	is KtcType.User     -> "%.*s"
	is KtcType.COpaque  -> "%p"
	else                -> "%.*s"
	}

/* KtcType → printf argument expression (e.g. String → "len, ptr" pair). */
internal fun SymbolReader.printfArg(expr: String, ktc: KtcType): String = when (ktc) {
	is KtcType.Prim if ktc.kind == KtcType.PrimKind.Boolean -> "($expr) ? \"true\" : \"false\""
	is KtcType.Str -> "(ktc_Int)($expr).len, ($expr).ptr"
	is KtcType.User if ktc.kind == KtcType.UserKind.Enum -> {
		val cName = typeFlatName(ktc.baseName)
		"(ktc_Int)${cName}_names[($expr)].len, ${cName}_names[($expr)].ptr"
		}
	else -> expr
	}
