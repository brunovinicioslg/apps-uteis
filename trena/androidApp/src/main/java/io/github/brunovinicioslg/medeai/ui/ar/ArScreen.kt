package io.github.brunovinicioslg.medeai.ui.ar

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.opengl.GLSurfaceView
import android.util.Log
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableException
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException
import io.github.brunovinicioslg.medeai.R
import io.github.brunovinicioslg.medeai.measure.MeasureMode
import io.github.brunovinicioslg.medeai.ui.CameraPermissionGate
import io.github.brunovinicioslg.medeai.ui.rememberFormatter
import io.github.brunovinicioslg.medeai.units.MeasureFormatter
import io.github.brunovinicioslg.medeai.units.UnitSystem
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

/** ARCore's state on this phone, as far as the screen is concerned. */
private enum class ArSupport { CHECKING, INSTALLING, READY, UNSUPPORTED, DECLINED }

/** Measuring with ARCore: points on real surfaces, marked with the crosshair. */
// Phones honor the portrait lock, so the AR session and its points survive the phone tilting while
// aiming. Large screens on Android 16+ ignore it: turning them restarts the session, nothing worse.
@SuppressLint("SourceLockedOrientationActivity")
@Composable
fun ArScreen(unitSystem: UnitSystem, onBack: () -> Unit) {
    // Measurements need a steady picture: portrait only, and the screen stays on.
    val activity = LocalActivity.current
    val view = LocalView.current
    DisposableEffect(Unit) {
        val previous = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        view.keepScreenOn = true
        onDispose {
            view.keepScreenOn = false
            activity?.requestedOrientation = previous ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
    Box(Modifier.fillMaxSize()) {
        CameraPermissionGate(rationale = stringResource(R.string.ar_camera_rationale)) {
            val support = rememberArSupport(activity)
            when (support) {
                ArSupport.READY -> ArMeasureView(unitSystem)
                ArSupport.CHECKING, ArSupport.INSTALLING -> Centered { CircularProgressIndicator() }
                ArSupport.UNSUPPORTED -> Centered { Message(stringResource(R.string.ar_unsupported)) }
                ArSupport.DECLINED -> Centered { Message(stringResource(R.string.ar_install_declined)) }
            }
        }
        IconButton(onClick = onBack, modifier = Modifier.statusBarsPadding().padding(4.dp)) {
            Surface(shape = RoundedCornerShape(50), color = Color.Black.copy(alpha = 0.45f)) {
                Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.back), Modifier.padding(8.dp), tint = Color.White)
            }
        }
    }
}

/**
 * Asks whether ARCore works here and, when it only needs installing or updating, asks for that
 * (Play Store screen); checked again on every return to the app.
 */
@Composable
private fun rememberArSupport(activity: Activity?): ArSupport {
    var support by remember { mutableStateOf(ArSupport.CHECKING) }
    var installRequested by remember { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(activity) {
        activity ?: return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val apk = ArCoreApk.getInstance()
            var availability = apk.checkAvailability(activity)
            while (availability.isTransient) {
                delay(200)
                availability = apk.checkAvailability(activity)
            }
            // "Supported" includes "not installed" and "too old": requestInstall handles those.
            support = if (!availability.isSupported) {
                ArSupport.UNSUPPORTED
            } else {
                try {
                    when (apk.requestInstall(activity, !installRequested)) {
                        ArCoreApk.InstallStatus.INSTALLED -> ArSupport.READY
                        ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                            installRequested = true
                            ArSupport.INSTALLING
                        }
                    }
                } catch (_: UnavailableUserDeclinedInstallationException) {
                    ArSupport.DECLINED
                } catch (_: UnavailableDeviceNotCompatibleException) {
                    ArSupport.UNSUPPORTED
                } catch (e: UnavailableException) {
                    Log.w(TAG, "ARCore unavailable", e)
                    ArSupport.UNSUPPORTED
                }
            }
        }
    }
    return support
}

private fun createSession(context: Context): Session {
    val session = Session(context)
    val config = Config(session).apply {
        planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
        updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
        focusMode = Config.FocusMode.AUTO
        lightEstimationMode = Config.LightEstimationMode.DISABLED
        // Depth (on phones that have it) marks points on any surface, not only on flat planes.
        depthMode = if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) Config.DepthMode.AUTOMATIC else Config.DepthMode.DISABLED
    }
    session.configure(config)
    return session
}

