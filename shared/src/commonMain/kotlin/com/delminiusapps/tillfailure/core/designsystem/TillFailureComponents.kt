package com.delminiusapps.tillfailure.core.designsystem

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Immutable
internal data class TillFailurePrimaryButtonPalette(
    val containerColor: Color,
    val contentColor: Color,
    val disabledContainerColor: Color,
    val disabledContentColor: Color,
    val loadingIndicatorColor: Color,
)

internal fun tillFailurePrimaryButtonPalette(
    colors: TillFailureSemanticColors,
    inverted: Boolean,
): TillFailurePrimaryButtonPalette = TillFailurePrimaryButtonPalette(
    containerColor = if (inverted) colors.background else colors.accent,
    contentColor = if (inverted) colors.primaryText else colors.onAccent,
    disabledContainerColor = colors.elevatedSurface,
    disabledContentColor = colors.disabledText,
    loadingIndicatorColor = colors.disabledText,
)

@Composable
fun TillFailurePrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    inverted: Boolean = false,
) {
    val palette = tillFailurePrimaryButtonPalette(TillFailureTheme.colors, inverted)
    Button(
        onClick = onClick,
        modifier = modifier
            .heightIn(min = TillFailureSizing.minimumTouchTarget)
            .defaultMinSize(minHeight = TillFailureSizing.buttonHeight),
        enabled = enabled && !loading,
        shape = RoundedCornerShape(TillFailureRadii.sm),
        colors = ButtonDefaults.buttonColors(
            containerColor = palette.containerColor,
            contentColor = palette.contentColor,
            disabledContainerColor = palette.disabledContainerColor,
            disabledContentColor = palette.disabledContentColor,
        ),
        contentPadding = ButtonDefaults.ContentPadding,
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(TillFailureSizing.compactIcon),
                color = palette.loadingIndicatorColor,
                strokeWidth = 2.dp,
            )
            Spacer(Modifier.width(TillFailureSpacing.xs))
            Text("Loading")
        } else {
            Text(text)
        }
    }
}

@Composable
fun TillFailureSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier
            .heightIn(min = TillFailureSizing.minimumTouchTarget)
            .defaultMinSize(minHeight = TillFailureSizing.buttonHeight),
        enabled = enabled,
        shape = RoundedCornerShape(TillFailureRadii.sm),
        border = BorderStroke(TillFailureBorders.hairline, TillFailureTheme.colors.strongBorder),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = TillFailureTheme.colors.primaryText,
            disabledContentColor = TillFailureTheme.colors.disabledText,
        ),
    ) {
        Text(text)
    }
}

@Composable
fun TillFailureTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    TextButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = TillFailureSizing.minimumTouchTarget),
        enabled = enabled,
        colors = ButtonDefaults.textButtonColors(
            contentColor = TillFailureTheme.colors.accent,
            disabledContentColor = TillFailureTheme.colors.disabledText,
        ),
    ) {
        Text(text)
    }
}

