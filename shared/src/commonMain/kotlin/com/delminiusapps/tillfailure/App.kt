package com.delminiusapps.tillfailure

import androidx.compose.runtime.Composable
import com.delminiusapps.tillfailure.core.designsystem.TillFailureTheme
import com.delminiusapps.tillfailure.identity.IdentityRoot
import com.delminiusapps.tillfailure.identity.ProductIdentityClient

/** PR 1 product root. Debug-only catalog fixtures stay isolated from this identity gate. */
@Composable
fun App(identityClient: ProductIdentityClient? = null) {
    TillFailureTheme {
        IdentityRoot(identityClient)
    }
}
