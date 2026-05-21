package com.bitsycore.ktc.codegen.expr

import com.bitsycore.ktc.ast.*
import com.bitsycore.ktc.codegen.*
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
		if (currentClass != null && classes[currentClass]?.props?.any { it.first == e.name } == true) {
			val vCi        = classes[currentClass]!!
			val vFieldName = if (e.name in vCi.privateProps) "PRIV_${e.name}" else e.name
			val vFieldRef  = if (selfIsPointer) "\$self->${vFieldName}" else "\$self.${vFieldName}"
			// If field is stored as Optional but accessed after smart-cast (non-nullable context), unwrap
			val vFieldType = vCi.props.find { it.first == e.name }?.second
			if (vFieldType?.nullable == true && vCurKtc !is KtcType.Nullable) {
				return "KTC_UNWRAP($vFieldRef)"
				}
			return vFieldRef
			}
		val vCurObj = currentObject
		if (vCurObj != null && objects[vCurObj]?.props?.any { it.first == e.name } == true) {
			val vObjInfo = objects[vCurObj]!!
			val vFn      = if (e.name in vObjInfo.privateProps) "PRIV_${e.name}" else e.name
			return "${typeFlatName(vCurObj)}.$vFn"
			}
		// Inside a class nested within an object: resolve to parent object's field
		val vParentObj = currentClass?.substringBefore('$')
		if (vParentObj != null && currentObject == null
			&& objects[vParentObj]?.props?.any { it.first == e.name } == true) {
			val vObjInfo = objects[vParentObj]!!
			val vFn      = if (e.name in vObjInfo.privateProps) "PRIV_${e.name}" else e.name
			return "${typeFlatName(vParentObj)}.$vFn"
			}
		// Trampolined array param: redirect to local stack copy
		if (e.name in trampolinedParams) return "local$${e.name}"
		// Any trampoline smart-cast: narrowed from Any, dereference .data
		if (vCurKtc !is KtcType.Any && isAnySmartCastVar(e.name)) {
			val vCt = cTypeStr(vCurType)
			return "(*(($vCt*)(${e.name}.data)))"
			}
		// Optional var smart-casted to non-nullable: unwrap
		if (isOptional(e.name) && vCurKtc !is KtcType.Nullable) {
			return "KTC_UNWRAP(${e.name})"
			}
		return e.name
		}
	// Top-level property: apply package prefix
	if (e.name in topProps) return typeFlatName(e.name)
	// Object singleton: resolve to global instance name
	if (e.name in objects) return typeFlatName(e.name)
	if (e.name in enums)   return typeFlatName(e.name)
	// Inside a class nested within an object: resolve to parent object's field/function
	val vParentObj2 = currentClass?.substringBefore('$')
	if (vParentObj2 != null && currentObject == null) {
		val vObjInfo = objects[vParentObj2]
		if (vObjInfo?.props?.any { it.first == e.name } == true) {
			val vFn = if (e.name in vObjInfo.privateProps) "PRIV_${e.name}" else e.name
			return "${typeFlatName(vParentObj2)}.$vFn"
			}
		}
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
internal fun CCodeGen.genLValue(e: Expr, method: Boolean): String {
	return when (e) {
		is NameExpr -> {
			if (method && currentClass != null && classes[currentClass]?.props?.any { it.first == e.name } == true) {
				val vCi        = classes[currentClass]!!
				val vFieldName = if (e.name in vCi.privateProps) "PRIV_${e.name}" else e.name
				if (selfIsPointer) "\$self->${vFieldName}" else "\$self.${vFieldName}"
				} else if (currentObject.let { vCO -> vCO != null && objects[vCO]?.props?.any { it.first == e.name } == true })
				"${typeFlatName(currentObject!!)}.${e.name}"
			else e.name
			}

		is DotExpr -> {
			if (e.obj is NameExpr && objects.containsKey(e.obj.name))
				"${typeFlatName(e.obj.name)}.${e.name}"
			else if (e.obj is NameExpr && classCompanions.containsKey(e.obj.name)) {
				val vCompanionName = classCompanions[e.obj.name]!!
				"${typeFlatName(vCompanionName)}.${e.name}"
				} else {
				val vRecvKtc     = inferExprTypeKtc(e.obj)
				val vRecvKtcCore = (vRecvKtc as? KtcType.Nullable)?.inner ?: vRecvKtc
				val vOp          = if (vRecvKtcCore is KtcType.Ptr) "->" else "."
				"${genExpr(e.obj)}$vOp${e.name}"
				}
			}

		is IndexExpr -> {
			val vObjKtc     = inferExprTypeKtc(e.obj)
			val vObjKtcCore = (vObjKtc as? KtcType.Nullable)?.inner ?: vObjKtc
			if (vObjKtcCore != null && (vObjKtcCore is KtcType.Ptr || vObjKtcCore.isArrayLike)) {
				"${genExpr(e.obj)}[${genExpr(e.index)}]"
				} else {
				"${genExpr(e.obj)}.ptr[${genExpr(e.index)}]"
				}
			}

		else -> genExpr(e)
		}
	}
