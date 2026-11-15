@file:OptIn(UnsafeDuringIrConstructionAPI::class)

package com.bitsycore.ktc2.frontend

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irInt
import org.jetbrains.kotlin.ir.builders.irEquals
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irGetObject
import org.jetbrains.kotlin.ir.builders.irIfThen
import org.jetbrains.kotlin.ir.builders.irNull
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.builders.irString
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrDeclarationContainer
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrClassReference
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.util.isObject
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.ir.util.kotlinFqName

/*
Kotlin/Native's associated objects (`KClass.findAssociatedObject<T>()`), which kotlinx.serialization finds the
serializer of a class with. The platform layer's `findAssociatedObject` calls `ktc2.internal.associatedObject(class
name, annotation name)`; its body is written here, in IR, before the importer reads the module: one test per pair.

- A class annotated with an annotation whose class is `@AssociatedObjectKey`, the argument an `object`: that object.
- A class the serialization plugin gave a serializer: `@SerializableWith`, as the plugin says it on Kotlin/Native and
  ktc2 runs it as on the JVM, where it does not: the serializer its companion's `serializer()` (an object's own) gives.
  A generic class, whose serializer is made from its arguments' (a `SerializerFactory` on Native): a
  `Ktc2SerializerFactory` of the module, which calls `ktc2GenericSerializer`, whose body is written here too: for each
  generic class, its companion's `serializer(...)` with the serializers of the arguments.
*/

private const val kSerializableWith = "kotlinx.serialization.SerializableWith"

/** One entry of the table: the class, the annotation, and what the entry gives. */
private class AssociatedEntry(val className: String, val keyName: String, val value: (DeclarationIrBuilder) -> IrExpression)

/** A generic class with a serializer from the plugin: its name and its companion's `serializer(...)`. */
private class GenericSerializer(val className: String, val companion: IrClass, val serializer: IrSimpleFunction)

/** Writes the body of `ktc2.internal.associatedObject` in [inModule], when the program has it. */
internal fun writeAssociatedObjects(inModule: IrModuleFragment, inContext: IrPluginContext) {
	fun topLevel(inPackage: String, inName: String) = inModule.files.asSequence().filter { it.packageFqName.asString() == inPackage }.flatMap { it.declarations }
	val vFunction = topLevel("ktc2.internal", "").filterIsInstance<IrSimpleFunction>().firstOrNull { it.name.asString() == "associatedObject" } ?: return
	val vFactory = topLevel("kotlinx.serialization.internal", "").filterIsInstance<IrClass>().firstOrNull { it.name.asString() == "Ktc2SerializerFactory" }
	val vEntries = ArrayList<AssociatedEntry>()
	val vGenerics = ArrayList<GenericSerializer>()
	fun visit(inContainer: IrDeclarationContainer) {
		for (vClass in inContainer.declarations.filterIsInstance<IrClass>()) {
			entriesOf(vClass, vEntries, vGenerics, vFactory)
			visit(vClass)
		}
	}
	inModule.files.forEach { visit(it) }
	topLevel("kotlinx.serialization.internal", "").filterIsInstance<IrSimpleFunction>().firstOrNull { it.name.asString() == "ktc2GenericSerializer" }
		?.let { writeGenericSerializers(it, vGenerics, inContext) }
	val vParams = vFunction.parameters.filter { it.kind == IrParameterKind.Regular }
	val vBuilder = DeclarationIrBuilder(inContext, vFunction.symbol)
	vFunction.body = vBuilder.irBlockBody {
		for (vEntry in vEntries) {
			+irIfThen(
				context.irBuiltIns.unitType,
				irEquals(irGet(vParams[0]), irString(vEntry.className)),
				irIfThen(context.irBuiltIns.unitType, irEquals(irGet(vParams[1]), irString(vEntry.keyName)), irReturn(vEntry.value(vBuilder))),
			)
		}
		+irReturn(irNull())
	}
}

