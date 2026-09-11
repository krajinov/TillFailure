package com.delminiusapps.tillfailure

import android.app.Application
import com.delminiusapps.tillfailure.di.initializeKoin

class TillFailureApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        initializeKoin()
    }
}
