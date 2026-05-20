package com.bitsycore.ktc.codegen.emit

import com.bitsycore.ktc.ast.*
import com.bitsycore.ktc.codegen.*
import com.bitsycore.ktc.codegen.expr.*

// class / data class — primary class emit, secondary constructors

internal fun CCodeGen.emitClass(d: ClassDecl) {
	val ci = classes[d.name]!!
	val cName = ci.flatName
	val kind = if (d.isData) "data class" else "class"
	val vOptName = "${cName}\$Opt"

	hdr.appendLine(classBlockHeader(kind, d.name.replace('$', '.'), d.typeParams, d.superInterfaces, file.pkg ?: "", currentSourceFile, cName))
	hdr.appendLine("#define KTC_TYPE_NAME $cName")
	hdr.appendLine("#define KTC_OPT_TYPE_NAME $vOptName")
	hdr.appendLine("KTC_TYPE_ID(${typeIds[d.name]!!})")
	hdr.appendLine()
	hdr.appendLine("KTC_CLASS(")
	emitStructFields(ci)
	hdr.appendLine(");")
	hdr.appendLine()

	if (d.name.contains('$')) {
		impl.appendLine(cSourceFileHeader(kind, d.name.replace('$', '.'), file.pkg ?: "", cName, currentSourceFile))
		impl.appendLine()
		}

	hdr.appendLine("// ════ constructors ════")
	impl.appendLine(boxSection("constructors"))
	impl.appendLine()
	emitConstructorBody(cName, ci)
	for (vSctor in d.secondaryCtors) emitSecondaryCtor(d.name, cName, vSctor)

	val vAnyMethodNames = setOf("dispose", "toString", "hashCode")

	// Build method → interface display string map and ordered interface list
	val vIfaceMethodToStr = mutableMapOf<String, String>()
	val vIfaceOrder = mutableListOf<String>()
	fun collectMethodsPerIface(ifaceRef: TypeRef) {
		val vIfaceName = resolveIfaceName(ifaceRef)
		val vIface = interfaces[vIfaceName] ?: return
		val vIfaceStr = typeRefToStr(ifaceRef)
		if (vIfaceStr !in vIfaceOrder) vIfaceOrder += vIfaceStr
		for (m in vIface.methods) if (m.name !in vIfaceMethodToStr) vIfaceMethodToStr[m.name] = vIfaceStr
		for (p in vIface.propDecls) if (p.name !in vIfaceMethodToStr) vIfaceMethodToStr[p.name] = vIfaceStr
		for (superRef in vIface.superInterfaces) collectMethodsPerIface(superRef)
		}
	for (vIfaceRef in d.superInterfaces) collectMethodsPerIface(vIfaceRef)

	val vMethodsByIface = linkedMapOf<String, MutableList<FunDecl>>()
	for (m in d.members) {
		if (m is FunDecl && m.receiver == null && m.name !in vAnyMethodNames)
			vMethodsByIface.getOrPut(vIfaceMethodToStr[m.name] ?: "") { mutableListOf() } += m
		}

	currentClass = d.name
	selfIsPointer = true
	pushScope()
	for ((name, type) in ci.props) {
		defineVarKtc(name, resolveTypeName(type))
		if (!ci.isValProp(name)) markMutable(name)
		}

	var vHasMethodSection = false
	for (vIfaceStr in vIfaceOrder) {
		val vMethods = vMethodsByIface[vIfaceStr] ?: continue
		if (!vHasMethodSection) impl.appendLine()
		impl.appendLine(boxSection("implements $vIfaceStr"))
		impl.appendLine()
		for (m in vMethods) emitMethod(d.name, m, suppressHdr = true, ifaceName = vIfaceStr)
		vHasMethodSection = true
		}
	val vOtherMethods = vMethodsByIface[""] ?: emptyList()
	if (vOtherMethods.isNotEmpty()) {
		if (!vHasMethodSection) impl.appendLine()
		impl.appendLine(boxSection("methods"))
		impl.appendLine()
		for (m in vOtherMethods) emitMethod(d.name, m, suppressHdr = false, ifaceName = "")
		vHasMethodSection = true
		}
	popScope()
	currentClass = null

	val vDeferredLines = deferredHdrLines.remove(d.name)
	if (d.superInterfaces.isNotEmpty()) {
		val vByIface = vDeferredLines?.groupBy { it.first } ?: emptyMap()
		for (vIfaceRef in d.superInterfaces) {
			val vIfaceName = resolveIfaceName(vIfaceRef)
			val vIface = interfaces[vIfaceName] ?: continue
			val vIfaceStr = typeRefToStr(vIfaceRef)
			val cIface = typeFlatName(vIfaceName)
			hdr.appendLine()
			hdr.appendLine("// ════ implements $vIfaceStr ════")
			val vLines = vByIface[vIfaceStr]
			if (vLines != null) for ((_, vLine) in vLines) hdr.appendLine(vLine)
			for (vProp in vIface.propDecls) {
				val vCt = if (vProp.type != null) cType(vProp.type) else "ktc_Int"
				hdr.appendLine("KTC_METHOD($vCt, ${vProp.name}_get)(KTC_TYPE_NAME* \$self);")
				}
			hdr.appendLine("extern const ${cIface}_vt KTC_RELATED(${vIfaceName}_vt);")
			hdr.appendLine("KTC_METHOD($cIface, as_${vIfaceName})(KTC_TYPE_NAME* \$self);")
			emitTransitiveIfaceHdrDecls(vIface, vByIface)
			}
		}

	hdr.appendLine()
	hdr.appendLine("// ════ implements Any (implicit) ════")
	if (!vHasMethodSection) impl.appendLine()
	impl.appendLine(boxSection("implements Any (implicit)"))
	impl.appendLine()
	emitClassEquals(cName, ci)
	if (d.isData) emitDataClassToString(d.name, cName, ci)
	if (d.members.none { it is FunDecl && it.name == "dispose" }) {
		if (disposedMode != "NO" || doubleDisposeMode != "NO") {
			hdr.appendLine("KTC_METHOD(void, dispose)(KTC_TYPE_NAME* \$self);")
			impl.appendLine("// ══ fun dispose() ══")
			impl.appendLine("void ${cName}_dispose($cName* \$self) { KTC_MARK_DISPOSED(\$self); }")
			impl.appendLine()
			} else {
			hdr.appendLine("#define ${cName}_dispose(self) ((void)(self))")
			}
		}
	emitImplicitHashCode(cName, ci, d.isData, isGenericClass = false, d.members)
	if (!d.isData && d.members.none { it is FunDecl && it.name == "toString" }) {
		emitDefaultToString(d.name, cName, ci)
		}
	// Emit explicit overrides of Any methods (dispose/toString/hashCode)
	pushScope()
	for ((name, type) in ci.props) {
		defineVarKtc(name, resolveTypeName(type))
		if (!ci.isValProp(name)) markMutable(name)
		}
	currentClass = d.name
	selfIsPointer = true
	for (m in d.members) {
		if (m is FunDecl && m.receiver == null && m.name in vAnyMethodNames) {
			emitMethod(d.name, m, suppressHdr = false, ifaceName = "")
			}
		}
	currentClass = null
	popScope()

	hdr.appendLine()
	hdr.appendLine("// ════ Any cast ════")
	emitAnyVtable(cName, ci.name, d.isData, d.members, isGenericClass = false)

	hdr.appendLine()
	hdr.appendLine("#undef KTC_TYPE_NAME")
	hdr.appendLine("#undef KTC_OPT_TYPE_NAME")
	hdr.appendLine(classBlockFooter(kind, d.name.replace('$', '.'), d.typeParams))
	}

