package kotlin.reflect

/*
ktc2 platform layer: what `cast` and `safeCast` of a class (`KClasses.kt` of the library) need.
*/

internal actual val KClass<*>.qualifiedOrSimpleName: String?
	get() = qualifiedName
