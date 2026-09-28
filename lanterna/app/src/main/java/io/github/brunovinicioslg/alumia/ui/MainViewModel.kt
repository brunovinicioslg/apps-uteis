package io.github.brunovinicioslg.alumia.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.brunovinicioslg.alumia.appContainer
import io.github.brunovinicioslg.alumia.core.settings.Settings
import io.github.brunovinicioslg.alumia.service.TorchService
import io.github.brunovinicioslg.alumia.service.TorchServiceLauncher
import io.github.brunovinicioslg.alumia.torch.TorchState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val container = application.appContainer

    /** Null until the first read from storage. */
    val settings: StateFlow<Settings?> = container.settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    val torch: StateFlow<TorchState> = container.torch.state

    val torchMaxLevel: Int = container.torch.maxLevel

    /** Goes through the service so the light stays on after the screen is closed. */
    fun toggleTorch() {
        TorchServiceLauncher.send(getApplication(), TorchService.ACTION_TOGGLE_TORCH)
    }

    fun updateSettings(transform: (Settings) -> Settings) {
        viewModelScope.launch {
            val updated = container.settingsRepository.update(transform)
            if (updated.detectionEnabled) ensureServiceRunning()
        }
    }

    /** Safe to call repeatedly; the service ignores redundant starts. */
    fun ensureServiceRunning() {
        TorchServiceLauncher.send(getApplication(), TorchService.ACTION_START)
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
