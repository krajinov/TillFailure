package com.delminiusapps.tillfailure.firebase

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FirebaseContractsTest {
    @Test
    fun emulatorConfigurationRejectsAnyProductionLikeProjectOrHost() {
        assertFailsWith<IllegalArgumentException> { FirebaseEmulatorConfiguration(projectId = "real-project", host = "127.0.0.1") }
        assertFailsWith<IllegalArgumentException> { FirebaseEmulatorConfiguration(host = "example.com") }
        FirebaseEmulatorConfiguration(host = "127.0.0.1")
    }

    @Test
    fun callbackFenceRejectsDisposedAndOldEpochCallbacks() {
        val fence = AccountCallbackFence()
        val first = fence.advance()
        assertTrue(fence.accepts(first))
        val second = fence.advance()
        assertFalse(fence.accepts(first))
        assertTrue(fence.accepts(second))
        fence.dispose()
        assertFalse(fence.accepts(second))
    }
}
