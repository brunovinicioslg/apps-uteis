package io.github.brunovinicioslg.sigilo.app

import android.app.Application
import android.content.Context
import android.os.Build
import android.telephony.TelephonyManager
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import io.github.brunovinicioslg.sigilo.app.db.Conversation
import io.github.brunovinicioslg.sigilo.app.db.Message
import io.github.brunovinicioslg.sigilo.app.db.SigiloDatabase
import io.github.brunovinicioslg.sigilo.app.engine.AddressNormalizer
import io.github.brunovinicioslg.sigilo.app.engine.EngineEvents
import io.github.brunovinicioslg.sigilo.app.engine.MessageEngine
import io.github.brunovinicioslg.sigilo.app.inbox.Inbox
import io.github.brunovinicioslg.sigilo.app.notify.ExpiryScheduler
import io.github.brunovinicioslg.sigilo.app.notify.Notifier
import io.github.brunovinicioslg.sigilo.app.sms.AndroidSmsGateway
import io.github.brunovinicioslg.sigilo.app.sms.AndroidSystemSms
import io.github.brunovinicioslg.sigilo.app.sms.DefaultSmsApp
import io.github.brunovinicioslg.sigilo.app.vault.AndroidKeystoreWrapper
import io.github.brunovinicioslg.sigilo.app.vault.UnlockGuard
import io.github.brunovinicioslg.sigilo.app.vault.Vault
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

class SigiloApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.start()
        ProcessLifecycleOwner.get().lifecycle.addObserver(container.autoLock)
    }
}

/** Process-wide singletons. */
class AppContainer(private val app: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val inbox = Inbox(app)
    val settings = AppSettings(app)
    val notifier = Notifier(app, settings)
    private val expiry = ExpiryScheduler(app)
    val systemSms = AndroidSystemSms(app)
    val gateway = AndroidSmsGateway(app)

    private val _changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Ticks whenever conversations or messages change; screens reload on it. */
    val changes: SharedFlow<Unit> = _changes.asSharedFlow()

    private val events = object : EngineEvents {
        override fun onNewMessages(conversation: Conversation, messages: List<Message>) = notifier.showNew(conversation, messages, hideContent = lock.hasPassword())

        override fun onChanged() {
            _changes.tryEmit(Unit)
        }

        override fun onNextExpiry(at: Long?) = expiry.schedule(at)
    }

    val lock: AppLock = AppLock(
        vault = Vault(File(app.noBackupFilesDir, "vault.bin"), AndroidKeystoreWrapper()),
        guard = UnlockGuard(File(app.noBackupFilesDir, "unlock-attempts")),
        openDatabase = { key -> SigiloDatabase.open(app, SigiloDatabase.encrypted(key)) },
        makeEngine = { db -> MessageEngine(db, inbox, gateway, systemSms, events, AddressNormalizer(::region)) },
    )

    fun start() {
        scope.launch {
            lock.start()
            afterUnlock()
        }
    }

    /** Catches up after the database opened: waiting SMS, old disappearing messages, the system history. */
    fun afterUnlock() {
        scope.launch {
            lock.withEngine { engine ->
                engine.drain()
                engine.deleteExpired()
                if (DefaultSmsApp.isDefault(app)) engine.importSystemSms()
            } ?: return@launch
            notifier.cancelLocked()
        }
    }

    /** Called by the SMS receivers: handles the new items now, or tells the user they are waiting. */
    suspend fun processInbox() {
        val handled = lock.withEngine { it.drain() }
        if (handled == null) notifier.showLocked(inbox.waitingMessages())
    }

    /** The country the SIM belongs to, for reading local phone numbers; Brazil when unknown. */
    private fun region(): String {
        val telephony = app.getSystemService(TelephonyManager::class.java)
        val iso = telephony?.simCountryIso?.takeIf { it.isNotBlank() } ?: telephony?.networkCountryIso?.takeIf { it.isNotBlank() }
            ?: Locale.getDefault().country.takeIf { it.isNotBlank() } ?: "BR"
        return iso.uppercase(Locale.ROOT)
    }

    /** Locks the app some time after it leaves the screen, when a password is set. */
    val autoLock = object : DefaultLifecycleObserver {
        private var pending: Job? = null

        override fun onStart(owner: LifecycleOwner) {
            pending?.cancel()
            pending = null
        }

        override fun onStop(owner: LifecycleOwner) {
            if (!lock.hasPassword()) return
            pending?.cancel()
            pending = scope.launch {
                delay(settings.autoLockMillis)
                lock.lock()
            }
        }
    }

    companion object {
        /** Android 8 needs the notification permission only from 13; kept here for the UI. */
        val needsNotificationPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    }
}

val Context.appContainer: AppContainer
    get() = (applicationContext as SigiloApp).container
