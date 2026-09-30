package io.github.brunovinicioslg.sossego

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import io.github.brunovinicioslg.sossego.data.HistoryRepository
import io.github.brunovinicioslg.sossego.data.ListRepository
import io.github.brunovinicioslg.sossego.data.SettingsRepository
import io.github.brunovinicioslg.sossego.data.SossegoDatabase
import io.github.brunovinicioslg.sossego.device.ContactLookup
import io.github.brunovinicioslg.sossego.notify.BlockNotifier
import io.github.brunovinicioslg.sossego.screening.CallGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class SossegoApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.warmUp()
    }
}

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

/** Process-wide singletons, created once per process. */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    /** For work that must outlive the component that started it (tile clicks). */
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val settingsRepository = SettingsRepository(appContext.settingsDataStore)
    val database = SossegoDatabase(appContext)
    val lists = ListRepository(database)
    val history = HistoryRepository(database)
    val contacts = ContactLookup(appContext)
    val notifier = BlockNotifier(appContext, history)
    val gate = CallGate(settingsRepository, lists, history, contacts, notifier)

    /** Reads everything once in the background, so the first call screened answers right away. */
    fun warmUp() {
        applicationScope.launch(Dispatchers.IO) {
            settingsRepository.settings.first()
            lists.load()
            history.load()
        }
    }
}

val Context.appContainer: AppContainer
    get() = (applicationContext as SossegoApp).container
