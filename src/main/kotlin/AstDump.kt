package com.bitsycore.ktc

import com.bitsycore.ktc.ast.*

// ── AST debug dump — used by --ast and --dump-semantics flags ─────

internal fun dumpAst(file: KtFile): String {
	val vSb = StringBuilder()
	if (file.pkg != null) vSb.appendLine("${indent(0)}package ${file.pkg}")
	for (imp in file.imports) vSb.appendLine("${indent(0)}import $imp")
	for (decl in file.decls) vSb.append(dumpDecl(decl, 0))
	return vSb.toString()
	}

internal fun dumpDecl(d: Decl, depth: Int): String {
	val vSb = StringBuilder()
	val vId = indent(depth)
	when (d) {
		is FunDecl -> {
			val vP    = d.params.joinToString(", ") { dumpParam(it) }
			val vR    = if (d.returnType != null) ": ${dumpTypeRef(d.returnType)}" else ""
			val vTps  = if (d.typeParams.isNotEmpty()) "<${d.typeParams.joinToString(", ")}>" else ""
			val vRecv = if (d.receiver != null) "${dumpTypeRef(d.receiver)}." else ""
			val vOp   = if (d.isOperator) "operator " else ""
			vSb.appendLine("${vId}${vOp}fun $vRecv${d.name}$vTps($vP)$vR")
			if (d.body != null) vSb.append(dumpBlock(d.body, depth + 1))
			}

		is ClassDecl -> {
			val vTps = if (d.typeParams.isNotEmpty()) "<${d.typeParams.joinToString(", ")}>" else ""
			val vDs  = if (d.isSealed) "sealed " else if (d.isData) "data " else ""
			val vIfs = if (d.superInterfaces.isNotEmpty()) " : ${d.superInterfaces.joinToString(", ") { dumpTypeRef(it) }}" else ""
			vSb.appendLine("${vId}${vDs}class ${d.name}$vTps$vIfs")
			for (cp in d.ctorParams) vSb.appendLine("${indent(depth + 1)}ctor ${dumpCtorParam(cp)}")
			for (m in d.members) vSb.append(dumpDecl(m, depth + 1))
			for (init in d.initBlocks) vSb.append(dumpBlock(init, depth + 1, "init"))
			}

		is EnumDecl -> {
			vSb.appendLine("${vId}enum ${d.name} { ${d.entries.joinToString(", ") { it.name }} }")
			}

		is InterfaceDecl -> {
			val vTps = if (d.typeParams.isNotEmpty()) "<${d.typeParams.joinToString(", ")}>" else ""
			val vIfs = if (d.superInterfaces.isNotEmpty()) " : ${d.superInterfaces.joinToString(", ") { dumpTypeRef(it) }}" else ""
			vSb.appendLine("${vId}interface ${d.name}$vTps$vIfs")
			for (p in d.properties) vSb.append(dumpDecl(p, depth + 1))
			for (m in d.methods) vSb.append(dumpDecl(m, depth + 1))
			}

		is ObjectDecl -> {
			vSb.appendLine("${vId}object ${d.name}")
			for (m in d.members) vSb.append(dumpDecl(m, depth + 1))
			}

		is PropDecl -> {
			val vMut  = if (d.mutable) "var" else "val"
			val vTp   = if (d.type != null) ": ${dumpTypeRef(d.type)}" else ""
			val vInit = if (d.init != null) " = ${dumpExpr(d.init)}" else ""
			vSb.appendLine("${vId}$vMut ${d.name}$vTp$vInit")
			}

		is TypeAliasDecl -> vSb.appendLine("${vId}typealias ${d.name} = ${dumpTypeRef(d.target)}")
		}
	return vSb.toString()
	}

internal fun dumpCtorParam(cp: CtorParam): String {
	val vKw  = if (cp.isVar) "var " else if (cp.isVal) "val " else ""
	val vDef = if (cp.default != null) " = ${dumpExpr(cp.default)}" else ""
	return "$vKw${cp.name}: ${dumpTypeRef(cp.type)}$vDef"
	}

