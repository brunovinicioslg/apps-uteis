package io.github.brunovinicioslg.alumia.torch

import android.content.Context
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.github.brunovinicioslg.alumia.core.policy.Policies
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

sealed interface TorchState {
    /** Waiting for the camera service to report the current mode. */
    data object Unknown : TorchState

    data object NoFlash : TorchState

    /** Another app (usually the camera) is using the flash. */
    data object Unavailable : TorchState

    data object Off : TorchState

    /** [byApp] is true when this app turned the light on (other sources: system tile, other apps). */
    data class On(val byApp: Boolean) : TorchState
}

enum class TorchResult { TURNED_ON, TURNED_OFF, NO_FLASH, BUSY }

/**
 * Single source of truth for the flashlight. The camera service reports every change through
 * [CameraManager.TorchCallback], including changes made by the system tile or other apps.
 * Must be created on the main thread.
 */
class TorchController(context: Context) {

    private val cameraManager: CameraManager? = context.getSystemService(CameraManager::class.java)
    private val cameraId: String? = cameraManager?.let(::findFlashCamera)

    /** Highest hardware brightness level; 1 means brightness cannot be changed. */
    val maxLevel: Int = cameraId?.let(::readMaxLevel) ?: 1

    private val mutableState = MutableStateFlow<TorchState>(if (cameraId == null) TorchState.NoFlash else TorchState.Unknown)
    val state: StateFlow<TorchState> = mutableState.asStateFlow()

    private var expectingOwnTurnOn = false

    private val callback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(id: String, enabled: Boolean) {
            if (id != cameraId) return
            if (enabled) {
                val alreadyOurs = (mutableState.value as? TorchState.On)?.byApp == true
                mutableState.value = TorchState.On(byApp = expectingOwnTurnOn || alreadyOurs)
            } else {
                mutableState.value = TorchState.Off
            }
            expectingOwnTurnOn = false
        }

        override fun onTorchModeUnavailable(id: String) {
            if (id != cameraId) return
            expectingOwnTurnOn = false
            mutableState.value = TorchState.Unavailable
        }
    }

    init {
        if (cameraId != null) {
            // The callback immediately reports the current mode, which resolves Unknown.
            cameraManager?.registerTorchCallback(callback, Handler(Looper.getMainLooper()))
        }
    }

    suspend fun toggle(levelPercent: Int): TorchResult = when (awaitKnownState()) {
        is TorchState.On -> setEnabled(false, levelPercent)
        TorchState.Off, TorchState.Unknown -> setEnabled(true, levelPercent)
        TorchState.NoFlash -> TorchResult.NO_FLASH
        TorchState.Unavailable -> TorchResult.BUSY
    }

    /** Changes the light and suspends until the camera service confirms it (or a timeout). */
    suspend fun setEnabled(on: Boolean, levelPercent: Int = 100): TorchResult {
        val id = cameraId ?: return TorchResult.NO_FLASH
        val manager = cameraManager ?: return TorchResult.NO_FLASH
        try {
            if (on) {
                expectingOwnTurnOn = true
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && maxLevel > 1) {
                    manager.turnOnTorchWithStrengthLevel(id, Policies.torchLevel(levelPercent, maxLevel))
                } else {
                    manager.setTorchMode(id, true)
                }
            } else {
                manager.setTorchMode(id, false)
            }
        } catch (e: CameraAccessException) {
            expectingOwnTurnOn = false
            Log.w(TAG, "Torch change rejected", e)
            return TorchResult.BUSY
        } catch (e: IllegalArgumentException) {
            expectingOwnTurnOn = false
            Log.w(TAG, "Torch change rejected", e)
            return TorchResult.BUSY
        }
        val confirmed = withTimeoutOrNull(CONFIRM_TIMEOUT_MS) { state.first { (it is TorchState.On) == on } }
        return when {
            confirmed == null -> TorchResult.BUSY
            on -> TorchResult.TURNED_ON
            else -> TorchResult.TURNED_OFF
        }
    }

    /** Applies a new brightness if this app currently holds the light on. */
    fun applyLevel(levelPercent: Int) {
        val id = cameraId ?: return
        val current = mutableState.value
        if (current !is TorchState.On || !current.byApp) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || maxLevel <= 1) return
        try {
            cameraManager?.turnOnTorchWithStrengthLevel(id, Policies.torchLevel(levelPercent, maxLevel))
        } catch (e: CameraAccessException) {
            Log.w(TAG, "Brightness change rejected", e)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Brightness change rejected", e)
        }
    }

    private suspend fun awaitKnownState(): TorchState =
        withTimeoutOrNull(CONFIRM_TIMEOUT_MS) { state.first { it != TorchState.Unknown } } ?: state.value

    private fun findFlashCamera(manager: CameraManager): String? = try {
        val withFlash = manager.cameraIdList.filter {
            manager.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
        withFlash.firstOrNull {
            manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: withFlash.firstOrNull()
    } catch (e: CameraAccessException) {
        Log.w(TAG, "Cannot list cameras", e)
        null
    } catch (e: IllegalArgumentException) {
        Log.w(TAG, "Cannot list cameras", e)
        null
    }

    private fun readMaxLevel(id: String): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return 1
        return try {
            cameraManager?.getCameraCharacteristics(id)?.get(CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL) ?: 1
        } catch (e: CameraAccessException) {
            Log.w(TAG, "Cannot read torch levels", e)
            1
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Cannot read torch levels", e)
            1
        }
    }

    private companion object {
        const val TAG = "TorchController"
        const val CONFIRM_TIMEOUT_MS = 1_500L
    }
}
