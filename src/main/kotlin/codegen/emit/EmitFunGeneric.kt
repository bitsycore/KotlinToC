package com.bitsycore.ktc.codegen.emit

import com.bitsycore.ktc.ast.*
import com.bitsycore.ktc.codegen.*
import com.bitsycore.ktc.codegen.expr.emitStmt
import com.bitsycore.ktc.types.KtcType

// Generic function monomorphization and star-projection extension function emission.

/*
Emit monomorphized versions of a generic free function.
For `fun <T> sizeOfList(list: MutableList<T>)` called with MutableList<Int>,
emits `sizeOfList_Int(MutableList_Int* list)`.
*/
internal fun CCodeGen.emitGenericFunInstantiations(f: FunDecl) {
	if (f.isInline || f.isInfix) return  // inline/infix: expanded at call sites only
	val instantiations  = genericFunInstantiations[f.name] ?: return
	val prevSourceFile  = currentSourceFile
	declSourceFile[f.name]?.let { currentSourceFile = it }
	for (typeArgs in instantiations) {
		val subst = f.typeParams.zip(typeArgs).toMap()
		withTypeSubst(subst) {
			val mangledName = "${f.name}_${typeArgs.joinToString("_")}"

			impl.appendLine("// ══ generic ${f.name}<${typeArgs.joinToString(", ")}> ($currentSourceFile) ══")
			val hasReceiver = f.receiver != null
			val concreteRet = genericFunConcreteReturn[mangledName]
			val cName = if (hasReceiver) {
				val recvKtc  = resolveTypeName(f.receiver!!)
				val recvName = (recvKtc as? KtcType.Ptr)?.inner?.let { (it as? KtcType.User)?.baseName }
					?: recvKtc.toInternalStr.removeSuffix("*").removeSuffix("?")
				if (f.receiver.annotations.any { it.name == "Ptr" }) {
					val baseFlat = typeFlatName(recvName)
					"${baseFlat.removeSuffix("_$recvName")}_Ptr$${recvName}_${f.name}"
					} else "${typeFlatName(recvName)}_${f.name}"
				} else funCName(mangledName)
			val baseParams = expandParams(f.params)
			val selfParam  = if (hasReceiver) {
				val selfRecvKtc = resolveTypeName(f.receiver!!)
				val ct = if (f.receiver.nullable && selfRecvKtc !is KtcType.Ptr && selfRecvKtc !is KtcType.Nullable)
					optCTypeName(selfRecvKtc.toInternalStr) else cType(f.receiver)
				"$ct \$self"
				} else null
			val params = if (selfParam != null && baseParams.isNotEmpty()) "$selfParam, $baseParams" else selfParam ?: baseParams

			val prevState = saveFunState()
			var cRet = computeReturnInfo(f)
			if (concreteRet != null) {
				cRet = typeFlatName(concreteRet)
				currentFnReturnType = concreteRet
				}

			maybeEmitFunBanner(f.name)
			hdr.appendLine("$cRet $cName($params);")
			impl.appendLine("$cRet $cName($params) {")

			pushScope()
			if (hasReceiver) {
				val recvResolved = resolveTypeName(f.receiver!!)
				val recvFull     = recvResolved.toInternalStr
				val recvName     = recvFull.removeSuffix("?")
				val isClassType  = classes.containsKey(recvName)
				currentExtRecvType = if (f.receiver.nullable) "${recvName}?" else recvName
				defineVar("\$self", if (f.receiver.nullable) "${recvName}?" else recvName)
				if (f.receiver.nullable && isValueNullableKtc(recvResolved as? KtcType.Nullable ?: KtcType.Nullable(recvResolved))) markOptional("\$self")
				if (isClassType) { currentClass = recvName; selfIsPointer = f.receiver.annotations.any { it.name == "Ptr" } }
				else             { currentClass = null;     selfIsPointer = false }
				}
			registerParams(f.params)
			emitArrayParamCopies(f.params, "    ")
			emitFunBodyAndClose(f, prevState, insideMethod = false, withImplicitReturn = false)
			}
		}
	currentSourceFile = prevSourceFile
	}

/* Register params for a star-projection extension function.
Class types are registered as pointer types ("Type*") since they are stack-value receivers. */
private fun CCodeGen.registerStarExtParams(params: List<Param>) {
	for (p in params) {
		val ktc = resolveTypeName(p.type)
		val str = ktc.toInternalStr
		defineVar(p.name, when {
			p.type.nullable          -> "${str}?"
			classes.containsKey(str) -> "${str}*"
			else                     -> str
			})
		}
	}

