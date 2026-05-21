package com.bitsycore.ktc.codegen

import com.bitsycore.ktc.ast.*
import com.bitsycore.ktc.codegen.expr.inferIfExprType
import com.bitsycore.ktc.codegen.expr.inferInlineFunSubst
import com.bitsycore.ktc.codegen.expr.inferWhenExprType
import com.bitsycore.ktc.types.KtcType

// Expression type inference — main dispatcher and KtcType entry point.
// Call/method return types are in TypeInferCall.kt.
// Dot/index field types are in TypeInferDot.kt.

internal fun CCodeGen.inferExprType(e: Expr?): String? = when (e) {
	null         -> null
	is IntLit    -> "Int"
	is LongLit   -> "Long"
	is UIntLit   -> "UInt"
	is ULongLit  -> "ULong"
	is DoubleLit -> "Double"
	is FloatLit  -> "Float"
	is BoolLit   -> "Boolean"
	is CharLit   -> "Char"
	is StrLit, is StrTemplateExpr -> "String"
	is NullLit   -> null
	is ThisExpr  -> lambdaParamTypes["\$this"] ?: lookupVar("\$self") ?: currentExtRecvType ?: currentClass
	is NameExpr  -> lambdaParamTypes[e.name] ?: lookupVar(e.name) ?: run {
		if (enums.containsKey(e.name)) e.name
		else if (objects.containsKey(e.name)) e.name
		else {
			val vNarrowedSelf = if (currentExtRecvType != null && interfaces.containsKey(currentExtRecvType))
				lookupVar("\$self") else null
			if (vNarrowedSelf != null && classes.containsKey(vNarrowedSelf)) {
				val vCi = classes[vNarrowedSelf]!!
				val vProp = vCi.props.find { it.first == e.name }
				if (vProp != null) resolveTypeName(vProp.second).toInternalStr else null
				} else {
				val parentObj = currentClass?.substringBefore('$')
				if (parentObj != null && currentObject == null) {
					val oi = objects[parentObj]
					if (oi?.props?.any { it.first == e.name } == true)
						resolveTypeName(oi.props.find { it.first == e.name }!!.second).toInternalStr
					else null
					} else null
				}
			}
		}
	is BinExpr -> {
		if (e.op in setOf("==", "!=", "<", ">", "<=", ">=", "&&", "||", "in", "!in")) "Boolean"
		else if (e.op == "..") "IntRange"
		else if (e.op == "to") {
			val a = inferExprType(e.left) ?: "Int"
			val b = inferExprType(e.right) ?: "Int"
			if (genericClassDecls.containsKey("Pair")) {
				val vMangled = recordGenericInstantiation("Pair", listOf(a, b))
				materializeGenericInstantiations()
				vMangled
				} else "Pair_${a}_${b}"
			}
		else {
			val vInfixDecl = inlineExtFunDecls[e.op]
			if (vInfixDecl != null && vInfixDecl.returnType != null) {
				val vRecvType   = inferExprType(e.left)
				val vArgType    = inferExprType(e.right)
				val vSavedSubst = typeSubst
				if (vInfixDecl.typeParams.isNotEmpty()) typeSubst = inferInlineFunSubst(vInfixDecl, vRecvType, listOf(vArgType))
				val vResult = resolveTypeName(vInfixDecl.returnType).toInternalStr
				typeSubst = vSavedSubst
				vResult
				} else inferExprType(e.left)
			}
		}
	is PrefixExpr   -> if (e.op == "!") "Boolean" else inferExprType(e.expr)
	is PostfixExpr  -> inferExprType(e.expr)
	is CallExpr     -> inferCallType(e)
	is DotExpr      -> inferDotType(e)
	is SafeDotExpr  -> inferDotTypeSafe(e)
	is IndexExpr    -> inferIndexType(e)
	is IfExpr       -> inferIfExprType(e)
	is WhenExpr     -> inferWhenExprType(e)
	is NotNullExpr  -> inferExprType(e.expr)?.removeSuffix("?")
	is ElvisExpr    -> (inferExprType(e.left) ?: inferExprType(e.right))?.removeSuffix("?")
	is IsCheckExpr  -> "Boolean"
	is CastExpr     -> if (e.safe) e.type.name + "?" else e.type.name
	is FunRefExpr -> {
		val sig = funSigs[e.name]
		if (sig != null) {
			val params = sig.params.joinToString(",") { resolveTypeName(it.type).toInternalStr }
			val ret = if (sig.returnType != null) resolveTypeName(sig.returnType).toInternalStr else "Unit"
			"Fun($params)->$ret"
			} else null
		}
	is LambdaExpr -> null
	}

/*
Infer the KtcType of an expression.
For NameExpr uses lookupVarKtc directly to avoid string round-trip.
All other branches delegate to inferExprType and convert via stringToKtc.
*/
internal fun CCodeGen.inferExprTypeKtc(inExpr: Expr?): KtcType? {
	if (inExpr == null) return null
	if (inExpr is NameExpr) {
		val vKtc = lookupVarKtc(inExpr.name)
		if (vKtc != null) return vKtc
		/* Outer object props are not registered in local scope — look them up directly
		to avoid the string round-trip that would lose @Size annotations. */
		val vParentObj = currentClass?.substringBefore('$')
		if (vParentObj != null && currentObject == null) {
			val vOi   = objects[vParentObj]
			val vProp = vOi?.props?.find { it.first == inExpr.name }
			if (vProp != null) return resolveTypeName(vProp.second)
			}
		/* Also check the current object's own props (for methods inside objects). */
		if (currentObject != null) {
			val vOi   = objects[currentObject]
			val vProp = vOi?.props?.find { it.first == inExpr.name }
			if (vProp != null) return resolveTypeName(vProp.second)
			}
		}
	val vStr = inferExprType(inExpr) ?: return null
	return parseResolvedTypeName(vStr)
	}
