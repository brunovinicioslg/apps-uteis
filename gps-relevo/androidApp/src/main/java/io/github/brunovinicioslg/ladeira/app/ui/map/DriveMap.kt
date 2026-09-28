package io.github.brunovinicioslg.ladeira.app.ui.map

import android.content.Context
import android.view.Gravity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.brunovinicioslg.ladeira.app.region.Region
import io.github.brunovinicioslg.ladeira.app.ui.MapInsets
import io.github.brunovinicioslg.ladeira.app.ui.theme.GradeFlat
import io.github.brunovinicioslg.ladeira.app.ui.theme.GradeMild
import io.github.brunovinicioslg.ladeira.app.ui.theme.GradeSteep
import io.github.brunovinicioslg.ladeira.app.ui.theme.GradeVerySteep
import io.github.brunovinicioslg.ladeira.drive.AheadPoint
import io.github.brunovinicioslg.ladeira.geo.LatLon
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/** Where the map shows the vehicle, and which way it is going. */
data class MapPosition(val position: LatLon, val bearing: Double?)

/**
 * The offline map (MapLibre over the region's PMTiles file) with the road ahead colored by grade
 * and the vehicle position. While [following], the camera tracks the vehicle heading-up; any pan
 * or zoom by hand calls [onUserMovedMap].
 */
@Composable
fun DriveMap(
    region: Region?,
    vehicle: MapPosition?,
    ahead: List<AheadPoint>,
    following: Boolean,
    onUserMovedMap: () -> Unit,
    insets: MapInsets,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val mapView = rememberMapViewWithLifecycle()
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    val topInsetPx = WindowInsets.statusBars.getTop(LocalDensity.current)
    val density = LocalDensity.current.density

    DisposableEffect(mapView) {
        mapView.getMapAsync { m ->
            m.uiSettings.apply {
                isLogoEnabled = false
                // The attribution is always visible in the panel below the map.
                isAttributionEnabled = false
                compassGravity = Gravity.TOP or Gravity.END
                setCompassMargins(0, topInsetPx + (COMPASS_TOP_DP * density).roundToInt(), (12 * density).roundToInt(), 0)
            }
            map = m
        }
        onDispose { }
    }

    val gesture by rememberUpdatedState(onUserMovedMap)
    DisposableEffect(map) {
        val m = map
        val listener = MapLibreMap.OnCameraMoveStartedListener { reason ->
            if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) gesture()
        }
        m?.addOnCameraMoveStartedListener(listener)
        onDispose { m?.removeOnCameraMoveStartedListener(listener) }
    }

    val mapFile = region?.mapFile
    LaunchedEffect(map, mapFile, dark) {
        val m = map ?: return@LaunchedEffect
        style = null
        val json = if (mapFile == null) EMPTY_STYLE else withContext(Dispatchers.IO) { styleJson(context, mapFile, dark) }
        m.setStyle(Style.Builder().fromJson(json)) { loaded ->
            addDriveLayers(loaded)
            style = loaded
        }
    }

    // Show the region until there is a position to follow.
    LaunchedEffect(map, region?.name) {
        val m = map ?: return@LaunchedEffect
        val info = region?.map ?: return@LaunchedEffect
        if (vehicle == null) {
            m.cameraPosition = CameraPosition.Builder()
                .target(LatLng(info.center.lat, info.center.lon))
                .zoom(max(info.centerZoom, REGION_MIN_ZOOM).toDouble().coerceAtMost(info.maxZoom.toDouble()))
                .build()
        }
    }

    LaunchedEffect(style, ahead) {
        style?.getSourceAs<GeoJsonSource>(AHEAD_SOURCE)?.setGeoJson(aheadFeatures(ahead))
    }
    LaunchedEffect(style, vehicle?.position) {
        val position = vehicle?.position
        val features = if (position == null) emptyList() else listOf(Feature.fromGeometry(Point.fromLngLat(position.lon, position.lat)))
        style?.getSourceAs<GeoJsonSource>(VEHICLE_SOURCE)?.setGeoJson(FeatureCollection.fromFeatures(features))
    }
    LaunchedEffect(map, following, vehicle, insets) {
        val m = map ?: return@LaunchedEffect
        if (!following || vehicle == null) return@LaunchedEffect
        val camera = CameraPosition.Builder()
            .target(LatLng(vehicle.position.lat, vehicle.position.lon))
            .zoom(FOLLOW_ZOOM)
            .tilt(FOLLOW_TILT)
            .bearing(vehicle.bearing ?: m.cameraPosition.bearing)
            .padding(0.0, insets.top.toDouble(), 0.0, insets.bottom.toDouble())
            .build()
        // As long as the GPS interval, so the camera glides from fix to fix.
        m.easeCamera(CameraUpdateFactory.newCameraPosition(camera), FOLLOW_ANIMATION_MS, false)
    }

    AndroidView(factory = { mapView }, modifier = modifier)
}

