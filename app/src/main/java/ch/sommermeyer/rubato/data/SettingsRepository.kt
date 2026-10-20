package ch.sommermeyer.rubato.data

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import ch.sommermeyer.rubato.core.correction.CorrectionMath
import ch.sommermeyer.rubato.core.correction.ToneOffset
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import java.io.IOException

private val Context.settingsStore by preferencesDataStore(name = "settings")

/** Where the correction EQ is attached. */
enum class EqMode {
    /** One DynamicsProcessing on the output mix (session 0). */
    GLOBAL,

    /** One DynamicsProcessing per player session announced via broadcast. */
    SESSION,
}

@Serializable
data class RubatoSettings(
    val strength: Float = DEFAULT_STRENGTH,
    val maxCorrectionDb: Float = CorrectionMath.DEFAULT_MAX_DB.toFloat(),
    val tasteBassDb: Float = 0f,
    val tasteTrebleDb: Float = 0f,
    val eqMode: EqMode = EqMode.GLOBAL,
    /**
     * Whether our own EQ is subtracted from the measurement, per mode. The Visualizer is an
     * INSERT_FIRST effect and DynamicsProcessing an INSERT_LAST effect (AOSP), so on the output mix
     * the Visualizer should see the signal before our EQ, while a per-session EQ always sits before
     * the output mix. Both can be changed in the debug screen.
     */
    val compensateGlobal: Boolean = false,
    val compensateSession: Boolean = true,
    val onboardingDone: Boolean = false,
) {
    val compensation: Boolean
        get() = if (eqMode == EqMode.GLOBAL) compensateGlobal else compensateSession

    val taste: ToneOffset
        get() = ToneOffset(bassDb = tasteBassDb.toDouble(), trebleDb = tasteTrebleDb.toDouble())

    companion object {
        const val DEFAULT_STRENGTH = 0.7f
        const val TASTE_RANGE_DB = 6f
    }
}

class SettingsRepository(private val context: Context) {
    private object Keys {
        val STRENGTH = floatPreferencesKey("strength")
        val MAX_DB = floatPreferencesKey("max_correction_db")
        val TASTE_BASS = floatPreferencesKey("taste_bass_db")
        val TASTE_TREBLE = floatPreferencesKey("taste_treble_db")
        val EQ_MODE = stringPreferencesKey("eq_mode")
        val COMPENSATE_GLOBAL = booleanPreferencesKey("compensate_global")
        val COMPENSATE_SESSION = booleanPreferencesKey("compensate_session")
        val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
    }

    val settings: Flow<RubatoSettings> = context.settingsStore.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        .map { prefs ->
            val defaults = RubatoSettings()
            RubatoSettings(
                strength = prefs[Keys.STRENGTH] ?: defaults.strength,
                maxCorrectionDb = prefs[Keys.MAX_DB] ?: defaults.maxCorrectionDb,
                tasteBassDb = prefs[Keys.TASTE_BASS] ?: defaults.tasteBassDb,
                tasteTrebleDb = prefs[Keys.TASTE_TREBLE] ?: defaults.tasteTrebleDb,
                eqMode = prefs[Keys.EQ_MODE]?.let { name -> EqMode.entries.firstOrNull { it.name == name } }
                    ?: defaults.eqMode,
                compensateGlobal = prefs[Keys.COMPENSATE_GLOBAL] ?: defaults.compensateGlobal,
                compensateSession = prefs[Keys.COMPENSATE_SESSION] ?: defaults.compensateSession,
                onboardingDone = prefs[Keys.ONBOARDING_DONE] ?: defaults.onboardingDone,
            )
        }
        .distinctUntilChanged()

    suspend fun setStrength(value: Float) = edit { it[Keys.STRENGTH] = value.coerceIn(0f, 1f) }

    suspend fun setMaxCorrectionDb(value: Float) = edit {
        it[Keys.MAX_DB] = value.coerceIn(1f, CorrectionMath.MAX_ALLOWED_DB.toFloat())
    }

    suspend fun setTasteBassDb(value: Float) = edit { it[Keys.TASTE_BASS] = value.coerceTaste() }

    suspend fun setTasteTrebleDb(value: Float) = edit { it[Keys.TASTE_TREBLE] = value.coerceTaste() }

    suspend fun setEqMode(mode: EqMode) = edit { it[Keys.EQ_MODE] = mode.name }

    suspend fun setCompensation(mode: EqMode, enabled: Boolean) = edit {
        when (mode) {
            EqMode.GLOBAL -> it[Keys.COMPENSATE_GLOBAL] = enabled
            EqMode.SESSION -> it[Keys.COMPENSATE_SESSION] = enabled
        }
    }

    suspend fun setOnboardingDone(done: Boolean) = edit { it[Keys.ONBOARDING_DONE] = done }

    private suspend fun edit(block: (MutablePreferences) -> Unit) {
        context.settingsStore.edit { block(it) }
    }

    private fun Float.coerceTaste() =
        coerceIn(-RubatoSettings.TASTE_RANGE_DB, RubatoSettings.TASTE_RANGE_DB)
}
