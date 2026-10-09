package com.delminiusapps.tillfailure

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.delminiusapps.tillfailure.app.FoundationNavigation
import com.delminiusapps.tillfailure.core.designsystem.TillFailureTheme
import com.delminiusapps.tillfailure.identity.IdentityRoot
import com.delminiusapps.tillfailure.identity.ProductIdentityClient

internal enum class AppSurface { ProductGate, DevelopmentCatalog }

internal fun appSurface(debugCatalogEnabled: Boolean, catalogRequested: Boolean): AppSurface =
    if (debugCatalogEnabled && catalogRequested) AppSurface.DevelopmentCatalog else AppSurface.ProductGate

/** The catalog has its own fixture navigation and no product identity client or role authority. */
@Composable
fun App(identityClientProvider: () -> ProductIdentityClient? = { null }, showDevelopmentCatalog: Boolean = false) {
    var catalogRequested by remember { mutableStateOf(false) }
    TillFailureTheme {
        when (appSurface(showDevelopmentCatalog, catalogRequested)) {
            AppSurface.ProductGate -> {
                // This branch leaves composition while the fixture catalog is open. On return,
                // resolve the current native generation instead of reusing a retired client.
                val currentClient = remember { identityClientProvider() }
                IdentityRoot(currentClient,
                    onOpenDevelopmentCatalog = if (showDevelopmentCatalog) {{ catalogRequested = true }} else null)
            }
            AppSurface.DevelopmentCatalog -> Column(
                Modifier.fillMaxSize().background(TillFailureTheme.colors.background).safeDrawingPadding()
            ) {
                Button(onClick = { catalogRequested = false }, modifier = Modifier.padding(12.dp)) {
                    Text("Return to product gate")
                }
                Text("Debug visual fixtures only — no account or role access",
                    color = TillFailureTheme.colors.primaryText, modifier = Modifier.padding(horizontal = 12.dp))
                androidx.compose.foundation.layout.Box(Modifier.weight(1f)) {
                    FoundationNavigation(showDevelopmentCatalog = true)
                }
            }
        }
    }
}
