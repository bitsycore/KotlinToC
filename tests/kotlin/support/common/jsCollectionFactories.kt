/*
 * Copyright 2010-2018 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package test.collections.js

// The factories the tests of the collections share with Kotlin/JS, as the JVM and Kotlin/Native tests have them.
public fun <V> stringMapOf(vararg pairs: Pair<String, V>): HashMap<String, V> = hashMapOf<String, V>(*pairs)
public fun <V> linkedStringMapOf(vararg pairs: Pair<String, V>): LinkedHashMap<String, V> = linkedMapOf(*pairs)
public fun stringSetOf(vararg elements: String): HashSet<String> = hashSetOf(*elements)
public fun linkedStringSetOf(vararg elements: String): LinkedHashSet<String> = linkedSetOf(*elements)
