package com.bitsycore.ktc.codegen

import com.bitsycore.ktc.ast.*
import com.bitsycore.ktc.codegen.emit.*
import com.bitsycore.ktc.codegen.expression.emitPendingThreadEntries
import com.bitsycore.ktc.codegen.expression.emitPendingClosures
import com.bitsycore.ktc.codegen.expression.emitPendingClosureFnInsts

// The main code-generation pipeline: collectAndScan() + generate().
// Class state and utilities live in CCodeGen.kt.

internal fun CCodeGen.collectAndScan() {
	collectDecls()
	scanAll()
	}

/* The scan/materialize sequence shared by collectAndScan() (the --check pass) and generate() -
kept in one place so the two paths can't drift. (R14) */
private fun CCodeGen.scanAll() {
	scanForClassArrayTypes()
	scanForGenericInstantiations()
	materializeGenericInstantiations()
	scanForGenericFunCalls()
	runGenericScanFixedPoint()
	}

/* Run scan + materialize + concrete-return computation until no new instantiations
appear. computeGenericFunConcreteReturns may discover transitive forwards (e.g.
toList<Int>'s body calls listOf) and register new instantiations, which then need
their own bodies scanned and materialized. */
private fun CCodeGen.runGenericScanFixedPoint() {
	var prevInst: Map<String, Set<List<String>>>
	do {
		prevInst = genericFunInstantiations.mapValues { it.value.toSet() }.toMap()
		scanGenericFunBodiesForInstantiations()
		materializeGenericInstantiations()
		scanGenericClassMethodBodiesForInstantiations()
		materializeGenericInstantiations()
		computeGenericFunConcreteReturns()
	} while (genericFunInstantiations.mapValues { it.value.toSet() } != prevInst)
	}

