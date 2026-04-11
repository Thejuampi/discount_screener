package com.discountscreener.android.domain.model

data class ProfileTransitionFeedback(
    val startupPhase: DashboardStartupPhase,
    val refreshCompletedSymbols: Int,
    val refreshTargetSymbols: Int,
    val statusMessage: String?,
)

sealed interface ProfileTransitionEvent {
    data class SwitchRequested(
        val profile: String,
        val symbolCount: Int,
    ) : ProfileTransitionEvent

    data class CachedHydrated(
        val profile: String,
        val symbolCount: Int,
        val cachedSymbolCount: Int,
    ) : ProfileTransitionEvent

    data class RefreshStarted(
        val profile: String,
        val symbolCount: Int,
    ) : ProfileTransitionEvent

    data class RefreshProgress(
        val profile: String,
        val completedSymbols: Int,
        val totalSymbols: Int,
    ) : ProfileTransitionEvent

    data class RefreshFinished(
        val activeIssueCount: Int,
    ) : ProfileTransitionEvent
}

fun reduceProfileTransition(event: ProfileTransitionEvent): ProfileTransitionFeedback = when (event) {
    is ProfileTransitionEvent.SwitchRequested -> ProfileTransitionFeedback(
        startupPhase = DashboardStartupPhase.SwitchingProfile,
        refreshCompletedSymbols = 0,
        refreshTargetSymbols = event.symbolCount,
        statusMessage = "Switching to ${event.profile.uppercase()}…",
    )

    is ProfileTransitionEvent.CachedHydrated -> ProfileTransitionFeedback(
        startupPhase = if (event.cachedSymbolCount > 0) {
            DashboardStartupPhase.ShowingCached
        } else {
            DashboardStartupPhase.Ready
        },
        refreshCompletedSymbols = 0,
        refreshTargetSymbols = event.symbolCount,
        statusMessage = if (event.cachedSymbolCount > 0) {
            "Restored cached ${event.profile.uppercase()} state"
        } else {
            "Loaded ${event.profile.uppercase()} symbols"
        },
    )

    is ProfileTransitionEvent.RefreshStarted -> ProfileTransitionFeedback(
        startupPhase = DashboardStartupPhase.Refreshing,
        refreshCompletedSymbols = 0,
        refreshTargetSymbols = event.symbolCount,
        statusMessage = "Refreshing ${event.profile.uppercase()} 0/${event.symbolCount}…",
    )

    is ProfileTransitionEvent.RefreshProgress -> ProfileTransitionFeedback(
        startupPhase = DashboardStartupPhase.Refreshing,
        refreshCompletedSymbols = event.completedSymbols,
        refreshTargetSymbols = event.totalSymbols,
        statusMessage = "Refreshing ${event.profile.uppercase()} ${event.completedSymbols}/${event.totalSymbols}…",
    )

    is ProfileTransitionEvent.RefreshFinished -> ProfileTransitionFeedback(
        startupPhase = DashboardStartupPhase.Ready,
        refreshCompletedSymbols = 0,
        refreshTargetSymbols = 0,
        statusMessage = if (event.activeIssueCount > 0) {
            "Live refresh completed with ${event.activeIssueCount} active issues"
        } else {
            "Live snapshot updated"
        },
    )
}
