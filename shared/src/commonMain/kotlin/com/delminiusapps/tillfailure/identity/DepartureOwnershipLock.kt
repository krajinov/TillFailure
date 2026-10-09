package com.delminiusapps.tillfailure.identity

/**
 * Minimal mutual-exclusion primitive for the shared [CleanDepartureOwnership] state machine.
 *
 * Common Kotlin has no `synchronized`, so each platform supplies its own non-suspending lock, matching
 * the existing [com.delminiusapps.tillfailure.firebase.AccountCallbackFence] expect/actual pattern.
 */
internal expect class DepartureOwnershipLock() {
    fun <T> locked(block: () -> T): T
}
