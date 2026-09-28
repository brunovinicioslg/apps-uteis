package io.github.brunovinicioslg.alumia.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.common.truth.Truth.assertThat
import io.github.brunovinicioslg.alumia.core.detection.Sensitivity
import io.github.brunovinicioslg.alumia.core.detection.ShakeConfig
import io.github.brunovinicioslg.alumia.core.settings.Settings
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SettingsRepositoryTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun TestScope.newStore(): DataStore<Preferences> = PreferenceDataStoreFactory.create(
        scope = backgroundScope,
        produceFile = { File(folder.root, "settings.preferences_pb") },
    )

    @Test
    fun `empty storage yields defaults`() = runTest {
        val repository = SettingsRepository(newStore())
        assertThat(repository.settings.first()).isEqualTo(Settings())
    }

    @Test
    fun `update persists the sanitized result`() = runTest {
        val repository = SettingsRepository(newStore())
        val stored = repository.update { it.copy(sensitivity = Sensitivity.HIGH, requiredStrokes = 99, detectionEnabled = false) }
        assertThat(stored.requiredStrokes).isEqualTo(ShakeConfig.MAX_STROKES)
        assertThat(stored.sensitivity).isEqualTo(Sensitivity.HIGH)
        assertThat(repository.settings.first()).isEqualTo(stored)
    }

    @Test
    fun `corrupt stored values fall back to valid ones`() = runTest {
        val store = newStore()
        store.edit {
            it[stringPreferencesKey("sensitivity")] = "NOT_A_LEVEL"
            it[intPreferencesKey("auto_off_minutes")] = 7
            it[intPreferencesKey("torch_level_percent")] = -40
        }
        val settings = SettingsRepository(store).settings.first()
        assertThat(settings.sensitivity).isEqualTo(Sensitivity.MEDIUM)
        assertThat(settings.autoOffMinutes).isEqualTo(0)
        assertThat(settings.torchLevelPercent).isEqualTo(1)
    }
}
