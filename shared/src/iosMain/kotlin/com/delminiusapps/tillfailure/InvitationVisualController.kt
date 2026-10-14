package com.delminiusapps.tillfailure

import androidx.compose.ui.window.ComposeUIViewController
import com.delminiusapps.tillfailure.designcatalog.InvitationVisualFixture

fun InvitationVisualController(stage: String, fontScale: Float) = ComposeUIViewController {
    InvitationVisualFixture(stage, fontScale)
}
