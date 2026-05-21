package com.bitsycore.ktc.codegen.emit

import com.bitsycore.ktc.ast.FunDecl
import com.bitsycore.ktc.ast.PropDecl
import com.bitsycore.ktc.ast.TypeRef
import com.bitsycore.ktc.codegen.*

// ── Vtable struct + cast function emission ────────────────────────

/* Emit a vtable struct for a class implementing an interface.
Shared by emitInterfaceVtablesForClass and emitTransitiveInterfaceVtables. */
internal fun CCodeGen.emitVtable(
	cClass:    String,
	cIface:    String,
	ifaceName: String,
	className: String,
	props:     List<PropDecl>,
	methods:   List<FunDecl>
	) {
	val vIsObject = objects.containsKey(className)

	// For objects, emit thin wrapper functions matching vtable signatures.
	// Object methods have no $self param but vtables require void* $self first.
	if (vIsObject) {
		if (methods.none { it.name == "dispose" } && !hasDisposeOverride(className)) {
			impl.appendLine("static void ${cClass}_${ifaceName}_dispose_vt(void* \$self) { (void)\$self; }")
			impl.appendLine()
			}
		for (m in methods) {
			val vMReturnsNullable = m.returnType != null && m.returnType.nullable
			val vMRetKtc          = if (m.returnType != null) resolveTypeName(m.returnType) else null
			val vMRetResolved     = vMRetKtc?.toInternalStr ?: ""
			val vCRet             = if (vMReturnsNullable) optCTypeName(vMRetResolved) else if (m.returnType != null) cType(m.returnType) else "void"
			val vCastExtra        = m.params.joinToString("") { p ->
				val vKtcParam = resolveTypeName(p.type); val vPStr = vKtcParam.toInternalStr
				if (p.type.nullable) ", ${optCTypeName(vPStr)} ${p.name}" else ", ${cType(p.type)} ${p.name}"
				}
			val vExtraArgs = m.params.joinToString(", ") { it.name }
			val vVtName    = "${cClass}_${ifaceName}_${m.name}_vt"
			val vTargetFn  = if (m.name == "dispose" && !hasDisposeOverride(className)) null
				else "${cClass}_${m.name}"
			impl.appendLine("static $vCRet $vVtName(void* \$self$vCastExtra) {")
			impl.appendLine("    (void)\$self;")
			if (vTargetFn != null) {
				if (vCRet != "void") impl.appendLine("    return $vTargetFn($vExtraArgs);")
				else impl.appendLine("    $vTargetFn($vExtraArgs);")
				}
			impl.appendLine("}")
			impl.appendLine()
			}
		}

	impl.appendLine("const ${cIface}_vt ${cClass}_${ifaceName}_vt = {")
	for (p in props) {
		val vCt = if (p.type != null) cType(p.type) else "ktc_Int"
		impl.appendLine("    ($vCt (*)(void*)) ${cClass}_${p.name}_get,")
		}
	for (m in methods) {
		val vMReturnsNullable = m.returnType != null && m.returnType.nullable
		val vMRetKtc          = if (m.returnType != null) resolveTypeName(m.returnType) else null
		val vMRetResolved     = vMRetKtc?.toInternalStr ?: ""
		val vCRet             = if (vMReturnsNullable) optCTypeName(vMRetResolved) else if (m.returnType != null) cType(m.returnType) else "void"
		val vExtraCast        = m.params.joinToString("") { p ->
			val vKtcParam = resolveTypeName(p.type); val vPStr = vKtcParam.toInternalStr
			if (p.type.nullable) ", ${optCTypeName(vPStr)}" else ", ${cType(p.type)}"
			}
		val vFn = if (vIsObject) "${cClass}_${ifaceName}_${m.name}_vt"
			else if (m.name == "dispose" && !hasDisposeOverride(className)) "ktc_core_noop_dispose"
			else "${cClass}_${m.name}"
		impl.appendLine("    ($vCRet (*)(void*$vExtraCast)) $vFn,")
		}
	if (methods.none { it.name == "dispose" }) {
		val vFnDispose = if (vIsObject) "${cClass}_${ifaceName}_dispose_vt"
			else if (!hasDisposeOverride(className)) "ktc_core_noop_dispose"
			else "${cClass}_dispose"
		impl.appendLine("    (void (*)(void*)) $vFnDispose,")
		}
	impl.appendLine("};")
	impl.appendLine()
	}

