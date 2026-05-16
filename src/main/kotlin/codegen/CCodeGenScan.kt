package com.bitsycore.ktc.codegen

import com.bitsycore.ktc.ast.*
import com.bitsycore.ktc.types.KtcType

/**
 * ── Pre-scanning & Generic Monomorphization ─────────────────────────────
 *
 * This file runs multiple AST pre-passes before any C code is emitted.
 * It discovers all concrete instantiations of generic classes and functions
 * so that the emitter phase has complete type information.
 *
 * ## Pipeline order (called from [CCodeGen.collectAndScan]):
 *
 * 1. [scanForClassArrayTypes]      — discover class types used in Array<T>
 * 2. [scanForGenericInstantiations] — find MyList<Int>, HashMap<K,V> etc.
 * 3. [materializeGenericInstantiations] — create concrete ClassInfo entries
 * 4. [scanForGenericFunCalls]      — discover generic function call sites
 * 5. [scanGenericFunBodiesForInstantiations] — transitive discovery
 * 6. [scanGenericClassMethodBodiesForInstantiations] — from materialized types
 * 7. [computeGenericFunConcreteReturns] — interface-return → concrete type
 *
 * ## State accessed:
 *   classes, interfaces, genericClassDecls, genericIfaceDecls, genericFunDecls,
 *   genericInstantiations, genericFunInstantiations, genericTypeBindings,
 *   classInterfaces, classArrayTypes, allGenericTypeParamNames, pair/triple/tuple maps
 *
 * ## State mutated:
 *   genericInstantiations, genericFunInstantiations, genericFunConcreteReturn,
 *   classes (materialized generic instantiations), classInterfaces, classArrayTypes
 *
 * ## Dependencies:
 *   Calls into CCodeGenInfer.kt (inferExprType, inferBlockType)
 *   Calls into CCodeGenCTypes.kt (resolveTypeName, resolveIfaceName)
 *   Used by [CCodeGen.collectAndScan] before [CCodeGen.generate]
 */

// ═══════════════════════════ Scanning passes ═══════════════════════