/* Translates the parsed KtFile AST into C11 output. Orchestrates all pipeline phases. */
internal fun CCodeGen.generate(): COutput {
	/* @file:DocumentationOnly files provide type information to other files but
	produce no C output themselves - the real implementations live in ktc_core. */
	if (file.documentationOnly) return COutput("", emptyMap())

	collectDecls()

	hdr.appendLine("#pragma once")
	if (memTrack) hdr.appendLine("#define KTC_MEM_TRACK")
	when (disposedMode) {
		"ASSERT" -> hdr.appendLine("#define KTC_DISPOSED_ASSERT")
		"LOG"    -> hdr.appendLine("#define KTC_DISPOSED_LOG")
		}
	when (doubleDisposeMode) {
		"ASSERT" -> hdr.appendLine("#define KTC_DOUBLE_DISPOSE_ASSERT")
		"LOG"    -> hdr.appendLine("#define KTC_DOUBLE_DISPOSE_LOG")
		}
	val vFromDir = file.pkg?.replace('.', '/') ?: ""  // directory of _package_.h, relative to outDir
	hdr.appendLine("#include \"${relIncludePath(vFromDir, "ktc/core/ktc_core.h")}\"")
	hdr.appendLine()

	for (vInc in file.cIncludes) hdr.appendLine(vInc.toCDirective())

	// Always include the ktc intrinsic header (except for ktc itself, which IS that header).
	val hasKtc = allFiles.any { it.pkg == "ktc" }
	if (hasKtc && file.pkg != "ktc")
		hdr.appendLine("#include \"${relIncludePath(vFromDir, "ktc/_package_.h")}\"")

	// Explicit imports (e.g. "ktc.std.*") → C #include directives.
	// Skip pure "ktc" imports - the intrinsic header is already included above.
	for (imp in file.imports) {
		if (imp == "ktc" || imp == "ktc.*") continue
		val parts = imp.removeSuffix(".*").split('.')
		hdr.appendLine("#include \"${relIncludePath(vFromDir, "${parts.joinToString("/")}/_package_.h")}\"")
		}
	hdr.appendLine()

	// Placeholder for primitive/external/string KTC_DEFINE_ARRAY|STRING - replaced after emission.
	hdr.appendLine("/* @SIZED_TYPES@ */")
	hdr.appendLine()

	scanAll()

	// Build interfaceImplementors early so emitClass/emitObject can query it (e.g. copyWith_any).
	for ((className, ifaces) in classInterfaces) {
		for (iface in ifaces) interfaceImplementors.getOrPut(iface) { mutableListOf() }.add(className)
		}

	// Forward-declare all concrete interface types and monomorphized generic class types,
	// plus every concrete (non-generic) class - so functions can take/return any user type
	// regardless of declaration order in the source file.
	data class FwdDecl(val vCName: String, val vSrc: String) // one forward declaration line
	val vFwdDecls = mutableListOf<FwdDecl>()
	val vSeenFwdNames = mutableSetOf<String>()
	fun addFwd(cName: String, vSrc: String) {
		if (vSeenFwdNames.add(cName)) vFwdDecls.add(FwdDecl(cName, vSrc))
		}
	for ((name, info) in interfaces) {
		if (info.typeParams.isNotEmpty()) continue
		val cName = typeFlatName(name)
		val vSrc  = declSourceFile[name]?.let { " // $it" } ?: ""
		addFwd(cName, vSrc)
		if (name !in simpleUnionInterfaces) addFwd("${cName}_vt", vSrc)
		val vComponents = mangledComponents[name]
		if (vComponents != null) {
			val (vGenBase, vTypeArgs) = vComponents
			addFwd(genericOptionalCName(vGenBase, vTypeArgs), vSrc)
			}
		}
	for ((baseName, instantiations) in genericInstantiations) {
		if (!genericClassDecls.containsKey(baseName)) continue
		for (typeArgs in instantiations) {
			val vMangledName = mangledGenericName(baseName, typeArgs)
			val vCName = typeFlatName(vMangledName)
			val vSrc   = declSourceFile[baseName]?.let { " // $it" } ?: ""
			addFwd(vCName, vSrc)
			addFwd(genericOptionalCName(baseName, typeArgs), vSrc)
			}
		}
	// Concrete (non-generic) class declarations from the current file - fwd-decl their
	// flat C struct name so signatures elsewhere in the same package can use them
	// before the body declaration appears.
	for (d in file.decls) {
		if (d is ClassDecl && d.typeParams.isEmpty() && !d.annotations.any { it.name == "DocumentationOnly" }) {
			val vSrc = declSourceFile[d.name]?.let { " // $it" } ?: ""
			addFwd(typeFlatName(d.name), vSrc)
			}
		}
	if (vFwdDecls.isNotEmpty()) {
		hdr.appendLine("/* $kHdrRule")
		hdr.appendLine(" * forward declarations")
		hdr.appendLine(" * $kHdrRule */")
		for (vFd in vFwdDecls) hdr.appendLine("typedef struct ${vFd.vCName} ${vFd.vCName};${vFd.vSrc}")
		hdr.appendLine()
		}
	// Primitive/external VarArr types (always-visible element types) - must come before class method prototypes.
	hdr.appendLine("/* @VAR_ARR_PRIM_TYPES@ */")
	hdr.appendLine()
	// Emit struct/enum/object declarations (non-generic).
	// Topologically sort class declarations so value-embedded types are always defined before their users.
	val sortedDecls = topoSortClassDecls(file.decls)
	var firstClass = true
	for (d in sortedDecls) when (d) {
		is ClassDecl -> if (d.typeParams.isEmpty() && !d.annotations.any { it.name == "DocumentationOnly" }) {
			if (!firstClass) hdr.appendLine()
			firstClass = false
			captureForDecl(d.name) {
				emitClass(d)
				for (vMember in d.members.filterIsInstance<ObjectDecl>()) {
					hdr.appendLine()
					val vCompName = "${d.name}$${vMember.name}"
					emitObject(ObjectDecl(vCompName, vMember.members))
					val vCompKind = if (vMember.name == "Companion") "companion object" else "object"
					impl.appendLine(classBlockFooter(vCompKind, vCompName.replace('$', '.'), emptyList()))
					impl.appendLine()
					}
				fun emitNested(inParent: ClassDecl, inParentFlatName: String) {
					for (vNested in inParent.members.filterIsInstance<ClassDecl>()) {
						if (vNested.typeParams.isEmpty()) {
							val vFlatName = "$inParentFlatName$${vNested.name}"
							hdr.appendLine()
							emitClass(ClassDecl(vFlatName, vNested.isData,
								vNested.ctorParams, vNested.members, vNested.initBlocks,
								vNested.superInterfaces, vNested.typeParams, vNested.secondaryCtors))
							emitNested(vNested, vFlatName)
							val vNestedKind = if (vNested.isData) "data class" else "class"
							impl.appendLine(classBlockFooter(vNestedKind, vFlatName.replace('$', '.'), emptyList()))
							impl.appendLine()
							}
						}
					}
				emitNested(d, d.name)
				}
			}
		is EnumDecl -> {
			if (!firstClass) hdr.appendLine()
			firstClass = false
			captureForDecl(d.name) { emitEnum(d) }
			}
		is ObjectDecl -> {
			if (!firstClass) hdr.appendLine()
			firstClass = false
			captureForDecl(d.name) { emitObject(d) }
			}
		is InterfaceDecl -> {
			for (vNested in d.nestedClasses) {
				val vFlatName = "${d.name}$${vNested.name}"
				if (vNested.typeParams.isEmpty()) {
					if (!firstClass) hdr.appendLine()
					firstClass = false
					captureForDecl(vFlatName) {
						val vSuperIfaces = nestedSuperIfacesWithParent(vNested, d.name, d.typeParams)
						emitClass(ClassDecl(vFlatName, vNested.isData, vNested.ctorParams,
							vNested.members, vNested.initBlocks, vSuperIfaces,
							vNested.typeParams, vNested.secondaryCtors))
						}
					}
				}
			}
		else -> {}
		}

	// User-package VarArr types - after non-generic type defs, before monomorphized generics.
	hdr.appendLine()
	hdr.appendLine("/* @VAR_ARR_TYPES@ */")

	// Pre-pass: register classInterfaces for objects and all monomorphized generic classes.
	for (d in file.decls) if (d is ObjectDecl && d.superInterfaces.isNotEmpty())
		classInterfaces[d.name] = d.superInterfaces.map { it.name }
	forEachMonomorphizedSuperIfaces { mangledName, resolvedIfaces ->
		classInterfaces[mangledName] = resolvedIfaces.map { resolveTypeNameStr(it) }
		}
	// Rebuild interfaceImplementors with generic classes included.
	interfaceImplementors.clear()
	for ((className, ifaces) in classInterfaces) {
		for (iface in ifaces) interfaceImplementors.getOrPut(iface) { mutableListOf() }.add(className)
		}

	// Emit monomorphized generic classes.
	for ((baseName, instantiations) in genericInstantiations) {
		val templateDecl = genericClassDecls[baseName] ?: continue
		for (typeArgs in instantiations) {
			if (!firstClass) hdr.appendLine()
			firstClass = false
			val mangledName  = mangledGenericName(baseName, typeArgs)
			val templateCi   = classes[baseName] ?: continue
			typeSubst = templateCi.typeParams.zip(typeArgs).toMap()
			val prevSourceFile = currentSourceFile
			declSourceFile[baseName]?.let { currentSourceFile = it }
			captureForDecl(mangledName) { emitGenericClass(templateDecl, mangledName) }
			currentSourceFile = prevSourceFile
			typeSubst = emptyMap()
			}
		}

	// Emit static vtable instances for interface implementations.
	for (d in file.decls) if (d is ClassDecl && d.typeParams.isEmpty() && d.superInterfaces.isNotEmpty())
		captureForDecl(d.name) { emitInterfaceVtablesForClass(d.name, d.superInterfaces, implsOnly = true) }
	for (d in file.decls) if (d is ObjectDecl && d.superInterfaces.isNotEmpty())
		captureForDecl(d.name) { emitInterfaceVtablesForClass(d.name, d.superInterfaces, implsOnly = true) }
	for (d in file.decls) if (d is InterfaceDecl) {
		for (vNested in d.nestedClasses) if (vNested.typeParams.isEmpty()) {
			val vFlatName = "${d.name}$${vNested.name}"
			val vIfaces = classInterfaces[vFlatName] ?: continue
			if (vIfaces.isNotEmpty()) {
				val vIfaceRefs = vIfaces.map { TypeRef(it) }
				captureForDecl(vFlatName) { emitInterfaceVtablesForClass(vFlatName, vIfaceRefs, implsOnly = true) }
				}
			}
		}
	forEachMonomorphizedSuperIfaces { mangledName, resolvedIfaces ->
		captureForDecl(mangledName) { emitInterfaceVtablesForClass(mangledName, resolvedIfaces, implsOnly = true) }
		}

	// Emit complete interface blocks (vtable + tagged union + $Opt).
	for ((name, info) in interfaces) {
		if (info.typeParams.isNotEmpty()) continue
		val isMonomorphized  = mangledComponents.containsKey(name)
			|| genericIfaceDecls.keys.any { tmpl -> name.startsWith(tmpl + "_") }
		val isCrossPackage   = info.pkg.isNotEmpty() && info.pkg != prefix
		if (!isMonomorphized && isCrossPackage) continue
		hdr.appendLine()
		val prevSourceFile = currentSourceFile
		declSourceFile[name]?.let { currentSourceFile = it }
		emitInterfaceBlock(info)
		currentSourceFile = prevSourceFile
		}

	// Placeholder replaced later with user-type KTC_DEFINE_ARRAY (must be after struct defs).
	hdr.appendLine()
	hdr.appendLine("/* @SIZED_TYPES_USER@ */")

	// Emit top-level functions, properties, generic funs, star ext funs, enum values data.
	for (d in file.decls) when (d) {
		is FunDecl -> {
			if (d.annotations.any { it.name == "DocumentationOnly" }) continue
			if (d.typeParams.isNotEmpty()) continue
			if (d.receiver != null && d.receiver.typeArgs.any { it.name == "*" }) continue
			if (d.receiver != null && d.receiver.typeArgs.isNotEmpty()
				&& (genericIfaceDecls.containsKey(d.receiver.name) || genericClassDecls.containsKey(d.receiver.name))) continue
			if (d.receiver != null && (d.isInline || d.isInfix)) continue
			val vSrcKey = "|${declSourceFile[d.name] ?: sourceFileName}"
			captureForDecl(vSrcKey) {
				if (d.receiver != null) emitExtensionFun(d) else emitFun(d)
				}
			}
		is PropDecl -> {
			if (d.receiver != null) continue  // extension props: getter inlined at access site via genDot
			val vSrcKey = "|${declSourceFile[d.name] ?: sourceFileName}"
			captureForDecl(vSrcKey) { emitTopProp(d) }
			}
		else -> {}
		}
	for (f in genericFunDecls) {
		val vSrcKey = "|${declSourceFile[f.name] ?: sourceFileName}"
		captureForDecl(vSrcKey) { emitGenericFunInstantiations(f) }
		}
	for (f in starExtFunDecls) {
		val vSrcKey = "|${declSourceFile[f.name] ?: sourceFileName}"
		captureForDecl(vSrcKey) { emitStarExtFunInstantiations(f) }
		}
	// Generated `thread { }` entry functions, closure invoke functions, and higher-order
	// monomorphizations - flushed here, after the decl loop, so they don't nest inside the buffer of
	// the function that defined the lambda. Iterate to a fixpoint: a monomorphized body may itself
	// introduce new closures / thread entries.
	while (pendingThreadEntries.isNotEmpty() || pendingClosures.isNotEmpty() || pendingClosureFnInsts.isNotEmpty()) {
		emitPendingThreadEntries()
		emitPendingClosures()
		emitPendingClosureFnInsts()
	}
	emitEnumValuesData()

	// ── Assemble output ────────────────────────────────────────────────
	val vSrcName = prefix.trimEnd('_').ifEmpty {
		sourceFileName.removeSuffix(".kt").ifEmpty { "main" }
		}
	val vPkg     = file.pkg ?: ""
	val vSources = mutableMapOf<String, SourceFile>()

	// Per-source-file top-level .c files (one per .kt source file, key starts with "|").
	for ((vKey, vTopImpl) in perDeclImpl) {
		if (!vKey.startsWith("|")) continue
		if (vTopImpl.isEmpty()) continue
		val vSrcFull    = vKey.removePrefix("|")
		val vSrcBase    = vSrcFull.removeSuffix(".kt")
		val vCFileName  = "${vSrcBase}Kt.c"
		val vTopImplFwd = perDeclImplFwd[vKey]
		val vSrc = buildString {
			appendLine(funBlockHeader(vPkg, vSrcFull))
			appendLine()
			appendLine("#include \"_package_.h\"")
			for (vInc in file.cIncludes) appendLine(vInc.toCDirective())
			appendLine()
			if (vTopImplFwd != null && vTopImplFwd.isNotEmpty()) {
				append(vTopImplFwd)
				appendLine()
				}
			append(vTopImpl)
			}
		vSources[vCFileName] = SourceFile(vSrc, vPkg)
		}

	// Per-declaration .c files - one per class / object / enum.
	for ((vDeclName, vDeclImpl) in perDeclImpl) {
		if (vDeclName.startsWith("|")) continue
		if (vDeclImpl.isEmpty()) continue
		val vDeclImplFwd = perDeclImplFwd[vDeclName]
		val vCi          = classes[vDeclName]
		val vOi          = objects[vDeclName]
		val vEi          = enums[vDeclName]
		val vGenBase     = mangledComponents[vDeclName]?.first
		val vGenTypeArgs = mangledComponents[vDeclName]?.second
		val vKind: String; val vCName: String; val vKtName: String
		val vSrcFile: String; val vInstFrom: String; val vRoutingPkg: String
		when {
			vCi != null -> {
				vKind       = if (vCi.isData) "data class" else "class"
				vCName      = vCi.flatName
				val vBase   = vGenBase ?: vDeclName
				vSrcFile    = declSourceFile[vBase] ?: sourceFileName
				vInstFrom   = if (vGenBase != null) sourceFileName else ""
				vKtName     = if (vGenBase != null && vGenTypeArgs != null)
					"$vGenBase<${vGenTypeArgs.joinToString(", ")}>" else vDeclName.replace('$', '.')
				vRoutingPkg = declOrigPkg[vBase] ?: vPkg
				}
			vOi != null -> {
				val vParts  = vDeclName.split('$')
				vKind       = if (vParts.size > 1 && vParts.last() == "Companion") "companion object" else "object"
				vCName      = vOi.flatName
				vSrcFile    = declSourceFile[vDeclName] ?: sourceFileName
				vInstFrom   = ""
				vKtName     = vDeclName.replace('$', '.')
				vRoutingPkg = declOrigPkg[vDeclName] ?: vPkg
				}
			vEi != null -> {
				vKind       = "enum"
				vCName      = vEi.flatName
				vSrcFile    = declSourceFile[vDeclName] ?: sourceFileName
				vInstFrom   = ""
				vKtName     = vDeclName
				vRoutingPkg = declOrigPkg[vDeclName] ?: vPkg
				}
			else -> continue
			}
		val vFileDir    = vRoutingPkg.replace('.', '/')
		val vHdrAbsPath = if (vPkg.isNotEmpty()) "${vPkg.replace('.', '/')}/_package_.h"
			else "$vSrcName/_package_.h"
		val vSrc = buildString {
			appendLine("#include \"${relIncludePath(vFileDir, vHdrAbsPath)}\"")
			for (vInc in file.cIncludes) appendLine(vInc.toCDirective())
			appendLine()
			appendLine(cSourceFileHeader(vKind, vKtName, vRoutingPkg, vCName, vSrcFile, vInstFrom))
			appendLine()
			if (vDeclImplFwd != null && vDeclImplFwd.isNotEmpty()) {
				append(vDeclImplFwd)
				appendLine()
				}
			append(vDeclImpl)
			appendLine(classBlockFooter(vKind, vKtName, emptyList()))
			appendLine()
			}
		vSources["$vDeclName.c"] = SourceFile(vSrc, vRoutingPkg)
		}

	// Replace @SIZED_TYPES@ (before fwd decls) with primitive/external/string array defs.
	// Replace @SIZED_TYPES_USER@ (after class defs) with current-pkg user type array defs.
	val vEarlyTypesSb = StringBuilder()
	val vUserTypesSb  = StringBuilder()
	for ((vElemCType, vSize) in sizedArrayDecls.sortedWith(compareBy({ it.first }, { it.second })))
		vUserTypesSb.appendLine("KTC_DEFINE_ARRAY($vElemCType, $vSize);")
	for ((vElemCType, vSize) in sizedArrayGuardedDecls
		.filter { it !in sizedArrayDecls }
		.sortedWith(compareBy({ it.first }, { it.second }))) {
		val vGuard = "KTC_ARRAY_DEF_${vElemCType}_$vSize"
		vEarlyTypesSb.appendLine("#ifndef $vGuard\n#define $vGuard\nKTC_DEFINE_ARRAY($vElemCType, $vSize);\n#endif")
		}
	for (vSize in sizedStringDecls.sorted()) {
		val vGuard = "KTC_STRING_DEF_$vSize"
		vEarlyTypesSb.appendLine("#ifndef $vGuard\n#define $vGuard\nKTC_DEFINE_STRING($vSize);\n#endif")
		}
	fun replaceHdrPlaceholder(inPlaceholder: String, inContent: StringBuilder, inTitle: String) {
		val vIdx = hdr.indexOf(inPlaceholder)
		if (vIdx < 0) return
		val vSection = if (inContent.isNotEmpty())
			"/* $kHdrRule\n * $inTitle\n * $kHdrRule */\n$inContent"
		else ""
		val vEnd = vIdx + inPlaceholder.length +
			if (hdr.getOrNull(vIdx + inPlaceholder.length) == '\n') 1 else 0
		hdr.replace(vIdx, vEnd, vSection)
		}
	replaceHdrPlaceholder("/* @SIZED_TYPES_USER@ */", vUserTypesSb,  "sized array types (user-defined element types)")
	replaceHdrPlaceholder("/* @SIZED_TYPES@ */",      vEarlyTypesSb, "sized array / string types")

	// VarArr declarations split by when element type is visible:
	//   @VAR_ARR_PRIM_TYPES@ - primitives/external types: before class method prototypes
	//   @VAR_ARR_TYPES@      - current-package user types: after all type definitions
	fun buildVarArrSection(inTypes: Set<String>): StringBuilder {
		val vSb = StringBuilder()
		for (vElemCType in inTypes.toSortedSet()) {
			val vGuard = "KTC_VAR_ARR_DEF_${sanitizeForVarArrName(vElemCType)}"
			vSb.appendLine("#ifndef $vGuard\n#define $vGuard\nKTC_DECL_VAR_ARR($vElemCType, ${varArrTypeRef(vElemCType)});\n#endif")
			}
		return vSb
		}
	replaceHdrPlaceholder("/* @VAR_ARR_PRIM_TYPES@ */", buildVarArrSection(varArrGuardedDecls), "typed VarArr types (primitives / external)")
	replaceHdrPlaceholder("/* @VAR_ARR_TYPES@ */",      buildVarArrSection(varArrDecls),        "typed VarArr types (current-package user types)")

	// Deduplicate string literals used 2+ times within the same .c file.
	val vStrPattern = Regex("""ktc_core_str\("([^"]*)"\)""")
	val vGlobalDups = mutableSetOf<String>()
	for (vSf in vSources.values) {
		val vPerFile = mutableMapOf<String, Int>()
		for (vMatch in vStrPattern.findAll(vSf.content)) {
			val vValue = vMatch.groupValues[1]
			vPerFile[vValue] = (vPerFile[vValue] ?: 0) + 1
		}
		for ((vValue, vCount) in vPerFile) if (vCount >= 2) vGlobalDups += vValue
	}
	if (vGlobalDups.isNotEmpty()) {
		val vStrLitSb = StringBuilder()
		vStrLitSb.appendLine("/* ════ interned string literals ════ */")
		var vIdx = 0
		val vReplacements = mutableMapOf<String, String>()
		val vPrefix = if (prefix.isNotEmpty()) prefix.trimEnd('_') else "global"
		for (vValue in vGlobalDups.sorted()) {
			val vArr  = "ktc_str_${vPrefix}_$vIdx"
			val vName = "\$${vPrefix}_s$vIdx"
			vIdx++
			// Intern into a named, read-only static array - guarantees .rodata placement + NUL
			// termination explicitly (a C string literal already is both) and dedups the value.
			vStrLitSb.appendLine("static const ktc_Char $vArr[] = \"$vValue\";")
			vStrLitSb.appendLine("#define $vName ((ktc_String){$vArr, (ktc_Int)(sizeof($vArr) - 1)})")
			vReplacements["ktc_core_str(\"$vValue\")"] = vName
		}
		vStrLitSb.appendLine()
		val vInsertIdx = hdr.indexOf("/* @SIZED_TYPES@ */")
		if (vInsertIdx >= 0) hdr.insert(vInsertIdx, vStrLitSb)
		else hdr.append(vStrLitSb)
		for ((vCFileName, vSf) in vSources) {
			var vContent = vSf.content
			for ((vInline, vName) in vReplacements) {
				vContent = vContent.replace(vInline, vName)
			}
			if (vContent != vSf.content) vSources[vCFileName] = SourceFile(vContent, vSf.routingPkg)
		}
	}

	// Collapse 3+ consecutive newlines to 2 (= 1 blank line) so empty sections leave no extra whitespace.
	return COutput(hdr.toString().replace(Regex("\n{3,}"), "\n\n"), vSources)
	}

