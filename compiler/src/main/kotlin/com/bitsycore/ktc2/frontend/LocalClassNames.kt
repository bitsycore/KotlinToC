package com.bitsycore.ktc2.frontend

import org.jetbrains.kotlin.backend.common.lower.InventNamesForLocalClasses
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.packagePartClassName
import org.jetbrains.kotlin.ir.util.isAnonymousObject

/**
 * The names Kotlin/Native gives local classes and object expressions, which their default `toString()` shows
 * (`pkg.Outer$function$Local`, `pkg.Outer$function$variable$1`, `pkg.function$2`): the compiler's own lowering, as
 * Native's `NativeInventNamesForLocalClasses` makes it, with the names kept aside (the class keeps its own, which C names
 * and the reports of cycles use).
 */
class LocalClassNames : InventNamesForLocalClasses() {

	/** Each local class's name, without its package. */
	val names = HashMap<IrClass, String>()

	override fun computeTopLevelClassName(clazz: IrClass): String = clazz.name.asString()

	override fun sanitizeNameIfNeeded(name: String): String = name

	override fun customizeNameInventorData(clazz: IrClass, data: NameBuilder): NameBuilder {
		if (!clazz.isAnonymousObject) return data
		val vFilePart = (clazz.parent as? IrFile)?.packagePartClassName ?: return data
		return NameBuilder(null, vFilePart, true, data.processingInlinedFunction)
	}

	override fun putLocalClassName(declaration: IrElement, localClassName: String) {
		if (declaration is IrClass) names[declaration] = localClassName
	}

	/** The names of the local classes of every file of [inModule]. */
	fun of(inModule: IrModuleFragment): Map<IrClass, String> {
		inModule.files.forEach { lower(it) }
		return names
	}
}
