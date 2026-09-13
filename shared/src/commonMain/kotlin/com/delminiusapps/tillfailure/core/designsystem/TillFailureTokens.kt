package com.delminiusapps.tillfailure.core.designsystem

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Immutable
data class TillFailureSemanticColors(
    val background: Color,
    val surface: Color,
    val elevatedSurface: Color,
    val border: Color,
    val strongBorder: Color,
    val primaryText: Color,
    val secondaryText: Color,
    val disabledText: Color,
    val accent: Color,
    val onAccent: Color,
    val focus: Color,
    val success: Color,
    val successSurface: Color,
    val warning: Color,
    val warningSurface: Color,
    val error: Color,
    val errorSurface: Color,
    val errorBorder: Color,
    val info: Color,
    val infoSurface: Color,
    val overlay: Color,
)

val TillFailureDarkColors = TillFailureSemanticColors(
    background = Color(0xFF111312),
    surface = Color(0xFF191C1A),
    elevatedSurface = Color(0xFF222622),
    border = Color(0xFF30352F),
    strongBorder = Color(0xFF454C45),
    primaryText = Color(0xFFF5F7F2),
    secondaryText = Color(0xFF9DA49C),
    disabledText = Color(0xFF6F756E),
    accent = Color(0xFFC8F04B),
    onAccent = Color(0xFF172000),
    focus = Color(0xFFD8FF66),
    success = Color(0xFF55D68B),
    successSurface = Color(0xFF203427),
    warning = Color(0xFFF5B84B),
    warningSurface = Color(0xFF3A2E17),
    error = Color(0xFFFF6B68),
    errorSurface = Color(0xFF3B2020),
    errorBorder = Color(0xFF6A3434),
    info = Color(0xFF6EAAFF),
    infoSurface = Color(0xFF1B2B40),
    overlay = Color(0xA6000000),
)

object TillFailureSpacing {
    val xxs = 4.dp
    val xs = 8.dp
    val sm = 12.dp
    val md = 16.dp
    val lg = 20.dp
    val xl = 24.dp
    val xxl = 32.dp
}

object TillFailureRadii {
    val xs = 6.dp
    val sm = 10.dp
    val md = 16.dp
    val lg = 24.dp
}

object TillFailureBorders {
    val hairline = 1.dp
    val focused = 2.dp
}

object TillFailureSizing {
    /** The Pencil masters are visually 44 dp; the effective Compose target is intentionally 48 dp. */
    val minimumTouchTarget = 48.dp
    val buttonHeight = 48.dp
    val fieldHeight = 56.dp
    val compactIcon = 20.dp
    val icon = 24.dp
}
