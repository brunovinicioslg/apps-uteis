package io.github.brunovinicioslg.medeai.ui.photo

import android.Manifest
import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.magnifier
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.brunovinicioslg.medeai.R
import io.github.brunovinicioslg.medeai.geometry.Vec2
import io.github.brunovinicioslg.medeai.photo.PhotoPlane
import io.github.brunovinicioslg.medeai.photo.PhotoSession
import io.github.brunovinicioslg.medeai.ui.ToolTopBar
import io.github.brunovinicioslg.medeai.ui.hasCameraPermission
import io.github.brunovinicioslg.medeai.ui.rememberFormatter
import io.github.brunovinicioslg.medeai.ui.theme.MeasureOutline
import io.github.brunovinicioslg.medeai.ui.theme.MeasureYellow
import io.github.brunovinicioslg.medeai.units.UnitSystem

const val PHOTO_CANVAS_TAG = "photo_canvas"

@Composable
fun PhotoScreen(unitSystem: UnitSystem, onBack: () -> Unit, viewModel: PhotoViewModel = viewModel()) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    var cameraFailed by remember { mutableStateOf(false) }

    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture(), viewModel::onCaptureResult)
    val launchCamera = {
        try {
            takePicture.launch(viewModel.newCaptureUri())
        } catch (_: ActivityNotFoundException) {
            cameraFailed = true
        }
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        // Apps that declare CAMERA may only start the camera app once it is granted.
        if (granted) launchCamera() else cameraFailed = true
    }
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(viewModel::load) }

    Scaffold(topBar = { ToolTopBar(stringResource(R.string.tool_photo_title), onBack) }) { padding ->
        val image = state.image
        if (image == null) {
            PhotoPicker(
                loading = state.loading,
                failed = state.loadFailed || cameraFailed,
                onTakePhoto = {
                    cameraFailed = false
                    if (hasCameraPermission(context)) launchCamera() else cameraPermission.launch(Manifest.permission.CAMERA)
                },
                onPickPhoto = {
                    cameraFailed = false
                    pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
                modifier = Modifier.padding(padding),
            )
        } else {
            PhotoMeasure(
                image = image,
                state = state,
                unitSystem = unitSystem,
                onTap = viewModel::tap,
                onMove = viewModel::move,
                onUndo = viewModel::undo,
                onClear = viewModel::clearMeasurements,
                onReference = viewModel::chooseReference,
                onNewPhoto = viewModel::closeImage,
                modifier = Modifier.padding(padding),
            )
        }
    }
}

@Composable
private fun PhotoPicker(loading: Boolean, failed: Boolean, onTakePhoto: () -> Unit, onPickPhoto: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.photo_intro), style = MaterialTheme.typography.bodyLarge)
        if (loading) {
            CircularProgressIndicator()
        } else {
            Button(onClick = onTakePhoto, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.photo_take)) }
            OutlinedButton(onClick = onPickPhoto, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.photo_pick)) }
        }
        if (failed) Text(stringResource(R.string.photo_load_failed), color = MaterialTheme.colorScheme.error)
    }
}

