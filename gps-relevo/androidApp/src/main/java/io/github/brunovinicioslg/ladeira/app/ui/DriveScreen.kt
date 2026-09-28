package io.github.brunovinicioslg.ladeira.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.brunovinicioslg.ladeira.app.R
import io.github.brunovinicioslg.ladeira.app.drive.AlertPhrases
import io.github.brunovinicioslg.ladeira.app.drive.NavigationStatus
import io.github.brunovinicioslg.ladeira.app.ui.theme.LimitRed
import io.github.brunovinicioslg.ladeira.app.ui.theme.gradeColor
import io.github.brunovinicioslg.ladeira.drive.DriveState
import io.github.brunovinicioslg.ladeira.profile.Slope
import io.github.brunovinicioslg.ladeira.profile.SlopeKind
import io.github.brunovinicioslg.ladeira.profile.VehicleProfile
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

enum class PermissionProblem { DENIED, APPROXIMATE_ONLY }

/** Map area hidden by the overlays, in pixels: the camera centers the vehicle in what is left. */
data class MapInsets(val top: Int, val bottom: Int) {
    companion object {
        /**
         * Puts the vehicle three quarters of the way down the visible map, so most of the view is
         * the road ahead. MapLibre centers the camera target in the padded area, so the top padding
         * is pushed down by half the visible height.
         */
        fun around(screenHeight: Int, topBarBottom: Int, panelHeight: Int): MapInsets {
            val visible = screenHeight - topBarBottom - panelHeight
            if (screenHeight <= 0 || visible <= 0) return MapInsets(0, 0)
            return MapInsets(top = topBarBottom + visible / 2, bottom = panelHeight)
        }
    }
}

/**
 * The driving screen: the map fills it, the road name sits on top and the panel at the bottom.
 * [map] draws the map; tests pass a placeholder because MapLibre needs a real device.
 */
@Composable
fun DriveScreen(
    status: NavigationStatus,
    vehicle: VehicleProfile,
    hasRegions: Boolean,
    following: Boolean,
    permissionProblem: PermissionProblem?,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onVehicleChange: (VehicleProfile) -> Unit,
    onOpenMaps: () -> Unit,
    onRecenter: () -> Unit,
    onOpenGpsSettings: () -> Unit,
    onOpenAppSettings: () -> Unit,
    map: @Composable (Modifier, MapInsets) -> Unit,
    onMark: () -> Unit = {},
    /** Points marked while driving whose type is still to be chosen. */
    pendingMarks: Int = 0,
    onOpenPoints: () -> Unit = {},
) {
    val context = LocalContext.current
    val phrases = remember(context) { AlertPhrases(context) }
    var screenHeight by remember { mutableIntStateOf(0) }
    var topBarBottom by remember { mutableIntStateOf(0) }
    var panelHeight by remember { mutableIntStateOf(0) }
    Box(Modifier.fillMaxSize().onSizeChanged { screenHeight = it.height }) {
        map(Modifier.fillMaxSize(), MapInsets.around(screenHeight, topBarBottom, panelHeight))
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)
                .onGloballyPositioned { topBarBottom = it.boundsInParent().bottom.toInt() },
            verticalAlignment = Alignment.Top,
        ) {
            val drive = status.drive
            Box(Modifier.weight(1f)) {
                if (status.running && drive?.status == DriveState.Status.ON_ROAD) RoadCard(drive, phrases)
            }
            Spacer(Modifier.width(8.dp))
            FilledTonalIconButton(onClick = onOpenMaps) {
                Icon(painterResource(R.drawable.ic_layers), contentDescription = stringResource(R.string.action_maps))
            }
        }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().onSizeChanged { panelHeight = it.height }) {
            if (status.running && !following) {
                SmallFloatingActionButton(onClick = onRecenter, modifier = Modifier.align(Alignment.End).padding(end = 12.dp, bottom = 8.dp)) {
                    Icon(painterResource(R.drawable.ic_my_location), contentDescription = stringResource(R.string.action_recenter))
                }
            }
            DrivePanel(
                status = status,
                vehicle = vehicle,
                hasRegions = hasRegions,
                permissionProblem = permissionProblem,
                onStart = onStart,
                onStop = onStop,
                onVehicleChange = onVehicleChange,
                onOpenMaps = onOpenMaps,
                onOpenGpsSettings = onOpenGpsSettings,
                onOpenAppSettings = onOpenAppSettings,
                onMark = onMark,
                pendingMarks = pendingMarks,
                onOpenPoints = onOpenPoints,
            )
        }
    }
}