internal fun dumpParam(p: Param): String {
	val vVa  = if (p.isVararg) "vararg " else ""
	val vDef = if (p.default != null) " = ${dumpExpr(p.default)}" else ""
	return "$vVa${p.name}: ${dumpTypeRef(p.type)}$vDef"
	}

internal fun dumpTypeRef(t: TypeRef): String {
	val vTps = if (t.typeArgs.isNotEmpty()) "<${t.typeArgs.joinToString(", ") { dumpTypeRef(it) }}>" else ""
	val vNll = if (t.nullable) "?" else ""
	val vFn  = if (t.funcParams != null) {
		val vPs = t.funcParams.joinToString(", ") { dumpTypeRef(it) }
		"($vPs) -> ${dumpTypeRef(t.funcReturn!!)}"
		} else ""
	val vAnn = if (t.annotations.isNotEmpty()) {
		t.annotations.joinToString(" ") {
			"@${it.name}${
				if (it.args.isNotEmpty()) "(${
					it.args.joinToString(", ") { argsStr -> dumpExpr(argsStr) }
				})" else ""
				}"
			} + " "
		} else ""
	return "$vAnn${t.name}$vTps$vFn$vNll"
	}

internal fun dumpBlock(b: Block, depth: Int, label: String = ""): String {
	val vSb  = StringBuilder()
	val vId  = indent(depth)
	val vPre = if (label.isNotEmpty()) "$label " else ""
	vSb.appendLine("${vId}${vPre}{")
	for (s in b.stmts) vSb.append(dumpStmt(s, depth))
	vSb.appendLine("${vId}}")
	return vSb.toString()
	}

internal fun dumpStmt(s: Stmt, depth: Int): String {
	val vSb = StringBuilder()
	val vId = indent(depth)
	when (s) {
		is ExprStmt    -> vSb.appendLine("${vId}${dumpExpr(s.expr)}")
		is VarDeclStmt -> {
			val vMut  = if (s.mutable) "var" else "val"
			val vTp   = if (s.type != null) ": ${dumpTypeRef(s.type)}" else ""
			val vInit = if (s.init != null) " = ${dumpExpr(s.init)}" else ""
			vSb.appendLine("${vId}$vMut ${s.name}$vTp$vInit")
			}
		is DestructuringDeclStmt -> {
			val vMut = if (s.mutable) "var" else "val"
			vSb.appendLine("${vId}$vMut (${s.names.joinToString(", ")}) = ${dumpExpr(s.init)}")
			}
		is AssignStmt  -> vSb.appendLine("${vId}${dumpExpr(s.target)} ${s.op} ${dumpExpr(s.value)}")
		is ReturnStmt  -> vSb.appendLine("${vId}return${if (s.value != null) " ${dumpExpr(s.value)}" else ""}")
		is ForStmt     -> {
			vSb.appendLine("${vId}for (${s.varName} in ${dumpExpr(s.iter)})")
			vSb.append(dumpBlock(s.body, depth))
			}
		is WhileStmt   -> {
			vSb.appendLine("${vId}while (${dumpExpr(s.cond)})")
			vSb.append(dumpBlock(s.body, depth))
			}
		is DoWhileStmt -> {
			vSb.appendLine("${vId}do")
			vSb.append(dumpBlock(s.body, depth))
			vSb.appendLine("${vId}while (${dumpExpr(s.cond)})")
			}
		is BreakStmt    -> vSb.appendLine("${vId}break")
		is ContinueStmt -> vSb.appendLine("${vId}continue")
		is DeferStmt    -> {
			vSb.appendLine("${vId}defer")
			vSb.append(dumpBlock(s.body, depth))
			}
		is CommentStmt  -> vSb.appendLine("${vId}comment ${s.text}")
		}
	return vSb.toString()
	}

