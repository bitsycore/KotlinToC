package com.bitsycore.ktc.codegen

import com.bitsycore.ktc.ast.*
import com.bitsycore.ktc.types.KtcType

// Pre-scan passes for generic class instantiation discovery and materialization.

private val kArrayElemPrimitives: Set<String> = KtcType.PrimKind.namesSet + "String"

/** Pre-scan AST for Array<T> type references to populate classArrayTypes. */
internal fun CCodeGen.scanForClassArrayTypes() {
	fun checkType(t: TypeRef?) {
		if (t == null) return
		if (t.name == "Array" && t.typeArgs.isNotEmpty()) {
			val elem = t.typeArgs[0].name.replace('.', '$')
			if (elem !in kArrayElemPrimitives) classArrayTypes.add(elem)
			}
		}
	fun scanExpr(e: Expr?) {
		if (e == null) return
		if (e is CallExpr) {
			val name = (e.callee as? NameExpr)?.name
			if (name == "arrayOf" && e.args.isNotEmpty()) {
				val firstArg = e.args[0].expr
				if (firstArg is CallExpr) {
					val argName = (firstArg.callee as? NameExpr)?.name
					if (argName != null && classes.containsKey(argName)) classArrayTypes.add(argName)
					}
				}
			if (name == "arrayOfNulls" && e.typeArgs.isNotEmpty()) {
				val elem = e.typeArgs[0].name.replace('.', '$')
				if (elem !in kArrayElemPrimitives) classArrayTypes.add(elem)
				}
			for (arg in e.args) scanExpr(arg.expr)
			}
		if (e is BinExpr) { scanExpr(e.left); scanExpr(e.right) }
		}
	fun scanStmt(s: Stmt) {
		when (s) {
			is VarDeclStmt -> { checkType(s.type); scanExpr(s.init) }
			is AssignStmt -> { scanExpr(s.target); scanExpr(s.value) }
			is ExprStmt -> scanExpr(s.expr)
			is ForStmt -> { scanExpr(s.iter); s.body.stmts.forEach(::scanStmt) }
			is WhileStmt -> s.body.stmts.forEach(::scanStmt)
			is DoWhileStmt -> s.body.stmts.forEach(::scanStmt)
			is ReturnStmt -> scanExpr(s.value)
			is DeferStmt -> s.body.stmts.forEach(::scanStmt)
			else -> {}
			}
		}
	fun scanBody(body: Block?) { body?.stmts?.forEach(::scanStmt) }
	for (d in file.decls) {
		when (d) {
			is FunDecl -> {
				for (p in d.params) checkType(p.type)
				d.returnType?.let { checkType(it) }
				scanBody(d.body)
				}
			is ClassDecl -> {
				for (p in d.ctorParams) checkType(p.type)
				for (m in d.members) if (m is FunDecl) {
					for (p in m.params) checkType(p.type)
					m.returnType?.let { checkType(it) }
					scanBody(m.body)
					}
				for (m in d.members) if (m is PropDecl) {
					checkType(m.type)
					scanExpr(m.init)
					}
				}
			is PropDecl -> {
				checkType(d.type)
				scanExpr(d.init)
				}
			else -> {}
			}
		}
	}

/** Pre-scan AST for concrete instantiations of generic classes (e.g. MyList<Int>). */
internal fun CCodeGen.scanForGenericInstantiations() {
	val allTypeParamNames = mutableSetOf<String>()
	for (d in file.decls) {
		if (d is ClassDecl && d.typeParams.isNotEmpty()) allTypeParamNames += d.typeParams
		}
	for (d in file.decls) {
		when (d) {
			is FunDecl -> {
				if (d.typeParams.isNotEmpty()) continue
				if (d.receiver != null && d.receiver.typeArgs.any { it.name == "*" }) continue
				for (p in d.params) scanTypeRefForGenerics(p.type, allTypeParamNames)
				d.returnType?.let { scanTypeRefForGenerics(it, allTypeParamNames) }
				scanBlockForGenerics(d.body, allTypeParamNames)
				}
			is ClassDecl -> {
				val skip = allTypeParamNames + d.typeParams
				for (p in d.ctorParams) scanTypeRefForGenerics(p.type, skip)
				for (m in d.members) if (m is FunDecl) {
					for (p in m.params) scanTypeRefForGenerics(p.type, skip)
					m.returnType?.let { scanTypeRefForGenerics(it, skip) }
					scanBlockForGenerics(m.body, skip)
					}
				for (m in d.members) if (m is PropDecl) {
					scanTypeRefForGenerics(m.type, skip)
					scanExprForGenerics(m.init, skip)
					}
				}
			is PropDecl -> {
				scanTypeRefForGenerics(d.type, allTypeParamNames)
				scanExprForGenerics(d.init, allTypeParamNames)
				}
			else -> {}
			}
		}
	}

