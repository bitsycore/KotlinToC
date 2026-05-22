package com.bitsycore.ktc.codegen.emit

import com.bitsycore.ktc.ast.ClassDecl
import com.bitsycore.ktc.ast.FunDecl
import com.bitsycore.ktc.codegen.CCodeGen
import com.bitsycore.ktc.codegen.boxSection
import com.bitsycore.ktc.codegen.classBlockFooter
import com.bitsycore.ktc.codegen.classBlockHeader

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

	val vHasMethodSectionGen = emitClassNonAnyMethods(mangledName, templateDecl.superInterfaces, templateDecl.members, ci)

	emitSuperInterfaceHdrDecls(templateDecl.superInterfaces, deferredHdrLines.remove(mangledName))

	hdr.appendLine()
	hdr.appendLine("// ════ implements Any (implicit) ════")
	if (!vHasMethodSectionGen) impl.appendLine()
	impl.appendLine(boxSection("implements Any"))
	impl.appendLine()
	emitImplicitDispose(cName, templateDecl.members)
	emitImplicitHashCode(cName, ci, templateDecl.isData, isGenericClass = true, templateDecl.members)
	if (templateDecl.members.none { it is FunDecl && it.name == "equals" }) emitClassEquals(cName, ci)
	if (templateDecl.isData && templateDecl.members.none { it is FunDecl && it.name == "toString" }) {
		emitDataClassToString(templateDecl.name, cName, ci)
		}
	if (!templateDecl.isData && templateDecl.members.none { it is FunDecl && it.name == "toString" }) {
		emitDefaultToString(ci.name, cName, ci)
		}
	// Emit explicit overrides of Any methods (dispose/toString/hashCode)
	emitClassAnyOverrides(mangledName, templateDecl.members, ci)

	hdr.appendLine()
	hdr.appendLine("// ════ Any cast ════")
	emitAnyVtable(cName, ci.name, templateDecl.isData, templateDecl.members, isGenericClass = true)

	hdr.appendLine()
	hdr.appendLine("#undef KTC_TYPE_NAME")
	hdr.appendLine("#undef KTC_OPT_TYPE_NAME")
	hdr.appendLine(classBlockFooter(kind, templateDecl.name.replace('$', '.'), vTypeArgs.map { it }))
	}
