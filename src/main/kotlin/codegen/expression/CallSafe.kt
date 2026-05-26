package com.bitsycore.ktc.codegen.expression

import com.bitsycore.ktc.ast.*
import com.bitsycore.ktc.codegen.*
import com.bitsycore.ktc.codegen.emit.collectAllIfaceMethods
import com.bitsycore.ktc.types.KtcType

/* Wrap a safe-call result: NULL for pointer returns, Optional for value returns. */
private fun CCodeGen.wrapSafeCallResult(retType: String, guard: String, call: String): String {
	val retKtc = parseResolvedTypeName(retType)
	if (retKtc is KtcType.Ptr) {
		val t = tmp()
		preStmts += "${cTypeStr(retType)} $t = $guard ? $call : NULL;"
		defineVar(t, "${retType}?")
		return t
		}
	val optType = optCTypeName("${retType}?")
	return tmpOptional(retType, "$guard ? ${optSome(optType, call)} : ${optNone(optType)}")
	}

/* Handle a safe (?.) method call, wrapping the result in Optional or NULL guard as needed. */
internal fun CCodeGen.genSafeMethodCall(dot: SafeDotExpr, args: List<Arg>): String {
	val recvName         = (dot.obj as? NameExpr)?.name
	val recvTypeKtc      = if (recvName != null) lookupVarKtc(recvName) else inferExprTypeKtc(dot.obj)
	val recvTypeCoreKtc  = recvTypeKtc.stripNullable
	val recvType         = recvTypeKtc?.toInternalStr
	// Warn: ?. method call on a non-nullable receiver (and not a pointer)
	if (recvTypeKtc != null && recvTypeKtc !is KtcType.Nullable && recvTypeCoreKtc !is KtcType.Ptr) {
		val vSrc = recvName ?: "expression"
		codegenWarning("Safe call '?.' on non-nullable '$recvType' ($vSrc.${dot.name}) is redundant; use '.' instead")
		}
	val isValueNullRecv = recvTypeKtc is KtcType.Nullable && isValueNullableKtc(recvTypeKtc)

	// Handle intermediate safe-call chains (e.g., a?.b()?.c())
	if (recvName == null) {
		val recvExpr  = genExpr(dot.obj)  // pre-compute, returns temp name from inner safe-call
		val dotExpr2  = DotExpr(NameExpr(recvExpr), dot.name)
		val call2     = genMethodCall(dotExpr2, args)
		val guard2    = if (recvTypeKtc is KtcType.Nullable) nullGuardExpr(recvTypeKtc, recvExpr, recvExpr, isThis = false) else "true"
		val retType2  = inferMethodReturnType(dotExpr2, args)
		if (retType2 == null || retType2 == "Unit") {
			return "($guard2 ? ($call2, 0) : 0)"
			}
		return wrapSafeCallResult(retType2, guard2, call2)
		}

	val dotExpr = DotExpr(dot.obj, dot.name)

	// Handle .ptr() safe-call: guard first, then take address
	if (dot.name == "ptr" && isValueNullRecv) {
		val baseClass = recvType!!.removeSuffix("?")
		val cName     = typeFlatName(baseClass)
		val t         = tmp()
		preStmts += "$cName* $t = ($recvName.tag == ktc_SOME ? &${recvName}.value : NULL);"
		defineVar(t, "${baseClass}*?")
		return t
		}
	if (dot.name == "ptr" && recvType != null) {
		val cleanType = recvType.removeSuffix("?")
		if (recvTypeCoreKtc != null && recvTypeCoreKtc.isArrayLike) {
			// Array?.ptr → return .ptr field directly; NULL when array is null (ptr == NULL)
			val t        = tmp()
			val arrCType = cTypeStr(cleanType)
			preStmts += "$arrCType $t = $recvName.ptr;"
			defineVar(t, "${cleanType}*?")
			return t
			}
		}

	val call    = genMethodCall(dotExpr, args)
	// Determine the null guard expression
	val guard   = if (recvTypeKtc != null) nullGuardExpr(recvTypeKtc, recvName, recvName, isThis = false) else "${recvName}\$has"
	// Determine the return type
	val retType = inferMethodReturnType(dotExpr, args)
	if (retType == null || retType == "Unit") {
		return "($guard ? ($call, 0) : 0)"
		}
	// Pointer return (Ref<T>): use NULL for null; value return: wrap in Optional
	return wrapSafeCallResult(retType, guard, call)
	}

// ==================
// MARK: Default argument helpers
// ==================

/* Resolve the effective defaults for a method, preferring local overrides and falling back to the interface declaration. */
internal fun CCodeGen.effectiveDefaults(
	inMethodDecl: FunDecl,
	inOwnerName: String?
): Map<String, Expr?> {
	val vLocal = inMethodDecl.params.associate { it.name to it.default }  // defaults on the override itself
	if (!inMethodDecl.isOverride || inOwnerName == null) return vLocal
	val vIfaces = classInterfaces[inOwnerName] ?: return vLocal
	for (vIfaceName in vIfaces) {
		val vIfaceInfo   = interfaces[vIfaceName] ?: continue
		val vIfaceMethod = collectAllIfaceMethods(vIfaceInfo).find { it.name == inMethodDecl.name }
			?: continue
		val vIfaceDefaults = vIfaceMethod.params.associate { it.name to it.default }
		return vLocal.mapValues { (vKey, vVal) -> vVal ?: vIfaceDefaults[vKey] }  // local wins, iface fills gaps
		}
	return vLocal
	}

/*
strict=true: called after overload resolution is already committed to this specific FunDecl.
strict=false (default): called speculatively (e.g. funSigs pre-check before overload resolution).
Only strict mode emits the missing-required-args error to avoid false positives during resolution.
*/
internal fun CCodeGen.fillDefaults(
	args: List<Arg>,
	params: List<Param>,
	defaults: Map<String, Expr?>,
	funName: String = "<unknown>",
	strict: Boolean = false
): List<Arg> {
	val hasVararg      = params.any { it.isVararg }
	val nonVarargCount = params.count { !it.isVararg }

	// Check: too many positional arguments (only for non-vararg functions)
	if (!hasVararg && args.none { it.name != null } && args.size > nonVarargCount) {
		codegenError("Too many arguments for '$funName': expected $nonVarargCount, got ${args.size}")
		}

	if (args.size >= params.size) return args

	// Check: required arguments missing (no default, not vararg)
	// Only in strict mode to avoid false positives from the funSigs pre-resolution path.
	if (strict) {
		val requiredMissing = params
			.drop(args.size)
			.filter { !it.isVararg && defaults[it.name] == null }
		if (requiredMissing.isNotEmpty()) {
			codegenError("Missing required argument(s) for '$funName': ${requiredMissing.joinToString(", ") { it.name }}")
			}
		}

	// Named args: reorder
	val hasNamed = args.any { it.name != null }
	if (hasNamed) {
		val result = params.mapNotNull { p ->
			if (p.isVararg) return@mapNotNull null  // vararg handled by expandCallArgs
			val explicit = args.find { it.name == p.name }
			explicit ?: Arg(p.name, defaults[p.name] ?: IntLit(0))
			}
		return result
		}
	// Positional: fill missing from defaults (skip vararg params)
	val result = args.toMutableList()
	for (i in args.size until params.size) {
		if (params[i].isVararg) continue  // vararg handled by expandCallArgs
		val def = defaults[params[i].name]
		result += Arg(null, def ?: IntLit(0))
		}
	return result
	}
