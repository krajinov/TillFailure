package com.delminiusapps.tillfailure.profile

import com.delminiusapps.tillfailure.onboarding.validOnboardingBasics
import kotlinx.serialization.Serializable

@Serializable
data class BasicProfile(val displayName: String, val locale: String, val timeZoneId: String, val revision: Int) {
    fun valid() = revision in 1..Int.MAX_VALUE - 1 && validOnboardingBasics(displayName, locale, timeZoneId)
    override fun toString() = "BasicProfile(redacted)"
}
@Serializable
data class ProfileUpdate(val displayName: String, val locale: String, val timeZoneId: String, val expectedRevision: Int) {
    fun valid() = expectedRevision in 1..Int.MAX_VALUE - 2 && validOnboardingBasics(displayName, locale, timeZoneId)
    override fun toString() = "ProfileUpdate(redacted)"
}
