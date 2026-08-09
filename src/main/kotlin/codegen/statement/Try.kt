package com.bitsycore.ktc.codegen.statement

import com.bitsycore.ktc.ast.*
import com.bitsycore.ktc.codegen.*
import com.bitsycore.ktc.codegen.expression.genExpr
import com.bitsycore.ktc.dumpExpr
import com.bitsycore.ktc.types.KtcType

/* try/catch/finally and throw emission - lowered to setjmp/longjmp through the
KTC_TRY macro family in ktc_core_exception.h.

Lowering shape:

    KTC_TRY($exc0) {                                   try {
        ...body...                                         ...
    }                                                  }
    KTC_CATCH($exc0, KTC_EXC_TYPE_ID() == X_TYPE_ID) { catch (e: X) {
        X e; ...take off the arena...                      ...
        ...body...                                     }
    }
    KTC_FINALLY($exc0) {                               finally {
        ...body...                                         ...
    }                                                  }
    KTC_END_TRY;

Type matching is whole-program static: a catch on a concrete class compares
KTC_EXC_TYPE_ID() against that class; a catch on an interface (Throwable /
Exception / user sub-interfaces) ORs over every known implementing class.

`throw` evaluates the exception into a frame local, then calls
ktc_core_exc_throw with sizeof/TYPE_ID/offsetof(message) so the runtime can
deep-copy the object plus its message bytes into the TLS arena. */

// ==================
// MARK: Try context (return/break/continue interplay)
// ==================

/* One enclosing `try` during body emission. Tracked so a `return` can pop the
exception frame(s) and re-emit finally bodies, and so break/continue can be
refused when they would jump across the try construct. */
internal data class TryContext(
	val frameVar:         String,   // C name of the ktc_ExcFrame local for this try
	val finallyBlock:     Block?,   // finally body - re-emitted at return sites
	val loopDepthAtEntry: Int,      // loopDepth when this try was entered
	var emittingFinally:  Boolean = false  // true while the finally body is being emitted
)

/* Emitted on every return path (from emitDeferredBlocks) BEFORE the deferred
blocks: pops the exception frame of each enclosing try (innermost first) and
re-emits its finally body, mirroring what KTC_END_TRY would have done on the
normal path. [inDownToMark] limits the unwind for inline-expansion returns
(goto endLabel), which only leave the tries opened inside the expansion. */
internal fun CCodeGen.emitTryReturnCleanup(ind: String, insideMethod: Boolean = false, inDownToMark: Int = 0) {
	if (tryContexts.size <= inDownToMark) return
	val vCtxs = tryContexts.drop(inDownToMark).reversed()  // innermost-first snapshot
	for (vCtx in vCtxs) {
		impl.appendLine("${ind}KTC_TRY_LEAVE(${vCtx.frameVar});")
		if (vCtx.finallyBlock != null) {
			vCtx.emittingFinally = true
			for (vStmt in vCtx.finallyBlock.stmts) emitStmt(vStmt, ind, insideMethod)
			vCtx.emittingFinally = false
		}
	}
}

// ==================
// MARK: Throwable hierarchy queries
// ==================

/* Transitive closure of an interface name through its super-interfaces. */
private fun CCodeGen.ifaceClosure(inName: String): Set<String> {
	val vOut = mutableSetOf<String>()
	fun walk(inN: String) {
		if (!vOut.add(inN)) return
		interfaces[inN]?.superInterfaces?.forEach { walk(it.name) }
	}
	walk(inName)
	return vOut
}

/* All interfaces a class implements, including inherited super-interfaces. */
private fun CCodeGen.classIfaceClosure(inClass: String): Set<String> =
	(classInterfaces[inClass] ?: emptyList()).flatMapTo(mutableSetOf()) { ifaceClosure(it) }

/* True when [inClass] is a concrete exception class (implements Throwable). */
internal fun CCodeGen.isThrowableClass(inClass: String): Boolean =
	"Throwable" in classIfaceClosure(inClass)

