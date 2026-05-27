package com.bitsycore.ktc.codegen.emit

import com.bitsycore.ktc.ast.EnumDecl
import com.bitsycore.ktc.ast.FunDecl
import com.bitsycore.ktc.ast.isRefType
import com.bitsycore.ktc.codegen.*
import com.bitsycore.ktc.codegen.expression.genExpr
import com.bitsycore.ktc.codegen.expression.inferBlockType

// Enum emission — two paths:
//   simple path: C `enum { TAG, ... }` of integer tags + names[]/values[]/valueOf() helpers
//   full path:   struct with ctor-param fields + ordinal + name, plus extern const per entry

internal fun CCodeGen.emitEnum(d: EnumDecl) {
	val ei = enums[d.name]!!
	if (ei.isSimple) emitSimpleEnum(d, ei) else emitFullEnum(d, ei)
	}

// ──────────────────────────────────────────────────────────────────
// MARK: Simple enum (C int)
// ──────────────────────────────────────────────────────────────────

private fun CCodeGen.emitSimpleEnum(d: EnumDecl, ei: EnumInfo) {
	val cName = ei.flatName
	val n     = d.entries.size
	hdr.appendLine(classBlockHeader("enum class", d.name, emptyList(), emptyList(), file.pkg ?: "", currentSourceFile, cName))
	hdr.appendLine("#define KTC_TYPE_NAME $cName")
	hdr.appendLine()
	hdr.appendLine("KTC_ENUM(")
	for ((i, entry) in d.entries.withIndex()) {
		hdr.append("    KTC_RELATED(${entry.name})")
		if (i < d.entries.lastIndex) hdr.append(",")
		hdr.appendLine()
		}
	hdr.appendLine(");")
	hdr.appendLine()
	hdr.appendLine("extern const ktc_String KTC_RELATED(names[$n]);")
	hdr.appendLine("extern const KTC_TYPE_NAME KTC_RELATED(values[$n]);")
	hdr.appendLine("extern const ktc_Int KTC_RELATED(values\$len);")
	hdr.appendLine("KTC_METHOD(KTC_TYPE_NAME, valueOf)(ktc_String name);")
	hdr.appendLine()
	hdr.appendLine("#undef KTC_TYPE_NAME")
	hdr.appendLine(classBlockFooter("enum class", d.name, emptyList()))
	val nameInits = d.entries.joinToString(", ") { "ktc_core_str(\"${it.name}\")" }
	impl.appendLine(boxSection("names"))
	impl.appendLine()
	impl.appendLine("const ktc_String ${cName}_names[$n] = {$nameInits};")
	impl.appendLine()
	}

// ──────────────────────────────────────────────────────────────────
// MARK: Full enum (struct with ctor-param fields + ordinal + name)
// ──────────────────────────────────────────────────────────────────

private fun CCodeGen.emitFullEnum(d: EnumDecl, ei: EnumInfo) {
	val cName = ei.flatName
	val n     = d.entries.size

	hdr.appendLine(classBlockHeader("enum class", d.name, emptyList(), emptyList(), file.pkg ?: "", currentSourceFile, cName))
	hdr.appendLine("#define KTC_TYPE_NAME $cName")
	hdr.appendLine("KTC_TYPE_ID(${typeIds[d.name]!!})")
	hdr.appendLine()
	// Struct body: ctor-param fields, body-stored fields, then ordinal/name.
	hdr.appendLine("typedef struct $cName {")
	for (p in ei.ctorParams) {
		val vKtc = resolveTypeName(p.typeRef)
		val vMutComment = if (p.isVal) "/*VAL*/ " else "/*VAR*/ "
		hdr.appendLine("    $vMutComment${cTypeStr(vKtc)} ${p.name};")
		}
	for (p in ei.bodyProps) {
		if (p.getter != null) continue   // computed property: no backing field
		val vKtc = resolveTypeName(p.typeRef)
		val vMutComment = if (p.isVal) "/*VAL*/ " else "/*VAR*/ "
		hdr.appendLine("    $vMutComment${cTypeStr(vKtc)} ${p.name};")
		}
	hdr.appendLine("    ktc_Int    ordinal;")
	hdr.appendLine("    ktc_String name;")
	hdr.appendLine("} $cName;")
	hdr.appendLine()

	for (entry in d.entries)
		hdr.appendLine("extern const $cName ${cName}_${entry.name};")
	hdr.appendLine()
	hdr.appendLine("extern const ktc_String KTC_RELATED(names[$n]);")
	hdr.appendLine("extern const $cName KTC_RELATED(values[$n]);")
	hdr.appendLine("extern const ktc_Int KTC_RELATED(values\$len);")
	hdr.appendLine("KTC_METHOD(KTC_TYPE_NAME, valueOf)(ktc_String name);")

	// Emit entry constants in .c.
	impl.appendLine(boxSection("entries"))
	impl.appendLine()
	for ((vOrd, entry) in d.entries.withIndex()) {
		val vArgs    = ei.entryArgs[entry.name] ?: emptyList()
		val vCtorPs  = ei.ctorParams
		val vInits   = mutableListOf<String>()
		// ctor-param field initializers
		for ((vIdx, vP) in vCtorPs.withIndex()) {
			val vExpr = vArgs.getOrNull(vIdx)
			val vInit = if (vExpr != null) genExpr(vExpr) else defaultVal(resolveTypeName(vP.typeRef))
			vInits += vInit
			}
		// body-stored fields: zero/default-initialized (init exprs may run later)
		for (vP in ei.bodyProps) {
			if (vP.getter != null) continue
			vInits += defaultVal(resolveTypeName(vP.typeRef))
			}
		vInits += "$vOrd"
		vInits += "ktc_core_str(\"${entry.name}\")"
		impl.appendLine("const $cName ${cName}_${entry.name} = { ${vInits.joinToString(", ")} };")
		}
	impl.appendLine()

	// names[] array — also used by .name and toString of unknown values.
	val vNameInits = d.entries.joinToString(", ") { "ktc_core_str(\"${it.name}\")" }
	impl.appendLine(boxSection("names"))
	impl.appendLine()
	impl.appendLine("const ktc_String ${cName}_names[$n] = {$vNameInits};")
	impl.appendLine()

	// Method declarations + bodies.
	for (m in ei.enumMethods) emitEnumMethod(d.name, m)

	hdr.appendLine()
	hdr.appendLine("#undef KTC_TYPE_NAME")
	hdr.appendLine(classBlockFooter("enum class", d.name, emptyList()))
	}

