package com.delminiusapps.tillfailure

import androidx.compose.ui.window.ComposeUIViewController
import com.delminiusapps.tillfailure.firebase.IosFirebaseSpikeClient
import com.delminiusapps.tillfailure.firebase.NativeFirebaseBridge

fun MainViewController(
    showDevelopmentCatalog: Boolean,
    nativeFirebaseBridge: NativeFirebaseBridge? = null,
) = ComposeUIViewController {
    nativeFirebaseBridge?.let(::IosFirebaseSpikeClient)
    App(showDevelopmentCatalog = showDevelopmentCatalog)
}