/*
Emit star-projection extension functions — one per known generic instantiation.
For `fun MutableList<*>.sizeOf()`, if MutableList<Int> is known, emits
`MutableList_Int_sizeOf(MutableList_Int* $self)`.
*/
internal fun CCodeGen.emitStarExtFunInstantiations(f: FunDecl) {
	val recvBaseName   = f.receiver!!.name
	val instantiations = genericInstantiations[recvBaseName]
	if (instantiations == null && genericIfaceDecls.containsKey(recvBaseName)) {
		emitStarExtFunForGenericInterface(f, recvBaseName)
		return
		}
	if (instantiations == null) return
	val emitted = mutableSetOf<String>()
	for (typeArgs in instantiations) {
		val mangledRecvName  = mangledGenericName(recvBaseName, typeArgs)
		val key = "${mangledRecvName}_${f.name}"
		if (!emitted.add(key)) continue
		val concreteReceiver = TypeRef(mangledRecvName, f.receiver.nullable)
		val templateCi       = classes[recvBaseName] ?: continue
		val subst = templateCi.typeParams.zip(typeArgs).toMap()
		withTypeSubst(subst) {
			val recvIsNullable = concreteReceiver.nullable
			val cRet        = if (f.returnType != null) cType(f.returnType) else "void"
			val isClassType = classes.containsKey(mangledRecvName)
			val cRecvType   = typeFlatName(mangledRecvName)
			val selfParam   = if (isClassType) "$cRecvType* \$self" else "$cRecvType \$self"
			val nullableExtra = if (recvIsNullable) ", ktc_Bool \$self\$has" else ""
			val extraParams   = expandParams(f.params)
			val allParams     = if (extraParams.isEmpty()) "$selfParam$nullableExtra" else "$selfParam$nullableExtra, $extraParams"
			val cFnName       = "${typeFlatName(mangledRecvName)}_${f.name}"

			hdr.appendLine("$cRet $cFnName($allParams);")
			impl.appendLine("$cRet $cFnName($allParams) {")

			currentExtRecvType = if (recvIsNullable) "$mangledRecvName?" else mangledRecvName
			if (isClassType) { currentClass = mangledRecvName; selfIsPointer = true }
			else             { currentClass = null;            selfIsPointer = false }

			val prevState = saveFunState()
			pushScope()
			registerStarExtParams(f.params)
			if (isClassType) registerClassFields(classes[mangledRecvName]!!, "\$self->")
			emitArrayParamCopies(f.params, "    ")
			emitFunBodyAndClose(f, prevState, insideMethod = isClassType, withImplicitReturn = false)

			extensionFuns.getOrPut(mangledRecvName) { mutableListOf() }
				.add(FunDecl(f.name, f.params, f.returnType, f.body, concreteReceiver))
			classes[mangledRecvName]?.methods?.add(
				FunDecl(f.name, f.params, f.returnType, f.body, concreteReceiver))
			}
		}
	}

/*
Expand a star-projection extension on a generic interface into concrete implementations.
For ArrayList_Int (implements List_Int) → ArrayList_Int_sizeOf, etc.
*/
internal fun CCodeGen.emitStarExtFunForGenericInterface(f: FunDecl, ifaceBaseName: String) {
	val emitted = mutableSetOf<String>()
	for ((className, ifaceList) in classInterfaces) {
		val matchingIface = ifaceList.find { it.startsWith("${ifaceBaseName}_") || it.startsWith("${ifaceBaseName}\$") }
			?: continue
		val ci  = classes[className] ?: continue
		val key = "${className}_${f.name}"
		if (!emitted.add(key)) continue

		val prevSubst = typeSubst
		typeSubst = genericTypeBindings[className] ?: emptyMap()

		val cRet        = if (f.returnType != null) cType(f.returnType) else "void"
		val cRecvType   = typeFlatName(className)
		val selfParam   = "$cRecvType* \$self"
		val extraParams = expandParams(f.params)
		val allParams   = if (extraParams.isEmpty()) selfParam else "$selfParam, $extraParams"
		val cFnName     = "${cRecvType}_${f.name}"

		hdr.appendLine("$cRet $cFnName($allParams);")
		impl.appendLine("$cRet $cFnName($allParams) {")

		currentExtRecvType = className
		currentClass       = className
		selfIsPointer      = true

		val prevState = saveFunState()
		pushScope()
		registerStarExtParams(f.params)
		registerClassFields(ci, "\$self->")
		emitArrayParamCopies(f.params, "    ")
		emitFunBodyAndClose(f, prevState, insideMethod = true, withImplicitReturn = false)

		val concreteReceiver = TypeRef(className, f.receiver!!.nullable)
		extensionFuns.getOrPut(className) { mutableListOf() }
			.add(FunDecl(f.name, f.params, f.returnType, f.body, concreteReceiver))
		ci.methods.add(FunDecl(f.name, f.params, f.returnType, f.body, concreteReceiver))
		typeSubst = prevSubst
		}
	}
