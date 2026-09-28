package io.github.brunovinicioslg.sigilo.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Bubble colors: the user's own messages in the brand tint, the contact's in a neutral one. */
@Immutable
data class BubbleColors(val outgoing: Color, val onOutgoing: Color, val incoming: Color, val onIncoming: Color, val chatBackground: Color)

private val LightBubbles = BubbleColors(Color(0xFFE0E7FF), Color(0xFF1E1B4B), Color.White, Color(0xFF111827), Color(0xFFF1F0F7))
private val DarkBubbles = BubbleColors(Color(0xFF312E81), Color(0xFFE0E7FF), Color(0xFF1F2330), Color(0xFFE5E7EB), Color(0xFF0E0F16))

val LocalBubbles = staticCompositionLocalOf { LightBubbles }

// Fixed brand colors (no wallpaper-based dynamic color): the look stays recognizable.
private val LightColors = lightColorScheme(
    primary = Color(0xFF3730A3),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE0E7FF),
    onPrimaryContainer = Color(0xFF1E1B4B),
    secondary = Color(0xFF4F46E5),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA5B4FC),
    onPrimary = Color(0xFF1E1B4B),
    primaryContainer = Color(0xFF312E81),
    onPrimaryContainer = Color(0xFFE0E7FF),
    secondary = Color(0xFF818CF8),
)

@Composable
fun SigiloTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    androidx.compose.runtime.CompositionLocalProvider(LocalBubbles provides if (dark) DarkBubbles else LightBubbles) {
        MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, content = content)
    }
}