/* Iterate every monomorphized generic-class instantiation that declares super
   interfaces. Sets up the typeSubst around [inAction] so codegen helpers see the
   correct substitution while resolving interface type names. Skips when the
   ClassInfo or class template can't be looked up. */
private inline fun CCodeGen.forEachMonomorphizedSuperIfaces(
	inAction: (mangledName: String, resolvedIfaces: List<TypeRef>) -> Unit
	) {
	for ((vBaseName, vInstantiations) in genericInstantiations) {
		val vTemplateDecl = genericClassDecls[vBaseName] ?: continue
		if (vTemplateDecl.superInterfaces.isEmpty()) continue
		for (vTypeArgs in vInstantiations) {
			val vMangledName = mangledGenericName(vBaseName, vTypeArgs)
			val vCi          = classes[vMangledName] ?: continue
			val vSubst       = vCi.typeParams.ifEmpty { vTemplateDecl.typeParams }.zip(vTypeArgs).toMap()
				.ifEmpty { genericTypeBindings[vMangledName] ?: emptyMap() }
			val vResolved    = vTemplateDecl.superInterfaces.map { substituteTypeRef(it, vSubst) }
			typeSubst = vSubst
			inAction(vMangledName, vResolved)
			typeSubst = emptyMap()
			}
		}
	}