@Composable
fun TillFailureIconButton(
    glyph: String,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    IconButton(
        onClick = onClick,
        modifier = modifier
            .size(TillFailureSizing.minimumTouchTarget)
            .clip(RoundedCornerShape(TillFailureRadii.sm))
            .background(TillFailureTheme.colors.elevatedSurface)
            .semantics { this.contentDescription = contentDescription },
        enabled = enabled,
    ) {
        Text(
            text = glyph,
            color = if (enabled) TillFailureTheme.colors.primaryText else TillFailureTheme.colors.disabledText,
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

@Composable
fun TillFailureTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    isError: Boolean = false,
    enabled: Boolean = true,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailingContent: (@Composable () -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        isError = isError,
        label = { Text(label) },
        supportingText = supportingText?.let { message ->
            { Text(message) }
        },
        trailingIcon = trailingContent,
        visualTransformation = visualTransformation,
        singleLine = true,
        shape = RoundedCornerShape(TillFailureRadii.sm),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = TillFailureTheme.colors.surface,
            unfocusedContainerColor = TillFailureTheme.colors.surface,
            disabledContainerColor = TillFailureTheme.colors.surface,
            errorContainerColor = TillFailureTheme.colors.errorSurface,
            focusedBorderColor = TillFailureTheme.colors.focus,
            unfocusedBorderColor = TillFailureTheme.colors.border,
            errorBorderColor = TillFailureTheme.colors.errorBorder,
            cursorColor = TillFailureTheme.colors.accent,
            focusedTextColor = TillFailureTheme.colors.primaryText,
            unfocusedTextColor = TillFailureTheme.colors.primaryText,
            focusedLabelColor = TillFailureTheme.colors.accent,
            unfocusedLabelColor = TillFailureTheme.colors.secondaryText,
            errorLabelColor = TillFailureTheme.colors.error,
            errorSupportingTextColor = TillFailureTheme.colors.error,
        ),
    )
}

@Immutable
internal data class TillFailureFilterChipPresentation(
    val enabled: Boolean,
    val selected: Boolean,
    val containerColor: Color,
    val contentColor: Color,
    val borderColor: Color,
) {
    val exposesDisabledSemantics: Boolean
        get() = !enabled
}

internal fun tillFailureFilterChipPresentation(
    colors: TillFailureSemanticColors,
    enabled: Boolean,
    selected: Boolean,
): TillFailureFilterChipPresentation = when {
    enabled && selected -> TillFailureFilterChipPresentation(
        enabled = true,
        selected = true,
        containerColor = colors.accent,
        contentColor = colors.onAccent,
        borderColor = colors.accent,
    )
    enabled -> TillFailureFilterChipPresentation(
        enabled = true,
        selected = false,
        containerColor = colors.elevatedSurface,
        contentColor = colors.primaryText,
        borderColor = colors.border,
    )
    selected -> TillFailureFilterChipPresentation(
        enabled = false,
        selected = true,
        containerColor = colors.surface,
        contentColor = colors.secondaryText,
        borderColor = colors.disabledText,
    )
    else -> TillFailureFilterChipPresentation(
        enabled = false,
        selected = false,
        containerColor = colors.background,
        contentColor = colors.secondaryText,
        borderColor = colors.strongBorder,
    )
}

internal fun dispatchTillFailureFilterChipClick(
    enabled: Boolean,
    onClick: () -> Unit,
) {
    if (enabled) onClick()
}

@Composable
fun TillFailureFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    horizontalContentPadding: Dp = TillFailureSpacing.md,
) {
    val presentation = tillFailureFilterChipPresentation(TillFailureTheme.colors, enabled, selected)
    Surface(
        modifier = modifier
            .heightIn(min = TillFailureSizing.minimumTouchTarget)
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.Tab,
                onClick = { dispatchTillFailureFilterChipClick(enabled, onClick) },
            )
            .semantics {
                this.selected = presentation.selected
                if (presentation.exposesDisabledSemantics) disabled()
            },
        color = presentation.containerColor,
        contentColor = presentation.contentColor,
        shape = RoundedCornerShape(TillFailureRadii.lg),
        border = BorderStroke(TillFailureBorders.hairline, presentation.borderColor),
    ) {
        Box(
            modifier = Modifier.padding(horizontal = horizontalContentPadding, vertical = TillFailureSpacing.sm),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
            )
        }
    }
}

enum class TillFailureSegmentedControlLayout { Weighted, HorizontallyScrollable }

fun tillFailureSegmentedControlLayout(fontScale: Float): TillFailureSegmentedControlLayout =
    if (fontScale >= 1.5f) {
        TillFailureSegmentedControlLayout.HorizontallyScrollable
    } else {
        TillFailureSegmentedControlLayout.Weighted
    }

data class TillFailureSegmentItem(
    val label: String,
    val selected: Boolean,
    val onClick: () -> Unit,
    val weight: Float = 1f,
    val enabled: Boolean = true,
)

@Composable
fun TillFailureSegmentedControl(
    items: List<TillFailureSegmentItem>,
    modifier: Modifier = Modifier,
) {
    val fontScale = LocalDensity.current.fontScale
    when (tillFailureSegmentedControlLayout(fontScale)) {
        TillFailureSegmentedControlLayout.Weighted -> Row(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(TillFailureSpacing.xs),
        ) {
            items.forEach { item ->
                TillFailureFilterChip(
                    label = item.label,
                    selected = item.selected,
                    onClick = item.onClick,
                    enabled = item.enabled,
                    modifier = Modifier.weight(item.weight),
                    horizontalContentPadding = TillFailureSpacing.xs,
                )
            }
        }

        TillFailureSegmentedControlLayout.HorizontallyScrollable -> {
            val listState = rememberLazyListState()
            val selectedIndex = items.indexOfFirst(TillFailureSegmentItem::selected)
            LaunchedEffect(selectedIndex) {
                if (selectedIndex >= 0) {
                    listState.animateScrollToItem(selectedIndex)
                }
            }
            LazyRow(
                modifier = modifier.fillMaxWidth(),
                state = listState,
                horizontalArrangement = Arrangement.spacedBy(TillFailureSpacing.xxs),
            ) {
                itemsIndexed(items) { _, item ->
                    TillFailureFilterChip(
                        label = item.label,
                        selected = item.selected,
                        onClick = item.onClick,
                        enabled = item.enabled,
                        modifier = Modifier.widthIn(min = TillFailureSizing.minimumTouchTarget),
                        horizontalContentPadding = TillFailureSpacing.xxs,
                    )
                }
            }
        }
    }
}

