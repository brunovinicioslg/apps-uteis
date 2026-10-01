package io.github.brunovinicioslg.alumia.settings

import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.brunovinicioslg.alumia.core.detection.Sensitivity
import io.github.brunovinicioslg.alumia.core.settings.Settings
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

class SettingsRepository(private val dataStore: DataStore<Preferences>) {

    val settings: Flow<Settings> = dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { it.toSettings() }
        .distinctUntilChanged()

    /** Applies [transform] atomically and returns the stored (sanitized) result. */
    suspend fun update(transform: (Settings) -> Settings): Settings =
        dataStore.updateData { prefs ->
            prefs.toMutablePreferences().apply { write(transform(prefs.toSettings()).sanitized()) }
        }.toSettings()

    private object Keys {
        val detectionEnabled = booleanPreferencesKey("detection_enabled")
        val sensitivity = stringPreferencesKey("sensitivity")
        val requiredStrokes = intPreferencesKey("required_strokes")
        val workWithScreenOff = booleanPreferencesKey("work_with_screen_off")
        val ignoreInPocket = booleanPreferencesKey("ignore_in_pocket")
        val vibrate = booleanPreferencesKey("vibrate")
        val autoOffMinutes = intPreferencesKey("auto_off_minutes")
        val lowBatteryPercent = intPreferencesKey("low_battery_percent")
        val torchLevelPercent = intPreferencesKey("torch_level_percent")

        /** Set once the 1.0.1 default for the screen-off gesture has been applied. */
        val screenOffDefaultApplied = booleanPreferencesKey("screen_off_default_1_0_1")
    }

    private fun Preferences.toSettings(): Settings {
        val defaults = Settings()
        return Settings(
            detectionEnabled = this[Keys.detectionEnabled] ?: defaults.detectionEnabled,
            sensitivity = Sensitivity.entries.firstOrNull { it.name == this[Keys.sensitivity] } ?: defaults.sensitivity,
            requiredStrokes = this[Keys.requiredStrokes] ?: defaults.requiredStrokes,
            workWithScreenOff = this[Keys.workWithScreenOff] ?: defaults.workWithScreenOff,
            ignoreInPocket = this[Keys.ignoreInPocket] ?: defaults.ignoreInPocket,
            vibrate = this[Keys.vibrate] ?: defaults.vibrate,
            autoOffMinutes = this[Keys.autoOffMinutes] ?: defaults.autoOffMinutes,
            lowBatteryPercent = this[Keys.lowBatteryPercent] ?: defaults.lowBatteryPercent,
            torchLevelPercent = this[Keys.torchLevelPercent] ?: defaults.torchLevelPercent,
        ).sanitized()
    }

    companion object {
        /**
         * Version 1.0.0 stored every setting, its old "work with the screen off" default (on) included,
         * on any change. In 1.0.1 that default turned off, because shakes in a pocket lit the flashlight:
         * applied once to installs that come from 1.0.0. The user can turn it back on.
         */
        val screenOffDefaultMigration: DataMigration<Preferences> = object : DataMigration<Preferences> {
            override suspend fun shouldMigrate(currentData: Preferences): Boolean = currentData[Keys.screenOffDefaultApplied] != true

            override suspend fun migrate(currentData: Preferences): Preferences = currentData.toMutablePreferences().apply {
                this[Keys.workWithScreenOff] = false
                this[Keys.screenOffDefaultApplied] = true
            }

            override suspend fun cleanUp() = Unit
        }

        val migrations: List<DataMigration<Preferences>> = listOf(screenOffDefaultMigration)
    }

    private fun MutablePreferences.write(s: Settings) {
        this[Keys.detectionEnabled] = s.detectionEnabled
        this[Keys.sensitivity] = s.sensitivity.name
        this[Keys.requiredStrokes] = s.requiredStrokes
        this[Keys.workWithScreenOff] = s.workWithScreenOff
        this[Keys.ignoreInPocket] = s.ignoreInPocket
        this[Keys.vibrate] = s.vibrate
        this[Keys.autoOffMinutes] = s.autoOffMinutes
        this[Keys.lowBatteryPercent] = s.lowBatteryPercent
        this[Keys.torchLevelPercent] = s.torchLevelPercent
    }
}
