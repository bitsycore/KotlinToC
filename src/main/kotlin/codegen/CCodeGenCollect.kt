package com.bitsycore.ktc.codegen

import com.bitsycore.ktc.ast.*
import com.bitsycore.ktc.codegen.emit.collectAllIfaceMethods
import com.bitsycore.ktc.codegen.expression.inferBlockType
import com.bitsycore.ktc.codegen.statement.inferInitType
import com.bitsycore.ktc.types.KtcType

/* Kotlin operator-function arity table. Maps the operator method name to the
set of valid parameter counts (the receiver is implicit, so a binary infix
operator like `plus` takes ONE explicit parameter). Operators not listed
either have variable arity (get/set/invoke) or are accepted as-is. */
private val kOperatorArity: Map<String, Set<Int>> = mapOf(
	// Binary arithmetic — exactly one operand
	"plus" to setOf(1), "minus" to setOf(1), "times" to setOf(1),
	"div"  to setOf(1), "rem"   to setOf(1), "mod"   to setOf(1),
	// Unary — no operand
	"unaryPlus" to setOf(0), "unaryMinus" to setOf(0), "not" to setOf(0),
	"inc"       to setOf(0), "dec"        to setOf(0),
	// Range operators take exactly one operand
	"rangeTo" to setOf(1), "rangeUntil" to setOf(1),
	// `a in b` → b.contains(a); always one explicit param
	"contains" to setOf(1),
	// Comparison: a.compareTo(b)
	"compareTo" to setOf(1),
	// Iterator protocol: no args
	"iterator" to setOf(0), "hasNext" to setOf(0), "next" to setOf(0),
	// Compound-assignment helpers take one operand
	"plusAssign" to setOf(1), "minusAssign" to setOf(1),
	"timesAssign" to setOf(1), "divAssign" to setOf(1), "remAssign" to setOf(1),
	// `==` lowers to equals(other: Any?). One operand.
	"equals" to setOf(1),
	// `get`, `set`, and `invoke` accept multiple — checked elsewhere if at all.
)

