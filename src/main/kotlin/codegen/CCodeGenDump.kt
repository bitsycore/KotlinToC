package com.bitsycore.ktc.codegen

import com.bitsycore.ktc.ast.*

/* Produce a human-readable semantic dump of all declarations in the file.
Used with the --dump-semantics flag to debug symbol table contents. */
internal fun CCodeGen.dumpSemantics(): String {
	val sb = StringBuilder()
	sb.appendLine("╔══════════════════════════════════════════════════╗")
	sb.appendLine("║  Semantic Analysis  -  ${file.sourceFile}".padEnd(49) + "║")
	sb.appendLine("╚══════════════════════════════════════════════════╝")
	sb.appendLine()

	if (file.pkg != null) sb.appendLine("package ${file.pkg}")
	if (file.imports.isNotEmpty()) {
		for (imp in file.imports) sb.appendLine("import $imp")
		sb.appendLine()
		}

	if (classes.isNotEmpty()) {
		sb.appendLine("── Classes ──")
		for ((name, ci) in classes) {
			val tp     = if (ci.typeParams.isNotEmpty()) "<${ci.typeParams.joinToString(", ")}>" else ""
			val ifaces = classInterfaces[name]?.joinToString(", ") ?: ""
			val ifs    = if (ifaces.isNotEmpty()) " : $ifaces" else ""
			val data   = if (ci.isData) "(data) " else ""
			sb.appendLine("  ${data}class $name$tp$ifs")
			sb.appendLine("    type_id: ${typeIds[name]}")
			for ((pn, pt) in ci.props) {
				val priv    = if (pn in ci.privateProps) "private " else ""
				val valMark = if (ci.isValProp(pn)) "val" else "var"
				sb.appendLine("    $priv$valMark $pn: ${typeRefToStr(pt)}")
				}
			if (ci.ctorPlainParams.isNotEmpty()) {
				for (vParam in ci.ctorPlainParams) {
					sb.appendLine("    param ${vParam.name}: ${typeRefToStr(vParam.typeRef)}")
					}
				}
			for (m in ci.methods) {
				val priv   = if (m.isPrivate) "private " else ""
				val op     = if (m.isOperator) "operator " else ""
				val tp2    = if (m.typeParams.isNotEmpty()) "<${m.typeParams.joinToString(", ")}>" else ""
				val ret    = m.returnType?.let { ": ${typeRefToStr(it)}" } ?: ""
				val params = m.params.joinToString(", ") { "${it.name}: ${typeRefToStr(it.type)}" }
				sb.appendLine("    ${priv}${op}fun $tp2${m.name}($params)$ret")
				}
			}
		sb.appendLine()
		}

	if (interfaces.isNotEmpty()) {
		sb.appendLine("── Interfaces ──")
		for ((name, iface) in interfaces) {
			val tp  = if (iface.typeParams.isNotEmpty()) "<${iface.typeParams.joinToString(", ")}>" else ""
			val sup = if (iface.superInterfaces.isNotEmpty())
				" : ${iface.superInterfaces.joinToString(", ") { typeRefToStr(it) }}" else ""
			sb.appendLine("  interface $name$tp$sup")
			for (p in iface.propDecls) {
				val mut = if (p.mutable) "var" else "val"
				val pt  = if (p.type != null) ": ${typeRefToStr(p.type)}" else ""
				sb.appendLine("    $mut ${p.name}$pt")
				}
			for (m in iface.methods) {
				val op     = if (m.isOperator) "operator " else ""
				val tp2    = if (m.typeParams.isNotEmpty()) "<${m.typeParams.joinToString(", ")}>" else ""
				val ret    = m.returnType?.let { ": ${typeRefToStr(it)}" } ?: ""
				val params = m.params.joinToString(", ") { "${it.name}: ${typeRefToStr(it.type)}" }
				sb.appendLine("    ${op}fun $tp2${m.name}($params)$ret")
				}
			}
		sb.appendLine()
		}

	if (enums.isNotEmpty()) {
		sb.appendLine("── Enums ──")
		for ((name, ei) in enums) {
			sb.appendLine("  enum $name { ${ei.entries.joinToString(", ")} }")
			}
		sb.appendLine()
		}

	if (objects.isNotEmpty()) {
		sb.appendLine("── Objects ──")
		for ((name, oi) in objects) {
			sb.appendLine("  object $name")
			for ((pn, pt) in oi.props) { sb.appendLine("    val $pn: $pt") }
			for (m in oi.methods) {
				val ret    = m.returnType?.let { ": ${typeRefToStr(it)}" } ?: ""
				val params = m.params.joinToString(", ") { "${it.name}: ${typeRefToStr(it.type)}" }
				sb.appendLine("    fun ${m.name}($params)$ret")
				}
			}
		sb.appendLine()
		}

	if (funSigs.isNotEmpty()) {
		sb.appendLine("── Function Signatures ──")
		for ((name, sig) in funSigs) {
			val ret    = sig.returnType?.let { ": ${typeRefToStr(it)}" } ?: ""
			val params = sig.params.joinToString(", ") { "${it.name}: ${typeRefToStr(it.type)}" }
			val src    = declSourceFile[name]?.let { "  // $it" } ?: ""
			sb.appendLine("  fun $name($params)$ret$src")
			}
		sb.appendLine()
		}

	if (extensionFuns.isNotEmpty()) {
		sb.appendLine("── Extension Functions ──")
		for ((typeName, funs) in extensionFuns) {
			for (f in funs) {
				val ret    = f.returnType?.let { ": ${typeRefToStr(it)}" } ?: ""
				val params = f.params.joinToString(", ") { "${it.name}: ${typeRefToStr(it.type)}" }
				sb.appendLine("  fun $typeName.${f.name}($params)$ret")
				}
			}
		sb.appendLine()
		}

	if (genericClassDecls.isNotEmpty()) {
		sb.appendLine("── Generic Class Templates (un-instantiated) ──")
		for ((name, decl) in genericClassDecls) {
			val tps = decl.typeParams.joinToString(", ")
			val src = declSourceFile[name]?.let { "  // $it" } ?: ""
			sb.appendLine("  $name<$tps>$src")
			}
		sb.appendLine()
		}

	if (genericInstantiations.isNotEmpty()) {
		sb.appendLine("── Generic Class Instantiations ──")
		for ((base, insts) in genericInstantiations) {
			for (typeArgs in insts) {
				val bindings = genericTypeBindings[mangledGenericName(base, typeArgs)]
				sb.appendLine("  $base<${typeArgs.joinToString(", ")}>")
				if (bindings != null) {
					sb.appendLine("    subst: ${bindings.entries.joinToString(", ") { "${it.key}→${it.value}" }}")
					}
				}
			}
		sb.appendLine()
		}

	if (genericFunDecls.isNotEmpty()) {
		sb.appendLine("── Generic Function Templates ──")
		for (f in genericFunDecls) {
			val tps    = "<${f.typeParams.joinToString(", ")}>"
			val params = f.params.joinToString(", ") { p ->
				val va  = if (p.isVararg) "vararg " else ""
				val def = if (p.default != null) " = ..." else ""
				"$va${p.name}: ${typeRefToStr(p.type)}$def"
				}
			val ret  = f.returnType?.let { ": ${typeRefToStr(it)}" } ?: ""
			val recv = if (f.receiver != null) "${typeRefToStr(f.receiver)}." else ""
			val src  = declSourceFile[f.name]?.let { "  // $it" } ?: ""
			sb.appendLine("  fun $tps $recv${f.name}($params)$ret$src")
			}
		sb.appendLine()
		}

	if (genericFunInstantiations.isNotEmpty()) {
		sb.appendLine("── Generic Function Instantiations ──")
		for ((mangled, typeArgsList) in genericFunInstantiations) {
			for (typeArgs in typeArgsList) {
				sb.appendLine("  $mangled(${typeArgs.joinToString(", ")})")
				}
			}
		sb.appendLine()
		}

	if (genericFunConcreteReturn.isNotEmpty()) {
		sb.appendLine("── Generic Function Concrete Returns ──")
		for ((mangled, ret) in genericFunConcreteReturn) {
			sb.appendLine("  $mangled → $ret")
			}
		sb.appendLine()
		}

	if (interfaceImplementors.isNotEmpty()) {
		sb.appendLine("── Interface Implementors ──")
		for ((iface, impls) in interfaceImplementors) {
			sb.appendLine("  $iface ← ${impls.joinToString(", ")}")
			}
		sb.appendLine()
		}

	if (classCompanions.isNotEmpty()) {
		sb.appendLine("── Companion Objects ──")
		for ((cls, companion) in classCompanions) {
			sb.appendLine("  $cls → $companion")
			}
		sb.appendLine()
		}

	if (classArrayTypes.isNotEmpty()) {
		sb.appendLine("── Class Array Types ──")
		sb.appendLine("  ${classArrayTypes.joinToString(", ")}")
		sb.appendLine()
		}

	return sb.toString()
	}
