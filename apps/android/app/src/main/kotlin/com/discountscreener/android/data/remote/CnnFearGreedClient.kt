package com.discountscreener.android.data.remote

import com.discountscreener.core.regime.CnnFearGreed
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * CNN's Fear & Greed index, from the unofficial dataviz endpoint `cnn_fng.rs` reads.
 *
 * **The alternative.me fallback is deliberately not ported.** Rust falls back to
 * `api.alternative.me/fng` when CNN blocks it, and its own comment says what that is: the *crypto*
 * fear and greed index. The two are not the same measurement and routinely sit in opposite zones —
 * probed on 2026-08-09, CNN read 63.7 ("Greed") while alternative.me read 31 ("Fear"). That number
 * does not merely tint a pillar: `compose` turns the sentiment zone into the stance, and the stance
 * selects the whole scoring policy, so a CNN outage would silently score every equity in the app
 * against crypto sentiment.
 *
 * Returning null instead is strictly better, because absence is already handled: `sentimentPillar`
 * falls back to breadth and reports reduced confidence, which is an honest "we cannot tell" rather
 * than a confident reading of the wrong market. This is a knowing divergence from Windows.
 */
open class CnnFearGreedClient(
    private val httpClient: OkHttpClient = defaultHttpClient(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    /** Null on any failure — a missing sentiment reading degrades the market read, never fails it. */
    open suspend fun fetch(today: LocalDate = LocalDate.now(ZoneOffset.UTC)): CnnFearGreed? =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(GRAPHDATA_BASE + today.minusDays(HISTORY_WINDOW_DAYS))
                .header("User-Agent", CNN_USER_AGENT)
                .header("Accept", "application/json,text/plain,*/*")
                .header("Referer", "https://www.cnn.com/markets/fear-and-greed")
                .header("Origin", "https://www.cnn.com")
                .build()
            val body = try {
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext null
                    response.body?.string()
                }
            } catch (error: IOException) {
                null
            } ?: return@withContext null
            parseFearGreed(body, json)
        }

    private companion object {
        const val GRAPHDATA_BASE = "https://production.dataviz.cnn.io/index/fearandgreed/graphdata/"

        /** The endpoint wants a start date; six months back is the window Rust asks for. */
        const val HISTORY_WINDOW_DAYS = 180L

        const val CNN_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0.0.0 Safari/537.36"

        fun defaultHttpClient() = OkHttpClient.Builder()
            .callTimeout(Duration.ofSeconds(12))
            .build()
    }
}

/**
 * Null rather than an exception for anything unreadable: a shape change at an endpoint nobody
 * publishes a contract for should cost the sentiment pillar its confidence, not crash a refresh.
 *
 * A score outside 0..100 is treated as unreadable rather than clamped. Clamping would turn a
 * garbled response into a confident "extreme greed", which is the one reading that most moves the
 * stance.
 */
internal fun parseFearGreed(body: String, json: Json): CnnFearGreed? {
    val current = runCatching { json.parseToJsonElement(body).jsonObject["fear_and_greed"]?.jsonObject }
        .getOrNull() ?: return null
    val score = current.numberOrNull("score") ?: return null
    if (score !in 0.0..100.0) return null
    val rating = current["rating"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    return CnnFearGreed(
        score = score,
        rating = rating?.let(::titleCase) ?: ratingFromScore(score),
        previousClose = current.numberOrNull("previous_close"),
        previous1Week = current.numberOrNull("previous_1_week"),
        fetchedAtEpoch = current["timestamp"]?.jsonPrimitive?.contentOrNull?.let(::parseIsoEpoch)
            ?: Instant.now().epochSecond,
    )
}

private fun JsonObject.numberOrNull(key: String): Double? =
    runCatching { this[key]?.jsonPrimitive?.doubleOrNull }.getOrNull()

/** `"extreme fear"` → `"Extreme Fear"`, matching `cnn_fng.rs::title_case_rating`. */
private fun titleCase(rating: String): String = rating.split(" ")
    .filter { it.isNotEmpty() }
    .joinToString(" ") { word -> word.replaceFirstChar { it.uppercaseChar() } }

/** `cnn_fng.rs::rating_from_score`, for the days the endpoint omits the label. */
private fun ratingFromScore(score: Double): String = when {
    score <= 24.0 -> "Extreme Fear"
    score <= 44.0 -> "Fear"
    score <= 55.0 -> "Neutral"
    score <= 75.0 -> "Greed"
    else -> "Extreme Greed"
}

/**
 * `"2026-08-07T23:59:47+00:00"` or a bare epoch. Rust hand-rolls a calendar here because it has no
 * chrono; `java.time` is on the platform, so this parses the real timestamp instead of the
 * approximation-to-the-day Rust settles for.
 */
private fun parseIsoEpoch(raw: String): Long? =
    raw.toLongOrNull() ?: runCatching { Instant.parse(raw).epochSecond }.getOrNull()
        ?: runCatching { OffsetDateTime.parse(raw).toEpochSecond() }.getOrNull()