/* Returns true if every yielded String value in the body is a bare string
literal. Such a function is safe to return value-type String — the literal's
bytes live in .rodata, not in the callee's frame. */
private fun returnsOnlyStringLiterals(inBody: Block): Boolean {
	// A "yield" is either a ReturnStmt value or the tail expression of an
	// expression-body function (parser emits it as ExprStmt at the tail).
	fun isLiteralYield(e: Expr): Boolean {
		// Use a small worklist to avoid mutual recursion (Kotlin forbids forward refs).
		val vStack = ArrayDeque<Expr>()
		vStack.addLast(e)
		while (vStack.isNotEmpty()) {
			when (val vCur = vStack.removeLast()) {
				is StrLit -> {}
				is IfExpr -> {
					val vLastThen = vCur.then.stmts.lastOrNull()
					if (vLastThen is ReturnStmt && vLastThen.value != null) vStack.addLast(vLastThen.value)
					else if (vLastThen is ExprStmt) vStack.addLast(vLastThen.expr)
					if (vCur.els != null) {
						val vLastEls = vCur.els.stmts.lastOrNull()
						if (vLastEls is ReturnStmt && vLastEls.value != null) vStack.addLast(vLastEls.value)
						else if (vLastEls is ExprStmt) vStack.addLast(vLastEls.expr)
						}
					}
				is WhenExpr -> for (b in vCur.branches) {
					val vLastB = b.body.stmts.lastOrNull()
					if (vLastB is ReturnStmt && vLastB.value != null) vStack.addLast(vLastB.value)
					else if (vLastB is ExprStmt) vStack.addLast(vLastB.expr)
					}
				else -> return false
				}
			}
		return true
		}
	for (s in inBody.stmts) {
		if (s is ReturnStmt && s.value != null && !isLiteralYield(s.value)) return false
		}
	val vLast = inBody.stmts.lastOrNull()
	if (vLast is ExprStmt && !isLiteralYield(vLast.expr)) return false
	return true
	}

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
					codegenError("Class property '${p.name}' cannot have raw array type '${p.type.name}'. Use Ref<Array<T>> or @Size(N) Array<T> instead")
					}
				}
			for (p in d.members.filterIsInstance<PropDecl>()) {
				val propType = p.type ?: inferInitType(p.init)
				if (propType.isRawArray()) {
					currentStmtLine = p.line
					codegenError("Class property '${p.name}' cannot have raw array type '${propType.name}'. Use Ref<Array<T>> or @Size(N) Array<T> instead")
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
			// Direct recursive self-reference without Ref/array indirection produces
			// an infinite-size struct in C. The user must indirect through Ref<T>,
			// Array<T> (VarArr), or RawArray<T> to break the size cycle.
			for (vProp in vAllProps) {
				val vT = vProp.typeRef ?: continue
				if (vT.name == d.name && !vT.isRefType() && vT.name != "Array" && vT.name != "RawArray") {
					codegenError("Class '${d.name}' has property '${vProp.name}' of its own type — infinite struct size. " +
						"Use Ref<${d.name}> (pointer), Array<${d.name}>, or RawArray<${d.name}> to indirect.")
				}
			}
			if (d.isValue) {
				val vValProps = vCtorProps.filter { it.isVal }
				if (vValProps.size != 1)
					codegenError("Value class '${d.name}' must have exactly one val property in its constructor")
				if (vBodyProps.isNotEmpty())
					codegenError("Value class '${d.name}' cannot have body properties")
			}
			val ci = ClassInfo(d.name, d.isData, vAllProps, vCtorPlainParams, initBlocks = d.initBlocks, typeParams = d.typeParams, isValue = d.isValue)
			if (d.typeParams.isNotEmpty()) allGenericTypeParamNames += d.typeParams
			for (m in d.members) if (m is FunDecl && m.receiver == null) {
				// Mirror the function-level rule: inline methods may return bare
				// Array<T> since the body's stack array lands in the caller's frame.
				if (m.returnType != null && m.returnType.isRawArray() && !m.isInline) {
					codegenError("Method '${m.name}' cannot return raw array type '${m.returnType.name}'. Use Ref<Array<T>>, @Size(N) Array<T>, or mark the method `inline`.")
					}
				if (m.isOperator) {
					val vExpected = kOperatorArity[m.name]
					if (vExpected != null && m.params.size !in vExpected) {
						codegenError("Operator function '${m.name}' has wrong arity (${m.params.size}); " +
							"valid arity for '${m.name}' is ${vExpected.joinToString(" or ")}.")
					}
				}
				ci.methods += m
				}
			classes[d.name] = ci
			allClassDecls[d.name] = d
			getTypeId(d.name)
			if (d.typeParams.isNotEmpty()) genericClassDecls[d.name] = d
			if (d.superInterfaces.isNotEmpty()) classInterfaces[d.name] = d.superInterfaces.map { it.name }
			// Verify all interface methods are implemented with override (current file, non-stdlib)
			if (validate && file.pkg != "ktc.std") {
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
					codegenError("Object property '${p.name}' cannot have raw array type '${propType.name}'. Use Ref<Array<T>> or @Size(N) Array<T> instead")
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
				// Mirror the function-level rule: inline methods may return bare
				// Array<T> since the body's stack array lands in the caller's frame.
				if (m.returnType != null && m.returnType.isRawArray() && !m.isInline) {
					codegenError("Method '${m.name}' cannot return raw array type '${m.returnType.name}'. Use Ref<Array<T>>, @Size(N) Array<T>, or mark the method `inline`.")
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
			if (validate && file.pkg != "ktc.std") {
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
			// Normalize Ref<T> in receiver and params to @Ptr T so all downstream code uses the uniform form
			val needsRecvNorm = d.receiver != null && d.receiver.name == "Ref" && d.receiver.typeArgs.isNotEmpty()
			val needsParamNorm = d.params.any { it.type.name == "Ref" && it.type.typeArgs.isNotEmpty() }
			if (needsRecvNorm || needsParamNorm) {
				val normRecv = if (needsRecvNorm) d.receiver!!.normalizeRef() else d.receiver
				val normParams = if (needsParamNorm) d.params.map {
					if (it.type.name == "Ref" && it.type.typeArgs.isNotEmpty()) it.copy(type = it.type.normalizeRef())
					else it
				} else d.params
				collectDecl(d.copy(receiver = normRecv, params = normParams))
				return
			}
			// Bare Array<T> returns dangle the same way bare String returns do —
			// the underlying buffer lives in the callee's frame and dies at exit.
			// Inline functions are exempt (body is expanded into the caller's
			// frame, so the array storage lives in scope long enough for the
			// caller to consume it). Mirrors the String return rule.
			if (d.returnType != null && d.returnType.isRawArray() && !d.isInline) {
				codegenError("Function '${d.name}' cannot return raw array type '${d.returnType.name}'. Use Ref<Array<T>>, @Size(N) Array<T>, or mark the function `inline`.")
				}
			if (d.returnType != null && d.returnType.name == "Any" && !d.returnType.isRefType()) {
				codegenError("Function '${d.name}' cannot return value-type 'Any'. Use Ref<Any> instead")
				}
			// Bare String returns are unsafe outside inline functions and toString overrides:
			// the String's internal buffer would live in the callee's frame and become a
			// dangling reference after return. Require Ref<String> or @Size(N) String, OR
			// mark the function inline (the buffer lives in the caller's frame via alloca).
			if (d.returnType != null && d.returnType.name == "String"
				&& !d.returnType.nullable
				&& d.body != null
				&& !d.returnType.isRefType() && !d.returnType.hasSizeAnnotation()
				&& !d.isInline && d.name != "toString"
				&& !returnsOnlyStringLiterals(d.body)
			) {
				codegenError("Function '${d.name}' cannot return value-type 'String' " +
					"(its internal buffer would die at function exit). " +
					"Use one of: Ref<String>, @Size(N) String, or mark the function `inline`.")
				}
			// Lambda escape: non-inline functions can't return function types.
			// KTC has no closure/heap-capture machinery, so the lambda would
			// only see captured locals on the dead caller frame. Require
			// inline so the function body is expanded into the caller.
			if (d.returnType != null && d.returnType.funcParams != null && !d.isInline) {
				codegenError("Function '${d.name}' returns a function type (lambda) — not supported outside inline functions. " +
					"Mark '${d.name}' as `inline` so its body is expanded at the call site (KTC has no closure heap allocation).")
			}
			// inline + vararg: inline expansion doesn't reify the vararg array on
			// a frame the body can scan over — KTC's vararg lowering needs a real
			// stack-allocated slot per arg group. The combination silently
			// produces incorrect C.
			if (d.isInline && d.params.any { it.isVararg }) {
				codegenError("Function '${d.name}' cannot be both 'inline' and have a vararg parameter — KTC's inline expansion has no stack frame to reify the vararg array.")
			}
			// Operator function arity: Kotlin pins operator-name arities, and a
			// mismatched signature would produce a method that's never callable
			// via the operator syntax (and confuses overload resolution).
			if (d.isOperator) {
				val vArity = d.params.size
				val vExpected = kOperatorArity[d.name]
				if (vExpected != null && vArity !in vExpected) {
					codegenError("Operator function '${d.name}' has wrong arity ($vArity); " +
						"valid arity for '${d.name}' is ${vExpected.joinToString(" or ")}.")
				}
			}
			val effectiveReturnType = d.returnType ?: d.body?.let { inferredTypeRef(inferBlockType(it)) }
			when {
				d.typeParams.isNotEmpty() -> {
					// Generic function template — store for monomorphization
					if (genericFunDecls.none { it == d }) genericFunDecls += d
					funSigs[d.name] = FunSig(d.params, effectiveReturnType)
					allGenericTypeParamNames += d.typeParams
					if (d.isInline && d.receiver != null) inlineExtFunDecls.getOrPut(d.name) { mutableListOf() }.add(d)
					if (d.isInline) inlineFunDecls.getOrPut(d.name) { mutableListOf() }.add(d)
					}
				d.receiver != null && d.receiver.typeArgs.any { it.name == "*" } -> {
					if (starExtFunDecls.none { it == d }) starExtFunDecls += d
					}
				d.receiver != null && d.receiver.typeArgs.isNotEmpty()
						&& (genericIfaceDecls.containsKey(d.receiver.name) || genericClassDecls.containsKey(d.receiver.name)) -> {
					if (starExtFunDecls.none { it == d }) starExtFunDecls += d
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
				topPropDecls[d.name] = d
				if (!d.mutable) valTopProps.add(d.name)
				if (d.lazyInit != null) lazyTopProps.add(d.name)
				if (d.annotations.any { it.name == "Tls" }) tlsProps.add(d.name)
				}
			}

		is TypeAliasDecl -> {
			// Register the alias for later substitution at type-resolution sites.
			// Refuse re-declaration so accidental shadowing produces a clear error.
			val vExisting = typeAliases[d.name]
			if (vExisting != null && vExisting != d.target) {
				codegenError("typealias '${d.name}' is declared more than once with different targets")
			}
			typeAliases[d.name] = d.target
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
