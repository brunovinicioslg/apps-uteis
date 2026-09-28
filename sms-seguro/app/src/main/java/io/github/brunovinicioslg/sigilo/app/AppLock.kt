package io.github.brunovinicioslg.sigilo.app

import io.github.brunovinicioslg.sigilo.app.db.SigiloDatabase
import io.github.brunovinicioslg.sigilo.app.engine.MessageEngine
import io.github.brunovinicioslg.sigilo.app.vault.UnlockGuard
import io.github.brunovinicioslg.sigilo.app.vault.Vault
import io.github.brunovinicioslg.sigilo.app.vault.VaultUnavailableException
import io.github.brunovinicioslg.sigilo.app.vault.WrongPasswordException
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

sealed interface LockState {
    data object Loading : LockState

    /** First run: no vault yet. */
    data object NeedsSetup : LockState

    /** A password protects the vault and nobody entered it yet. */
    data object Locked : LockState

    data object Unlocked : LockState

    /** The vault cannot be opened on this device (restored from another phone, damaged). */
    data object Unavailable : LockState
}

sealed interface UnlockResult {
    data object Ok : UnlockResult

    data class Wrong(val waitMillis: Long) : UnlockResult

    /** Too many wrong passwords: try again after this long. */
    data class Wait(val millis: Long) : UnlockResult

    data object Unavailable : UnlockResult
}

/**
 * Opens and closes the encrypted database and owns the one thread that uses it. Without a
 * password the database opens by itself whenever needed (also for SMS arriving in the background);
 * with one, only after the user enters it, until the app locks again.
 */
class AppLock(
    private val vault: Vault,
    private val guard: UnlockGuard,
    private val openDatabase: (ByteArray) -> SigiloDatabase,
    private val makeEngine: (SigiloDatabase) -> MessageEngine,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Every use of the database and of the engine runs here, one at a time. */
    val dispatcher: CoroutineDispatcher = Executors.newSingleThreadExecutor { r -> Thread(r, "sigilo-engine") }.asCoroutineDispatcher()

    private val _state = MutableStateFlow<LockState>(LockState.Loading)
    val state: StateFlow<LockState> = _state.asStateFlow()

    private var database: SigiloDatabase? = null
    private var engine: MessageEngine? = null
    private var masterKey: ByteArray? = null

    /** Works out the state at startup; opens right away when there is no password. */
    suspend fun start() = withContext(dispatcher) {
        if (engine != null) return@withContext
        _state.value = when {
            !vault.exists() -> LockState.NeedsSetup
            else -> try {
                if (vault.hasPassword()) LockState.Locked else openWith(vault.open(null))
            } catch (_: VaultUnavailableException) {
                LockState.Unavailable
            }
        }
    }

    suspend fun setUp(password: CharArray?) = withContext(dispatcher) {
        check(engine == null && !vault.exists()) { "already set up" }
        _state.value = openWith(vault.create(password))
    }

    suspend fun unlock(password: CharArray): UnlockResult = withContext(dispatcher) {
        if (engine != null) return@withContext UnlockResult.Ok
        val wait = guard.waitMillis(clock())
        if (wait > 0) return@withContext UnlockResult.Wait(wait)
        try {
            val key = vault.open(password)
            guard.recordSuccess()
            _state.value = openWith(key)
            UnlockResult.Ok
        } catch (_: WrongPasswordException) {
            guard.recordFailure(clock())
            UnlockResult.Wrong(guard.waitMillis(clock()))
        } catch (_: VaultUnavailableException) {
            _state.value = LockState.Unavailable
            UnlockResult.Unavailable
        } finally {
            password.fill('\u0000')
        }
    }

    /** Closes the database; with a password, nothing can be read until it is entered again. */
    suspend fun lock() = withContext(dispatcher) {
        if (!vault.exists() || !vault.hasPassword()) return@withContext
        close()
        _state.value = LockState.Locked
    }

    fun hasPassword(): Boolean = vault.exists() && vault.hasPassword()

    /** Sets, changes (non-null) or removes (null) the password; the app must be unlocked. */
    suspend fun changePassword(newPassword: CharArray?) = withContext(dispatcher) {
        val key = checkNotNull(masterKey) { "locked" }
        try {
            vault.changePassword(key, newPassword)
        } finally {
            newPassword?.fill('\u0000')
        }
    }

    /**
     * Runs [block] with the engine; null when the app is locked with a password. Without a password
     * the database opens on demand, so SMS arriving in the background are handled at once.
     */
    suspend fun <T> withEngine(block: (MessageEngine) -> T): T? = withContext(dispatcher) {
        val current = engine ?: run {
            if (!vault.exists() || vault.hasPassword()) return@withContext null
            try {
                _state.value = openWith(vault.open(null))
            } catch (_: VaultUnavailableException) {
                _state.value = LockState.Unavailable
                return@withContext null
            }
            engine!!
        }
        block(current)
    }

    private fun openWith(key: ByteArray): LockState {
        val db = openDatabase(key)
        database = db
        masterKey = key
        engine = makeEngine(db)
        return LockState.Unlocked
    }

    private fun close() {
        engine = null
        database?.close()
        database = null
        masterKey?.fill(0)
        masterKey = null
    }
}
