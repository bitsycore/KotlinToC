package com.bitsycore.ktc.codegen

import com.bitsycore.ktc.ast.*
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

	fun inferTypeArgsFromCall(f: FunDecl, callArgs: List<Arg>, explicitTypeArgs: List<TypeRef>): List<String>? {
		if (explicitTypeArgs.isNotEmpty()) return explicitTypeArgs.map { it.name }
		val subst = mutableMapOf<String, String>() // accumulated type param substitutions
		for ((i, param) in f.params.withIndex()) {
			if (i >= callArgs.size) break
			val argExpr = callArgs[i].expr
			val argType = inferExprType(argExpr) ?: continue
			inferExprTypeKtc(argExpr)
			/* Materialize before matching so genericTypeBindings is populated. */
			materializeGenericInstantiations()
			matchTypeParam(param.type, argType, f.typeParams.toSet(), subst)
			}
		if (subst.size == f.typeParams.size) return f.typeParams.map { subst[it]!! }
		return null
		}

	fun inferTypeArgsFromReceiver(f: FunDecl, recvType: String, callArgs: List<Arg>, explicitTypeArgs: List<TypeRef>): List<String>? {
		if (explicitTypeArgs.isNotEmpty()) return explicitTypeArgs.map { it.name }
		val subst = mutableMapOf<String, String>() // accumulated type param substitutions
		materializeGenericInstantiations()
		if (f.receiver != null) matchTypeParam(f.receiver, recvType, f.typeParams.toSet(), subst)
		for ((i, param) in f.params.withIndex()) {
			if (i >= callArgs.size) break
			val argExpr = callArgs[i].expr
			val argType = inferExprType(argExpr) ?: continue
			inferExprTypeKtc(argExpr)
			materializeGenericInstantiations()
			matchTypeParam(param.type, argType, f.typeParams.toSet(), subst)
			}
		if (subst.size == f.typeParams.size) return f.typeParams.map { subst[it]!! }
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
						val typeArgs = inferTypeArgsFromCall(f, e.args, e.typeArgs)
						if (typeArgs != null) genericFunInstantiations.getOrPut(name) { mutableSetOf() }.add(typeArgs)
						}
					if (e.callee is DotExpr) {
						val dotName = e.callee.name
						if (genFunsByName.containsKey(dotName)) {
							val f = genFunsByName[dotName]!!
							val recvType = inferExprType(e.callee.obj)
							inferExprTypeKtc(e.callee.obj)
							val typeArgs = if (f.receiver != null && recvType != null) {
								inferTypeArgsFromReceiver(f, recvType, e.args, e.typeArgs)
								} else null
							if (typeArgs != null) {
								genericFunInstantiations.getOrPut(dotName) { mutableSetOf() }.add(typeArgs)
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
					val vInfixDecl = inlineExtFunDecls[e.op]
					if (vInfixDecl != null && vInfixDecl.typeParams.isNotEmpty()) {
						val vRecvType = inferExprType(e.left)
						inferExprTypeKtc(e.left)
						if (vRecvType != null) {
							val vArgs = inferTypeArgsFromReceiver(vInfixDecl, vRecvType, listOf(Arg(expr = e.right)), emptyList())
							if (vArgs != null) genericFunInstantiations.getOrPut(e.op) { mutableSetOf() }.add(vArgs)
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
