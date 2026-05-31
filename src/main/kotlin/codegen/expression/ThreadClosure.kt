package com.bitsycore.ktc.codegen.expression

import com.bitsycore.ktc.ast.*
import com.bitsycore.ktc.codegen.*
import com.bitsycore.ktc.codegen.emit.emitFun
import com.bitsycore.ktc.codegen.statement.emitStmt
import com.bitsycore.ktc.types.KtcType

// ── thread { capture(...); body } closure lowering ───────────────
//
// KTC has no closures, so an OS-thread body normally has to be a top-level function reference plus an
// opaque `arg`. `thread { capture(a, b); body }` lets you write it as a lambda: the captured values
// are marshalled exactly like KTC function arguments (a value type is copied, a Ref<T> passes just the
// pointer — C struct-copy semantics give both for free), the body becomes a generated top-level entry
// function that unpacks them, and the context is a plain local struct in the SPAWNING frame (stack /
// alloca — no heap, no free). You must join() before that frame returns, C-style. Using an enclosing
// local that wasn't capture()d is a lint warning ("uncaptured").

// A captured variable: its name, resolved type, and the C access expression in the spawning scope.
internal data class ThreadCapture(
	val name:  String,    // variable name (also the context-struct field name)
	val ktc:   KtcType,   // resolved type — drives the C field/local type
	val cExpr: String     // C expression reading it in the spawning frame
	)

// A generated thread entry function, emitted after the main decl loop to avoid nesting inside the
// function that spawned the thread.
internal data class PendingThreadEntry(
	val entryCName: String,           // C name of the generated entry function
	val ctxType:    String,           // C name of the generated context struct
	val captures:   List<ThreadCapture>,
	val body:       List<Stmt>,       // lambda body (capture() calls filtered out at emit time)
	val srcKey:     String            // per-decl buffer key (which .c file to land in)
	)

/* True for a `capture(...)` statement (the marker call that declares a thread closure's captures). */
internal fun isCaptureCall(inStmt: Stmt): Boolean =
	inStmt is ExprStmt && inStmt.expr is CallExpr && (inStmt.expr.callee as? NameExpr)?.name == "capture"

/* Lower `thread { capture(...); body }`. Returns the C expression (a ktc_std_Thread value) or null when
this isn't the closure form (e.g. `thread(::fn, arg)`), letting normal dispatch handle it. */
internal fun CCodeGen.genThreadClosureOrNull(inCall: CallExpr): String? {
	val vCallee = inCall.callee as? NameExpr ?: return null
	if (vCallee.name != "thread") return null
	val vLambda = inCall.args.lastOrNull()?.expr as? LambdaExpr ?: return null  // closure form: trailing block

	// Kotlin signature `thread(start, name, priority, block)`: only `start` is honored in KTC (name /
	// priority are accepted for source parity but have no native backing yet).
	val vLeading  = inCall.args.dropLast(1)
	val vStartArg = vLeading.firstOrNull { it.name == "start" }?.expr
		?: vLeading.firstOrNull { it.name == null }?.expr
	val vStartC   = vStartArg?.let { genExpr(it) }

	val vCaptures = collectThreadCaptures(vLambda)
	checkUncapturedThreadRefs(vLambda, vCaptures.map { it.name }.toSet())

	val vId       = threadClosureCounter++
	val vCtxType  = funCName("ThreadCtx$vId")
	val vEntry    = funCName("threadEntry$vId")
	val vCtxVar   = "\$tcap$vId"
	val vThrVar   = "\$tthr$vId"

	// Context struct + entry forward declaration go in the shared package header (visible everywhere).
	val vFields = vCaptures.joinToString(" ") { "${cTypeStr(it.ktc)} ${it.name};" }
	hdr.appendLine("typedef struct { $vFields } $vCtxType;")
	hdr.appendLine("void $vEntry(void* arg);")

	// Defer the entry-function body so it isn't emitted inside the spawning function's buffer.
	pendingThreadEntries += PendingThreadEntry(vEntry, vCtxType, vCaptures, vLambda.body, "|$currentSourceFile")

	// Call site: a local context struct on the spawning frame (stack), filled with the captures, then
	// construct a Thread over the generated entry and (by default) start it. No heap, no free — the
	// caller must join() before this frame returns. Lowers to the same Thread ctor + start() the
	// explicit `Thread(::fn, arg).start()` form uses.
	preStmts += "$vCtxType $vCtxVar;"
	for (vCap in vCaptures) preStmts += "$vCtxVar.${vCap.name} = ${vCap.cExpr};"
	preStmts += "ktc_std_Thread $vThrVar = ktc_std_Thread_primaryConstructor($vEntry, (void*)&$vCtxVar);"
	if (vStartC == null) preStmts += "ktc_std_Thread_start(&$vThrVar);"
	else                 preStmts += "if ($vStartC) ktc_std_Thread_start(&$vThrVar);"
	return vThrVar
	}

