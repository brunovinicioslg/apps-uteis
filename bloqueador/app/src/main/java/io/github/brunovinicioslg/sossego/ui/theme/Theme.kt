package io.github.brunovinicioslg.sossego.ui.theme

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

private val LightColors = lightColorScheme(
    primary = Color(0xFF00696F),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF9CF0F6),
    onPrimaryContainer = Color(0xFF002022),
    secondary = Color(0xFF4A6365),
    onSecondary = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF80D4DA),
    onPrimary = Color(0xFF003739),
    primaryContainer = Color(0xFF004F53),
    onPrimaryContainer = Color(0xFF9CF0F6),
    secondary = Color(0xFFB1CBCD),
    onSecondary = Color(0xFF1C3436),
)

@Composable
fun SossegoTheme(content: @Composable () -> Unit) {
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
