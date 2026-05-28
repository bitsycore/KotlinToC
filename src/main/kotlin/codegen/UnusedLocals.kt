package com.bitsycore.ktc.codegen

import com.bitsycore.ktc.ast.*

// W025 (unused local), W028 (var never reassigned).
// Static analysis over a function body's AST. We don't try to be flow-sensitive —
// the question is "anywhere later in the function, is this name read / assigned?"
// Conservative: if we can't prove it's unread, we say nothing.

internal data class LocalUsage(
	val name:        String,
	val mutable:     Boolean,    // true = var, false = val
	val line:        Int,
	val col:         Int,
	var readCount:   Int = 0,
	var writeCount:  Int = 0     // counts reassignments after the initializer
	)

// Recognize `c.addr(name)` — returns the name being address-of'd, else null.
// `c` here is the C interop namespace; `addr` takes a value lvalue and returns its &.
private fun addressOfTargetName(inCall: CallExpr): String? {
	val vCallee = inCall.callee as? DotExpr ?: return null
	if (vCallee.name != "addr") return null
	val vRecv = vCallee.obj as? NameExpr ?: return null
	if (vRecv.name != "c") return null
	val vArg = inCall.args.firstOrNull()?.expr as? NameExpr ?: return null
	return vArg.name
	}

// Scan [inBody] for VarDeclStmt nodes, then count uses of each one across the rest
// of the body. Emits W025 for unread locals (skip _-prefixed) and W028 for vars that
// are declared with an initializer but never reassigned.
internal fun CCodeGen.scanUnusedLocals(inBody: Block) {
	val vUsages = mutableListOf<LocalUsage>()
	collectLocalDecls(inBody, vUsages)
	if (vUsages.isEmpty()) return
	val vByName = vUsages.associateBy { it.name }
	scanUsageInBlock(inBody, vByName)
	for (vU in vUsages) {
		if (vU.name.startsWith("_")) continue
		val vPrev = currentStmtLine
		val vPrevCol = currentStmtCol
		currentStmtLine = vU.line
		currentStmtCol = vU.col
		try {
			val vKind = if (vU.mutable) "var" else "val"
			if (vU.readCount == 0) {
				codegenWarning("unused-local",
					"Local $vKind '${vU.name}' is declared but never read.")
				}
			else if (vU.mutable && vU.writeCount == 0) {
				codegenWarning("could-be-val",
					"Local var '${vU.name}' is never reassigned — declare it as 'val' instead.")
				}
			}
		finally {
			currentStmtLine = vPrev
			currentStmtCol = vPrevCol
			}
		}
	}

// Collect VarDeclStmt names (excluding destructuring and lazy locals — those have
// non-trivial bodies that we don't want to chase here). Skips locals re-declared in
// inner scopes; the outermost decl wins for the warning location.
private fun collectLocalDecls(inBlock: Block, ioOut: MutableList<LocalUsage>) {
	val vSeen = ioOut.mapTo(HashSet()) { it.name }
	for (vS in inBlock.stmts) {
		when (vS) {
			is VarDeclStmt -> {
				if (vS.lazyInit != null) continue            // lazy locals have complex init semantics
				if (vS.name == "_" || vS.name.isEmpty()) continue
				if (vSeen.add(vS.name)) ioOut += LocalUsage(vS.name, vS.mutable, vS.line, vS.col)
				}
			else -> {}   // nested scopes (if/when/for/while bodies) are scanned for reads, not for decls
			}
		}
	}

// Walk the block counting reads/writes of every name in [inUsages].
private fun scanUsageInBlock(inBlock: Block, inUsages: Map<String, LocalUsage>) {
	for (vS in inBlock.stmts) scanStmt(vS, inUsages)
	}

private fun scanStmt(inS: Stmt, inUsages: Map<String, LocalUsage>) {
	when (inS) {
		is VarDeclStmt -> {
			// Initializer counts as a read of any referenced var, NOT a write to vS.name.
			inS.init?.let { scanExpr(it, inUsages) }
			inS.lazyInit?.let { scanUsageInBlock(it, inUsages) }
			}
		is AssignStmt -> {
			// LHS is a write when target is a plain NameExpr; otherwise it's a path
			// that reads receivers (e.g. obj.field = ...). RHS is always a read.
			when (val vT = inS.target) {
				is NameExpr -> {
					inUsages[vT.name]?.let {
						it.writeCount++
						// Compound ops (+=, -=, etc.) also read the LHS before writing.
						if (inS.op != "=") it.readCount++
						}
					}
				else -> scanExpr(vT, inUsages)
				}
			scanExpr(inS.value, inUsages)
			}
		is ExprStmt        -> scanExpr(inS.expr, inUsages)
		is ReturnStmt      -> inS.value?.let { scanExpr(it, inUsages) }
		is ForStmt         -> { scanExpr(inS.iter, inUsages); scanUsageInBlock(inS.body, inUsages) }
		is WhileStmt       -> { scanExpr(inS.cond, inUsages); scanUsageInBlock(inS.body, inUsages) }
		is DoWhileStmt     -> { scanUsageInBlock(inS.body, inUsages); scanExpr(inS.cond, inUsages) }
		is DestructuringDeclStmt -> scanExpr(inS.init, inUsages)
		else               -> {}
		}
	}

