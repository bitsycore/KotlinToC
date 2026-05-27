package com.bitsycore.ktc.codegen

import com.bitsycore.ktc.ast.*
import com.bitsycore.ktc.codegen.expression.genExpr

// ════════════════════════════ Tail-call analysis ═══════════════════════════

internal data class TailrecAnalysis(
	val tailCalls: Int,
	val nonTailCalls: Int
) {
	val allCalls get() = tailCalls + nonTailCalls
}

private class TailrecAnalyzer(private val fnName: String, params: List<Param>) {
	var tailCalls = 0
	var allCalls = 0
	private val minArgs = params.count { it.default == null }
	private val maxArgs = params.size

	fun walkBlock(b: Block) = b.stmts.forEach(::walkStmt)

	fun walkStmt(s: Stmt) {
		when (s) {
			is ReturnStmt -> {
				val v = s.value
				if (v is CallExpr && isCallToSelf(v)) {
					tailCalls++; allCalls++
					v.args.forEach { walkExpr(it.expr) }
					(v.callee as? DotExpr)?.let { walkExpr(it.obj) }
				} else v?.let { walkExpr(it) }
			}
			is ExprStmt              -> walkExpr(s.expr)
			is VarDeclStmt           -> s.init?.let { walkExpr(it) }
			is DestructuringDeclStmt -> walkExpr(s.init)
			is AssignStmt            -> { walkExpr(s.target); walkExpr(s.value) }
			is ForStmt               -> { walkExpr(s.iter); walkBlock(s.body) }
			is WhileStmt             -> { walkExpr(s.cond); walkBlock(s.body) }
			is DoWhileStmt           -> { walkBlock(s.body); walkExpr(s.cond) }
			is DeferStmt             -> walkBlock(s.body)
			else -> {}
		}
	}

	fun walkExpr(e: Expr) {
		when (e) {
			is CallExpr -> {
				if (isCallToSelf(e)) allCalls++
				e.args.forEach { walkExpr(it.expr) }
				(e.callee as? DotExpr)?.let { walkExpr(it.obj) }
				(e.callee as? SafeDotExpr)?.let { walkExpr(it.obj) }
			}
			is DotExpr         -> walkExpr(e.obj)
			is SafeDotExpr     -> walkExpr(e.obj)
			is BinExpr         -> { walkExpr(e.left); walkExpr(e.right) }
			is PrefixExpr      -> walkExpr(e.expr)
			is PostfixExpr     -> walkExpr(e.expr)
			is IndexExpr       -> { walkExpr(e.obj); walkExpr(e.index) }
			is IfExpr          -> { walkExpr(e.cond); walkBlock(e.then); e.els?.let { walkBlock(it) } }
			is WhenExpr        -> {
				e.subject?.let { walkExpr(it) }
				e.branches.forEach { br ->
					br.conds?.forEach { c ->
						when (c) {
							is ExprCond -> walkExpr(c.expr)
							is InCond   -> walkExpr(c.expr)
							else -> {}
						}
					}
					walkBlock(br.body)
				}
			}
			is NotNullExpr     -> walkExpr(e.expr)
			is ElvisExpr       -> { walkExpr(e.left); walkExpr(e.right) }
			is IsCheckExpr     -> walkExpr(e.expr)
			is CastExpr        -> walkExpr(e.expr)
			is LambdaExpr      -> e.body.forEach(::walkStmt)
			is StrTemplateExpr -> e.parts.forEach { if (it is ExprPart) walkExpr(it.expr) }
			else -> {}
		}
	}

	private fun isCallToSelf(call: CallExpr): Boolean {
		val nameMatch = when (val c = call.callee) {
			is NameExpr -> c.name == fnName
			is DotExpr  -> c.name == fnName
			else        -> false
		}
		if (!nameMatch) return false
		return call.args.size in minArgs..maxArgs
	}
}

internal fun analyzeTailrec(body: Block, fnName: String, params: List<Param> = emptyList()): TailrecAnalysis {
	val w = TailrecAnalyzer(fnName, params)
	w.walkBlock(body)
	return TailrecAnalysis(w.tailCalls, w.allCalls - w.tailCalls)
}

