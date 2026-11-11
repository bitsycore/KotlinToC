package com.bitsycore.ktc2.frontend

import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrMemberAccessExpression
import org.jetbrains.kotlin.ir.expressions.IrTypeOperatorCall
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.util.getPackageFragment
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid
import java.io.File

/**
 * As in Kotlin/Native, a program has no JDK: nothing of `java.*` or `javax.*`. The Kotlin
 * compiler ktc2 runs is the JVM one and cannot be told so (it needs the JDK for the built-in
 * types), so the uses are looked for here, in the files of the program, and reported.
 *
 * What ktc2's platform layer itself declares under `java.lang` is compiled from source and is
 * not the JDK: those declarations are not reported.
 */
class JavaUseCheck(private val mLibraryFiles: Set<String>) {

	val errors = ArrayList<String>()

	fun check(inFiles: List<IrFile>) {
		for (vFile in inFiles) {
			if (File(vFile.fileEntry.name).absoluteFile.invariantSeparatorsPath in mLibraryFiles) continue
			vFile.acceptVoid(Visitor(vFile))
		}
	}

	private inner class Visitor(private val mFile: IrFile) : IrVisitorVoid() {
		private val mReported = HashSet<String>()

		override fun visitElement(element: IrElement) {
			element.acceptChildrenVoid(this)
		}

		override fun visitMemberAccess(expression: IrMemberAccessExpression<*>) {
			(expression.symbol.owner as? IrDeclaration)?.let { report(expression, it) }
			super.visitMemberAccess(expression)
		}

		override fun visitTypeOperator(expression: IrTypeOperatorCall) {
			report(expression, expression.typeOperand)
			super.visitTypeOperator(expression)
		}

		override fun visitVariable(declaration: IrVariable) {
			report(declaration, declaration.type)
			super.visitVariable(declaration)
		}

		override fun visitFunction(declaration: IrFunction) {
			// A member inherited from a built-in type (`Enum`, `Throwable`, `Iterable`) is not something the program wrote.
			if (declaration.origin == IrDeclarationOrigin.FAKE_OVERRIDE || declaration.startOffset < 0) return
			declaration.parameters.forEach { report(it, it.type) }
			report(declaration, declaration.returnType)
			super.visitFunction(declaration)
		}

		override fun visitClass(declaration: IrClass) {
			declaration.superTypes.forEach { report(declaration, it) }
			super.visitClass(declaration)
		}

		private fun report(inElement: IrElement, inType: IrType) {
			inType.classOrNull?.owner?.let { report(inElement, it) }
		}

		private fun report(inElement: IrElement, inDeclaration: IrDeclaration) {
			val vFragment = runCatching { inDeclaration.getPackageFragment() }.getOrNull() ?: return
			val vPackage = vFragment.packageFqName.asString()
			// A declaration with a file is compiled from source: the platform layer's, not the JDK's.
			if (vFragment is IrFile || !(vPackage == "java" || vPackage.startsWith("java.") || vPackage.startsWith("javax."))) return
			val vOwner = generateSequence(inDeclaration) { it.parent as? IrDeclaration }.filterIsInstance<IrClass>().lastOrNull()
			val vName = vOwner?.kotlinFqName?.asString() ?: vPackage
			if (inElement.startOffset < 0) return
			val vLine = mFile.fileEntry.getLineNumber(inElement.startOffset) + 1
			if (mReported.add("$vLine $vName")) {
				errors += "${File(mFile.fileEntry.name).name}:$vLine: '$vName' is not available: a program has no JDK, as in Kotlin/Native"
			}
		}
	}
}
