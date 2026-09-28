package io.github.brunovinicioslg.ladeira.app.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.brunovinicioslg.ladeira.app.AppContainer
import io.github.brunovinicioslg.ladeira.app.R
import io.github.brunovinicioslg.ladeira.app.appContainer
import io.github.brunovinicioslg.ladeira.app.drive.NavigationService
import io.github.brunovinicioslg.ladeira.app.settings.AppSettings
import io.github.brunovinicioslg.ladeira.app.ui.map.DriveMap
import io.github.brunovinicioslg.ladeira.app.ui.map.MapPosition
import io.github.brunovinicioslg.ladeira.app.ui.theme.LadeiraTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Screen { DRIVE, MAPS, LICENSES, POINTS }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            LadeiraTheme {
                LadeiraRoot()
            }
        }
    }
}

@Composable
private fun LadeiraRoot() {
    val context = LocalContext.current
    val container = context.appContainer
    val settings by remember { container.settingsRepository.settings }.collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()
    var screen by rememberSaveable { mutableStateOf(Screen.DRIVE) }
    // "My points" opens from the driving screen and from Maps: back returns where it came from.
    var pointsFrom by rememberSaveable { mutableStateOf(Screen.DRIVE) }
    val openPoints: (Screen) -> Unit = { from ->
        pointsFrom = from
        screen = Screen.POINTS
    }
    BackHandler(enabled = screen != Screen.DRIVE) {
        screen = when (screen) {
            Screen.LICENSES -> Screen.MAPS
            Screen.POINTS -> pointsFrom
            else -> Screen.DRIVE
        }
    }
    val points by container.userPoints.points.collectAsStateWithLifecycle()

    val current = settings ?: return
    val update: ((AppSettings) -> AppSettings) -> Unit = { transform -> scope.launch { container.settingsRepository.update(transform) } }
    when (screen) {
        Screen.DRIVE -> DriveRoute(
            container, current, update,
            onOpenMaps = { screen = Screen.MAPS },
            pendingMarks = points.count { it.needsType },
            onOpenPoints = { openPoints(Screen.DRIVE) },
        )
        Screen.MAPS -> {
            val regions by container.regionStore.regions.collectAsStateWithLifecycle()
            val importState by container.regionImporter.state.collectAsStateWithLifecycle()
            val status by container.driveSession.status.collectAsStateWithLifecycle()
            val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
                container.regionImporter.import(context.contentResolver, uris)
            }
            MapsScreen(
                regions = regions,
                importState = importState,
                voiceEnabled = current.voiceEnabled,
                voiceAvailable = status.voiceAvailable,
                // Region files have no registered MIME type, so any file can be picked; the store checks it.
                onImport = { picker.launch(arrayOf("*/*")) },
                onDismissResults = container.regionImporter::clearResults,
                onDelete = { name -> container.applicationScope.launch(Dispatchers.IO) { container.regionStore.delete(name) } },
                onVoiceChange = { enabled -> update { it.copy(voiceEnabled = enabled) } },
                onOpenLicenses = { screen = Screen.LICENSES },
                onBack = { screen = Screen.DRIVE },
                pointsCount = points.size,
                onOpenPoints = { openPoints(Screen.MAPS) },
            )
        }
        Screen.LICENSES -> LicensesScreen(onBack = { screen = Screen.MAPS })
        Screen.POINTS -> PointsRoute(container, onBack = { screen = pointsFrom })
    }
}

@Composable
private fun PointsRoute(container: AppContainer, onBack: () -> Unit) {
    val context = LocalContext.current
    val points by container.userPoints.points.collectAsStateWithLifecycle()
    var message by remember { mutableStateOf<PointsMessage?>(null) }
    val store = container.userPoints
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/geo+json")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        container.applicationScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(store.export().toByteArray()) } != null
                } catch (e: IOException) {
                    false
                }
            }
            message = if (ok) PointsMessage.Exported else PointsMessage.ExportFailed
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        container.applicationScope.launch {
            message = withContext(Dispatchers.IO) {
                try {
                    // A list of points is small; a huge file is not one.
                    val text = context.contentResolver.openInputStream(uri)?.use { it.readAtMost(MAX_POINTS_FILE_BYTES) }?.decodeToString()
                    when (val added = text?.let(store::import)) {
                        null -> PointsMessage.ImportFailed
                        0 -> PointsMessage.ImportedNothing
                        else -> PointsMessage.Imported(added)
                    }
                } catch (e: IllegalArgumentException) {
                    PointsMessage.ImportFailed
                } catch (e: IOException) {
                    PointsMessage.ImportFailed
                }
            }
        }
    }
    PointsScreen(
        points = points,
        message = message,
        onSave = { point -> container.applicationScope.launch(Dispatchers.IO) { store.update(point) } },
        onDelete = { id -> container.applicationScope.launch(Dispatchers.IO) { store.delete(id) } },
        // GeoJSON has no MIME type every file manager knows: any file can be picked; the store checks it.
        onImport = { importer.launch(arrayOf("*/*")) },
        onExport = { exporter.launch("ladeira-pontos.geojson") },
        onMessageShown = { message = null },
        onBack = onBack,
    )
}

