package com.delminiusapps.tillfailure.core.designsystem

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val LocalTillFailureColors = staticCompositionLocalOf { TillFailureDarkColors }

private val TillFailureMaterialColors = darkColorScheme(
    primary = TillFailureDarkColors.accent,
    onPrimary = TillFailureDarkColors.onAccent,
    primaryContainer = TillFailureDarkColors.accent,
    onPrimaryContainer = TillFailureDarkColors.onAccent,
    secondary = TillFailureDarkColors.primaryText,
    onSecondary = TillFailureDarkColors.background,
    background = TillFailureDarkColors.background,
    onBackground = TillFailureDarkColors.primaryText,
    surface = TillFailureDarkColors.surface,
    onSurface = TillFailureDarkColors.primaryText,
    surfaceVariant = TillFailureDarkColors.elevatedSurface,
    onSurfaceVariant = TillFailureDarkColors.secondaryText,
    outline = TillFailureDarkColors.border,
    outlineVariant = TillFailureDarkColors.strongBorder,
    error = TillFailureDarkColors.error,
    onError = Color.Black,
    errorContainer = TillFailureDarkColors.errorSurface,
    onErrorContainer = TillFailureDarkColors.error,
    scrim = TillFailureDarkColors.overlay,
)

private val DisplayFallback = FontFamily.SansSerif
private val BodyFallback = FontFamily.SansSerif

private val TillFailureTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = DisplayFallback,
        fontWeight = FontWeight.Bold,
        fontSize = 34.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.5).sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = DisplayFallback,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = DisplayFallback,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = DisplayFallback,
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = DisplayFallback,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = BodyFallback,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = BodyFallback,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = BodyFallback,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = BodyFallback,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 17.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = BodyFallback,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = BodyFallback,
        fontWeight = FontWeight.Bold,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.6.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = BodyFallback,
        fontWeight = FontWeight.Bold,
        fontSize = 10.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.7.sp,
    ),
)

private val TillFailureShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(TillFailureRadii.xs),
    small = androidx.compose.foundation.shape.RoundedCornerShape(TillFailureRadii.sm),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(TillFailureRadii.md),
    large = androidx.compose.foundation.shape.RoundedCornerShape(TillFailureRadii.lg),
)

object TillFailureTheme {
    val colors: TillFailureSemanticColors
        @Composable
        @ReadOnlyComposable
        get() = LocalTillFailureColors.current
}

@Composable
fun TillFailureTheme(content: @Composable () -> Unit) {
    androidx.compose.runtime.CompositionLocalProvider(LocalTillFailureColors provides TillFailureDarkColors) {
        MaterialTheme(
            colorScheme = TillFailureMaterialColors,
            typography = TillFailureTypography,
            shapes = TillFailureShapes,
            content = content,
        )
    }
}
