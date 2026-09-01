package com.delminiusapps.tillfailure.foundation.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val FoundationColors = darkColorScheme(
    primary = Color(0xFFC8F04B),
    onPrimary = Color(0xFF111312),
    background = Color(0xFF111312),
    onBackground = Color(0xFFF4F6F0),
    surface = Color(0xFF191C1A),
    onSurface = Color(0xFFF4F6F0),
)

/** Minimal approved-color bridge; the complete design system belongs to Milestone 2. */
@Composable
fun TillFailureFoundationTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = FoundationColors,
        content = content,
    )
}
