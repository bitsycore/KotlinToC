package com.bitsycore.ktc.codegen.expression

import com.bitsycore.ktc.ast.DotExpr
import com.bitsycore.ktc.ast.Expr
import com.bitsycore.ktc.ast.IndexExpr
import com.bitsycore.ktc.ast.NameExpr
import com.bitsycore.ktc.codegen.CCodeGen
import com.bitsycore.ktc.codegen.cTypeStr
import com.bitsycore.ktc.codegen.inferExprTypeKtc
import com.bitsycore.ktc.codegen.stripNullable
import com.bitsycore.ktc.types.KtcType

// ── Name resolution and l-value generation ───────────────────────

/* Resolve a name expression: lambda subst, scope variable, field, object, enum, top-level prop. */
internal fun CCodeGen.genName(e: NameExpr): String {
	val vSubst  = lambdaParamSubst[e.name]
	if (vSubst != null) return vSubst
	val vCurType = lookupVar(e.name)
	val vCurKtc  = lookupVarKtc(e.name)
	// Check if it's a known variable in scope
	if (vCurType != null) {
		// Trampolined array param: redirect to local stack copy
		if (e.name in trampolinedParams) return "local$${e.name}"
		// Any trampoline smart-cast: narrowed from Any, dereference .data
		if (vCurKtc !is KtcType.Any && isAnySmartCastVar(e.name)) {
			val vCt = cTypeStr(vCurType)
			return "(*(($vCt*)(${e.name}.data)))"
			}
		val vCExpr = lookupCName(e.name)
		// Optional var smart-casted to non-nullable: unwrap
		if (isOptional(e.name) && vCurKtc !is KtcType.Nullable) {
			return "KTC_UNWRAP($vCExpr)"
			}
		return vCExpr
		}
	// Top-level property: apply package prefix
	if (e.name in topProps) return typeFlatName(e.name)
	// Object singleton: resolve to global instance name
	if (e.name in objects) return typeFlatName(e.name)
	if (e.name in enums)   return typeFlatName(e.name)
	// Bare field access when $self has been narrowed from interface in extension function
	if (currentExtRecvType != null && interfaces.containsKey(currentExtRecvType)) {
		val vNarrowedSelf = lookupVar("\$self")
		if (vNarrowedSelf != null && classes.containsKey(vNarrowedSelf)) {
			val vCi = classes[vNarrowedSelf]!!
			if (vCi.props.any { it.first == e.name }) {
				return "${ifaceUnionAccess(currentExtRecvType!!, vNarrowedSelf, "\$self")}.${e.name}"
				}
			}
		}
	return e.name
	}

/* Generate the C l-value expression for an assignment target. */
internal fun CCodeGen.genLValue(e: Expr): String {
	return when (e) {
		is NameExpr -> lookupCName(e.name)

		is DotExpr -> {
			if (e.obj is NameExpr && objects.containsKey(e.obj.name))
				"${typeFlatName(e.obj.name)}.${e.name}"
			else if (e.obj is NameExpr && classCompanions.containsKey(e.obj.name)) {
				val vCompanionName = classCompanions[e.obj.name]!!
				"${typeFlatName(vCompanionName)}.${e.name}"
				} else {
				val vRecvKtc     = inferExprTypeKtc(e.obj)
				val vRecvKtcCore = vRecvKtc.stripNullable
				val vOp          = if (vRecvKtcCore is KtcType.Ptr) "->" else "."
				"${genExpr(e.obj)}$vOp${e.name}"
				}
			}

		is IndexExpr -> {
			val vObjKtc        = inferExprTypeKtc(e.obj)
			val vObjKtcCore    = vObjKtc.stripNullable
			val vIsRawPtr      = vObjKtcCore is KtcType.Ptr && vObjKtcCore.inner !is KtcType.Arr // raw pointer (not @Ptr Array)
			val vIsSizedArr    = vObjKtcCore?.asArr?.sized != null                             // fixed-size C array
			val vIsTrampolined = e.obj is NameExpr && e.obj.name in trampolinedParams                             // @Size trampolined param
			if (vIsRawPtr || vIsSizedArr || vIsTrampolined) "${genExpr(e.obj)}[${genExpr(e.index)}]"
			else "${genExpr(e.obj)}.ptr[${genExpr(e.index)}]"
			}

		else -> genExpr(e)
		}
	}
