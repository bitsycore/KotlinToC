package com.bitsycore.ktc.codegen.expr

import com.bitsycore.ktc.ast.*
import com.bitsycore.ktc.codegen.*
import com.bitsycore.ktc.types.KtcType

// ── Allocator-based construction + constructor dispatch ───────────
// genAllocWithCallOrNull: handles Foo.allocWith(alloc, ...) / Array.allocWith(alloc, n)
// genCtorCallOrNull:      handles Foo(...) constructor calls for known and generic classes

/*
Handles ClassName.allocWith(allocator, ...) calls.
Returns the allocated pointer expression, or null if className is not a recognized type.
Only called when callee is DotExpr named "allocWith" with a NameExpr object and at least one arg.
*/
internal fun CCodeGen.genAllocWithCallOrNull(inCall: CallExpr): String? {
	val vClassName  = ((inCall.callee as DotExpr).obj as NameExpr).name
	val vAllocExpr  = genExpr(inCall.args[0].expr)
	val vAllocObjName = (inCall.args[0].expr as? NameExpr)?.name

	/* Resolve allocator expression to a ktc_IfacePtr. */
	fun resolveAllocIface(inAllocArgKtc: KtcType?): Pair<String, Boolean> {
		val vAllocCore    = (inAllocArgKtc as? KtcType.Nullable)?.inner ?: inAllocArgKtc
		val vIsTrampoline = vAllocCore is KtcType.Ptr && vAllocCore.inner is KtcType.User
			&& vAllocCore.inner.kind == KtcType.UserKind.Interface
		if (vIsTrampoline) return Pair(vAllocExpr, false)
		if (vAllocObjName != null && objects.containsKey(vAllocObjName)) {
			val vConcrete = typeFlatName(vAllocObjName)
			val vTypeId   = getTypeId(vAllocObjName)
			val vT        = tmp()
			preStmts += "ktc_IfacePtr $vT = {{$vTypeId}, (const void*)&${vConcrete}_Allocator_vt, (void*)&$vAllocExpr};"
			return Pair(vT, true)
			}
		return Pair(vAllocExpr, false)
		}

	// Array.allocWith(allocator, size) or RawArray.allocWith(allocator, size)
	if (vClassName == "Array" || vClassName == "RawArray") {
		val vElemName = when {
			inCall.typeArgs.isNotEmpty() ->
				typeSubst[inCall.typeArgs[0].name] ?: inCall.typeArgs[0].name
			heapAllocTargetType != null && heapAllocTargetType!!.typeArgs.isNotEmpty() ->
				typeSubst[heapAllocTargetType!!.typeArgs[0].name] ?: heapAllocTargetType!!.typeArgs[0].name
			else -> "Int"
			}
		val vElemC    = cTypeStr(vElemName)
		val vSizeExpr = genExpr(inCall.args[1].expr)
		val vAllocKtc = inferExprTypeKtc(inCall.args[0].expr)
		val (vIfExpr, _) = resolveAllocIface(vAllocKtc)
		val vT = tmp()
		preStmts += "$vElemC* ${vT}_ptr = ($vElemC*)((ktc_std_Allocator_vt*)$vIfExpr.vt)->allocMem($vIfExpr.obj, sizeof($vElemC) * (size_t)($vSizeExpr), ${ktSrcStr()});"
		if (vClassName == "Array") {
				val vVarArrType = varArrTypeName(vElemC)
				preStmts += "$vVarArrType $vT = {${vT}_ptr, $vSizeExpr};"
				return vT
			}
		return "${vT}_ptr"
		}

	// Concrete class: Foo.allocWith(allocator, ctorArgs...)
	if (classes.containsKey(vClassName) && !classes[vClassName]!!.isGeneric) {
		val vCName    = typeFlatName(vClassName)
		val vCtorArgs = inCall.args.drop(1).joinToString(", ") { genExpr(it.expr) }
		val vAllocKtc = inferExprTypeKtc(inCall.args[0].expr)
		val vAllocCore = (vAllocKtc as? KtcType.Nullable)?.inner ?: vAllocKtc
		val vAllocClassName = (vAllocCore as? KtcType.User)?.baseName
		val vIsAllocObj = vAllocObjName != null && objects.containsKey(vAllocObjName)
			&& classInterfaces[vAllocObjName]?.contains("Allocator") == true
		val vIsAllocClass = vAllocClassName != null && classes.containsKey(vAllocClassName)
			&& classInterfaces[vAllocClassName]?.contains("Allocator") == true
		val vIsTrampoline = run {
			val vi = (vAllocCore as? KtcType.Ptr)?.inner
			vi is KtcType.User && vi.kind == KtcType.UserKind.Interface
			}
		val vT          = tmp()
		val vIfaceCreated: Boolean
		val vIfExpr: String
		when {
			vIsAllocObj -> {
				val vConcrete = typeFlatName(vAllocObjName); val vTypeId = getTypeId(vAllocObjName)
				preStmts += "ktc_IfacePtr $vT = {{$vTypeId}, (const void*)&${vConcrete}_Allocator_vt, (void*)&$vAllocExpr};"
				vIfaceCreated = true; vIfExpr = vT
				}
			vIsAllocClass -> {
				val vConcrete = typeFlatName(vAllocClassName); val vTypeId = getTypeId(vAllocClassName)
				preStmts += "ktc_IfacePtr $vT = {{$vTypeId}, (const void*)&${vConcrete}_Allocator_vt, (void*)&$vAllocExpr};"
				vIfaceCreated = true; vIfExpr = vT
				}
			else -> { vIfaceCreated = false; vIfExpr = vAllocExpr }
			}
		val vTPtr = tmp()
		if (vIfaceCreated || vIsTrampoline) {
			preStmts += "$vCName* ${vTPtr}_ptr = ($vCName*)((ktc_std_Allocator_vt*)$vIfExpr.vt)->allocMem($vIfExpr.obj, sizeof($vCName), ${ktSrcStr()});"
			} else {
			preStmts += "$vCName* ${vTPtr}_ptr = ($vCName*)$vIfExpr.vt->allocMem((void*)&$vIfExpr.data, sizeof($vCName), ${ktSrcStr()});"
			}
		preStmts += "if (${vTPtr}_ptr) *${vTPtr}_ptr = ${vCName}_primaryConstructor($vCtorArgs);"
		return "${vTPtr}_ptr"
		}

	// Generic class: Foo<T>.allocWith(allocator, ctorArgs...)
	if (genericClassDecls.containsKey(vClassName)) {
		val vTypeArgs = inCall.typeArgs.ifEmpty { heapAllocTargetType?.typeArgs ?: emptyList() }
		if (vTypeArgs.isNotEmpty()) {
			val vResolvedArgs = vTypeArgs.map { vTa ->
				val vSub = substituteTypeParams(vTa)
				if (vSub.nullable) "${resolveTypeNameStr(vSub)}?" else resolveTypeNameStr(vSub)
				}
			val vMangled = mangledGenericName(vClassName, vResolvedArgs)
			if (classes.containsKey(vMangled)) {
				val vCName    = typeFlatName(vMangled)
				val vAllocKtc = inferExprTypeKtc(inCall.args[0].expr)
				val vAllocCore = (vAllocKtc as? KtcType.Nullable)?.inner ?: vAllocKtc
				val vAllocClassName2 = (vAllocCore as? KtcType.User)?.baseName
				val vIsAllocObj2 = vAllocObjName != null && objects.containsKey(vAllocObjName)
					&& classInterfaces[vAllocObjName]?.contains("Allocator") == true
				val vIsAllocClass2 = vAllocClassName2 != null && classes.containsKey(vAllocClassName2)
					&& classInterfaces[vAllocClassName2]?.contains("Allocator") == true
				val vIsTrampoline2 = run {
					val vi = (vAllocCore as? KtcType.Ptr)?.inner
					vi is KtcType.User && vi.kind == KtcType.UserKind.Interface
					}
				val vT = tmp()
				val vIfaceCreated2: Boolean
				val vIfExpr2: String
				val vCtorArgs2 = inCall.args.drop(1).joinToString(", ") { vArg ->
					val vArgExpr  = genExpr(vArg.expr)
					val vArgVarName = (vArg.expr as? NameExpr)?.name
					if (vArgVarName != null && objects.containsKey(vArgVarName)) {
						val vConcrete = typeFlatName(vArgVarName); val vTypeId = getTypeId(vArgVarName)
						val vTCtor = tmp()
						preStmts += "ktc_IfacePtr $vTCtor = {{$vTypeId}, (const void*)&${vConcrete}_Allocator_vt, (void*)&$vArgExpr};"
						vTCtor
						} else vArgExpr
					}
				when {
					vIsAllocObj2 -> {
						val vConcrete = typeFlatName(vAllocObjName); val vTypeId = getTypeId(vAllocObjName)
						preStmts += "ktc_IfacePtr $vT = {{$vTypeId}, (const void*)&${vConcrete}_Allocator_vt, (void*)&$vAllocExpr};"
						vIfaceCreated2 = true; vIfExpr2 = vT
						}
					vIsAllocClass2 -> {
						val vConcrete = typeFlatName(vAllocClassName2); val vTypeId = getTypeId(vAllocClassName2)
						preStmts += "ktc_IfacePtr $vT = {{$vTypeId}, (const void*)&${vConcrete}_Allocator_vt, (void*)&$vAllocExpr};"
						vIfaceCreated2 = true; vIfExpr2 = vT
						}
					else -> { vIfaceCreated2 = false; vIfExpr2 = vAllocExpr }
					}
				val vTPtr2 = tmp()
				if (vIfaceCreated2 || vIsTrampoline2) {
					preStmts += "$vCName* ${vTPtr2}_ptr = ($vCName*)((ktc_std_Allocator_vt*)$vIfExpr2.vt)->allocMem($vIfExpr2.obj, sizeof($vCName), ${ktSrcStr()});"
					} else {
					preStmts += "$vCName* ${vTPtr2}_ptr = ($vCName*)$vIfExpr2.vt->allocMem((void*)&$vIfExpr2.data, sizeof($vCName), ${ktSrcStr()});"
					}
				preStmts += "if (${vTPtr2}_ptr) *${vTPtr2}_ptr = ${vCName}_primaryConstructor($vCtorArgs2);"
				return "${vTPtr2}_ptr"
				}
			}
		}

	return null
	}

