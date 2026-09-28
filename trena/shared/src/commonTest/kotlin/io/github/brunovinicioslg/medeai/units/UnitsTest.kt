package io.github.brunovinicioslg.medeai.units

import io.github.brunovinicioslg.medeai.forEachCase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UnitsTest {

    private val br = MeasureFormatter(',')

    @Test
    fun metricLengths() {
        assertEquals("1,23 m", br.length(1.234, LengthUnit.METER))
        assertEquals("50,0 cm", br.length(0.5, LengthUnit.CENTIMETER))
        assertEquals("13 mm", br.length(0.0126, LengthUnit.MILLIMETER))
        assertEquals("1,01 m", br.length(1.005, LengthUnit.METER), "half rounds up despite binary error")
        assertEquals("0,00 m", br.length(-0.001, LengthUnit.METER), "no negative zero")
        assertEquals("1.23 m", MeasureFormatter('.').length(1.234, LengthUnit.METER))
    }

    @Test
    fun imperialLengths() {
        assertEquals("1 ft 0,0 in", br.length(0.3048, LengthUnit.FOOT_INCH))
        assertEquals("1 ft 0,0 in", br.length(0.0254 * 11.96, LengthUnit.FOOT_INCH), "rounding carries into feet")
        assertEquals("5 ft 6,9 in", br.length(1.7, LengthUnit.FOOT_INCH))
        assertEquals("3,9 in", br.length(0.1, LengthUnit.FOOT_INCH))
        assertEquals("3,94 ft", br.length(1.2, LengthUnit.FOOT))
        assertEquals("39,4 in", br.length(1.0, LengthUnit.INCH))
    }

    @Test
    fun automaticUnit() {
        assertEquals("85,6 cm", br.length(0.856, UnitSystem.METRIC))
        assertEquals("2,40 m", br.length(2.4, UnitSystem.METRIC))
        assertEquals("7 ft 10,5 in", br.length(2.4, UnitSystem.IMPERIAL))
    }

    @Test
    fun areas() {
        assertEquals("624 cm²", br.area(0.210 * 0.297, UnitSystem.METRIC))
        assertEquals("2,50 m²", br.area(2.5, UnitSystem.METRIC))
        assertEquals("10,76 ft²", br.area(1.0, UnitSystem.IMPERIAL))
        assertEquals("1,6 in²", br.area(0.001, UnitSystem.IMPERIAL))
    }

    @Test
    fun invalidValuesShowADash() {
        assertEquals("—", br.length(Double.NaN, LengthUnit.METER))
        assertEquals("—", br.area(Double.POSITIVE_INFINITY, UnitSystem.METRIC))
        assertEquals("—", br.degrees(Double.NaN))
        assertEquals("90,0°", br.degrees(90.0))
    }

    @Test
    fun formattedMetricValuesRoundTrip() {
        forEachCase(2000) { seed, rnd ->
            val meters = rnd.nextDouble(0.0, 100.0)
            val text = br.length(meters, LengthUnit.METER)
            val parsed = text.removeSuffix(" m").replace(',', '.').toDouble()
            assertTrue(kotlin.math.abs(parsed - meters) <= 0.005 + 1e-9, "seed=$seed $meters -> $text")
        }
    }
}
