package com.delminiusapps.tillfailure.designcatalog

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.delminiusapps.tillfailure.core.designsystem.TillFailureBottomNavigation
import com.delminiusapps.tillfailure.core.designsystem.TillFailureNavigationItem
import com.delminiusapps.tillfailure.core.designsystem.TillFailureTheme
import com.delminiusapps.tillfailure.core.designsystem.TillFailureTopBar
import com.delminiusapps.tillfailure.core.designsystem.TillFailureSpacing

internal data class CatalogTopAction(
    val glyph: String,
    val description: String,
    val onClick: () -> Unit,
)

internal fun catalogTopAction(
    glyph: String?,
    description: String?,
    onClick: (() -> Unit)?,
): CatalogTopAction? = if (glyph != null && description != null && onClick != null) {
    CatalogTopAction(glyph, description, onClick)
} else {
    null
}

@Composable
internal fun CatalogScreenScaffold(
    title: String,
    onBackClick: () -> Unit,
    bottomItems: List<TillFailureNavigationItem>? = null,
    bottomContent: (@Composable () -> Unit)? = null,
    onBottomItemClick: ((Int) -> Unit)? = null,
    topActionGlyph: String? = null,
    topActionDescription: String? = null,
    onTopActionClick: (() -> Unit)? = null,
    snackbarHostState: SnackbarHostState? = null,
    content: @Composable (androidx.compose.foundation.layout.PaddingValues) -> Unit,
) {
    val topAction = catalogTopAction(topActionGlyph, topActionDescription, onTopActionClick)
    Scaffold(
        containerColor = TillFailureTheme.colors.background,
        contentColor = TillFailureTheme.colors.primaryText,
        contentWindowInsets = if (bottomItems == null && bottomContent == null) {
            WindowInsets.navigationBars
        } else {
            WindowInsets(0, 0, 0, 0)
        },
        topBar = {
            TillFailureTopBar(
                title = title,
                onBackClick = onBackClick,
                actionGlyph = topAction?.glyph,
                actionDescription = topAction?.description,
                onActionClick = topAction?.onClick,
                modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars),
            )
        },
        bottomBar = {
            if (bottomItems != null && onBottomItemClick != null) {
                TillFailureBottomNavigation(
                    items = bottomItems,
                    onItemClick = onBottomItemClick,
                    modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars),
                )
            } else if (bottomContent != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(TillFailureTheme.colors.background)
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .padding(horizontal = TillFailureSpacing.md, vertical = TillFailureSpacing.xs),
                ) {
                    bottomContent()
                }
            }
        },
        snackbarHost = {
            if (snackbarHostState != null) {
                SnackbarHost(snackbarHostState)
            }
        },
        content = content,
    )
}
