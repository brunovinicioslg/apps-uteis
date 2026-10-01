package io.github.brunovinicioslg.alumia

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import io.github.brunovinicioslg.alumia.settings.SettingsRepository
import io.github.brunovinicioslg.alumia.torch.TorchController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class AlumiaApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

private val Context.settingsDataStore by preferencesDataStore(
    name = "settings",
    produceMigrations = { SettingsRepository.migrations },
)

/** Process-wide singletons, created once per process. */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    /** For work that must outlive the component that started it (tile clicks, broadcasts). */
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val settingsRepository = SettingsRepository(appContext.settingsDataStore)

    val torch: TorchController by lazy { TorchController(appContext) }
}

val Context.appContainer: AppContainer
    get() = (applicationContext as AlumiaApp).container
