package com.bitsycore.ktc.codegen.expression

import com.bitsycore.ktc.ast.*
import com.bitsycore.ktc.codegen.*
import com.bitsycore.ktc.codegen.emit.ifaceDataName
import com.bitsycore.ktc.codegen.statement.checkWhenExhaustiveness
import com.bitsycore.ktc.codegen.statement.extractSmartCasts
import com.bitsycore.ktc.codegen.statement.genWhenCond
import com.bitsycore.ktc.codegen.statement.pushSmartCasts
import com.bitsycore.ktc.codegen.statement.popSmartCasts
import com.bitsycore.ktc.types.KtcType

internal fun CCodeGen.findCommonInterface(type1: String?, type2: String?): String? {
    if (type1 == null || type2 == null || type1 == type2) return null
    val ifaces1 = classInterfaces[type1]?.toSet() ?: return null
    val ifaces2 = classInterfaces[type2]?.toSet() ?: return null
    val common = ifaces1.intersect(ifaces2)
    return common.firstOrNull()
}

/** Emit block statements into preStmts, delegating the last expression to [assignLast]. */
private fun CCodeGen.emitBlockIntoTempBase(b: Block, indent: String, assignLast: (Expr) -> Unit) {
    for ((i, s) in b.stmts.withIndex()) {
        if (i == b.stmts.lastIndex) {
            val expr = when (s) {
                is ExprStmt   -> s.expr
                is ReturnStmt -> s.value
                else          -> null
                }
            if (expr != null) assignLast(expr) else emitStmtToPreStmts(s, indent)
            } else {
            emitStmtToPreStmts(s, indent)
            }
        }
    }

/** Emit block statements into preStmts, wrapping the last expression into an interface struct. */
internal fun CCodeGen.emitBlockIntoTempIface(b: Block, tempVar: String, concreteType: String, ifaceName: String, indent: String) {
    val cConcrete = typeFlatName(concreteType)
    val crossPkg = isImplCrossPkg(ifaceName, concreteType)
    emitBlockIntoTempBase(b, indent) { expr ->
        val valExpr = genExpr(expr)
        if (crossPkg) {
            val cType = if (objects.containsKey(concreteType)) "${cConcrete}_t" else cConcrete
            preStmts += "${indent}*($cType*)&$tempVar.data = $valExpr;"
        } else {
            val dataName = ifaceDataName(concreteType)
            preStmts += "$indent$tempVar.data.$dataName = $valExpr;"
        }
        if (ifaceName !in simpleUnionInterfaces)
            preStmts += "$indent$tempVar.vt = &${cConcrete}_${ifaceName}_vt;"
        }
    }

// ── if expression (as C ternary or temp) ─────────────────────────

internal fun CCodeGen.genIfExpr(e: IfExpr): String {
    val thenType = inferBlockType(e.then)
    val elseType = if (e.els != null) inferBlockType(e.els) else null
    val commonIface = findCommonInterface(thenType, elseType)

    // Interface coercion: branches return different types sharing a common interface
    if (commonIface != null) {
        val t = tmp()
        val cIface = typeFlatName(commonIface)
        preStmts += "$cIface $t;"
        preStmts += "if (${genExpr(e.cond)}) {"
        emitBlockIntoTempIface(e.then, t, thenType!!, commonIface, "    ")
        if (e.els != null) {
            preStmts += "} else {"
            emitBlockIntoTempIface(e.els, t, elseType!!, commonIface, "    ")
        }
        preStmts += "}"
        return t
    }

    val thenCasts = extractSmartCasts(e.cond)
    val elseCasts = extractSmartCasts(e.cond, forElse = true)
    val hasSmartCasts = thenCasts.isNotEmpty() || elseCasts.isNotEmpty()

    // Simple case: both branches are single expressions → ternary (only when no smart-cast needed)
    val thenExpr = blockAsSingleExpr(e.then)
    val elseExpr = if (e.els != null) blockAsSingleExpr(e.els) else null
    if (!hasSmartCasts && thenExpr != null && (e.els == null || elseExpr != null)) {
        val thenStr = genExpr(thenExpr)
        val elseStr = if (elseExpr != null) genExpr(elseExpr) else "0"
        return "(${genExpr(e.cond)} ? $thenStr : $elseStr)"
    }

    // Complex case or smart-cast needed: hoist to temp
    val t = tmp()
    val retType = inferIfExprType(e) ?: "Int"
    val ct = cTypeStr(retType)
    preStmts += "$ct $t;"
    preStmts += "if (${genExpr(e.cond)}) {"
    if (thenCasts.isNotEmpty()) {
        for ((name, type) in thenCasts) preStmts += "    // smart-cast: '$name' narrowed to '$type'"
        pushScope()
        for ((name, type) in thenCasts) {
            val ktc = parseResolvedTypeName(type)
            val cName = if (ktc is KtcType.Ptr) "((${ktc.toCType()})${lookupCName(name)})" else lookupLocalVar(name)?.cName
            defineVar(name, LocalVar(ktc = ktc, mutable = false, cName = cName))
        }
    }
    emitBlockIntoTemp(e.then, t, "    ")
    if (thenCasts.isNotEmpty()) popScope()
    if (e.els != null) {
        preStmts += "} else {"
        if (elseCasts.isNotEmpty()) {
            for ((name, type) in elseCasts) preStmts += "    // smart-cast: '$name' narrowed to '$type'"
            pushScope()
            for ((name, type) in elseCasts) {
                val ktc = parseResolvedTypeName(type)
                val cName = if (ktc is KtcType.Ptr) "((${ktc.toCType()})${lookupCName(name)})" else lookupLocalVar(name)?.cName
                defineVar(name, LocalVar(ktc = ktc, mutable = false, cName = cName))
            }
        }
        emitBlockIntoTemp(e.els, t, "    ")
        if (elseCasts.isNotEmpty()) popScope()
    }
    preStmts += "}"
    return t
}

