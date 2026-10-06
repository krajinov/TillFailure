package com.delminiusapps.tillfailure

import androidx.compose.ui.window.ComposeUIViewController
import com.delminiusapps.tillfailure.firebase.NativeFirebaseBridge
import com.delminiusapps.tillfailure.firebase.NativeIdentityBridge
import com.delminiusapps.tillfailure.firebase.IosProductIdentityClient
import com.delminiusapps.tillfailure.persistence.NativeRecoveryPersistenceBridge

fun MainViewController(
    nativeFirebaseBridge: NativeFirebaseBridge? = null,
    nativeRecoveryBridge: NativeRecoveryPersistenceBridge? = null,
) = ComposeUIViewController {
    App(
        identityClient = if (nativeFirebaseBridge is NativeIdentityBridge && nativeRecoveryBridge != null)
            IosProductIdentityClient(nativeFirebaseBridge, nativeRecoveryBridge) else null,
    )
}
