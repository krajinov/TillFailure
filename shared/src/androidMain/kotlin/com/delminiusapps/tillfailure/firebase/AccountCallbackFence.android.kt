package com.delminiusapps.tillfailure.firebase

actual class AccountCallbackFence actual constructor(initialEpoch: Long) {
    private val lock = Any()
    private var currentEpoch = initialEpoch
    private var disposed = false

    actual fun advance(): Long = synchronized(lock) {
        currentEpoch += 1
        disposed = false
        currentEpoch
    }

    actual fun dispose() = synchronized(lock) { disposed = true }

    actual fun accepts(epoch: Long): Boolean = synchronized(lock) { !disposed && epoch == currentEpoch }

    actual fun deliverIfCurrent(epoch: Long, deliver: () -> Unit) {
        synchronized(lock) {
            if (!disposed && epoch == currentEpoch) deliver()
        }
    }
}
