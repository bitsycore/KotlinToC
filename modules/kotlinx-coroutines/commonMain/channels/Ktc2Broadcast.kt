package kotlinx.coroutines.channels

/**
 * ktc2: what the subscriptions of a broadcast channel reach it through (BroadcastChannel.kt.edit), [value] weak in the
 * module: a subscription that is all that holds its channel does not keep it.
 */
internal class Ktc2BroadcastReference(value: Any) {
    var value: Any? = value
}
