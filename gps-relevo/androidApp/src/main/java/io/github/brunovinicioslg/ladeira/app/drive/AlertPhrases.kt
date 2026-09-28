package io.github.brunovinicioslg.ladeira.app.drive

import android.content.Context
import io.github.brunovinicioslg.ladeira.alerts.Alert
import io.github.brunovinicioslg.ladeira.app.R
import io.github.brunovinicioslg.ladeira.profile.Slope
import io.github.brunovinicioslg.ladeira.profile.SlopeKind
import io.github.brunovinicioslg.ladeira.road.PoiType
import java.text.NumberFormat
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Words for alerts, in the app's language: spoken sentences and short labels for the screen. */
class AlertPhrases(private val context: Context) {

    private val locale get() = context.resources.configuration.locales[0]

    /** The sentence read aloud for [alert]. */
    fun speech(alert: Alert): String = when (alert) {
        is Alert.SlopeAhead -> slopeSpeech(alert.slope, alert.kind, alert.distance)
        is Alert.PoiAhead -> buildList {
            add(ahead(poiName(alert.poi.type), alert.distance))
            alert.poi.speedLimitKmh?.let { add(context.getString(R.string.speech_limit, it)) }
        }.joinToString(" ")
    }

    private fun slopeSpeech(slope: Slope, kind: SlopeKind, distance: Double): String {
        // Inside the slope, what matters is how much of it is left.
        val remaining = slope.end - max(0.0, slope.start)
        val grade = abs(slope.averageGradePercent).roundToInt()
        return buildList {
            add(ahead(kindName(kind), distance))
            add(context.getString(R.string.speech_slope_detail, spokenDistance(remaining), grade.toString()))
            if (kind == SlopeKind.LONG_DESCENT) add(context.getString(R.string.speech_engine_brake))
        }.joinToString(" ")
    }

    private fun ahead(what: String, distance: Double): String =
        if (distance < NOW_M) "$what." else context.getString(R.string.speech_ahead, what, spokenDistance(distance))

    /** Rounded the way people say it: "450 metros", "1 quilômetro", "1,5 quilômetros". */
    fun spokenDistance(meters: Double): String {
        if (meters < 950) return context.getString(R.string.speech_meters, max(50L, (meters / 50).roundToLong() * 50).toString())
        val tenths = (meters / 100).roundToLong()
        if (tenths == 10L) return context.getString(R.string.speech_one_km)
        val km = if (tenths % 10 == 0L) (tenths / 10).toString() else decimal(tenths / 10.0)
        return context.getString(R.string.speech_km, km)
    }

    fun kindName(kind: SlopeKind): String = context.getString(
        when (kind) {
            SlopeKind.CLIMB -> R.string.kind_climb
            SlopeKind.DESCENT -> R.string.kind_descent
            SlopeKind.LONG_DESCENT -> R.string.kind_long_descent
        },
    )

    fun poiName(type: PoiType): String = context.getString(
        when (type) {
            PoiType.SPEED_CAMERA -> R.string.poi_speed_camera
            PoiType.RED_LIGHT_CAMERA -> R.string.poi_red_light_camera
            PoiType.SPEED_BUMP -> R.string.poi_speed_bump
            PoiType.POTHOLE -> R.string.poi_pothole
            PoiType.DANGEROUS_CURVE -> R.string.poi_dangerous_curve
            PoiType.FLOODING -> R.string.poi_flooding
            PoiType.TOLL -> R.string.poi_toll
            PoiType.ROADWORK -> R.string.poi_roadwork
            PoiType.OTHER -> R.string.poi_other
        },
    )

    /** For the screen: "850 m", "1,2 km", "12 km". */
    fun shortDistance(meters: Double): String = when {
        meters < 995 -> "${max(0L, (meters / 10).roundToLong() * 10)} m"
        meters < 9_950 -> "${decimal((meters / 100).roundToLong() / 10.0)} km"
        else -> "${(meters / 1000).roundToLong()} km"
    }

    /** OSM joins concurrent route numbers with ';': "BR-040;BR-356" reads "BR-040 / BR-356". */
    fun roadNumber(ref: String): String = ref.split(';').map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" / ")

    /** For the screen: "6%". */
    fun percent(value: Double): String = "${abs(value).roundToInt()}%"

    private fun decimal(value: Double): String =
        NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 1; minimumFractionDigits = 1 }.format(value)

    private companion object {
        /** Closer than this, the alert is about where the vehicle already is. */
        const val NOW_M = 50.0
    }
}
