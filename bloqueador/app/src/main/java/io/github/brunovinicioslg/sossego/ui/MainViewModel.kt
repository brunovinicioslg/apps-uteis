package io.github.brunovinicioslg.sossego.ui

import android.app.Application
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
import android.provider.ContactsContract
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.brunovinicioslg.sossego.appContainer
import io.github.brunovinicioslg.sossego.core.dial.BlockEvent
import io.github.brunovinicioslg.sossego.core.dial.Contact
import io.github.brunovinicioslg.sossego.core.dial.LoggedCall
import io.github.brunovinicioslg.sossego.core.dial.RecentCall
import io.github.brunovinicioslg.sossego.core.dial.Recents
import io.github.brunovinicioslg.sossego.core.lists.ListEntry
import io.github.brunovinicioslg.sossego.core.lists.ListFile
import io.github.brunovinicioslg.sossego.core.number.PhoneNumbers
import io.github.brunovinicioslg.sossego.core.rules.Settings
import io.github.brunovinicioslg.sossego.data.BlockedCall
import io.github.brunovinicioslg.sossego.data.ListRepository
import io.github.brunovinicioslg.sossego.device.CallLogReader
import io.github.brunovinicioslg.sossego.device.Caller
import io.github.brunovinicioslg.sossego.device.ContactsReader
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What an import did. */
sealed interface ImportResult {
    data class Done(val added: Int, val skipped: Int) : ImportResult

    data object Failed : ImportResult
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val container = application.appContainer

    val settings: StateFlow<Settings?> =
        container.settingsRepository.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val entries: StateFlow<List<ListEntry>?> = container.lists.entries
    val history: StateFlow<List<BlockedCall>?> = container.history.recent

    private val contactsReader = ContactsReader(application)
    private val callLogReader = CallLogReader(application)
    private val caller = Caller(application)
    private val _contacts = MutableStateFlow<List<Contact>?>(null)
    private val callLog = MutableStateFlow<List<LoggedCall>?>(null)

    /** Null until read, or without permission. */
    val contacts: StateFlow<List<Contact>?> = _contacts.asStateFlow()

    /** Whether the phone's call log could be read (else the recent calls show only the blocked). */
    val callLogShown: StateFlow<Boolean> = callLog.map { it != null }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** The phone's calls and this app's blocks, newest first, each call once. */
    val recents: StateFlow<List<RecentCall>> = combine(callLog, history) { log, blocks ->
        Recents.merge(log.orEmpty(), blocks.orEmpty().map { BlockEvent(it.id, it.time, it.key, it.reason, it.silenced) }, PhoneNumbers::key)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val resolver = application.contentResolver
    private val contactsObserver = observer { refreshContacts() }
    private val callLogObserver = observer { refreshCallLog() }
    private val observed = mutableSetOf<Uri>()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            if (entries.value == null) container.lists.load()
            if (history.value == null) container.history.load()
        }
        refreshPhoneData()
    }

    /** Reads the contacts and the call log again (after a permission changed, or on return). */
    fun refreshPhoneData() {
        refreshContacts()
        refreshCallLog()
    }

    private fun refreshContacts() {
        viewModelScope.launch(Dispatchers.IO) {
            _contacts.value = contactsReader.all()
            if (_contacts.value != null) observe(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, contactsObserver)
        }
    }

    private fun refreshCallLog() {
        viewModelScope.launch(Dispatchers.IO) {
            callLog.value = callLogReader.recent(caller.simLabels())
            if (callLog.value != null) observe(CallLog.Calls.CONTENT_URI, callLogObserver)
        }
    }

    /** Watches [uri] for changes, once it can be read (and only once). */
    @Synchronized
    private fun observe(uri: Uri, observer: ContentObserver) {
        if (uri in observed) return
        try {
            resolver.registerContentObserver(uri, true, observer)
            observed += uri
        } catch (e: SecurityException) {
            // Not readable after all: watched on a later refresh.
        }
    }

    private fun observer(onChange: () -> Unit) = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = onChange()
    }

    override fun onCleared() {
        resolver.unregisterContentObserver(contactsObserver)
        resolver.unregisterContentObserver(callLogObserver)
    }

    fun updateSettings(transform: (Settings) -> Settings) {
        viewModelScope.launch { container.settingsRepository.update(transform) }
    }

    fun addEntry(entry: ListEntry, onDone: (ListRepository.Added) -> Unit) = io(onDone) { container.lists.add(entry) }

    fun replaceEntry(id: Long, entry: ListEntry, onDone: (ListRepository.Added) -> Unit) =
        io(onDone) { container.lists.replace(id, entry) }

    fun deleteEntry(id: Long) = io({}) { container.lists.delete(id) }

    fun deleteHistory(id: Long) = io({}) { container.history.delete(id) }

    fun clearHistory() = io({}) { container.history.clear() }

    /** Whether [number] is in the contacts (false when the app may not read them). */
    fun isContact(number: String, onDone: (Boolean) -> Unit) = io(onDone) { container.contacts.isContact(number) }

    fun exportLists(uri: Uri, onDone: (Boolean) -> Unit) = io(onDone) {
        try {
            val text = ListFile.write(container.lists.all())
            val stream = getApplication<Application>().contentResolver.openOutputStream(uri, "wt") ?: return@io false
            stream.bufferedWriter(Charsets.UTF_8).use { it.write(text) }
            true
        } catch (e: IOException) {
            Log.w(TAG, "Export failed", e)
            false
        } catch (e: SecurityException) {
            Log.w(TAG, "Export failed", e)
            false
        }
    }

    fun importLists(uri: Uri, onDone: (ImportResult) -> Unit) = io(onDone) {
        try {
            val stream = getApplication<Application>().contentResolver.openInputStream(uri) ?: return@io ImportResult.Failed
            val bytes = stream.use { it.readNBytesCompat(MAX_IMPORT_BYTES + 1) }
            if (bytes.size > MAX_IMPORT_BYTES) return@io ImportResult.Failed
            val parsed = ListFile.read(bytes.toString(Charsets.UTF_8))
            ImportResult.Done(container.lists.import(parsed.entries), parsed.skipped)
        } catch (e: IOException) {
            Log.w(TAG, "Import failed", e)
            ImportResult.Failed
        } catch (e: SecurityException) {
            Log.w(TAG, "Import failed", e)
            ImportResult.Failed
        }
    }

    private fun <T> io(onDone: (T) -> Unit, work: suspend () -> T) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { work() }
            onDone(result)
        }
    }

    private companion object {
        const val TAG = "SossegoLists"

        /** A list file is text; anything bigger than this is not one. */
        const val MAX_IMPORT_BYTES = 2 * 1024 * 1024
    }
}

/** readNBytes needs Android 13; this reads at most [limit] bytes on any version. */
private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (out.size() < limit) {
        val read = read(buffer, 0, minOf(buffer.size, limit - out.size()))
        if (read < 0) break
        out.write(buffer, 0, read)
    }
    return out.toByteArray()
}
