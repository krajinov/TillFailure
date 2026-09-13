package com.delminiusapps.tillfailure.foundation.ui

import androidx.compose.runtime.Composable
import com.delminiusapps.tillfailure.core.designsystem.TillFailureTheme

/** Compatibility wrapper retained until the temporary foundation flow is replaced. */
@Composable
fun TillFailureFoundationTheme(content: @Composable () -> Unit) {
    TillFailureTheme(content)
}
