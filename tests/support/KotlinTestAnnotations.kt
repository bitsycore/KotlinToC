package kotlin.test

/*
The annotations of kotlin.test for the suite's JVM side: the JVM's kotlin-test jar has the assertions (with its
DefaultAsserter, as Kotlin/Native's) and leaves these to a framework's jar (kotlin-test-junit), whose asserter words its
messages otherwise. Kept at run time, for Ktc2JvmTestRunner.kt to find.
*/

@Target(AnnotationTarget.FUNCTION)
public annotation class Test

@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
public annotation class Ignore

@Target(AnnotationTarget.FUNCTION)
public annotation class BeforeTest

@Target(AnnotationTarget.FUNCTION)
public annotation class AfterTest
