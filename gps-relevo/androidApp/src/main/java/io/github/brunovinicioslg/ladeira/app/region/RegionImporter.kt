package io.github.brunovinicioslg.ladeira.app.region

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ImportState(val running: Boolean = false, val results: List<ImportResult> = emptyList())

/** Copies files the user picked into the region store; keeps going if the screen is closed. */
class RegionImporter(private val store: RegionStore, private val scope: CoroutineScope) {

    private val _state = MutableStateFlow(ImportState())
    val state: StateFlow<ImportState> = _state.asStateFlow()

    fun import(resolver: ContentResolver, uris: List<Uri>) {
        if (uris.isEmpty() || _state.value.running) return
        _state.value = ImportState(running = true)
        scope.launch(Dispatchers.IO) {
            // Road packages first: a region is usable for alerts as soon as its roads are in.
            val named = uris.map { it to displayName(resolver, it) }.sortedBy { (_, name) -> !name.endsWith(".ldrp", ignoreCase = true) }
            for ((uri, name) in named) {
                val result = try {
                    resolver.openInputStream(uri)?.use { store.install(name, it) }
                        ?: ImportResult.Failed(name, ImportResult.Error.IO)
                } catch (_: IOException) {
                    ImportResult.Failed(name, ImportResult.Error.IO)
                } catch (_: SecurityException) {
                    ImportResult.Failed(name, ImportResult.Error.IO) // permission to the file was revoked
                }
                _state.update { it.copy(results = it.results + result) }
            }
            _state.update { it.copy(running = false) }
        }
    }

    fun clearResults() = _state.update { if (it.running) it else ImportState() }

    private fun displayName(resolver: ContentResolver, uri: Uri): String {
        val queried = try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
            }
        } catch (_: RuntimeException) {
            null // some providers throw instead of returning no rows
        }
        return queried ?: uri.lastPathSegment?.substringAfterLast('/') ?: "arquivo"
    }
}