@Composable
private fun ArMeasureView(unitSystem: UnitSystem) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val snapRadius = with(LocalDensity.current) { SNAP_RADIUS.toPx() }
    val overlayFlow = remember { MutableStateFlow(ArOverlay()) }
    val session = remember {
        try {
            createSession(context)
        } catch (e: UnavailableException) {
            Log.w(TAG, "Cannot start ARCore", e)
            null
        } catch (e: RuntimeException) {
            // FatalException and the like: ARCore is installed but cannot run on this phone.
            Log.e(TAG, "ARCore failed to start", e)
            null
        }
    }
    if (session == null) {
        Centered { Message(stringResource(R.string.ar_unsupported)) }
        return
    }
    val renderer = remember(session) {
        ArMeasureRenderer(
            session = session,
            displayRotation = { ContextCompat.getDisplayOrDefault(context).rotation },
            snapRadius = snapRadius,
            onOverlay = { overlayFlow.value = it },
        )
    }
    val surface = remember(renderer) {
        GLSurfaceView(context).apply {
            preserveEGLContextOnPause = true
            setEGLContextClientVersion(2)
            setEGLConfigChooser(8, 8, 8, 8, 16, 0)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
    }
    DisposableEffect(lifecycleOwner, session) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    try {
                        session.resume()
                        surface.onResume()
                    } catch (e: CameraNotAvailableException) {
                        Log.w(TAG, "Camera in use elsewhere", e)
                        overlayFlow.value = ArOverlay(ArStatus.CAMERA_UNAVAILABLE)
                    } catch (e: RuntimeException) {
                        Log.e(TAG, "ARCore failed to resume", e)
                        overlayFlow.value = ArOverlay(ArStatus.FAILED)
                    }
                }
                // The GL thread must stop using the session before it pauses.
                Lifecycle.Event.ON_PAUSE -> {
                    surface.onPause()
                    session.pause()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            surface.onPause()
            session.pause()
            session.close()
        }
    }

    val overlay by overlayFlow.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { surface }, modifier = Modifier.fillMaxSize())
        MeasureOverlay(overlay, unitSystem, Modifier.fillMaxSize())
        MeasurePanel(overlay, unitSystem, onCommand = renderer::send, modifier = Modifier.align(Alignment.BottomCenter))
    }
}

