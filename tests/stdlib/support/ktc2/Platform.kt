/*
 * Copyright 2010-2018 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package test

import kotlin.test.*

// What the library's tests declare `expect`. Where the JVM and Kotlin/Native differ ktc2 does as Kotlin/Native does
// (DESIGN.md, section 1), so it answers as Kotlin/Native's tests do (kotlin-native/runtime/test/utils.kt).

public val TestPlatform.Companion.current: TestPlatform get() = TestPlatform.Native

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

	val notYetDefinedGroup: HandlingOption = HandlingOption.THROW
	val notYetDefinedNamedGroup: HandlingOption = HandlingOption.THROW
	val enclosingGroup: HandlingOption = HandlingOption.MATCH_NOTHING
	val nonExistentGroup: HandlingOption = HandlingOption.THROW
	val nonExistentNamedGroup: HandlingOption = HandlingOption.THROW
	val groupZero: HandlingOption = HandlingOption.THROW
}
