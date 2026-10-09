package com.delminiusapps.tillfailure

import com.delminiusapps.tillfailure.identity.GateStatus
import com.delminiusapps.tillfailure.identity.IdentityState
import com.delminiusapps.tillfailure.identity.canOpenDevelopmentCatalog
import kotlin.test.Test
import kotlin.test.assertEquals

class AppSurfaceTest {
    @Test fun catalogRequiresExplicitDebugEntryAndReturnRestoresTheProductGate() {
        assertEquals(AppSurface.ProductGate, appSurface(debugCatalogEnabled = true, catalogRequested = false))
        assertEquals(AppSurface.DevelopmentCatalog, appSurface(debugCatalogEnabled = true, catalogRequested = true))
        assertEquals(AppSurface.ProductGate, appSurface(debugCatalogEnabled = true, catalogRequested = false))
        assertEquals(AppSurface.ProductGate, appSurface(debugCatalogEnabled = false, catalogRequested = true))
    }

    @Test fun catalogCannotInterruptCleanDepartureOrRecovery() {
        assertEquals(true, canOpenDevelopmentCatalog(IdentityState(status = GateStatus.SignedOut)))
        assertEquals(false, canOpenDevelopmentCatalog(IdentityState(status = GateStatus.SwitchingOut, busy = true)))
        assertEquals(false, canOpenDevelopmentCatalog(IdentityState(status = GateStatus.CleanupRequired)))
        assertEquals(false, canOpenDevelopmentCatalog(IdentityState(status = GateStatus.SignedOut, busy = true)))
    }
}
