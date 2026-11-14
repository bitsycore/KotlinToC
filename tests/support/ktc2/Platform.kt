/*
 * Copyright 2010-2018 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package test

import kotlin.test.*

// What the library's tests declare `expect`. Where the JVM and Kotlin/Native differ ktc2 does as the JVM does,
// so it answers as the JVM's tests do (libraries/stdlib/jvm/test/testUtilsJVM.kt), but for the comparison of two
// classes, which there is `javaClass`.

public val TestPlatform.Companion.current: TestPlatform get() = TestPlatform.Jvm

public fun assertTypeEquals(expected: Any?, actual: Any?) {
	if (expected != null && actual != null) {
		assertTrue(expected::class.isInstance(actual) || actual::class.isInstance(expected), "Expected: $expected,  Actual: $actual")
	} else {
		assertTrue(expected == null && actual == null)
	}
}

public val isFloat32RangeEnforced: Boolean get() = true

public val supportsOctalLiteralInRegex: Boolean get() = true

public val supportsEscapeAnyCharInRegex: Boolean get() = true

public val regexSplitUnicodeCodePointHandling: Boolean get() = false

public object BackReferenceHandling {
	val captureLargestValidIndex: Boolean get() = true

	val notYetDefinedGroup: HandlingOption = HandlingOption.MATCH_NOTHING
	val notYetDefinedNamedGroup: HandlingOption = HandlingOption.THROW
	val enclosingGroup: HandlingOption = HandlingOption.MATCH_NOTHING
	val nonExistentGroup: HandlingOption = HandlingOption.MATCH_NOTHING
	val nonExistentNamedGroup: HandlingOption = HandlingOption.THROW
	val groupZero: HandlingOption = HandlingOption.THROW
}
