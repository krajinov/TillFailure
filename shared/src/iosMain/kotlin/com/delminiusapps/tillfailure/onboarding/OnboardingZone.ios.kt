package com.delminiusapps.tillfailure.onboarding

import platform.Foundation.NSTimeZone
import platform.Foundation.knownTimeZoneNames

actual fun isKnownOnboardingZone(zone: String): Boolean = zone == "UTC" || zone in NSTimeZone.knownTimeZoneNames