/* Collect the captured variables from every capture(...) call in the lambda body, resolving each
against the spawning scope (type + C access expression). */
private fun CCodeGen.collectThreadCaptures(inLambda: LambdaExpr): List<ThreadCapture> {
	val vOut = mutableListOf<ThreadCapture>()
	for (vStmt in inLambda.body) {
		if (!isCaptureCall(vStmt)) continue
		val vCall = (vStmt as ExprStmt).expr as CallExpr
		for (vArg in vCall.args) {
			val vName = (vArg.expr as? NameExpr)?.name
				?: run { codegenError("capture(...) arguments must be simple variable names"); return@run "" }
			if (vName.isEmpty()) continue
			val vKtc = lookupVarKtc(vName)
				?: run { codegenError("capture: '$vName' is not a variable in scope"); return@run null }
			if (vKtc == null) continue
			if (vOut.any { it.name == vName }) continue                       // ignore duplicate captures
			vOut += ThreadCapture(vName, vKtc, genExpr(vArg.expr))
			}
		}
	return vOut
	}

/* E054: a non-inline (escaping) lambda body must capture every enclosing value it reads — capture is
explicit in KTC. Conservative — only flags names that resolve to a spawning-scope variable and are
neither captured nor declared inside the body (globals, functions, objects, members are never flagged). */
internal fun CCodeGen.checkUncapturedThreadRefs(inLambda: LambdaExpr, inCaptured: Set<String>) {
	val vDeclared = mutableSetOf("it")
	vDeclared += inLambda.params                                             // the lambda's own params are locals
	for (vStmt in inLambda.body) collectDeclaredNames(vStmt, vDeclared)
	val vRefs = linkedSetOf<String>()
	for (vStmt in inLambda.body) {
		if (isCaptureCall(vStmt)) continue                                   // the capture() list itself is fine
		collectRefNames(vStmt, vRefs)
		}
	for (vName in vRefs) {
		if (vName in inCaptured || vName in vDeclared) continue
		if (lookupVarKtc(vName) == null) continue                            // not a spawning-scope variable
		codegenError("E054", "'$vName' is used in the closure body but not captured — add capture($vName). " +
			"KTC closures capture explicitly (it would otherwise read uninitialized / freed memory).")
		}
	}

// ── name-collection walkers (lint only) ──────────────────────────

private fun collectDeclaredNames(inStmt: Stmt, ioOut: MutableSet<String>) {
	when (inStmt) {
		is VarDeclStmt          -> { ioOut += inStmt.name; inStmt.init?.let { collectDeclaredInExpr(it, ioOut) } }
		is DestructuringDeclStmt -> ioOut += inStmt.names
		is ForStmt              -> { ioOut += inStmt.varName; ioOut += inStmt.destructureNames; inStmt.body.stmts.forEach { collectDeclaredNames(it, ioOut) } }
		is WhileStmt            -> inStmt.body.stmts.forEach { collectDeclaredNames(it, ioOut) }
		is DoWhileStmt          -> inStmt.body.stmts.forEach { collectDeclaredNames(it, ioOut) }
		is DeferStmt            -> inStmt.body.stmts.forEach { collectDeclaredNames(it, ioOut) }
		is ExprStmt             -> collectDeclaredInExpr(inStmt.expr, ioOut)
		is ReturnStmt           -> inStmt.value?.let { collectDeclaredInExpr(it, ioOut) }
		is AssignStmt           -> { collectDeclaredInExpr(inStmt.value, ioOut) }
		else                    -> {}
		}
	}

