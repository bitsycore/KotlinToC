package com.bitsycore.ktc.codegen.emit

import com.bitsycore.ktc.ast.FunDecl
import com.bitsycore.ktc.ast.PropDecl
import com.bitsycore.ktc.ast.TypeRef
import com.bitsycore.ktc.codegen.*

// ──────────────────────────────────────────────────────────
// interface — vtable struct, fat-pointer, implementor casts
// ──────────────────────────────────────────────────────────

// ── interface ────────────────────────────────────────────────────

/**
 * Emit a vtable struct + fat-pointer typedef for an interface.
 *
 * interface Drawable { fun draw(); fun area(): Float }
 * →
 * typedef struct {
 *     void (*draw)(void* $self);
 *     float (*area)(void* $self);
 * } game_Drawable_vt;
 *
 * typedef struct {
 *     void* obj;
 *     const game_Drawable_vt* vt;
 * } game_Drawable;
 */
/* Emit a complete interface block: banner, TYPE_ID, CLS_TYPES X-macro, KTC_INTERFACE invocation.
Must be called after all class struct definitions and vtable implementations are emitted. */
internal fun CCodeGen.emitInterfaceBlock(info: IfaceInfo) {
    val cName = info.flatName  // C name, e.g. ktc_std_MutableList_Int

    val vComponents = mangledComponents[info.name]
    val (vBaseName, vDisplayArgs) = when {
        vComponents != null -> vComponents.first to vComponents.second
        else -> info.name to emptyList()
    }
    val vPkg = info.pkg.trimEnd('_').replace('_', '.')  // convert C prefix back to Kotlin pkg
    val impls = interfaceImplementors[info.name] ?: emptyList()
    fun implCType(name: String) = if (objects.containsKey(name)) "${typeFlatName(name)}_t" else typeFlatName(name)
    val vOptName = if (vComponents != null) {
        val (vGenBase, vTypeArgs) = vComponents
        genericOptionalCName(vGenBase, vTypeArgs)
    } else {
        "${cName}\$Opt"
    }

    hdr.appendLine(classBlockHeader("interface", vBaseName, vDisplayArgs,
        info.superInterfaces, vPkg, currentSourceFile, cName))

    // KTC_TYPE_NAME / KTC_OPT_TYPE_NAME / TYPE_ID defines
    hdr.appendLine("#define KTC_TYPE_NAME $cName")
    hdr.appendLine("#define KTC_OPT_TYPE_NAME $vOptName")
    hdr.appendLine("KTC_TYPE_ID(${typeIds[info.name]!!})")

    // CLS_TYPES X-macro listing every concrete implementor (used by KTC_INTERFACE union).
    // Each entry is X(TYPE, NAME) where TYPE is the C struct type and NAME is the field base
    // (these differ for object singletons: TYPE = Foo_t, NAME = Foo).
    if (impls.isNotEmpty()) {
        hdr.appendLine()
        hdr.appendLine("#define CLS_TYPES(X) \\")
        for ((i, impl) in impls.withIndex()) {
            val vType = implCType(impl)           // e.g. ktc_std_Heap_t or ktc_std_Circle
            val vName = typeFlatName(impl)        // always without _t, used for field name
            if (i < impls.size - 1) hdr.appendLine("    X($vType, $vName) \\")
            else hdr.appendLine("    X($vType, $vName)")
        }
    }

    hdr.appendLine()

    val allMethods = collectAllIfaceMethods(info)
    val allProps = collectAllIfaceProperties(info)
    val vtableHasDispose = allMethods.any { it.name == "dispose" }

    if (impls.isEmpty()) {
        // No-implementor fallback: raw structs (KTC_INTERFACE requires at least one union member)
        hdr.appendLine("typedef struct ${cName}_vt {")
        emitIfaceVtableBody(allProps, allMethods, vtableHasDispose)
        hdr.appendLine("} ${cName}_vt;")
        hdr.appendLine()
        hdr.appendLine("typedef struct $cName {")
        hdr.appendLine("    void* obj;")
        hdr.appendLine("    const ${cName}_vt* vt;")
        hdr.appendLine("} $cName;")
        hdr.appendLine("typedef struct { ktc_OptionalTag tag; $cName value; } $vOptName;")
    } else {
        // Use KTC_INTERFACE macro — always emits a union (works for 1 or more implementors)
        hdr.appendLine("KTC_INTERFACE({")
        emitIfaceVtableBody(allProps, allMethods, vtableHasDispose)
        hdr.appendLine("}, CLS_TYPES);")
    }

    hdr.appendLine()
    hdr.appendLine("#undef KTC_TYPE_NAME")
    hdr.appendLine("#undef KTC_OPT_TYPE_NAME")
    if (impls.isNotEmpty()) hdr.appendLine("#undef CLS_TYPES")

    hdr.appendLine(classBlockFooter("interface", vBaseName, vDisplayArgs))
}

