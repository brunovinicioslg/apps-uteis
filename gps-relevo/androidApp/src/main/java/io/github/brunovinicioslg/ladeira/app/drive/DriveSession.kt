package io.github.brunovinicioslg.ladeira.app.drive

import io.github.brunovinicioslg.ladeira.drive.DriveState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class NavigationStatus(
    val running: Boolean = false,
    val drive: DriveState? = null,
    /** Direction of travel, for turning the map; null until known. */
    val bearing: Double? = null,
    val gpsEnabled: Boolean = true,
    /** Null until the speech engine answers; false when the phone cannot speak the app's language. */
    val voiceAvailable: Boolean? = null,
)

/** What the navigation service is doing, for the screen. Written by the service, from any thread. */
class DriveSession {
    private val _status = MutableStateFlow(NavigationStatus())
    val status: StateFlow<NavigationStatus> = _status.asStateFlow()

    fun update(transform: (NavigationStatus) -> NavigationStatus) = _status.update(transform)
}