// Nested lambda params are locals to their own body; collect them so they aren't flagged.
private fun collectDeclaredInExpr(inExpr: Expr, ioOut: MutableSet<String>) {
	when (inExpr) {
		is LambdaExpr -> { ioOut += inExpr.params; inExpr.body.forEach { collectDeclaredNames(it, ioOut) } }
		is CallExpr   -> { collectDeclaredInExpr(inExpr.callee, ioOut); inExpr.args.forEach { collectDeclaredInExpr(it.expr, ioOut) } }
		is BinExpr    -> { collectDeclaredInExpr(inExpr.left, ioOut); collectDeclaredInExpr(inExpr.right, ioOut) }
		is DotExpr    -> collectDeclaredInExpr(inExpr.obj, ioOut)
		is SafeDotExpr -> collectDeclaredInExpr(inExpr.obj, ioOut)
		is IndexExpr  -> { collectDeclaredInExpr(inExpr.obj, ioOut); collectDeclaredInExpr(inExpr.index, ioOut) }
		is IfExpr     -> { collectDeclaredInExpr(inExpr.cond, ioOut); inExpr.then.stmts.forEach { collectDeclaredNames(it, ioOut) }; inExpr.els?.stmts?.forEach { collectDeclaredNames(it, ioOut) } }
		is NotNullExpr -> collectDeclaredInExpr(inExpr.expr, ioOut)
		is ElvisExpr  -> { collectDeclaredInExpr(inExpr.left, ioOut); collectDeclaredInExpr(inExpr.right, ioOut) }
		is PrefixExpr -> collectDeclaredInExpr(inExpr.expr, ioOut)
		is PostfixExpr -> collectDeclaredInExpr(inExpr.expr, ioOut)
		is CastExpr   -> collectDeclaredInExpr(inExpr.expr, ioOut)
		else          -> {}
		}
	}

private fun collectRefNames(inStmt: Stmt, ioOut: MutableSet<String>) {
	when (inStmt) {
		is ExprStmt   -> collectRefInExpr(inStmt.expr, ioOut)
		is ReturnStmt -> inStmt.value?.let { collectRefInExpr(it, ioOut) }
		is VarDeclStmt -> inStmt.init?.let { collectRefInExpr(it, ioOut) }
		is DestructuringDeclStmt -> collectRefInExpr(inStmt.init, ioOut)
		is AssignStmt -> { collectRefInExpr(inStmt.target, ioOut); collectRefInExpr(inStmt.value, ioOut) }
		is ForStmt    -> { collectRefInExpr(inStmt.iter, ioOut); inStmt.body.stmts.forEach { collectRefNames(it, ioOut) } }
		is WhileStmt  -> { collectRefInExpr(inStmt.cond, ioOut); inStmt.body.stmts.forEach { collectRefNames(it, ioOut) } }
		is DoWhileStmt -> { collectRefInExpr(inStmt.cond, ioOut); inStmt.body.stmts.forEach { collectRefNames(it, ioOut) } }
		is DeferStmt  -> inStmt.body.stmts.forEach { collectRefNames(it, ioOut) }
		else          -> {}
		}
	}

