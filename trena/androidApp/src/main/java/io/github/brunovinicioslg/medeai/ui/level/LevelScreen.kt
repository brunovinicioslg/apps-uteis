package io.github.brunovinicioslg.medeai.ui.level

import android.view.Surface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.brunovinicioslg.medeai.R
import io.github.brunovinicioslg.medeai.level.Level
import io.github.brunovinicioslg.medeai.sensors.gravityFlow
import io.github.brunovinicioslg.medeai.ui.ToolTopBar
import io.github.brunovinicioslg.medeai.ui.rememberFormatter
import io.github.brunovinicioslg.medeai.ui.theme.LevelGreen
import io.github.brunovinicioslg.medeai.ui.theme.MeasureYellow
import kotlinx.coroutines.flow.map

/** Below this error (degrees) the surface counts as level. */
const val LEVEL_TOLERANCE_DEGREES = 0.3

/** The bubble reaches the edge of the vial at this tilt (degrees). */
private const val FULL_SCALE_DEGREES = 10.0

sealed interface LevelState {
    data object Waiting : LevelState
    data object NoSensor : LevelState
    data class Measuring(val reading: Level.Reading) : LevelState
}

@Composable
fun LevelScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val rotation = LocalView.current.display?.rotation ?: Surface.ROTATION_0
    val state by remember(rotation) {
        gravityFlow(context).map { g ->
            if (g == null) {
                LevelState.NoSensor
            } else {
                val (x, y) = Level.toScreenAxes(g.x, g.y, rotation)
                Level.read(x, y, g.z)?.let(LevelState::Measuring) ?: LevelState.Waiting
            }
        }
    }.collectAsStateWithLifecycle(initialValue = LevelState.Waiting)

    Scaffold(topBar = { ToolTopBar(stringResource(R.string.tool_level_title), onBack) }) { padding ->
        LevelContent(state, Modifier.padding(padding))
    }
}

@Composable
fun LevelContent(state: LevelState, modifier: Modifier = Modifier) {
    val format = rememberFormatter()
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
    ) {
        when (state) {
            LevelState.Waiting -> Text(stringResource(R.string.level_waiting))
            LevelState.NoSensor -> Text(stringResource(R.string.level_no_sensor), textAlign = TextAlign.Center)
            is LevelState.Measuring -> {
                val reading = state.reading
                val level = reading.error < LEVEL_TOLERANCE_DEGREES
                val color = if (level) LevelGreen else MaterialTheme.colorScheme.onSurface
                if (reading.orientation == Level.Orientation.FLAT) {
                    SurfaceVial(reading.tiltX, reading.tiltY, level)
                } else {
                    EdgeVial(reading.edgeError, level)
                }
                Text(
                    text = if (level) stringResource(R.string.level_ok) else format.degrees(reading.error),
                    style = MaterialTheme.typography.displayMedium,
                    color = color,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                Text(
                    text = if (reading.orientation == Level.Orientation.FLAT) {
                        stringResource(R.string.level_flat_axes, format.degrees(reading.tiltX), format.degrees(reading.tiltY))
                    } else {
                        stringResource(R.string.level_upright_hint)
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
            }
        }
        Text(
            stringResource(R.string.level_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** Round vial seen from above: the bubble drifts towards the raised side. */
@Composable
private fun SurfaceVial(tiltX: Double, tiltY: Double, level: Boolean) {
    val ring = MaterialTheme.colorScheme.outline
    Canvas(Modifier.size(260.dp)) {
        val radius = size.minDimension / 2
        val bubbleRadius = radius * 0.18f
        drawCircle(ring, radius - 2.dp.toPx(), style = Stroke(2.dp.toPx()))
        drawCircle(ring, bubbleRadius * 1.25f, style = Stroke(2.dp.toPx()))
        val travel = radius - bubbleRadius - 4.dp.toPx()
        var dx = (tiltX / FULL_SCALE_DEGREES).toFloat() * travel
        var dy = (-tiltY / FULL_SCALE_DEGREES).toFloat() * travel // screen y grows downwards
        val distance = kotlin.math.sqrt(dx * dx + dy * dy)
        if (distance > travel) {
            dx *= travel / distance
            dy *= travel / distance
        }
        drawCircle(if (level) LevelGreen else MeasureYellow, bubbleRadius, center + Offset(dx, dy))
    }
}

/** Straight vial along the phone's edge. */
@Composable
private fun EdgeVial(edgeError: Double, level: Boolean) {
    val ring = MaterialTheme.colorScheme.outline
    Canvas(Modifier.fillMaxWidth().height(64.dp)) {
        val corner = CornerRadius(size.height / 2)
        drawRoundRect(ring, cornerRadius = corner, style = Stroke(2.dp.toPx()))
        val bubbleWidth = size.width * 0.18f
        val travel = (size.width - bubbleWidth) / 2 - 4.dp.toPx()
        val dx = (edgeError / FULL_SCALE_DEGREES).toFloat().coerceIn(-1f, 1f) * travel
        val left = size.width / 2 - bubbleWidth / 2 + dx
        drawRoundRect(
            color = if (level) LevelGreen else MeasureYellow,
            topLeft = Offset(left, 8.dp.toPx()),
            size = Size(bubbleWidth, size.height - 16.dp.toPx()),
            cornerRadius = corner,
        )
        drawLine(Color.Gray, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), 2.dp.toPx())
    }
}