/* Emit the vtable function-pointer body lines (without surrounding typedef/braces). */
private fun CCodeGen.emitIfaceVtableBody(
    inProps: List<PropDecl>,
    inMethods: List<FunDecl>,
    inHasDispose: Boolean
) {
    for (vP in inProps) {
        val vCt = if (vP.type != null) cType(vP.type) else "ktc_Int"
        hdr.appendLine("    $vCt (*${vP.name})(void* \$self);")
    }
    for (vM in inMethods) {
        val vReturnsNullable = vM.returnType != null && vM.returnType.nullable
        val vMRetKtc = if (vM.returnType != null) resolveTypeName(vM.returnType) else null
        val vMRetResolved = vMRetKtc?.toInternalStr ?: ""
        val vCRet = if (vReturnsNullable) optCTypeName(vMRetResolved)
                    else if (vM.returnType != null) cType(vM.returnType) else "void"
        val vExtraParams = vM.params.joinToString("") { vP ->
            val vKtcVtParam = resolveTypeName(vP.type)
            val vVtParamStr = vKtcVtParam.toInternalStr
            if (vP.type.nullable) ", ${optCTypeName(vVtParamStr)} ${vP.name}"
            else ", ${cType(vP.type)} ${vP.name}"
        }
        hdr.appendLine("    $vCRet (*${vM.name})(void* \$self$vExtraParams);")
    }
    if (!inHasDispose) hdr.appendLine("    void (*dispose)(void* \$self);")
}

/** Collect all methods for an interface, including inherited from super interfaces (depth-first). */
internal fun CCodeGen.collectAllIfaceMethods(info: IfaceInfo): List<FunDecl> {
    val result = mutableListOf<FunDecl>()
    val seen = mutableSetOf<String>()
    fun collect(i: IfaceInfo) {
        for (superRef in i.superInterfaces) {
            val superName = resolveIfaceName(superRef)
            val superInfo = interfaces[superName] ?: continue
            collect(superInfo)
        }
        for (m in i.methods) {
            if (m.name !in seen) { result += m; seen += m.name }
        }
    }
    collect(info)
    return result
}

/** Collect all properties for an interface, including inherited from super interfaces. */
internal fun CCodeGen.collectAllIfaceProperties(info: IfaceInfo): List<PropDecl> {
    val result = mutableListOf<PropDecl>()
    val seen = mutableSetOf<String>()
    fun collect(i: IfaceInfo) {
        for (superRef in i.superInterfaces) {
            val superName = resolveIfaceName(superRef)
            val superInfo = interfaces[superName] ?: continue
            collect(superInfo)
        }
        for (p in i.propDecls) {
            if (p.name !in seen) { result += p; seen += p.name }
        }
    }
    collect(info)
    return result
}

/** Data member name for a class inside a tagged union or single-field interface struct. */
internal fun CCodeGen.ifaceDataName(className: String): String = "${typeFlatName(className)}_data"

