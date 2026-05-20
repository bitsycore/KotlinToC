package com.bitsycore.ktc.codegen

import com.bitsycore.ktc.ast.*

// AST-walk helpers that apply a type-param substitution map while scanning for
// generic instantiations. Also contains concrete-return inference and type-param matching.

internal fun CCodeGen.scanBodyWithSubst(block: Block?, subst: Map<String, String>): Boolean {
	if (block == null) return false
	var found = false
	for (s in block.stmts) if (scanStmtWithSubst(s, subst)) found = true
	return found
	}

internal fun CCodeGen.scanStmtWithSubst(s: Stmt, subst: Map<String, String>): Boolean {
	return when (s) {
		is VarDeclStmt -> {
			var f = scanExprWithSubst(s.init, subst)
			if (s.type != null && scanTypeRefWithSubst(s.type, subst)) f = true
			f
			}
		is AssignStmt -> {
			var f = scanExprWithSubst(s.target, subst)
			if (scanExprWithSubst(s.value, subst)) f = true
			f
			}
		is ExprStmt -> scanExprWithSubst(s.expr, subst)
		is ForStmt -> {
			var f = scanExprWithSubst(s.iter, subst)
			if (scanBodyWithSubst(s.body, subst)) f = true
			f
			}
		is WhileStmt -> {
			var f = scanExprWithSubst(s.cond, subst)
			if (scanBodyWithSubst(s.body, subst)) f = true
			f
			}
		is DoWhileStmt -> {
			var f = scanBodyWithSubst(s.body, subst)
			if (scanExprWithSubst(s.cond, subst)) f = true
			f
			}
		is ReturnStmt -> scanExprWithSubst(s.value, subst)
		is DeferStmt -> scanBodyWithSubst(s.body, subst)
		else -> false
		}
	}

internal fun CCodeGen.scanExprWithSubst(e: Expr?, subst: Map<String, String>): Boolean {
	if (e == null) return false
	return when (e) {
		is CallExpr -> {
			var found = false
			val name = (e.callee as? NameExpr)?.name
			// Constructor call to generic class WITH explicit type args: ArrayList<T>(...) → ArrayList_Int
			if (name != null && classes.containsKey(name) && classes[name]!!.isGeneric && e.typeArgs.isNotEmpty()) {
				val resolvedArgs = e.typeArgs.map { subst[it.name] ?: it.name }
				if (resolvedArgs.none { it in allGenericTypeParamNames }) {
					if (genericInstantiations[name]?.contains(resolvedArgs) != true) {
						recordGenericInstantiation(name, resolvedArgs)
						found = true
						}
					}
				}
			// Constructor call to generic class WITHOUT explicit type args: infer from ctor param types
			if (name != null && genericClassDecls.containsKey(name) && e.typeArgs.isEmpty()) {
				val vClassDecl = genericClassDecls[name]!!
				val vTypeParamSet = vClassDecl.typeParams.toSet()
				val vInferred = mutableMapOf<String, String>()
				for (vCtorParam in vClassDecl.ctorParams) {
					val vPTypeName = vCtorParam.type.name
					if (vPTypeName in vTypeParamSet) {
						val vResolved = subst[vPTypeName]
						if (vResolved != null && vResolved !in allGenericTypeParamNames) vInferred[vPTypeName] = vResolved
						}
					}
				if (vInferred.size == vClassDecl.typeParams.size) {
					val vArgs = vClassDecl.typeParams.map { vInferred[it]!! }
					if (genericInstantiations[name]?.contains(vArgs) != true) {
						recordGenericInstantiation(name, vArgs)
						found = true
						}
					}
				}
			for (ta in e.typeArgs) if (scanTypeRefWithSubst(ta, subst)) found = true
			// Generic function with explicit type args
			if (name != null) {
				val genFun = genericFunDecls.find { it.name == name }
				if (genFun != null && e.typeArgs.isNotEmpty()) {
					val resolvedArgs = e.typeArgs.map { subst[it.name] ?: it.name }
					if (resolvedArgs.none { it in allGenericTypeParamNames }) {
						val existing = genericFunInstantiations[name]
						if (existing == null || resolvedArgs !in existing) {
							genericFunInstantiations.getOrPut(name) { mutableSetOf() }.add(resolvedArgs)
							found = true
							}
						}
					}
				}
			for (a in e.args) if (scanExprWithSubst(a.expr, subst)) found = true
			if (scanExprWithSubst(e.callee, subst)) found = true
			found
			}
		is BinExpr -> {
			var f = scanExprWithSubst(e.left, subst)
			if (scanExprWithSubst(e.right, subst)) f = true
			f
			}
		is DotExpr -> scanExprWithSubst(e.obj, subst)
		is SafeDotExpr -> scanExprWithSubst(e.obj, subst)
		is IndexExpr -> {
			var f = scanExprWithSubst(e.obj, subst)
			if (scanExprWithSubst(e.index, subst)) f = true
			f
			}
		is PrefixExpr -> scanExprWithSubst(e.expr, subst)
		is PostfixExpr -> scanExprWithSubst(e.expr, subst)
		is NotNullExpr -> scanExprWithSubst(e.expr, subst)
		is ElvisExpr -> {
			var f = scanExprWithSubst(e.left, subst)
			if (scanExprWithSubst(e.right, subst)) f = true
			f
			}
		is IfExpr -> {
			var f = scanExprWithSubst(e.cond, subst)
			if (scanBodyWithSubst(e.then, subst)) f = true
			if (scanBodyWithSubst(e.els, subst)) f = true
			f
			}
		is CastExpr -> {
			var f = scanExprWithSubst(e.expr, subst)
			if (scanTypeRefWithSubst(e.type, subst)) f = true
			f
			}
		is StrTemplateExpr -> {
			var f = false
			for (p in e.parts) if (p is ExprPart && scanExprWithSubst(p.expr, subst)) f = true
			f
			}
		is LambdaExpr -> {
			var f = false
			for (stmt in e.body) if (scanStmtWithSubst(stmt, subst)) f = true
			f
			}
		else -> false
		}
	}