/** Pre-scan AST for Array<T> type references to populate classArrayTypes. */
internal fun CCodeGen.scanForClassArrayTypes() {
    val primitives = setOf(
        "Byte", "Short", "Int", "Long", "Float", "Double", "Boolean", "Char",
        "UByte", "UShort", "UInt", "ULong", "String"
    )
    fun checkType(t: TypeRef?) {
        if (t == null) return
    if (t.name == "Array" && t.typeArgs.isNotEmpty()) {
            val elem = t.typeArgs[0].name
            if (elem !in primitives) classArrayTypes.add(elem)
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
                    if (argName != null && classes.containsKey(argName)) {
                        classArrayTypes.add(argName)
                    }
                }
            }
            if (name == "heapArrayOf" && e.args.isNotEmpty()) {
                val firstArg = e.args[0].expr
                if (firstArg is CallExpr) {
                    val argName = (firstArg.callee as? NameExpr)?.name
                    if (argName != null && classes.containsKey(argName)) {
                        classArrayTypes.add(argName)
                    }
                }
            }
            if (name == "arrayOfNulls" && e.typeArgs.isNotEmpty()) {
                val elem = e.typeArgs[0].name
                if (elem !in primitives) classArrayTypes.add(elem)
            }
            // mutableListOf / arrayListOf: no longer built-in
            // HashMap and mapOf use generic runtime — no per-type scanning needed
            // Recurse into args
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
    // Collect all known type param names (from generic classes and generic functions)
    // to avoid treating them as concrete type args
    val allTypeParamNames = mutableSetOf<String>()
    for (d in file.decls) {
        if (d is ClassDecl && d.typeParams.isNotEmpty()) allTypeParamNames += d.typeParams
    }

    for (d in file.decls) {
        when (d) {
            is FunDecl -> {
                // Skip generic functions — they are templates, scanned at call sites
                if (d.typeParams.isNotEmpty()) continue
                // Skip star-projection extension functions — expanded later
                if (d.receiver != null && d.receiver.typeArgs.any { it.name == "*" }) continue
                for (p in d.params) scanTypeRefForGenerics(p.type, allTypeParamNames)
                d.returnType?.let { scanTypeRefForGenerics(it, allTypeParamNames) }
                scanBlockForGenerics(d.body, allTypeParamNames)
            }
            is ClassDecl -> {
                // For generic classes, add their own type params to the skip set
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
        // Only record if all type args are concrete (not type params or star projections)
        // Include "?" suffix for nullable type args so scan matches codegen's resolved names.
        val concreteArgs = t.typeArgs.map { if (it.nullable) "${it.name}?" else it.name }
        if (concreteArgs.none { it.trimEnd('?') in skip || it == "*" }) {
            recordGenericInstantiation(t.name, concreteArgs)
        }
    }
    for (arg in t.typeArgs) scanTypeRefForGenerics(arg, skip)
}

internal fun CCodeGen.scanExprForGenerics(e: Expr?, skip: Set<String> = emptySet()) {
    if (e == null) return
    when (e) {
        is CallExpr -> {
            val name = (e.callee as? NameExpr)?.name
            // Constructor call: MyList<Int>(...) or HeapAlloc<MyList<Int>>(...)
            for (ta in e.typeArgs) {
                if (ta.typeArgs.isNotEmpty() && classes.containsKey(ta.name) && classes[ta.name]!!.isGeneric) {
                    val concreteArgs = ta.typeArgs.map { if (it.nullable) "${it.name}?" else it.name }
                    if (concreteArgs.none { it.trimEnd('?') in skip || it == "*" }) {
                        recordGenericInstantiation(ta.name, concreteArgs)
                    }
                }
            }
            if (name != null && classes.containsKey(name) && classes[name]!!.isGeneric && e.typeArgs.isNotEmpty()) {
                val concreteArgs = e.typeArgs.map { if (it.nullable) "${it.name}?" else it.name }
                if (concreteArgs.none { it.trimEnd('?') in skip || it == "*" }) {
                    recordGenericInstantiation(name, concreteArgs)
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

internal fun CCodeGen.scanBlockForGenerics(block: Block?, skip: Set<String> = emptySet()) { block?.stmts?.forEach { scanStmtForGenerics(it, skip) } }

/**
 * Create concrete ClassInfo entries for each generic instantiation discovered.
 * E.g., MyList<Int> → classes["MyList_Int"] with all T→Int substitution tracked.
 */
internal fun CCodeGen.materializeGenericInstantiations() {
    for ((baseName, instantiations) in genericInstantiations) {
        val templateCi = classes[baseName] ?: continue
        val templateDecl = genericClassDecls[baseName] ?: continue
        for (typeArgs in instantiations) {
            val mangledName = mangledGenericName(baseName, typeArgs)
            if (classes.containsKey(mangledName)) continue  // already materialized
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
			val vAllNewProps = vNewCtorProps + vNewBodyProps  // combined substituted props
			val ci = ClassInfo(mangledName, templateCi.isData, vAllNewProps, vNewPlainParams,
				initBlocks = templateCi.initBlocks, typeParams = templateCi.typeParams)
            // Copy methods from template
            for (m in templateCi.methods) ci.methods += m
            classes[mangledName] = ci
            getTypeId(mangledName)
            // Set pkg for the mangled name from the base class's pkg
            classes[mangledName]?.pkg = classes[baseName]?.pkg ?: prefix
            // Store type bindings for resolving method return types later
			genericTypeBindings[mangledName] = vSubst

            // Resolve super interfaces with type substitution
            if (templateDecl.superInterfaces.isNotEmpty()) {
                val resolvedIfaces = templateDecl.superInterfaces.map { ifaceRef ->
                    val resolved = substituteTypeRef(ifaceRef, vSubst)
                    resolveIfaceName(resolved)
                }
                classInterfaces[mangledName] = resolvedIfaces
                // Monomorphize each generic interface
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

/**
 * Monomorphize a generic interface template. E.g., List<Int> → creates IfaceInfo("List_Int", ...).
 * Recursively processes super interfaces.
 */
internal fun CCodeGen.materializeGenericInterface(t: TypeRef) {
    if (t.typeArgs.isEmpty()) return  // non-generic, already registered
    val baseName = t.name
    val template = interfaces[baseName] ?: return
    if (template.typeParams.isEmpty()) return  // non-generic template
    val typeArgs = t.typeArgs.map { resolveTypeName(it).toInternalStr }
    val mangledName = mangledGenericName(baseName, typeArgs)
    if (interfaces.containsKey(mangledName)) return  // already materialized
    val subst = template.typeParams.zip(typeArgs).toMap()
    // Substitute types in methods
    val methods = template.methods.map { m ->
        m.copy(
            params = m.params.map { p -> p.copy(type = substituteTypeRef(p.type, subst)) },
            returnType = m.returnType?.let { substituteTypeRef(it, subst) }
        )
    }
    // Substitute types in properties
    val properties = template.propDecls.map { p ->
        p.copy(type = p.type?.let { substituteTypeRef(it, subst) })
    }
    // Resolve super interfaces
    val resolvedSupers = template.superInterfaces.map { substituteTypeRef(it, subst) }
    interfaces[mangledName] = IfaceInfo(mangledName, methods, properties, emptyList(), resolvedSupers)
    getTypeId(mangledName)
    // Set pkg for the mangled name from the base interface's pkg
    interfaces[mangledName]?.pkg = interfaces[baseName]?.pkg ?: prefix
    // Recursively monomorphize parent interfaces
    for (superRef in resolvedSupers) {
        materializeGenericInterface(superRef)
    }
}

/** Substitute type parameters in a TypeRef: T → Int when subst = {T: Int}. */
internal fun CCodeGen.substituteTypeRef(t: TypeRef, subst: Map<String, String>): TypeRef {
    val rawNewName = subst[t.name] ?: t.name
    val hasNullableSuffix = rawNewName.endsWith("?")
    val newName = if (hasNullableSuffix) rawNewName.dropLast(1) else rawNewName
    val newTypeArgs = t.typeArgs.map { substituteTypeRef(it, subst) }
    return t.copy(name = newName, typeArgs = newTypeArgs,
        nullable = t.nullable || hasNullableSuffix)
}

// ── generic function call-site scanning ──────────────────────────

/**
 * Scan call sites for generic functions to determine concrete type bindings.
 * For `fun <T> sizeOfList(list: MutableList<T>)` called with a MutableList_Int arg,
 * we infer T=Int and record the instantiation.
 */
internal fun CCodeGen.scanForGenericFunCalls() {
    val genFunsByName = genericFunDecls.associateBy { it.name }
    if (genFunsByName.isEmpty()) return

    fun inferTypeArgsFromCall(f: FunDecl, callArgs: List<Arg>, explicitTypeArgs: List<TypeRef>): List<String>? {
        if (explicitTypeArgs.isNotEmpty()) {
            return explicitTypeArgs.map { it.name }
        }
        val subst = mutableMapOf<String, String>() // accumulated type param substitutions
        for ((i, param) in f.params.withIndex()) {
            if (i >= callArgs.size) break
            val argExpr = callArgs[i].expr
            val argType = inferExprType(argExpr) ?: continue
            inferExprTypeKtc(argExpr)
            /* Materialize before matching so genericTypeBindings is populated for
            any generic instantiations inferred by inferExprType above (e.g. Pair_A_B). */
            materializeGenericInstantiations()
            matchTypeParam(param.type, argType, f.typeParams.toSet(), subst)
        }
        if (subst.size == f.typeParams.size) {
            return f.typeParams.map { subst[it]!! }
        }
        return null
    }

    fun inferTypeArgsFromReceiver(f: FunDecl, recvType: String, callArgs: List<Arg>, explicitTypeArgs: List<TypeRef>): List<String>? {
        if (explicitTypeArgs.isNotEmpty()) {
            return explicitTypeArgs.map { it.name }
        }
        val subst = mutableMapOf<String, String>() // accumulated type param substitutions
        /* Materialize before matching so genericTypeBindings is available for generic types. */
        materializeGenericInstantiations()
        // Match receiver type
        if (f.receiver != null) {
            matchTypeParam(f.receiver, recvType, f.typeParams.toSet(), subst)
        }
        // Match regular params
        for ((i, param) in f.params.withIndex()) {
            if (i >= callArgs.size) break
            val argExpr = callArgs[i].expr
            val argType = inferExprType(argExpr) ?: continue
            inferExprTypeKtc(argExpr)
            materializeGenericInstantiations()
            matchTypeParam(param.type, argType, f.typeParams.toSet(), subst)
        }
        if (subst.size == f.typeParams.size) {
            return f.typeParams.map { subst[it]!! }
        }
        return null
    }

    // Use member functions to avoid Kotlin forward-reference issues with local functions
    val scanner = object {
        fun scanExpr(e: Expr?) {
            if (e == null) return
            when (e) {
                is CallExpr -> {
                    val name = (e.callee as? NameExpr)?.name
                    if (name != null && genFunsByName.containsKey(name)) {
                        val f = genFunsByName[name]!!
                        val typeArgs = inferTypeArgsFromCall(f, e.args, e.typeArgs)
                        if (typeArgs != null) {
                            genericFunInstantiations.getOrPut(name) { mutableSetOf() }.add(typeArgs)
                        }
                    }
                    // Generic extension call: map.tryDispose() where tryDispose is generic
                    if (e.callee is DotExpr) {
                        val dotName = e.callee.name
                        if (genFunsByName.containsKey(dotName)) {
                            val f = genFunsByName[dotName]!!
                            val recvType = inferExprType(e.callee.obj)
                            inferExprTypeKtc(e.callee.obj)
                            val typeArgs = if (f.receiver != null && recvType != null) {
                                // Infer type args from receiver type
                                inferTypeArgsFromReceiver(f, recvType, e.args, e.typeArgs)
                            } else null
                            if (typeArgs != null) {
                                genericFunInstantiations.getOrPut(dotName) { mutableSetOf() }.add(typeArgs)
                                // Also register in extensionFuns for method dispatch
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
                    // Infix call: `a myOp b` → treat like a.myOp(b) for generic instantiation
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
                    /* Track variable type as KtcType for subsequent inference in this function. */
                    val vVarKtc: KtcType? = if (s.type != null) resolveTypeName(s.type)  // typed: resolve directly
                        else inferExprType(s.init)?.let { parseResolvedTypeName(it) }     // inferred: parse inferred string
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
                preScanVarTypes!!.clear()  // fresh var scope per function
                // Register function params so they're available for inference
                for (p in d.params) {
                    preScanVarTypes!![p.name] = resolveTypeName(p.type)  // store as KtcType
                }
                scanner.scanBlock(d.body)
            }
            is ClassDecl -> {
                for (m in d.members) if (m is FunDecl) {
                    preScanVarTypes!!.clear()
                    for (p in m.params) {
                        preScanVarTypes!![p.name] = resolveTypeName(p.type)  // store as KtcType
                    }
                    scanner.scanBlock(m.body)
                }
            }
            else -> {}
        }
    }
    preScanVarTypes = null
}

/**
 * Scan generic function bodies for generic class instantiations that only become
 * concrete after type parameter substitution.
 * E.g., `fun <T> listOf(vararg items: T): List<T>` body has `ArrayList<T>(items.size)`.
 * When listOf is called with T=Int, we need to discover ArrayList<Int>.
 * Iterates until no new instantiations are found (handles transitive cases).
 */
internal fun CCodeGen.scanGenericFunBodiesForInstantiations() {
    var changed = true
    while (changed) {
        changed = false
        for ((funName, instantiations) in genericFunInstantiations.toMap()) {
            val funDecl = genericFunDecls.find { it.name == funName } ?: continue
            for (typeArgs in instantiations.toSet()) {
                val subst = funDecl.typeParams.zip(typeArgs).toMap()
                /* Scan param types for generic class references that only appear in signatures,
                not in the body — e.g. fun newMapOf(vararg pairs: Pair<K,V>) needs Pair<Int,String>
                even when the body never calls Pair(...) directly. */
                for (vParam in funDecl.params)
                    if (scanTypeRefWithSubst(vParam.type, subst)) changed = true
                /* Scan return type for the same reason (e.g. fun makePair(): Pair<A,B>). */
                if (funDecl.returnType != null && scanTypeRefWithSubst(funDecl.returnType, subst)) changed = true
                if (scanBodyWithSubst(funDecl.body, subst)) changed = true
            }
        }
    }
}

/**
 * Scan method bodies and return types of materialized generic classes for further
 * generic class instantiations. E.g., HashMap<Int,String>.iterator() returns
 * MapIterator<K,V> which with {K→Int,V→String} becomes MapIterator<Int,String>.
 * Iterates to fixpoint so transitive discoveries are handled.
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
                // Scan method bodies and return types
                for (m in templateDecl.members) {
                    if (m is FunDecl) {
                        if (m.returnType != null && scanTypeRefWithSubst(m.returnType, subst)) changed = true
                        for (p in m.params) if (scanTypeRefWithSubst(p.type, subst)) changed = true
                        if (scanBodyWithSubst(m.body, subst)) changed = true
                    }
                }
                // Scan body property types and initializers
                for (m in templateDecl.members) {
                    if (m is PropDecl) {
                        if (m.type != null && scanTypeRefWithSubst(m.type, subst)) changed = true
                        if (scanExprWithSubst(m.init, subst)) changed = true
                    }
                }
                // Scan ctor param types
                for (p in templateDecl.ctorParams) {
                    if (scanTypeRefWithSubst(p.type, subst)) changed = true
                }
                // Scan init blocks
                for (initBlock in templateDecl.initBlocks) {
                    if (scanBodyWithSubst(initBlock, subst)) changed = true
                }
            }
        }
        // Re-materialize any newly discovered instantiations within the loop
        if (changed) materializeGenericInstantiations()
    }
}

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
            // Constructor call to generic class WITHOUT explicit type args: StdPair(this, that)
            // Infer type args from ctor param type names via current subst (e.g. A→"Int", B→"String")
            if (name != null && genericClassDecls.containsKey(name) && e.typeArgs.isEmpty()) {
                val vClassDecl = genericClassDecls[name]!! // generic class template declaration
                val vTypeParamSet = vClassDecl.typeParams.toSet() // type param names
                val vInferred = mutableMapOf<String, String>() // type param → resolved concrete type
                for (vCtorParam in vClassDecl.ctorParams) {
                    val vPTypeName = vCtorParam.type.name // param's type name (may be a type param)
                    if (vPTypeName in vTypeParamSet) {
                        val vResolved = subst[vPTypeName] // resolve via current substitution
                        if (vResolved != null && vResolved !in allGenericTypeParamNames) vInferred[vPTypeName] = vResolved
                    }
                }
                if (vInferred.size == vClassDecl.typeParams.size) {
                    val vArgs = vClassDecl.typeParams.map { vInferred[it]!! } // ordered resolved args
                    if (genericInstantiations[name]?.contains(vArgs) != true) {
                        recordGenericInstantiation(name, vArgs)
                        found = true
                    }
                }
            }
            // Check typeArgs for nested generic types (e.g., HeapAlloc<Array<T>>)
            for (ta in e.typeArgs) if (scanTypeRefWithSubst(ta, subst)) found = true
            // Check if it's a call to another generic function with resolvable type args
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
        // Generic class instantiation
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

/**
 * Pre-compute concrete return types for generic functions whose declared return
 * type is an interface but whose body returns a concrete class instance.
 * This enables returning the class by value (on the stack) instead of heap-allocating.
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

/**
 * Scan a function body to determine the concrete class returned.
 * Traces return statements back to var declarations with class constructor inits.
 */
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

/**
 * Match a parameter type against a concrete argument type to infer type params.
 * E.g., param=MutableList<T>, argType="MutableList_Int", typeParams={T} → subst\[T]=Int
 */
/*
Infer type parameter substitutions from a single param/arg pair.
Callers must call materializeGenericInstantiations() before invoking this so
genericTypeBindings is populated for any generic types inferred by inferExprType.
E.g., param=MutableList<T>, argType="MutableList_Int", typeParams={T} → subst[T]=Int
E.g., param=Pair<K,V>,       argType="Pair$2_Int_String",  typeParams={K,V} → subst[K]=Int, subst[V]=String
*/
internal fun CCodeGen.matchTypeParam(paramType: TypeRef, argType: String, typeParams: Set<String>, subst: MutableMap<String, String>) {
    // Direct type param: param is T, arg is Int → T=Int
    if (paramType.name in typeParams) {
        subst[paramType.name] = argType
        return
    }
    /* Generic class param (includes stdlib Pair<A,B>, Triple<A,B,C>):
    Uses genericTypeBindings populated by materializeGenericInstantiations().
    Checks genericClassDecls for the template (e.g. "Pair") and classes for
    already-materialized generic entries (e.g. "MutableList"). */
    if (paramType.typeArgs.isNotEmpty()) {
        val baseType = argType.trimEnd('*', '?') // strip pointer/nullable suffix
        val bindings = genericTypeBindings[baseType] // type bindings for the mangled arg type, or null
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
                        val templateParam = templateTypeParams[i] // template type param name (e.g. "A")
                        bindings[templateParam]?.let { subst[typeArg.name] = it }
                    }
                }
                return
            }
        }
    }
    // Generic interface param: List<T>, arg=ArrayList$1_Int → T=Int
    if (paramType.typeArgs.isNotEmpty() && genericIfaceDecls.containsKey(paramType.name)) {
        val baseType = argType.trimEnd('*', '?') // strip pointer/nullable suffix
        val classIfaces = classInterfaces[baseType] ?: return
        for (ifaceName in classIfaces) {
            /* New naming: List$1_Int — strip "List$", then "1_" arity prefix, then split type args */
            if (ifaceName.startsWith(paramType.name + "\$")) {
                val afterDollar = ifaceName.removePrefix(paramType.name + "\$") // "1_Int" or "2_Int_String"
                val underscoreIdx = afterDollar.indexOf('_')
                if (underscoreIdx < 0) return
                val extractedArgs = afterDollar.substring(underscoreIdx + 1).split("_")
                for ((i, typeArg) in paramType.typeArgs.withIndex()) {
                    if (typeArg.name in typeParams && i < extractedArgs.size) {
                        subst[typeArg.name] = extractedArgs[i]
                    }
                }
                return
            }
        }
    }
}
