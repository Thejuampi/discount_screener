package com.discountscreener.android.ui.dashboard

import com.discountscreener.android.domain.model.ChangeDirection
import com.discountscreener.android.domain.model.RankMovement
import com.discountscreener.android.domain.model.ValuationChange

internal fun money(cents: Long): String = "$" + "%.2f".format(cents / 100.0)

internal fun compactMoney(cents: Long): String {
    val dollars = cents / 100.0
    val sign = if (dollars < 0) "-" else ""
    val absolute = kotlin.math.abs(dollars)
    if (absolute >= 1_000.0) {
        return sign + "$" + compactFinancialNumber(absolute.toLong())
    }
    val decimals = when {
        absolute >= 100.0 -> 0
        absolute >= 10.0 -> 1
        else -> 2
    }
    return sign + "$" + "%.${decimals}f".format(absolute)
}

internal fun compactFinancialNumber(value: Long): String {
    val absolute = kotlin.math.abs(value.toDouble())
    val (scaled, suffix) = when {
        absolute >= 1_000_000_000_000.0 -> value / 1_000_000_000_000.0 to "T"
        absolute >= 1_000_000_000.0 -> value / 1_000_000_000.0 to "B"
        absolute >= 1_000_000.0 -> value / 1_000_000.0 to "M"
        absolute >= 1_000.0 -> value / 1_000.0 to "K"
        else -> return value.toString()
    }
    val decimals = when {
        kotlin.math.abs(scaled) >= 100 -> 0
        kotlin.math.abs(scaled) >= 10 -> 1
        else -> 2
    }
    return "%.${decimals}f%s".format(scaled, suffix)
}

internal fun symbolWithCompany(symbol: String, companyName: String?): String {
    val normalizedCompanyName = companyName?.trim().orEmpty()
    if (normalizedCompanyName.isBlank() || normalizedCompanyName.equals(symbol, ignoreCase = true)) {
        return symbol
    }
    return "$symbol $normalizedCompanyName"
}

internal fun formatPct(bps: Int): String = "%.2f%%".format(bps / 100.0)

internal fun rankMovementLabel(movement: RankMovement?): String? = movement?.let {
    val arrow = if (it.direction == ChangeDirection.Up) "↑" else "↓"
    "$arrow${it.places} rank"
}

internal fun valuationChangeLabel(change: ValuationChange?): String? = change?.let {
    val arrow = if (it.direction == ChangeDirection.Up) "↑" else "↓"
    "Target $arrow${"%.1f".format(kotlin.math.abs(it.changeBps) / 100.0)}%"
}

internal fun formatFileSize(bytes: Long): String = when {
    bytes >= 1_073_741_824L -> "%.1f GB".format(bytes / 1_073_741_824.0)
    bytes >= 1_048_576L -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1_024L -> "%.1f KB".format(bytes / 1_024.0)
    else -> "$bytes B"
}

internal fun formatLogTimestamp(epochSeconds: Long): String =
    java.time.format.DateTimeFormatter.ofLocalizedDateTime(java.time.format.FormatStyle.MEDIUM, java.time.format.FormatStyle.SHORT)
        .format(java.time.Instant.ofEpochSecond(epochSeconds).atZone(java.time.ZoneId.systemDefault()).toLocalDateTime())
