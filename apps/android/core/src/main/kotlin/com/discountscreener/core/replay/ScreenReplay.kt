package com.discountscreener.core.replay

import com.discountscreener.core.engine.ScreenDataProjectionEngine
import com.discountscreener.core.model.ComputationResult
import com.discountscreener.core.model.ProjectedDashboardData
import com.discountscreener.core.model.ProjectedOpportunityRow
import com.discountscreener.core.model.ProjectedTrackedRow
import com.discountscreener.core.model.ScreenDataProjectionRequest
import java.io.File
import kotlinx.serialization.json.Json

/**
 * Draws the app's screen numbers on a plain JVM, from a captured request file.
 *
 * **What this is for.** Experiments. The predictive models are tuned by changing a formula and
 * reading what moves, and the emulator makes that loop minutes long. Every number the dashboard
 * shows comes out of one pure function — [ScreenDataProjectionEngine.project] — over one
 * serializable input. So the input is captured once and replayed here in milliseconds, as many
 * times as the experiment needs, with no device, no network and no database.
 *
 * **Why the same numbers come out.** The replay calls the engine the app calls, over bytes the app
 * wrote. Nothing is re-implemented here, so there is no second model to drift from the first. Two
 * engine versions over one captured file differ only by the change under test.
 *
 * **What it does not cover.** Loading. The governor, the caches, the store and the provider all
 * live upstream of the request. A change to any of those is not visible here; that flow is tested
 * on the JVM through Robolectric instead.
 */
object ScreenReplay {

    /**
     * The one format both ends use.
     *
     * `allowStructuredMapKeys` is required, not a preference: the request keys its candles by
     * [com.discountscreener.core.model.SymbolRangeKey], and JSON object keys are strings. Without
     * it the capture throws at write time. `ignoreUnknownKeys` lets a file captured before a field
     * was added still replay, which is the point of keeping old captures around.
     */
    val json = Json {
        allowStructuredMapKeys = true
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    fun encodeRequest(request: ScreenDataProjectionRequest): String = json.encodeToString(
        ScreenDataProjectionRequest.serializer(),
        request,
    )

    fun decodeRequest(text: String): ScreenDataProjectionRequest = json.decodeFromString(
        ScreenDataProjectionRequest.serializer(),
        text,
    )

    /** Projects [request], and fails loudly with the engine's own reason when it refuses. */
    fun project(request: ScreenDataProjectionRequest): ProjectedDashboardData {
        var result = ScreenDataProjectionEngine().project(request)
        return when (result) {
            is ComputationResult.Success -> result.value
            is ComputationResult.Error -> error(
                "The engine refused the request: area=${result.failure.area.name} " +
                    "code=${result.failure.code} ${result.failure.message}",
            )
        }
    }

    fun renderJson(data: ProjectedDashboardData): String = json.encodeToString(
        ProjectedDashboardData.serializer(),
        data,
    )

    /**
     * One fixed-width table per populated section, so two runs can be diffed line by line.
     */
    fun renderTable(data: ProjectedDashboardData): String {
        var sections = listOf(
            "TRACKED" to data.trackedRows.map(::trackedReplayRow),
            "WATCHLIST" to data.watchlistRows.map(::trackedReplayRow),
            "OPPORTUNITIES" to data.opportunityRows.map(::opportunityReplayRow),
        ).filter { section -> section.second.isNotEmpty() }
        if (sections.isEmpty()) {
            return "No rows. The captured request carried no symbol the engine could project."
        }
        return sections.joinToString("\n\n") { section ->
            "${section.first} (${section.second.size})\n${renderRows(section.second)}"
        }
    }

    private fun renderRows(rows: List<ReplayRow>): String {
        var header = listOf("SYMBOL", "PRICE", "FAIR", "DISC%", "UPSIDE%", "CONF", "DECISION")
        var body = rows.map { row ->
            listOf(
                row.symbol,
                dollars(row.marketPriceCents),
                dollars(row.fairValueCents),
                percent(row.gapBps),
                percent(row.upsideBps),
                row.confidence,
                row.decision,
            )
        }
        var widths = header.indices.map { column ->
            (listOf(header) + body).maxOf { cells -> cells[column].length }
        }
        return (listOf(header) + body).joinToString("\n") { cells ->
            cells.mapIndexed { column, cell -> cell.padEnd(widths[column]) }
                .joinToString("  ")
                .trimEnd()
        }
    }

    private fun dollars(cents: Long?): String = cents?.let { value ->
        var sign = if (value < 0L) "-" else ""
        var absolute = if (value < 0L) -value else value
        "$sign${absolute / 100L}.${(absolute % 100L).toString().padStart(2, '0')}"
    } ?: "-"

    private fun percent(bps: Int?): String = bps?.let { value ->
        var sign = if (value < 0) "-" else ""
        var absolute = if (value < 0) -value else value
        "$sign${absolute / 100}.${(absolute % 100).toString().padStart(2, '0')}"
    } ?: "-"
}

private data class ReplayRow(
    val symbol: String,
    val marketPriceCents: Long?,
    val fairValueCents: Long?,
    val gapBps: Int?,
    val upsideBps: Int?,
    val confidence: String,
    val decision: String,
)

private fun trackedReplayRow(row: ProjectedTrackedRow): ReplayRow = ReplayRow(
    symbol = row.symbol,
    marketPriceCents = row.marketPriceCents,
    fairValueCents = row.fairValueAnchor.valueCents,
    gapBps = row.gapBps,
    upsideBps = row.upsideBps,
    confidence = row.confidence.name,
    decision = row.decision?.name ?: "-",
)

private fun opportunityReplayRow(row: ProjectedOpportunityRow): ReplayRow = ReplayRow(
    symbol = row.symbol,
    marketPriceCents = row.candidateRow.marketPriceCents,
    fairValueCents = row.fairValueAnchor.valueCents,
    gapBps = row.gapBps,
    upsideBps = row.upsideBps,
    confidence = row.confidence.name,
    decision = row.decision?.name ?: "-",
)

/**
 * `replayScreen --request=<file> [--format=table|json] [--out=<file>]`.
 */
fun main(args: Array<String>) {
    var options = args.filter { argument -> argument.startsWith("--") }
        .associate { argument ->
            var body = argument.removePrefix("--")
            body.substringBefore('=') to body.substringAfter('=', "")
        }
    var requestPath = options["request"]
    if (requestPath.isNullOrBlank()) {
        System.err.println("Usage: replayScreen --request=<file> [--format=table|json] [--out=<file>]")
        return
    }
    var requestFile = File(requestPath)
    if (!requestFile.isFile) {
        System.err.println("No captured request at ${requestFile.absolutePath}")
        return
    }
    var data = ScreenReplay.project(ScreenReplay.decodeRequest(requestFile.readText()))
    var rendered = when (options["format"] ?: "table") {
        "json" -> ScreenReplay.renderJson(data)
        else -> ScreenReplay.renderTable(data)
    }
    var outPath = options["out"]
    if (outPath.isNullOrBlank()) {
        println(rendered)
    } else {
        File(outPath).writeText(rendered)
        println("Wrote ${File(outPath).absolutePath}")
    }
}