// Emit a single enum method: takes the enum value by-value as $self.
private fun CCodeGen.emitEnumMethod(enumName: String, f: FunDecl) {
	if (f.isInline) return   // expanded at call sites via inlineFunDecls
	val ei         = enums[enumName]!!
	val cName      = ei.flatName
	val methodName = resolvedFnName(f, ei.enumMethods)
	val selfParam  = "$cName \$self"
	val extra      = expandParams(f.params)
	val allParams  = if (extra.isNotEmpty()) "$selfParam, $extra" else selfParam

	val paramSig = f.params.joinToString(", ") { p -> "${p.name}: ${typeRefToStr(p.type)}" }
	val retSig   = f.returnType?.let { ": ${typeRefToStr(it)}" } ?: ""
	impl.appendLine("// ══ fun ${f.name}($paramSig)$retSig ══")

	val prevState = saveFunState()
	val cRet      = computeReturnInfo(f, f.body?.let { inferBlockType(it) })
	val vHdrSig   = "KTC_METHOD($cRet, $methodName)(${allParams.replace(cName, "KTC_TYPE_NAME")});"
	if (f.isPrivate) implFwd.appendLine("$cRet ${cName}_${methodName}($allParams);")
	else             hdr.appendLine(vHdrSig)
	impl.appendLine("$cRet ${cName}_${methodName}($allParams) {")

	pushScope()
	registerParams(f.params)
	// Register ctor-param + body fields as `$self.field` access for this method body.
	for (p in ei.ctorParams + ei.bodyProps) {
		if (scopes.last().containsKey(p.name)) continue   // don't shadow params
		val vKtc   = resolveTypeName(p.typeRef)
		val vIsOpt = p.typeRef.nullable && !p.typeRef.isRefType() && !vKtc.isArrayLike
		defineVar(p.name, LocalVar(ktc = vKtc, mutable = !p.isVal, optional = vIsOpt, cName = "\$self.${p.name}"))
		}
	// `this` inside an enum method refers to $self by-value; selfIsPointer=false makes
	// `this.field` lower correctly via the registerClassFields/dot path.
	currentClass  = enumName
	selfIsPointer = false
	emitArrayParamCopies(f.params, "    ")
	emitFunBodyAndClose(f, prevState, insideMethod = true)
	}

// ──────────────────────────────────────────────────────────────────
// MARK: Deferred values[] / valueOf() data
// ──────────────────────────────────────────────────────────────────

/* Emit values[] and valueOf() for all enums accessed via .values/.valueOf this pass. */
internal fun CCodeGen.emitEnumValuesData() {
	for (enumName in enumValuesCalled) {
		val info  = enums[enumName] ?: continue
		val cName = typeFlatName(enumName)
		val entryNames = info.entries.joinToString(", ") { "${cName}_$it" }
		val n          = info.entries.size
		captureForDecl(enumName) {
			impl.appendLine(boxSection("values"))
			impl.appendLine()
			impl.appendLine("const $cName ${cName}_values[] = {$entryNames};")
			impl.appendLine("const ktc_Int ${cName}_values\$len = $n;")
			impl.appendLine()
			}
		}
	for (enumName in enumValueOfCalled) {
		val info  = enums[enumName] ?: continue
		val cName = typeFlatName(enumName)
		captureForDecl(enumName) {
			impl.appendLine(boxSection("valueOf"))
			impl.appendLine()
			impl.appendLine("$cName ${cName}_valueOf(ktc_String name) {")
			for (entry in info.entries)
				impl.appendLine("    if (ktc_core_string_eq(name, ktc_core_str(\"$entry\"))) return ${cName}_$entry;")
			impl.appendLine("    return ${cName}_${info.entries.first()};")
			impl.appendLine("}")
			impl.appendLine()
			}
		}
	enumValuesCalled.clear()
	enumValueOfCalled.clear()
	}