private fun collectRefInExpr(inExpr: Expr, ioOut: MutableSet<String>) {
	when (inExpr) {
		is NameExpr   -> ioOut += inExpr.name
		is BinExpr    -> { collectRefInExpr(inExpr.left, ioOut); collectRefInExpr(inExpr.right, ioOut) }
		is PrefixExpr -> collectRefInExpr(inExpr.expr, ioOut)
		is PostfixExpr -> collectRefInExpr(inExpr.expr, ioOut)
		is CallExpr   -> { collectRefInExpr(inExpr.callee, ioOut); inExpr.args.forEach { collectRefInExpr(it.expr, ioOut) } }
		is DotExpr    -> collectRefInExpr(inExpr.obj, ioOut)       // only the root object is a name ref; .name is a member
		is SafeDotExpr -> collectRefInExpr(inExpr.obj, ioOut)
		is IndexExpr  -> { collectRefInExpr(inExpr.obj, ioOut); collectRefInExpr(inExpr.index, ioOut) }
		is IfExpr     -> { collectRefInExpr(inExpr.cond, ioOut); inExpr.then.stmts.forEach { collectRefNames(it, ioOut) }; inExpr.els?.stmts?.forEach { collectRefNames(it, ioOut) } }
		is WhenExpr   -> { inExpr.subject?.let { collectRefInExpr(it, ioOut) }; inExpr.branches.forEach { b -> b.body.stmts.forEach { collectRefNames(it, ioOut) } } }
		is NotNullExpr -> collectRefInExpr(inExpr.expr, ioOut)
		is ElvisExpr  -> { collectRefInExpr(inExpr.left, ioOut); collectRefInExpr(inExpr.right, ioOut) }
		is IsCheckExpr -> collectRefInExpr(inExpr.expr, ioOut)
		is CastExpr   -> collectRefInExpr(inExpr.expr, ioOut)
		is StrTemplateExpr -> inExpr.parts.forEach { if (it is ExprPart) collectRefInExpr(it.expr, ioOut) }
		is LambdaExpr -> inExpr.body.forEach { collectRefNames(it, ioOut) }   // nested-lambda params handled via vDeclared
		else          -> {}
		}
	}

// ── General closures: a value-position lambda lowered to a functor ───────────────
//
// Each lambda becomes its OWN specialized struct (the capture fields) plus a generated
// `R Closure_N_invoke(Closure_N* self, params…)`; the closure VALUE is that struct, passed as real
// data. Calling `f(args)` lowers to `Closure_N_invoke(&f, args)`. Phase 1: frame-bound — the struct is
// a plain local (stack), so the closure must not outlive its frame. Same capture marshalling as thread
// (value copied, Ref<T> by pointer) and the same mandatory-capture rule (E054).

// A lambda parameter of the generated invoke function.
internal data class ClosureParam(val cType: String, val name: String, val ktc: KtcType)

// A generated closure invoke function, emitted after the main decl loop (avoids nesting in the
// defining function's buffer).
internal data class PendingClosure(
	val invokeFn:   String,
	val structType: String,
	val retCType:   String,
	val retKtc:     KtcType,
	val params:     List<ClosureParam>,
	val captures:   List<ThreadCapture>,
	val body:       List<Stmt>,
	val srcKey:     String
	)

/* Lower a value-position lambda of function type [inFunc] to a functor. Emits the struct typedef + an
invoke forward decl, defers the invoke body, fills a stack instance from the captures, and registers the
struct so calls dispatch through _invoke. Returns (structTypeCName, instanceExprName). */
internal fun CCodeGen.genClosureValue(inLambda: LambdaExpr, inFunc: KtcType.Func): Pair<String, String> {
	val vCaptures = collectThreadCaptures(inLambda)
	checkUncapturedThreadRefs(inLambda, vCaptures.map { it.name }.toSet())

	val vId      = threadClosureCounter++
	val vStruct  = funCName("Closure$vId")
	val vInvoke  = "${vStruct}_invoke"
	val vCloVar  = "\$clo$vId"
	val vRetKtc  = inFunc.ret
	val vParams  = inLambda.params.mapIndexed { vI, vP ->
		val vPKtc = inFunc.params.getOrNull(vI) ?: KtcType.Void
		ClosureParam(cTypeStr(vPKtc), vP, vPKtc)
		}
	val vParamSig = vParams.joinToString("") { ", ${it.cType} ${it.name}" }

	val vFields = vCaptures.joinToString(" ") { "${cTypeStr(it.ktc)} ${it.name};" }
	hdr.appendLine("typedef struct { $vFields } $vStruct;")
	hdr.appendLine("${cTypeStr(vRetKtc)} $vInvoke($vStruct* self$vParamSig);")

	closureStructTypes += vStruct
	pendingClosures += PendingClosure(vInvoke, vStruct, cTypeStr(vRetKtc), vRetKtc, vParams, vCaptures, inLambda.body, "|$currentSourceFile")

	preStmts += "$vStruct $vCloVar;"
	for (vCap in vCaptures) preStmts += "$vCloVar.${vCap.name} = ${vCap.cExpr};"
	return vStruct to vCloVar
	}

