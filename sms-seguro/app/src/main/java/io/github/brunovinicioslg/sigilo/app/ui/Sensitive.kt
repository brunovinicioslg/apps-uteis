package io.github.brunovinicioslg.sigilo.app.ui

import android.os.Build
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import io.github.brunovinicioslg.sigilo.app.BuildConfig

/**
 * Keeps this window's text from apps that read the screen through accessibility, as spyware does
 * (blocking screenshots does not stop them). Android 14+ then hands it only to real accessibility
 * tools, such as TalkBack. Lifted with screenshots, by `-PallowScreenshots`, for UI testing.
 */
fun View.markAccessibilitySensitive() {
    if (BuildConfig.SECURE_WINDOW && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        rootView.setAccessibilityDataSensitive(View.ACCESSIBILITY_DATA_SENSITIVE_YES)
    }
}

/** For dialogs and menus that show messages, names or passwords: they open in windows of their own. */
@Composable
fun SensitiveWindow() {
    val view = LocalView.current
    DisposableEffect(view) {
        view.markAccessibilitySensitive()
        onDispose { }
    }
}