internal fun CCodeGen.scanTypeRefForGenerics(t: TypeRef?, skip: Set<String> = emptySet()) {
	if (t == null) return
	if (t.typeArgs.isNotEmpty() && classes.containsKey(t.name) && classes[t.name]!!.isGeneric) {
		val concreteArgs = t.typeArgs.map { if (it.nullable) "${it.name}?" else it.name }
		if (concreteArgs.none { it.trimEnd('?') in skip || it == "*" }) {
			recordGenericInstantiation(t.name, concreteArgs)
			}
		}
	// Generic interface type ref (e.g. Result<Int>) → instantiate all nested classes
	val ifaceDecl = genericIfaceDecls[t.name]
	if (ifaceDecl != null && t.typeArgs.isNotEmpty()) {
		val concreteArgs = t.typeArgs.map { if (it.nullable) "${it.name}?" else it.name }
		if (concreteArgs.none { it.trimEnd('?') in skip || it == "*" }) {
			for (nested in ifaceDecl.nestedClasses) {
				val flatName = "${t.name}$${nested.name}"
				if (genericClassDecls.containsKey(flatName))
					recordGenericInstantiation(flatName, concreteArgs)
				}
			}
		}
	for (arg in t.typeArgs) scanTypeRefForGenerics(arg, skip)
	}

internal fun CCodeGen.scanExprForGenerics(e: Expr?, skip: Set<String> = emptySet()) {
	if (e == null) return
	when (e) {
		is CallExpr -> {
			val name = (e.callee as? NameExpr)?.name
			// Resolve dotted callee (e.g. Result.Ok → Result$Ok)
			val dotCallee = e.callee as? DotExpr
			val dotName = if (dotCallee != null) {
				val objName = (dotCallee.obj as? NameExpr)?.name
				if (objName != null) "$objName$${dotCallee.name}" else null
				} else null
			for (ta in e.typeArgs) {
				if (ta.typeArgs.isNotEmpty() && classes.containsKey(ta.name) && classes[ta.name]!!.isGeneric) {
					val concreteArgs = ta.typeArgs.map { if (it.nullable) "${it.name}?" else it.name }
					if (concreteArgs.none { it.trimEnd('?') in skip || it == "*" }) {
						recordGenericInstantiation(ta.name, concreteArgs)
						}
					}
				}
			val effectiveName = name ?: dotName
			if (effectiveName != null && classes.containsKey(effectiveName) && classes[effectiveName]!!.isGeneric && e.typeArgs.isNotEmpty()) {
				val concreteArgs = e.typeArgs.map { if (it.nullable) "${it.name}?" else it.name }
				if (concreteArgs.none { it.trimEnd('?') in skip || it == "*" }) {
					recordGenericInstantiation(effectiveName, concreteArgs)
					}
				}
			for (a in e.args) scanExprForGenerics(a.expr, skip)
			scanExprForGenerics(e.callee, skip)
			}
		is BinExpr -> { scanExprForGenerics(e.left, skip); scanExprForGenerics(e.right, skip) }
		is DotExpr -> scanExprForGenerics(e.obj, skip)
		is SafeDotExpr -> scanExprForGenerics(e.obj, skip)
		is IndexExpr -> { scanExprForGenerics(e.obj, skip); scanExprForGenerics(e.index, skip) }
		is PrefixExpr -> scanExprForGenerics(e.expr, skip)
		is PostfixExpr -> scanExprForGenerics(e.expr, skip)
		is NotNullExpr -> scanExprForGenerics(e.expr, skip)
		is ElvisExpr -> { scanExprForGenerics(e.left, skip); scanExprForGenerics(e.right, skip) }
		is IfExpr -> {
			scanExprForGenerics(e.cond, skip)
			scanBlockForGenerics(e.then, skip)
			scanBlockForGenerics(e.els, skip)
			}
		is CastExpr -> { scanExprForGenerics(e.expr, skip); scanTypeRefForGenerics(e.type, skip) }
		is StrTemplateExpr -> e.parts.forEach { if (it is ExprPart) scanExprForGenerics(it.expr, skip) }
		is LambdaExpr -> e.body.forEach { scanStmtForGenerics(it, skip) }
		else -> {}
		}
	}

internal fun CCodeGen.scanStmtForGenerics(s: Stmt, skip: Set<String> = emptySet()) {
	when (s) {
		is VarDeclStmt -> { scanTypeRefForGenerics(s.type, skip); scanExprForGenerics(s.init, skip) }
		is AssignStmt -> { scanExprForGenerics(s.target, skip); scanExprForGenerics(s.value, skip) }
		is ExprStmt -> scanExprForGenerics(s.expr, skip)
		is ForStmt -> { scanExprForGenerics(s.iter, skip); scanBlockForGenerics(s.body, skip) }
		is WhileStmt -> { scanExprForGenerics(s.cond, skip); scanBlockForGenerics(s.body, skip) }
		is DoWhileStmt -> { scanBlockForGenerics(s.body, skip); scanExprForGenerics(s.cond, skip) }
		is ReturnStmt -> scanExprForGenerics(s.value, skip)
		is DeferStmt -> scanBlockForGenerics(s.body, skip)
		else -> {}
		}
	}

internal fun CCodeGen.scanBlockForGenerics(block: Block?, skip: Set<String> = emptySet()) {
	block?.stmts?.forEach { scanStmtForGenerics(it, skip) }
	}

