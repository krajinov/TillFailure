package com.delminiusapps.tillfailure

import androidx.compose.ui.window.ComposeUIViewController

fun MainViewController(showDevelopmentCatalog: Boolean) = ComposeUIViewController {
    App(showDevelopmentCatalog = showDevelopmentCatalog)
}