/* True when [inIface] is Throwable or one of its sub-interfaces. */
internal fun CCodeGen.isThrowableIface(inIface: String): Boolean =
	interfaces.containsKey(inIface) && "Throwable" in ifaceClosure(inIface)

/* Every concrete class matched by a catch/throw on [inType]: the class itself
for a concrete type, or all transitive implementors for an interface.
Returns null when the type is neither (caller reports E131/E130). */
private fun CCodeGen.matchedThrowableClasses(inType: String): List<String>? = when {
	classes.containsKey(inType)    -> if (isThrowableClass(inType)) listOf(inType) else null
	interfaces.containsKey(inType) -> if (isThrowableIface(inType))
		classes.keys.filter { inType in classIfaceClosure(it) }.sorted() else null
	else -> null
}

/* The `message` property must be a stored field (constructor `val`) so the
runtime can patch it by offset when relocating the exception. */
private fun CCodeGen.requireStoredMessage(inClass: String) {
	val vCi = classes[inClass] ?: return
	if (vCi.storedProps.none { it.first == "message" })
		codegenError("E130",
			"Exception class '$inClass' must store 'message' as a constructor property " +
			"(override val message: String) - a computed getter cannot be relocated when the " +
			"exception is copied into the throw arena.")
}

// ==================
// MARK: try / catch / finally
// ==================

internal fun CCodeGen.emitTry(s: TryStmt, ind: String, insideMethod: Boolean) {
	// Validate catch types up front so errors point at the try, not mid-emission.
	for (vC in s.catches) {
		if (matchedThrowableClasses(vC.type.name) == null)
			codegenError("E131",
				"catch type '${vC.type.name}' is not part of the Throwable hierarchy - catch a " +
				"class or interface implementing ktc.Throwable (e.g. Exception, RuntimeException).")
	}

	val vFrame = "\$exc${tmpCounter++}"  // unique frame name → nested tries + KTC_TRY_LEAVE addressing
	impl.appendLine("${ind}KTC_TRY($vFrame) {")
	val vCtx = TryContext(vFrame, s.finallyBlock, loopDepth)
	tryContexts.add(vCtx)
	emitBlock(s.body, ind, insideMethod)
	impl.appendLine("$ind}")

	// Catch clauses - matched in source order; track coverage to flag dead clauses.
	val vCovered = mutableSetOf<String>()
	for (vC in s.catches) {
		val vClasses = matchedThrowableClasses(vC.type.name)!!
		if (vClasses.isEmpty())
			codegenWarning("catch-no-match",
				"catch (${vC.name}: ${vC.type.name}) matches no known exception class - no class implements '${vC.type.name}'.")
		else if (vCovered.containsAll(vClasses))
			codegenWarning("unreachable-catch",
				"catch (${vC.name}: ${vC.type.name}) is unreachable - every matching class is already handled by an earlier clause.")
		val vCond = if (vClasses.isEmpty()) "false"
			else vClasses.joinToString(" || ") { "KTC_EXC_TYPE_ID() == ${typeFlatName(it)}_TYPE_ID" }
		vCovered += vClasses
		impl.appendLine("$ind/* ${kotlinEcho("catch (${vC.name}: ${typeRefToStr(vC.type)})")} */")
		impl.appendLine("${ind}KTC_CATCH($vFrame, $vCond) {")
		pushScope()
		emitCatchBinding(vC, ind)
		emitBlock(vC.body, ind, insideMethod)
		popScope()
		impl.appendLine("$ind}")
	}

	if (s.finallyBlock != null) {
		impl.appendLine("${ind}KTC_FINALLY($vFrame) {")
		vCtx.emittingFinally = true
		emitBlock(s.finallyBlock, ind, insideMethod)
		vCtx.emittingFinally = false
		impl.appendLine("$ind}")
	}

	tryContexts.removeAt(tryContexts.size - 1)
	impl.appendLine("${ind}KTC_END_TRY;")
}

