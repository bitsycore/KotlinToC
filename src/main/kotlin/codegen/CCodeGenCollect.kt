package com.bitsycore.ktc.codegen

import com.bitsycore.ktc.ast.*
import com.bitsycore.ktc.codegen.emit.collectAllIfaceMethods
import com.bitsycore.ktc.codegen.expression.inferBlockType
import com.bitsycore.ktc.codegen.statement.inferInitType
import com.bitsycore.ktc.types.KtcType

/* Populate all symbol tables from allFiles (cross-reference pass) then from
the current file (authoritative pass). Must be called before any emission. */
internal fun CCodeGen.collectDecls() {
	objectsWithDispose.clear()
	tlsObjects.clear()
	tlsProps.clear()
	// Collect from all files for cross-reference resolution
	for (f in allFiles) {
		if (f.documentationOnly) continue
		val fpfx = f.pkg?.replace('.', '_')?.plus("_") ?: ""  // package prefix for this file
		for (d in f.decls) {
			collectDecl(d)
			// Record the source file for generic declarations (for mem-track attribution)
			if (f.sourceFile.isNotEmpty()) {
				when (d) {
					is ClassDecl     -> declSourceFile[d.name] = f.sourceFile
					is FunDecl       -> declSourceFile[d.name] = f.sourceFile
					is InterfaceDecl -> declSourceFile[d.name] = f.sourceFile
					is ObjectDecl    -> declSourceFile[d.name] = f.sourceFile
					is PropDecl      -> declSourceFile[d.name] = f.sourceFile
					else             -> {}
					}
				}
			// Record the package prefix for cross-file symbols
			when (d) {
				is ClassDecl -> {
					classes[d.name]?.pkg = fpfx
					declOrigPkg[d.name] = f.pkg ?: ""
					for (vMember in d.members.filterIsInstance<ObjectDecl>()) {
						objects["${d.name}$${vMember.name}"]?.pkg = fpfx
						declOrigPkg["${d.name}$${vMember.name}"] = f.pkg ?: ""
						}
					}
				is EnumDecl      -> { enums[d.name]?.pkg = fpfx;   declOrigPkg[d.name] = f.pkg ?: "" }
				is InterfaceDecl -> interfaces[d.name]?.pkg = fpfx
				is ObjectDecl    -> { objects[d.name]?.pkg = fpfx; declOrigPkg[d.name] = f.pkg ?: "" }
				is FunDecl -> {
					if (d.receiver == null)
						funNames[d.name] = "$fpfx${d.name}"   // cross-file C name
					}
				else -> {}
				}
			}
		}
	// Re-key extension props and extension funs from dotted receiver names to dollar form.
	// Needed when the extension file is processed before its receiver's definition file in the
	// cross-reference pass: e.g. "SDL3.Renderer" → "SDL3$Renderer" once classes["SDL3$Renderer"]
	// is populated, or "SDL3.Event" → "SDL3$Event" once objects["SDL3$Event"] is populated.
	val vDottedPropKeys = extensionProps.keys.filter { '.' in it }.toList()
	for (key in vDottedPropKeys) {
		val dollarKey = key.replace('.', '$')
		if (classes.containsKey(dollarKey) || objects.containsKey(dollarKey)) {
			extensionProps.getOrPut(dollarKey) { mutableListOf() }.addAll(extensionProps[key]!!)
			extensionProps.remove(key)
			}
		}
	val vDottedFunKeys = extensionFuns.keys.filter { '.' in it }.toList()
	for (key in vDottedFunKeys) {
		val dollarKey = key.replace('.', '$')
		if (classes.containsKey(dollarKey) || objects.containsKey(dollarKey)) {
			extensionFuns.getOrPut(dollarKey) { mutableListOf() }.addAll(extensionFuns[key]!!)
			extensionFuns.remove(key)
			}
		}
	// Set pkg for nested classes whose pkg wasn't explicitly set
	for ((name, ci) in classes) {
		if ('$' in name && ci.pkg.isEmpty()) {
			val vParent = name.substringBefore('$')
			ci.pkg = classes[vParent]?.pkg ?: objects[vParent]?.pkg ?: prefix
			}
		}
	// Current file's symbols use current prefix (overwrite any from allFiles)
	for (d in file.decls) {
		collectDecl(d, validate = true)
		when (d) {
			is ClassDecl -> {
				classes[d.name]?.pkg = prefix
				for (vMember in d.members.filterIsInstance<ObjectDecl>()) {
					objects["${d.name}$${vMember.name}"]?.pkg = prefix
					}
				}
			is EnumDecl      -> enums[d.name]?.pkg = prefix
			is InterfaceDecl -> interfaces[d.name]?.pkg = prefix
			is ObjectDecl    -> objects[d.name]?.pkg = prefix
			is FunDecl -> {
				if (d.receiver == null)
					funNames[d.name] = "$prefix${d.name}"   // current-file C name (overrides allFiles)
				}
			else -> {}
			}
		}
	// Sync pkg for nested classes/objects after the current-file pass
	for ((name, ci) in classes) {
		if ('$' in name) {
			val vParent = name.substringBefore('$')
			ci.pkg = classes[vParent]?.pkg ?: objects[vParent]?.pkg ?: prefix
			}
		}
	for ((name, oi) in objects) {
		if ('$' in name) {
			val vParent = name.substringBefore('$')
			oi.pkg = classes[vParent]?.pkg ?: objects[vParent]?.pkg ?: prefix
			}
		}
	}

