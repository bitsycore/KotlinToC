@file:OptIn(kotlin.experimental.ExperimentalNativeApi::class)

package kotlinx.io

import kotlin.native.OsFamily
import kotlin.native.Platform

/*
ktc2's part of kotlinx-io: what kotlinx-io gives each system of Kotlin/Native apart (its mingwMain,
unixMain, linuxMain, appleMain), given here once for every system ktc2 compiles for.
*/

/** The line separator of the system: "\r\n" on Windows, "\n" elsewhere, as kotlinx-io has it. */
public actual val SystemLineSeparator: String = if (Platform.osFamily == OsFamily.WINDOWS) "\r\n" else "\n"
