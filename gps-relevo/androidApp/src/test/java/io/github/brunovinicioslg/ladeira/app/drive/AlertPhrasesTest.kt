package io.github.brunovinicioslg.ladeira.app.drive

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.brunovinicioslg.ladeira.alerts.Alert
import io.github.brunovinicioslg.ladeira.geo.LatLon
import io.github.brunovinicioslg.ladeira.profile.Slope
import io.github.brunovinicioslg.ladeira.profile.SlopeKind
import io.github.brunovinicioslg.ladeira.road.Poi
import io.github.brunovinicioslg.ladeira.road.PoiType
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "pt-rBR")
class AlertPhrasesTest {

    private val phrases = AlertPhrases(ApplicationProvider.getApplicationContext())
    private val here = LatLon(-20.0, -43.9)

    @Test
    fun longDescentAhead() {
        val slope = Slope(start = 800.0, end = 4_000.0, elevationChange = -192.0, maxGradePercent = 7.5)
        assertThat(phrases.speech(Alert.SlopeAhead(slope, SlopeKind.LONG_DESCENT, 800.0)))
            .isEqualTo("Descida longa em 800 metros. 3,2 quilômetros com 6 por cento. Use o freio motor.")
    }

    @Test
    fun alreadyOnTheSlopeTellsWhatIsLeft() {
        val slope = Slope(start = -700.0, end = 2_500.0, elevationChange = -192.0, maxGradePercent = 7.0)
        assertThat(phrases.speech(Alert.SlopeAhead(slope, SlopeKind.LONG_DESCENT, 0.0)))
            .isEqualTo("Descida longa. 2,5 quilômetros com 6 por cento. Use o freio motor.")
    }

    @Test
    fun climbAndDescentHaveNoBrakeAdvice() {
        val climb = Slope(start = 1_000.0, end = 1_600.0, elevationChange = 48.0, maxGradePercent = 10.0)
        assertThat(phrases.speech(Alert.SlopeAhead(climb, SlopeKind.CLIMB, 1_000.0)))
            .isEqualTo("Subida em 1 quilômetro. 600 metros com 8 por cento.")
        val descent = Slope(start = 430.0, end = 930.0, elevationChange = -35.0, maxGradePercent = 9.0)
        assertThat(phrases.speech(Alert.SlopeAhead(descent, SlopeKind.DESCENT, 430.0)))
            .isEqualTo("Descida em 450 metros. 500 metros com 7 por cento.")
    }

    @Test
    fun pointsOfInterest() {
        assertThat(phrases.speech(Alert.PoiAhead(Poi(PoiType.SPEED_CAMERA, here, speedLimitKmh = 60), 310.0)))
            .isEqualTo("Radar em 300 metros. Limite de 60.")
        assertThat(phrases.speech(Alert.PoiAhead(Poi(PoiType.POTHOLE, here), 180.0))).isEqualTo("Buraco em 200 metros.")
        assertThat(phrases.speech(Alert.PoiAhead(Poi(PoiType.SPEED_BUMP, here), 20.0))).isEqualTo("Lombada.")
    }

    @Test
    fun spokenDistancesAreRoundedLikePeopleSayThem() {
        assertThat(phrases.spokenDistance(60.0)).isEqualTo("50 metros")
        assertThat(phrases.spokenDistance(949.0)).isEqualTo("950 metros")
        assertThat(phrases.spokenDistance(960.0)).isEqualTo("1 quilômetro")
        assertThat(phrases.spokenDistance(1_449.0)).isEqualTo("1,4 quilômetros")
        assertThat(phrases.spokenDistance(2_010.0)).isEqualTo("2 quilômetros")
    }

    @Test
    fun screenDistancesAndPercent() {
        assertThat(phrases.shortDistance(0.0)).isEqualTo("0 m")
        assertThat(phrases.shortDistance(994.0)).isEqualTo("990 m")
        assertThat(phrases.shortDistance(995.0)).isEqualTo("1,0 km")
        assertThat(phrases.shortDistance(3_240.0)).isEqualTo("3,2 km")
        assertThat(phrases.shortDistance(12_345.0)).isEqualTo("12 km")
        assertThat(phrases.percent(-6.4)).isEqualTo("6%")
    }

    @Test
    fun concurrentRouteNumbers() {
        assertThat(phrases.roadNumber("BR-040;BR-356")).isEqualTo("BR-040 / BR-356")
        assertThat(phrases.roadNumber("MG-030")).isEqualTo("MG-030")
        assertThat(phrases.roadNumber(" BR-381 ; ")).isEqualTo("BR-381")
    }

    @Test
    @Config(qualifiers = "en-rUS")
    fun english() {
        val english = AlertPhrases(ApplicationProvider.getApplicationContext())
        val slope = Slope(start = 800.0, end = 4_000.0, elevationChange = -192.0, maxGradePercent = 7.5)
        assertThat(english.speech(Alert.SlopeAhead(slope, SlopeKind.LONG_DESCENT, 800.0)))
            .isEqualTo("Long descent in 800 meters. 3.2 kilometers at 6 percent. Use engine braking.")
    }
}