// ── Higher-order: a non-inline function called with a capturing lambda ───────────
//
// Each lambda is a distinct functor type, so a function that receives one is monomorphized per closure
// type (KTC's existing per-type specialization, the C++-template model): the closure parameter is
// retyped to the functor struct, and inside the body `param(x)` then dispatches through the struct's
// _invoke (handled by genCall). The closure is passed by value (its captures copied into the callee).
// Phase 1: frame-bound only — single closure param, non-generic, non-overloaded top-level function.

internal data class PendingClosureFnInst(
	val fn:         FunDecl,
	val paramIdx:   Int,
	val structType: String,
	val mangled:    String,
	val srcKey:     String
	)

/* Lower `F(lambda, …)` where F is a (non-inline) top-level function with a function-typed parameter:
generate the lambda's functor, monomorphize F for that closure type, and call the instance. Returns the
C call expression or null when this isn't that shape (so normal dispatch handles it). */
internal fun CCodeGen.genHigherOrderClosureCallOrNull(inName: String, inArgs: List<Arg>, inCall: CallExpr): String? {
	val vFuns = file.decls.filterIsInstance<FunDecl>().filter { it.name == inName }
	if (vFuns.size != 1) return null                                         // skip overloaded (Phase 1)
	val vFn = vFuns[0]
	if (vFn.isInline || vFn.typeParams.isNotEmpty() || vFn.receiver != null) return null
	val vIdx = vFn.params.indexOfFirst { resolveTypeName(it.type) is KtcType.Func }
	if (vIdx < 0 || vIdx >= inArgs.size) return null
	val vLambda = inArgs[vIdx].expr as? LambdaExpr ?: return null
	// Only one closure param in Phase 1.
	if (vFn.params.withIndex().count { (j, p) -> resolveTypeName(p.type) is KtcType.Func && inArgs.getOrNull(j)?.expr is LambdaExpr } != 1) return null

	val vFuncType = resolveTypeName(vFn.params[vIdx].type) as KtcType.Func
	val (vStruct, vInstance) = genClosureValue(vLambda, vFuncType)
	val vMangled = "${inName}__$vStruct"
	if (closureFnInstNames.add(vMangled))
		pendingClosureFnInsts += PendingClosureFnInst(vFn, vIdx, vStruct, vMangled, "|$currentSourceFile")

	val vArgsC = inArgs.mapIndexed { vJ, vA -> if (vJ == vIdx) vInstance else genExpr(vA.expr) }.joinToString(", ")
	return "${funCName(vMangled)}($vArgsC)"
	}

/* Emit the deferred higher-order monomorphizations: re-emit each function with its closure parameter
retyped to the functor struct, so `param(x)` in the body dispatches through _invoke. Iterate a snapshot,
not the live list: emitting a body can itself queue new monomorphizations (chained higher-order calls)
and the fixpoint loop in generate() flushes those — appending to the list mid-iteration would otherwise
throw ConcurrentModificationException. */
internal fun CCodeGen.emitPendingClosureFnInsts() {
	val vBatch = pendingClosureFnInsts.toList()
	pendingClosureFnInsts.clear()
	for (vInst in vBatch) {
		captureForDecl(vInst.srcKey) {
			val vModParams = vInst.fn.params.mapIndexed { vJ, vP ->
				if (vJ == vInst.paramIdx) vP.copy(type = TypeRef(vInst.structType)) else vP
				}
			emitFun(vInst.fn.copy(name = vInst.mangled, params = vModParams, isInline = false))
			}
		}
	}

