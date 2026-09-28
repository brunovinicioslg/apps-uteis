package io.github.brunovinicioslg.medeai.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.brunovinicioslg.medeai.appContainer
import io.github.brunovinicioslg.medeai.settings.AppSettings
import io.github.brunovinicioslg.medeai.ui.level.LevelScreen
import io.github.brunovinicioslg.medeai.ui.photo.PhotoScreen
import io.github.brunovinicioslg.medeai.ui.theme.MedeAiTheme
import io.github.brunovinicioslg.medeai.ui.tilt.TiltScreen
import kotlinx.coroutines.launch

enum class Tool { HOME, PHOTO, TILT, LEVEL }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            MedeAiTheme {
                MedeAiRoot()
            }
        }
    }
}

@Composable
private fun MedeAiRoot() {
    val repository = LocalContext.current.appContainer.settingsRepository
    val settings by remember { repository.settings }.collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()
    var tool by rememberSaveable { mutableStateOf(Tool.HOME) }
    BackHandler(enabled = tool != Tool.HOME) { tool = Tool.HOME }

    val current = settings ?: return
    val update: ((AppSettings) -> AppSettings) -> Unit = { transform -> scope.launch { repository.update(transform) } }
    val goHome = { tool = Tool.HOME }
    when (tool) {
        Tool.HOME -> HomeScreen(
            settings = current,
            onOpen = { tool = it },
            onUnitSystemChange = { system -> update { it.copy(unitSystem = system) } },
        )
        Tool.PHOTO -> PhotoScreen(unitSystem = current.unitSystem, onBack = goHome)
        Tool.TILT -> TiltScreen(
            settings = current,
            onCameraHeightChange = { meters -> update { it.copy(cameraHeightMeters = meters) } },
            onBack = goHome,
        )
        Tool.LEVEL -> LevelScreen(onBack = goHome)
    }
}
