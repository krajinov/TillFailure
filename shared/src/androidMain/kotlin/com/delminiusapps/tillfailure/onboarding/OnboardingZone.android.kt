package com.delminiusapps.tillfailure.onboarding

actual fun isKnownOnboardingZone(zone: String): Boolean = zone == "UTC" || zone in java.util.TimeZone.getAvailableIDs()
