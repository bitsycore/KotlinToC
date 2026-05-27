package com.bitsycore.ktc.utils

private const val ANSI_RESET  = "[0m"
private const val ANSI_BOLD   = "[1m"
private const val ANSI_RED    = "[1;31m"
private const val ANSI_YELLOW = "[1;33m"
private const val ANSI_CYAN   = "[36m"
private const val ANSI_GRAY   = "[90m"

val ansiEnabled: Boolean = System.console() != null && System.getenv("NO_COLOR") == null

fun String.wrapBold(): String   = if (ansiEnabled) "$ANSI_BOLD${this}$ANSI_RESET" else this
fun String.wrapRed(): String    = if (ansiEnabled) "$ANSI_RED${this}$ANSI_RESET" else this
fun String.wrapYellow(): String = if (ansiEnabled) "$ANSI_YELLOW${this}$ANSI_RESET" else this
fun String.wrapCyan(): String   = if (ansiEnabled) "$ANSI_CYAN${this}$ANSI_RESET" else this
fun String.wrapGray(): String   = if (ansiEnabled) "$ANSI_GRAY${this}$ANSI_RESET" else this
