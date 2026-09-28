package io.github.brunovinicioslg.alumia.tile

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import io.github.brunovinicioslg.alumia.R
import io.github.brunovinicioslg.alumia.appContainer
import io.github.brunovinicioslg.alumia.service.TorchService
import io.github.brunovinicioslg.alumia.service.TorchServiceLauncher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Quick Settings tile that turns shake detection on and off. */
class DetectionTileService : TileService() {

    private var listeningScope: CoroutineScope? = null
    private var detectionEnabled: Boolean? = null

    override fun onStartListening() {
        super.onStartListening()
        val scope = MainScope()
        listeningScope = scope
        scope.launch {
            appContainer.settingsRepository.settings.collect {
                detectionEnabled = it.detectionEnabled
                render(it.detectionEnabled)
            }
        }
    }

    override fun onStopListening() {
        listeningScope?.cancel()
        listeningScope = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        detectionEnabled?.let { render(!it) } // immediate feedback when the state is known
        val container = appContainer
        // Flips the stored value atomically, so it works even if the click arrives before the
        // tile has read the settings. The application scope survives the panel closing mid-write.
        container.applicationScope.launch {
            val updated = container.settingsRepository.update { it.copy(detectionEnabled = !it.detectionEnabled) }
            detectionEnabled = updated.detectionEnabled
            render(updated.detectionEnabled)
            if (updated.detectionEnabled) TorchServiceLauncher.send(this@DetectionTileService, TorchService.ACTION_START)
        }
    }

    private fun render(enabled: Boolean) {
        val tile = qsTile ?: return
        tile.state = if (enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.tile_label)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = getString(if (enabled) R.string.tile_on else R.string.tile_off)
        }
        tile.updateTile()
    }
}
