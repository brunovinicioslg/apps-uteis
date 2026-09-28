package io.github.brunovinicioslg.ladeira.app

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import io.github.brunovinicioslg.ladeira.app.drive.DriveSession
import io.github.brunovinicioslg.ladeira.app.region.RegionImporter
import io.github.brunovinicioslg.ladeira.app.region.RegionStore
import io.github.brunovinicioslg.ladeira.app.settings.SettingsRepository
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class LadeiraApp : Application() {
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
    /** Outlives screens: imports keep going when the user leaves the screen that started them. */
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val settingsRepository = SettingsRepository(context.applicationContext.settingsDataStore)
    val regionStore = RegionStore(File(context.filesDir, "regions"))
    val regionImporter = RegionImporter(regionStore, applicationScope)
    val driveSession = DriveSession()

    init {
        applicationScope.launch(Dispatchers.IO) { regionStore.refresh() }
    }
}

val Context.appContainer: AppContainer
    get() = (applicationContext as LadeiraApp).container