/*
Emits the deferred as_* cast functions for [inClassName] into the current impl buffer,
wrapped in a boxSection("as") header, then removes the entry from deferredAsCalls.
Call this after all vtable structs for the class have been emitted.
*/
internal fun CCodeGen.flushDeferredAsForClass(inClassName: String) {
    val vAsCalls = deferredAsCalls.remove(inClassName) ?: return // nothing deferred
    if (vAsCalls.isEmpty()) return
    impl.appendLine(boxSection("as"))
    impl.appendLine()
    impl.append(vAsCalls.toString().trimEnd())
    impl.appendLine()
    impl.appendLine()
}

/** Emit vtable struct for a class implementing an interface (shared by two interface-emission paths). */
internal fun CCodeGen.emitVtable(cClass: String, cIface: String, ifaceName: String, className: String, props: List<PropDecl>, methods: List<FunDecl>) {
    val isObject = objects.containsKey(className)

    // For objects, emit thin wrapper functions matching vtable signatures.
    // Object methods have no $self param but vtables require void* $self first.
    if (isObject) {
        if (methods.none { it.name == "dispose" } && !hasDisposeOverride(className)) {
            impl.appendLine("static void ${cClass}_${ifaceName}_dispose_vt(void* \$self) { (void)\$self; }")
            impl.appendLine()
        }
        for (m in methods) {
            val mReturnsNullable = m.returnType != null && m.returnType.nullable
            val vMRetKtc = if (m.returnType != null) resolveTypeName(m.returnType) else null
            val mRetResolved = vMRetKtc?.toInternalStr ?: ""
            val cRet = if (mReturnsNullable) optCTypeName(mRetResolved) else if (m.returnType != null) cType(m.returnType) else "void"
            val castExtra = m.params.joinToString("") { p ->
                val vKtcParam = resolveTypeName(p.type); val vPStr = vKtcParam.toInternalStr
                if (p.type.nullable) ", ${optCTypeName(vPStr)} ${p.name}" else ", ${cType(p.type)} ${p.name}"
            }
            val extraArgs = m.params.joinToString(", ") { it.name }
            val vtName = "${cClass}_${ifaceName}_${m.name}_vt"
            val targetFn = if (m.name == "dispose" && !hasDisposeOverride(className))
                null  // no-op for dispose without override
            else "${cClass}_${m.name}"
            impl.appendLine("static $cRet $vtName(void* \$self$castExtra) {")
            impl.appendLine("    (void)\$self;")
            if (targetFn != null) {
                if (cRet != "void") impl.appendLine("    return $targetFn($extraArgs);")
                else impl.appendLine("    $targetFn($extraArgs);")
            }
            impl.appendLine("}")
            impl.appendLine()
        }
    }

    impl.appendLine("const ${cIface}_vt ${cClass}_${ifaceName}_vt = {")
    for (p in props) {
        val ct = if (p.type != null) cType(p.type) else "ktc_Int"
        impl.appendLine("    ($ct (*)(void*)) ${cClass}_${p.name}_get,")
    }
    for (m in methods) {
        val mReturnsNullable = m.returnType != null && m.returnType.nullable
        val vMRetKtc = if (m.returnType != null) resolveTypeName(m.returnType) else null
        val mRetResolved = vMRetKtc?.toInternalStr ?: ""
        val cRet = if (mReturnsNullable) optCTypeName(mRetResolved) else if (m.returnType != null) cType(m.returnType) else "void"
        val extraCast = m.params.joinToString("") { p ->
            val vKtcParam = resolveTypeName(p.type); val vPStr = vKtcParam.toInternalStr
            if (p.type.nullable) ", ${optCTypeName(vPStr)}" else ", ${cType(p.type)}"
        }
        val fn = if (isObject) "${cClass}_${ifaceName}_${m.name}_vt"
                 else if (m.name == "dispose" && !hasDisposeOverride(className)) "ktc_core_noop_dispose"
                 else "${cClass}_${m.name}"
        impl.appendLine("    ($cRet (*)(void*$extraCast)) $fn,")
    }
    if (methods.none { it.name == "dispose" }) {
        val fnDispose = if (isObject) "${cClass}_${ifaceName}_dispose_vt"
                         else if (!hasDisposeOverride(className)) "ktc_core_noop_dispose"
                         else "${cClass}_dispose"
        impl.appendLine("    (void (*)(void*)) $fnDispose,")
    }
    impl.appendLine("};")
    impl.appendLine()
}

