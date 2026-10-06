package com.delminiusapps.tillfailure.firebase

import android.content.Context

/** Retains one product SDK generation across Activity recreation within a process. */
object AndroidProductIdentitySession {
    private var current: AndroidFirebaseSpikeClient? = null

    @Synchronized
    fun get(context: Context): AndroidFirebaseSpikeClient = current ?: AndroidFirebaseSpikeClient(
        context.applicationContext,
        FirebaseEmulatorConfiguration(host = "10.0.2.2"),
        AccountCallbackFence(),
        productMemoryCache = true,
    ).also { current = it }

    @Synchronized
    internal fun replaceIfCurrent(old: AndroidFirebaseSpikeClient, fresh: AndroidFirebaseSpikeClient) {
        if (current === old) current = fresh
    }
}