/*
Create concrete ClassInfo entries for each generic instantiation discovered.
E.g. MyList<Int> → classes["MyList_Int"] with T→Int substitution.
*/
internal fun CCodeGen.materializeGenericInstantiations() {
	for ((baseName, instantiations) in genericInstantiations) {
		val templateCi = classes[baseName] ?: continue
		val templateDecl = genericClassDecls[baseName] ?: continue
		for (typeArgs in instantiations) {
			val mangledName = mangledGenericName(baseName, typeArgs)
			if (classes.containsKey(mangledName)) continue
			val vSubst = templateCi.typeParams.zip(typeArgs).toMap() // type param substitution map
			val vNewCtorProps = templateCi.ctorProps.map { vProp ->  // substitute ctor props
				vProp.copy(typeRef = substituteTypeRef(vProp.typeRef, vSubst))
				}
			val vNewPlainParams = templateCi.ctorPlainParams.map { vProp ->  // substitute plain params
				vProp.copy(typeRef = substituteTypeRef(vProp.typeRef, vSubst))
				}
			val vNewBodyProps = templateCi.bodyProps.map { vProp ->  // substitute body props
				vProp.copy(typeRef = substituteTypeRef(vProp.typeRef, vSubst))
				}
			val vAllNewProps = vNewCtorProps + vNewBodyProps // combined substituted props
			val ci = ClassInfo(mangledName, templateCi.isData, vAllNewProps, vNewPlainParams,
				initBlocks = templateCi.initBlocks, typeParams = templateCi.typeParams)
			for (m in templateCi.methods) ci.methods += m
			classes[mangledName] = ci
			getTypeId(mangledName)
			classes[mangledName]?.pkg = classes[baseName]?.pkg ?: prefix
			genericTypeBindings[mangledName] = vSubst
			if (templateDecl.superInterfaces.isNotEmpty()) {
				val resolvedIfaces = templateDecl.superInterfaces.map { ifaceRef ->
					val resolved = substituteTypeRef(ifaceRef, vSubst)
					resolveIfaceName(resolved)
					}
				classInterfaces[mangledName] = resolvedIfaces
				for (ifaceRef in templateDecl.superInterfaces) {
					val resolved = substituteTypeRef(ifaceRef, vSubst)
					materializeGenericInterface(resolved)
					}
				}
			}
		}
	}

/** Resolve an interface TypeRef to its concrete name (e.g. MutableList<Int> → "MutableList_Int"). */
internal fun CCodeGen.resolveIfaceName(t: TypeRef): String {
	if (t.typeArgs.isEmpty()) return t.name
	return mangledGenericName(t.name, t.typeArgs.map { typeRef ->
		val sub = substituteTypeParams(typeRef)
		if (sub.nullable) "${resolveTypeNameStr(sub)}?" else resolveTypeNameStr(sub)
		})
	}

/* Monomorphize a generic interface template. E.g. List<Int> → IfaceInfo("List_Int", ...). */
internal fun CCodeGen.materializeGenericInterface(t: TypeRef) {
	if (t.typeArgs.isEmpty()) return
	val baseName = t.name
	val template = interfaces[baseName] ?: return
	if (template.typeParams.isEmpty()) return
	val typeArgs = t.typeArgs.map { resolveTypeName(it).toInternalStr }
	val mangledName = mangledGenericName(baseName, typeArgs)
	if (interfaces.containsKey(mangledName)) return
	val subst = template.typeParams.zip(typeArgs).toMap()
	val methods = template.methods.map { m ->
		m.copy(
			params = m.params.map { p -> p.copy(type = substituteTypeRef(p.type, subst)) },
			returnType = m.returnType?.let { substituteTypeRef(it, subst) }
			)
		}
	val properties = template.propDecls.map { p ->
		p.copy(type = p.type?.let { substituteTypeRef(it, subst) })
		}
	val resolvedSupers = template.superInterfaces.map { substituteTypeRef(it, subst) }
	interfaces[mangledName] = IfaceInfo(mangledName, methods, properties, emptyList(), resolvedSupers)
	getTypeId(mangledName)
	interfaces[mangledName]?.pkg = interfaces[baseName]?.pkg ?: prefix
	if (baseName in simpleUnionInterfaces) simpleUnionInterfaces += mangledName
	for (superRef in resolvedSupers) materializeGenericInterface(superRef)
	}

/** Substitute type parameters in a TypeRef: T → Int when subst = {T: Int}. */
internal fun CCodeGen.substituteTypeRef(t: TypeRef, subst: Map<String, String>): TypeRef {
	val rawNewName = subst[t.name] ?: t.name
	val hasNullableSuffix = rawNewName.endsWith("?")
	val newName = if (hasNullableSuffix) rawNewName.dropLast(1) else rawNewName
	val newTypeArgs = t.typeArgs.map { substituteTypeRef(it, subst) }
	return t.copy(name = newName, typeArgs = newTypeArgs, nullable = t.nullable || hasNullableSuffix)
	}
