package com.bitsycore.ktc.codegen.emit

import com.bitsycore.ktc.ast.*
import com.bitsycore.ktc.codegen.*
import com.bitsycore.ktc.codegen.expr.*
import com.bitsycore.ktc.types.KtcType

// Any-protocol implementations: equals, toString, hashCode, vtable, and hash helpers.

internal fun CCodeGen.emitClassEquals(cName: String, ci: ClassInfo) {
	hdr.appendLine("KTC_METHOD(ktc_Bool, equals)(KTC_TYPE_NAME a, KTC_TYPE_NAME b);")
	impl.appendLine("// ══ fun equals ══")
	impl.appendLine("ktc_Bool ${cName}_equals($cName a, $cName b) {")
	val eqs = ci.props.filter { (_, type) ->
		!(type.annotations.any { it.name == "Ptr" } && interfaces.containsKey(type.name))
		}.joinToString(" && ") { (name, type) ->
		val fieldName = if (name in ci.privateProps) "PRIV_$name" else name
		val vKtcEq    = resolveTypeName(type)
		val vTStr     = vKtcEq.toInternalStr
		when {
			type.nullable -> {
				val vInnerName = type.name
				val vValueCmp = when {
					vInnerName == "String" -> "ktc_core_string_eq(KTC_UNWRAP(a.$fieldName), KTC_UNWRAP(b.$fieldName))"
					classes[vInnerName]?.isData == true -> "${typeFlatName(vInnerName)}_equals(KTC_UNWRAP(a.$fieldName), KTC_UNWRAP(b.$fieldName))"
					else -> "KTC_UNWRAP(a.$fieldName) == KTC_UNWRAP(b.$fieldName)"
					}
				"(KTC_IS_SOME(a.$fieldName) == KTC_IS_SOME(b.$fieldName) && (KTC_IS_NONE(a.$fieldName) || $vValueCmp))"
				}
			vTStr == "String" -> "ktc_core_string_eq(a.$fieldName, b.$fieldName)"
			classes[vTStr]?.isData == true -> "${typeFlatName(vTStr)}_equals(a.$fieldName, b.$fieldName)"
			vKtcEq.isArrayLike && vKtcEq.asArr?.sized == null -> "a.$fieldName.ptr == b.$fieldName.ptr"
			else -> "a.$fieldName == b.$fieldName"
			}
		}
	impl.appendLine("    return ${eqs.ifEmpty { "true" }};")
	impl.appendLine("}")
	impl.appendLine()
	}

internal fun CCodeGen.emitDataClassToString(ktName: String, cName: String, ci: ClassInfo) {
	val maxLen     = toStringMaxLen(ci.name)
	val maxComment = if (maxLen != null) " // max output: $maxLen chars" else ""
	hdr.appendLine("KTC_METHOD(void, toString)(KTC_TYPE_NAME* \$self, ktc_StrBuf* sb);${maxComment}")
	impl.appendLine("// ══ fun toString() ══")
	impl.appendLine("void ${cName}_toString($cName* \$self, ktc_StrBuf* sb) {")
	for ((i, prop) in ci.props.withIndex()) {
		val (name, type) = prop
		val fieldName = if (name in ci.privateProps) "PRIV_$name" else name
		val vKtcTs    = resolveTypeName(type)
		val tFull     = if (type.nullable) vKtcTs.nullable else vKtcTs
		val prefix    = if (i == 0) "$ktName($name=" else ", $name="
		impl.appendLine("    ktc_core_sb_append_str(sb, ktc_core_str(\"$prefix\"));")
		impl.appendLine("    ${genSbAppendKtc("sb", "\$self->$fieldName", tFull)}")
		}
	impl.appendLine("    ktc_core_sb_append_char(sb, ')');")
	impl.appendLine("}")
	impl.appendLine()
	}

