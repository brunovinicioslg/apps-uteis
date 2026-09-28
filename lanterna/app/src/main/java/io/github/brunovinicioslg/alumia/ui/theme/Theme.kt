package io.github.brunovinicioslg.alumia.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/** Brand color of the lit flashlight; kept fixed even with dynamic color so "on" always looks like light. */
val TorchAmber = Color(0xFFFFC940)
val OnTorchAmber = Color(0xFF14213D)

private val LightColors = lightColorScheme(
    primary = Color(0xFF745B00),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE08B),
    onPrimaryContainer = Color(0xFF241A00),
    secondary = Color(0xFF3D5A80),
    onSecondary = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFC940),
    onPrimary = Color(0xFF3C2F00),
    primaryContainer = Color(0xFF584400),
    onPrimaryContainer = Color(0xFFFFE08B),
    secondary = Color(0xFFA9C7F0),
    onSecondary = Color(0xFF0B3050),
)

@Composable
fun AlumiaTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}