/** Try to extract a single expression from a block (last stmt as expr). */
internal fun blockAsSingleExpr(b: Block): Expr? {
    if (b.stmts.size == 1) {
        val s = b.stmts[0]
        if (s is ExprStmt) return s.expr
    }
    return null
}

/** Emit block statements into preStmts, assigning the last expression to [tempVar]. */
internal fun CCodeGen.emitBlockIntoTemp(b: Block, tempVar: String, indent: String) =
    emitBlockIntoTempBase(b, indent) { expr -> preStmts += "$indent$tempVar = ${genExpr(expr)};" }

/** Emit a statement into preStmts (for hoisting into if/when expression bodies). */
internal fun CCodeGen.emitStmtToPreStmts(s: Stmt, indent: String) {
    when (s) {
        is ExprStmt -> {
            val expr = genExpr(s.expr)
            preStmts += "$indent$expr;"
        }

        is VarDeclStmt -> {
            val t = if (s.type != null) resolveTypeName(s.type).toInternalStr else (inferExprType(s.init) ?: "Int")
            val ct = cTypeStr(t)
            val initExpr = if (s.init != null) genExpr(s.init) else defaultVal(parseResolvedTypeName(t))
            preStmts += "$indent$ct ${s.name} = $initExpr;"
            defineVar(s.name, t)
        }

        else -> preStmts += "$indent/* unsupported stmt in expr block */;"
    }
}

internal fun CCodeGen.inferIfExprType(e: IfExpr): String? {
    val thenType = inferBlockType(e.then)
    val elseType = if (e.els != null) inferBlockType(e.els) else null
    val commonIface = findCommonInterface(thenType, elseType)
    if (commonIface != null) return commonIface
    return thenType ?: elseType
}

internal fun CCodeGen.inferBlockType(b: Block): String? {
    val last = b.stmts.lastOrNull() ?: return null
    return when (last) {
        is ExprStmt -> inferExprType(last.expr)
        is ReturnStmt -> if (last.value != null) inferExprType(last.value) else null
        else -> null
    }
}

// ── when expression (nested ternary or temp) ──────────────────────

