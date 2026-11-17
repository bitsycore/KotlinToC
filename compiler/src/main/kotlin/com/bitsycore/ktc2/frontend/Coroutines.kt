package com.bitsycore.ktc2.frontend

import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrBlockBody
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrContainerExpression
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrExpressionBody
import org.jetbrains.kotlin.ir.expressions.IrFunctionExpression
import org.jetbrains.kotlin.ir.expressions.IrLoop
import org.jetbrains.kotlin.ir.expressions.IrReturn
import org.jetbrains.kotlin.ir.expressions.IrTry
import org.jetbrains.kotlin.ir.expressions.IrTypeOperator
import org.jetbrains.kotlin.ir.expressions.IrTypeOperatorCall
import org.jetbrains.kotlin.ir.expressions.IrWhen
import org.jetbrains.kotlin.ir.symbols.IrReturnTargetSymbol
import org.jetbrains.kotlin.ir.types.isUnit
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid

/*
What the importer needs to know of `suspend` functions before it lowers one: whether it can do
without a state machine. A function whose every suspension is its last action keeps nothing while
it waits: it passes its own completion on, and returns `COROUTINE_SUSPENDED` when the function it
called does (the JVM and Kotlin/Native call that a tail call). The others are state machines.
*/

/** `suspendCoroutineUninterceptedOrReturn`, which the compiler implements: its block is handed the continuation. */
internal fun isSuspendIntrinsic(inFunction: IrFunction): Boolean =
	inFunction.kotlinFqName.asString() == "kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn"

/** The getter of `coroutineContext`, which the compiler implements: the context of the continuation. */
internal fun isContextIntrinsic(inFunction: IrFunction): Boolean =
	inFunction.kotlinFqName.asString() == "kotlin.coroutines.<get-coroutineContext>"

/** The arity of `kotlin.coroutines.SuspendFunctionN` when [inClass] is one, else null. */
internal fun suspendFunctionArity(inClass: IrClass?): Int? {
	val vName = inClass?.kotlinFqName?.asString() ?: return null
	if (!vName.startsWith("kotlin.coroutines.SuspendFunction")) return null
	return vName.removePrefix("kotlin.coroutines.SuspendFunction").toIntOrNull()
}

/** True when a call to [inFunction] may suspend the caller: a `suspend` function, not expanded in place, or the intrinsic. */
internal fun suspendsAtCall(inFunction: IrSimpleFunction): Boolean =
	isSuspendIntrinsic(inFunction) || (inFunction.isSuspend && !isContextIntrinsic(inFunction) && !isExpanded(inFunction))

/**
 * True when every suspension of [inFunction] is the last thing it does: its value is what the
 * function returns, as it is (a `Unit` function may also end with a call that returns `Unit`).
 * A suspension inside a `try`, a loop or a lambda expanded in place counts as not the last.
 * [inSourceOf] gives the body of a function of the library expanded in place.
 */
internal fun suspendsOnlyAtTail(inFunction: IrSimpleFunction, inSourceOf: (IrSimpleFunction) -> IrSimpleFunction): Boolean {
	val vCheck = TailCheck(inSourceOf)
	vCheck.body(inFunction, true)
	return vCheck.isTail
}

private class TailCheck(private val mSourceOf: (IrSimpleFunction) -> IrSimpleFunction) {
	var isTail = true

	/** The functions whose `return` is the end of the function checked, and the expansions inside it that are in its last place. */
	private val mTailTargets = HashSet<IrReturnTargetSymbol>()

	/** Inline functions being expanded: a recursion through them would not end. */
	private val mExpanding = HashSet<IrFunction>()

	fun body(inFunction: IrFunction, inIsTail: Boolean) {
		val vAdded = inIsTail && mTailTargets.add(inFunction.symbol)
		when (val vBody = inFunction.body) {
			is IrBlockBody -> vBody.statements.forEachIndexed { vIndex, vStatement ->
				val vIsLast = vIndex == vBody.statements.size - 1
				// The last statement of a `Unit` function is its last action: a call there that returns `Unit` gives the result.
				if (vStatement is IrExpression) expression(vStatement, inIsTail && vIsLast && inFunction.returnType.isUnit()) else other(vStatement)
			}
			is IrExpressionBody -> expression(vBody.expression, inIsTail)
			else -> {}
		}
		if (vAdded) mTailTargets.remove(inFunction.symbol)
	}

	/** A declaration: what it computes is not the result. A function or class declared here is a function of its own. */
	private fun other(inElement: IrElement) {
		when (inElement) {
			is IrVariable -> inElement.initializer?.let { expression(it, false) }
			is IrFunction, is IrClass -> {}
			else -> inElement.acceptChildrenVoid(Visitor())
		}
	}

	private inner class Visitor : IrVisitorVoid() {
		override fun visitElement(element: IrElement) {
			if (element is IrExpression) expression(element, false) else other(element)
		}
	}

	private fun expression(inExpression: IrExpression, inIsTail: Boolean) {
		if (!isTail) return
		when (inExpression) {
			is IrCall -> call(inExpression, inIsTail)
			is IrReturn -> expression(inExpression.value, inExpression.returnTargetSymbol in mTailTargets)
			is IrTypeOperatorCall -> when (inExpression.operator) {
				IrTypeOperator.IMPLICIT_CAST, IrTypeOperator.IMPLICIT_NOTNULL, IrTypeOperator.CAST -> expression(inExpression.argument, inIsTail)
				IrTypeOperator.IMPLICIT_COERCION_TO_UNIT -> expression(inExpression.argument, inIsTail && inExpression.argument.type.isUnit())
				else -> expression(inExpression.argument, false)
			}
			is IrWhen -> inExpression.branches.forEach { vBranch ->
				expression(vBranch.condition, false)
				expression(vBranch.result, inIsTail)
			}
			is IrContainerExpression -> inExpression.statements.forEachIndexed { vIndex, vStatement ->
				val vIsLast = vIndex == inExpression.statements.size - 1
				if (vStatement is IrExpression) expression(vStatement, inIsTail && vIsLast) else other(vStatement)
			}
			// A lambda that is a value is a function of its own.
			is IrFunctionExpression -> {}
			is IrTry, is IrLoop -> inExpression.acceptChildrenVoid(Visitor())
			else -> inExpression.acceptChildrenVoid(Visitor())
		}
	}

	private fun call(inCall: IrCall, inIsTail: Boolean) {
		val vCallee = mSourceOf(inCall.symbol.owner)
		// The lambdas a call expands in place run in the middle of it: not the last action.
		for (vArgument in inCall.arguments) {
			when (vArgument) {
				null -> {}
				is IrFunctionExpression -> if (isExpanded(vCallee) || isSuspendIntrinsic(vCallee)) body(vArgument.function, false)
				else -> expression(vArgument, false)
			}
		}
		when {
			isSuspendIntrinsic(vCallee) || isContextIntrinsic(vCallee) -> if (isSuspendIntrinsic(vCallee) && !inIsTail) isTail = false
			vCallee.isSuspend && isExpanded(vCallee) -> {
				if (!mExpanding.add(vCallee)) {
					isTail = false
					return
				}
				body(vCallee, inIsTail)
				mExpanding.remove(vCallee)
			}
			vCallee.isSuspend -> if (!inIsTail) isTail = false
			isExpanded(vCallee) -> {
				// An inline function that is not `suspend` may still suspend in the lambdas it expands: those were looked at above.
				if (!mExpanding.add(vCallee)) return
				body(vCallee, false)
				mExpanding.remove(vCallee)
			}
		}
	}
}