/** Emit implicit hashCode. Uses field-based hash for data classes, identity hash otherwise. */
internal fun CCodeGen.emitImplicitHashCode(
	cName:          String,
	ci:             ClassInfo,
	isData:         Boolean,
	isGenericClass: Boolean,
	members:        List<Decl>
	) {
	if (members.any { it is FunDecl && it.name == "hashCode" }) return
	hdr.appendLine("KTC_METHOD(ktc_Int, hashCode)(KTC_TYPE_NAME* \$self);")
	impl.appendLine("// ══ fun hashCode(): Int ══")
	impl.appendLine("ktc_Int ${cName}_hashCode($cName* \$self) {")
	if (isData && ci.props.isNotEmpty()) {
		impl.appendLine("    ktc_Int h = 0;")
		for ((name, type) in ci.props) {
			val vKtcHash   = resolveTypeName(type)
			val fieldName  = if (name in ci.privateProps) "PRIV_$name" else name
			val hashExpr   = if (type.nullable && vKtcHash !is KtcType.Ptr) {
				val valueExpr = "\$self->$fieldName"
				"(KTC_IS_SOME($valueExpr) ? ${hashFieldExprKtc(vKtcHash, "KTC_UNWRAP($valueExpr)")} : 0)"
				} else {
				hashFieldExprKtc(vKtcHash, "\$self->$fieldName")
				}
			impl.appendLine("    h = h * 31 + $hashExpr;")
			}
		impl.appendLine("    return h;")
		} else if (isGenericClass) {
		impl.appendLine("    uintptr_t p = (uintptr_t)\$self; p >>= 4;")
		impl.appendLine("    ktc_UInt lo = (ktc_UInt)p;")
		impl.appendLine("    ktc_UInt hi = (ktc_UInt)(p >> 32);")
		impl.appendLine("    ktc_UInt t = (ktc_UInt)\$self->__base.typeId * 0x9e3779b1U;")
		impl.appendLine("    ktc_UInt h = lo ^ hi ^ t;")
		impl.appendLine("    h = ktc_core_fmix32(h);")
		impl.appendLine("    return (ktc_Int)h;")
		} else {
		impl.appendLine("    uintptr_t x = (uintptr_t)\$self;")
		impl.appendLine("    return (ktc_Int)(x ^ (x >> 32));")
		}
	impl.appendLine("}")
	impl.appendLine()
	}

/** Emit default toString for non-data classes: ClassName@hexHashCode */
internal fun CCodeGen.emitDefaultToString(ktName: String, cName: String, ci: ClassInfo) {
	val maxLen     = toStringMaxLen(ci.name)
	val maxComment = if (maxLen != null) " // max output: $maxLen chars" else ""
	hdr.appendLine("KTC_METHOD(void, toString)(KTC_TYPE_NAME* \$self, ktc_StrBuf* sb);${maxComment}")
	impl.appendLine("// ══ fun toString() ══")
	impl.appendLine("void ${cName}_toString($cName* \$self, ktc_StrBuf* sb) {")
	if (maxLen != null && maxLen <= 64) {
		impl.appendLine("    ktc_Char buf[$maxLen];")
		impl.appendLine("    snprintf(buf, $maxLen, \"%s@%x\", \"${ktDisplayName(ktName)}\", ${cName}_hashCode(\$self));")
		impl.appendLine("    ktc_core_sb_append_cstr(sb, buf);")
		} else {
		impl.appendLine("    ktc_Char buf[64];")
		impl.appendLine("    snprintf(buf, 64, \"%s@%x\", \"${ktDisplayName(ktName)}\", ${cName}_hashCode(\$self));")
		impl.appendLine("    ktc_core_sb_append_cstr(sb, buf);")
		}
	impl.appendLine("}")
	impl.appendLine()
	}

