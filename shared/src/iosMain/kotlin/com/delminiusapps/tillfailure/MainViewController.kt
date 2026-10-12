package com.delminiusapps.tillfailure

import androidx.compose.ui.window.ComposeUIViewController
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.delminiusapps.tillfailure.firebase.NativeFirebaseBridge
import com.delminiusapps.tillfailure.firebase.NativeIdentityBridge
import com.delminiusapps.tillfailure.firebase.IosProductIdentityClient
import com.delminiusapps.tillfailure.persistence.NativeRecoveryPersistenceBridge

fun MainViewController(
    nativeFirebaseBridge: NativeFirebaseBridge? = null,
    nativeRecoveryBridge: NativeRecoveryPersistenceBridge? = null,
    showDevelopmentCatalog: Boolean = false,
    fontScaleOverride: Float = 0f,
) = ComposeUIViewController {
    val density = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides if (fontScaleOverride in 1f..2f)
        Density(density.density, fontScaleOverride) else density) {
      App(
        identityClientProvider = {
            if (nativeFirebaseBridge is NativeIdentityBridge && nativeRecoveryBridge != null)
                IosProductIdentityClient(nativeFirebaseBridge.activeBridge(), nativeRecoveryBridge) else null
        },
        showDevelopmentCatalog = showDevelopmentCatalog,
    )
    }
}
