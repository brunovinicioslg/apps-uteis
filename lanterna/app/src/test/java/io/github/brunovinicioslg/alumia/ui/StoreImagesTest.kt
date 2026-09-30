package io.github.brunovinicioslg.alumia.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.brunovinicioslg.alumia.R
import io.github.brunovinicioslg.alumia.core.settings.Settings
import io.github.brunovinicioslg.alumia.torch.TorchState
import io.github.brunovinicioslg.alumia.ui.theme.AlumiaTheme
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * The Google Play images, drawn from the real screens. Skipped in normal runs; to write them:
 * `./gradlew :app:testDebugUnitTest --tests '*StoreImagesTest' --rerun -PstoreImages=<folder>`.
 * The folder gets `<locale>/images/...`, the layout Play tools and F-Droid read.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// Android 11: the app's own colors instead of the wallpaper's.
@Config(sdk = [30])
class StoreImagesTest {

    @get:Rule
    val compose = createComposeRule()

    private val out = System.getProperty("storeImages")?.takeIf { it.isNotBlank() }?.let(::File)

    @Before
    fun onlyWhenAsked() = assumeTrue("pass -PstoreImages=<folder> to write the store images", out != null)

    @Test
    @Config(qualifiers = "pt-rBR-w360dp-h640dp-xxhdpi")
    fun `screenshots in Portuguese`() = screenshots("pt-BR")

    @Test
    @Config(qualifiers = "en-rUS-w360dp-h640dp-xxhdpi")
    fun `screenshots in English`() = screenshots("en-US")

    @Test
    @Config(qualifiers = "w1024dp-h1024dp-mdpi")
    fun icon() {
        compose.setContent {
            Box(Modifier.testTag(IMAGE).size(512.dp).background(colorResource(R.color.brand_navy)).clipToBounds(), Alignment.Center) {
                // The launcher shows the middle 72 of the icon's 108; Play rounds only the corners.
                Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.requiredSize(512.dp * 108 / 80))
            }
        }
        val image = compose.onNodeWithTag(IMAGE).captureToImage()
        LOCALES.forEach { save(image, "$it/images/icon.png", alpha = true) }
    }

    @Test
    @Config(qualifiers = "pt-rBR-w1024dp-h1024dp-mdpi")
    fun `feature graphic in Portuguese`() = featureGraphic("pt-BR", "Lanterna no gesto", "Chacoalhe o celular para acender, até com a tela bloqueada")

    @Test
    @Config(qualifiers = "en-rUS-w1024dp-h1024dp-mdpi")
    fun `feature graphic in English`() = featureGraphic("en-US", "Shake for light", "Shake your phone to turn on the flashlight, even when locked")

    private fun screenshots(locale: String) {
        var torch by mutableStateOf<TorchState>(TorchState.On(byApp = true))
        compose.setContent {
            AlumiaTheme {
                MainScreen(
                    state = MainScreenState(
                        settings = Settings(),
                        torch = torch,
                        torchMaxLevel = 1,
                        notificationShown = false,
                        batteryUnrestricted = true,
                        sideKeyEnabled = true,
                        manufacturer = Manufacturer.OTHER,
                    ),
                    actions = MainScreenActions({}, {}, {}, {}, {}, {}),
                )
            }
        }
        shoot("$locale/images/phoneScreenshots/1.png")
        torch = TorchState.Off
        compose.onNodeWithTag(MAIN_LIST_TAG).performScrollToIndex(1)
        shoot("$locale/images/phoneScreenshots/2.png")
        compose.onNodeWithTag(MAIN_LIST_TAG).performScrollToIndex(2)
        shoot("$locale/images/phoneScreenshots/3.png")
    }

    private fun featureGraphic(locale: String, title: String, text: String) {
        compose.setContent { FeatureGraphic(title, text) }
        save(compose.onNodeWithTag(IMAGE).captureToImage(), "$locale/images/featureGraphic.png")
    }

    @Composable
    private fun FeatureGraphic(title: String, text: String) {
        Row(
            Modifier.testTag(IMAGE).size(1024.dp, 500.dp).background(colorResource(R.color.brand_navy)).padding(horizontal = 72.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.requiredSize(380.dp))
            Spacer(Modifier.width(24.dp))
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Alumia", color = colorResource(R.color.brand_amber), fontSize = 88.sp, fontWeight = FontWeight.Bold)
                Text(title, color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.Medium)
                Text(text, color = Color.White.copy(alpha = 0.8f), fontSize = 28.sp, lineHeight = 36.sp)
            }
        }
    }

    private fun shoot(path: String) {
        compose.waitForIdle()
        save(compose.onRoot().captureToImage(), path)
    }

    /** Play takes the icon with transparency (32-bit PNG) and the other images without it (24-bit PNG). */
    private fun save(image: ImageBitmap, path: String, alpha: Boolean = false) {
        val file = File(out, path)
        file.parentFile!!.mkdirs()
        val bitmap = image.asAndroidBitmap()
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val png = BufferedImage(bitmap.width, bitmap.height, if (alpha) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB)
        png.setRGB(0, 0, bitmap.width, bitmap.height, pixels, 0, bitmap.width)
        check(ImageIO.write(png, "png", file)) { "no PNG writer" }
    }

    private companion object {
        const val IMAGE = "store_image"
        val LOCALES = listOf("pt-BR", "en-US")
    }
}