/* Returns true if a class or object has an explicit dispose() override. */
internal fun CCodeGen.hasDisposeOverride(inClassName: String): Boolean {
	classes[inClassName]?.methods?.any { it.name == "dispose" }?.let { return it }
	objects[inClassName]?.methods?.any { it.name == "dispose" }?.let { return it }
	return false
	}

/* Generate the designated-initializer return expression for ClassName_as_IfaceName().
Format depends on how many implementors the interface has:
  0: (void*) shallow pointer (fallback, no union)
  1: .ClassName = *$self    (single struct field, no data wrapper)
  2+: .data.ClassName = *$self (tagged union) */
internal fun CCodeGen.ifaceAsInit(
	cIface:    String,
	cClass:    String,
	className: String,
	ifaceName: String
	): String {
	val vImpls    = interfaceImplementors[ifaceName]
	val vDataName = ifaceDataName(className)
	val vTypeIdField = ".__base.typeId = ${cClass}_TYPE_ID"
	return when {
		vImpls.isNullOrEmpty() -> "($cIface){(void*)\$self, &${cClass}_${ifaceName}_vt}"
		else -> "($cIface){$vTypeIdField, .data.$vDataName = *\$self, .vt = &${cClass}_${ifaceName}_vt}"
		}
	}

/* Emit vtables for a concrete class implementing the given super interfaces.
Works for both non-generic and monomorphized generic classes.
declsOnly: emit only header declarations (grouped with class struct).
implsOnly: emit only .c implementations (skip hdr lines). */
internal fun CCodeGen.emitInterfaceVtablesForClass(
	className:      String,
	superIfaceRefs: List<TypeRef>,
	declsOnly:      Boolean = false,
	implsOnly:      Boolean = false
	) {
	val vCClass    = typeFlatName(className)
	val vIsObject  = objects.containsKey(className)
	val vCSelfType = if (vIsObject) "${vCClass}_t" else vCClass
	val vCSelfPtr  = "$vCSelfType*"
	for (vIfaceRef in superIfaceRefs) {
		val vIfaceName  = resolveIfaceName(vIfaceRef)
		val vIface      = interfaces[vIfaceName] ?: continue
		val vCIface     = typeFlatName(vIfaceName)
		val vAllMethods = collectAllIfaceMethods(vIface)
		val vAllProps   = collectAllIfaceProperties(vIface)

		if (!implsOnly) hdr.appendLine()
		if (!implsOnly) hdr.appendLine("// ════ implements $vIfaceName ════")
		if (!declsOnly) {
			val vDisplayName = typeRefToStr(vIfaceRef)
			impl.appendLine(boxSection("implements $vDisplayName"))
			impl.appendLine()
			// Object interface method bodies buffered during emitObject
			val vMethodBuf = if (vIsObject) deferredObjIfaceMethods.remove(Pair(className, vIfaceName)) else null
			if (vMethodBuf != null) impl.append(vMethodBuf)
			impl.appendLine(boxSection("cast to $vDisplayName"))
			impl.appendLine()
			}

		// Emit property getter wrappers
		for (vP in vAllProps) {
			val vCt         = if (vP.type != null) cType(vP.type) else "ktc_Int"
			val vGetterName = "${vCClass}_${vP.name}_get"
			if (!implsOnly) hdr.appendLine("KTC_METHOD($vCt, ${vP.name}_get)($vCSelfPtr \$self);")
			if (!declsOnly) {
				if (vIsObject) {
					impl.appendLine("$vCt $vGetterName($vCSelfPtr \$self) { (void)\$self; return ${vCClass}.${vP.name}; }")
					} else {
					impl.appendLine("$vCt $vGetterName($vCSelfPtr \$self) { return \$self->${vP.name}; }")
					}
				impl.appendLine()
				}
			}

		// static vtable instance
		if (!implsOnly) hdr.appendLine("extern const ${vCIface}_vt KTC_RELATED(${vIfaceName}_vt);")
		if (!declsOnly) {
			emitVtable(vCClass, vCIface, vIfaceName, className, vAllProps, vAllMethods)
			}

		// as_IfaceName cast function
		if (!implsOnly) hdr.appendLine("KTC_METHOD($vCIface, as_${vIfaceName})($vCSelfPtr \$self);")
		if (!implsOnly) {
			val vLines = deferredHdrLines[className]
			if (vLines != null) {
				for ((_, vLine) in vLines) hdr.appendLine(vLine)
				deferredHdrLines.remove(className)
				}
			}
		if (!declsOnly) {
			impl.appendLine("$vCIface ${vCClass}_as_${vIfaceName}($vCSelfPtr \$self) {")
			if (vIsObject) impl.appendLine("    (void)\$self;")
			impl.appendLine("    return ${ifaceAsInit(vCIface, vCClass, className, vIfaceName)};")
			impl.appendLine("}")
			impl.appendLine()
			}

		if (!declsOnly) emitTransitiveInterfaceVtables(className, vCClass, vIface)
		}
	}