private const val MAX_POINTS_FILE_BYTES = 5_000_000

/** The whole stream, or null when it is longer than [limit] bytes. */
private fun InputStream.readAtMost(limit: Int): ByteArray? {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(8_192)
    while (true) {
        val n = read(buffer)
        if (n < 0) return out.toByteArray()
        if (out.size() + n > limit) return null
        out.write(buffer, 0, n)
    }
}

@Composable
private fun DriveRoute(
    container: AppContainer,
    settings: AppSettings,
    update: ((AppSettings) -> AppSettings) -> Unit,
    onOpenMaps: () -> Unit,
    pendingMarks: Int,
    onOpenPoints: () -> Unit,
) {
    val context = LocalContext.current
    val status by container.driveSession.status.collectAsStateWithLifecycle()
    val regions by container.regionStore.regions.collectAsStateWithLifecycle()
    var following by rememberSaveable { mutableStateOf(true) }
    var permissionProblem by rememberSaveable { mutableStateOf<PermissionProblem?>(null) }

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        permissionProblem = when {
            granted[Manifest.permission.ACCESS_FINE_LOCATION] == true -> null
            granted[Manifest.permission.ACCESS_COARSE_LOCATION] == true -> PermissionProblem.APPROXIMATE_ONLY
            else -> PermissionProblem.DENIED
        }
        if (permissionProblem == null) {
            following = true
            NavigationService.start(context)
        }
    }

    // The screen stays on while driving with the app in front, like any navigation app.
    val view = LocalView.current
    DisposableEffect(status.running) {
        view.keepScreenOn = status.running
        onDispose { view.keepScreenOn = false }
    }
    LaunchedEffect(status.running) { if (status.running) permissionProblem = null }

    val drive = status.drive
    val position = drive?.position
    val mapRegion = remember(regions, position?.let { it.lat.toInt() to it.lon.toInt() }) { container.regionStore.mapRegionFor(position) }
    DriveScreen(
        status = status,
        vehicle = settings.vehicle,
        hasRegions = regions.isNotEmpty(),
        following = following,
        permissionProblem = permissionProblem,
        onStart = {
            if (NavigationService.hasLocationPermission(context)) {
                following = true
                NavigationService.start(context)
            } else {
                permissions.launch(requiredPermissions())
            }
        },
        onStop = { NavigationService.stop(context) },
        onVehicleChange = { vehicle -> update { it.copy(vehicle = vehicle) } },
        onOpenMaps = onOpenMaps,
        onRecenter = { following = true },
        onMark = { NavigationService.markHere(context) },
        pendingMarks = pendingMarks,
        onOpenPoints = onOpenPoints,
        onOpenGpsSettings = { context.openSettings(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) },
        onOpenAppSettings = {
            context.openSettings(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
        },
        map = { modifier, insets ->
            DriveMap(
                region = mapRegion,
                vehicle = if (status.running && position != null) MapPosition(position, status.bearing) else null,
                ahead = if (status.running) drive?.ahead.orEmpty() else emptyList(),
                following = following,
                onUserMovedMap = { following = false },
                insets = insets,
                modifier = modifier,
            )
        },
    )
}

private fun requiredPermissions(): Array<String> = buildList {
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    add(Manifest.permission.ACCESS_COARSE_LOCATION)
    // The navigation notification, with its stop button; the app works without it.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
}.toTypedArray()

private fun Context.openSettings(intent: Intent) {
    try {
        startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        // Some trimmed-down systems lack the screen; nothing better to open.
    }
}
