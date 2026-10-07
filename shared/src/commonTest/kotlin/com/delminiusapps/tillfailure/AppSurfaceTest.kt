package com.delminiusapps.tillfailure

import kotlin.test.Test
import kotlin.test.assertEquals

class AppSurfaceTest {
    @Test fun catalogRequiresExplicitDebugEntryAndReturnRestoresTheProductGate() {
        assertEquals(AppSurface.ProductGate, appSurface(debugCatalogEnabled = true, catalogRequested = false))
        assertEquals(AppSurface.DevelopmentCatalog, appSurface(debugCatalogEnabled = true, catalogRequested = true))
        assertEquals(AppSurface.ProductGate, appSurface(debugCatalogEnabled = true, catalogRequested = false))
        assertEquals(AppSurface.ProductGate, appSurface(debugCatalogEnabled = false, catalogRequested = true))
    }
}