/* Declare the caught binding and copy the exception off the arena onto this
frame (object as a local, message bytes alloca'd) so the catch body can use it
freely and the arena is immediately reusable for a rethrow / new throw.
Skipped entirely when the binding is unused ("_" or never referenced). */
private fun CCodeGen.emitCatchBinding(inClause: CatchClause, ind: String) {
	val vName = inClause.name
	val vUsed = vName != "_" && blockReferencesName(inClause.body, vName)
	if (!vUsed) return
	val vTypeName = inClause.type.name
	val vInd = "$ind    "
	val vMsgBuf = tmp()
	if (classes.containsKey(vTypeName)) {
		val vCType = typeFlatName(vTypeName)
		impl.appendLine("$vInd$vCType $vName;")
		impl.appendLine("$vInd{")
		impl.appendLine("$vInd    ktc_Char* $vMsgBuf = (ktc_Char*)ktc_core_alloca((size_t)(KTC_EXC_MSG_LEN() + 1));")
		impl.appendLine("$vInd    ktc_core_exc_take(&$vName, $vMsgBuf);")
		impl.appendLine("$vInd}")
	} else {
		// Interface binding: fill the tagged-union fat value by hand - typeId from the
		// in-flight exception, object bytes into the union, vtable picked by typeId.
		val vCIface = typeFlatName(vTypeName)
		val vImpls  = matchedThrowableClasses(vTypeName)!!
		val vVtSel  = vImpls.joinToString(" :\n$vInd        ") {
			"KTC_EXC_TYPE_ID() == ${typeFlatName(it)}_TYPE_ID ? &${typeFlatName(it)}_${vTypeName}_vt"
		} + " : NULL"
		impl.appendLine("$vInd$vCIface $vName;")
		impl.appendLine("$vInd{")
		impl.appendLine("$vInd    $vName.__typeId = (ktc_UInt)KTC_EXC_TYPE_ID();")
		impl.appendLine("$vInd    ktc_Char* $vMsgBuf = (ktc_Char*)ktc_core_alloca((size_t)(KTC_EXC_MSG_LEN() + 1));")
		impl.appendLine("$vInd    ktc_core_exc_take(&$vName.data, $vMsgBuf);")
		impl.appendLine("$vInd    $vName.vt = $vVtSel;")
		impl.appendLine("$vInd}")
	}
	defineVar(vName, vTypeName)
}

// ==================
// MARK: throw
// ==================

