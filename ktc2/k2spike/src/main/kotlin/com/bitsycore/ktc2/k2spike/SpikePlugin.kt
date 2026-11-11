@file:OptIn(ExperimentalCompilerApi::class)

package com.bitsycore.ktc2.k2spike

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrFunctionExpression
import org.jetbrains.kotlin.ir.expressions.IrTypeOperatorCall
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrTypeProjection
import org.jetbrains.kotlin.ir.util.dump
import org.jetbrains.kotlin.ir.util.dumpKotlinLike
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.util.render
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid
import java.io.File

/** Registers [SpikeExtension] with the K2 compiler. */
class SpikeRegistrar : CompilerPluginRegistrar() {

	override val pluginId: String = "ktc2.k2spike"

	override val supportsK2: Boolean = true

	override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
		IrGenerationExtension.registerExtension(SpikeExtension())
	}
}

/**
 * Receives the module IR right after FIR→IR (before any JVM lowering) and writes what a C
 * backend would have to work with: the IR itself, the external callees and the `ktc2.`
 * type annotations that survived.
 */
class SpikeExtension : IrGenerationExtension {

	override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
		val vOutDir = File(System.getProperty(OUT_DIR_PROPERTY))
		File(vOutDir, "ir.txt").writeText(moduleFragment.dump())
		File(vOutDir, "ir.kt.txt").writeText(moduleFragment.dumpKotlinLike())

		val vCollector = Collector()
		moduleFragment.acceptVoid(vCollector)
		File(vOutDir, "report.txt").writeText(vCollector.report())
	}
}

/** Walks the IR and gathers the facts the spike is about. */
private class Collector : IrVisitorVoid() {

	private val mExternalWithBody = sortedMapOf<String, Int>()
	private val mExternalNoBody = sortedMapOf<String, Int>()
	private val mLocalCalls = sortedMapOf<String, Int>()
	private val mAnnotatedTypes = sortedSetOf<String>()
	private val mTypeOperators = sortedMapOf<String, Int>()
	private var mLambdas = 0

	override fun visitElement(element: org.jetbrains.kotlin.ir.IrElement) {
		element.acceptChildrenVoid(this)
	}

	override fun visitCall(expression: IrCall) {
		classify(expression.symbol.owner)
		super.visitCall(expression)
	}

	override fun visitConstructorCall(expression: IrConstructorCall) {
		classify(expression.symbol.owner)
		super.visitConstructorCall(expression)
	}

	override fun visitFunctionExpression(expression: IrFunctionExpression) {
		mLambdas++
		super.visitFunctionExpression(expression)
	}

	override fun visitTypeOperator(expression: IrTypeOperatorCall) {
		mTypeOperators.merge(expression.operator.name, 1, Int::plus)
		super.visitTypeOperator(expression)
	}

	override fun visitField(declaration: IrField) {
		recordType("field ${declaration.name}", declaration.type)
		super.visitField(declaration)
	}

	override fun visitValueParameter(declaration: IrValueParameter) {
		recordType("param ${declaration.name}", declaration.type)
		super.visitValueParameter(declaration)
	}

	override fun visitVariable(declaration: IrVariable) {
		recordType("local ${declaration.name}", declaration.type)
		super.visitVariable(declaration)
	}

	/** Sorts a callee into "declared in this module" / "library with IR body" / "library, no body". */
	private fun classify(inCallee: IrFunction) {
		val vName = inCallee.kotlinFqName.asString() + if (inCallee.isInline) " [inline]" else ""
		val vIsExternal = inCallee.origin.toString().contains("EXTERNAL") || inCallee.origin.toString().contains("LIB")
		val vTarget = when {
			!vIsExternal -> mLocalCalls
			inCallee.body != null -> mExternalWithBody
			else -> mExternalNoBody
		}
		vTarget.merge(vName, 1, Int::plus)
	}

	/** Records every annotated type reachable from [inType] (the type itself or its arguments). */
	private fun recordType(inWhere: String, inType: IrType) {
		if (inType.annotations.isNotEmpty()) mAnnotatedTypes += "$inWhere : ${inType.render()}"
		if (inType is IrSimpleType) {
			for (vArgument in inType.arguments) {
				if (vArgument is IrTypeProjection) recordType(inWhere, vArgument.type)
			}
		}
	}

	fun report(): String = buildString {
		appendLine("== ktc2 type annotations visible in IR ==")
		mAnnotatedTypes.forEach { appendLine("  $it") }
		appendLine("== lambdas (IrFunctionExpression): $mLambdas ==")
		appendLine("== type operators ==")
		mTypeOperators.forEach { (vKey, vCount) -> appendLine("  $vKey x$vCount") }
		appendLine("== callees declared in the module ==")
		mLocalCalls.forEach { (vKey, vCount) -> appendLine("  $vKey x$vCount") }
		appendLine("== library callees WITH an IR body ==")
		mExternalWithBody.forEach { (vKey, vCount) -> appendLine("  $vKey x$vCount") }
		appendLine("== library callees WITHOUT an IR body ==")
		mExternalNoBody.forEach { (vKey, vCount) -> appendLine("  $vKey x$vCount") }
	}
}
