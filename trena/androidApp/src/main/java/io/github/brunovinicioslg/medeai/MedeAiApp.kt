package io.github.brunovinicioslg.medeai

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import io.github.brunovinicioslg.medeai.settings.SettingsRepository

class MedeAiApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

/** Process-wide singletons. */
class AppContainer(context: Context) {
    val settingsRepository = SettingsRepository(context.applicationContext.settingsDataStore)
}

val Context.appContainer: AppContainer
    get() = (applicationContext as MedeAiApp).container
