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

/* Expand ctor props: array → (T* name, ktc_Int name$len), nullable → OptT name. */
internal fun CCodeGen.expandCtorParams(inProps: List<PropertyDef>): String {
	val vParts = mutableListOf<String>() // accumulated C parameter declarations
	for (vProp in inProps) {
		val vName = vProp.name                      // parameter name
		val vType = vProp.typeRef                   // parameter TypeRef
		val vKtc  = resolveTypeName(vType)          // resolved KtcType
		when {
			vKtc is KtcType.Func -> vParts += cFuncPtrDecl(vKtc, vName)
			vKtc.isArrayLike     -> {
				vParts += "${cTypeStr(vKtc)} $vName"
				if (!vType.hasSizeAnnotation()) vParts += "ktc_Int ${vName}\$len"
				}
			vType.nullable       -> vParts += "${optCTypeName(vKtc.toInternalStr)} $vName"
			else                 -> vParts += "${cTypeStr(vKtc)} $vName"
			}
		}
	return vParts.joinToString(", ")
	}

/* Expand a parameter list: variable array params → ktc_ArrayTrampoline, @Size arrays → struct, nullable params → OptT name. */
internal fun CCodeGen.expandParams(inParams: List<Param>): String {
	val vParts = mutableListOf<String>() // accumulated C parameter declarations
	for (vP in inParams) {
		val vKtc = resolveTypeName(vP.type)
		when {
			vP.isVararg -> {
				vParts += "${cTypeStr(vKtc)}* ${vP.name}"
				vParts += "ktc_Int ${vP.name}\$len"
				}
			vKtc is KtcType.Func -> vParts += cFuncPtrDecl(vKtc, vP.name)
			vP.type.isSizedString() -> {
				val vSize = vP.type.getSizeAnnotation()!!  // must have @Size annotation
				vParts += "${sizedStringCTypeName(vSize)} ${vP.name}"
				}
			vKtc is KtcType.Ptr && vP.type.annotations.any { it.name == "Ptr" } -> {
				val vNullComment = if (vP.type.nullable) " /** nullable */" else " /** notnull */"
				vParts += "${cTypeStr(vKtc)} ${vP.name}$vNullComment"
				val vInnerArr = vKtc.inner.asArr
				if (vInnerArr != null && vInnerArr.sized == null) vParts += "ktc_Int ${vP.name}\$len"
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
					vParts += "ktc_ArrayTrampoline ${vP.name}$vNullComment /** ${vElemCType}[] */"
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
		if (!vP.type.isRawArray()) continue
		if (!vAny) {
			impl.appendLine("${inInd}// ── sized param unpack start ──")
			vAny = true
			}
		val vElem      = resolveTypeName(vP.type).asArr!!.elem
		val vElemCType = if (vElem is KtcType.Nullable) optCTypeName(vElem.inner.toInternalStr)
			else cTypeStr(vElem)
		if (vP.type.nullable) {
			impl.appendLine("${inInd}$vElemCType* local$${vP.name} = NULL;")
			impl.appendLine("${inInd}if (${vP.name}.data != NULL) {")
			impl.appendLine("$inInd    local$${vP.name} = ($vElemCType*)ktc_core_alloca(sizeof($vElemCType) * ${vP.name}.size);")
			impl.appendLine("$inInd    memcpy(local$${vP.name}, ${vP.name}.data, sizeof($vElemCType) * ${vP.name}.size);")
			impl.appendLine("${inInd}}")
			} else {
			impl.appendLine("${inInd}$vElemCType* local$${vP.name} = ($vElemCType*)ktc_core_alloca(sizeof($vElemCType) * ${vP.name}.size);")
			impl.appendLine("${inInd}memcpy(local$${vP.name}, ${vP.name}.data, sizeof($vElemCType) * ${vP.name}.size);")
			}
		trampolinedParams += vP.name
		}
	if (vAny) impl.appendLine("${inInd}// ── sized param unpack end ──")
	}