/** Returns true if a class or object has an explicit dispose() override. */
internal fun CCodeGen.hasDisposeOverride(className: String): Boolean {
    classes[className]?.methods?.any { it.name == "dispose" }?.let { return it }
    objects[className]?.methods?.any { it.name == "dispose" }?.let { return it }
    return false
}

/**
 * Generate the designated-initializer return expression for ClassName_as_IfaceName().
 * Format depends on how many implementors the interface has:
 *   0: (void*) shallow pointer  (fallback, no union)
 *   1: .ClassName = *$self     (single struct field, no data wrapper)
 *   2+: .data.ClassName = *$self  (tagged union)
 */
internal fun CCodeGen.ifaceAsInit(cIface: String, cClass: String, className: String, ifaceName: String): String {
    val impls = interfaceImplementors[ifaceName]
    val dataName = ifaceDataName(className)
    val typeIdField = ".__base.typeId = ${cClass}_TYPE_ID"
    return when {
        impls.isNullOrEmpty() -> "($cIface){(void*)\$self, &${cClass}_${ifaceName}_vt}"
        else -> "($cIface){$typeIdField, .data.$dataName = *\$self, .vt = &${cClass}_${ifaceName}_vt}"
    }
}

/**
 * Emit vtables for a concrete class name implementing the given super interfaces.
 * Works for both non-generic and monomorphized generic classes.
 * @param declsOnly if true, only emit header declarations (for grouping with class struct).
 * @param implsOnly if true, only emit .c implementations (skip hdr lines).
 */
internal fun CCodeGen.emitInterfaceVtablesForClass(className: String, superIfaceRefs: List<TypeRef>, declsOnly: Boolean = false, implsOnly: Boolean = false) {
    val cClass = typeFlatName(className)
    val isObject = objects.containsKey(className)
    val cSelfType = if (isObject) "${cClass}_t" else cClass
    val cSelfPtr = "$cSelfType*"
    for (ifaceRef in superIfaceRefs) {
        val ifaceName = resolveIfaceName(ifaceRef)
        val iface = interfaces[ifaceName] ?: continue
        val cIface = typeFlatName(ifaceName)
        val allMethods = collectAllIfaceMethods(iface)
        val allProps = collectAllIfaceProperties(iface)

        if (!implsOnly) hdr.appendLine()
        if (!implsOnly) hdr.appendLine("// ════ implements $ifaceName ════")
        if (!declsOnly) {
            val vDisplayName = typeRefToStr(ifaceRef)
            impl.appendLine(boxSection("implements $vDisplayName"))
            impl.appendLine()
            // Object interface method bodies buffered during emitObject
            val vMethodBuf = if (isObject) deferredObjIfaceMethods.remove(Pair(className, ifaceName)) else null
            if (vMethodBuf != null) impl.append(vMethodBuf)
            impl.appendLine(boxSection("cast to $vDisplayName"))
            impl.appendLine()
        }

        // Emit property getter wrappers
        for (p in allProps) {
            val ct = if (p.type != null) cType(p.type) else "ktc_Int"
            val getterName = "${cClass}_${p.name}_get"
            if (!implsOnly) hdr.appendLine("KTC_METHOD($ct, ${p.name}_get)($cSelfPtr \$self);")
            if (!declsOnly) {
                if (isObject) {
                    impl.appendLine("$ct $getterName($cSelfPtr \$self) { (void)\$self; return ${cClass}.${p.name}; }")
                } else {
                    impl.appendLine("$ct $getterName($cSelfPtr \$self) { return \$self->${p.name}; }")
                }
                impl.appendLine()
            }
        }

        // static vtable instance
        if (!implsOnly) hdr.appendLine("extern const ${cIface}_vt KTC_RELATED(${ifaceName}_vt);")
        if (!declsOnly) {
            emitVtable(cClass, cIface, ifaceName, className, allProps, allMethods)
        }

        // as_IfaceName — emitted inline after vtable struct
        if (!implsOnly) hdr.appendLine("KTC_METHOD($cIface, as_${ifaceName})($cSelfPtr \$self);")
        if (!implsOnly) {
            val lines = deferredHdrLines[className]
            if (lines != null) {
                for ((_, line) in lines) hdr.appendLine(line)
                deferredHdrLines.remove(className)
            }
        }
        if (!declsOnly) {
            impl.appendLine("$cIface ${cClass}_as_${ifaceName}($cSelfPtr \$self) {")
            if (isObject) impl.appendLine("    (void)\$self;")
            impl.appendLine("    return ${ifaceAsInit(cIface, cClass, className, ifaceName)};")
            impl.appendLine("}")
            impl.appendLine()
        }

        if (!declsOnly) emitTransitiveInterfaceVtables(className, cClass, iface)
    }
}