internal fun CCodeGen.genWhenExpr(e: WhenExpr): String {
    checkWhenExhaustiveness(e)
    // ThisExpr subject maps to $self; NameExpr subject maps to its variable name
    val subjName = when (e.subject) {
        is NameExpr -> e.subject.name
        is ThisExpr -> "\$self"
        else -> null
    }
    // Check if branches need interface coercion (different types sharing a common interface)
    val branchTypes = e.branches.map { inferBlockType(it.body) }
    val distinctTypes = branchTypes.filterNotNull().distinct()
    val commonIface = if (distinctTypes.size > 1) {
        var common: Set<String>? = null
        for (t in distinctTypes) {
            val ifaces = classInterfaces[t]?.toSet() ?: break
            common = common?.intersect(ifaces) ?: ifaces
            if (common.isEmpty()) break
        }
        common?.firstOrNull()
    } else null

    if (commonIface != null) {
        // Interface coercion: use temp with interface type, wrap each branch
        val t = tmp()
        val cIface = typeFlatName(commonIface)
        preStmts += "$cIface $t;"
        for ((bi, br) in e.branches.withIndex()) {
            if (br.conds == null) {
                preStmts += if (bi > 0) "} else {" else "{"
            } else {
                val condStr = br.conds.joinToString(" || ") { genWhenCond(it, e.subject) }
                val keyword = if (bi == 0) "if" else "} else if"
                preStmts += "$keyword ($condStr) {"
            }
            val narrowedType = narrowSubjectForBranch(br, subjName)
            if (narrowedType != null) {
                preStmts += "    // smart-cast: '$subjName' narrowed to '$narrowedType'"
                pushScope(); defineVar(subjName!!, narrowedType)
            }
            val brType = branchTypes[bi]
            if (brType != null && classes.containsKey(brType)) {
                emitBlockIntoTempIface(br.body, t, brType, commonIface, "    ")
            } else {
                emitBlockIntoTemp(br.body, t, "    ")
            }
            if (narrowedType != null) popScope()
        }
        preStmts += "}"
        return t
    }

    // If any branch needs is-narrowing, avoid ternary: accessing wrong union member is UB
    val hasNarrowingBranch = e.branches.any { narrowSubjectForBranch(it, subjName) != null }
    // Check if all branches are single-expression → nested ternary (only when no narrowing needed)
    val allSimple = !hasNarrowingBranch && e.branches.all { blockAsSingleExpr(it.body) != null }
    if (allSimple) {
        val sb = StringBuilder()
        for (br in e.branches) {
            val narrowedType = narrowSubjectForBranch(br, subjName)
            if (narrowedType != null) {
                pushScope(); defineVar(subjName!!, narrowedType)
            }
            val expr = genExpr(blockAsSingleExpr(br.body)!!)
            if (narrowedType != null) popScope()
            if (br.conds == null) {
                sb.append(expr)
            } else {
                val cond = br.conds.joinToString(" || ") { genWhenCond(it, e.subject) }
                sb.append("($cond) ? $expr : ")
            }
        }
        return sb.toString()
    }

    // Complex case: hoist to temp
    val t = tmp()
    val retType = inferWhenExprType(e) ?: "Int"
    val ct = cTypeStr(retType)
    preStmts += "$ct $t;"
    for ((bi, br) in e.branches.withIndex()) {
        if (br.conds == null) {
            preStmts += if (bi > 0) "} else {"
            else "{"
        } else {
            val condStr = br.conds.joinToString(" || ") { genWhenCond(it, e.subject) }
            val keyword = if (bi == 0) "if" else "} else if"
            preStmts += "$keyword ($condStr) {"
        }
        val narrowedType = narrowSubjectForBranch(br, subjName)
        if (narrowedType != null) {
            preStmts += "    // smart-cast: '$subjName' narrowed to '$narrowedType'"
            pushScope(); defineVar(subjName!!, narrowedType)
        }
        emitBlockIntoTemp(br.body, t, "    ")
        if (narrowedType != null) popScope()
    }
    preStmts += "}"
    return t
}

internal fun CCodeGen.narrowSubjectForBranch(br: WhenBranch, subjName: String?): String? {
    if (br.conds == null || subjName == null || isMutable(subjName)) return null
    val isCond = br.conds.find { it is IsCond && !it.negated } as? IsCond ?: return null
    val target = resolveTypeName(isCond.type).toInternalStr
    // $self in extension function: use currentExtRecvType as the base type when not in scope
    val currentKtc = if (subjName == "\$self") (lookupVarKtc("\$self") ?: (currentExtRecvType?.let { parseResolvedTypeName(it) })) ?: return null
                      else lookupVarKtc(subjName) ?: return null
    val current = currentKtc.toInternalStr
    // Don't narrow pointer types (Any* etc.) — they need original type for ->data dereference
    if (currentKtc is KtcType.Ptr) return null
    val resolved = resolveMonoNestedClass(target, currentKtc.stripNullable.toInternalStr)
    return if (current != resolved) resolved else null
}

internal fun CCodeGen.inferWhenExprType(e: WhenExpr): String? {
    val types = e.branches.mapNotNull { inferBlockType(it.body) }
    if (types.isEmpty()) return null
    if (types.distinct().size > 1) {
        var common: Set<String>? = null
        for (t in types) {
            val ifaces = classInterfaces[t]?.toSet() ?: break
            common = common?.intersect(ifaces) ?: ifaces
            if (common.isEmpty()) break
        }
        if (!common.isNullOrEmpty()) return common.first()
    }
    return types.first()
}

/* Returns true when expr contains no function calls — safe to evaluate multiple times without side effects. */
internal fun isSimpleCExpr(inExpr: String) = '(' !in inExpr

// ── println / print (expression context — rare) ──────────────────
