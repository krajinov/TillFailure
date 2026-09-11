package com.delminiusapps.tillfailure.foundation.scoping

/** Milestone-only diagnostic used to make destination ViewModel creation and disposal observable. */
class FoundationScopeProbe {
    private var createdDetailsCount = 0
    private var releasedDetailsCount = 0

    fun registerDetailsScope(): FoundationScopeRegistration {
        createdDetailsCount += 1
        return FoundationScopeRegistration(
            instanceNumber = createdDetailsCount,
            previouslyReleasedCount = releasedDetailsCount,
        )
    }

    fun releaseDetailsScope() {
        releasedDetailsCount += 1
    }

    internal fun snapshot(): FoundationScopeSnapshot = FoundationScopeSnapshot(
        createdDetailsCount = createdDetailsCount,
        releasedDetailsCount = releasedDetailsCount,
    )
}

data class FoundationScopeRegistration(
    val instanceNumber: Int,
    val previouslyReleasedCount: Int,
)

internal data class FoundationScopeSnapshot(
    val createdDetailsCount: Int,
    val releasedDetailsCount: Int,
)
