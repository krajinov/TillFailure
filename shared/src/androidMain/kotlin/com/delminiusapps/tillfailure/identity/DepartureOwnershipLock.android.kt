package com.delminiusapps.tillfailure.identity

internal actual class DepartureOwnershipLock actual constructor() {
    private val lock = Any()

    actual fun <T> locked(block: () -> T): T = synchronized(lock, block)
}
