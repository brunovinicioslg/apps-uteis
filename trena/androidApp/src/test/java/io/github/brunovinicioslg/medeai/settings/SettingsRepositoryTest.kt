package io.github.brunovinicioslg.medeai.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.common.truth.Truth.assertThat
import io.github.brunovinicioslg.medeai.tilt.TiltSession
import io.github.brunovinicioslg.medeai.units.UnitSystem
import java.io.File
import java.util.Locale
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SettingsRepositoryTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun TestScope.newStore(): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(scope = backgroundScope, produceFile = { File(folder.root, "settings.preferences_pb") })

    @Test
    fun `defaults follow the country`() {
        assertThat(AppSettings.defaultUnitSystem(Locale.of("pt", "BR"))).isEqualTo(UnitSystem.METRIC)
        assertThat(AppSettings.defaultUnitSystem(Locale.of("en", "US"))).isEqualTo(UnitSystem.IMPERIAL)
        assertThat(AppSettings.defaultUnitSystem(Locale.of("en", "GB"))).isEqualTo(UnitSystem.METRIC)
    }

    @Test
    fun `updates are stored and clamped`() = runTest {
        val repository = SettingsRepository(newStore())
        val stored = repository.update { it.copy(unitSystem = UnitSystem.IMPERIAL, cameraHeightMeters = 9.0) }
        assertThat(stored.unitSystem).isEqualTo(UnitSystem.IMPERIAL)
        assertThat(stored.cameraHeightMeters).isEqualTo(TiltSession.MAX_CAMERA_HEIGHT)
        assertThat(repository.settings.first()).isEqualTo(stored)
    }

    @Test
    fun `corrupt values fall back to defaults`() = runTest {
        val store = newStore()
        store.edit {
            it[stringPreferencesKey("unit_system")] = "NAUTICAL"
            it[doublePreferencesKey("camera_height_m")] = Double.NaN
        }
        val settings = SettingsRepository(store).settings.first()
        assertThat(settings.cameraHeightMeters).isEqualTo(AppSettings.DEFAULT_CAMERA_HEIGHT)
        assertThat(settings.unitSystem).isEqualTo(AppSettings.defaultUnitSystem())
    }
}
