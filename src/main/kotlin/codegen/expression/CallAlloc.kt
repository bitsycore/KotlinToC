package com.bitsycore.ktc.codegen.expression

import com.bitsycore.ktc.ast.*
import com.bitsycore.ktc.codegen.*
import com.bitsycore.ktc.types.KtcType

// ── Allocator-based construction + constructor dispatch ───────────
// genAllocWithCallOrNull: handles Foo(ctorArgs...).allocWith(alloc) / Array<T>(n).allocWith(alloc)
// genCtorCallOrNull:      handles Foo(...) constructor calls for known and generic classes

/* Emit `for (it=0; it<size; it++) dataPtr[it] = <lambda body>;` into preStmts.
Mirrors tryArrayOfInit's stack-array lambda-init loop, for the heap-allocated path. */
private fun CCodeGen.emitArrayInitLambda(dataPtr: String, sizeExpr: String, lambda: LambdaExpr) {
	val vItName = lambda.params.firstOrNull() ?: "it"
	preStmts += "for (ktc_Int $vItName = 0; $vItName < ($sizeExpr); $vItName++) {"
	pushScope()
	defineVar(vItName, "Int")
	for ((vIdx, vStmt) in lambda.body.withIndex()) {
		val vIsLast = vIdx == lambda.body.lastIndex
		when {
			vIsLast && vStmt is ExprStmt ->
				preStmts += "    $dataPtr[$vItName] = ${genExpr(vStmt.expr)};"
			vStmt is ExprStmt ->
				preStmts += "    (void)${genExpr(vStmt.expr)};"
			vStmt is VarDeclStmt -> {
				val vTypeKtc  = vStmt.type?.let { resolveTypeName(it) }
					?: parseResolvedTypeName(inferExprType(vStmt.init) ?: "Int")
				defineVarKtc(vStmt.name, vTypeKtc)
				val vCT       = cTypeStr(vTypeKtc)
				val vMut      = if (vStmt.mutable) "" else "const "
				val vInitExpr = vStmt.init?.let { genExpr(it) } ?: "0"
				preStmts += "    ${vMut}$vCT ${vStmt.name} = $vInitExpr;"
				}
			else -> codegenError("Unsupported statement in Array init lambda body (heap path)")
			}
		}
	popScope()
	preStmts += "}"
	}

/* Emit a preStmt that wraps [allocExpr] in a ktc_IfacePtr for the Allocator vtable of [name]. */
private fun CCodeGen.emitAllocatorIfacePtr(name: String, t: String, allocExpr: String) {
	val vConcrete = typeFlatName(name)
	val vTypeId   = getTypeId(name)
	preStmts += "ktc_IfacePtr $t = {$vTypeId, (const void*)&${vConcrete}_Allocator_vt, (void*)&$allocExpr};"
	}

/* Resolution of an allocator argument for class-construction allocWith.
ifaceExpr is the ktc_IfacePtr expression to call through;
ifaceCreated is true when ifaceExpr is a fresh local emitted via emitAllocatorIfacePtr;
isTrampoline is true when the allocator arg is `@Ptr Allocator` (already a fat pointer). */
private data class AllocResolution(val ifaceExpr: String, val ifaceCreated: Boolean, val isTrampoline: Boolean)

/* Resolve the allocator argument of a Foo(...).allocWith(alloc) call.
Emits the wrapping IfacePtr preStmt when needed and returns how the call site should
invoke the allocator (direct, wrapped, or trampoline). */
private fun CCodeGen.resolveAllocatorForClassAlloc(inCall: CallExpr, inAllocExpr: String, inAllocObjName: String?): AllocResolution {
	val vAllocKtc       = inferExprTypeKtc(inCall.args[0].expr)
	val vAllocCore      = vAllocKtc.stripNullable
	val vAllocClassName = (vAllocCore as? KtcType.User)?.baseName
	val vIsAllocObj     = inAllocObjName != null && objects.containsKey(inAllocObjName)
		&& classInterfaces[inAllocObjName]?.contains("Allocator") == true
	val vIsAllocClass   = vAllocClassName != null && classes.containsKey(vAllocClassName)
		&& classInterfaces[vAllocClassName]?.contains("Allocator") == true
	val vIsTrampoline   = run {
		val vi = (vAllocCore as? KtcType.Ptr)?.inner
		vi is KtcType.User && vi.kind == KtcType.UserKind.Interface
		}
	return when {
		vIsAllocObj   -> { val vT = tmp(); emitAllocatorIfacePtr(inAllocObjName!!,   vT, inAllocExpr); AllocResolution(vT, true,  vIsTrampoline) }
		vIsAllocClass -> { val vT = tmp(); emitAllocatorIfacePtr(vAllocClassName!!,  vT, inAllocExpr); AllocResolution(vT, true,  vIsTrampoline) }
		else          -> AllocResolution(inAllocExpr, false, vIsTrampoline)
		}
	}

