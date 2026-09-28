package io.github.brunovinicioslg.medeai.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.brunovinicioslg.medeai.tilt.TiltSession
import io.github.brunovinicioslg.medeai.units.UnitSystem
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

data class AppSettings(
    val unitSystem: UnitSystem = defaultUnitSystem(),
    /** Height at which the user holds the phone, for tilt mode. */
    val cameraHeightMeters: Double = DEFAULT_CAMERA_HEIGHT,
) {
    companion object {
        const val DEFAULT_CAMERA_HEIGHT = 1.40

        /** Countries that still use imperial units day to day. */
        private val IMPERIAL_COUNTRIES = setOf("US", "LR", "MM")

        fun defaultUnitSystem(locale: Locale = Locale.getDefault()): UnitSystem =
            if (locale.country in IMPERIAL_COUNTRIES) UnitSystem.IMPERIAL else UnitSystem.METRIC
    }
}

class SettingsRepository(private val dataStore: DataStore<Preferences>) {

    val settings: Flow<AppSettings> = dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { it.toSettings() }
        .distinctUntilChanged()

    suspend fun update(transform: (AppSettings) -> AppSettings): AppSettings =
        dataStore.updateData { prefs ->
            val next = transform(prefs.toSettings())
            prefs.toMutablePreferences().apply {
                this[UNIT_SYSTEM] = next.unitSystem.name
                this[CAMERA_HEIGHT] = next.cameraHeightMeters.coerceIn(TiltSession.MIN_CAMERA_HEIGHT, TiltSession.MAX_CAMERA_HEIGHT)
            }
        }.toSettings()

    private fun Preferences.toSettings(): AppSettings {
        val defaults = AppSettings()
        val height = this[CAMERA_HEIGHT]?.takeIf { it.isFinite() } ?: defaults.cameraHeightMeters
        return AppSettings(
            unitSystem = UnitSystem.entries.firstOrNull { it.name == this[UNIT_SYSTEM] } ?: defaults.unitSystem,
            cameraHeightMeters = height.coerceIn(TiltSession.MIN_CAMERA_HEIGHT, TiltSession.MAX_CAMERA_HEIGHT),
        )
    }

    private companion object {
        val UNIT_SYSTEM = stringPreferencesKey("unit_system")
        val CAMERA_HEIGHT = doublePreferencesKey("camera_height_m")
    }
}