/** Generate a secondary constructor function name: ClassName_constructorWithType1_Type2 */
internal fun CCodeGen.secondaryCtorName(inCClass: String, inParams: List<Param>): String {
	if (inParams.isEmpty()) return "${inCClass}_emptyConstructor"
	val vTypes = inParams.map { resolveTypeName(it.type).toInternalStr.removeSuffix("*") }
	return "${inCClass}_constructorWith${vTypes.joinToString("_")}"
	}

/** Emit a secondary constructor that delegates to the primary constructor. */
internal fun CCodeGen.emitSecondaryCtor(className: String, cClass: String, sctor: SecondaryCtor) {
	val ctorName = secondaryCtorName(cClass, sctor.params)
	val extraParams = expandParams(sctor.params)

	hdr.appendLine("$cClass $ctorName($extraParams);")
	impl.appendLine("$cClass $ctorName($extraParams) {")

	val delegateArgs = sctor.delegation.args.joinToString(", ") { a -> genExpr(a.expr) }
	flushPreStmts("    ")
	impl.appendLine("    $cClass \$self = ${cClass}_primaryConstructor($delegateArgs);")

	pushScope()
	currentClass = className
	selfIsPointer = true
	for (p in sctor.params) defineVarKtc(p.name, resolveTypeName(p.type))
	val ci = classes[className]
	if (ci != null) for ((name, type) in ci.props) defineVarKtc(name, resolveTypeName(type))

	for (s in sctor.body.stmts) emitStmt(s, "    ", true)
	popScope()
	currentClass = null

	impl.appendLine("    return \$self;")
	impl.appendLine("}")
	impl.appendLine()
	}