/* Emit alloc+ctor preStmts for an allocator-based object construction and return the pointer expr. */
private fun CCodeGen.emitAllocWithConstruct(cName: String, ifExpr: String, ifaceCreated: Boolean, isTrampoline: Boolean, ctorArgs: String): String {
	val vTPtr = tmp()
	if (ifaceCreated || isTrampoline) {
		preStmts += "$cName* ${vTPtr}_ptr = ($cName*)((ktc_Allocator_vt*)$ifExpr.vt)->allocMem($ifExpr.obj, sizeof($cName), ${ktSrcStr()});"
		} else if (ifaceUsesPointerLayout("Allocator")) {
		preStmts += "$cName* ${vTPtr}_ptr = ($cName*)$ifExpr.vt->allocMem($ifExpr.obj, sizeof($cName), ${ktSrcStr()});"
		} else {
		preStmts += "$cName* ${vTPtr}_ptr = ($cName*)$ifExpr.vt->allocMem((void*)&$ifExpr.data, sizeof($cName), ${ktSrcStr()});"
		}
	preStmts += "if (${vTPtr}_ptr) *${vTPtr}_ptr = ${cName}_primaryConstructor($ctorArgs);"
	return "${vTPtr}_ptr"
	}

/*
Handles Class(ctorArgs...).allocWith(allocator) calls.
The receiver is a constructor call (the construction is fused with the allocation, so the
object is built directly in the allocated storage). Returns the allocated pointer expression,
or null if the receiver is not a recognized type.
Only called when callee is DotExpr named "allocWith" with a CallExpr object and at least one arg.
*/
internal fun CCodeGen.genAllocWithCallOrNull(inCall: CallExpr): String? {
	val vRecvCall   = (inCall.callee as DotExpr).obj as? CallExpr ?: return null
	val vClassName  = (vRecvCall.callee as? NameExpr)?.name ?: return null
	val vTypeArgs   = vRecvCall.typeArgs
	val vCtorArgs   = vRecvCall.args
	val vAllocExpr  = genExpr(inCall.args[0].expr)
	val vAllocObjName = (inCall.args[0].expr as? NameExpr)?.name

	/* Resolve allocator expression to a ktc_IfacePtr. */
	fun resolveAllocIface(inAllocArgKtc: KtcType?): Pair<String, Boolean> {
		val vAllocCore    = inAllocArgKtc.stripNullable
		val vIsTrampoline = vAllocCore is KtcType.Ptr && vAllocCore.inner is KtcType.User
			&& vAllocCore.inner.kind == KtcType.UserKind.Interface
		if (vIsTrampoline) return Pair(vAllocExpr, false)
		if (vAllocObjName != null && objects.containsKey(vAllocObjName)) {
			val vT = tmp()
			emitAllocatorIfacePtr(vAllocObjName, vT, vAllocExpr)
			return Pair(vT, true)
			}
		return Pair(vAllocExpr, false)
		}

	// Array<T>(size).allocWith(allocator), Array<T>(size) { init }.allocWith(allocator),
	// or RawArray<T>(size).allocWith(allocator)
	if (vClassName == "Array" || vClassName == "RawArray") {
		val vElemName = when {
			vTypeArgs.isNotEmpty() ->
				typeSubst[vTypeArgs[0].name] ?: vTypeArgs[0].name
			allocTargetType != null && allocTargetType!!.typeArgs.isNotEmpty() ->
				typeSubst[allocTargetType!!.typeArgs[0].name] ?: allocTargetType!!.typeArgs[0].name
			else -> "Int"
			}
		val vElemC    = cTypeStr(vElemName)
		val vSizeExpr = genExpr(vCtorArgs[0].expr)
		val vAllocKtc = inferExprTypeKtc(inCall.args[0].expr)
		val (vIfExpr, _) = resolveAllocIface(vAllocKtc)
		val vT = tmp()
		preStmts += "$vElemC* ${vT}_ptr = ($vElemC*)((ktc_Allocator_vt*)$vIfExpr.vt)->allocMem($vIfExpr.obj, sizeof($vElemC) * (size_t)($vSizeExpr), ${ktSrcStr()});"
		// Array<T>(size) { init } — run the init lambda over the freshly allocated slots.
		// (RawArray has no lambda-init form.)
		if (vClassName == "Array" && vCtorArgs.size >= 2 && vCtorArgs[1].expr is LambdaExpr) {
			emitArrayInitLambda("${vT}_ptr", vSizeExpr, vCtorArgs[1].expr as LambdaExpr)
			}
		if (vClassName == "Array") {
				val vVarArrType = varArrTypeName(vElemC)
				preStmts += "$vVarArrType $vT = {${vT}_ptr, $vSizeExpr};"
				return vT
			}
		return "${vT}_ptr"
		}

	// Concrete class: Foo(ctorArgs...).allocWith(allocator)
	if (classes.containsKey(vClassName) && !classes[vClassName]!!.isGeneric) {
		val vCName       = typeFlatName(vClassName)
		val vCtorArgsStr = vCtorArgs.joinToString(", ") { genExpr(it.expr) }
		val vAlloc       = resolveAllocatorForClassAlloc(inCall, vAllocExpr, vAllocObjName)
		return emitAllocWithConstruct(vCName, vAlloc.ifaceExpr, vAlloc.ifaceCreated, vAlloc.isTrampoline, vCtorArgsStr)
		}

	// Generic class: Foo<T>(ctorArgs...).allocWith(allocator)
	if (genericClassDecls.containsKey(vClassName)) {
		val vEffectiveTypeArgs = vTypeArgs.ifEmpty { allocTargetType?.typeArgs ?: emptyList() }
		if (vEffectiveTypeArgs.isNotEmpty()) {
			val vResolvedArgs = vEffectiveTypeArgs.map { vTa ->
				val vSub = substituteTypeParams(vTa)
				if (vSub.nullable) "${resolveTypeNameStr(vSub)}?" else resolveTypeNameStr(vSub)
				}
			val vMangled = mangledGenericName(vClassName, vResolvedArgs)
			if (classes.containsKey(vMangled)) {
				val vCName = typeFlatName(vMangled)
				// Generic-ctor args may themselves be Allocator-implementing objects (e.g. ArrayList<T>(Heap, n)
				// where `Heap` is an object); wrap such object-args as IfacePtrs so they match the iface param type.
				val vCtorArgsStr = vCtorArgs.joinToString(", ") { vArg ->
					val vArgExpr    = genExpr(vArg.expr)
					val vArgVarName = (vArg.expr as? NameExpr)?.name
					if (vArgVarName != null && objects.containsKey(vArgVarName)) {
						val vTCtor = tmp()
						emitAllocatorIfacePtr(vArgVarName, vTCtor, vArgExpr)
						vTCtor
						} else vArgExpr
					}
				val vAlloc = resolveAllocatorForClassAlloc(inCall, vAllocExpr, vAllocObjName)
				return emitAllocWithConstruct(vCName, vAlloc.ifaceExpr, vAlloc.ifaceCreated, vAlloc.isTrampoline, vCtorArgsStr)
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
			// Also try parent object of a nested class (e.g. SDL3 for SDL3$Window)
			if (vResolvedName == inName && currentClass != null && '$' in currentClass!!) {
				val vObjParent = currentClass!!.substringBefore('$')
				val vNested = "$vObjParent$${inName}"
				if (classes.containsKey(vNested)) vResolvedName = vNested
				}
			// Fallback: scan all objects for a nested class with this name
			if (vResolvedName == inName) {
				for (vObj in objects.keys) {
					val vNested = "$vObj$${inName}"
					if (classes.containsKey(vNested)) { vResolvedName = vNested; break }
					}
				}
		}

	val vEffectiveTypeArgs = inCall.typeArgs.ifEmpty { allocTargetType?.typeArgs ?: emptyList() }

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
			// Match secondary ctor by arg-count range (required..total → fills defaults)
			val vSctor = vDeclClass?.secondaryCtors?.find { vSc ->
				val vMin = vSc.params.count { it.default == null }
				if (inArgs.size !in vMin..vSc.params.size) return@find false
				if (vSc.params.size != vPrimaryParamCount) return@find true
				// Same count: disambiguate by first-arg type vs secondary param
				val vFirstArg = inArgs.firstOrNull() ?: return@find false
				val vArgKtc = inferExprTypeKtc(vFirstArg.expr)
				val vScParamKtc = resolveTypeName(vSc.params[0].type)
				vArgKtc?.toInternalStr == vScParamKtc.toInternalStr
				}
			if (vSctor != null) {
				val vTypes  = vSctor.params.map { resolveTypeName(it.type).toInternalStr.removeSuffix("*") }
				val vSuffix = if (vTypes.isEmpty()) "emptyConstructor"
					else "constructorWith${vTypes.joinToString("_")}"
				val vFilledSctorArgs = fillDefaults(inArgs, vSctor.params, vSctor.params.associate { it.name to it.default }, vResolvedName, strict = true)
				val vArgStr = vFilledSctorArgs.joinToString(", ") { genExpr(it.expr) }
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