enum class TillFailureStatusTone { Success, Warning, Error, Info, Neutral }

@Composable
fun TillFailureStatusBadge(
    label: String,
    tone: TillFailureStatusTone,
    modifier: Modifier = Modifier,
) {
    val (foreground, background, glyph) = when (tone) {
        TillFailureStatusTone.Success -> Triple(TillFailureTheme.colors.success, TillFailureTheme.colors.successSurface, "✓")
        TillFailureStatusTone.Warning -> Triple(TillFailureTheme.colors.warning, TillFailureTheme.colors.warningSurface, "!")
        TillFailureStatusTone.Error -> Triple(TillFailureTheme.colors.error, TillFailureTheme.colors.errorSurface, "×")
        TillFailureStatusTone.Info -> Triple(TillFailureTheme.colors.info, TillFailureTheme.colors.infoSurface, "i")
        TillFailureStatusTone.Neutral -> Triple(TillFailureTheme.colors.secondaryText, TillFailureTheme.colors.elevatedSurface, "•")
    }
    Surface(
        modifier = modifier,
        color = background,
        contentColor = foreground,
        shape = RoundedCornerShape(TillFailureRadii.lg),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = TillFailureSpacing.sm, vertical = TillFailureSpacing.xs),
            horizontalArrangement = Arrangement.spacedBy(TillFailureSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(glyph, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
fun TillFailureCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val clickableModifier = if (onClick == null) Modifier else Modifier.clickable(onClick = onClick, role = Role.Button)
    Card(
        modifier = modifier.then(clickableModifier),
        colors = CardDefaults.cardColors(containerColor = TillFailureTheme.colors.surface),
        shape = RoundedCornerShape(TillFailureRadii.md),
        border = BorderStroke(TillFailureBorders.hairline, TillFailureTheme.colors.border),
    ) {
        content()
    }
}

@Composable
fun TillFailureTopBar(
    title: String,
    modifier: Modifier = Modifier,
    onBackClick: (() -> Unit)? = null,
    actionGlyph: String? = null,
    actionDescription: String? = null,
    onActionClick: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(TillFailureTheme.colors.background)
            .padding(horizontal = TillFailureSpacing.sm, vertical = TillFailureSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TillFailureSpacing.xs),
    ) {
        if (onBackClick != null) {
            TillFailureIconButton("‹", "Back", onBackClick)
        } else {
            Spacer(Modifier.width(TillFailureSizing.minimumTouchTarget))
        }
        Text(
            text = title,
            modifier = Modifier
                .weight(1f)
                .semantics { heading() },
            style = MaterialTheme.typography.titleMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (actionGlyph != null && actionDescription != null && onActionClick != null) {
            TillFailureIconButton(actionGlyph, actionDescription, onActionClick)
        } else {
            Spacer(Modifier.width(TillFailureSizing.minimumTouchTarget))
        }
    }
}

@Immutable
data class TillFailureNavigationItem(
    val label: String,
    val glyph: String,
    val selected: Boolean,
)

internal enum class TillFailureBottomNavigationLayout { Weighted, HorizontallyScrollable }

internal fun tillFailureBottomNavigationLayout(
    availableWidthPx: Int,
    requiredItemWidthsPx: List<Int>,
    outerHorizontalPaddingPx: Int,
): TillFailureBottomNavigationLayout {
    if (requiredItemWidthsPx.isEmpty()) return TillFailureBottomNavigationLayout.Weighted
    val compactItemWidthPx =
        (availableWidthPx - 2 * outerHorizontalPaddingPx).coerceAtLeast(0) / requiredItemWidthsPx.size
    return if (requiredItemWidthsPx.all { it <= compactItemWidthPx }) {
        TillFailureBottomNavigationLayout.Weighted
    } else {
        TillFailureBottomNavigationLayout.HorizontallyScrollable
    }
}

@Composable
fun TillFailureBottomNavigation(
    items: List<TillFailureNavigationItem>,
    onItemClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val labelStyle = MaterialTheme.typography.labelSmall
    val glyphStyle = MaterialTheme.typography.titleSmall
    val minimumTouchTargetPx = with(density) { TillFailureSizing.minimumTouchTarget.roundToPx() }
    val outerHorizontalPaddingPx = with(density) { TillFailureSpacing.xs.roundToPx() }
    val requiredItemWidthsPx = items.map { item ->
        maxOf(
            minimumTouchTargetPx,
            textMeasurer.measure(item.label, style = labelStyle, maxLines = 1, softWrap = false).size.width,
            textMeasurer.measure(item.glyph, style = glyphStyle, maxLines = 1, softWrap = false).size.width,
        )
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = TillFailureTheme.colors.surface,
        border = BorderStroke(TillFailureBorders.hairline, TillFailureTheme.colors.border),
        shape = RoundedCornerShape(topStart = TillFailureRadii.md, topEnd = TillFailureRadii.md),
    ) {
        BoxWithConstraints {
            val layout = tillFailureBottomNavigationLayout(
                availableWidthPx = constraints.maxWidth,
                requiredItemWidthsPx = requiredItemWidthsPx,
                outerHorizontalPaddingPx = outerHorizontalPaddingPx,
            )
            when (layout) {
                TillFailureBottomNavigationLayout.Weighted -> Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = TillFailureSpacing.xs, vertical = TillFailureSpacing.xs),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    items.forEachIndexed { index, item ->
                        TillFailureBottomNavigationItem(
                            item = item,
                            onClick = { onItemClick(index) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                TillFailureBottomNavigationLayout.HorizontallyScrollable -> {
                    val listState = rememberLazyListState()
                    val selectedIndex = items.indexOfFirst(TillFailureNavigationItem::selected)
                    LaunchedEffect(selectedIndex) {
                        if (selectedIndex >= 0) listState.animateScrollToItem(selectedIndex)
                    }
                    LazyRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = TillFailureSpacing.xs),
                        state = listState,
                        contentPadding = PaddingValues(horizontal = TillFailureSpacing.xs),
                        horizontalArrangement = Arrangement.spacedBy(TillFailureSpacing.xxs),
                    ) {
                        itemsIndexed(items) { index, item ->
                            TillFailureBottomNavigationItem(
                                item = item,
                                onClick = { onItemClick(index) },
                                modifier = Modifier.widthIn(min = TillFailureSizing.minimumTouchTarget),
                                horizontalPadding = TillFailureSpacing.sm,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TillFailureBottomNavigationItem(
    item: TillFailureNavigationItem,
    onClick: () -> Unit,
    modifier: Modifier,
    horizontalPadding: Dp = 0.dp,
) {
    val foreground = if (item.selected) TillFailureTheme.colors.accent else TillFailureTheme.colors.secondaryText
    Column(
        modifier = modifier
            .heightIn(min = TillFailureSizing.minimumTouchTarget)
            .selectable(
                selected = item.selected,
                role = Role.Tab,
                onClick = onClick,
            )
            .semantics {
                selected = item.selected
                contentDescription = item.label
            }
            .padding(horizontal = horizontalPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(item.glyph, color = foreground, style = MaterialTheme.typography.titleSmall)
        Text(item.label, color = foreground, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

enum class LoggedSetStatus { Current, Completed, Edited, Incomplete, Skipped, Pending }

val LoggedSetStatus.accessibleLabel: String
    get() = when (this) {
        LoggedSetStatus.Current -> "Current"
        LoggedSetStatus.Completed -> "Complete"
        LoggedSetStatus.Edited -> "Edited"
        LoggedSetStatus.Incomplete -> "Incomplete"
        LoggedSetStatus.Skipped -> "Skipped"
        LoggedSetStatus.Pending -> "Pending"
    }

@Composable
fun LoggedSetRow(
    setNumber: Int,
    weight: String,
    reps: String,
    status: LoggedSetStatus,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val tone = when (status) {
        LoggedSetStatus.Current -> TillFailureStatusTone.Info
        LoggedSetStatus.Completed -> TillFailureStatusTone.Success
        LoggedSetStatus.Edited -> TillFailureStatusTone.Warning
        LoggedSetStatus.Incomplete -> TillFailureStatusTone.Error
        LoggedSetStatus.Skipped,
        LoggedSetStatus.Pending,
        -> TillFailureStatusTone.Neutral
    }
    val clickableModifier = if (onClick != null) {
        Modifier.clickable(role = Role.Button, onClick = onClick)
    } else {
        Modifier
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = TillFailureSizing.minimumTouchTarget)
            .clip(RoundedCornerShape(TillFailureRadii.sm))
            .background(TillFailureTheme.colors.elevatedSurface)
            .then(clickableModifier)
            .padding(horizontal = TillFailureSpacing.sm, vertical = TillFailureSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TillFailureSpacing.xs),
    ) {
        Text(setNumber.toString(), modifier = Modifier.width(24.dp), color = TillFailureTheme.colors.secondaryText)
        Text(weight, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(reps, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        TillFailureStatusBadge(status.accessibleLabel, tone)
    }
}

@Composable
fun WorkoutRow(
    title: String,
    summary: String,
    status: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    TillFailureCard(modifier = modifier.fillMaxWidth(), onClick = onClick) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(TillFailureSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(TillFailureSpacing.sm),
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(summary, color = TillFailureTheme.colors.secondaryText, style = MaterialTheme.typography.bodySmall)
            }
            TillFailureStatusBadge(status, TillFailureStatusTone.Success)
        }
    }
}

@Composable
fun ExerciseRow(
    name: String,
    prescription: String,
    modifier: Modifier = Modifier,
    status: String? = null,
    onClick: (() -> Unit)? = null,
) {
    TillFailureCard(modifier = modifier.fillMaxWidth(), onClick = onClick) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(TillFailureSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(TillFailureSpacing.sm),
        ) {
            Box(
                modifier = Modifier.size(40.dp).clip(RoundedCornerShape(TillFailureRadii.sm)).background(TillFailureTheme.colors.elevatedSurface),
                contentAlignment = Alignment.Center,
            ) {
                Text("↗", color = TillFailureTheme.colors.accent)
            }
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleSmall)
                Text(prescription, color = TillFailureTheme.colors.secondaryText, style = MaterialTheme.typography.bodySmall)
            }
            if (status != null) {
                Text(status, color = TillFailureTheme.colors.accent, style = MaterialTheme.typography.labelMedium)
            }
            Text("›", color = TillFailureTheme.colors.secondaryText)
        }
    }
}

@Composable
fun TillFailureStatusBanner(
    title: String,
    message: String,
    tone: TillFailureStatusTone,
    modifier: Modifier = Modifier,
) {
    val (foreground, background, glyph) = when (tone) {
        TillFailureStatusTone.Success -> Triple(TillFailureTheme.colors.success, TillFailureTheme.colors.successSurface, "✓")
        TillFailureStatusTone.Warning -> Triple(TillFailureTheme.colors.warning, TillFailureTheme.colors.warningSurface, "!")
        TillFailureStatusTone.Error -> Triple(TillFailureTheme.colors.error, TillFailureTheme.colors.errorSurface, "×")
        TillFailureStatusTone.Info -> Triple(TillFailureTheme.colors.info, TillFailureTheme.colors.infoSurface, "i")
        TillFailureStatusTone.Neutral -> Triple(TillFailureTheme.colors.secondaryText, TillFailureTheme.colors.elevatedSurface, "•")
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(TillFailureRadii.sm))
            .background(background)
            .padding(TillFailureSpacing.md),
        horizontalArrangement = Arrangement.spacedBy(TillFailureSpacing.sm),
        verticalAlignment = Alignment.Top,
    ) {
        Text(glyph, color = foreground, fontWeight = FontWeight.Bold)
        Column(Modifier.weight(1f)) {
            Text(title, color = foreground, style = MaterialTheme.typography.titleSmall)
            Text(message, color = TillFailureTheme.colors.primaryText, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun TillFailureLoadingState(
    label: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(TillFailureSpacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(TillFailureSpacing.sm),
    ) {
        CircularProgressIndicator(color = TillFailureTheme.colors.accent)
        Text(label, color = TillFailureTheme.colors.secondaryText)
    }
}

@Composable
fun TillFailureEmptyState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onActionClick: (() -> Unit)? = null,
) {
    StatePresentation("○", title, message, modifier, actionLabel, onActionClick)
}

@Composable
fun TillFailureErrorState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    onRetryClick: () -> Unit,
) {
    StatePresentation("!", title, message, modifier, "Retry", onRetryClick)
}

@Composable
private fun StatePresentation(
    glyph: String,
    title: String,
    message: String,
    modifier: Modifier,
    actionLabel: String?,
    onActionClick: (() -> Unit)?,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(TillFailureSpacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(TillFailureSpacing.sm),
    ) {
        Text(glyph, color = TillFailureTheme.colors.accent, style = MaterialTheme.typography.headlineMedium)
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(message, color = TillFailureTheme.colors.secondaryText, style = MaterialTheme.typography.bodyMedium)
        if (actionLabel != null && onActionClick != null) {
            TillFailureSecondaryButton(actionLabel, onActionClick)
        }
    }
}

@Composable
fun TillFailureSnackbar(message: String, modifier: Modifier = Modifier) {
    Snackbar(
        modifier = modifier,
        containerColor = TillFailureTheme.colors.successSurface,
        contentColor = TillFailureTheme.colors.primaryText,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(TillFailureSpacing.xs)) {
            Text("✓", color = TillFailureTheme.colors.success)
            Text(message)
        }
    }
}

@Composable
fun TillFailureConfirmationDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TillFailurePrimaryButton(confirmLabel, onConfirm) },
        dismissButton = { TillFailureTextButton("Cancel", onDismiss) },
        containerColor = TillFailureTheme.colors.surface,
        titleContentColor = TillFailureTheme.colors.primaryText,
        textContentColor = TillFailureTheme.colors.secondaryText,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TillFailureActionSheet(
    title: String,
    message: String,
    primaryLabel: String,
    onPrimaryClick: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = TillFailureTheme.colors.surface,
        contentColor = TillFailureTheme.colors.primaryText,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(
                start = TillFailureSpacing.xl,
                end = TillFailureSpacing.xl,
                bottom = TillFailureSpacing.xxl,
            ),
            verticalArrangement = Arrangement.spacedBy(TillFailureSpacing.md),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(message, color = TillFailureTheme.colors.secondaryText)
            TillFailurePrimaryButton(primaryLabel, onPrimaryClick, Modifier.fillMaxWidth())
            TillFailureSecondaryButton("Cancel", onDismiss, Modifier.fillMaxWidth())
        }
    }
}

@Composable
fun TillFailureSectionTitle(
    eyebrow: String,
    title: String,
    modifier: Modifier = Modifier,
    action: String? = null,
    onActionClick: (() -> Unit)? = null,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(TillFailureSpacing.xs)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(eyebrow.uppercase(), color = TillFailureTheme.colors.accent, style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.weight(1f))
            if (action != null && onActionClick != null) {
                Text(
                    text = action,
                    modifier = Modifier
                        .heightIn(min = TillFailureSizing.minimumTouchTarget)
                        .clickable(role = Role.Button, onClick = onActionClick)
                        .padding(horizontal = TillFailureSpacing.xs, vertical = TillFailureSpacing.sm),
                    color = TillFailureTheme.colors.accent,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
        Text(title, style = MaterialTheme.typography.titleLarge)
    }
}

internal fun tillFailureSectionTitleHasAction(action: String?, onActionClick: (() -> Unit)?): Boolean =
    action != null && onActionClick != null

internal fun tillFailureEmptyStateHasAction(action: String?, onActionClick: (() -> Unit)?): Boolean =
    action != null && onActionClick != null

internal fun tillFailureProgressValue(progress: Float): Float =
    if (progress.isFinite()) progress.coerceIn(0f, 1f) else 0f

@Composable
fun TillFailureProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
    progressDescription: String? = null,
) {
    val normalizedProgress = tillFailureProgressValue(progress)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(CircleShape)
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(normalizedProgress, 0f..1f)
                if (progressDescription != null) stateDescription = progressDescription
            }
            .background(TillFailureTheme.colors.border),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(normalizedProgress)
                .height(4.dp)
                .background(TillFailureTheme.colors.accent),
        )
    }
}

@Composable
fun TillFailureMetric(
    label: String,
    value: String,
    supporting: String,
    modifier: Modifier = Modifier,
) {
    TillFailureCard(modifier) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(TillFailureSpacing.md),
            verticalArrangement = Arrangement.spacedBy(TillFailureSpacing.xs),
        ) {
            Text(label.uppercase(), color = TillFailureTheme.colors.secondaryText, style = MaterialTheme.typography.labelSmall)
            Text(value, style = MaterialTheme.typography.titleLarge)
            Text(supporting, color = TillFailureTheme.colors.success, style = MaterialTheme.typography.bodySmall)
        }
    }
}