internal fun CCodeGen.scanTypeRefWithSubst(t: TypeRef, subst: Map<String, String>): Boolean {
	var found = false
	if (t.typeArgs.isNotEmpty()) {
		val resolvedArgs = t.typeArgs.map { subst[it.name] ?: it.name }
		if (classes.containsKey(t.name) && classes[t.name]!!.isGeneric) {
			if (resolvedArgs.none { it in allGenericTypeParamNames }) {
				if (genericInstantiations[t.name]?.contains(resolvedArgs) != true) {
					recordGenericInstantiation(t.name, resolvedArgs)
					found = true
					}
				}
			}
		}
	for (arg in t.typeArgs) if (scanTypeRefWithSubst(arg, subst)) found = true
	return found
	}

// ==================
// MARK: Concrete return inference
// ==================

/*
Pre-compute concrete return types for generic functions whose declared return type is
an interface but whose body returns a concrete class instance. Enables stack allocation.
*/
internal fun CCodeGen.computeGenericFunConcreteReturns() {
	for ((funName, instantiations) in genericFunInstantiations) {
		val funDecl = genericFunDecls.find { it.name == funName } ?: continue
		if (funDecl.returnType == null) continue
		for (typeArgs in instantiations) {
			val subst = funDecl.typeParams.zip(typeArgs).toMap()
			val prevSubst = typeSubst
			typeSubst = subst
			val resolvedReturn = resolveTypeName(funDecl.returnType).toInternalStr
			if (interfaces.containsKey(resolvedReturn)) {
				val concrete = inferConcreteReturnClass(funDecl.body, subst)
				if (concrete != null) {
					val mangledName = "${funName}_${typeArgs.joinToString("_")}"
					genericFunConcreteReturn[mangledName] = concrete
					}
				}
			typeSubst = prevSubst
			}
		}
	}

/* Scan a function body to find the concrete class returned via a named variable. */
internal fun CCodeGen.inferConcreteReturnClass(body: Block?, subst: Map<String, String>): String? {
	if (body == null) return null
	val varInits = mutableMapOf<String, Expr?>()
	for (s in body.stmts) {
		if (s is VarDeclStmt) varInits[s.name] = s.init
		}
	for (s in body.stmts) {
		if (s is ReturnStmt && s.value is NameExpr) {
			val varName = s.value.name
			val init = varInits[varName]
			if (init is CallExpr) {
				val calleeName = (init.callee as? NameExpr)?.name
				if (calleeName != null && classes.containsKey(calleeName) && classes[calleeName]!!.isGeneric) {
					val resolvedArgs = init.typeArgs.map { subst[it.name] ?: it.name }
					val mangledName = mangledGenericName(calleeName, resolvedArgs)
					if (classes.containsKey(mangledName)) return mangledName
					}
				if (calleeName != null && classes.containsKey(calleeName) && !classes[calleeName]!!.isGeneric) {
					return calleeName
					}
				}
			}
		}
	return null
	}

/*
Match a parameter type against a concrete argument type to infer type params.
E.g. param=MutableList<T>, argType="MutableList_Int", typeParams={T} → subst[T]=Int.
Callers must call materializeGenericInstantiations() before invoking this so
genericTypeBindings is populated for any generic types inferred by inferExprType.
*/
internal fun CCodeGen.matchTypeParam(
	paramType:  TypeRef,
	argType:    String,
	typeParams: Set<String>,
	subst:      MutableMap<String, String>
	) {
	if (paramType.name in typeParams) {
		subst[paramType.name] = argType
		return
		}
	if (paramType.typeArgs.isNotEmpty()) {
		val baseType = argType.trimEnd('*', '?')
		val bindings = genericTypeBindings[baseType]
		if (bindings != null) {
			val templateTypeParams: List<String>? = when {
				classes.containsKey(paramType.name) && classes[paramType.name]!!.isGeneric ->
					classes[paramType.name]!!.typeParams
				genericClassDecls.containsKey(paramType.name) ->
					genericClassDecls[paramType.name]!!.typeParams
				else -> null
				}
			if (templateTypeParams != null) {
				for ((i, typeArg) in paramType.typeArgs.withIndex()) {
					if (typeArg.name in typeParams && i < templateTypeParams.size) {
						val templateParam = templateTypeParams[i]
						bindings[templateParam]?.let { subst[typeArg.name] = it }
						}
					}
				return
				}
			}
		}
	// Generic interface param: List<T>, arg=ArrayList_Int → T=Int
	if (paramType.typeArgs.isNotEmpty() && genericIfaceDecls.containsKey(paramType.name)) {
		val baseType = argType.trimEnd('*', '?')
		val classIfaces = classInterfaces[baseType] ?: return
		for (ifaceName in classIfaces) {
			if (ifaceName.startsWith(paramType.name + "_")) {
				val components = mangledComponents[ifaceName]
				if (components != null) {
					val (_, typeArgs) = components
					for ((i, typeArg) in paramType.typeArgs.withIndex()) {
						if (typeArg.name in typeParams && i < typeArgs.size) subst[typeArg.name] = typeArgs[i]
						}
					return
					}
				}
			}
		}
	}
