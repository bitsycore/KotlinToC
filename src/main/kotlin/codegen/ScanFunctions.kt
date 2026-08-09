package com.bitsycore.ktc.codegen

import com.bitsycore.ktc.ast.*
import com.bitsycore.ktc.codegen.statement.bindLambdaReturnTypeParams
import com.bitsycore.ktc.types.KtcType

// Pre-scan passes for generic function call-site discovery and body scanning.

/*
Scan call sites for generic functions to determine concrete type bindings.
For `fun <T> sizeOfList(list: MutableList<T>)` called with a MutableList_Int arg,
we infer T=Int and record the instantiation.
*/
internal fun CCodeGen.scanForGenericFunCalls() {
	val genFunsByName = genericFunDecls.associateBy { it.name }
	if (genFunsByName.isEmpty()) return

	/* Infer type args for a call to f. If inRecvType is non-null, also matches against f.receiver. */
	fun inferTypeArgs(
		inF              : FunDecl,
		inCallArgs       : List<Arg>,
		inExplicitTypeArgs: List<TypeRef>,
		inRecvType       : String? = null
		): List<String>? {
		if (inExplicitTypeArgs.isNotEmpty()) return inExplicitTypeArgs.map { it.name }
		val vSubst = mutableMapOf<String, String>() // accumulated type param substitutions
		if (inRecvType != null && inF.receiver != null) {
			materializeGenericInstantiations()
			matchTypeParam(inF.receiver, inRecvType, inF.typeParams.toSet(), vSubst)
			}
		for ((i, vParam) in inF.params.withIndex()) {
			if (i >= inCallArgs.size) break
			val vArgExpr = inCallArgs[i].expr
			val vArgType = inferExprType(vArgExpr) ?: continue
			inferExprTypeKtc(vArgExpr)
			/* Materialize before matching so genericTypeBindings is populated. */
			materializeGenericInstantiations()
			matchTypeParam(vParam.type, vArgType, inF.typeParams.toSet(), vSubst)
			}
		if (vSubst.size == inF.typeParams.size) return inF.typeParams.map { vSubst[it]!! }
		return null
		}

	// Use a nested object to allow mutual recursion between scanExpr/scanStmt/scanBlock
	val scanner = object {
		fun scanExpr(e: Expr?) {
			if (e == null) return
			when (e) {
				is CallExpr -> {
					val name = (e.callee as? NameExpr)?.name
					if (name != null && genFunsByName.containsKey(name)) {
						val f = genFunsByName[name]!!
						val typeArgs = inferTypeArgs(f, e.args, e.typeArgs)
						if (typeArgs != null) recordGenericFunInstantiation(name, typeArgs)
						}
					// Generic INLINE fun: its body expands at the call site - scan it with the
					// inferred substitution so generic instantiations inside (e.g. runCatching's
					// Result.Success<T>) are recorded before emission. ScanSubst has the
					// subst-aware twin of this hook for nested generic contexts.
					if (name != null && name !in inlineScanInProgress) {
						val vInline = inlineFunDecls[name]?.firstOrNull { it.typeParams.isNotEmpty() && it.body != null }
						if (vInline != null) {
							val vSub = mutableMapOf<String, String>()
							if (e.typeArgs.isNotEmpty() && e.typeArgs.size == vInline.typeParams.size) {
								vInline.typeParams.zip(e.typeArgs).forEach { (vTp, vTa) -> vSub[vTp] = vTa.name }
							} else {
								for ((vI, vP) in vInline.params.withIndex()) {
									if (vI >= e.args.size) break
									val vArgT = inferExprType(e.args[vI].expr)?.removeSuffix("?") ?: continue
									matchTypeParam(vP.type, vArgT, vInline.typeParams.toSet(), vSub)
								}
								bindLambdaReturnTypeParams(vInline, e.args, null, vSub)
							}
							if (vSub.size == vInline.typeParams.size && vSub.values.none { it in allGenericTypeParamNames }) {
								inlineScanInProgress += name
								try { scanBodyWithSubst(vInline.body, vSub) } finally { inlineScanInProgress -= name }
							}
						}
					}
					if (e.callee is DotExpr) {
						val dotName = e.callee.name
						if (genFunsByName.containsKey(dotName)) {
							val f = genFunsByName[dotName]!!
							val recvType = inferExprType(e.callee.obj)
							inferExprTypeKtc(e.callee.obj)
							val typeArgs = if (f.receiver != null && recvType != null) {
								inferTypeArgs(f, e.args, e.typeArgs, recvType)
								} else null
							if (typeArgs != null) {
								recordGenericFunInstantiation(dotName, typeArgs)
								val mangledRecvName = substituteTypeRef(f.receiver!!, f.typeParams.zip(typeArgs).toMap()).let {
									resolveTypeName(it).toInternalStr
									}
								extensionFuns.getOrPut(mangledRecvName) { mutableListOf() }.add(f)
								}
							}
						}
					for (a in e.args) scanExpr(a.expr)
					scanExpr(e.callee)
					}
				is BinExpr -> {
					val vInfixDecl = inlineExtFunDecls[e.op]?.firstOrNull()
					if (vInfixDecl != null && vInfixDecl.typeParams.isNotEmpty()) {
						val vRecvType = inferExprType(e.left)
						inferExprTypeKtc(e.left)
						if (vRecvType != null) {
							val vArgs = inferTypeArgs(vInfixDecl, listOf(Arg(expr = e.right)), emptyList(), vRecvType)
							if (vArgs != null) recordGenericFunInstantiation(e.op, vArgs)
							}
						}
					scanExpr(e.left); scanExpr(e.right)
					}
				is DotExpr -> scanExpr(e.obj)
				is SafeDotExpr -> scanExpr(e.obj)
				is IndexExpr -> { scanExpr(e.obj); scanExpr(e.index) }
				is PrefixExpr -> scanExpr(e.expr)
				is PostfixExpr -> scanExpr(e.expr)
				is NotNullExpr -> scanExpr(e.expr)
				is ElvisExpr -> { scanExpr(e.left); scanExpr(e.right) }
				is IfExpr -> { scanExpr(e.cond); scanBlock(e.then); scanBlock(e.els) }
				is CastExpr -> scanExpr(e.expr)
				is StrTemplateExpr -> e.parts.forEach { if (it is ExprPart) scanExpr(it.expr) }
				else -> {}
				}
			}
		fun scanStmt(s: Stmt) {
			when (s) {
				is VarDeclStmt -> {
					scanExpr(s.init)
					val vVarKtc: KtcType? = if (s.type != null) resolveTypeName(s.type) // typed: resolve directly
						else inferExprType(s.init)?.let { parseResolvedTypeName(it) }    // inferred: parse from string
					inferExprTypeKtc(s.init)
					if (vVarKtc != null) preScanVarTypes?.set(s.name, vVarKtc)
					}
				is AssignStmt -> { scanExpr(s.target); scanExpr(s.value) }
				is ExprStmt -> scanExpr(s.expr)
				is ForStmt -> { scanExpr(s.iter); scanBlock(s.body) }
				is WhileStmt -> { scanExpr(s.cond); scanBlock(s.body) }
				is DoWhileStmt -> { scanBlock(s.body); scanExpr(s.cond) }
				is ReturnStmt -> scanExpr(s.value)
				is DeferStmt -> scanBlock(s.body)
				is ThrowStmt -> scanExpr(s.value)
				is TryStmt -> {
					scanBlock(s.body)
					for (c in s.catches) scanBlock(c.body)
					scanBlock(s.finallyBlock)
					}
				else -> {}
				}
			}
		fun scanBlock(b: Block?) { b?.stmts?.forEach(::scanStmt) }
		}

	preScanVarTypes = mutableMapOf()
	for (d in file.decls) {
		when (d) {
			is FunDecl -> if (d.typeParams.isEmpty()) {
				preScanVarTypes!!.clear()
				for (p in d.params) preScanVarTypes!![p.name] = resolveTypeName(p.type)
				scanner.scanBlock(d.body)
				}
			is ClassDecl -> {
				for (m in d.members) if (m is FunDecl) {
					preScanVarTypes!!.clear()
					for (p in m.params) preScanVarTypes!![p.name] = resolveTypeName(p.type)
					scanner.scanBlock(m.body)
					}
				}
			else -> {}
			}
		}
	preScanVarTypes = null
	}

