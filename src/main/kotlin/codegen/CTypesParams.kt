package com.bitsycore.ktc.codegen

import com.bitsycore.ktc.ast.*
import com.bitsycore.ktc.types.KtcType

// C parameter expansion: function/constructor params → C declarations.
// Type resolution and C type strings are in CTypes.kt.

// KtcType overload — null-safety comment for pointer params
internal fun ptrNullComment(kt: KtcType): String = when (kt) {
	is KtcType.Nullable if kt.inner is KtcType.Ptr -> " /** nullable */"
	is KtcType.Ptr -> " /** notnull */"
	is KtcType.Arr -> " /** notnull */"
	else -> ""
	}

internal fun CCodeGen.cType(t: TypeRef): String = cTypeStr(resolveTypeName(t))

/* Expand ctor props: array → ktc_VarArr_T name, nullable → OptT name. */
internal fun CCodeGen.expandCtorParams(inProps: List<PropertyDef>): String {
	val vParts = mutableListOf<String>() // accumulated C parameter declarations
	for (vProp in inProps) {
		val vName = vProp.name                      // parameter name
		val vType = vProp.typeRef                   // parameter TypeRef
		val vKtc  = resolveTypeName(vType)          // resolved KtcType
		when {
			vKtc is KtcType.Func -> vParts += cFuncPtrDecl(vKtc, vName)
			vKtc.isArrayLike     -> {
				when {
					vType.hasSizeAnnotation() -> {
						// @Size(N) Array<T>: fixed-size struct (unchanged)
						vParts += "${cTypeStr(vKtc)} $vName"
						}
					vKtc is KtcType.Ptr -> {
						// @Ptr Array<T>: treat same as regular Array<T> — ktc_VarArr_T
						val vInnerArr  = vKtc.inner.asArr!!
						val vElemCType = if (vInnerArr.elem is KtcType.Nullable) optCTypeName(vInnerArr.elem.inner.toInternalStr)
							else cTypeStr(vInnerArr.elem)
						vParts += "${varArrTypeName(vElemCType)} $vName"
						}
					else -> {
						// Regular Array<T>: typed VarArr struct (no companion)
						val vArrElem   = vKtc.asArr!!.elem
						val vElemCType = if (vArrElem is KtcType.Nullable) optCTypeName(vArrElem.inner.toInternalStr)
							else cTypeStr(vArrElem)
						vParts += "${varArrTypeName(vElemCType)} $vName"
						}
					}
				}
			vType.nullable       -> vParts += "${optCTypeName(vKtc.toInternalStr)} $vName"
			else                 -> vParts += "${cTypeStr(vKtc)} $vName"
			}
		}
	return vParts.joinToString(", ")
	}

/* Expand a parameter list: variable array params → ktc_VarArr_T, @Size arrays → struct, nullable params → OptT name. */
internal fun CCodeGen.expandParams(inParams: List<Param>): String {
	val vParts = mutableListOf<String>() // accumulated C parameter declarations
	for (vP in inParams) {
		val vKtc = resolveTypeName(vP.type)
		when {
			vP.isVararg -> {
				// Vararg: pack all variadic elements into a single ktc_VarArr_T
				vParts += "${varArrTypeName(cTypeStr(vKtc))} ${vP.name}"
				}
			vKtc is KtcType.Func -> vParts += cFuncPtrDecl(vKtc, vP.name)
			vP.type.isSizedString() -> {
				val vSize = vP.type.getSizeAnnotation()!!  // must have @Size annotation
				vParts += "${sizedStringCTypeName(vSize)} ${vP.name}"
				}
			vKtc is KtcType.Ptr && vP.type.annotations.any { it.name == "Ptr" } -> {
				val vInnerArr = vKtc.inner.asArr
				if (vInnerArr != null && vInnerArr.sized == null) {
					// @Ptr Array<T>: treat same as regular ktc_VarArr_T (no raw pointer ABI)
					val vNullComment = if (vP.type.nullable) " /** nullable */" else ""
					val vElemCType   = if (vInnerArr.elem is KtcType.Nullable) optCTypeName(vInnerArr.elem.inner.toInternalStr)
						else cTypeStr(vInnerArr.elem)
					vParts += "${varArrTypeName(vElemCType)} ${vP.name}$vNullComment"
					} else {
					// @Ptr non-array or @Ptr @Size array: keep as raw pointer
					val vNullComment = if (vP.type.nullable) " /** nullable */" else " /** notnull */"
					vParts += "${cTypeStr(vKtc)} ${vP.name}$vNullComment"
					}
				}
			vKtc.isArrayLike -> {
				if (vP.type.hasSizeAnnotation()) {
					val vElemCType = cTypeStr(vKtc.asArr!!.elem)
					val vSize      = vP.type.getSizeAnnotation()!!
					vParts += "${sizedArrayCTypeName(vElemCType, vSize)} ${vP.name}"
					} else {
					val vNullComment = if (vP.type.nullable) " /** nullable */" else ""
					val vArrElem     = vKtc.asArr!!.elem
					val vElemCType   = if (vArrElem is KtcType.Nullable) optCTypeName(vArrElem.inner.toInternalStr)
						else cTypeStr(vArrElem)
					vParts += "${varArrTypeName(vElemCType)} ${vP.name}$vNullComment /** ${vElemCType}[] */"
					}
				}
			vP.type.nullable -> {
				val vNullComment = if (vKtc is KtcType.Any) " /** nullable */" else ""
				vParts += "${optCTypeName(vKtc.toInternalStr)} ${vP.name}$vNullComment"
				}
			else -> vParts += "${cTypeStr(vKtc)} ${vP.name}"
			}
		}
	return vParts.joinToString(", ")
	}

/* Emit alloca+memcpy copies for variable array params and record them as trampolined. */
internal fun CCodeGen.emitArrayParamCopies(inParams: List<Param>, inInd: String) {
	var vAny = false // whether any trampoline was emitted
	for (vP in inParams) {
		if (vP.isVararg) continue
		// @Size(N) String param: unpack ktc_String_N struct to ktc_String view for body access
		if (vP.type.isSizedString()) {
			if (!vAny) { impl.appendLine("${inInd}// ── sized param unpack start ──"); vAny = true }
			impl.appendLine("${inInd}ktc_String local\$${vP.name} = {${vP.name}.buf, ${vP.name}.len};")
			trampolinedParams += vP.name
			continue
			}
		// @Size(N) Array<T> param: unpack ktc_Array_T_N struct to T* pointer for body access
		if (vP.type.isSizedArray()) {
			if (!vAny) { impl.appendLine("${inInd}// ── sized param unpack start ──"); vAny = true }
			val vElemKtc   = resolveTypeName(vP.type).asArr!!.elem
			val vElemCType = cTypeStr(vElemKtc)
			val vSize      = vP.type.getSizeAnnotation()!!
			impl.appendLine("${inInd}$vElemCType* local\$${vP.name} = ${vP.name}.arr;")
			impl.appendLine("${inInd}const ktc_Int local\$${vP.name}\$len = $vSize;")
			trampolinedParams += vP.name
			sizedArrayTrampolinedParams += vP.name
			continue
			}
		// Regular Array<T> params are now ktc_VarArr_T — no trampolining needed; access via .ptr/.len
		}
	if (vAny) impl.appendLine("${inInd}// ── sized param unpack end ──")
	}
