package com.delminiusapps.tillfailure.core.designsystem

import androidx.compose.ui.graphics.Color
import com.delminiusapps.tillfailure.designcatalog.CatalogFixtures
import com.delminiusapps.tillfailure.designcatalog.TrainerDashboardSegmentLayout
import com.delminiusapps.tillfailure.designcatalog.trainerDashboardSegmentLayout
import com.delminiusapps.tillfailure.designcatalog.trainerDashboardSegmentWeight
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DesignSystemContractTest {
    @Test
    fun effectiveTouchTargetMeetsComposeAccessibilityMinimum() {
        assertTrue(TillFailureSizing.minimumTouchTarget.value >= 48f)
    }

    @Test
    fun primaryButtonLoadingIndicatorUsesContrastingDisabledContentColor() {
        listOf(false, true).forEach { inverted ->
            val palette = tillFailurePrimaryButtonPalette(TillFailureDarkColors, inverted)

            assertEquals(TillFailureDarkColors.elevatedSurface, palette.disabledContainerColor)
            assertEquals(TillFailureDarkColors.disabledText, palette.disabledContentColor)
            assertEquals(palette.disabledContentColor, palette.loadingIndicatorColor)
            assertNotEquals(palette.disabledContainerColor, palette.loadingIndicatorColor)
            assertTrue(contrastRatio(palette.loadingIndicatorColor, palette.disabledContainerColor) >= 3.0)
        }
    }

    @Test
    fun filterChipPaletteCoversAllEnabledAndSelectedCombinations() {
        val enabledUnselected = tillFailureFilterChipPresentation(TillFailureDarkColors, enabled = true, selected = false)
        val enabledSelected = tillFailureFilterChipPresentation(TillFailureDarkColors, enabled = true, selected = true)
        val disabledUnselected = tillFailureFilterChipPresentation(TillFailureDarkColors, enabled = false, selected = false)
        val disabledSelected = tillFailureFilterChipPresentation(TillFailureDarkColors, enabled = false, selected = true)

        assertNotEquals(enabledUnselected.contentColor, disabledUnselected.contentColor)
        assertNotEquals(enabledUnselected.containerColor, disabledUnselected.containerColor)
        assertNotEquals(enabledUnselected.borderColor, disabledUnselected.borderColor)
        assertNotEquals(enabledSelected.contentColor, disabledSelected.contentColor)
        assertNotEquals(enabledSelected.containerColor, disabledSelected.containerColor)
        assertNotEquals(enabledSelected.borderColor, disabledSelected.borderColor)
        assertNotEquals(disabledUnselected, disabledSelected)
        assertTrue(disabledUnselected.exposesDisabledSemantics)
        assertTrue(disabledSelected.exposesDisabledSemantics)
        assertFalse(enabledUnselected.exposesDisabledSemantics)
        assertFalse(enabledSelected.exposesDisabledSemantics)
    }

    @Test
    fun disabledFilterChipLabelsRetainReadableContrast() {
        listOf(false, true).forEach { selected ->
            val presentation = tillFailureFilterChipPresentation(
                TillFailureDarkColors,
                enabled = false,
                selected = selected,
            )

            assertTrue(contrastRatio(presentation.contentColor, presentation.containerColor) >= 4.5)
        }
    }

    @Test
    fun disabledFilterChipCannotDispatchItsCallback() {
        var invocationCount = 0

        dispatchTillFailureFilterChipClick(enabled = false) { invocationCount += 1 }
        assertEquals(0, invocationCount)

        dispatchTillFailureFilterChipClick(enabled = true) { invocationCount += 1 }
        assertEquals(1, invocationCount)
    }

    @Test
    fun workoutStatusesHaveVisibleNonColorLabels() {
        val labels = LoggedSetStatus.entries.map(LoggedSetStatus::accessibleLabel)

        assertEquals(LoggedSetStatus.entries.size, labels.distinct().size)
        assertTrue(labels.all(String::isNotBlank))
    }

    @Test
    fun fixturesPreserveApprovedSampleContinuity() {
        assertEquals("Alex", CatalogFixtures.clientHome.firstName)
        assertEquals("Barbell Bench Press", CatalogFixtures.activeWorkout.exerciseName)
        assertEquals("Maya", CatalogFixtures.trainerDashboard.trainerName)
        assertEquals("Alex Morgan", CatalogFixtures.trainerClientDetails.name)
    }

    @Test
    fun trainerDashboardSegmentsUseContentAwareNormalWidthsAndScrollableLargeText() {
        assertEquals(TrainerDashboardSegmentLayout.Weighted, trainerDashboardSegmentLayout(fontScale = 1f))
        assertEquals(
            TrainerDashboardSegmentLayout.HorizontallyScrollable,
            trainerDashboardSegmentLayout(fontScale = 2f),
        )
        assertTrue(trainerDashboardSegmentWeight("Appointments") > trainerDashboardSegmentWeight("Messages"))
        assertTrue(trainerDashboardSegmentWeight("Messages") > trainerDashboardSegmentWeight("Active"))
    }

    @Test
    fun tillFailureSegmentedControlSelectsWeightedAtNormalScaleAndScrollableAtLargeScale() {
        assertEquals(TillFailureSegmentedControlLayout.Weighted, tillFailureSegmentedControlLayout(fontScale = 1f))
        assertEquals(TillFailureSegmentedControlLayout.Weighted, tillFailureSegmentedControlLayout(fontScale = 1.25f))
        assertEquals(TillFailureSegmentedControlLayout.HorizontallyScrollable, tillFailureSegmentedControlLayout(fontScale = 1.5f))
        assertEquals(TillFailureSegmentedControlLayout.HorizontallyScrollable, tillFailureSegmentedControlLayout(fontScale = 2f))
    }

    @Test
    fun sectionTitleOnlyExposesActionWhenBothLabelAndCallbackArePresent() {
        assertFalse(tillFailureSectionTitleHasAction(action = null, onActionClick = null))
        assertFalse(tillFailureSectionTitleHasAction(action = "View all", onActionClick = null))
        assertFalse(tillFailureSectionTitleHasAction(action = null, onActionClick = {}))
        assertTrue(tillFailureSectionTitleHasAction(action = "View all", onActionClick = {}))
    }

    @Test
    fun emptyStateOnlyExposesActionWhenBothLabelAndCallbackArePresent() {
        assertFalse(tillFailureEmptyStateHasAction(action = null, onActionClick = null))
        assertFalse(tillFailureEmptyStateHasAction(action = "Retry", onActionClick = null))
        assertFalse(tillFailureEmptyStateHasAction(action = null, onActionClick = {}))
        assertTrue(tillFailureEmptyStateHasAction(action = "Retry", onActionClick = {}))
    }

    @Test
    fun progressValuesClampAndRejectNonFiniteInput() {
        assertEquals(0f, tillFailureProgressValue(-0.5f))
        assertEquals(0.375f, tillFailureProgressValue(0.375f))
        assertEquals(1f, tillFailureProgressValue(1.5f))
        assertEquals(0f, tillFailureProgressValue(Float.NaN))
        assertEquals(0f, tillFailureProgressValue(Float.POSITIVE_INFINITY))
    }
}

private fun contrastRatio(first: Color, second: Color): Double {
    val lighter = maxOf(first.relativeLuminance(), second.relativeLuminance())
    val darker = minOf(first.relativeLuminance(), second.relativeLuminance())
    return (lighter + 0.05) / (darker + 0.05)
}

private fun Color.relativeLuminance(): Double =
    0.2126 * red.linearized() + 0.7152 * green.linearized() + 0.0722 * blue.linearized()

private fun Float.linearized(): Double = if (this <= 0.04045f) {
    toDouble() / 12.92
} else {
    ((toDouble() + 0.055) / 1.055).pow(2.4)
}
