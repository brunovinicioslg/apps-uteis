package io.github.brunovinicioslg.ladeira.app.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.brunovinicioslg.ladeira.profile.VehicleProfile
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

data class AppSettings(
    val vehicle: VehicleProfile = VehicleProfile.CAR,
    val voiceEnabled: Boolean = true,
)

class SettingsRepository(private val dataStore: DataStore<Preferences>) {

    val settings: Flow<AppSettings> = dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { it.toSettings() }
        .distinctUntilChanged()

    suspend fun update(transform: (AppSettings) -> AppSettings): AppSettings =
        dataStore.updateData { prefs ->
            val next = transform(prefs.toSettings())
            prefs.toMutablePreferences().apply {
                this[VEHICLE] = next.vehicle.name
                this[VOICE] = next.voiceEnabled
            }
        }.toSettings()

    private fun Preferences.toSettings(): AppSettings {
        val defaults = AppSettings()
        return AppSettings(
            vehicle = VehicleProfile.entries.firstOrNull { it.name == this[VEHICLE] } ?: defaults.vehicle,
            voiceEnabled = this[VOICE] ?: defaults.voiceEnabled,
        )
    }

    private companion object {
        val VEHICLE = stringPreferencesKey("vehicle")
        val VOICE = booleanPreferencesKey("voice_enabled")
    }
}