/*
Emit Any vtable + _as_Any wrapper for a class.
Generates thin wrapper functions (void* → ClassName*) for vtable dispatch,
a static ktc_core_AnyVt, and a ClassName_as_Any function.
*/
internal fun CCodeGen.emitAnyVtable(
	cName:          String,
	className:      String,
	isData:         Boolean,
	members:        List<Decl>,
	isGenericClass: Boolean
	) {
	impl.appendLine(boxSection("cast to Any"))
	impl.appendLine()
	impl.appendLine("static void ${cName}_toString_any(void* \$self, ktc_StrBuf* sb) {")
	impl.appendLine("    ${cName}_toString(($cName*)\$self, sb);")
	impl.appendLine("}")
	impl.appendLine()
	impl.appendLine("static ktc_Int ${cName}_hashCode_any(void* \$self) {")
	impl.appendLine("    return ${cName}_hashCode(($cName*)\$self);")
	impl.appendLine("}")
	impl.appendLine()
	impl.appendLine("static ktc_Bool ${cName}_equals_any(void* \$self, void* other) {")
	impl.appendLine("    return ${cName}_equals(*($cName*)\$self, *($cName*)other);")
	impl.appendLine("}")
	impl.appendLine()
	if (members.none { it is FunDecl && it.name == "dispose" }) {
		impl.appendLine("static void ${cName}_dispose_any(void* \$self) {")
		if (disposedMode != "NO" || doubleDisposeMode != "NO")
			impl.appendLine("    KTC_MARK_DISPOSED(($cName*)\$self);")
		else
			impl.appendLine("    (void)\$self;")
		impl.appendLine("}")
		} else {
		impl.appendLine("static void ${cName}_dispose_any(void* \$self) {")
		impl.appendLine("    ${cName}_dispose(($cName*)\$self);")
		impl.appendLine("}")
		}
	impl.appendLine()
	impl.appendLine("static void* ${cName}_copyWith_any(void* \$self, void* alloc) {")
	impl.appendLine("    ktc_std_Allocator* a = (ktc_std_Allocator*)alloc;")
	impl.appendLine("    $cName* dst = ($cName*)a->vt->allocMem(a, sizeof($cName), ${ktSrcStr()});")
	impl.appendLine("    if (dst) *dst = *($cName*)\$self;")
	impl.appendLine("    return dst;")
	impl.appendLine("}")
	impl.appendLine()
	hdr.appendLine("extern const ktc_core_AnyVt KTC_RELATED(AnyVt);")
	impl.appendLine("const ktc_core_AnyVt ${cName}_AnyVt = {")
	impl.appendLine("    (void (*)(void*, void*)) ${cName}_toString_any,")
	impl.appendLine("    (ktc_Int (*)(void*)) ${cName}_hashCode_any,")
	impl.appendLine("    (ktc_Bool (*)(void*, void*)) ${cName}_equals_any,")
	impl.appendLine("    (void (*)(void*)) ${cName}_dispose_any,")
	impl.appendLine("    (void* (*)(void*, void*)) ${cName}_copyWith_any,")
	impl.appendLine("};")
	impl.appendLine()
	hdr.appendLine("KTC_METHOD(ktc_Any, as_Any)(KTC_TYPE_NAME* \$self);")
	impl.appendLine("ktc_Any ${cName}_as_Any($cName* \$self) {")
	impl.appendLine("    return (ktc_Any){{.typeId = ${cName}_TYPE_ID}, (void*)\$self, &${cName}_AnyVt};")
	impl.appendLine("}")
	impl.appendLine()
	}

internal fun CCodeGen.hashFieldExprKtc(ktc: KtcType, valueExpr: String): String = when (ktc) {
	is KtcType.Nullable if isValueNullableKtc(ktc) ->
		"(KTC_IS_SOME($valueExpr) ? ${hashFieldExprKtc(ktc.inner, "KTC_UNWRAP($valueExpr)")} : 0)"
	is KtcType.Prim -> when (ktc.kind) {
		KtcType.PrimKind.Byte    -> "ktc_core_hash_i8($valueExpr)"
		KtcType.PrimKind.Short   -> "ktc_core_hash_i16($valueExpr)"
		KtcType.PrimKind.Int     -> "ktc_core_hash_i32($valueExpr)"
		KtcType.PrimKind.Long    -> "ktc_core_hash_i64($valueExpr)"
		KtcType.PrimKind.Float   -> "ktc_core_hash_f32($valueExpr)"
		KtcType.PrimKind.Double  -> "ktc_core_hash_f64($valueExpr)"
		KtcType.PrimKind.Boolean -> "ktc_core_hash_bool($valueExpr)"
		KtcType.PrimKind.Char    -> "ktc_core_hash_char($valueExpr)"
		KtcType.PrimKind.UByte   -> "ktc_core_hash_u8($valueExpr)"
		KtcType.PrimKind.UShort  -> "ktc_core_hash_u16($valueExpr)"
		KtcType.PrimKind.UInt    -> "ktc_core_hash_u32($valueExpr)"
		KtcType.PrimKind.ULong   -> "ktc_core_hash_u64($valueExpr)"
		KtcType.PrimKind.Rune    -> "ktc_core_hash_i32($valueExpr)"
		}
	is KtcType.Str -> "ktc_core_hash_str($valueExpr)"
	is KtcType.Ptr -> "((ktc_Int)(uintptr_t)($valueExpr))"
	is KtcType.User, is KtcType.Arr, is KtcType.Nullable -> "($valueExpr).__base.typeId"
	else -> "($valueExpr).__base.typeId"
	}
