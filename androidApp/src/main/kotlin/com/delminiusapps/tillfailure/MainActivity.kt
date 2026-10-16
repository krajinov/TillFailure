package com.delminiusapps.tillfailure

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.delminiusapps.tillfailure.firebase.AndroidProductIdentitySession
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)

        setContent {
            if (BuildConfig.DEBUG && intent.hasExtra("tfPr3Visual")) {
                com.delminiusapps.tillfailure.designcatalog.InvitationVisualFixture(
                    intent.getStringExtra("tfPr3Visual") ?: "client", intent.getFloatExtra("tfPr3FontScale", 1f),
                )
                return@setContent
            }
            val density = LocalDensity.current
            val scale = if (BuildConfig.DEBUG) intent.getFloatExtra("tfPr3FontScale", 0f) else 0f
            CompositionLocalProvider(LocalDensity provides if (scale in 1f..2f) Density(density.density, scale) else density) {
                App(
                    identityClientProvider = {
                        if (BuildConfig.DEBUG) AndroidProductIdentitySession.get(applicationContext,
                            configuration = com.delminiusapps.tillfailure.firebase.FirebaseEmulatorConfiguration(
                                host = "10.0.2.2",
                                authPort = intent.getIntExtra("tfAuthPort", 9099),
                                firestorePort = intent.getIntExtra("tfFirestorePort", 8080),
                                functionsPort = intent.getIntExtra("tfFunctionsPort", 5001),
                            )) else null
                    },
                    showDevelopmentCatalog = BuildConfig.DEBUG,
                )
            }
        }
    }
}