// ════════════════════════════ Validation + suggestion ═══════════════════════════

internal fun CCodeGen.validateTailrec(f: FunDecl) {
	if (!f.isTailrec || f.body == null) return
	if (f.isInline) {
		codegenWarning("tailrec-inline", "'tailrec' is ignored on 'inline' function '${f.name}'")
		return
	}
	val a = analyzeTailrec(f.body, f.name, f.params)
	if (a.allCalls == 0)
		codegenError("'tailrec' modifier on '${f.name}' is pointless: function doesn't call itself")
	if (a.nonTailCalls > 0)
		codegenError("Recursive call to '${f.name}' is not a tail call - rewrite each recursive call as 'return ${f.name}(...)' at the end of a branch")
}

internal fun CCodeGen.suggestTailrec(f: FunDecl) {
	if (f.isTailrec || f.isInline || f.body == null) return
	val a = analyzeTailrec(f.body, f.name, f.params)
	if (a.allCalls > 0 && a.nonTailCalls == 0)
		codegenWarning("tailrec-suggestion", "'${f.name}' has recursive tail calls - consider adding 'tailrec' modifier")
}

// ════════════════════════════ Tailrec setup for emit ═══════════════════════════

internal fun CCodeGen.setupTailrec(f: FunDecl, hasReceiver: Boolean = false, selfCType: String? = null) {
	if (!f.isTailrec || f.isInline) return
	tailrecFnName = f.name
	tailrecParams = f.params
	tailrecHasReceiver = hasReceiver
	tailrecSelfCType = selfCType
	impl.appendLine("    \$tailrec:;")
}

// ════════════════════════════ Tail-call detection + trampoline ═══════════════════════════

internal fun CCodeGen.asTailrecSelfCall(expr: Expr?): CallExpr? {
	if (tailrecFnName == null || expr !is CallExpr) return null
	val fnName = tailrecFnName!!
	val match = when (val c = expr.callee) {
		is NameExpr -> c.name == fnName
		is DotExpr  -> c.name == fnName
		else        -> false
	}
	return if (match) expr else null
}

internal fun CCodeGen.emitTailrecTrampoline(call: CallExpr, ind: String) {
	val params = tailrecParams
	val args = call.args

	// Match args to params (named + positional)
	val argExprs = arrayOfNulls<Expr>(params.size)
	var posIdx = 0
	for (arg in args) {
		if (arg.name != null) {
			val idx = params.indexOfFirst { it.name == arg.name }
			if (idx >= 0) argExprs[idx] = arg.expr
		} else if (posIdx < params.size) {
			argExprs[posIdx++] = arg.expr
		}
	}
	for (i in params.indices) {
		if (argExprs[i] == null)
			argExprs[i] = params[i].default
				?: codegenError("Missing argument '${params[i].name}' in tailrec call")
	}

	// Evaluate all arg expressions to temps first (avoids aliasing during reassignment)
	val temps = mutableListOf<Pair<String, String>>()
	for (i in params.indices) {
		val cExpr = genExpr(argExprs[i]!!)
		flushPreStmts(ind)
		val t = tmp()
		val ct = cType(params[i].type)
		impl.appendLine("$ind$ct $t = $cExpr;")
		temps += t to params[i].name
	}

	// Reassign receiver when callee is a dot call with a non-this receiver
	val calleeDot = call.callee as? DotExpr
	if (tailrecHasReceiver && calleeDot != null) {
		val recvObj = calleeDot.obj
		if (recvObj !is ThisExpr) {
			val recvExpr = genExpr(recvObj)
			flushPreStmts(ind)
			val t = tmp()
			impl.appendLine("$ind${tailrecSelfCType} $t = $recvExpr;")
			temps += t to "\$self"
		}
	}

	// Assign all temps to their targets
	for ((temp, target) in temps) {
		impl.appendLine("$ind$target = $temp;")
	}

	impl.appendLine("${ind}goto \$tailrec;")
}
