package com.bitsycore.ktc.codegen.emit

import com.bitsycore.ktc.ast.ClassDecl
import com.bitsycore.ktc.ast.FunDecl
import com.bitsycore.ktc.ast.Param
import com.bitsycore.ktc.ast.SecondaryCtor
import com.bitsycore.ktc.codegen.*
import com.bitsycore.ktc.codegen.expression.genExpr
import com.bitsycore.ktc.codegen.statement.emitStmt

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

	val vHasMethodSection = emitClassNonAnyMethods(d.name, d.superInterfaces, d.members, ci)

	emitSuperInterfaceHdrDecls(d.superInterfaces, deferredHdrLines.remove(d.name))

	hdr.appendLine()
	hdr.appendLine("// ════ implements Any (implicit) ════")
	if (!vHasMethodSection) impl.appendLine()
	impl.appendLine(boxSection("implements Any (implicit)"))
	impl.appendLine()
	if (d.members.none { it is FunDecl && it.name == "equals" }) emitClassEquals(cName, ci)
	if (d.isData) emitDataClassToString(d.name, cName, ci)
	emitImplicitDispose(cName, d.members)
	emitImplicitHashCode(cName, ci, d.isData, isGenericClass = false, d.members)
	if (!d.isData && d.members.none { it is FunDecl && it.name == "toString" }) {
		emitDefaultToString(d.name, cName, ci)
		}
	// Emit explicit overrides of Any methods (dispose/toString/hashCode)
	emitClassAnyOverrides(d.name, d.members, ci)

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

	// Params must be in scope before evaluating delegation args so
	// expressions like `this(window.createRenderer())` can resolve types.
	val prevState = saveFunState()
	currentClass = className
	selfIsPointer = false
	pushScope()
	for (p in sctor.params) {
		val vKtcP = resolveTypeName(p.type)
		defineVar(p.name, LocalVar(ktc = vKtcP, mutable = true))
		}

	val delegateArgs = sctor.delegation.args.joinToString(", ") { a -> genExpr(a.expr) }
	flushPreStmts("    ")
	impl.appendLine("    $cClass \$self = ${cClass}_primaryConstructor($delegateArgs);")
	val ci = classes[className]
	if (ci != null) {
		for ((name, type) in ci.props) {
			if (scopes.last().containsKey(name)) continue // param takes priority
			val vKtc        = resolveTypeName(type)
			val vCFieldName = if (name in ci.privateProps) "PRIV_$name" else name
			val vIsOpt      = type.nullable && !type.annotations.any { it.name == "Ptr" } && !vKtc.isArrayLike
			defineVar(name, LocalVar(ktc = vKtc, mutable = !ci.isValProp(name), optional = vIsOpt, cName = "\$self.$vCFieldName"))
			}
		}

	for (s in sctor.body.stmts) emitStmt(s, "    ", true)
	popScope()
	restoreFunState(prevState)

	impl.appendLine("    return \$self;")
	impl.appendLine("}")
	impl.appendLine()
	}