@Composable
private fun rememberMapViewWithLifecycle(): MapView {
    val context = LocalContext.current
    val mapView = remember {
        MapLibre.getInstance(context)
        MapView(context).apply { onCreate(null) }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, mapView) {
        var state = Lifecycle.State.CREATED
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart().also { state = Lifecycle.State.STARTED }
                Lifecycle.Event.ON_RESUME -> mapView.onResume().also { state = Lifecycle.State.RESUMED }
                Lifecycle.Event.ON_PAUSE -> mapView.onPause().also { state = Lifecycle.State.STARTED }
                Lifecycle.Event.ON_STOP -> mapView.onStop().also { state = Lifecycle.State.CREATED }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            // Leaving the screen while the activity is still running: wind the map down in order.
            if (state == Lifecycle.State.RESUMED) mapView.onPause()
            if (state.isAtLeast(Lifecycle.State.STARTED)) mapView.onStop()
            mapView.onDestroy()
        }
    }
    return mapView
}

private fun styleJson(context: Context, mapFile: File, dark: Boolean): String {
    val template = context.assets.open(if (dark) "styles/dark.json" else "styles/light.json").use { it.readBytes().decodeToString() }
    return template.replace(PMTILES_PLACEHOLDER, "pmtiles://file://${mapFile.absolutePath}")
}

private fun addDriveLayers(style: Style) {
    style.addSource(GeoJsonSource(AHEAD_SOURCE, FeatureCollection.fromFeatures(emptyList())))
    style.addSource(GeoJsonSource(VEHICLE_SOURCE, FeatureCollection.fromFeatures(emptyList())))
    val steep = Expression.get(STEEP)
    val ahead = LineLayer(AHEAD_LAYER, AHEAD_SOURCE).withProperties(
        PropertyFactory.lineColor(
            Expression.step(
                steep, Expression.color(GradeFlat.toArgb()),
                Expression.stop(3, Expression.color(GradeMild.toArgb())),
                Expression.stop(6, Expression.color(GradeSteep.toArgb())),
                Expression.stop(9, Expression.color(GradeVerySteep.toArgb())),
            ),
        ),
        PropertyFactory.lineWidth(
            Expression.interpolate(Expression.exponential(1.5f), Expression.zoom(), Expression.stop(10, 3f), Expression.stop(17, 12f)),
        ),
        PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        PropertyFactory.lineOpacity(0.85f),
    )
    // Under the labels, so street names stay readable over the colored line.
    val firstLabel = style.layers.firstOrNull { it is SymbolLayer }?.id
    if (firstLabel != null) style.addLayerBelow(ahead, firstLabel) else style.addLayer(ahead)
    style.addLayer(
        CircleLayer(VEHICLE_HALO_LAYER, VEHICLE_SOURCE).withProperties(
            PropertyFactory.circleRadius(16f),
            PropertyFactory.circleColor(VEHICLE_COLOR),
            PropertyFactory.circleOpacity(0.2f),
            PropertyFactory.circlePitchAlignment(Property.CIRCLE_PITCH_ALIGNMENT_MAP),
        ),
    )
    style.addLayer(
        CircleLayer(VEHICLE_LAYER, VEHICLE_SOURCE).withProperties(
            PropertyFactory.circleRadius(8f),
            PropertyFactory.circleColor(VEHICLE_COLOR),
            PropertyFactory.circleStrokeColor(android.graphics.Color.WHITE),
            PropertyFactory.circleStrokeWidth(3f),
            PropertyFactory.circlePitchAlignment(Property.CIRCLE_PITCH_ALIGNMENT_MAP),
        ),
    )
}

/** The road ahead as line pieces of equal steepness (whole percent), so each gets one color. */
internal fun aheadFeatures(ahead: List<AheadPoint>): FeatureCollection {
    val features = mutableListOf<Feature>()
    var run = mutableListOf<Point>()
    var runSteep: Int? = null
    fun flush() {
        val steep = runSteep ?: return
        if (run.size >= 2) {
            features += Feature.fromGeometry(LineString.fromLngLats(run)).apply { addNumberProperty(STEEP, steep) }
        }
    }
    for ((a, b) in ahead.zipWithNext()) {
        val steep = abs(a.gradePercent).roundToInt().coerceAtMost(MAX_STEEP)
        if (steep != runSteep) {
            flush()
            run = mutableListOf(Point.fromLngLat(a.position.lon, a.position.lat))
            runSteep = steep
        }
        run += Point.fromLngLat(b.position.lon, b.position.lat)
    }
    flush()
    return FeatureCollection.fromFeatures(features)
}

private const val PMTILES_PLACEHOLDER = "__PMTILES_URL__"
private const val AHEAD_SOURCE = "ladeira-ahead"
private const val AHEAD_LAYER = "ladeira-ahead-line"
private const val VEHICLE_SOURCE = "ladeira-vehicle"
private const val VEHICLE_LAYER = "ladeira-vehicle-dot"
private const val VEHICLE_HALO_LAYER = "ladeira-vehicle-halo"
private const val VEHICLE_COLOR = "#1565C0"
private const val STEEP = "steep"
private const val MAX_STEEP = 30
private const val FOLLOW_ZOOM = 16.0
private const val FOLLOW_TILT = 45.0
private const val FOLLOW_ANIMATION_MS = 1_000
private const val REGION_MIN_ZOOM = 11
private const val COMPASS_TOP_DP = 76

/** Before any region is installed: a plain background instead of a broken map. */
private const val EMPTY_STYLE = """{"version":8,"sources":{},"layers":[{"id":"background","type":"background","paint":{"background-color":"#E8E4DC"}}]}"""