/* Emit the deferred closure invoke-function definitions (after the main decl loop). Iterate a snapshot,
not the live list: an invoke body may itself define a nested closure / higher-order call that queues
more pending entries (flushed by the generate() fixpoint loop), so appending mid-iteration would throw. */
internal fun CCodeGen.emitPendingClosures() {
	val vBatch = pendingClosures.toList()
	pendingClosures.clear()
	for (vC in vBatch) {
		captureForDecl(vC.srcKey) {
			val vPrev          = saveFunState()
			val vSavedRetVar   = inlineReturnVar
			val vSavedEndLabel = inlineEndLabel
			val vSavedLabelUsed = inlineLabelUsed
			inlineReturnVar = null; inlineEndLabel = null; inlineLabelUsed = false
			currentFnReturnType    = vC.retKtc.toInternalStr
			currentFnReturnKtcType = vC.retKtc
			val vParamSig = vC.params.joinToString("") { ", ${it.cType} ${it.name}" }
			impl.appendLine("// ══ generated closure invoke ══")
			impl.appendLine("${vC.retCType} ${vC.invokeFn}(${vC.structType}* self$vParamSig) {")
			pushScope()
			for (vCap in vC.captures) {
				impl.appendLine("    ${cTypeStr(vCap.ktc)} ${vCap.name} = self->${vCap.name};")
				defineVar(vCap.name, LocalVar(vCap.ktc))
				}
			for (vP in vC.params) defineVar(vP.name, LocalVar(vP.ktc))
			val vIsVoid = vC.retKtc is KtcType.Void
			val vBody   = vC.body.filter { !isCaptureCall(it) }
			vBody.forEachIndexed { vI, vStmt ->
				// the lambda's trailing expression is its result — turn it into a `return`.
				if (!vIsVoid && vI == vBody.lastIndex && vStmt is ExprStmt) emitStmt(ReturnStmt(vStmt.expr), "    ")
				else emitStmt(vStmt, "    ")
				}
			closeFunBody(vPrev)
			inlineReturnVar = vSavedRetVar; inlineEndLabel = vSavedEndLabel; inlineLabelUsed = vSavedLabelUsed
			}
		}
	}

/* Emit the deferred thread entry-function definitions. Called after the main declaration loop, when no
other function is mid-emission (so each lands cleanly in its source file's buffer). Iterate a snapshot,
not the live list: a thread body may queue further closures/entries (flushed by the generate() fixpoint
loop), so appending mid-iteration would throw ConcurrentModificationException. */
internal fun CCodeGen.emitPendingThreadEntries() {
	val vBatch = pendingThreadEntries.toList()
	pendingThreadEntries.clear()
	for (vPend in vBatch) {
		captureForDecl(vPend.srcKey) {
			val vPrev          = saveFunState()
			val vSavedRetVar   = inlineReturnVar
			val vSavedEndLabel = inlineEndLabel
			val vSavedLabelUsed = inlineLabelUsed
			inlineReturnVar = null; inlineEndLabel = null; inlineLabelUsed = false
			impl.appendLine("// ══ generated thread entry ══")
			impl.appendLine("void ${vPend.entryCName}(void* arg) {")
			pushScope()
			impl.appendLine("    ${vPend.ctxType}* \$c = (${vPend.ctxType}*)arg;")
			for (vCap in vPend.captures) {
				impl.appendLine("    ${cTypeStr(vCap.ktc)} ${vCap.name} = \$c->${vCap.name};")
				defineVar(vCap.name, LocalVar(vCap.ktc))
				}
			for (vStmt in vPend.body) {
				if (isCaptureCall(vStmt)) continue
				emitStmt(vStmt, "    ")
				}
			closeFunBody(vPrev)
			inlineReturnVar = vSavedRetVar; inlineEndLabel = vSavedEndLabel; inlineLabelUsed = vSavedLabelUsed
			}
		}
	}
