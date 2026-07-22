package com.discountscreener.android.data.persistence

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.discountscreener.core.engine.PricingHistoryMerge
import com.discountscreener.core.model.ChartRange
import com.discountscreener.core.model.ChartRangeSummary
import com.discountscreener.core.model.DataProvenance
import com.discountscreener.core.model.DcfAnalysis
import com.discountscreener.core.model.ExternalSignalStatus
import com.discountscreener.core.model.ExternalValuationSignal
import com.discountscreener.core.model.FundamentalSnapshot
import com.discountscreener.core.model.FundamentalTimeseries as CoreFundamentalTimeseries
import com.discountscreener.core.model.HistoricalCandle
import com.discountscreener.core.model.MarketSnapshot
import com.discountscreener.core.model.PersistedSymbolState
import com.discountscreener.core.model.PricingCandle
import com.discountscreener.core.model.PriceHistoryPoint
import com.discountscreener.core.model.QualificationStatus
import com.discountscreener.core.model.IndexEstimatesReport
import com.discountscreener.android.domain.model.DatabaseTableInfo
import com.discountscreener.android.domain.model.DiscoveryJobKind
import com.discountscreener.android.domain.model.DiscoveryJobRecord
import com.discountscreener.android.domain.model.DiscoveryJobStatus
import com.discountscreener.android.data.remote.isUsableCompanyName
import com.discountscreener.android.domain.model.DiscoveryConfig
import com.discountscreener.android.domain.model.LogTableInfo
import com.discountscreener.android.domain.model.SystemStats
import com.discountscreener.core.engine.DiscoveryMembershipMerge
import com.discountscreener.core.engine.DiscoveryScoreRow
import com.discountscreener.core.engine.DiscoveryUniverseEngine
import com.discountscreener.core.engine.OpportunityEngine
import com.discountscreener.core.model.OpportunityScoringModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

enum class PersistenceIssueSource {
    Feed,
    Persistence,
}

enum class PersistenceIssueSeverity {
    Warning,
    Error,
    Critical,
}

data class PersistedChartRecord(
    val symbol: String,
    val range: ChartRange,
    val candles: List<HistoricalCandle>,
    val fetchedAt: Long,
)

data class PersistedIssueRecord(
    val key: String,
    val source: PersistenceIssueSource,
    val severity: PersistenceIssueSeverity,
    val title: String,
    val detail: String,
    val count: Int,
    val firstSeenEvent: Int,
    val lastSeenEvent: Int,
    val active: Boolean,
)

data class PersistenceBootstrap(
    val trackedSymbols: List<String> = emptyList(),
    val watchlist: List<String> = emptyList(),
    val symbolStates: List<PersistedSymbolState> = emptyList(),
    val chartCache: List<PersistedChartRecord> = emptyList(),
    val issues: List<PersistedIssueRecord> = emptyList(),
    val lastPersistedAtEpochSeconds: Long? = null,
)

enum class CaptureKind {
    Snapshot,
    External,
    Fundamentals,
    ChartCandles,
    FundamentalTimeseries,
}

@Serializable
sealed interface RawCapturePayload {
    @Serializable
    data class Snapshot(val value: MarketSnapshot) : RawCapturePayload

    @Serializable
    data class External(val value: ExternalValuationSignal) : RawCapturePayload

    @Serializable
    data class Fundamentals(val value: FundamentalSnapshot) : RawCapturePayload

    @Serializable
    data class Chart(
        val range: ChartRange,
        val candles: List<HistoricalCandle>,
    ) : RawCapturePayload

    @Serializable
    data class FundamentalTimeseries(
        val value: CoreFundamentalTimeseries,
        val provenance: DataProvenance,
    ) : RawCapturePayload
}

data class RawCapture(
    val symbol: String,
    val captureKind: CaptureKind,
    val scopeKey: String?,
    val capturedAt: Long,
    val payload: RawCapturePayload,
)

@Serializable
data class MetricGroupStatus(
    val available: Boolean,
    val stale: Boolean,
)

@Serializable
data class EvaluatedSymbolState(
    val snapshot: MarketSnapshot? = null,
    val externalSignal: ExternalValuationSignal? = null,
    val fundamentals: FundamentalSnapshot? = null,
    val gapBps: Int? = null,
    val qualification: QualificationStatus? = null,
    val externalStatus: ExternalSignalStatus? = null,
    val chartSummaries: List<ChartRangeSummary> = emptyList(),
    val dcfAnalysis: DcfAnalysis? = null,
    val coreStatus: MetricGroupStatus = MetricGroupStatus(available = false, stale = false),
    val fundamentalsStatus: MetricGroupStatus = MetricGroupStatus(available = false, stale = false),
    val relativeStatus: MetricGroupStatus = MetricGroupStatus(available = false, stale = false),
    val dcfStatus: MetricGroupStatus = MetricGroupStatus(available = false, stale = false),
    val chartStatus: MetricGroupStatus = MetricGroupStatus(available = false, stale = false),
    val isWatched: Boolean = false,
)

data class SymbolRevisionInput(
    val symbol: String,
    val evaluatedAt: Long,
    val lastSequence: Int,
    val updateCount: Int,
    val priceHistory: List<PriceHistoryPoint>,
    val payload: EvaluatedSymbolState,
)

data class PersistedRevisionRecord(
    val revisionId: Long,
    val symbol: String,
    val evaluatedAt: Long,
    val lastSequence: Int,
    val updateCount: Int,
    val payload: EvaluatedSymbolState,
)

