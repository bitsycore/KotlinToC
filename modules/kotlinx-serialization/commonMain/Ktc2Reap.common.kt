package kotlinx.serialization.internal

/** ktc2: gives [value] to the runtime's reaper, which frees what it is left in once nothing else holds it (Ktc2Serialization.kt). */
internal expect fun ktc2ReapLater(value: Any)
