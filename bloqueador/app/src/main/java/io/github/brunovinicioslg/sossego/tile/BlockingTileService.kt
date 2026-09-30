package io.github.brunovinicioslg.sossego.tile

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import io.github.brunovinicioslg.sossego.R
import io.github.brunovinicioslg.sossego.appContainer
import io.github.brunovinicioslg.sossego.core.rules.Mode
import io.github.brunovinicioslg.sossego.core.rules.Settings
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Turns blocking on and off; turning it back on restores the chosen mode. */
class BlockingTileService : TileService() {

    private var listening: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        listening?.cancel()
        listening = appContainer.applicationScope.launch {
            appContainer.settingsRepository.settings.collect(::show)
        }
    }

    override fun onStopListening() {
        listening?.cancel()
        listening = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        appContainer.applicationScope.launch {
            appContainer.settingsRepository.update { it.copy(enabled = !it.enabled) }
        }
    }

    private fun show(settings: Settings) {
        val tile = qsTile ?: return
        tile.state = if (settings.enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.app_name)
        tile.subtitle = getString(if (settings.enabled) modeName(settings.mode) else R.string.mode_off)
        tile.updateTile()
    }

    private fun modeName(mode: Mode): Int = when (mode) {
        Mode.OFF -> R.string.mode_off
        Mode.BLOCKLIST -> R.string.mode_blocklist
        Mode.ALLOWLIST -> R.string.mode_allowlist
        Mode.BLOCK_ALL -> R.string.mode_block_all
    }
}
