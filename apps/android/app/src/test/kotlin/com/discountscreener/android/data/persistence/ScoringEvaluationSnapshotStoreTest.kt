package com.discountscreener.android.data.persistence

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.discountscreener.android.domain.model.ScoringEvaluationInputs
import com.discountscreener.android.domain.model.ScoringEvaluationModelResult
import com.discountscreener.android.domain.model.ScoringEvaluationScore
import com.discountscreener.android.domain.model.ScoringEvaluationSnapshot
import com.discountscreener.android.domain.model.ScoringEvaluationSymbolInput
import com.discountscreener.core.model.OpportunityScoringModel
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ScoringEvaluationSnapshotStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        context.deleteDatabase(DB_NAME)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(DB_NAME)
    }

    @Test
    fun an_atomic_snapshot_survives_a_cold_start_and_warm_start_reset() = runTest {
        val snapshot = snapshot()

        SQLiteStateStore(context).use { store ->
            store.appendScoringEvaluationSnapshot(snapshot)
            store.appendScoringEvaluationSnapshot(snapshot)
            store.resetWarmStartState()
        }

        SQLiteStateStore(context).use { store ->
            assertEquals(listOf(snapshot), store.loadScoringEvaluationSnapshots())
        }
    }

    @Test
    fun a_version_eleven_database_gains_the_snapshot_table() = runTest {
        val file = context.getDatabasePath(DB_NAME)
        file.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { database -> database.version = 11 }

        SQLiteStateStore(context).use { store ->
            store.appendScoringEvaluationSnapshot(snapshot())
            assertEquals(1, store.loadScoringEvaluationSnapshots().size)
        }
    }

    @Test
    fun the_latest_refresh_replaces_an_earlier_snapshot_for_the_same_profile_day() = runTest {
        val first = snapshot()
        val later = snapshot(NOW + 60)

        SQLiteStateStore(context).use { store ->
            store.appendScoringEvaluationSnapshot(first)
            store.appendScoringEvaluationSnapshot(later)

            assertEquals(listOf(later), store.loadScoringEvaluationSnapshots("qa"))
        }
    }

    private fun snapshot(capturedAtEpochSeconds: Long = NOW) = ScoringEvaluationSnapshot.create(
            capturedAtEpochSeconds = capturedAtEpochSeconds,
            profileName = "qa",
            policyVersion = "valuation-policy/1",
            regimeScoringEnabled = true,
            inputs = ScoringEvaluationInputs(
                universe = listOf(
                    ScoringEvaluationSymbolInput(
                        profilePosition = 1,
                        symbol = "AAPL",
                        unavailableReason = "No normalized company detail after refresh.",
                    ),
                ),
            ),
            modelResults = listOf(
                ScoringEvaluationModelResult(
                    model = OpportunityScoringModel.AggressiveV5,
                    formulaVersion = "aggressive-v5/1",
                    rows = listOf(
                        ScoringEvaluationScore(
                            symbol = "AAPL",
                            universeRank = 1,
                            visibleRank = null,
                            marketPriceCents = 20_000,
                            compositeScore = 51,
                            compositeScoreBase = 48,
                            coverageCount = 3,
                        ),
                    ),
                ),
            ),
        )

    private companion object {
        const val DB_NAME = "discount_screener_state.sqlite3"
        const val NOW = 1_700_000_000L
    }
}