/** Points, stretches with their lengths, and the crosshair, drawn over the camera. */
@Composable
fun MeasureOverlay(overlay: ArOverlay, unitSystem: UnitSystem, modifier: Modifier = Modifier) {
    val formatter = rememberFormatter()
    val measurer = rememberTextMeasurer()
    val accent = MaterialTheme.colorScheme.primary
    val labelStyle = TextStyle(color = Color.Black, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    Canvas(modifier) {
        val center = Offset(size.width / 2, size.height / 2)
        fun offset(p: ScreenPoint) = Offset(p.x, p.y)
        for (stretch in overlay.result.stretches) {
            val from = overlay.points.getOrNull(stretch.from) ?: continue
            val to = if (stretch.live) center else offset(overlay.points.getOrNull(stretch.to ?: -1) ?: continue)
            val a = offset(from)
            drawLine(Color.White, a, to, strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
            val text = measurer.measure(formatter.length(stretch.length, unitSystem), labelStyle)
            val mid = Offset((a.x + to.x) / 2, (a.y + to.y) / 2)
            val box = Size(text.size.width + 12.dp.toPx(), text.size.height + 6.dp.toPx())
            val topLeft = Offset(mid.x - box.width / 2, mid.y - box.height / 2)
            drawRoundRect(Color.White, topLeft, box, CornerRadius(6.dp.toPx()))
            drawText(text, topLeft = Offset(topLeft.x + 6.dp.toPx(), topLeft.y + 3.dp.toPx()))
        }
        overlay.points.forEach { p ->
            if (p == null) return@forEach
            drawCircle(Color.White, 7.dp.toPx(), offset(p))
            drawCircle(accent, 5.dp.toPx(), offset(p))
        }
        crosshair(center, overlay, accent)
    }
}

private fun DrawScope.crosshair(center: Offset, overlay: ArOverlay, accent: Color) {
    val onSurface = overlay.status == ArStatus.READY || overlay.snapToFirst
    val color = when {
        overlay.snapToFirst -> Color(0xFF4CAF50)
        onSurface -> Color.White
        else -> Color.White.copy(alpha = 0.5f)
    }
    drawCircle(color, 20.dp.toPx(), center, style = Stroke(width = 2.5.dp.toPx()))
    drawCircle(if (onSurface) accent else color, 3.dp.toPx(), center)
}

@Composable
internal fun MeasurePanel(overlay: ArOverlay, unitSystem: UnitSystem, onCommand: (ArCommand) -> Unit, modifier: Modifier = Modifier) {
    val formatter = rememberFormatter()
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
    ) {
        Column(Modifier.navigationBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (mode in MeasureMode.entries) {
                    FilterChip(
                        selected = overlay.mode == mode,
                        onClick = { onCommand(ArCommand.Mode(mode)) },
                        label = { Text(stringResource(modeName(mode))) },
                    )
                }
            }
            Text(
                resultText(overlay, unitSystem, formatter),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            statusText(overlay)?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onCommand(ArCommand.Undo) }, enabled = overlay.canUndo || overlay.closed) {
                    Text(stringResource(R.string.ar_undo))
                }
                OutlinedButton(onClick = { onCommand(ArCommand.Clear) }, enabled = overlay.canUndo) {
                    Text(stringResource(R.string.ar_clear))
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                    val canAdd = overlay.status == ArStatus.READY || overlay.snapToFirst
                    val label = stringResource(if (overlay.snapToFirst) R.string.ar_close else R.string.ar_add)
                    ExtendedFloatingActionButton(
                        onClick = { if (canAdd) onCommand(ArCommand.Add) },
                        containerColor = if (canAdd) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.semantics { contentDescription = label },
                    ) { Text(label) }
                }
            }
            Text(stringResource(R.string.ar_disclaimer), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun resultText(overlay: ArOverlay, unitSystem: UnitSystem, formatter: MeasureFormatter): String {
    val result = overlay.result
    return when (overlay.mode) {
        MeasureMode.DISTANCE -> result.total?.let { formatter.length(it, unitSystem) } ?: stringResource(R.string.ar_mark_first)
        MeasureMode.PATH -> result.total?.let {
            pluralStringResource(R.plurals.ar_path_total, result.stretches.size, formatter.length(it, unitSystem), result.stretches.size)
        } ?: stringResource(R.string.ar_mark_first)
        MeasureMode.AREA -> result.polygon?.let {
            stringResource(R.string.ar_area_result, formatter.area(it.area, unitSystem), formatter.length(it.perimeter, unitSystem))
        } ?: stringResource(R.string.ar_area_need_points)
    }
}

@Composable
private fun statusText(overlay: ArOverlay): String? {
    val id = when (overlay.status) {
        ArStatus.STARTING, ArStatus.MOVE_TO_START -> R.string.ar_status_move
        ArStatus.LOW_LIGHT -> R.string.ar_status_light
        ArStatus.TOO_FAST -> R.string.ar_status_slow
        ArStatus.FEW_FEATURES -> R.string.ar_status_features
        ArStatus.CAMERA_UNAVAILABLE -> R.string.ar_status_camera
        ArStatus.FAILED -> R.string.ar_status_failed
        ArStatus.LOST -> R.string.ar_status_lost
        ArStatus.FIND_SURFACE -> R.string.ar_status_find
        ArStatus.AIM_AT_SURFACE -> R.string.ar_status_aim
        ArStatus.READY -> when {
            overlay.result.polygon?.selfIntersecting == true -> R.string.ar_outline_crossed
            (overlay.crosshairDistance ?: 0.0) > FAR_M -> R.string.ar_status_far
            overlay.snapToFirst -> R.string.ar_status_close
            else -> return null
        }
    }
    return stringResource(id)
}

private fun modeName(mode: MeasureMode) = when (mode) {
    MeasureMode.DISTANCE -> R.string.ar_mode_distance
    MeasureMode.PATH -> R.string.ar_mode_path
    MeasureMode.AREA -> R.string.ar_mode_area
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun Message(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
}

private const val TAG = "ArScreen"
private val SNAP_RADIUS = 28.dp

/** Beyond this the crosshair's point is too far for a trustworthy measurement. */
private const val FAR_M = 5.0
