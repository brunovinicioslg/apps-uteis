package io.github.brunovinicioslg.medeai.ui.photo

import android.app.Application
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import io.github.brunovinicioslg.medeai.geometry.Vec2
import io.github.brunovinicioslg.medeai.photo.PhotoSession
import io.github.brunovinicioslg.medeai.photo.ReferenceObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ReferenceChoice { CARD, A4, LETTER, CUSTOM }

data class PhotoUiState(
    val image: ImageBitmap? = null,
    val loading: Boolean = false,
    val loadFailed: Boolean = false,
    val reference: ReferenceChoice = ReferenceChoice.CARD,
    val session: PhotoSession = PhotoSession(),
)

class PhotoViewModel(application: Application, private val saved: SavedStateHandle) : AndroidViewModel(application) {

    private val mutableState = MutableStateFlow(PhotoUiState())
    val state: StateFlow<PhotoUiState> = mutableState.asStateFlow()

    init {
        // After the process was killed (common while the camera app is open), reload the photo.
        saved.get<String>(KEY_IMAGE)?.let { load(it.toUri()) }
    }

    /** Uri handed to the camera app; kept across process death until the result arrives. */
    fun newCaptureUri(): Uri = ImageLoader.newCaptureUri(getApplication()).also { saved[KEY_PENDING_CAPTURE] = it.toString() }

    fun onCaptureResult(success: Boolean) {
        val uri = saved.get<String>(KEY_PENDING_CAPTURE)?.toUri()
        saved.remove<String>(KEY_PENDING_CAPTURE)
        if (success && uri != null) load(uri)
    }

    fun load(uri: Uri) {
        mutableState.update { it.copy(loading = true, loadFailed = false) }
        viewModelScope.launch {
            val bitmap = ImageLoader.load(getApplication(), uri)
            if (bitmap != null) {
                val copy = if (uri.toString() == saved.get<String>(KEY_IMAGE)) uri else ImageLoader.persist(getApplication(), bitmap)
                if (copy != null) saved[KEY_IMAGE] = copy.toString() else saved.remove<String>(KEY_IMAGE)
            }
            mutableState.update { current ->
                if (bitmap == null) {
                    current.copy(loading = false, loadFailed = true)
                } else {
                    current.copy(
                        loading = false,
                        image = bitmap.asImageBitmap(),
                        // A new photo keeps the chosen reference but starts over.
                        session = PhotoSession(current.session.referenceWidth, current.session.referenceHeight),
                    )
                }
            }
        }
    }

    fun tap(p: Vec2) = mutableState.update { it.copy(session = it.session.tap(p)) }

    fun move(handle: PhotoSession.Handle, p: Vec2) = mutableState.update { it.copy(session = it.session.move(handle, p)) }

    fun undo() = mutableState.update { it.copy(session = it.session.undo()) }

    fun clearMeasurements() = mutableState.update { it.copy(session = it.session.clearMeasurements()) }

    fun chooseReference(choice: ReferenceChoice, customWidthMeters: Double = 0.0, customHeightMeters: Double = 0.0) {
        val size = when (choice) {
            ReferenceChoice.CARD -> ReferenceObject.CARD.widthMeters to ReferenceObject.CARD.heightMeters
            ReferenceChoice.A4 -> ReferenceObject.A4_SHEET.widthMeters to ReferenceObject.A4_SHEET.heightMeters
            ReferenceChoice.LETTER -> ReferenceObject.LETTER_SHEET.widthMeters to ReferenceObject.LETTER_SHEET.heightMeters
            ReferenceChoice.CUSTOM -> customWidthMeters to customHeightMeters
        }
        if (!(size.first > 0.0 && size.second > 0.0)) return
        mutableState.update { it.copy(reference = choice, session = it.session.withReference(size.first, size.second)) }
    }

    /** Back to choosing a photo. */
    fun closeImage() {
        saved.remove<String>(KEY_IMAGE)
        mutableState.update { PhotoUiState(reference = it.reference, session = PhotoSession(it.session.referenceWidth, it.session.referenceHeight)) }
    }

    private companion object {
        const val KEY_IMAGE = "image"
        const val KEY_PENDING_CAPTURE = "pending_capture"
    }
}
