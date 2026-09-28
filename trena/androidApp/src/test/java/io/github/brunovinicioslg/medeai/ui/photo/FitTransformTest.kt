package io.github.brunovinicioslg.medeai.ui.photo

import androidx.compose.ui.geometry.Offset
import com.google.common.truth.Truth.assertThat
import io.github.brunovinicioslg.medeai.geometry.Vec2
import kotlin.random.Random
import org.junit.Test

class FitTransformTest {

    @Test
    fun `wide image is letterboxed vertically`() {
        val t = FitTransform.fit(imageWidth = 2000, imageHeight = 1000, viewWidth = 1000f, viewHeight = 1000f)!!
        assertThat(t.scale).isEqualTo(0.5f)
        assertThat(t.offsetX).isEqualTo(0f)
        assertThat(t.offsetY).isEqualTo(250f)
    }

    @Test
    fun `tall image is letterboxed horizontally`() {
        val t = FitTransform.fit(imageWidth = 1000, imageHeight = 2000, viewWidth = 1000f, viewHeight = 1000f)!!
        assertThat(t.scale).isEqualTo(0.5f)
        assertThat(t.offsetX).isEqualTo(250f)
        assertThat(t.offsetY).isEqualTo(0f)
    }

    @Test
    fun `view and image coordinates round trip`() {
        val rnd = Random(42)
        repeat(1000) {
            val t = FitTransform.fit(rnd.nextInt(1, 5000), rnd.nextInt(1, 5000), rnd.nextInt(1, 3000).toFloat(), rnd.nextInt(1, 3000).toFloat())!!
            val p = Vec2(rnd.nextDouble(0.0, t.imageWidth.toDouble()), rnd.nextDouble(0.0, t.imageHeight.toDouble()))
            val back = t.toImage(t.toView(p))
            assertThat(back.distanceTo(p)).isLessThan(0.01 * (1 + 1 / t.scale.toDouble()))
            assertThat(t.isInsideImage(p)).isTrue()
        }
    }

    @Test
    fun `points in the letterbox are outside the image`() {
        val t = FitTransform.fit(2000, 1000, 1000f, 1000f)!!
        assertThat(t.isInsideImage(t.toImage(Offset(500f, 100f)))).isFalse()
        assertThat(t.isInsideImage(t.toImage(Offset(500f, 500f)))).isTrue()
    }

    @Test
    fun `no transform without sizes`() {
        assertThat(FitTransform.fit(0, 100, 100f, 100f)).isNull()
        assertThat(FitTransform.fit(100, 100, 0f, 100f)).isNull()
    }

    @Test
    fun `custom reference sizes accept comma and dot`() {
        assertThat(parseCentimeters("8,56")).isEqualTo(8.56)
        assertThat(parseCentimeters(" 21.0 ")).isEqualTo(21.0)
        assertThat(parseCentimeters("0")).isNull()
        assertThat(parseCentimeters("-3")).isNull()
        assertThat(parseCentimeters("abc")).isNull()
        assertThat(parseCentimeters("NaN")).isNull()
        assertThat(parseCentimeters("Infinity")).isNull()
    }
}
