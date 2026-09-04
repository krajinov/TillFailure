package com.delminiusapps.tillfailure

import androidx.compose.runtime.Composable
import com.delminiusapps.tillfailure.app.FoundationNavigation
import com.delminiusapps.tillfailure.core.designsystem.TillFailureTheme

/**
 * Temporary Milestone 1 shell. It proves shared rendering and foundation wiring only and is
 * intentionally isolated for replacement by the role-aware application shell in a later milestone.
 */
@Composable
fun App(showDevelopmentCatalog: Boolean = false) {
    TillFailureTheme {
        FoundationNavigation(showDevelopmentCatalog = showDevelopmentCatalog)
    }
}
