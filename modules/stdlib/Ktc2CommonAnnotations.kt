package kotlin.jvm

/*
ktc2 library code compiled as part of the common source set. The standard library marks some
of its generated files with this annotation, which is internal to it; it means nothing here.
*/

@Target(AnnotationTarget.FILE)
@Retention(AnnotationRetention.SOURCE)
internal annotation class JvmPackageName(val name: String)