@Composable
fun PhotoMeasure(
    image: ImageBitmap,
    state: PhotoUiState,
    unitSystem: UnitSystem,
    onTap: (Vec2) -> Unit,
    onMove: (PhotoSession.Handle, Vec2) -> Unit,
    onUndo: () -> Unit,
    onClear: () -> Unit,
    onReference: (ReferenceChoice, Double, Double) -> Unit,
    onNewPhoto: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val format = rememberFormatter()
    val session = state.session
    var customDialog by rememberSaveable { mutableStateOf(false) }
    val referenceName = stringResource(
        when (state.reference) {
            ReferenceChoice.CARD -> R.string.reference_card_name
            ReferenceChoice.A4 -> R.string.reference_a4_name
            ReferenceChoice.LETTER -> R.string.reference_letter_name
            ReferenceChoice.CUSTOM -> R.string.reference_custom_name
        },
    )

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ReferenceChoice.entries.forEach { choice ->
                FilterChip(
                    selected = state.reference == choice,
                    onClick = { if (choice == ReferenceChoice.CUSTOM) customDialog = true else onReference(choice, 0.0, 0.0) },
                    label = {
                        Text(
                            stringResource(
                                when (choice) {
                                    ReferenceChoice.CARD -> R.string.reference_card
                                    ReferenceChoice.A4 -> R.string.reference_a4
                                    ReferenceChoice.LETTER -> R.string.reference_letter
                                    ReferenceChoice.CUSTOM -> R.string.reference_custom
                                },
                            ),
                        )
                    },
                )
            }
        }
        Text(
            text = when {
                session.placingCorners -> stringResource(R.string.photo_step_corners, referenceName, session.corners.size)
                session.invalidCorners -> stringResource(R.string.photo_step_invalid)
                else -> stringResource(R.string.photo_step_measure, referenceName)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (session.invalidCorners) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            // Fixed height: if the text grew, the photo would jump just as the user is about to tap.
            minLines = 2,
            maxLines = 2,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        PhotoCanvas(
            image = image,
            session = session,
            label = { index -> session.length(index)?.let { format.length(it, unitSystem) } },
            onTap = onTap,
            onMove = onMove,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            TextButton(onClick = onUndo, enabled = session.corners.isNotEmpty()) { Text(stringResource(R.string.photo_undo)) }
            TextButton(onClick = onClear, enabled = session.segments.isNotEmpty()) { Text(stringResource(R.string.photo_clear)) }
            TextButton(onClick = onNewPhoto) { Text(stringResource(R.string.photo_new)) }
        }
    }

    if (customDialog) {
        CustomReferenceDialog(
            onConfirm = { w, h ->
                customDialog = false
                onReference(ReferenceChoice.CUSTOM, w, h)
            },
            onDismiss = { customDialog = false },
        )
    }
}

