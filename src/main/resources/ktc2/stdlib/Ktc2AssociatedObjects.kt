package kotlin.reflect

/*
ktc2 platform layer: Kotlin/Native's associated objects, which kotlinx.serialization finds the
serializer of a class with (`@SerializableWith`, which the serialization plugin puts on a class).
ktc2 does not keep the table of them yet: no class has an associated object.
*/

/** Marks the API of associated objects, experimental as on Kotlin/Native and JS. */
@RequiresOptIn(level = RequiresOptIn.Level.ERROR)
@MustBeDocumented
@Retention(AnnotationRetention.BINARY)
public annotation class ExperimentalAssociatedObjects

/** Marks an annotation whose argument, an `object`, is associated with the class it annotates. */
@ExperimentalAssociatedObjects
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.ANNOTATION_CLASS)
public annotation class AssociatedObjectKey

/** The object associated with this class by an annotation of type [T]; null when there is none. */
@ExperimentalAssociatedObjects
public inline fun <reified T : Annotation> KClass<*>.findAssociatedObject(): Any? = null