internal fun dumpExpr(e: Expr): String = when (e) {
	is IntLit    -> if (e.hex) "0x${e.value.toString(16)}" else "${e.value}"
	is LongLit   -> if (e.hex) "0x${e.value.toString(16)}L" else "${e.value}L"
	is UIntLit   -> if (e.hex) "0x${e.value.toString(16)}u" else "${e.value}u"
	is ULongLit  -> if (e.hex) "0x${e.value.toString(16)}UL" else "${e.value}UL"
	is DoubleLit -> "${e.value}"
	is FloatLit  -> "${e.value}f"
	is BoolLit   -> "${e.value}"
	is CharLit   -> "'${e.value}'"
	is StrLit    -> "\"${e.value}\""
	is StrTemplateExpr -> "\"${
		e.parts.joinToString("") {
			when (it) {
				is LitPart  -> it.text
				is ExprPart -> "\${${dumpExpr(it.expr)}}"
				}
			}
		}\""
	is NullLit       -> "null"
	is NameExpr      -> e.name
	is ThisExpr      -> "this"
	is BinExpr       -> "(${dumpExpr(e.left)} ${e.op} ${dumpExpr(e.right)})"
	is PrefixExpr    -> "(${e.op}${dumpExpr(e.expr)})"
	is PostfixExpr   -> "(${dumpExpr(e.expr)}${e.op})"
	is CallExpr      -> {
		val vTas  = if (e.typeArgs.isNotEmpty()) "<${e.typeArgs.joinToString(", ") { dumpTypeRef(it) }}>" else ""
		val vArgs = e.args.joinToString(", ") { if (it.isSpread) "*${dumpExpr(it.expr)}" else dumpExpr(it.expr) }
		"${dumpExpr(e.callee)}$vTas($vArgs)"
		}
	is DotExpr       -> "${dumpExpr(e.obj)}.${e.name}"
	is SafeDotExpr   -> "${dumpExpr(e.obj)}?.${e.name}"
	is IndexExpr     -> "${dumpExpr(e.obj)}[${dumpExpr(e.index)}]"
	is IfExpr        -> {
		val vEls = if (e.els != null) {
			val vEs = StringBuilder()
			for (s in e.els.stmts) vEs.append(dumpStmt(s, 0))
			" else { ${vEs.toString().trim()} }"
			} else ""
		val vTs = StringBuilder()
		for (s in e.then.stmts) vTs.append(dumpStmt(s, 0))
		"if (${dumpExpr(e.cond)}) { ${vTs.toString().trim()} }$vEls"
		}
	is WhenExpr      -> {
		val vBrs = e.branches.joinToString(" ") { b ->
			val vConds = if (b.conds == null) "else" else b.conds.joinToString(", ") { dumpWhenCond(it) }
			val vBody  = StringBuilder()
			for (s in b.body.stmts) vBody.append(dumpStmt(s, 0))
			"$vConds -> { ${vBody.toString().trim()} }"
			}
		val vSub = if (e.subject != null) "(${dumpExpr(e.subject)})" else ""
		"when$vSub { $vBrs }"
		}
	is NotNullExpr   -> "${dumpExpr(e.expr)}!!"
	is ElvisExpr     -> "(${dumpExpr(e.left)} ?: ${dumpExpr(e.right)})"
	is IsCheckExpr   -> "${dumpExpr(e.expr)} ${if (e.negated) "!is" else "is"} ${dumpTypeRef(e.type)}"
	is CastExpr      -> "${dumpExpr(e.expr)} as ${dumpTypeRef(e.type)}"
	is FunRefExpr    -> "::${e.name}"
	is ClassRefExpr  -> "${e.typeName}::class"
	is LambdaExpr    -> {
		val vParams = if (e.params.isNotEmpty()) e.params.joinToString(", ") + " -> " else ""
		val vBody   = e.body.joinToString("; ") { dumpStmt(it, 0).trim() }
		"{ $vParams$vBody }"
		}
	is ObjectExpr    -> "object<${e.syntheticName}>"
	}

internal fun dumpWhenCond(c: WhenCond): String = when (c) {
	is ExprCond -> dumpExpr(c.expr)
	is IsCond   -> "${if (c.negated) "!is " else "is "}${dumpTypeRef(c.type)}"
	is InCond   -> "${if (c.negated) "!in " else "in "}${dumpExpr(c.expr)}"
	}

private fun indent(n: Int) = "  ".repeat(n) // indentation helper for dump output
