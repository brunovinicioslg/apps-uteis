package io.github.brunovinicioslg.ladeira.app.ui.theme

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

/** Grade colors, the same on the map line and on the panel: flat green to steep red. */
val GradeFlat = Color(0xFF2E7D32)
val GradeMild = Color(0xFFF9A825)
val GradeSteep = Color(0xFFEF6C00)
val GradeVerySteep = Color(0xFFC62828)
val LimitRed = Color(0xFFD32F2F)

fun gradeColor(percent: Double): Color {
    val steep = kotlin.math.abs(percent)
    return when {
        steep < 3 -> GradeFlat
        steep < 6 -> GradeMild
        steep < 9 -> GradeSteep
        else -> GradeVerySteep
    }
}

private val LightColors = lightColorScheme(
    primary = Color(0xFF14532D),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFBBF7D0),
    onPrimaryContainer = Color(0xFF052E16),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF86EFAC),
    onPrimary = Color(0xFF052E16),
    primaryContainer = Color(0xFF166534),
    onPrimaryContainer = Color(0xFFBBF7D0),
)

@Composable
fun LadeiraTheme(content: @Composable () -> Unit) {
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