/* Register a single declaration into the appropriate symbol table.
validate=true enables interface implementation checks (current-file pass only). */
internal fun CCodeGen.collectDecl(d: Decl, validate: Boolean = false) {
	when (d) {
		is ClassDecl -> {
			if (d.annotations.any { it.name == "DocumentationOnly" }) return
			for (p in d.ctorParams) {
				if ((p.isVal || p.isVar) && p.type.isRawArray()) {
					codegenError("Class property '${p.name}' cannot have raw array type '${p.type.name}'. Use @Ptr Array<T> or @Size(N) Array<T> instead")
					}
				}
			for (p in d.members.filterIsInstance<PropDecl>()) {
				val propType = p.type ?: inferInitType(p.init)
				if (propType.isRawArray()) {
					currentStmtLine = p.line
					codegenError("Class property '${p.name}' cannot have raw array type '${propType.name}'. Use @Ptr Array<T> or @Size(N) Array<T> instead")
					}
				}
			val vCtorProps = d.ctorParams.filter { it.isVal || it.isVar }.map { vP ->  // ctor val/var props
				PropertyDef(
					name = vP.name,
					typeRef = vP.type,
					isVal = vP.isVal,
					isPrivate = vP.isPrivate,
					isConstructorParam = true
					)
				}
			val vCtorPlainParams = d.ctorParams.filter { !it.isVal && !it.isVar }.map { vP ->  // plain ctor params (not properties)
				PropertyDef(
					name = vP.name,
					typeRef = vP.type,
					isVal = false,
					isConstructorParam = true
					)
				}
			val vBodyProps = d.members.filterIsInstance<PropDecl>().map { vP ->  // body-declared properties
				PropertyDef(
					name = vP.name,
					typeRef = vP.type ?: inferInitType(vP.init),
					isVal = !vP.mutable,
					isPrivate = vP.isPrivate,
					isPrivateSet = vP.isPrivateSet,
					initExpr = vP.init,
					line = vP.line,
					getter = vP.getter,
					setterParam = vP.setterParam,
					setterBody = vP.setterBody
					)
				}
			val vAllProps = vCtorProps + vBodyProps  // combined property list
			val ci = ClassInfo(d.name, d.isData, vAllProps, vCtorPlainParams, initBlocks = d.initBlocks, typeParams = d.typeParams)
			if (d.typeParams.isNotEmpty()) allGenericTypeParamNames += d.typeParams
			for (m in d.members) if (m is FunDecl && m.receiver == null) {
				if (m.returnType != null && m.returnType.isRawArray()) {
					codegenError("Method '${m.name}' cannot return raw array type '${m.returnType.name}'. Use @Ptr Array<T> or @Size(N) Array<T> instead")
					}
				ci.methods += m
				}
			classes[d.name] = ci
			allClassDecls[d.name] = d
			getTypeId(d.name)
			if (d.typeParams.isNotEmpty()) genericClassDecls[d.name] = d
			if (d.superInterfaces.isNotEmpty()) classInterfaces[d.name] = d.superInterfaces.map { it.name }
			// Verify all interface methods are implemented with override (current file, non-stdlib)
			if (validate && file.pkg != "ktc.std" && file.pkg != "ktc") {
				val classMethodNames = ci.methods.associateBy { it.name }
				for (ifaceRef in d.superInterfaces) {
					val ifaceName = resolveIfaceName(ifaceRef)
					val iface     = interfaces[ifaceName] ?: continue
					for (m in collectAllIfaceMethods(iface)) {
						val impl = classMethodNames[m.name]
						when {
							impl == null      -> codegenError("Class '${d.name}' must implement '${m.name}' from interface '$ifaceName'")
							!impl.isOverride  -> codegenError("Method '${m.name}' in class '${d.name}' must be marked 'override'")
							}
						}
					}
				// dispose() and hashCode() are implicitly overrides — always require the keyword
				for (m in ci.methods) {
					if ((m.name == "dispose" || m.name == "hashCode") && !m.isOverride) {
						codegenError("Method '${m.name}' in class '${d.name}' must be marked 'override'")
						}
					}
				// Check for bogus override on methods that don't match any interface
				val allIfaceMethodNames = d.superInterfaces.flatMap { ifaceRef ->
					val ifaceName = resolveIfaceName(ifaceRef)
					interfaces[ifaceName]?.let { collectAllIfaceMethods(it).map { m -> m.name } } ?: emptyList()
					}.toSet() + "dispose" + "hashCode"
				for (m in ci.methods) {
					if (m.isOverride && m.name !in allIfaceMethodNames) {
						codegenError("Method '${m.name}' is marked 'override' but does not override any interface method")
						}
					}
				}
			// Collect companion objects declared inside this class
			for (vMember in d.members.filterIsInstance<ObjectDecl>()) {
				val vCompanionSynthName = "${d.name}$${vMember.name}"
				classCompanions[d.name] = vCompanionSynthName
				collectDecl(ObjectDecl(vCompanionSynthName, vMember.members, vMember.annotations))
				}
			// Collect nested classes (namespacing: prefix with parent name)
			val nestedClasses = d.members.filterIsInstance<ClassDecl>()
			for (nested in nestedClasses) {
				val nestedName = "${d.name}$${nested.name}"
				collectDecl(ClassDecl(nestedName, nested.isData, nested.ctorParams, nested.members,
					nested.initBlocks, nested.superInterfaces, nested.typeParams, nested.secondaryCtors))
				}
			}

		is EnumDecl  -> enums[d.name] = EnumInfo(d.name, d.entries)

		is InterfaceDecl -> {
			interfaces[d.name] = IfaceInfo(d.name, d.methods, d.properties, d.typeParams, d.superInterfaces)
			getTypeId(d.name)
			if (d.typeParams.isNotEmpty()) {
				genericIfaceDecls[d.name] = d
				allGenericTypeParamNames += d.typeParams
				}
			}

		is ObjectDecl -> {
			if (d.annotations.any { it.name == "Tls" })       tlsObjects.add(d.name)
			if (d.annotations.any { it.name == "Namespace" }) namespaceObjects.add(d.name)
			for (p in d.members.filterIsInstance<PropDecl>()) {
				val propType = p.type ?: inferInitType(p.init)
				if (propType.isRawArray()) {
					currentStmtLine = p.line
					codegenError("Object property '${p.name}' cannot have raw array type '${propType.name}'. Use @Ptr Array<T> or @Size(N) Array<T> instead")
					}
				}
			val vObjProps = d.members.filterIsInstance<PropDecl>().map { vP ->  // object properties
				PropertyDef(
					name = vP.name,
					typeRef = vP.type ?: inferInitType(vP.init),
					isVal = !vP.mutable,
					isPrivate = vP.isPrivate,
					isPrivateSet = vP.isPrivateSet,
					initExpr = vP.init,
					line = vP.line,
					getter = vP.getter,
					setterParam = vP.setterParam,
					setterBody = vP.setterBody
					)
				}
			val oi = ObjInfo(d.name, vObjProps)
			for (m in d.members) if (m is FunDecl) {
				if (m.returnType != null && m.returnType.isRawArray()) {
					codegenError("Method '${m.name}' cannot return raw array type '${m.returnType.name}'. Use @Ptr Array<T> or @Size(N) Array<T> instead")
					}
				oi.methods += m
				if (funSigs[m.name] == null) funSigs[m.name] = FunSig(m.params, m.returnType)
				if (m.isInline) inlineFunDecls.getOrPut(m.name) { mutableListOf() }.add(m)
				// Register extension functions declared inside object
				if (m.receiver != null) {
					val vRecv = resolveNestedObjName(m.receiver.name, d.name)
					extensionFuns.getOrPut(vRecv) { mutableListOf() }.add(m)
					classes[vRecv]?.methods?.add(m)
					funSigs["${vRecv}.${m.name}"] = FunSig(m.params, m.returnType)
					if (m.isInline || m.isInfix) inlineExtFunDecls.getOrPut(m.name) { mutableListOf() }.add(m)
					}
				}
			objects[d.name] = oi
			if (d.superInterfaces.isNotEmpty()) classInterfaces[d.name] = d.superInterfaces.map { it.name }
			// dispose()/hashCode() are implicitly overrides — require the keyword (current file, non-stdlib)
			if (validate && file.pkg != "ktc.std" && file.pkg != "ktc") {
				for (m in d.members) if (m is FunDecl && (m.name == "dispose" || m.name == "hashCode") && !m.isOverride) {
					codegenError("Method '${m.name}' in object '${d.name}' must be marked 'override'")
					}
				}
			// Track objects with dispose for auto-call on main exit (current file only)
			if (validate) {
				for (m in d.members) if (m is FunDecl && m.name == "dispose") {
					val cName = "$prefix${d.name}"
					if (cName !in objectsWithDispose) objectsWithDispose.add(cName)
					}
				}
			// Collect nested classes inside object
			for (nested in d.members.filterIsInstance<ClassDecl>()) {
				val nestedName = "${d.name}$${nested.name}"
				collectDecl(ClassDecl(nestedName, nested.isData, nested.ctorParams, nested.members,
					nested.initBlocks, nested.superInterfaces, nested.typeParams, nested.secondaryCtors))
				}
				// Collect nested objects (e.g. object Event inside SDL3)
				for (nested in d.members.filterIsInstance<ObjectDecl>()) {
					val nestedName = "${d.name}$${nested.name}"
					collectDecl(ObjectDecl(nestedName, nested.members, nested.annotations, nested.superInterfaces))
					}
			}

		is FunDecl -> {
			if (d.annotations.any { it.name == "DocumentationOnly" }) return
			if (d.returnType != null && d.returnType.isRawArray()) {
				codegenError("Function '${d.name}' cannot return raw array type '${d.returnType.name}'. Use @Ptr Array<T> or @Size(N) Array<T> instead")
				}
			if (d.returnType != null && d.returnType.name == "Any" && d.returnType.annotations.none { it.name == "Ptr" }) {
				codegenError("Function '${d.name}' cannot return value-type 'Any'. Use @Ptr Any instead")
				}
			val effectiveReturnType = d.returnType ?: d.body?.let { inferredTypeRef(inferBlockType(it)) }
			when {
				d.typeParams.isNotEmpty() -> {
					// Generic function template — store for monomorphization
					if (genericFunDecls.none { it === d }) genericFunDecls += d
					funSigs[d.name] = FunSig(d.params, effectiveReturnType)
					allGenericTypeParamNames += d.typeParams
					if (d.isInline && d.receiver != null) inlineExtFunDecls.getOrPut(d.name) { mutableListOf() }.add(d)
					if (d.isInline) inlineFunDecls.getOrPut(d.name) { mutableListOf() }.add(d)
					}
				d.receiver != null && d.receiver.typeArgs.any { it.name == "*" } -> {
					if (starExtFunDecls.none { it === d }) starExtFunDecls += d
					}
				d.receiver != null && d.receiver.typeArgs.isNotEmpty()
						&& (genericIfaceDecls.containsKey(d.receiver.name) || genericClassDecls.containsKey(d.receiver.name)) -> {
					if (starExtFunDecls.none { it === d }) starExtFunDecls += d
					}
				d.receiver != null -> {
					// Resolve dotted receiver (e.g. "SDL3.Window" → "SDL3$Window", also for nested objects)
					val vFlatRecv = d.receiver.name.replace('.', '$')
					val recvName  = if (classes.containsKey(vFlatRecv) || objects.containsKey(vFlatRecv)) vFlatRecv else d.receiver.name
					extensionFuns.getOrPut(recvName) { mutableListOf() }.add(d)
					classes[recvName]?.methods?.add(d)
					funSigs["${recvName}.${d.name}"] = FunSig(d.params, effectiveReturnType)
					if (d.isInline || d.isInfix) inlineExtFunDecls.getOrPut(d.name) { mutableListOf() }.add(d)
					}
				else -> {
					funSigs[d.name] = FunSig(d.params, effectiveReturnType)
					if (d.isInline) inlineFunDecls.getOrPut(d.name) { mutableListOf() }.add(d)
					}
				}
			}

		is PropDecl -> {
			if (d.receiver != null) {
				val vFlatRecv = d.receiver.name.replace('.', '$')
				val recvName = if (classes.containsKey(vFlatRecv) || objects.containsKey(vFlatRecv)) vFlatRecv else d.receiver.name
				extensionProps.getOrPut(recvName) { mutableListOf() }.add(d)
				} else {
				topProps.add(d.name)
				if (!d.mutable) valTopProps.add(d.name)
				if (d.annotations.any { it.name == "Tls" }) tlsProps.add(d.name)
				}
			}
		}
	}

