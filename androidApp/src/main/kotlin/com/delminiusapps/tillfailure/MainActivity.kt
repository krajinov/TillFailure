package com.delminiusapps.tillfailure

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.delminiusapps.tillfailure.firebase.AndroidProductIdentitySession

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)

        setContent {
            App(
                identityClientProvider = {
                    if (BuildConfig.DEBUG) AndroidProductIdentitySession.get(applicationContext) else null
                },
                showDevelopmentCatalog = BuildConfig.DEBUG,
            )
        }
    }
}