internal fun CCodeGen.emitThrow(s: ThrowStmt, ind: String) {
	val vTypeName = inferExprType(s.value)?.removeSuffix("?")
		?: codegenError("E130", "Cannot infer the type of the thrown expression - throw a value of a class implementing ktc.Throwable.")

	if (classes.containsKey(vTypeName)) {
		if (!isThrowableClass(vTypeName))
			codegenError("E130",
				"'$vTypeName' does not implement ktc.Throwable - only Throwable values can be thrown " +
				"(declare it as `class $vTypeName(override val message: String) : Exception`).")
		requireStoredMessage(vTypeName)
		val vCType = typeFlatName(vTypeName)
		val vExpr  = genExpr(s.value)
		flushPreStmts(ind)
		// Reuse an existing local directly; otherwise materialize the rvalue first.
		val vT = if (s.value is NameExpr && lookupLocalVar((s.value as NameExpr).name) != null) vExpr
			else tmp().also { impl.appendLine("$ind$vCType $it = $vExpr;") }
		impl.appendLine("$ind/* ${kotlinEcho("throw " + dumpExpr(s.value))} */")
		impl.appendLine("${ind}ktc_core_exc_throw(&$vT, (ktc_Int)sizeof($vCType), ${vCType}_TYPE_ID, " +
			"(ktc_Int)offsetof($vCType, message), $vT.message.ptr, $vT.message.len, " +
			"\"${vTypeName.removeSuffix("\$Impl")}\", \"$currentSourceFile\", $currentStmtLine);")
		return
	}

	if (interfaces.containsKey(vTypeName)) {
		// Rethrow of an interface-typed binding: dispatch on the concrete typeId so the
		// runtime gets the right sizeof/offsetof for the deep copy.
		val vImpls = matchedThrowableClasses(vTypeName)
			?: codegenError("E130", "'$vTypeName' is not part of the Throwable hierarchy - only Throwable values can be thrown.")
		vImpls.forEach { requireStoredMessage(it) }
		val vExpr = genExpr(s.value)
		flushPreStmts(ind)
		val vT = if (s.value is NameExpr && lookupLocalVar((s.value as NameExpr).name) != null) vExpr
			else tmp().also { impl.appendLine("$ind${typeFlatName(vTypeName)} $it = $vExpr;") }
		impl.appendLine("$ind/* ${kotlinEcho("throw " + dumpExpr(s.value))} */")
		impl.appendLine("${ind}switch (KTC_GET_TYPEID($vT.__typeId)) {")
		for (vImpl in vImpls) {
			val vC = typeFlatName(vImpl)
			impl.appendLine("$ind    case ${vC}_TYPE_ID: ktc_core_exc_throw(($vC*)&$vT.data, " +
				"(ktc_Int)sizeof($vC), ${vC}_TYPE_ID, (ktc_Int)offsetof($vC, message), " +
				"(($vC*)&$vT.data)->message.ptr, (($vC*)&$vT.data)->message.len, " +
				"\"${vImpl.removeSuffix("\$Impl")}\", \"$currentSourceFile\", $currentStmtLine);")
		}
		impl.appendLine("$ind    default: break;")
		impl.appendLine("$ind}")
		return
	}

	codegenError("E130",
		"Cannot throw a value of type '$vTypeName' - only classes implementing ktc.Throwable can be thrown.")
}

// ==================
// MARK: throw in expression position (?: throw)
// ==================

/* Run [inBlock] with `impl` redirected to a buffer; returns the emitted lines.
Lets statement-emitters (emitThrow) be reused inside expression lowering. */
private fun CCodeGen.captureImplLines(inBlock: () -> Unit): List<String> {
	val vBuf = StringBuilder()
	val vSaved = impl
	impl = vBuf
	try { inBlock() } finally { impl = vSaved }
	return vBuf.lines().filter { it.isNotBlank() }
}

/* Lower `left ?: throw X(...)` - evaluate left once into a temp, throw on
null, yield the unwrapped value. The throw lowering lands in preStmts. */
internal fun CCodeGen.genElvisThrow(inLeft: Expr, inThrow: ThrowExpr): String {
	val vLKtc = inferExprTypeKtc(inLeft)
	val vCore = vLKtc.stripNullable
		?: codegenError("Cannot infer the type of the left operand of '?: throw'")
	val vL = genExpr(inLeft)
	val vT = tmp()
	val vThrowStmt = ThrowStmt(inThrow.value).also { it.line = currentStmtLine }
	val vThrowLines = captureImplLines { emitThrow(vThrowStmt, "    ") }
	return if (vLKtc is KtcType.Nullable && isValueNullableKtc(vLKtc)) {
		// Value-nullable left: Optional struct - unwrap after the null gate.
		preStmts += "${optCTypeName(vCore.toInternalStr)} $vT = $vL;"
		preStmts += "if (!KTC_IS_SOME($vT)) {"
		preStmts += vThrowLines
		preStmts += "}"
		"KTC_UNWRAP($vT)"
	} else {
		// Pointer-nullable left (Ref<T?>, Ref<Iface>?): NULL gate on the pointer.
		preStmts += "${cTypeStr(vCore)} $vT = $vL;"
		preStmts += "if (!($vT)) {"
		preStmts += vThrowLines
		preStmts += "}"
		vT
	}
}

// ==================
// MARK: AST queries
// ==================

