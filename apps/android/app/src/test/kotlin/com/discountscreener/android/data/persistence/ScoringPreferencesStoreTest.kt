package com.discountscreener.android.data.persistence

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.discountscreener.android.domain.model.ScoringPreferences
import com.discountscreener.core.model.OpportunityScoringModel
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The two scoring choices that must outlive the process.
 *
 * Every test reopens the store rather than reading back through the same instance: an in-memory
 * field would satisfy a same-instance round trip, and the whole point of these keys is that a cold
 * start finds them.
 *
 * The non-default value is always the one written, so a store that ignored the write and answered
 * from [ScoringPreferences]' defaults would fail rather than pass by coincidence.
 */
@RunWith(RobolectricTestRunner::class)
class ScoringPreferencesStoreTest {
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
    fun a_chosen_opportunity_model_survives_a_cold_start() = runTest {
        save(ScoringPreferences(opportunityModel = OpportunityScoringModel.AggressiveV2))
        assertEquals(OpportunityScoringModel.AggressiveV2, load().opportunityModel)
    }

    @Test
    fun a_switched_off_market_dimension_survives_a_cold_start() = runTest {
        save(ScoringPreferences(regimeScoringEnabled = false))
        assertEquals(false, load().regimeScoringEnabled)
    }

    @Test
    fun an_unwritten_opportunity_model_yields_the_documented_default() = runTest {
        assertEquals(ScoringPreferences.DEFAULT_OPPORTUNITY_MODEL, load().opportunityModel)
    }

    @Test
    fun an_unwritten_market_dimension_yields_the_documented_default() = runTest {
        assertEquals(ScoringPreferences.DEFAULT_REGIME_ENABLED, load().regimeScoringEnabled)
    }

    /** A model name that no longer exists — a renamed enum entry, or a downgrade. */
    @Test
    fun an_unrecognised_opportunity_model_yields_the_documented_default() = runTest {
        writeMeta("scoring.opportunity_model", "ReasonableV9")
        assertEquals(ScoringPreferences.DEFAULT_OPPORTUNITY_MODEL, load().opportunityModel)
    }

    /**
     * The failure that matters most: garbage must not read as "off". A scoring dimension the user
     * never switched off silently disappearing is worse than one that ignores a corrupt row.
     */
    @Test
    fun an_unreadable_market_dimension_is_the_default_rather_than_off() = runTest {
        writeMeta("scoring.regime_enabled", "yes")
        assertEquals(ScoringPreferences.DEFAULT_REGIME_ENABLED, load().regimeScoringEnabled)
    }

    private suspend fun save(preferences: ScoringPreferences) {
        val store = SQLiteStateStore(context)
        try {
            store.saveScoringPreferences(preferences)
        } finally {
            store.close()
        }
    }

    private suspend fun load(): ScoringPreferences {
        val store = SQLiteStateStore(context)
        try {
            return store.loadScoringPreferences()
        } finally {
            store.close()
        }
    }

    private fun writeMeta(key: String, value: String) {
        val store = SQLiteStateStore(context)
        try {
            store.writableDatabase.insertWithOnConflict(
                "meta",
                null,
                ContentValues().apply {
                    put("key", key)
                    put("value", value)
                },
                SQLiteDatabase.CONFLICT_REPLACE,
            )
        } finally {
            store.close()
        }
    }

    private companion object {
        const val DB_NAME = "discount_screener_state.sqlite3"
    }
}
