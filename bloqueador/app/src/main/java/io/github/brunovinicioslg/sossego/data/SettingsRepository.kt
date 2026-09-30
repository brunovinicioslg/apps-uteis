package io.github.brunovinicioslg.sossego.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.brunovinicioslg.sossego.core.rules.BlockAction
import io.github.brunovinicioslg.sossego.core.rules.Mode
import io.github.brunovinicioslg.sossego.core.rules.NotifyMode
import io.github.brunovinicioslg.sossego.core.rules.Schedule
import io.github.brunovinicioslg.sossego.core.rules.Settings
import java.io.IOException
import java.time.DayOfWeek
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

class SettingsRepository(private val dataStore: DataStore<Preferences>) {

    val settings: Flow<Settings> = dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { it.toSettings() }
        .distinctUntilChanged()

    /** For the call screening, which must answer right away. Blocking: off the main thread. */
    fun current(): Settings = runBlocking { settings.first() }

    /** Applies [transform] atomically and returns the stored (sanitized) result. */
    suspend fun update(transform: (Settings) -> Settings): Settings =
        dataStore.updateData { prefs ->
            prefs.toMutablePreferences().apply { write(transform(prefs.toSettings()).sanitized()) }
        }.toSettings()

    private object Keys {
        val enabled = booleanPreferencesKey("enabled")
        val mode = stringPreferencesKey("mode")
        val action = stringPreferencesKey("action")
        val keepInCallLog = booleanPreferencesKey("keep_in_call_log")
        val notifications = stringPreferencesKey("notifications")
        val blockHidden = booleanPreferencesKey("block_hidden")
        val blockTelemarketing = booleanPreferencesKey("block_telemarketing")
        val blockInternational = booleanPreferencesKey("block_international")
        val repeatCallers = booleanPreferencesKey("repeat_callers")
        val scheduleEnabled = booleanPreferencesKey("schedule_enabled")
        val scheduleStart = intPreferencesKey("schedule_start")
        val scheduleEnd = intPreferencesKey("schedule_end")
        val scheduleDays = intPreferencesKey("schedule_days")
        val scheduleMode = stringPreferencesKey("schedule_mode")
    }

    private fun Preferences.toSettings(): Settings {
        val defaults = Settings()
        val schedule = defaults.schedule
        return Settings(
            enabled = this[Keys.enabled] ?: defaults.enabled,
            mode = enumValueOrNull<Mode>(this[Keys.mode]) ?: defaults.mode,
            action = enumValueOrNull<BlockAction>(this[Keys.action]) ?: defaults.action,
            keepInCallLog = this[Keys.keepInCallLog] ?: defaults.keepInCallLog,
            notifications = enumValueOrNull<NotifyMode>(this[Keys.notifications]) ?: defaults.notifications,
            blockHidden = this[Keys.blockHidden] ?: defaults.blockHidden,
            blockTelemarketing = this[Keys.blockTelemarketing] ?: defaults.blockTelemarketing,
            blockInternational = this[Keys.blockInternational] ?: defaults.blockInternational,
            repeatCallers = this[Keys.repeatCallers] ?: defaults.repeatCallers,
            schedule = Schedule(
                enabled = this[Keys.scheduleEnabled] ?: schedule.enabled,
                startMinute = this[Keys.scheduleStart] ?: schedule.startMinute,
                endMinute = this[Keys.scheduleEnd] ?: schedule.endMinute,
                days = this[Keys.scheduleDays]?.let(::daysOf) ?: schedule.days,
                mode = enumValueOrNull<Mode>(this[Keys.scheduleMode]) ?: schedule.mode,
            ),
        ).sanitized()
    }

    private fun MutablePreferences.write(s: Settings) {
        this[Keys.enabled] = s.enabled
        this[Keys.mode] = s.mode.name
        this[Keys.action] = s.action.name
        this[Keys.keepInCallLog] = s.keepInCallLog
        this[Keys.notifications] = s.notifications.name
        this[Keys.blockHidden] = s.blockHidden
        this[Keys.blockTelemarketing] = s.blockTelemarketing
        this[Keys.blockInternational] = s.blockInternational
        this[Keys.repeatCallers] = s.repeatCallers
        this[Keys.scheduleEnabled] = s.schedule.enabled
        this[Keys.scheduleStart] = s.schedule.startMinute
        this[Keys.scheduleEnd] = s.schedule.endMinute
        this[Keys.scheduleDays] = s.schedule.days.fold(0) { mask, day -> mask or (1 shl day.value) }
        this[Keys.scheduleMode] = s.schedule.mode.name
    }

    private fun daysOf(mask: Int): Set<DayOfWeek> = DayOfWeek.entries.filter { mask and (1 shl it.value) != 0 }.toSet()
}