/* Render a Kotlin source echo for a generated-C comment: one line, truncated,
with any '*' '/' pairing broken so it can't terminate the comment early. */
private fun kotlinEcho(inText: String): String {
	val vOneLine = inText.replace("\r", "").replace("\n", " ").replace("*/", "*\\/")
	return if (vOneLine.length > 100) vOneLine.take(97) + "..." else vOneLine
}

/* True when [inBlock] references the name [inName] anywhere (decides whether a
catch binding needs the copy-off-the-arena prologue). Over-approximates:
shadowing redeclarations still count as a use. The three helpers are
file-level so they can be mutually recursive. */
private fun blockReferencesName(inBlock: Block, inName: String): Boolean =
	inBlock.stmts.any { stmtReferencesName(it, inName) }

private fun stmtReferencesName(s: Stmt, inName: String): Boolean = when (s) {
	is ExprStmt              -> exprReferencesName(s.expr, inName)
	is VarDeclStmt           -> exprReferencesName(s.init, inName)
	is DestructuringDeclStmt -> exprReferencesName(s.init, inName)
	is AssignStmt            -> exprReferencesName(s.target, inName) || exprReferencesName(s.value, inName)
	is ReturnStmt            -> exprReferencesName(s.value, inName)
	is ThrowStmt             -> exprReferencesName(s.value, inName)
	is ForStmt               -> exprReferencesName(s.iter, inName) || blockReferencesName(s.body, inName)
	is WhileStmt             -> exprReferencesName(s.cond, inName) || blockReferencesName(s.body, inName)
	is DoWhileStmt           -> exprReferencesName(s.cond, inName) || blockReferencesName(s.body, inName)
	is DeferStmt             -> blockReferencesName(s.body, inName)
	is TryStmt               -> blockReferencesName(s.body, inName) ||
		s.catches.any { blockReferencesName(it.body, inName) } ||
		(s.finallyBlock != null && blockReferencesName(s.finallyBlock, inName))
	else                     -> false
}

private fun exprReferencesName(e: Expr?, inName: String): Boolean = when (e) {
	null               -> false
	is NameExpr        -> e.name == inName
	is CallExpr        -> exprReferencesName(e.callee, inName) || e.args.any { exprReferencesName(it.expr, inName) }
	is BinExpr         -> exprReferencesName(e.left, inName) || exprReferencesName(e.right, inName)
	is DotExpr         -> exprReferencesName(e.obj, inName)
	is SafeDotExpr     -> exprReferencesName(e.obj, inName)
	is IndexExpr       -> exprReferencesName(e.obj, inName) || exprReferencesName(e.index, inName)
	is PrefixExpr      -> exprReferencesName(e.expr, inName)
	is PostfixExpr     -> exprReferencesName(e.expr, inName)
	is NotNullExpr     -> exprReferencesName(e.expr, inName)
	is ThrowExpr       -> exprReferencesName(e.value, inName)
	is ElvisExpr       -> exprReferencesName(e.left, inName) || exprReferencesName(e.right, inName)
	is CastExpr        -> exprReferencesName(e.expr, inName)
	is IsCheckExpr     -> exprReferencesName(e.expr, inName)
	is IfExpr          -> exprReferencesName(e.cond, inName) || blockReferencesName(e.then, inName) ||
		(e.els != null && blockReferencesName(e.els, inName))
	is WhenExpr        -> exprReferencesName(e.subject, inName) || e.branches.any { vB ->
		blockReferencesName(vB.body, inName) || vB.conds.orEmpty().any { vCond ->
			when (vCond) { is ExprCond -> exprReferencesName(vCond.expr, inName)
				is InCond -> exprReferencesName(vCond.expr, inName)
				else -> false } } }
	is StrTemplateExpr -> e.parts.any { it is ExprPart && exprReferencesName(it.expr, inName) }
	is LambdaExpr      -> e.body.any { stmtReferencesName(it, inName) }
	else               -> false
}

