package io.github.brunovinicioslg.medeai.ui.tilt

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.brunovinicioslg.medeai.R
import io.github.brunovinicioslg.medeai.sensors.gravityFlow
import io.github.brunovinicioslg.medeai.settings.AppSettings
import io.github.brunovinicioslg.medeai.tilt.TiltRanging
import io.github.brunovinicioslg.medeai.tilt.TiltSession
import io.github.brunovinicioslg.medeai.ui.CameraPermissionGate
import io.github.brunovinicioslg.medeai.ui.CameraPreview
import io.github.brunovinicioslg.medeai.ui.ToolTopBar
import io.github.brunovinicioslg.medeai.ui.rememberFormatter
import io.github.brunovinicioslg.medeai.ui.theme.MeasureOutline
import io.github.brunovinicioslg.medeai.ui.theme.MeasureYellow
import io.github.brunovinicioslg.medeai.units.UnitSystem

private val TiltSessionSaver = listSaver<TiltSession, Double>(
    save = { listOf(it.cameraHeight, it.baseDistance ?: Double.NaN, it.objectHeight ?: Double.NaN) },
    restore = { TiltSession(it[0], it[1].takeUnless(Double::isNaN), it[2].takeUnless(Double::isNaN)) },
)

@Composable
fun TiltScreen(settings: AppSettings, onCameraHeightChange: (Double) -> Unit, onBack: () -> Unit) {
    Scaffold(topBar = { ToolTopBar(stringResource(R.string.tool_tilt_title), onBack) }) { padding ->
        Box(Modifier.padding(padding)) {
            CameraPermissionGate(rationale = stringResource(R.string.tilt_camera_rationale)) {
                val context = LocalContext.current
                val gravity by remember { gravityFlow(context) }.collectAsStateWithLifecycle(initialValue = null)
                val elevation = gravity?.let { TiltRanging.cameraElevationDegrees(it.x, it.y, it.z) }
                var session by rememberSaveable(stateSaver = TiltSessionSaver) { mutableStateOf(TiltSession(settings.cameraHeightMeters)) }
                LaunchedEffect(settings.cameraHeightMeters) {
                    if (session.cameraHeight != settings.cameraHeightMeters) session = session.withCameraHeight(settings.cameraHeightMeters)
                }
                Box(Modifier.fillMaxSize()) {
                    CameraPreview(Modifier.fillMaxSize())
                    Crosshair(Modifier.align(Alignment.Center))
                    TiltPanel(
                        session = session,
                        elevation = elevation,
                        unitSystem = settings.unitSystem,
                        onMark = { angle -> session = session.mark(angle) },
                        onRestart = { session = session.restart() },
                        onCameraHeightChange = onCameraHeightChange,
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            }
        }
    }
}

/** Instructions, live value and actions; stateless so it can be tested without a camera. */
@Composable
fun TiltPanel(
    session: TiltSession,
    elevation: Double?,
    unitSystem: UnitSystem,
    onMark: (Double) -> Unit,
    onRestart: () -> Unit,
    onCameraHeightChange: (Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    val format = rememberFormatter()
    var editingHeight by rememberSaveable { mutableStateOf(false) }
    val preview = elevation?.let(session::preview)

    Surface(
        modifier = modifier.fillMaxWidth().padding(12.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(
                    when (session.step) {
                        TiltSession.Step.AIM_AT_BASE -> R.string.tilt_step_base
                        TiltSession.Step.AIM_AT_TOP -> R.string.tilt_step_top
                        TiltSession.Step.DONE -> R.string.tilt_step_done
                    },
                ),
                style = MaterialTheme.typography.bodyLarge,
            )
            session.baseDistance?.let {
                Text(stringResource(R.string.tilt_result_distance, format.length(it, unitSystem)), style = MaterialTheme.typography.titleMedium)
            }
            session.objectHeight?.let {
                Text(stringResource(R.string.tilt_result_height, format.length(it, unitSystem)), style = MaterialTheme.typography.titleMedium)
            }
            if (session.step != TiltSession.Step.DONE) {
                Text(
                    text = when {
                        elevation == null -> stringResource(R.string.tilt_no_sensor)
                        preview == null && session.step == TiltSession.Step.AIM_AT_BASE -> stringResource(R.string.tilt_aim_lower)
                        preview == null -> stringResource(R.string.tilt_aim_invalid)
                        else -> format.length(preview, unitSystem)
                    },
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (session.step != TiltSession.Step.DONE) {
                    Button(onClick = { elevation?.let(onMark) }, enabled = preview != null) {
                        Text(stringResource(if (session.step == TiltSession.Step.AIM_AT_BASE) R.string.tilt_mark_base else R.string.tilt_mark_top))
                    }
                }
                if (session.baseDistance != null) {
                    OutlinedButton(onClick = onRestart) { Text(stringResource(R.string.tilt_restart)) }
                }
            }
            TextButton(onClick = { editingHeight = true }) {
                Text(stringResource(R.string.tilt_camera_height, format.length(session.cameraHeight, unitSystem)))
            }
        }
    }

    if (editingHeight) {
        CameraHeightDialog(
            initial = session.cameraHeight,
            unitSystem = unitSystem,
            onConfirm = {
                editingHeight = false
                onCameraHeightChange(it)
            },
            onDismiss = { editingHeight = false },
        )
    }
}

@Composable
private fun CameraHeightDialog(initial: Double, unitSystem: UnitSystem, onConfirm: (Double) -> Unit, onDismiss: () -> Unit) {
    val format = rememberFormatter()
    var value by remember { mutableFloatStateOf(initial.toFloat()) }
    val min = TiltSession.MIN_CAMERA_HEIGHT.toFloat()
    val max = TiltSession.MAX_CAMERA_HEIGHT.toFloat()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.tilt_height_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.tilt_height_dialog_text))
                Text(format.length(value.toDouble(), unitSystem), style = MaterialTheme.typography.headlineSmall)
                // One-centimeter steps.
                Slider(value = value, onValueChange = { value = it }, valueRange = min..max, steps = ((max - min) * 100).toInt() - 1)
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(value.toDouble()) }) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun Crosshair(modifier: Modifier = Modifier) {
    Canvas(modifier.size(64.dp).background(Color.Transparent)) {
        val c = center
        val arm = size.minDimension / 2
        for ((color, width) in listOf(MeasureOutline to 5.dp.toPx(), MeasureYellow to 2.dp.toPx())) {
            drawLine(color, Offset(c.x - arm, c.y), Offset(c.x - arm / 4, c.y), width)
            drawLine(color, Offset(c.x + arm / 4, c.y), Offset(c.x + arm, c.y), width)
            drawLine(color, Offset(c.x, c.y - arm), Offset(c.x, c.y - arm / 4), width)
            drawLine(color, Offset(c.x, c.y + arm / 4), Offset(c.x, c.y + arm), width)
            drawCircle(color, 3.dp.toPx(), c)
        }
    }
}
