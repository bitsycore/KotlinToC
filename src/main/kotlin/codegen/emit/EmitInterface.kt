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

/* Collect all items of type T from an interface hierarchy, depth-first, deduplicating by name. */
private fun <T : Any> CCodeGen.collectAllIfaceItems(
    info:     IfaceInfo,
    getItems: (IfaceInfo) -> List<T>,
    getName:  (T) -> String
    ): List<T> {
    val result = mutableListOf<T>()
    val seen   = mutableSetOf<String>()
    fun collect(i: IfaceInfo) {
        for (superRef in i.superInterfaces) {
            val superName = resolveIfaceName(superRef)
            val superInfo = interfaces[superName] ?: continue
            collect(superInfo)
            }
        for (item in getItems(i)) {
            val name = getName(item)
            if (name !in seen) { result += item; seen += name }
            }
        }
    collect(info)
    return result
    }

/* Collect all methods for an interface, including inherited from super interfaces (depth-first). */
internal fun CCodeGen.collectAllIfaceMethods(info: IfaceInfo): List<FunDecl> =
    collectAllIfaceItems(info, { it.methods }, { it.name })

/* Collect all properties for an interface, including inherited from super interfaces. */
internal fun CCodeGen.collectAllIfaceProperties(info: IfaceInfo): List<PropDecl> =
    collectAllIfaceItems(info, { it.propDecls }, { it.name })

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

