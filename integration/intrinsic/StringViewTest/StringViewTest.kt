package StringViewTest

// Pure zero-alloc String view extensions (removePrefix/removeSuffix/substringBefore/After[Last]).

fun main(): Int {
	var ok = true

	ok = ok && ("hello.kt".removeSuffix(".kt") == "hello")
	ok = ok && ("hello.kt".removeSuffix(".md") == "hello.kt")   // no match → unchanged
	ok = ok && ("foo_bar".removePrefix("foo_") == "bar")
	ok = ok && ("foo_bar".removePrefix("xyz") == "foo_bar")     // no match → unchanged

	ok = ok && ("a/b/c".substringBefore('/') == "a")
	ok = ok && ("a/b/c".substringAfter('/') == "b/c")
	ok = ok && ("a/b/c".substringBeforeLast('/') == "a/b")
	ok = ok && ("a/b/c".substringAfterLast('/') == "c")

	ok = ok && ("nodelim".substringBefore('/') == "nodelim")    // absent → whole string
	ok = ok && ("nodelim".substringAfter('/') == "nodelim")

	if (!ok) {
		println("StringViewTest FAIL")
		return 1
	}
	println("StringViewTest OK")
	return 0
}