private fun scanExpr(inE: Expr, inUsages: Map<String, LocalUsage>) {
	when (inE) {
		is NameExpr        -> inUsages[inE.name]?.let { it.readCount++ }
		is CallExpr        -> {
			// `c.addr(name)` takes a pointer to `name` and hands it to native code,
			// which may mutate it through the pointer. Count as an implicit write —
			// otherwise we'd falsely flag the var as never-reassigned.
			val vAddrTarget = addressOfTargetName(inE)
			if (vAddrTarget != null) {
				inUsages[vAddrTarget]?.let { it.writeCount++; it.readCount++ }
				}
			// Method call on a bare name (e.g. `sb.append(...)`, `arena.reset()`) —
			// in KTC a method receiver may be passed by reference and mutated through
			// it (StringBuffer.len, Arena bump pointer, etc.). Treat the receiver as
			// a possible write to avoid false-positive W028 on intentional `var`s.
			val vDotCallee = inE.callee as? DotExpr
			if (vDotCallee != null) {
				val vRecvName = (vDotCallee.obj as? NameExpr)?.name
				if (vRecvName != null) inUsages[vRecvName]?.let { it.writeCount++ }
				}
			scanExpr(inE.callee, inUsages)
			inE.args.forEach { scanExpr(it.expr, inUsages) }
			}
		is DotExpr         -> {
			// `name.asRef()` — same pointer-escape logic as `c.addr(name)`.
			if (inE.name == "asRef") {
				val vRecv = inE.obj as? NameExpr
				if (vRecv != null) inUsages[vRecv.name]?.let { it.writeCount++ }
				}
			scanExpr(inE.obj, inUsages)
			}
		is SafeDotExpr     -> scanExpr(inE.obj, inUsages)
		is IndexExpr       -> { scanExpr(inE.obj, inUsages); scanExpr(inE.index, inUsages) }
		is BinExpr         -> { scanExpr(inE.left, inUsages); scanExpr(inE.right, inUsages) }
		is PrefixExpr      -> {
			// ++x / --x mutates x in place; otherwise just a read.
			if ((inE.op == "++" || inE.op == "--") && inE.expr is NameExpr) {
				inUsages[inE.expr.name]?.let { it.writeCount++ }
				}
			scanExpr(inE.expr, inUsages)
			}
		is PostfixExpr     -> {
			if ((inE.op == "++" || inE.op == "--") && inE.expr is NameExpr) {
				inUsages[inE.expr.name]?.let { it.writeCount++ }
				}
			scanExpr(inE.expr, inUsages)
			}
		is NotNullExpr     -> scanExpr(inE.expr, inUsages)
		is ElvisExpr       -> { scanExpr(inE.left, inUsages); scanExpr(inE.right, inUsages) }
		is CastExpr        -> scanExpr(inE.expr, inUsages)
		is IsCheckExpr     -> scanExpr(inE.expr, inUsages)
		is IfExpr          -> {
			scanExpr(inE.cond, inUsages)
			scanUsageInBlock(inE.then, inUsages)
			inE.els?.let { scanUsageInBlock(it, inUsages) }
			}
		is WhenExpr        -> {
			inE.subject?.let { scanExpr(it, inUsages) }
			for (vBr in inE.branches) {
				vBr.conds?.forEach { vC -> when (vC) {
					is ExprCond -> scanExpr(vC.expr, inUsages)
					is InCond   -> scanExpr(vC.expr, inUsages)
					else        -> {}
					} }
				scanUsageInBlock(vBr.body, inUsages)
				}
			}
		is StrTemplateExpr -> inE.parts.forEach { p -> if (p is ExprPart) scanExpr(p.expr, inUsages) }
		is LambdaExpr      -> inE.body.forEach { scanStmt(it, inUsages) }
		else               -> {}
		}
	}
