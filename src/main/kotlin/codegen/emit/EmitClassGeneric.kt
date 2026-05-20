package com.bitsycore.ktc.codegen.emit

import com.bitsycore.ktc.ast.*
import com.bitsycore.ktc.codegen.*
import com.bitsycore.ktc.codegen.expr.*

// Generic class instantiation emit.

/*
Emit a concrete instantiation of a generic class.
typeSubst must be set before calling (e.g. {T → Int}).
mangledName is the concrete class name (e.g. "MyList_Int").
*/
internal fun CCodeGen.emitGenericClass(templateDecl: ClassDecl, mangledName: String) {
	val ci = classes[mangledName]!!
	val cName = ci.flatName
	val kind = if (templateDecl.isData) "data class" else "class"
	val (vGenBase, vTypeArgs) = mangledComponents[mangledName]!!
	val vGenOptName = genericOptionalCName(vGenBase, vTypeArgs)
	val vConcreteTypes = vTypeArgs.joinToString(", ")

	hdr.appendLine(classBlockHeader(kind, "${templateDecl.name.replace('$', '.')}<$vConcreteTypes>",
		emptyList(), templateDecl.superInterfaces, file.pkg ?: "", currentSourceFile, cName))
	hdr.appendLine("#define KTC_TYPE_NAME $cName")
	hdr.appendLine("#define KTC_OPT_TYPE_NAME $vGenOptName")
	hdr.appendLine("KTC_TYPE_ID(${typeIds[ci.name]!!})")
	hdr.appendLine()
	hdr.appendLine("KTC_CLASS(")
	emitStructFields(ci)
	hdr.appendLine(");")
	hdr.appendLine()

	hdr.appendLine("// ════ constructors ════")
	impl.appendLine(boxSection("constructors"))
	impl.appendLine()
	emitConstructorBody(cName, ci)
	for (vSctor in templateDecl.secondaryCtors) emitSecondaryCtor(mangledName, cName, vSctor)

	val vAnyMethodNamesGen = setOf("dispose", "toString", "hashCode")

	val vIfaceMethodToStrGen = mutableMapOf<String, String>()
	val vIfaceOrderGen = mutableListOf<String>()
	fun collectMethodsPerIfaceGen(ifaceRef: TypeRef) {
		val vIfaceName = resolveIfaceName(ifaceRef)
		val vIface = interfaces[vIfaceName] ?: return
		val vIfaceStr = typeRefToStr(ifaceRef)
		if (vIfaceStr !in vIfaceOrderGen) vIfaceOrderGen += vIfaceStr
		for (m in vIface.methods) if (m.name !in vIfaceMethodToStrGen) vIfaceMethodToStrGen[m.name] = vIfaceStr
		for (p in vIface.propDecls) if (p.name !in vIfaceMethodToStrGen) vIfaceMethodToStrGen[p.name] = vIfaceStr
		for (superRef in vIface.superInterfaces) collectMethodsPerIfaceGen(superRef)
		}
	for (vIfaceRef in templateDecl.superInterfaces) collectMethodsPerIfaceGen(vIfaceRef)

	val vMethodsByIfaceGen = linkedMapOf<String, MutableList<FunDecl>>()
	for (m in templateDecl.members) {
		if (m is FunDecl && m.receiver == null && m.name !in vAnyMethodNamesGen)
			vMethodsByIfaceGen.getOrPut(vIfaceMethodToStrGen[m.name] ?: "") { mutableListOf() } += m
		}

	currentClass = mangledName
	selfIsPointer = true
	pushScope()
	for ((name, type) in ci.props) {
		defineVarKtc(name, resolveTypeName(type))
		if (!ci.isValProp(name)) markMutable(name)
		}

	var vHasMethodSectionGen = false
	for (vIfaceStr in vIfaceOrderGen) {
		val vMethods = vMethodsByIfaceGen[vIfaceStr] ?: continue
		if (!vHasMethodSectionGen) impl.appendLine()
		impl.appendLine(boxSection("implements $vIfaceStr"))
		impl.appendLine()
		for (m in vMethods) emitMethod(mangledName, m, suppressHdr = true, ifaceName = vIfaceStr)
		vHasMethodSectionGen = true
		}
	val vOtherMethodsGen = vMethodsByIfaceGen[""] ?: emptyList()
	if (vOtherMethodsGen.isNotEmpty()) {
		if (!vHasMethodSectionGen) impl.appendLine()
		impl.appendLine(boxSection("methods"))
		impl.appendLine()
		for (m in vOtherMethodsGen) emitMethod(mangledName, m, suppressHdr = false, ifaceName = "")
		vHasMethodSectionGen = true
		}
	popScope()
	currentClass = null

	val vGenDeferredLines = deferredHdrLines.remove(mangledName)
	if (templateDecl.superInterfaces.isNotEmpty()) {
		val vByIface = vGenDeferredLines?.groupBy { it.first } ?: emptyMap()
		for (vIfaceRef in templateDecl.superInterfaces) {
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
	if (!vHasMethodSectionGen) impl.appendLine()
	impl.appendLine(boxSection("implements Any"))
	impl.appendLine()
	if (templateDecl.members.none { it is FunDecl && it.name == "dispose" }) {
		if (disposedMode != "NO" || doubleDisposeMode != "NO") {
			hdr.appendLine("KTC_METHOD(void, dispose)(KTC_TYPE_NAME* \$self);")
			impl.appendLine("// ══ fun dispose() ══")
			impl.appendLine("void ${cName}_dispose($cName* \$self) { KTC_MARK_DISPOSED(\$self); }")
			impl.appendLine()
			} else {
			hdr.appendLine("#define ${cName}_dispose(self) ((void)(self))")
			}
		}
	emitImplicitHashCode(cName, ci, templateDecl.isData, isGenericClass = true, templateDecl.members)
	if (templateDecl.members.none { it is FunDecl && it.name == "equals" }) emitClassEquals(cName, ci)
	if (templateDecl.isData && templateDecl.members.none { it is FunDecl && it.name == "toString" }) {
		emitDataClassToString(templateDecl.name, cName, ci)
		}
	if (!templateDecl.isData && templateDecl.members.none { it is FunDecl && it.name == "toString" }) {
		emitDefaultToString(ci.name, cName, ci)
		}
	// Emit explicit overrides of Any methods (dispose/toString/hashCode)
	pushScope()
	for ((name, type) in ci.props) {
		defineVarKtc(name, resolveTypeName(type))
		if (!ci.isValProp(name)) markMutable(name)
		}
	currentClass = mangledName
	selfIsPointer = true
	for (m in templateDecl.members) {
		if (m is FunDecl && m.receiver == null && m.name in vAnyMethodNamesGen) {
			emitMethod(mangledName, m, suppressHdr = false, ifaceName = "")
			}
		}
	currentClass = null
	popScope()

	hdr.appendLine()
	hdr.appendLine("// ════ Any cast ════")
	emitAnyVtable(cName, ci.name, templateDecl.isData, templateDecl.members, isGenericClass = true)

	hdr.appendLine()
	hdr.appendLine("#undef KTC_TYPE_NAME")
	hdr.appendLine("#undef KTC_OPT_TYPE_NAME")
	hdr.appendLine(classBlockFooter(kind, templateDecl.name.replace('$', '.'), vTypeArgs.map { it }))
	}
