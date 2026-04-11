package com.discountscreener.android.domain.model

data class DatabaseTableInfo(
    val tableName: String,
    val rowCount: Long,
)

data class LogTableInfo(
    val tableName: String,
    val rowCount: Long,
    val oldestEpoch: Long?,
    val newestEpoch: Long?,
)

data class SystemStats(
    val databaseFileSizeBytes: Long,
    val tables: List<DatabaseTableInfo>,
    val logTables: List<LogTableInfo>,
)