/*
Scan generic function bodies for generic class instantiations that only become
concrete after type parameter substitution. Iterates to fixpoint for transitive cases.
*/
internal fun CCodeGen.scanGenericFunBodiesForInstantiations() {
	var changed = true
	while (changed) {
		changed = false
		for ((funName, instantiations) in genericFunInstantiations.toMap()) {
			val funDecl = genericFunDecls.find { it.name == funName } ?: continue
			for (typeArgs in instantiations.toSet()) {
				val subst = funDecl.typeParams.zip(typeArgs).toMap()
				for (vParam in funDecl.params)
					if (scanTypeRefWithSubst(vParam.type, subst)) changed = true
				if (funDecl.returnType != null && scanTypeRefWithSubst(funDecl.returnType, subst)) changed = true
				if (scanBodyWithSubst(funDecl.body, subst)) changed = true
				}
			}
		}
	}

/*
Scan method bodies and return types of materialized generic classes for further
generic class instantiations. Iterates to fixpoint so transitive discoveries are handled.
*/
internal fun CCodeGen.scanGenericClassMethodBodiesForInstantiations() {
	var changed = true
	while (changed) {
		changed = false
		for ((baseName, instantiations) in genericInstantiations.toMap()) {
			val templateCi = classes[baseName] ?: continue
			if (!templateCi.isGeneric) continue
			val templateDecl = genericClassDecls[baseName] ?: continue
			for (typeArgs in instantiations.toSet()) {
				val subst = templateCi.typeParams.zip(typeArgs).toMap()
				for (m in templateDecl.members) {
					if (m is FunDecl) {
						if (m.returnType != null && scanTypeRefWithSubst(m.returnType, subst)) changed = true
						for (p in m.params) if (scanTypeRefWithSubst(p.type, subst)) changed = true
						if (scanBodyWithSubst(m.body, subst)) changed = true
						}
					}
				for (m in templateDecl.members) {
					if (m is PropDecl) {
						if (m.type != null && scanTypeRefWithSubst(m.type, subst)) changed = true
						if (scanExprWithSubst(m.init, subst)) changed = true
						}
					}
				for (p in templateDecl.ctorParams) if (scanTypeRefWithSubst(p.type, subst)) changed = true
				for (initBlock in templateDecl.initBlocks) if (scanBodyWithSubst(initBlock, subst)) changed = true
				}
			}
		if (changed) materializeGenericInstantiations()
		}
	}