/**
 * For interface inheritance chains, emit vtables for parent interfaces.
 * E.g., if ArrayList_Int implements MutableList_Int which extends List_Int,
 * emit ArrayList_Int_as_List_Int with the List_Int subset of the vtable.
 */
internal fun CCodeGen.emitTransitiveInterfaceVtables(className: String, cClass: String, iface: IfaceInfo) {
    for (superRef in iface.superInterfaces) {
        val superName = resolveIfaceName(superRef)
        val superIface = interfaces[superName] ?: continue
        val cSuper = typeFlatName(superName)
        val superMethods = collectAllIfaceMethods(superIface)
        val superProps = collectAllIfaceProperties(superIface)

        val vSuperDisplay = typeRefToStr(superRef)
        impl.appendLine(boxSection("cast to $vSuperDisplay"))
        impl.appendLine()

        // Register this class as also implementing the parent interface
        val existing = classInterfaces[className]?.toMutableList() ?: mutableListOf()
        if (superName !in existing) {
            existing += superName
            classInterfaces[className] = existing
            // Also update the reverse map for tagged-union emission
            interfaceImplementors.getOrPut(superName) { mutableListOf() }.add(className)
        }

        // static vtable instance (same class methods, but only the parent's slots)
        emitVtable(cClass, cSuper, superName, className, superProps, superMethods)
        // as_SuperName — emitted inline after vtable struct
        impl.appendLine("$cSuper ${cClass}_as_${superName}($cClass* \$self) {")
        impl.appendLine("    return ${ifaceAsInit(cSuper, cClass, className, superName)};")
        impl.appendLine("}")
        impl.appendLine()

        // Recurse for deeper inheritance
        emitTransitiveInterfaceVtables(className, cClass, superIface)
    }
}



/* Recursively emit transitive parent interface header declarations inside the current KTC_TYPE_NAME block.
Must be called while #define KTC_TYPE_NAME is active so KTC_METHOD and KTC_RELATED expand correctly.
inByIface maps interface display strings to their deferred method lines. */
internal fun CCodeGen.emitTransitiveIfaceHdrDecls(
    inIface: IfaceInfo,
    inByIface: Map<String, List<Pair<String, String>>>
) {
    for (vSuperRef in inIface.superInterfaces) {
        val vSuperName = resolveIfaceName(vSuperRef)
        val vSuperIface = interfaces[vSuperName] ?: continue
        val vCSuperName = typeFlatName(vSuperName)
        val vSuperStr = typeRefToStr(vSuperRef)
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