class SQLiteStateStore(
    private val appContext: Context,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : SQLiteOpenHelper(appContext, DEFAULT_DB_FILE_NAME, null, SQLITE_SCHEMA_VERSION) {

    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.rawQuery("PRAGMA busy_timeout = 5000", emptyArray()).use { }
        db.rawQuery("PRAGMA synchronous = FULL", emptyArray()).use { }
    }

    override fun onCreate(db: SQLiteDatabase) {
        createSchema(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            throw IllegalStateException("unsupported sqlite schema upgrade $oldVersion -> $newVersion")
        }
        if (oldVersion < 3 && newVersion >= 3) {
            db.execSQL("ALTER TABLE symbol_latest ADD COLUMN price_history_json TEXT")
        }
        if (oldVersion < 4 && newVersion >= 4) {
            createPricingCandleSchema(db)
        }
        if (oldVersion < 5 && newVersion >= 5) {
            db.execSQL(
                """
                CREATE TABLE estimates_snapshot (
                    id              INTEGER PRIMARY KEY AUTOINCREMENT,
                    profile_name    TEXT    NOT NULL,
                    computed_at_epoch INTEGER NOT NULL,
                    payload_json    TEXT    NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE INDEX estimates_snapshot_profile_idx ON estimates_snapshot(profile_name, computed_at_epoch, id)",
            )
        }
        if (oldVersion < 6 && newVersion >= 6) {
            createDiscoverySchema(db)
        }
    }

    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        throw IllegalStateException("sqlite schema version $oldVersion is newer than supported version $newVersion")
    }

    suspend fun loadWarmStart(): PersistenceBootstrap = withContext(Dispatchers.IO) {
        val db = readableDatabase
        setMetaValue(db, META_KEY_LAST_STARTUP_AT, nowEpochSeconds().toString())
        PersistenceBootstrap(
            trackedSymbols = loadTrackedSymbols(db),
            watchlist = loadWatchlist(db),
            symbolStates = loadSymbolLatest(db),
            chartCache = loadChartCache(db),
            issues = loadIssues(db),
            lastPersistedAtEpochSeconds = loadMetaValue(db, META_KEY_LAST_PERSISTED_AT)?.toLongOrNull(),
        )
    }

    suspend fun resetWarmStartState() = withContext(Dispatchers.IO) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("tracked_symbol", null, null)
            db.delete("watchlist", null, null)
            db.delete("raw_capture", null, null)
            db.delete("raw_latest", null, null)
            db.delete("pricing_candle", null, null)
            db.delete("symbol_revision", null, null)
            db.delete("symbol_latest", null, null)
            db.delete("issue_state", null, null)
            db.delete("discovery_score", null, null)
            db.delete("discovery_symbol", null, null)
            db.delete("discovery_job", null, null)
            db.delete("meta", "key = ?", arrayOf(META_KEY_LAST_PERSISTED_AT))
            db.delete("meta", "key = ?", arrayOf(META_KEY_DISCOVERY_MIN_SCORE))
            db.delete("meta", "key = ?", arrayOf(META_KEY_DISCOVERY_SCORING_MODEL))
            db.delete("meta", "key = ?", arrayOf(META_KEY_DISCOVERY_UNIVERSE_NAME))
            db.delete("meta", "key = ?", arrayOf(META_KEY_DISCOVERY_LAST_SOURCE_HINT))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    suspend fun getSystemStats(): SystemStats = withContext(Dispatchers.IO) {
        val db = readableDatabase
        SystemStats(
            databaseFileSizeBytes = databaseFileSizeBytes(),
            tables = TABLE_NAMES.map { tableName ->
                DatabaseTableInfo(tableName, rowCount(db, tableName))
            },
            logTables = LOG_TABLE_QUERIES.map { query ->
                LogTableInfo(
                    tableName = query.tableName,
                    rowCount = rowCount(db, query.tableName),
                    oldestEpoch = timestampExtreme(db, query.tableName, query.timestampColumn, "MIN"),
                    newestEpoch = timestampExtreme(db, query.tableName, query.timestampColumn, "MAX"),
                )
            },
        )
    }

    suspend fun pruneOldRevisions(retentionDays: Int): Int = withContext(Dispatchers.IO) {
        val db = writableDatabase
        val cutoff = nowEpochSeconds() - retentionDays.toLong() * 86_400L
        val cutoffArg = arrayOf(cutoff.toString())
        val rawCutoffArg = arrayOf(cutoff.toString())
        db.beginTransaction()
        try {
            db.delete("raw_latest", "capture_id IN (SELECT id FROM raw_capture WHERE captured_at < ?)", rawCutoffArg)
            db.delete("symbol_latest", "revision_id IN (SELECT revision_id FROM symbol_revision WHERE evaluated_at < ?)", cutoffArg)
            val rawDeleted = db.delete("raw_capture", "captured_at < ?", rawCutoffArg)
            val revDeleted = db.delete("symbol_revision", "evaluated_at < ?", cutoffArg)
            db.setTransactionSuccessful()
            rawDeleted + revDeleted
        } finally {
            db.endTransaction()
        }.also {
            db.execSQL("VACUUM")
        }
    }

    private fun databaseFileSizeBytes(): Long {
        val dbFile = appContext.getDatabasePath(DEFAULT_DB_FILE_NAME)
        return if (dbFile.exists()) dbFile.length() else 0L
    }

    private fun rowCount(db: SQLiteDatabase, tableName: String): Long =
        db.compileStatement("SELECT COUNT(*) FROM $tableName").simpleQueryForLong()

    private fun timestampExtreme(db: SQLiteDatabase, tableName: String, column: String, aggregate: String): Long? {
        val sql = "SELECT $aggregate($column) FROM $tableName"
        val cursor = db.rawQuery(sql, emptyArray())
        return cursor.use {
            if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null
        }
    }

    suspend fun replaceTrackedSymbols(symbols: List<String>) = withContext(Dispatchers.IO) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("tracked_symbol", null, null)
            symbols.forEachIndexed { index, symbol ->
                db.insertOrThrow(
                    "tracked_symbol",
                    null,
                    ContentValues().apply {
                        put("position", index)
                        put("symbol", symbol)
                    },
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    suspend fun replaceWatchlist(symbols: List<String>) = withContext(Dispatchers.IO) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("watchlist", null, null)
            symbols.forEach { symbol ->
                db.insertOrThrow(
                    "watchlist",
                    null,
                    ContentValues().apply { put("symbol", symbol) },
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    suspend fun replaceIssues(issues: List<PersistedIssueRecord>) = withContext(Dispatchers.IO) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("issue_state", null, null)
            issues.forEach { issue ->
                db.insertOrThrow(
                    "issue_state",
                    null,
                    ContentValues().apply {
                        put("key", issue.key)
                        put("source", encodeIssueSource(issue.source))
                        put("severity", encodeIssueSeverity(issue.severity))
                        put("title", issue.title)
                        put("detail", issue.detail)
                        put("issue_count", issue.count)
                        put("first_seen_event", issue.firstSeenEvent)
                        put("last_seen_event", issue.lastSeenEvent)
                        put("active", if (issue.active) 1 else 0)
                    },
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    suspend fun persistBatch(
        rawCaptures: List<RawCapture>,
        revisions: List<SymbolRevisionInput>,
    ) = withContext(Dispatchers.IO) {
        if (rawCaptures.isEmpty() && revisions.isEmpty()) {
            return@withContext
        }

        val db = writableDatabase
        db.beginTransaction()
        try {
            var latestTimestamp: Long? = null

            rawCaptures.forEach { capture ->
                val captureId = db.insertOrThrow(
                    "raw_capture",
                    null,
                    ContentValues().apply {
                        put("symbol", capture.symbol)
                        put("capture_kind", encodeCaptureKind(capture.captureKind))
                        put("scope_key", capture.scopeKey)
                        put("captured_at", capture.capturedAt)
                        put("payload_json", json.encodeToString(capture.payload))
                    },
                )
                db.insertWithOnConflict(
                    "raw_latest",
                    null,
                    ContentValues().apply {
                        put("symbol", capture.symbol)
                        put("capture_key", rawCaptureKey(capture))
                        put("capture_id", captureId)
                    },
                    SQLiteDatabase.CONFLICT_REPLACE,
                )
                persistPricingCandles(db, capture)
                latestTimestamp = maxOf(latestTimestamp ?: 0L, capture.capturedAt)
            }

            revisions.forEach { revision ->
                val payloadJson = json.encodeToString(revision.payload)
                val snapshotJson = revision.payload.snapshot?.let(json::encodeToString)
                val externalJson = revision.payload.externalSignal?.let(json::encodeToString)
                val fundamentalsJson = revision.payload.fundamentals?.let(json::encodeToString)
                val priceHistoryJson = json.encodeToString(revision.priceHistory)

                val revisionId = db.insertOrThrow(
                    "symbol_revision",
                    null,
                    ContentValues().apply {
                        put("symbol", revision.symbol)
                        put("evaluated_at", revision.evaluatedAt)
                        put("last_sequence", revision.lastSequence)
                        put("update_count", revision.updateCount)
                        put("payload_json", payloadJson)
                        put("snapshot_json", snapshotJson)
                        put("external_json", externalJson)
                        put("fundamentals_json", fundamentalsJson)
                    },
                )

                db.insertWithOnConflict(
                    "symbol_latest",
                    null,
                    ContentValues().apply {
                        put("symbol", revision.symbol)
                        put("revision_id", revisionId)
                        put("evaluated_at", revision.evaluatedAt)
                        put("last_sequence", revision.lastSequence)
                        put("update_count", revision.updateCount)
                        put("payload_json", payloadJson)
                        put("snapshot_json", snapshotJson)
                        put("external_json", externalJson)
                        put("fundamentals_json", fundamentalsJson)
                        put("price_history_json", priceHistoryJson)
                    },
                    SQLiteDatabase.CONFLICT_REPLACE,
                )
                latestTimestamp = maxOf(latestTimestamp ?: 0L, revision.evaluatedAt)
            }

            latestTimestamp?.let { setMetaValue(db, META_KEY_LAST_PERSISTED_AT, it.toString()) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    suspend fun loadRevisionHistory(symbol: String): List<PersistedRevisionRecord> = withContext(Dispatchers.IO) {
        val db = readableDatabase
        db.rawQuery(
            """
                SELECT revision_id, symbol, evaluated_at, last_sequence, update_count, payload_json
                FROM symbol_revision
                WHERE symbol = ?
                ORDER BY evaluated_at ASC, revision_id ASC
            """.trimIndent(),
            arrayOf(symbol),
        ).useRows { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        PersistedRevisionRecord(
                            revisionId = cursor.getLong(0),
                            symbol = cursor.getString(1),
                            evaluatedAt = cursor.getLong(2),
                            lastSequence = cursor.getInt(3),
                            updateCount = cursor.getInt(4),
                            payload = json.decodeFromString(cursor.getString(5)),
                        ),
                    )
                }
            }
        }
    }

    private fun createSchema(db: SQLiteDatabase) {
        db.execSQL(
            """
                CREATE TABLE meta (
                    key TEXT PRIMARY KEY,
                    value TEXT NOT NULL
                )
            """.trimIndent(),
        )
        db.execSQL(
            """
                CREATE TABLE tracked_symbol (
                    position INTEGER NOT NULL,
                    symbol TEXT PRIMARY KEY
                )
            """.trimIndent(),
        )
        db.execSQL("CREATE TABLE watchlist (symbol TEXT PRIMARY KEY)")
        db.execSQL(
            """
                CREATE TABLE raw_capture (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    symbol TEXT NOT NULL,
                    capture_kind TEXT NOT NULL,
                    scope_key TEXT,
                    captured_at INTEGER NOT NULL,
                    payload_json TEXT NOT NULL
                )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX raw_capture_symbol_idx ON raw_capture(symbol, captured_at, id)")
        db.execSQL(
            """
                CREATE TABLE raw_latest (
                    symbol TEXT NOT NULL,
                    capture_key TEXT NOT NULL,
                    capture_id INTEGER NOT NULL,
                    PRIMARY KEY(symbol, capture_key)
                )
            """.trimIndent(),
        )
        createPricingCandleSchema(db)
        db.execSQL(
            """
                CREATE TABLE symbol_revision (
                    revision_id INTEGER PRIMARY KEY AUTOINCREMENT,
                    symbol TEXT NOT NULL,
                    evaluated_at INTEGER NOT NULL,
                    last_sequence INTEGER NOT NULL,
                    update_count INTEGER NOT NULL,
                    payload_json TEXT NOT NULL,
                    snapshot_json TEXT,
                    external_json TEXT,
                    fundamentals_json TEXT
                )
            """.trimIndent(),
        )
        db.execSQL(
            """
                CREATE INDEX symbol_revision_symbol_idx
                ON symbol_revision(symbol, evaluated_at, revision_id)
            """.trimIndent(),
        )
        db.execSQL(
            """
                CREATE TABLE symbol_latest (
                    symbol TEXT PRIMARY KEY,
                    revision_id INTEGER NOT NULL,
                    evaluated_at INTEGER NOT NULL,
                    last_sequence INTEGER NOT NULL,
                    update_count INTEGER NOT NULL,
                    payload_json TEXT NOT NULL,
                    snapshot_json TEXT,
                    external_json TEXT,
                    fundamentals_json TEXT,
                    price_history_json TEXT
                )
            """.trimIndent(),
        )
        db.execSQL(
            """
                CREATE TABLE issue_state (
                    key TEXT PRIMARY KEY,
                    source TEXT NOT NULL,
                    severity TEXT NOT NULL,
                    title TEXT NOT NULL,
                    detail TEXT NOT NULL,
                    issue_count INTEGER NOT NULL,
                    first_seen_event INTEGER NOT NULL,
                    last_seen_event INTEGER NOT NULL,
                    active INTEGER NOT NULL
                )
            """.trimIndent(),
        )
        db.execSQL(
            """
                CREATE TABLE estimates_snapshot (
                    id              INTEGER PRIMARY KEY AUTOINCREMENT,
                    profile_name    TEXT    NOT NULL,
                    computed_at_epoch INTEGER NOT NULL,
                    payload_json    TEXT    NOT NULL
                )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX estimates_snapshot_profile_idx ON estimates_snapshot(profile_name, computed_at_epoch, id)",
        )
        createDiscoverySchema(db)
    }

    private fun loadTrackedSymbols(db: SQLiteDatabase): List<String> =
        db.rawQuery(
            "SELECT symbol FROM tracked_symbol ORDER BY position ASC",
            emptyArray(),
        ).useRows { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(cursor.getString(0))
                }
            }
        }

    private fun loadWatchlist(db: SQLiteDatabase): List<String> =
        db.rawQuery(
            "SELECT symbol FROM watchlist ORDER BY symbol ASC",
            emptyArray(),
        ).useRows { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(cursor.getString(0))
                }
            }
        }

    private fun loadSymbolLatest(db: SQLiteDatabase): List<PersistedSymbolState> =
        db.rawQuery(
            """
                SELECT symbol, snapshot_json, external_json, fundamentals_json, last_sequence, update_count, price_history_json, payload_json
                FROM symbol_latest
                ORDER BY symbol ASC
            """.trimIndent(),
            emptyArray(),
        ).useRows { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val payload = cursor.getNullableString(7)
                        ?.let { runCatching { json.decodeFromString<EvaluatedSymbolState>(it) }.getOrNull() }
                    add(
                        PersistedSymbolState(
                            symbol = cursor.getString(0),
                            snapshot = cursor.getNullableString(1)?.let { json.decodeFromString(it) },
                            externalSignal = cursor.getNullableString(2)?.let { json.decodeFromString(it) },
                            fundamentals = cursor.getNullableString(3)?.let { json.decodeFromString(it) },
                            lastSequence = cursor.getInt(4),
                            updateCount = cursor.getInt(5),
                            priceHistory = cursor.getNullableString(6)?.let { json.decodeFromString(it) } ?: emptyList(),
                            dcfAnalysis = payload?.dcfAnalysis,
                        ),
                    )
                }
            }
        }

    private fun loadChartCache(db: SQLiteDatabase): List<PersistedChartRecord> =
        loadLatestRawChartCache(db)

    private fun loadPricingCandleCache(db: SQLiteDatabase, symbolFilter: String? = null): List<PersistedChartRecord> =
        db.rawQuery(
            """
                SELECT symbol, chart_range, captured_at, epoch_seconds,
                    open_cents, high_cents, low_cents, close_cents, volume
                FROM pricing_candle
                ${if (symbolFilter == null) "" else "WHERE symbol = ?"}
                ORDER BY symbol ASC, chart_range ASC, epoch_seconds ASC
            """.trimIndent(),
            symbolFilter?.let { arrayOf(it) } ?: emptyArray(),
        ).useRows { cursor ->
            val grouped = linkedMapOf<Pair<String, ChartRange>, MutableList<Pair<Long, HistoricalCandle>>>()
            while (cursor.moveToNext()) {
                val symbol = cursor.getString(0)
                val range = runCatching { ChartRange.valueOf(cursor.getString(1)) }.getOrNull() ?: continue
                val capturedAt = cursor.getLong(2)
                val candle = HistoricalCandle(
                    epochSeconds = cursor.getLong(3),
                    openCents = cursor.getLong(4),
                    highCents = cursor.getLong(5),
                    lowCents = cursor.getLong(6),
                    closeCents = cursor.getLong(7),
                    volume = cursor.getLong(8),
                )
                grouped.getOrPut(symbol to range) { mutableListOf() } += capturedAt to candle
            }
            grouped.map { (key, values) ->
                PersistedChartRecord(
                    symbol = key.first,
                    range = key.second,
                    candles = values.map { it.second },
                    fetchedAt = values.maxOfOrNull { it.first } ?: 0L,
                )
            }
        }

    private fun loadLatestRawChartCache(db: SQLiteDatabase): List<PersistedChartRecord> =
        db.rawQuery(
            """
                SELECT raw_capture.symbol, raw_capture.captured_at, raw_capture.payload_json
                FROM raw_latest
                JOIN raw_capture ON raw_capture.id = raw_latest.capture_id
                WHERE raw_latest.capture_key LIKE 'chart:%'
                ORDER BY raw_capture.symbol ASC, raw_latest.capture_key ASC
            """.trimIndent(),
            emptyArray(),
        ).useRows { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val payload = json.decodeFromString<RawCapturePayload>(cursor.getString(2))
                    if (payload is RawCapturePayload.Chart) {
                        add(
                            PersistedChartRecord(
                                symbol = cursor.getString(0),
                                range = payload.range,
                                candles = payload.candles,
                                fetchedAt = cursor.getLong(1),
                            ),
                        )
                    }
                }
            }
        }

    private fun loadRawChartHistory(db: SQLiteDatabase, symbol: String): List<PersistedChartRecord> =
        db.rawQuery(
            """
                SELECT captured_at, payload_json
                FROM raw_capture
                WHERE symbol = ? AND capture_kind = ?
                ORDER BY captured_at ASC, id ASC
            """.trimIndent(),
            arrayOf(symbol, encodeCaptureKind(CaptureKind.ChartCandles)),
        ).useRows { cursor ->
            val grouped = linkedMapOf<ChartRange, LinkedHashMap<Long, Pair<Long, HistoricalCandle>>>()
            while (cursor.moveToNext()) {
                val capturedAt = cursor.getLong(0)
                val payload = json.decodeFromString<RawCapturePayload>(cursor.getString(1))
                if (payload is RawCapturePayload.Chart) {
                    val values = grouped.getOrPut(payload.range) { linkedMapOf() }
                    payload.candles.forEach { candle ->
                        values[candle.epochSeconds] = capturedAt to candle
                    }
                }
            }
            grouped.map { (range, values) ->
                PersistedChartRecord(
                    symbol = symbol,
                    range = range,
                    candles = values.values.map { it.second }.sortedBy { it.epochSeconds },
                    fetchedAt = values.values.maxOfOrNull { it.first } ?: 0L,
                )
            }
        }

    private fun createPricingCandleSchema(db: SQLiteDatabase) {
        db.execSQL(
            """
                CREATE TABLE IF NOT EXISTS pricing_candle (
                    symbol TEXT NOT NULL,
                    chart_range TEXT NOT NULL,
                    captured_at INTEGER NOT NULL,
                    epoch_seconds INTEGER NOT NULL,
                    open_cents INTEGER NOT NULL,
                    high_cents INTEGER NOT NULL,
                    low_cents INTEGER NOT NULL,
                    close_cents INTEGER NOT NULL,
                    volume INTEGER NOT NULL,
                    PRIMARY KEY(symbol, chart_range, epoch_seconds)
                )
            """.trimIndent(),
        )
        db.execSQL(
            """
                CREATE INDEX IF NOT EXISTS pricing_candle_symbol_range_idx
                ON pricing_candle(symbol, chart_range, epoch_seconds)
            """.trimIndent(),
        )
    }

    suspend fun loadPricingHistory(symbol: String): List<PersistedChartRecord> = withContext(Dispatchers.IO) {
        val db = readableDatabase
        mergePersistedChartHistory(
            symbol = symbol,
            existing = loadRawChartHistory(db, symbol),
            incoming = loadPricingCandleCache(db, symbol),
        )
    }

    private fun persistPricingCandles(db: SQLiteDatabase, capture: RawCapture) {
        if (capture.captureKind != CaptureKind.ChartCandles) return
        val payload = capture.payload as? RawCapturePayload.Chart ?: return
        val existingCandles = loadPricingCandleCache(db, capture.symbol)
            .firstOrNull { record -> record.range == payload.range }
            ?.candles
            .orEmpty()
        val mergedCandles = PricingHistoryMerge.merge(
            existing = existingCandles.map { candle -> PricingCandle(capture.symbol, payload.range, candle) },
            incoming = payload.candles.map { candle -> PricingCandle(capture.symbol, payload.range, candle) },
        ).map { candle -> candle.candle }

        db.delete(
            "pricing_candle",
            "symbol = ? AND chart_range = ?",
            arrayOf(capture.symbol, payload.range.name),
        )
        mergedCandles.forEach { candle ->
            db.insertWithOnConflict(
                "pricing_candle",
                null,
                ContentValues().apply {
                    put("symbol", capture.symbol)
                    put("chart_range", payload.range.name)
                    put("captured_at", capture.capturedAt)
                    put("epoch_seconds", candle.epochSeconds)
                    put("open_cents", candle.openCents)
                    put("high_cents", candle.highCents)
                    put("low_cents", candle.lowCents)
                    put("close_cents", candle.closeCents)
                    put("volume", candle.volume)
                },
                SQLiteDatabase.CONFLICT_REPLACE,
            )
        }
    }

    private fun mergePersistedChartHistory(
        symbol: String,
        existing: List<PersistedChartRecord>,
        incoming: List<PersistedChartRecord>,
    ): List<PersistedChartRecord> {
        val mergedByRange = linkedMapOf<ChartRange, PersistedChartRecord>()
        existing.forEach { chart ->
            mergedByRange[chart.range] = chart
        }
        incoming.forEach { chart ->
            val previous = mergedByRange[chart.range]
            mergedByRange[chart.range] = if (previous == null) {
                chart
            } else {
                PersistedChartRecord(
                    symbol = symbol,
                    range = chart.range,
                    candles = PricingHistoryMerge.merge(
                        existing = previous.candles.map { PricingCandle(symbol, chart.range, it) },
                        incoming = chart.candles.map { PricingCandle(symbol, chart.range, it) },
                    ).map { it.candle },
                    fetchedAt = maxOf(previous.fetchedAt, chart.fetchedAt),
                )
            }
        }
        return mergedByRange.values.toList()
    }

    private fun loadIssues(db: SQLiteDatabase): List<PersistedIssueRecord> =
        db.rawQuery(
            """
                SELECT key, source, severity, title, detail, issue_count, first_seen_event, last_seen_event, active
                FROM issue_state
                ORDER BY active DESC, last_seen_event DESC
            """.trimIndent(),
            emptyArray(),
        ).useRows { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        PersistedIssueRecord(
                            key = cursor.getString(0),
                            source = decodeIssueSource(cursor.getString(1)),
                            severity = decodeIssueSeverity(cursor.getString(2)),
                            title = cursor.getString(3),
                            detail = cursor.getString(4),
                            count = cursor.getInt(5),
                            firstSeenEvent = cursor.getInt(6),
                            lastSeenEvent = cursor.getInt(7),
                            active = cursor.getInt(8) != 0,
                        ),
                    )
                }
            }
        }

    private fun setMetaValue(db: SQLiteDatabase, key: String, value: String) {
        db.insertWithOnConflict(
            "meta",
            null,
            ContentValues().apply {
                put("key", key)
                put("value", value)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    private fun loadMetaValue(db: SQLiteDatabase, key: String): String? =
        db.rawQuery("SELECT value FROM meta WHERE key = ?", arrayOf(key)).useRows { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }

    private fun rawCaptureKey(capture: RawCapture): String = when (capture.captureKind) {
        CaptureKind.Snapshot -> "snapshot"
        CaptureKind.External -> "external"
        CaptureKind.Fundamentals -> "fundamentals"
        CaptureKind.ChartCandles -> "chart:${capture.scopeKey ?: "unknown"}"
        CaptureKind.FundamentalTimeseries -> "fundamental-timeseries"
    }

    private fun encodeCaptureKind(captureKind: CaptureKind): String = when (captureKind) {
        CaptureKind.Snapshot -> "snapshot"
        CaptureKind.External -> "external"
        CaptureKind.Fundamentals -> "fundamentals"
        CaptureKind.ChartCandles -> "chart-candles"
        CaptureKind.FundamentalTimeseries -> "fundamental-timeseries"
    }

    private fun encodeIssueSource(source: PersistenceIssueSource): String = when (source) {
        PersistenceIssueSource.Feed -> "feed"
        PersistenceIssueSource.Persistence -> "persistence"
    }

    private fun decodeIssueSource(value: String): PersistenceIssueSource = when (value) {
        "feed" -> PersistenceIssueSource.Feed
        "persistence" -> PersistenceIssueSource.Persistence
        else -> PersistenceIssueSource.Persistence
    }

    private fun encodeIssueSeverity(severity: PersistenceIssueSeverity): String = when (severity) {
        PersistenceIssueSeverity.Warning -> "warning"
        PersistenceIssueSeverity.Error -> "error"
        PersistenceIssueSeverity.Critical -> "critical"
    }

    private fun decodeIssueSeverity(value: String): PersistenceIssueSeverity = when (value) {
        "warning" -> PersistenceIssueSeverity.Warning
        "error" -> PersistenceIssueSeverity.Error
        "critical" -> PersistenceIssueSeverity.Critical
        else -> PersistenceIssueSeverity.Error
    }

    /**
     * Persists an estimates snapshot.
     * When [replaceSameDay] is true, removes any existing rows for the same profile + UTC day first
     * so each calendar day keeps a single durable point.
     */
    suspend fun saveEstimatesSnapshot(
        report: IndexEstimatesReport,
        replaceSameDay: Boolean = false,
    ) = withContext(Dispatchers.IO) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            if (replaceSameDay) {
                db.execSQL(
                    """
                    DELETE FROM estimates_snapshot
                    WHERE profile_name = ?
                      AND date(computed_at_epoch, 'unixepoch') = date(?, 'unixepoch')
                    """.trimIndent(),
                    arrayOf(report.profileName, report.computedAtEpochSeconds.toString()),
                )
            }
            db.insertOrThrow(
                "estimates_snapshot",
                null,
                ContentValues().apply {
                    put("profile_name", report.profileName)
                    put("computed_at_epoch", report.computedAtEpochSeconds)
                    put("payload_json", json.encodeToString(report))
                },
            )
            pruneEstimatesHistoryLocked(db, report.profileName)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    suspend fun getEstimatesHistory(profileName: String): List<IndexEstimatesReport> =
        withContext(Dispatchers.IO) {
            // Newest N first, then reverse to chronological order for charts.
            readableDatabase.rawQuery(
                """
                SELECT payload_json FROM (
                    SELECT payload_json, computed_at_epoch, id
                    FROM estimates_snapshot
                    WHERE profile_name = ?
                    ORDER BY computed_at_epoch DESC, id DESC
                    LIMIT $ESTIMATES_HISTORY_LIMIT
                )
                ORDER BY computed_at_epoch ASC, id ASC
                """.trimIndent(),
                arrayOf(profileName),
            ).useRows { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(json.decodeFromString<IndexEstimatesReport>(cursor.getString(0)))
                    }
                }
            }
        }

    /**
     * Replaces all estimates history for [profileName] with [reports]
     * (used to collapse legacy multi-row days into one point per UTC day).
     */
    suspend fun replaceEstimatesHistory(
        profileName: String,
        reports: List<IndexEstimatesReport>,
    ) = withContext(Dispatchers.IO) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("estimates_snapshot", "profile_name = ?", arrayOf(profileName))
            for (report in reports) {
                db.insertOrThrow(
                    "estimates_snapshot",
                    null,
                    ContentValues().apply {
                        put("profile_name", report.profileName)
                        put("computed_at_epoch", report.computedAtEpochSeconds)
                        put("payload_json", json.encodeToString(report))
                    },
                )
            }
            pruneEstimatesHistoryLocked(db, profileName)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun pruneEstimatesHistoryLocked(db: SQLiteDatabase, profileName: String) {
        db.execSQL(
            """
            DELETE FROM estimates_snapshot
            WHERE profile_name = ?
              AND id NOT IN (
                SELECT id FROM estimates_snapshot
                WHERE profile_name = ?
                ORDER BY computed_at_epoch DESC, id DESC
                LIMIT $ESTIMATES_HISTORY_LIMIT
              )
            """.trimIndent(),
            arrayOf(profileName, profileName),
        )
    }

    suspend fun loadDiscoveryConfig(): DiscoveryConfig = withContext(Dispatchers.IO) {
        val db = readableDatabase
        val scoringModel = loadMetaValue(db, META_KEY_DISCOVERY_SCORING_MODEL)
            ?.let { raw -> OpportunityScoringModel.entries.firstOrNull { it.name == raw } }
            ?: DiscoveryConfig.DEFAULT_SCORING_MODEL
        val defaultMin = OpportunityEngine.actAtOrAboveScore(scoringModel)
        DiscoveryConfig(
            minScore = loadMetaValue(db, META_KEY_DISCOVERY_MIN_SCORE)?.toIntOrNull() ?: defaultMin,
            scoringModel = scoringModel,
            universeName = loadMetaValue(db, META_KEY_DISCOVERY_UNIVERSE_NAME)
                ?: DiscoveryConfig.DEFAULT_UNIVERSE_NAME,
        )
    }

    suspend fun saveDiscoveryConfig(config: DiscoveryConfig) = withContext(Dispatchers.IO) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            setMetaValue(db, META_KEY_DISCOVERY_MIN_SCORE, config.minScore.toString())
            setMetaValue(db, META_KEY_DISCOVERY_SCORING_MODEL, config.scoringModel.name)
            setMetaValue(db, META_KEY_DISCOVERY_UNIVERSE_NAME, config.universeName)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    suspend fun discoverySymbolCount(): Int = withContext(Dispatchers.IO) {
        readableDatabase.compileStatement("SELECT COUNT(*) FROM discovery_symbol").simpleQueryForLong().toInt()
    }

    suspend fun loadDiscoverySymbols(): List<String> = withContext(Dispatchers.IO) {
        readableDatabase.rawQuery(
            "SELECT symbol FROM discovery_symbol ORDER BY symbol ASC",
            emptyArray(),
        ).useRows { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(cursor.getString(0))
                }
            }
        }
    }

    /**
     * Applies membership merge from a seed list. Returns the merge plan that was applied.
     * Does not fetch market data. Preserves scores for kept symbols; drops scores for removed ones.
     */
    suspend fun applyDiscoveryMembershipMerge(
        seed: Collection<String>,
        sourceUniverse: String,
    ): DiscoveryMembershipMerge = withContext(Dispatchers.IO) {
        val db = writableDatabase
        val existing = db.rawQuery(
            "SELECT symbol FROM discovery_symbol",
            emptyArray(),
        ).useRows { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(cursor.getString(0))
                }
            }
        }
        val plan = DiscoveryUniverseEngine.mergeMembership(seed = seed, existing = existing)
        val now = nowEpochSeconds()
        db.beginTransaction()
        try {
            plan.toRemove.forEach { symbol ->
                db.delete("discovery_score", "symbol = ?", arrayOf(symbol))
                db.delete("discovery_symbol", "symbol = ?", arrayOf(symbol))
            }
            plan.toAdd.forEach { symbol ->
                db.insertWithOnConflict(
                    "discovery_symbol",
                    null,
                    ContentValues().apply {
                        put("symbol", symbol)
                        putNull("company_name")
                        put("added_at", now)
                        put("source_universe", sourceUniverse)
                    },
                    SQLiteDatabase.CONFLICT_IGNORE,
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        plan
    }

    suspend fun upsertDiscoveryScores(rows: List<DiscoveryScoreRow>) = withContext(Dispatchers.IO) {
        if (rows.isEmpty()) return@withContext
        val db = writableDatabase
        db.beginTransaction()
        try {
            rows.forEach { row ->
                db.insertWithOnConflict(
                    "discovery_score",
                    null,
                    ContentValues().apply {
                        put("symbol", row.symbol)
                        put("composite_score", row.compositeScore)
                        putNullableInt("fundamentals_score", row.fundamentalsScore)
                        putNullableInt("technical_score", row.technicalScore)
                        putNullableInt("forecast_score", row.forecastScore)
                        put("coverage_count", row.coverageCount)
                        putNullableLong("market_price_cents", row.marketPriceCents)
                        putNullableInt("upside_bps", row.upsideBps)
                        putNullableInt("gap_bps", row.gapBps)
                        putNullableString("confidence", row.confidence)
                        put("is_qualified", if (row.isQualified) 1 else 0)
                        put("scoring_model", row.scoringModel)
                        put("scored_at", row.scoredAtEpochSeconds)
                        putNullableString("last_error", row.lastError)
                    },
                    SQLiteDatabase.CONFLICT_REPLACE,
                )
                row.companyName?.takeIf(::isUsableCompanyName)?.let { companyName ->
                    db.update(
                        "discovery_symbol",
                        ContentValues().apply { put("company_name", companyName) },
                        "symbol = ?",
                        arrayOf(row.symbol),
                    )
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    suspend fun queryDiscoveryScores(
        minScore: Int,
        limit: Int,
        offset: Int = 0,
        qualifiedOnly: Boolean = true,
    ): List<DiscoveryScoreRow> = withContext(Dispatchers.IO) {
        val qualifiedClause = if (qualifiedOnly) "AND s.is_qualified = 1" else ""
        readableDatabase.rawQuery(
            """
            SELECT s.symbol, d.company_name, s.composite_score, s.fundamentals_score, s.technical_score,
                   s.forecast_score, s.coverage_count, s.market_price_cents, s.upside_bps, s.gap_bps,
                   s.confidence, s.is_qualified, s.scoring_model, s.scored_at, s.last_error
            FROM discovery_score s
            LEFT JOIN discovery_symbol d ON d.symbol = s.symbol
            WHERE s.composite_score >= ?
              $qualifiedClause
            ORDER BY s.composite_score DESC, s.coverage_count DESC, s.symbol ASC
            LIMIT ? OFFSET ?
            """.trimIndent(),
            arrayOf(minScore.toString(), limit.toString(), offset.toString()),
        ).useRows { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        DiscoveryScoreRow(
                            symbol = cursor.getString(0),
                            companyName = cursor.getNullableString(1)?.takeIf(::isUsableCompanyName),
                            compositeScore = cursor.getInt(2),
                            fundamentalsScore = cursor.getNullableInt(3),
                            technicalScore = cursor.getNullableInt(4),
                            forecastScore = cursor.getNullableInt(5),
                            coverageCount = cursor.getInt(6),
                            marketPriceCents = cursor.getNullableLong(7),
                            upsideBps = cursor.getNullableInt(8),
                            gapBps = cursor.getNullableInt(9),
                            confidence = cursor.getNullableString(10),
                            isQualified = cursor.getInt(11) != 0,
                            scoringModel = cursor.getString(12),
                            scoredAtEpochSeconds = cursor.getLong(13),
                            lastError = cursor.getNullableString(14),
                        ),
                    )
                }
            }
        }
    }

    suspend fun countDiscoveryScores(
        minScore: Int,
        qualifiedOnly: Boolean = true,
    ): Int = withContext(Dispatchers.IO) {
        val qualifiedClause = if (qualifiedOnly) "AND is_qualified = 1" else ""
        readableDatabase.compileStatement(
            """
            SELECT COUNT(*) FROM discovery_score
            WHERE composite_score >= ?
              $qualifiedClause
            """.trimIndent(),
        ).use { statement ->
            statement.bindLong(1, minScore.toLong())
            statement.simpleQueryForLong().toInt()
        }
    }

    suspend fun discoveryScoredSymbolCount(): Int = withContext(Dispatchers.IO) {
        readableDatabase.compileStatement("SELECT COUNT(*) FROM discovery_score").simpleQueryForLong().toInt()
    }

    suspend fun loadDiscoveryMaxScoredAt(): Long? = withContext(Dispatchers.IO) {
        readableDatabase.rawQuery(
            "SELECT MAX(scored_at) FROM discovery_score",
            emptyArray(),
        ).useRows { cursor ->
            if (!cursor.moveToFirst() || cursor.isNull(0)) {
                null
            } else {
                cursor.getLong(0)
            }
        }
    }

    suspend fun loadDiscoveryLastSourceHint(): String? = withContext(Dispatchers.IO) {
        loadMetaValue(readableDatabase, META_KEY_DISCOVERY_LAST_SOURCE_HINT)
    }

    suspend fun saveDiscoveryLastSourceHint(hint: String) = withContext(Dispatchers.IO) {
        setMetaValue(writableDatabase, META_KEY_DISCOVERY_LAST_SOURCE_HINT, hint)
    }

    suspend fun createDiscoveryJob(
        kind: DiscoveryJobKind,
        totalSymbols: Int,
    ): Long = withContext(Dispatchers.IO) {
        val db = writableDatabase
        db.insertOrThrow(
            "discovery_job",
            null,
            ContentValues().apply {
                put("kind", kind.storageValue)
                put("status", DiscoveryJobStatus.Running.storageValue)
                put("started_at", nowEpochSeconds())
                putNull("finished_at")
                put("total_symbols", totalSymbols)
                put("completed_symbols", 0)
                putNull("error_summary")
            },
        )
    }

    suspend fun updateDiscoveryJobProgress(
        jobId: Long,
        completedSymbols: Int,
        totalSymbols: Int? = null,
    ) = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put("completed_symbols", completedSymbols)
            if (totalSymbols != null) {
                put("total_symbols", totalSymbols)
            }
        }
        writableDatabase.update("discovery_job", values, "job_id = ?", arrayOf(jobId.toString()))
    }

    suspend fun finishDiscoveryJob(
        jobId: Long,
        status: DiscoveryJobStatus,
        completedSymbols: Int? = null,
        errorSummary: String? = null,
    ) = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put("status", status.storageValue)
            put("finished_at", nowEpochSeconds())
            if (completedSymbols != null) {
                put("completed_symbols", completedSymbols)
            }
            put("error_summary", errorSummary)
        }
        writableDatabase.update("discovery_job", values, "job_id = ?", arrayOf(jobId.toString()))
    }

    suspend fun loadLatestDiscoveryJob(): DiscoveryJobRecord? = withContext(Dispatchers.IO) {
        readableDatabase.rawQuery(
            """
            SELECT job_id, kind, status, started_at, finished_at, total_symbols, completed_symbols, error_summary
            FROM discovery_job
            ORDER BY job_id DESC
            LIMIT 1
            """.trimIndent(),
            emptyArray(),
        ).useRows { cursor ->
            if (!cursor.moveToFirst()) {
                null
            } else {
                DiscoveryJobRecord(
                    jobId = cursor.getLong(0),
                    kind = DiscoveryJobKind.fromStorage(cursor.getString(1)),
                    status = DiscoveryJobStatus.fromStorage(cursor.getString(2)),
                    startedAtEpochSeconds = cursor.getLong(3),
                    finishedAtEpochSeconds = cursor.getNullableLong(4),
                    totalSymbols = cursor.getInt(5),
                    completedSymbols = cursor.getInt(6),
                    errorSummary = cursor.getNullableString(7),
                )
            }
        }
    }

    suspend fun clearDiscoveryData() = withContext(Dispatchers.IO) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("discovery_score", null, null)
            db.delete("discovery_symbol", null, null)
            db.delete("discovery_job", null, null)
            db.delete("meta", "key = ?", arrayOf(META_KEY_DISCOVERY_MIN_SCORE))
            db.delete("meta", "key = ?", arrayOf(META_KEY_DISCOVERY_SCORING_MODEL))
            db.delete("meta", "key = ?", arrayOf(META_KEY_DISCOVERY_UNIVERSE_NAME))
            db.delete("meta", "key = ?", arrayOf(META_KEY_DISCOVERY_LAST_SOURCE_HINT))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun createDiscoverySchema(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE discovery_symbol (
                symbol TEXT PRIMARY KEY,
                company_name TEXT,
                added_at INTEGER NOT NULL,
                source_universe TEXT NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE discovery_score (
                symbol TEXT PRIMARY KEY,
                composite_score INTEGER NOT NULL,
                fundamentals_score INTEGER,
                technical_score INTEGER,
                forecast_score INTEGER,
                coverage_count INTEGER NOT NULL,
                market_price_cents INTEGER,
                upside_bps INTEGER,
                gap_bps INTEGER,
                confidence TEXT,
                is_qualified INTEGER NOT NULL,
                scoring_model TEXT NOT NULL,
                scored_at INTEGER NOT NULL,
                last_error TEXT,
                FOREIGN KEY (symbol) REFERENCES discovery_symbol(symbol)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE INDEX discovery_score_rank_idx
            ON discovery_score(is_qualified, composite_score DESC)
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE discovery_job (
                job_id INTEGER PRIMARY KEY AUTOINCREMENT,
                kind TEXT NOT NULL,
                status TEXT NOT NULL,
                started_at INTEGER NOT NULL,
                finished_at INTEGER,
                total_symbols INTEGER NOT NULL DEFAULT 0,
                completed_symbols INTEGER NOT NULL DEFAULT 0,
                error_summary TEXT
            )
            """.trimIndent(),
        )
    }

    private fun nowEpochSeconds(): Long = System.currentTimeMillis() / 1_000

    companion object {
        private const val SQLITE_SCHEMA_VERSION = 6
        private const val DEFAULT_DB_FILE_NAME = "discount_screener_state.sqlite3"
        private const val META_KEY_LAST_STARTUP_AT = "last_startup_at"
        private const val META_KEY_LAST_PERSISTED_AT = "last_persisted_at"
        private const val META_KEY_DISCOVERY_MIN_SCORE = "discovery.min_score"
        private const val META_KEY_DISCOVERY_SCORING_MODEL = "discovery.scoring_model"
        private const val META_KEY_DISCOVERY_UNIVERSE_NAME = "discovery.universe_name"
        private const val META_KEY_DISCOVERY_LAST_SOURCE_HINT = "discovery.last_source_hint"
        /** Daily points retained per profile (see EstimatesHistoryPolicy.MAX_DAILY_POINTS). */
        private const val ESTIMATES_HISTORY_LIMIT = 180
        private val TABLE_NAMES = listOf(
            "meta", "tracked_symbol", "watchlist", "raw_capture",
            "raw_latest", "pricing_candle", "symbol_revision", "symbol_latest", "issue_state",
            "estimates_snapshot",
            "discovery_symbol", "discovery_score", "discovery_job",
        )
        private val LOG_TABLE_QUERIES = listOf(
            LogTableQuery("raw_capture", "captured_at"),
            LogTableQuery("pricing_candle", "captured_at"),
            LogTableQuery("symbol_revision", "evaluated_at"),
            LogTableQuery("discovery_job", "started_at"),
        )
    }

    private data class LogTableQuery(val tableName: String, val timestampColumn: String)
}

private inline fun <T> Cursor.useRows(block: (Cursor) -> T): T =
    use { cursor -> block(cursor) }

private fun Cursor.getNullableString(index: Int): String? =
    if (isNull(index)) null else getString(index)

private fun Cursor.getNullableInt(index: Int): Int? =
    if (isNull(index)) null else getInt(index)

private fun Cursor.getNullableLong(index: Int): Long? =
    if (isNull(index)) null else getLong(index)

private fun ContentValues.putNullableInt(key: String, value: Int?) {
    if (value == null) putNull(key) else put(key, value)
}

private fun ContentValues.putNullableLong(key: String, value: Long?) {
    if (value == null) putNull(key) else put(key, value)
}

private fun ContentValues.putNullableString(key: String, value: String?) {
    if (value == null) putNull(key) else put(key, value)
}