/*
Dispatches Foo(args) constructor calls for known/generic classes.
Returns the constructor call expression, or null if inName is not a known class.
*/
internal fun CCodeGen.genCtorCallOrNull(
	inName:  String,
	inArgs:  List<Arg>,
	inCall:  CallExpr
	): String? {
	// Resolve nested class name within current object/class scope
	var vResolvedName = inName
	if (!classes.containsKey(inName)) {
		val vParent = currentObject ?: currentClass
		if (vParent != null) {
			val vNested = "$vParent$${inName}"
			if (classes.containsKey(vNested)) vResolvedName = vNested
			}
		}

	val vEffectiveTypeArgs = inCall.typeArgs.ifEmpty { heapAllocTargetType?.typeArgs ?: emptyList() }

	// Generic class constructor: explicit type args or LHS inference
	if (classes.containsKey(vResolvedName) && classes[vResolvedName]!!.isGeneric && vEffectiveTypeArgs.isNotEmpty()) {
		val vResolvedTypeArgs = vEffectiveTypeArgs.map { vTa ->
			val vSub = substituteTypeParams(vTa)
			if (vSub.nullable) "${resolveTypeNameStr(vSub)}?" else resolveTypeNameStr(vSub)
			}
		val vMangled = mangledGenericName(vResolvedName, vResolvedTypeArgs)
		val vCi      = classes[vMangled] ?: error("Generic class '$vMangled' not materialized (typeSubst=$typeSubst)")
		val vTplDecl = genericClassDecls[vResolvedName]
		val vAllParams    = vCi.ctorProps + vCi.ctorPlainParams
		val vCtorParams   = vAllParams.map { Param(it.name, it.typeRef) }
		val vFilledArgs   = fillDefaults(inArgs, vCtorParams, vAllParams.associate {
			val vCp = vTplDecl?.ctorParams?.find { vP -> vP.name == it.name }
			it.name to vCp?.default
			}, vResolvedName, strict = true)
		val vExpandedArgs = expandCallArgs(vFilledArgs, vCtorParams, isCtorCall = true)
		return "${vCi.flatName}_primaryConstructor($vExpandedArgs)"
		}

	// Generic class constructor: infer type args from argument types
	if (classes.containsKey(vResolvedName) && classes[vResolvedName]!!.isGeneric && inArgs.isNotEmpty()) {
		val vGenParams = classes[vResolvedName]!!.typeParams
		if (vGenParams.size == inArgs.size) {
			val vInferredArgs = inArgs.map { inferExprType(it.expr) ?: "Int" }
			val vMangled      = recordGenericInstantiation(vResolvedName, vInferredArgs)
			materializeGenericInstantiations()
			val vCi = classes[vMangled]
			if (vCi != null) {
				val vAllParams2  = vCi.ctorProps + vCi.ctorPlainParams
				val vCtorParams2 = vAllParams2.map { Param(it.name, it.typeRef) }
				val vFilledArgs2 = fillDefaults(inArgs, vCtorParams2, vAllParams2.associate { it.name to null }, vResolvedName, strict = true)
				val vExpandedArgs2 = expandCallArgs(vFilledArgs2, vCtorParams2, isCtorCall = true)
				return "${vCi.flatName}_primaryConstructor($vExpandedArgs2)"
				}
			}
		}

	// Known concrete class constructor
	if (classes.containsKey(vResolvedName)) {
		val vCi        = classes[vResolvedName]!!
		val vDeclClass = allClassDecls[vResolvedName]
		val vPrimaryParamCount = vCi.ctorProps.size + vCi.ctorPlainParams.size
		val vSctor = vDeclClass?.secondaryCtors?.find {
			it.params.size == inArgs.size && it.params.size != vPrimaryParamCount
			}
		if (vSctor != null) {
			val vTypes  = vSctor.params.map { resolveTypeName(it.type).toInternalStr.removeSuffix("*") }
			val vSuffix = if (vTypes.isEmpty()) "emptyConstructor"
				else "constructorWith${vTypes.joinToString("_")}"
			val vArgStr = inArgs.joinToString(", ") { genExpr(it.expr) }
			return "${vCi.flatName}_$vSuffix($vArgStr)"
			}
		val vAllParams3  = vCi.ctorProps + vCi.ctorPlainParams
		val vCtorParams3 = vAllParams3.map { Param(it.name, it.typeRef) }
		val vFilledArgs3 = fillDefaults(inArgs, vCtorParams3, vAllParams3.associate {
			val vCp = vDeclClass?.ctorParams?.find { p -> p.name == it.name }
			it.name to vCp?.default
			}, vResolvedName, strict = true)
		val vExpandedArgs3 = expandCallArgs(vFilledArgs3, vCtorParams3, isCtorCall = true)
		return "${vCi.flatName}_primaryConstructor($vExpandedArgs3)"
		}

	return null
	}