/* Emit vtables for parent interfaces in an inheritance chain.
E.g. if ArrayList_Int implements MutableList_Int which extends List_Int,
emits ArrayList_Int_as_List_Int with the List_Int subset of the vtable. */
internal fun CCodeGen.emitTransitiveInterfaceVtables(
	className: String,
	cClass:    String,
	iface:     IfaceInfo
	) {
	for (vSuperRef in iface.superInterfaces) {
		val vSuperName  = resolveIfaceName(vSuperRef)
		val vSuperIface = interfaces[vSuperName] ?: continue
		val vCSuper     = typeFlatName(vSuperName)
		val vSuperMethods = collectAllIfaceMethods(vSuperIface)
		val vSuperProps   = collectAllIfaceProperties(vSuperIface)

		val vSuperDisplay = typeRefToStr(vSuperRef)
		impl.appendLine(boxSection("cast to $vSuperDisplay"))
		impl.appendLine()

		// Register this class as also implementing the parent interface
		val vExisting = classInterfaces[className]?.toMutableList() ?: mutableListOf()
		if (vSuperName !in vExisting) {
			vExisting += vSuperName
			classInterfaces[className] = vExisting
			// Also update the reverse map for tagged-union emission
			interfaceImplementors.getOrPut(vSuperName) { mutableListOf() }.add(className)
			}

		// Emit vtable and as_SuperName cast
		emitVtable(cClass, vCSuper, vSuperName, className, vSuperProps, vSuperMethods)
		impl.appendLine("$vCSuper ${cClass}_as_${vSuperName}($cClass* \$self) {")
		impl.appendLine("    return ${ifaceAsInit(vCSuper, cClass, className, vSuperName)};")
		impl.appendLine("}")
		impl.appendLine()

		emitTransitiveInterfaceVtables(className, cClass, vSuperIface)
		}
	}

/* Recursively emit transitive parent interface header declarations inside the current KTC_TYPE_NAME block.
Must be called while #define KTC_TYPE_NAME is active so KTC_METHOD and KTC_RELATED expand correctly.
inByIface maps interface display strings to their deferred method lines. */
internal fun CCodeGen.emitTransitiveIfaceHdrDecls(
	inIface:    IfaceInfo,
	inByIface:  Map<String, List<Pair<String, String>>>
	) {
	for (vSuperRef in inIface.superInterfaces) {
		val vSuperName  = resolveIfaceName(vSuperRef)
		val vSuperIface = interfaces[vSuperName] ?: continue
		val vCSuperName = typeFlatName(vSuperName)
		val vSuperStr   = typeRefToStr(vSuperRef)
		hdr.appendLine()
		hdr.appendLine("// ════ implements $vSuperStr (transitive) ════")
		val vLines = inByIface[vSuperStr]
		if (vLines != null) for ((_, vLine) in vLines) hdr.appendLine(vLine)
		for (vProp in vSuperIface.propDecls) {
			val vCt = if (vProp.type != null) cType(vProp.type) else "ktc_Int"
			hdr.appendLine("KTC_METHOD($vCt, ${vProp.name}_get)(KTC_TYPE_NAME* \$self);")
			}
		hdr.appendLine("extern const ${vCSuperName}_vt KTC_RELATED(${vSuperName}_vt);")
		hdr.appendLine("KTC_METHOD($vCSuperName, as_${vSuperName})(KTC_TYPE_NAME* \$self);")
		emitTransitiveIfaceHdrDecls(vSuperIface, inByIface)
		}
	}
