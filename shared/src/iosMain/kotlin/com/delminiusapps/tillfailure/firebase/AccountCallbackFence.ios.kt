package com.delminiusapps.tillfailure.firebase

import platform.Foundation.NSRecursiveLock

actual class AccountCallbackFence actual constructor(initialEpoch: Long) {
    private val lock = NSRecursiveLock()
    private var currentEpoch = initialEpoch
    private var disposed = false

    actual fun advance(): Long {
        lock.lock()
        try {
            currentEpoch += 1
            disposed = false
            return currentEpoch
        } finally { lock.unlock() }
    }

    actual fun dispose() {
        lock.lock()
        try { disposed = true } finally { lock.unlock() }
    }

    actual fun accepts(epoch: Long): Boolean {
        lock.lock()
        try { return !disposed && epoch == currentEpoch } finally { lock.unlock() }
    }

    actual fun deliverIfCurrent(epoch: Long, deliver: () -> Unit) {
        lock.lock()
        try { if (!disposed && epoch == currentEpoch) deliver() } finally { lock.unlock() }
    }
}
