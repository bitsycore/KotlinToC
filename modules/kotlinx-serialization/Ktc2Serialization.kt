@file:Suppress("DEPRECATION_ERROR")

package kotlinx.serialization.internal

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException

/*
ktc2's part of kotlinx.serialization: what its plugin gives a generic class on Kotlin/Native, where the class's
companion is a SerializerFactory found as its associated object. ktc2 runs the plugin as on the JVM, where it is
not: the associated object of a generic class is one of these (ktc2's AssociatedObjects.kt), which calls the
companion's `serializer(...)` through [ktc2GenericSerializer].
*/

/**
 * The serializers of the generic class [mClassName], made from those of its type arguments, and kept in [gMadeSerializers].
 */
internal class Ktc2SerializerFactory(private val mClassName: String) : SerializerFactory {
	override fun serializer(vararg typeParamsSerializers: KSerializer<*>): KSerializer<*> = madeSerializer(mClassName, typeParamsSerializers)
}

/**
 * The serializers of generic classes made so far: for each class, one for each list of arguments (by identity: the
 * serializers of the arguments come from objects and from here). Such a serializer and its descriptor refer to each
 * other, a cycle reference counts never free; kept here, there is one for each type the program serializes rather than
 * one for each call, as the JVM's cache has it (PLAN.md, decided on 2026-11-15).
 */
private val gMadeSerializers = HashMap<String, ArrayList<Pair<Array<out KSerializer<*>>, KSerializer<*>>>>()

private fun madeSerializer(inClassName: String, inArguments: Array<out KSerializer<*>>): KSerializer<*> = ktc2.concurrent.synchronized(gMadeSerializers) {
	val vMade = gMadeSerializers.getOrPut(inClassName) { ArrayList() }
	vMade.firstOrNull { (vArguments, _) -> vArguments.size == inArguments.size && vArguments.indices.all { vArguments[it] === inArguments[it] } }
		?.let { return@synchronized it.second }
	val vSerializer = ktc2GenericSerializer(inClassName, inArguments) ?: throw SerializationException("Serializer for class '$inClassName' is not found.")
	vMade += inArguments.copyOf() to vSerializer
	vSerializer
}

/**
 * The serializer of the generic class named [className] for the serializers of its type arguments, [arguments]; null
 * when it has none. Its body is written by ktc2 when it compiles the program, from the classes it has.
 */
internal fun ktc2GenericSerializer(className: String, arguments: Array<out KSerializer<*>>): KSerializer<*>? = null

/**
 * ktc2: the reaper of the library (`PLAN.md`, "Who answers a cycle", rule 3). The descriptor of a type that contains
 * itself reaches its own serializer again through its children's serializers, a ring through arrays and lazy values that
 * the module declares (`rings`); the runtime's reaper checks it by trial deletion and frees it once nothing else holds
 * it, at the latest when the program ends.
 */
internal actual fun ktc2ReapLater(value: Any) = ktc2.internal.reapLater(value)
