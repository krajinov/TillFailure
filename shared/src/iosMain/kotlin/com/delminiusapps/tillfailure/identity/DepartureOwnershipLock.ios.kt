package com.delminiusapps.tillfailure.identity

import platform.Foundation.NSRecursiveLock

internal actual class DepartureOwnershipLock actual constructor() {
    private val lock = NSRecursiveLock()

    actual fun <T> locked(block: () -> T): T {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }
}