@Composable
private fun RoadCard(drive: DriveState, phrases: AlertPhrases) {
    val road = drive.road
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f))) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f, fill = false)) {
                val ref = road?.ref?.let(phrases::roadNumber)
                val name = road?.name
                Text(
                    ref ?: name ?: stringResource(R.string.unnamed_road),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (ref != null && name != null) {
                    Text(name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            road?.maxSpeedKmh?.let {
                Spacer(Modifier.width(12.dp))
                SpeedLimitSign(it)
            }
        }
    }
}

@Composable
private fun SpeedLimitSign(kmh: Int) {
    val description = stringResource(R.string.speed_limit_description, kmh)
    Box(
        Modifier.size(44.dp).background(Color.White, CircleShape).border(BorderStroke(5.dp, LimitRed), CircleShape)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(kmh.toString(), color = Color.Black, fontWeight = FontWeight.Bold, fontSize = if (kmh >= 100) 14.sp else 17.sp)
    }
}

@Composable
fun DrivePanel(
    status: NavigationStatus,
    vehicle: VehicleProfile,
    hasRegions: Boolean,
    permissionProblem: PermissionProblem?,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onVehicleChange: (VehicleProfile) -> Unit,
    onOpenMaps: () -> Unit,
    onOpenGpsSettings: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onMark: () -> Unit = {},
    pendingMarks: Int = 0,
    onOpenPoints: () -> Unit = {},
) {
    val context = LocalContext.current
    val phrases = remember(context) { AlertPhrases(context) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
    ) {
        Column(Modifier.navigationBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val drive = status.drive
            when {
                !hasRegions && !status.running -> {
                    Text(stringResource(R.string.no_regions_title), style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(R.string.no_regions_text), style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = onOpenMaps, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_maps)) }
                }
                !status.running -> {
                    Text(stringResource(R.string.status_ready), style = MaterialTheme.typography.titleLarge)
                    PermissionMessage(permissionProblem, onOpenAppSettings)
                    if (pendingMarks > 0) {
                        OutlinedButton(onClick = onOpenPoints, modifier = Modifier.fillMaxWidth()) {
                            Text(pluralStringResource(R.plurals.points_pending, pendingMarks, pendingMarks))
                        }
                    }
                    VehicleChips(vehicle, onVehicleChange)
                    Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_start)) }
                }
                else -> {
                    SpeedAndGrade(drive, phrases)
                    DriveDetails(status, phrases, onOpenGpsSettings)
                    VehicleChips(vehicle, onVehicleChange)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        // Big, to hit without looking: the type is chosen later, stopped.
                        FilledTonalButton(onClick = onMark, modifier = Modifier.weight(1f).height(56.dp)) {
                            Icon(painterResource(R.drawable.ic_flag), contentDescription = null)
                            Text(stringResource(R.string.action_mark), Modifier.padding(start = 8.dp))
                        }
                        OutlinedButton(
                            onClick = onStop,
                            modifier = Modifier.weight(1f).height(56.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        ) { Text(stringResource(R.string.action_stop)) }
                    }
                }
            }
            Text(
                stringResource(R.string.map_attribution),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PermissionMessage(problem: PermissionProblem?, onOpenAppSettings: () -> Unit) {
    problem ?: return
    Text(
        stringResource(if (problem == PermissionProblem.APPROXIMATE_ONLY) R.string.permission_precise_needed else R.string.permission_location_needed),
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodyMedium,
    )
    OutlinedButton(onClick = onOpenAppSettings) { Text(stringResource(R.string.action_open_settings)) }
}

@Composable
private fun SpeedAndGrade(drive: DriveState?, phrases: AlertPhrases) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            drive?.speedKmh?.let { max(0, it.roundToInt()).toString() } ?: "--",
            style = MaterialTheme.typography.displayMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(stringResource(R.string.speed_unit), modifier = Modifier.padding(start = 4.dp, bottom = 8.dp), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.weight(1f))
        val grade = drive?.gradePercent
        if (grade != null) {
            Column(horizontalAlignment = Alignment.End) {
                val text = when {
                    abs(grade) < FLAT_PERCENT -> stringResource(R.string.grade_flat)
                    grade > 0 -> stringResource(R.string.grade_up, phrases.percent(grade))
                    else -> stringResource(R.string.grade_down, phrases.percent(grade))
                }
                Text(text, style = MaterialTheme.typography.titleLarge, color = gradeColor(grade), fontWeight = FontWeight.Bold)
                drive.elevation?.let {
                    Text(stringResource(R.string.elevation, it.roundToInt().toString()), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun DriveDetails(status: NavigationStatus, phrases: AlertPhrases, onOpenGpsSettings: () -> Unit) {
    val drive = status.drive
    if (!status.gpsEnabled) {
        Text(stringResource(R.string.status_gps_off), color = MaterialTheme.colorScheme.error)
        OutlinedButton(onClick = onOpenGpsSettings) { Text(stringResource(R.string.action_gps_settings)) }
        return
    }
    val message = when (drive?.status) {
        null, DriveState.Status.WAITING_FOR_GPS -> R.string.status_waiting_gps
        DriveState.Status.NO_MAP_DATA -> R.string.status_no_map_data
        DriveState.Status.OFF_ROAD -> R.string.status_off_road
        DriveState.Status.ON_ROAD -> null
    }
    if (message != null) {
        Text(stringResource(message), style = MaterialTheme.typography.bodyLarge)
        return
    }
    drive ?: return
    val current = drive.current
    val next = drive.next
    when {
        current != null -> SlopeLine(current.first, current.second, phrases, now = true)
        next != null -> SlopeLine(next.first, next.second, phrases, now = false)
    }
    drive.nextPoi?.let { (poi, distance) ->
        val what = phrases.poiName(poi.type)
        val limit = poi.speedLimitKmh?.let { " · " + stringResource(R.string.poi_limit, it) }.orEmpty()
        Text(stringResource(R.string.next_item, what, phrases.shortDistance(distance)) + limit, style = MaterialTheme.typography.bodyLarge)
    }
    if (status.voiceAvailable == false) {
        Text(stringResource(R.string.settings_voice_unavailable), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun SlopeLine(slope: Slope, kind: SlopeKind, phrases: AlertPhrases, now: Boolean) {
    val name = phrases.kindName(kind)
    val head = if (now) stringResource(R.string.now_item, name) else stringResource(R.string.next_item, name, phrases.shortDistance(slope.start))
    val remaining = slope.end - max(0.0, slope.start)
    val detail = stringResource(R.string.slope_detail, phrases.shortDistance(remaining), phrases.percent(slope.averageGradePercent))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(12.dp).background(gradeColor(slope.averageGradePercent), CircleShape))
        Spacer(Modifier.width(8.dp))
        Text("$head · $detail", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun VehicleChips(selected: VehicleProfile, onChange: (VehicleProfile) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (profile in VehicleProfile.entries) {
            FilterChip(selected = profile == selected, onClick = { onChange(profile) }, label = { Text(vehicleName(profile)) })
        }
    }
}

@Composable
fun vehicleName(profile: VehicleProfile): String = stringResource(
    when (profile) {
        VehicleProfile.CAR -> R.string.vehicle_car
        VehicleProfile.TRUCK -> R.string.vehicle_truck
        VehicleProfile.MOTORCYCLE -> R.string.vehicle_motorcycle
        VehicleProfile.BICYCLE -> R.string.vehicle_bicycle
        VehicleProfile.WALKING -> R.string.vehicle_walking
    },
)

/** Below this the road reads as flat: terrain data is not precise enough to say more. */
private const val FLAT_PERCENT = 2.0
