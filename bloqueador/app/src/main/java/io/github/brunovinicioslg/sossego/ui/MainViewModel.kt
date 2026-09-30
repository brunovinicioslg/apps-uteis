package io.github.brunovinicioslg.sossego.ui

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.brunovinicioslg.sossego.appContainer
import io.github.brunovinicioslg.sossego.core.lists.ListEntry
import io.github.brunovinicioslg.sossego.core.lists.ListFile
import io.github.brunovinicioslg.sossego.core.rules.Settings
import io.github.brunovinicioslg.sossego.data.BlockedCall
import io.github.brunovinicioslg.sossego.data.ListRepository
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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

    init {
        viewModelScope.launch(Dispatchers.IO) {
            if (entries.value == null) container.lists.load()
            if (history.value == null) container.history.load()
        }
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
