package com.discountscreener.android.domain.model

data class DashboardNotice(
    val title: String,
    val message: String,
    val severity: DashboardNoticeSeverity = DashboardNoticeSeverity.Warning,
)

enum class DashboardNoticeSeverity {
    Info,
    Warning,
    Error,
}