/* Returns the method name with overload type suffixes when multiple methods share the same name. */
internal fun CCodeGen.methodName(f: FunDecl, siblings: List<FunDecl>): String {
	val base      = f.name
	val overloads = siblings.filter { it.name == base }
	if (overloads.size <= 1) return base
	val types = f.params.map { resolveTypeName(it.type).toInternalStr.removeSuffix("*") }
	if (types.isEmpty()) return base   // no-arg keeps plain name
	return "${base}With${types.joinToString("_")}"
	}

/* Returns the overloaded method name with PRIV_ prefix for private methods. */
internal fun CCodeGen.resolvedFnName(f: FunDecl, siblings: List<FunDecl>): String {
	val ov = methodName(f, siblings)
	return if (f.isPrivate) "PRIV_$ov" else ov
	}

/* Find the best-matching overloaded declaration from siblings for the given call args.
Returns the first match by arg count, then by KtcType structural equality. */
internal fun CCodeGen.findOverload(inName: String, inArgs: List<Arg>, inSiblings: List<FunDecl>): FunDecl? {
	val vCandidates = inSiblings.filter { it.name == inName }   // all methods with this name
	if (vCandidates.size <= 1) return vCandidates.firstOrNull()
	// Narrow by argument count (required params..total params)
	val vByCount = vCandidates.filter { inArgs.size in it.params.count { vP -> vP.default == null }..it.params.size }
	if (vByCount.size == 1) return vByCount[0]
	if (vByCount.isEmpty()) return vCandidates.firstOrNull()
	// Multiple same-count candidates: match by argument KtcType
	for (vCandidate in vByCount) {
		if (inArgs.size == vCandidate.params.size) {
			val vAllMatch = inArgs.zip(vCandidate.params).all { (vArg, vParam) ->
				val vArgKtc   = inferExprTypeKtc(vArg.expr) ?: KtcType.Prim(KtcType.PrimKind.Int)
				val vParamKtc = resolveTypeName(vParam.type)
				fun KtcType.core(): KtcType = when (this) {
					is KtcType.Nullable -> inner.core()
					is KtcType.Ptr      -> inner.core()
					else                -> this
					}
				val vArgCore   = vArgKtc.core()
				val vParamCore = vParamKtc.core()
				vArgCore == vParamCore || (vParamCore is KtcType.Any)
				}
			if (vAllMatch) return vCandidate
			}
		}
	return vByCount.firstOrNull()
	}
