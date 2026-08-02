package com.discountscreener.core.engine

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnalystMethodImportContractTest {
    private val json = Json { ignoreUnknownKeys = false }

    @Test
    fun shared_available_fixture_computes_expected_target() {
        val contract = json.parseToJsonElement(Files.readString(contractPath())).jsonObject
        val fixture = contract["fixtures"]!!.jsonObject["available"]!!.jsonArray.first().jsonObject
        val parsed = admit(fixture["import"]!!.jsonObject, fixture["admissionContext"]!!.jsonObject).getOrThrow()
        assertEquals(1_753_920_000_000L, parsed.decisionAtUnixMs)
        assertEquals("share_basis:amzn-us:post-split-2022", parsed.epsShareBasisId)

        val computed = ForwardEarningsMultiple.compute(parsed.femInput)
        assertTrue(computed is ForwardEarningsMultiple.Result.AvailableResult)
        assertEquals(
            fixture["expectedTargetValueCents"]!!.jsonPrimitive.longOrNull,
            computed.value.targetValueCents,
        )
    }

    @Test
    fun shared_horizon_counterexamples_fail_closed() {
        val contract = json.parseToJsonElement(Files.readString(contractPath())).jsonObject
        val base = contract["fixtures"]!!.jsonObject["available"]!!.jsonArray
            .first().jsonObject["import"]!!.jsonObject
        val context = contract["fixtures"]!!.jsonObject["available"]!!.jsonArray
            .first().jsonObject["admissionContext"]!!.jsonObject
        val refusals = contract["fixtures"]!!.jsonObject["refusals"]!!.jsonArray
            .map(JsonElement::jsonObject)
            .filter { it["jsonPointerPatch"] != null }

        assertTrue(refusals.isNotEmpty(), "shared contract must carry horizon counterexamples")
        for (case in refusals) {
            val patched = applyPatch(base, case["jsonPointerPatch"]!!.jsonObject)
            val result = admit(patched, context)
            assertTrue(result.isFailure, case["name"].toString())
            assertEquals(
                case["expectedReasonCode"]!!.jsonPrimitive.content,
                result.exceptionOrNull()?.message,
                case["name"].toString(),
            )
        }
    }

    private fun admit(
        doc: JsonObject,
        context: JsonObject,
    ): Result<AnalystMethodImport.Parsed> {
        val fem = doc["fem"]!!.jsonObject
        return AnalystMethodImport.admit(
            schemaVersion = doc["schemaVersion"]!!.jsonPrimitive.int,
            qualityLabelRaw = doc["qualityLabel"]!!.jsonPrimitive.content,
            issuerId = doc["issuerId"]!!.jsonPrimitive.content,
            securityId = doc["securityId"]!!.jsonPrimitive.content,
            runId = doc["runId"]!!.jsonPrimitive.content,
            decisionAtUnixMs = doc["decisionAtUnixMs"]!!.jsonPrimitive.longOrNull!!,
            admissionContext = AnalystMethodImport.AdmissionContext(
                expectedDecisionAtUnixMs = context["decisionAtUnixMs"]!!.jsonPrimitive.longOrNull!!,
                expectedEpsShareBasisId = context["shareBasisId"]!!.jsonPrimitive.content,
            ),
            observations = doc["observations"]!!.jsonArray.map(::observation),
            fem = AnalystMethodImport.FemSection(
                epsObservationId = fem["epsObservationId"]!!.jsonPrimitive.content,
                epsShareBasisId = fem["epsShareBasisId"]!!.jsonPrimitive.content,
                multipleObservationId = fem["multipleObservationId"]!!.jsonPrimitive.content,
                multipleProvenance = fem["multipleProvenance"]!!.jsonPrimitive.content,
                forecastPeriodEnd = fem["forecastPeriodEnd"]!!.jsonPrimitive.content,
                targetAsOf = fem["targetAsOf"]!!.jsonPrimitive.content,
                datePrecision = fem["datePrecision"]!!.jsonPrimitive.content,
                marketPriceCents = fem["marketPriceCents"]?.jsonPrimitive?.longOrNull,
                statedTargetCents = fem["statedTargetCents"]?.jsonPrimitive?.longOrNull,
                peerCount = fem["peerCount"]?.jsonPrimitive?.intOrNull,
            ),
        )
    }

    private fun observation(element: JsonElement): EvidenceObservationV2 {
        val o = element.jsonObject
        fun text(name: String) = o[name]!!.jsonPrimitive.content
        fun optionalText(name: String) = o[name]?.takeUnless { it is JsonNull }?.jsonPrimitive?.contentOrNull
        return EvidenceObservationV2(
            id = text("id"), issuerId = text("issuerId"), securityId = optionalText("securityId"),
            evidenceLane = EvidenceLane.entries.single { it.snake == text("evidenceLane") },
            providerId = text("providerId"), lineageGroupId = text("lineageGroupId"), metricId = text("metricId"),
            metricBasis = MetricBasis.entries.single { it.snake == text("metricBasis") },
            accountingRegime = AccountingRegime.entries.single { it.snake == text("accountingRegime") },
            economicPeriodStart = text("economicPeriodStart"), economicPeriodEnd = text("economicPeriodEnd"),
            datePrecision = DatePrecision.entries.single { it.snake == text("datePrecision") },
            publicationAtUnixMs = o["publicationAtUnixMs"]!!.jsonPrimitive.longOrNull!!,
            sourceAvailableAtUnixMs = o["sourceAvailableAtUnixMs"]!!.jsonPrimitive.longOrNull!!,
            ingestedAtUnixMs = o["ingestedAtUnixMs"]!!.jsonPrimitive.longOrNull!!,
            availabilityBasis = AvailabilityBasis.entries.single { it.snake == text("availabilityBasis") },
            providerVintageId = optionalText("providerVintageId"),
            unit = EvidenceUnitV2.entries.single { it.snake == text("unit") },
            valueCents = o["valueCents"]?.jsonPrimitive?.longOrNull,
            valueBps = o["valueBps"]?.jsonPrimitive?.intOrNull,
            valueMillis = o["valueMillis"]?.jsonPrimitive?.longOrNull,
            textValue = optionalText("textValue"), currency = optionalText("currency"),
            definition = text("definition"), sourceLocation = text("sourceLocation"), extractionMethod = text("extractionMethod"),
            quality = text("quality"), retrievalState = text("retrievalState"), revisionId = text("revisionId"),
            supersedes = optionalText("supersedes"), externalFileReference = optionalText("externalFileReference"),
            storageDisposition = StorageDisposition.entries.single { it.snake == text("storageDisposition") },
        )
    }

    private fun applyPatch(base: JsonObject, patch: JsonObject): JsonObject {
        var current: JsonElement = base
        for ((pointer, value) in patch) current = replace(current, pointer.removePrefix("/").split('/'), value)
        return current.jsonObject
    }

    private fun replace(node: JsonElement, path: List<String>, value: JsonElement): JsonElement {
        if (path.isEmpty()) return value
        val head = path.first()
        val tail = path.drop(1)
        return when (node) {
            is JsonObject -> JsonObject(node + (head to replace(node[head]!!, tail, value)))
            is JsonArray -> JsonArray(node.mapIndexed { index, child ->
                if (index == head.toInt()) replace(child, tail, value) else child
            })
            else -> error("cannot patch through $head")
        }
    }

    private fun contractPath(): Path {
        var current = Path.of("").toAbsolutePath()
        repeat(8) {
            val candidate = current.resolve("shared/contracts/valuation-forward-earnings-import-v1.json")
            if (Files.exists(candidate)) return candidate
            current = current.parent ?: return@repeat
        }
        error("shared import contract not found")
    }
}