@Composable
private fun PhotoCanvas(
    image: ImageBitmap,
    session: PhotoSession,
    label: (Int) -> String?,
    onTap: (Vec2) -> Unit,
    onMove: (PhotoSession.Handle, Vec2) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    var fit by remember { mutableStateOf<FitTransform?>(null) }
    var dragPosition by remember { mutableStateOf(Offset.Unspecified) }
    // Gesture handlers outlive recompositions; they must read the latest session, not a stale one.
    val currentSession by rememberUpdatedState(session)
    val currentOnMove by rememberUpdatedState(onMove)
    val currentOnTap by rememberUpdatedState(onTap)

    val transform = fit
    Canvas(
        modifier
            .testTag(PHOTO_CANVAS_TAG)
            .onSizeChanged { fit = FitTransform.fit(image.width, image.height, it.width.toFloat(), it.height.toFloat()) }
            .pointerInput(transform) {
                val t = transform ?: return@pointerInput
                detectTapGestures { pos ->
                    val p = t.toImage(pos)
                    if (t.isInsideImage(p)) currentOnTap(p)
                }
            }
            .pointerInput(transform) {
                val t = transform ?: return@pointerInput
                var handle: PhotoSession.Handle? = null
                var position = Offset.Zero
                detectDragGestures(
                    onDragStart = { pos ->
                        handle = currentSession.handleNear(t.toImage(pos), TOUCH_RADIUS_DP.dp.toPx() / t.scale.toDouble())
                        position = pos
                        if (handle != null) dragPosition = pos
                    },
                    onDragEnd = {
                        handle = null
                        dragPosition = Offset.Unspecified
                    },
                    onDragCancel = {
                        handle = null
                        dragPosition = Offset.Unspecified
                    },
                    onDrag = { change, amount ->
                        val h = handle ?: return@detectDragGestures
                        change.consume()
                        position += amount
                        dragPosition = position
                        val p = t.toImage(position)
                        currentOnMove(h, Vec2(p.x.coerceIn(0.0, t.imageWidth.toDouble()), p.y.coerceIn(0.0, t.imageHeight.toDouble())))
                    },
                )
            }
            // Shows what is under the finger while dragging a point (Android 9+).
            .magnifier(
                sourceCenter = { dragPosition },
                magnifierCenter = { if (dragPosition.isSpecified) dragPosition - Offset(0f, with(density) { 96.dp.toPx() }) else Offset.Unspecified },
                zoom = 3f,
            ),
    ) {
        val t = transform ?: return@Canvas
        drawImage(
            image = image,
            dstOffset = IntOffset(t.offsetX.toInt(), t.offsetY.toInt()),
            dstSize = IntSize((image.width * t.scale).toInt(), (image.height * t.scale).toInt()),
        )
        drawReference(session, t)
        session.segments.forEachIndexed { index, segment ->
            val start = t.toView(segment.start)
            val end = segment.end?.let(t::toView)
            if (end != null) outlinedLine(start, end)
            handle(start)
            if (end != null) {
                handle(end)
                val text = label(index) ?: "?"
                val layout = textMeasurer.measure(text, TextStyle(color = MeasureOutline, fontSize = 15.sp, fontWeight = FontWeight.Bold))
                val middle = Offset((start.x + end.x) / 2, (start.y + end.y) / 2)
                val topLeft = middle - Offset(layout.size.width / 2f, layout.size.height + 12.dp.toPx())
                drawRoundRect(
                    color = MeasureYellow,
                    topLeft = topLeft - Offset(6.dp.toPx(), 2.dp.toPx()),
                    size = androidx.compose.ui.geometry.Size(layout.size.width + 12.dp.toPx(), layout.size.height + 4.dp.toPx()),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(6.dp.toPx()),
                )
                drawText(layout, topLeft = topLeft)
            }
        }
    }
}

private fun DrawScope.drawReference(session: PhotoSession, t: FitTransform) {
    val points = session.corners.map(t::toView)
    if (session.corners.size == 4) {
        val ordered = PhotoPlane.orderAroundCentroid(session.corners).map(t::toView)
        ordered.forEachIndexed { i, p -> outlinedLine(p, ordered[(i + 1) % 4]) }
    }
    points.forEach { handle(it) }
}

private fun DrawScope.outlinedLine(a: Offset, b: Offset) {
    drawLine(MeasureOutline, a, b, 5.dp.toPx())
    drawLine(MeasureYellow, a, b, 2.5.dp.toPx())
}

private fun DrawScope.handle(p: Offset) {
    drawCircle(MeasureOutline, 9.dp.toPx(), p)
    drawCircle(MeasureYellow, 6.dp.toPx(), p)
}

@Composable
private fun CustomReferenceDialog(onConfirm: (Double, Double) -> Unit, onDismiss: () -> Unit) {
    var width by rememberSaveable { mutableStateOf("") }
    var height by rememberSaveable { mutableStateOf("") }
    val w = parseCentimeters(width)
    val h = parseCentimeters(height)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.reference_custom_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.reference_custom_text))
                OutlinedTextField(value = width, onValueChange = { width = it }, label = { Text(stringResource(R.string.reference_width_cm)) }, singleLine = true)
                OutlinedTextField(value = height, onValueChange = { height = it }, label = { Text(stringResource(R.string.reference_height_cm)) }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(onClick = { if (w != null && h != null) onConfirm(w / 100, h / 100) }, enabled = w != null && h != null) {
                Text(stringResource(R.string.ok))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Accepts "8,56" or "8.56"; null unless a positive, finite number. */
fun parseCentimeters(text: String): Double? = text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it > 0.0 && it.isFinite() && it < 10_000 }

private const val TOUCH_RADIUS_DP = 32