private fun entriesOf(inClass: IrClass, outEntries: MutableList<AssociatedEntry>, outGenerics: MutableList<GenericSerializer>, inFactory: IrClass?) {
	val vName = inClass.kotlinFqName.asString()
	for (vAnnotation in inClass.annotations) {
		val vAnnotationClass = vAnnotation.type.classOrNull?.owner ?: continue
		if (vAnnotationClass.annotations.none { it.type.classOrNull?.owner?.kotlinFqName?.asString() == "kotlin.reflect.AssociatedObjectKey" }) continue
		val vObject = (vAnnotation.arguments.firstOrNull() as? IrClassReference)?.classType?.classOrNull ?: continue
		if (!vObject.owner.isObject) continue
		outEntries += AssociatedEntry(vName, vAnnotationClass.kotlinFqName.asString()) { it.irGetObject(vObject) }
	}
	if (inClass.typeParameters.isNotEmpty()) {
		// A generic class: `serializer(typeSerial0, ...)` of its companion, one serializer for each type parameter.
		val vCompanion = inClass.declarations.filterIsInstance<IrClass>().firstOrNull { it.isCompanion } ?: return
		val vSerializer = vCompanion.declarations.filterIsInstance<IrSimpleFunction>().firstOrNull { vMember ->
			vMember.name.asString() == "serializer" && vMember.parameters.count { it.kind == IrParameterKind.Regular } == inClass.typeParameters.size
		} ?: return
		val vFactoryConstructor = inFactory?.declarations?.filterIsInstance<IrConstructor>()?.firstOrNull() ?: return
		outGenerics += GenericSerializer(vName, vCompanion, vSerializer)
		outEntries += AssociatedEntry(vName, kSerializableWith) { vBuilder ->
			vBuilder.irCall(vFactoryConstructor).also { it.arguments[0] = vBuilder.irString(vName) }
		}
		return
	}
	// The serializer the plugin gave the class: `serializer()` of its companion, or of the object itself.
	val vHolder = if (inClass.isObject) inClass else inClass.declarations.filterIsInstance<IrClass>().firstOrNull { it.isCompanion } ?: return
	val vSerializer = vHolder.declarations.filterIsInstance<IrSimpleFunction>().firstOrNull { vMember ->
		vMember.name.asString() == "serializer" && vMember.parameters.none { it.kind == IrParameterKind.Regular } &&
			vMember.returnType.classOrNull?.owner?.kotlinFqName?.asString() == "kotlinx.serialization.KSerializer"
	} ?: return
	outEntries += AssociatedEntry(vName, kSerializableWith) { vBuilder ->
		vBuilder.irCall(vSerializer).also { vCall -> vCall.arguments[0] = vBuilder.irGetObject(vHolder.symbol) }
	}
}

/** The body of `ktc2GenericSerializer(className, arguments)`: for each generic class, its companion's `serializer(arguments[0], ...)`. */
private fun writeGenericSerializers(inFunction: IrSimpleFunction, inGenerics: List<GenericSerializer>, inContext: IrPluginContext) {
	val vParams = inFunction.parameters.filter { it.kind == IrParameterKind.Regular }
	val vGet = inContext.irBuiltIns.arrayClass.owner.functions.first { it.name.asString() == "get" }
	val vBuilder = DeclarationIrBuilder(inContext, inFunction.symbol)
	inFunction.body = vBuilder.irBlockBody {
		for (vGeneric in inGenerics) {
			val vCall = irCall(vGeneric.serializer).also { vCall ->
				vCall.arguments[0] = irGetObject(vGeneric.companion.symbol)
				vGeneric.serializer.parameters.filter { it.kind == IrParameterKind.Regular }.forEachIndexed { vIndex, vParam ->
					vCall.arguments[vParam.indexInParameters] = irCall(vGet).also { vRead ->
						vRead.arguments[0] = irGet(vParams[1])
						vRead.arguments[1] = irInt(vIndex)
					}
				}
			}
			+irIfThen(context.irBuiltIns.unitType, irEquals(irGet(vParams[0]), irString(vGeneric.className)), irReturn(vCall))
		}
		+irReturn(irNull())
	}
}
