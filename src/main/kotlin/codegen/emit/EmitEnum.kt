package com.bitsycore.ktc.codegen.emit

import com.bitsycore.ktc.ast.EnumDecl
import com.bitsycore.ktc.codegen.*

// Enum class header/impl emission and deferred values/valueOf data.

internal fun CCodeGen.emitEnum(d: EnumDecl) {
	val ei    = enums[d.name]!!
	val cName = ei.flatName
	val n     = d.entries.size
	hdr.appendLine(classBlockHeader("enum class", d.name, emptyList(), emptyList(), file.pkg ?: "", currentSourceFile, cName))
	hdr.appendLine("#define KTC_TYPE_NAME $cName")
	hdr.appendLine()
	hdr.appendLine("KTC_ENUM(")
	for ((i, e) in d.entries.withIndex()) {
		hdr.append("    KTC_RELATED($e)")
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
	val nameInits = d.entries.joinToString(", ") { "ktc_core_str(\"$it\")" }
	impl.appendLine(boxSection("names"))
	impl.appendLine()
	impl.appendLine("const ktc_String ${cName}_names[$n] = {$nameInits};")
	impl.appendLine()
	}

/* Emit values[] and valueOf() for all enums accessed via .values/.valueOf this pass. */
internal fun CCodeGen.emitEnumValuesData() {
	for (enumName in enumValuesCalled) {
		val info  = enums[enumName] ?: continue
		val cName = typeFlatName(enumName)
		val entryNames = info.entries.joinToString(", ") { "${cName}_${it}" }
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
