package com.bitsycore.ktc.codegen.expr

import com.bitsycore.ktc.ast.*
import com.bitsycore.ktc.codegen.*
import com.bitsycore.ktc.types.KtcType

/* Returns the size expression for a trampolined param's array length.
For sized struct params (in sizedArrayTrampolinedParams), the local$name$len constant is used.
For regular ktc_VarArr_T params, the .len field is used. */
internal fun CCodeGen.arrayParamSizeExpr(inName: String): String =
	if (inName in sizedArrayTrampolinedParams) "local\$${inName}\$len" else "$inName.len"

/* If expr accesses a @Size(N) array field, return N. */
private fun CCodeGen.getSizedArrayFieldSize(expr: Expr): Int? {
	val fieldName = when (expr) {
		is DotExpr  -> expr.name.removePrefix("PRIV_")
		is NameExpr -> expr.name
		else        -> return null
		}
	val ci = currentClass?.let { classes[it] }
	val bp = ci?.bodyProps?.find { it.name == fieldName }
	if (bp != null && bp.typeRef.hasSizeAnnotation()) {
		return bp.typeRef.getSizeAnnotation()
		}
	return null
	}

/* Expand call arguments: array → VarArr struct; nullable → (arg, arg$has); class→interface wrapping; vararg packing. */
internal fun CCodeGen.expandCallArgs(args: List<Arg>, params: List<Param>?, isCtorCall: Boolean = false): String {
	val parts = mutableListOf<String>()
	if (params == null) {
		for (arg in args) parts += genExpr(arg.expr)
		return parts.joinToString(", ")
		}

	var argIdx = 0
	for (param in params) {
		val paramType    = resolveTypeName(param.type).toInternalStr   // string type (for structural checks: endsWith, isArray, etc.)
		val paramTypeKtc = resolveTypeName(param.type)                 // KtcType (for C type emission)
		if (param.isVararg) {
			// Consume remaining args for vararg — packed into a single ktc_VarArr_T
			val remaining   = args.subList(argIdx, args.size)
			val elemCType   = cTypeStr(paramTypeKtc)                    // element C type
			val vVarArrType = varArrTypeName(elemCType)
			if (remaining.size == 1 && remaining[0].isSpread) {
				// Spread arg: already a ktc_VarArr_T — pass directly
				parts += genExpr(remaining[0].expr)
				} else if (remaining.isEmpty()) {
				parts += "($vVarArrType){NULL, 0}"
				} else {
				val t        = tmp()
				val argExprs = remaining.map { genExpr(it.expr) }
				preStmts += "$elemCType ${t}_data[] = {${argExprs.joinToString(", ")}};"
				parts += "($vVarArrType){${t}_data, ${remaining.size}}"
				}
			argIdx = args.size
			} else if (argIdx < args.size) {
			val arg    = args[argIdx]
			val expr   = genExpr(arg.expr)
			val hasAtPtr        = param.type.annotations.any { it.name == "Ptr" }
			// User-class pointer (e.g. @Ptr Vec2 → Vec2*), NOT a typed array pointer (IntArray → Ptr<Arr<Int>>)
			val isPtrOrArrayPtr = paramTypeKtc is KtcType.Ptr && paramTypeKtc.inner !is KtcType.Arr
			if (hasAtPtr || isPtrOrArrayPtr) {
				// @Ptr-annotated type — pass raw pointer (NULL for null)
				if (arg.expr is NullLit) {
					val vNullIsVarArr = paramTypeKtc is KtcType.Ptr && paramTypeKtc.inner is KtcType.Arr && (paramTypeKtc.inner as KtcType.Arr).sized == null
					if (vNullIsVarArr) {
						// @Ptr Array<T> null: emit {NULL, 0} struct (same as regular nullable array)
						val vInner   = (paramTypeKtc as KtcType.Ptr).inner as KtcType.Arr
						val vElemC   = if (vInner.elem is KtcType.Nullable) optCTypeName(vInner.elem.inner.toInternalStr) else cTypeStr(vInner.elem)
						parts += "(${varArrTypeName(vElemC)}){NULL, 0}"
						} else if (paramTypeKtc is KtcType.Ptr && paramTypeKtc.inner is KtcType.User
						&& paramTypeKtc.inner.kind == KtcType.UserKind.Interface)
						parts += "(ktc_IfacePtr){0}"   // zero-init for iface trampoline
					else parts += "NULL"
					} else if ((paramTypeKtc as? KtcType.Ptr)?.inner?.let { it is KtcType.Any || (it is KtcType.User && it.baseName == "Any") } == true) {
					// @Ptr Any → check if arg is VarArr first (extract .ptr for void* cast)
					val vAnyArgKtc  = inferExprTypeKtc(arg.expr)
					val vAnyArgCore = (vAnyArgKtc as? KtcType.Nullable)?.inner ?: vAnyArgKtc
					if (vAnyArgCore?.isArrayLike == true && vAnyArgCore.asArr?.sized == null) {
						// VarArr passed to @Ptr Any: extract raw .ptr
						parts += "(void*)($expr).ptr"
						} else {
					// Wrap as ktc_Any fat pointer, pass pointer to it.
					val argType  = inferExprType(arg.expr)?.removeSuffix("?") ?: "Int"
					val typeId   = getTypeId(argType)
					val ct       = cTypeStr(argType)
					val dataRef: String
					if (arg.expr is NameExpr) {
						dataRef = "&$expr"
						} else {
						val tVal = tmp()
						preStmts += "$ct $tVal = $expr;"
						dataRef = "&$tVal"
						}
					val tAny = tmp()
					preStmts += "ktc_Any $tAny = {{$typeId}, (void*)$dataRef};"
					parts += "&$tAny"
					}
					} else if ((paramTypeKtc as? KtcType.Ptr)?.inner is KtcType.User && interfaces.containsKey((paramTypeKtc.inner as KtcType.User).baseName)) {
					// @Ptr InterfaceType → wrap into ktc_IfacePtr trampoline
					val ifaceName   = paramTypeKtc.inner.baseName
					val cIface      = typeFlatName(ifaceName)
					val argKtc      = inferExprTypeKtc(arg.expr)
					val argKtcCore  = (argKtc as? KtcType.Nullable)?.inner ?: argKtc
					val concreteName = when {
						arg.expr is NameExpr && classes.containsKey(arg.expr.name)  -> arg.expr.name
						arg.expr is NameExpr && objects.containsKey(arg.expr.name)  -> arg.expr.name
						argKtcCore is KtcType.User                                   -> argKtcCore.baseName
						(argKtcCore as? KtcType.Ptr)?.inner is KtcType.User          -> (argKtcCore.inner as KtcType.User).baseName
						else                                                          -> null
						}
					if (concreteName != null) {
						val cConcrete      = typeFlatName(concreteName)
						val typeId         = getTypeId(concreteName)
						// Objects are always @Ptr (genName returns &objName), so just cast
						val objPtr: String = if (arg.expr is NameExpr && objects.containsKey(arg.expr.name)) {
							"(void*)&$expr"
							} else if (arg.expr is NameExpr && argKtcCore is KtcType.Ptr) {
							expr
							} else if (arg.expr is NameExpr) {
							expr
							} else if (argKtcCore is KtcType.Ptr) {
							expr
							} else {
							val tVal = tmp()
							val ct   = cTypeStr(argKtcCore?.toInternalStr ?: "Int")
							preStmts += "$ct $tVal = $expr;"
							"&$tVal"
							}
						val tIface         = tmp()
						val argIsNullable  = argKtc is KtcType.Nullable || inferExprTypeKtc(arg.expr) is KtcType.Nullable
						preStmts += if (argIsNullable) {
							"ktc_IfacePtr $tIface = ($expr) ? ((ktc_IfacePtr){{$typeId}, (const void*)&${cConcrete}_${ifaceName}_vt, (void*)($expr)}) : ((ktc_IfacePtr){0});"
							} else {
							"ktc_IfacePtr $tIface = {{$typeId}, (const void*)&${cConcrete}_${ifaceName}_vt, $objPtr};"
							}
						parts += tIface
						} else {
						parts += expr
						}
					} else {
					val vIsArrPtr = paramTypeKtc is KtcType.Ptr && paramTypeKtc.inner is KtcType.Arr && (paramTypeKtc.inner as? KtcType.Arr)?.sized == null
					if (vIsArrPtr) {
						// @Ptr Array<T>: now ktc_VarArr_T — pass struct with .ptr cast if needed
						val vInnerArr  = (paramTypeKtc as KtcType.Ptr).inner as KtcType.Arr
						val vElemCType = if (vInnerArr.elem is KtcType.Nullable) optCTypeName(vInnerArr.elem.inner.toInternalStr) else cTypeStr(vInnerArr.elem)
						val vVarArrTp  = varArrTypeName(vElemCType)
						val vArgName   = (arg.expr as? NameExpr)?.name
						if (vArgName != null && vArgName in trampolinedParams) {
							parts += "($vVarArrTp){($vElemCType*)local\$$vArgName, ${arrayParamSizeExpr(vArgName)}}"
							} else {
							val vSrcKtc   = inferExprTypeKtc(arg.expr)
							val vSrcCore  = (vSrcKtc as? KtcType.Nullable)?.inner ?: vSrcKtc
							val vSrcElem  = vSrcCore?.asArr?.elem
							val vSrcElemC = vSrcElem?.let {
								if (it is KtcType.Nullable) optCTypeName(it.inner.toInternalStr) else cTypeStr(it)
								}
							if (vSrcElemC == vElemCType && vSrcCore?.asArr?.sized == null) {
								parts += expr
								} else {
								parts += "($vVarArrTp){($vElemCType*)${arrayDataPtr(expr, vSrcKtc)}, ${arrayDataLen(expr, vSrcKtc)}}"
								}
							}
						} else if (paramTypeKtc is KtcType.Ptr && (paramTypeKtc.inner is KtcType.Void || paramTypeKtc.inner is KtcType.Any)) {
						// AnyPtr / @Ptr Any (void*): extract .ptr when arg is a VarArr
						val vVoidArgKtc  = inferExprTypeKtc(arg.expr)
						val vVoidArgCore = (vVoidArgKtc as? KtcType.Nullable)?.inner ?: vVoidArgKtc
						if (vVoidArgCore?.isArrayLike == true && vVoidArgCore.asArr?.sized == null) {
							parts += "(void*)($expr).ptr"
							} else {
							parts += expr
							}
						} else if (paramTypeKtc is KtcType.Ptr) {
						// Raw pointer param (Ptr(T) where T is not a VarArr): extract .ptr when arg is a VarArr
						val vRawArgKtc  = inferExprTypeKtc(arg.expr)
						val vRawArgCore = (vRawArgKtc as? KtcType.Nullable)?.inner ?: vRawArgKtc
						if (vRawArgCore?.isArrayLike == true && vRawArgCore.asArr?.sized == null) {
							parts += "($expr).ptr"
							} else {
							parts += expr
							}
						} else {
						parts += expr
						}
					}
				} else if (param.type.isSizedString()) {
				// @Size(N) String param — wrap ktc_String into ktc_String_N value struct
				val vSize       = param.type.getSizeAnnotation()!!
				val vStructType = sizedStringCTypeRef(vSize)
				val vWrap       = tmp()
				preStmts += "$vStructType $vWrap;"
				preStmts += "memcpy($vWrap.buf, ($expr).ptr, ($expr).len * sizeof(ktc_Char)); $vWrap.len = ($expr).len;"
				parts += vWrap
				} else if (isArrayType(paramType)) {
				if (param.type.hasSizeAnnotation()) {
					// @Size(N) array param — wrap pointer into ktc_Array_T_N value struct
					val vElemKtc    = paramTypeKtc.asArr!!.elem  // element KtcType
					val vElemCType  = cTypeStr(vElemKtc)         // element C type
					val vSize       = param.type.getSizeAnnotation()!!
					val vStructType = sizedArrayCTypeRef(vElemCType, vSize)
					val vWrap       = tmp()
					preStmts += "$vStructType $vWrap;"
					val vSrcKtc = inferExprTypeKtc(arg.expr)
					preStmts += "memcpy($vWrap.arr, ${arrayDataPtr(expr, vSrcKtc)}, $vSize * sizeof($vElemCType));"
					parts += vWrap
					} else {
					// Variable-size array → build typed ktc_VarArr_T literal
					val vElemKtc    = paramTypeKtc.asArr?.elem
						?: (paramTypeKtc as? KtcType.Nullable)?.inner?.asArr?.elem
						?: KtcType.Prim(KtcType.PrimKind.Int)
					val vElemCType  = if (vElemKtc is KtcType.Nullable) optCTypeName(vElemKtc.inner.toInternalStr)
						else cTypeStr(vElemKtc)
					val vVarArrType = varArrTypeName(vElemCType)
					if (arg.expr is NullLit) {
						parts += "($vVarArrType){NULL, 0}"
						} else {
						val vArgName = (arg.expr as? NameExpr)?.name
						if (vArgName != null && vArgName in trampolinedParams) {
							// @Size trampolined: local raw pointer + local const len
							parts += "($vVarArrType){($vElemCType*)local\$$vArgName, ${arrayParamSizeExpr(vArgName)}}"
							} else {
							val vSrcKtc   = inferExprTypeKtc(arg.expr)
							val vSrcCore  = (vSrcKtc as? KtcType.Nullable)?.inner ?: vSrcKtc
							val vSrcElem  = vSrcCore?.asArr?.elem
							val vSrcElemC = vSrcElem?.let {
								if (it is KtcType.Nullable) optCTypeName(it.inner.toInternalStr) else cTypeStr(it)
								}
							// Same-typed VarArr already in scope — pass directly without re-wrapping
							if (vSrcElemC == vElemCType && vSrcCore?.asArr?.sized == null) {
								parts += expr
								} else {
								// Different element type — rebuild VarArr with cast
								parts += "($vVarArrType){($vElemCType*)${arrayDataPtr(expr, vSrcKtc)}, ${arrayDataLen(expr, vSrcKtc)}}"
								}
							}
						}
					}
				} else if ((param.type.nullable || paramTypeKtc is KtcType.Nullable) && isValueNullableKtc(paramTypeKtc.let {
					it as? KtcType.Nullable
						?: KtcType.Nullable(it)
					})) {
				// Value-nullable param → pass as Optional struct
				val optBase = if (paramType.endsWith("?")) paramType.dropLast(1) else paramType
				val optType = optCTypeName("${optBase}?")
				if (arg.expr is NullLit) {
					parts += optNone(optType)
					} else {
					val argVarName = (arg.expr as? NameExpr)?.name
					val argVarKtc  = if (argVarName != null) lookupVarKtc(argVarName) else null
					val wrapped = if (argVarKtc is KtcType.Nullable && isValueNullableKtc(argVarKtc)
						&& (argVarName != null && isOptional(argVarName))
						) {
						// Already an Optional var — check if needs interface conversion
						val ifaceName2 = paramType.removeSuffix("?")
						if (interfaces.containsKey(ifaceName2) && classes.containsKey(argVarKtc.inner.toInternalStr)
							&& classInterfaces[argVarKtc.inner.toInternalStr]?.contains(ifaceName2) == true) {
							val baseFlat = typeFlatName(argVarKtc.inner.toInternalStr)
							val t        = tmp()
							val optType2 = optCTypeName("${paramType}?")
							preStmts += "$optType2 $t = ($expr.tag == ktc_SOME) ? ($optType2){ktc_SOME, ${baseFlat}_as_$ifaceName2(&$expr.value)} : ($optType2){ktc_NONE};"
							t
							} else expr
						} else {
						// Check if needs as_Iface conversion for class→interface
						val ifaceName  = paramType
						val argKtc     = inferExprTypeKtc(arg.expr)
						val argKtcCore = (argKtc as? KtcType.Nullable)?.inner ?: argKtc
						val baseArg    = (argKtcCore as? KtcType.User)?.baseName ?: argKtcCore?.toInternalStr
						val isIfImpl   = baseArg != null && interfaces.containsKey(ifaceName)
							&& (classes.containsKey(baseArg) || objects.containsKey(baseArg))
							&& classInterfaces[baseArg]?.contains(ifaceName) == true
						val valExpr = if (isIfImpl) {
							if (argKtcCore is KtcType.Ptr || objects.containsKey(baseArg)) "${typeFlatName(baseArg)}_as_$ifaceName($expr)"
							else "${typeFlatName(baseArg)}_as_$ifaceName(&$expr)"
							} else expr
						optSome(optType, valExpr)
						}
					parts += wrapped
					}
				} else if (interfaces.containsKey(paramType)) {
				val argKtc      = inferExprTypeKtc(arg.expr)
				val argKtcCore  = (argKtc as? KtcType.Nullable)?.inner ?: argKtc
				val baseArgType = argKtcCore?.let {
					if (it is KtcType.User) it.baseName else it.toInternalStr
					}
				val isClassImpl = baseArgType != null && classes.containsKey(baseArgType) && classInterfaces[baseArgType]?.contains(paramType) == true
				val isObjImpl   = baseArgType != null && objects.containsKey(baseArgType) && classInterfaces[baseArgType]?.contains(paramType) == true
				parts += if (isClassImpl || isObjImpl) {
					if (argKtcCore is KtcType.Ptr) {
						"${typeFlatName(baseArgType)}_as_$paramType($expr)"
						} else if (isObjImpl) {
						"${typeFlatName(baseArgType)}_as_$paramType(&$expr)"
						} else {
						"${typeFlatName(baseArgType)}_as_$paramType(&$expr)"
						}
					} else {
					expr
					}
				} else if (paramTypeKtc is KtcType.Any) {
				if (arg.expr is NullLit) {
					parts += "(ktc_Any){0}"
					} else {
					val argType    = inferExprType(arg.expr)?.removeSuffix("?") ?: "Int"
					val argKtc     = inferExprTypeKtc(arg.expr)
					val argKtcCore = (argKtc as? KtcType.Nullable)?.inner ?: argKtc
					// If already Any/Any?, pass directly (no re-wrap)
					if (argKtcCore is KtcType.Any) {
						parts += expr
						} else {
						val typeId = getTypeId(argType)
						val ct     = cTypeStr(argType)
						val tVal   = tmp()
						preStmts += "$ct $tVal = $expr;"
						parts += "(ktc_Any){{$typeId}, (void*)&$tVal}"
						}
					}
				} else {
				// Auto-cast any pointer to AnyPtr / Byte* (for freeMem, reallocMem, etc.)
				val argKtc     = inferExprTypeKtc(arg.expr)
				val argKtcCore = (argKtc as? KtcType.Nullable)?.inner ?: argKtc
				if (paramType == "void*" || (paramType == "Byte*" && argKtcCore is KtcType.Ptr)) {
					parts += "(void*)($expr)"
					} else {
					parts += expr
					}
				}
			argIdx++
			}
		}
	// Handle remaining args if more args than params (shouldn't happen normally)
	while (argIdx < args.size) {
		parts += genExpr(args[argIdx].expr)
		argIdx++
		}
	return parts.joinToString(", ")
	}