/* True when a function body lexically contains a `try` - directly, inside a
lambda argument, or through a call to an `inline fun` whose body contains one
(inline bodies are expanded into this function, so its frame holds the setjmp).
Drives the KTC_TRY_FN attribute (per-function deoptimization: setjmp/longjmp
clobbers register-cached locals at -O2 otherwise). */
internal fun CCodeGen.bodyContainsTry(inBody: Block?): Boolean =
	inBody != null && bodyContainsTry(inBody.stmts, mutableSetOf())

internal fun CCodeGen.bodyContainsTry(inStmts: List<Stmt>, inVisited: MutableSet<String>): Boolean {
	fun inExpr(e: Expr?): Boolean = when (e) {
		null               -> false
		is CallExpr        -> {
			val vName = when (val vC = e.callee) {
				is NameExpr -> vC.name
				is DotExpr  -> vC.name
				is SafeDotExpr -> vC.name
				else -> null
			}
			val vInlineHasTry = vName != null && inVisited.add(vName) &&
				(inlineFunDecls[vName].orEmpty() + allInlineExtFunsNamed(vName))
					.any { it.body != null && bodyContainsTry(it.body.stmts, inVisited) }
			vInlineHasTry || inExpr(e.callee) || e.args.any { inExpr(it.expr) }
		}
		is BinExpr         -> inExpr(e.left) || inExpr(e.right)
		is DotExpr         -> inExpr(e.obj)
		is SafeDotExpr     -> inExpr(e.obj)
		is IndexExpr       -> inExpr(e.obj) || inExpr(e.index)
		is PrefixExpr      -> inExpr(e.expr)
		is PostfixExpr     -> inExpr(e.expr)
		is NotNullExpr     -> inExpr(e.expr)
		is ThrowExpr       -> inExpr(e.value)
		is ElvisExpr       -> inExpr(e.left) || inExpr(e.right)
		is CastExpr        -> inExpr(e.expr)
		is IfExpr          -> inExpr(e.cond) || bodyContainsTry(e.then.stmts, inVisited) ||
			(e.els != null && bodyContainsTry(e.els.stmts, inVisited))
		is WhenExpr        -> inExpr(e.subject) || e.branches.any { bodyContainsTry(it.body.stmts, inVisited) }
		is StrTemplateExpr -> e.parts.any { it is ExprPart && inExpr(it.expr) }
		is LambdaExpr      -> bodyContainsTry(e.body, inVisited)
		else               -> false
	}
	return inStmts.any { s ->
		when (s) {
			is TryStmt               -> true
			is ThrowStmt             -> inExpr(s.value)
			is ExprStmt              -> inExpr(s.expr)
			is VarDeclStmt           -> inExpr(s.init)
			is DestructuringDeclStmt -> inExpr(s.init)
			is AssignStmt            -> inExpr(s.target) || inExpr(s.value)
			is ReturnStmt            -> inExpr(s.value)
			is ForStmt               -> inExpr(s.iter) || bodyContainsTry(s.body.stmts, inVisited)
			is WhileStmt             -> inExpr(s.cond) || bodyContainsTry(s.body.stmts, inVisited)
			is DoWhileStmt           -> inExpr(s.cond) || bodyContainsTry(s.body.stmts, inVisited)
			is DeferStmt             -> bodyContainsTry(s.body.stmts, inVisited)
			else                     -> false
		}
	}
}

/* All inline extension functions with the given simple name (receiver-agnostic -
an over-approximation is fine here: worst case a function is needlessly deoptimized). */
private fun CCodeGen.allInlineExtFunsNamed(inName: String): List<FunDecl> =
	extensionFuns.values.flatten().filter { it.isInline && it.name == inName }

/* "KTC_TRY_FN " when the body needs the setjmp-safe attribute, else "". */
internal fun CCodeGen.tryFnAttr(inBody: Block?): String =
	if (bodyContainsTry(inBody)) "KTC_TRY_FN " else ""

internal fun CCodeGen.tryFnAttr(inStmts: List<Stmt>): String =
	if (bodyContainsTry(inStmts, mutableSetOf())) "KTC_TRY_FN " else ""
