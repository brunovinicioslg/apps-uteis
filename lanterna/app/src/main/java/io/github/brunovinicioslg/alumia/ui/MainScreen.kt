package io.github.brunovinicioslg.alumia.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.brunovinicioslg.alumia.BuildConfig
import io.github.brunovinicioslg.alumia.R
import io.github.brunovinicioslg.alumia.core.detection.Sensitivity
import io.github.brunovinicioslg.alumia.core.settings.Settings
import io.github.brunovinicioslg.alumia.shortcut.SideKeyShortcut
import io.github.brunovinicioslg.alumia.torch.TorchState
import io.github.brunovinicioslg.alumia.ui.theme.OnTorchAmber
import io.github.brunovinicioslg.alumia.ui.theme.TorchAmber

const val SOURCE_CODE_URL = "https://github.com/brunovinicioslg/apps-uteis"
const val MAIN_LIST_TAG = "main_list"

data class MainScreenState(
    val settings: Settings,
    val torch: TorchState,
    val torchMaxLevel: Int,
    val notificationsGranted: Boolean,
    val batteryUnrestricted: Boolean,
    val sideKeyEnabled: Boolean,
    val manufacturer: Manufacturer,
)

class MainScreenActions(
    val onToggleTorch: () -> Unit,
    val onSettingsChange: ((Settings) -> Settings) -> Unit,
    val onRequestNotifications: () -> Unit,
    val onOpenBatterySettings: () -> Unit,
    val onSideKeyChange: (Boolean) -> Unit,
    val onOpenSourceCode: () -> Unit,
)

@Composable
fun MainRoute(viewModel: MainViewModel = viewModel()) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val torch by viewModel.torch.collectAsStateWithLifecycle()
    var notificationsGranted by remember { mutableStateOf(DeviceStatus.notificationsGranted(context)) }
    var batteryUnrestricted by remember { mutableStateOf(DeviceStatus.batteryUnrestricted(context)) }
    var sideKeyEnabled by remember { mutableStateOf(SideKeyShortcut.isEnabled(context)) }
    val manufacturer = remember { DeviceStatus.manufacturer() }

    // The user may change these in system settings while the app is in the background.
    LifecycleResumeEffect(Unit) {
        notificationsGranted = DeviceStatus.notificationsGranted(context)
        batteryUnrestricted = DeviceStatus.batteryUnrestricted(context)
        sideKeyEnabled = SideKeyShortcut.isEnabled(context)
        onPauseOrDispose { }
    }

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notificationsGranted = granted
        // Re-posts the service notification now that it can be shown.
        if (granted) viewModel.ensureServiceRunning()
    }

    val current = settings ?: return
    // Also re-runs when notifications get allowed from system settings, so the service re-posts
    // its notification with current content.
    LaunchedEffect(current.detectionEnabled, notificationsGranted) {
        if (current.detectionEnabled) viewModel.ensureServiceRunning()
    }

    MainScreen(
        state = MainScreenState(
            settings = current,
            torch = torch,
            torchMaxLevel = viewModel.torchMaxLevel,
            notificationsGranted = notificationsGranted,
            batteryUnrestricted = batteryUnrestricted,
            sideKeyEnabled = sideKeyEnabled,
            manufacturer = manufacturer,
        ),
        actions = MainScreenActions(
            onToggleTorch = viewModel::toggleTorch,
            onSettingsChange = viewModel::updateSettings,
            onRequestNotifications = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            },
            onOpenBatterySettings = { DeviceStatus.openBatterySettings(context, manufacturer) },
            onSideKeyChange = { enabled ->
                SideKeyShortcut.setEnabled(context, enabled)
                sideKeyEnabled = enabled
            },
            onOpenSourceCode = { DeviceStatus.openUrl(context, SOURCE_CODE_URL) },
        ),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(state: MainScreenState, actions: MainScreenActions) {
    val settings = state.settings
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag(MAIN_LIST_TAG),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding(),
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { TorchButton(state.torch, actions.onToggleTorch) }

            if (!state.notificationsGranted) {
                item {
                    HintCard(
                        title = stringResource(R.string.notifications_title),
                        text = stringResource(R.string.notifications_summary),
                        button = stringResource(R.string.notifications_button),
                        onClick = actions.onRequestNotifications,
                    )
                }
            }
            if (settings.detectionEnabled && !state.batteryUnrestricted) {
                item {
                    HintCard(
                        title = stringResource(R.string.battery_title),
                        text = stringResource(
                            when (state.manufacturer) {
                                Manufacturer.XIAOMI -> R.string.battery_summary_xiaomi
                                Manufacturer.SAMSUNG -> R.string.battery_summary_samsung
                                Manufacturer.OTHER -> R.string.battery_summary_generic
                            },
                        ),
                        button = stringResource(R.string.battery_button),
                        onClick = actions.onOpenBatterySettings,
                    )
                }
            }

            item { DetectionSection(settings, actions.onSettingsChange) }
            item { TorchSection(settings, state.torchMaxLevel, actions.onSettingsChange) }
            item { ShortcutsSection(state.sideKeyEnabled, actions.onSideKeyChange) }
            item { AboutSection(actions.onOpenSourceCode) }
        }
    }
}

@Composable
private fun TorchButton(torch: TorchState, onClick: () -> Unit) {
    val on = torch is TorchState.On
    val status = stringResource(
        when (torch) {
            is TorchState.On -> R.string.torch_status_on
            TorchState.NoFlash -> R.string.torch_status_no_flash
            TorchState.Unavailable -> R.string.torch_status_busy
            TorchState.Off, TorchState.Unknown -> R.string.torch_status_off
        },
    )
    val actionLabel = stringResource(if (on) R.string.torch_turn_off else R.string.torch_turn_on)
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            onClick = onClick,
            enabled = torch != TorchState.NoFlash,
            shape = CircleShape,
            color = if (on) TorchAmber else MaterialTheme.colorScheme.surfaceVariant,
            contentColor = if (on) OnTorchAmber else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .size(168.dp)
                .semantics {
                    contentDescription = actionLabel
                    stateDescription = status
                },
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painter = painterResource(if (on) R.drawable.ic_flashlight_on else R.drawable.ic_flashlight),
                    contentDescription = null,
                    modifier = Modifier.size(84.dp),
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(status, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
    }
}

@Composable
private fun DetectionSection(settings: Settings, onChange: ((Settings) -> Settings) -> Unit) {
    SectionCard {
        SwitchRow(
            title = stringResource(R.string.detection_title),
            summary = stringResource(if (settings.detectionEnabled) R.string.detection_on_summary else R.string.detection_off_summary),
            checked = settings.detectionEnabled,
            onCheckedChange = { enabled -> onChange { it.copy(detectionEnabled = enabled) } },
            emphasized = true,
        )
        if (!settings.detectionEnabled) return@SectionCard
        HorizontalDivider()
        OptionRow(
            title = stringResource(R.string.sensitivity_title),
            options = Sensitivity.entries,
            selected = settings.sensitivity,
            label = {
                stringResource(
                    when (it) {
                        Sensitivity.LOW -> R.string.sensitivity_low
                        Sensitivity.MEDIUM -> R.string.sensitivity_medium
                        Sensitivity.HIGH -> R.string.sensitivity_high
                    },
                )
            },
            onSelect = { value -> onChange { it.copy(sensitivity = value) } },
        )
        OptionRow(
            title = stringResource(R.string.strokes_title),
            summary = stringResource(R.string.strokes_summary),
            options = Settings.STROKE_OPTIONS,
            selected = settings.requiredStrokes,
            label = { it.toString() },
            onSelect = { value -> onChange { it.copy(requiredStrokes = value) } },
        )
        SwitchRow(
            title = stringResource(R.string.screen_off_title),
            summary = stringResource(R.string.screen_off_summary),
            checked = settings.workWithScreenOff,
            onCheckedChange = { value -> onChange { it.copy(workWithScreenOff = value) } },
        )
        SwitchRow(
            title = stringResource(R.string.pocket_title),
            summary = stringResource(R.string.pocket_summary),
            checked = settings.ignoreInPocket,
            onCheckedChange = { value -> onChange { it.copy(ignoreInPocket = value) } },
        )
        SwitchRow(
            title = stringResource(R.string.vibrate_title),
            summary = null,
            checked = settings.vibrate,
            onCheckedChange = { value -> onChange { it.copy(vibrate = value) } },
        )
    }
}

@Composable
private fun TorchSection(settings: Settings, maxLevel: Int, onChange: ((Settings) -> Settings) -> Unit) {
    SectionCard {
        SectionTitle(stringResource(R.string.torch_section_title))
        OptionRow(
            title = stringResource(R.string.auto_off_title),
            options = Settings.AUTO_OFF_OPTIONS,
            selected = settings.autoOffMinutes,
            label = { if (it == 0) stringResource(R.string.option_never) else stringResource(R.string.minutes_short, it) },
            onSelect = { value -> onChange { it.copy(autoOffMinutes = value) } },
        )
        OptionRow(
            title = stringResource(R.string.low_battery_title),
            options = Settings.LOW_BATTERY_OPTIONS,
            selected = settings.lowBatteryPercent,
            label = { if (it == 0) stringResource(R.string.option_never) else stringResource(R.string.percent_short, it) },
            onSelect = { value -> onChange { it.copy(lowBatteryPercent = value) } },
        )
        if (maxLevel > 1) {
            var level by remember(settings.torchLevelPercent) { mutableFloatStateOf(settings.torchLevelPercent.toFloat()) }
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(stringResource(R.string.level_title), style = MaterialTheme.typography.bodyLarge)
                Slider(
                    value = level,
                    onValueChange = { level = it },
                    onValueChangeFinished = { onChange { it.copy(torchLevelPercent = level.toInt()) } },
                    valueRange = 1f..100f,
                )
            }
        }
    }
}

@Composable
private fun ShortcutsSection(sideKeyEnabled: Boolean, onSideKeyChange: (Boolean) -> Unit) {
    SectionCard {
        SectionTitle(stringResource(R.string.shortcuts_title))
        SwitchRow(
            title = stringResource(R.string.side_key_title),
            summary = stringResource(R.string.side_key_summary),
            checked = sideKeyEnabled,
            onCheckedChange = onSideKeyChange,
        )
        Text(
            stringResource(R.string.shortcuts_other),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
        )
    }
}

@Composable
private fun AboutSection(onOpenSourceCode: () -> Unit) {
    SectionCard {
        SectionTitle(stringResource(R.string.about_title))
        Text(
            stringResource(R.string.about_text, BuildConfig.VERSION_NAME),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        TextButton(onClick = onOpenSourceCode, modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) {
            Text(stringResource(R.string.about_source))
        }
    }
}

@Composable
private fun HintCard(title: String, text: String, button: String, onClick: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onClick, modifier = Modifier.align(Alignment.End)) { Text(button) }
        }
    }
}

@Composable
private fun SectionCard(content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 8.dp)) { content() }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun SwitchRow(
    title: String,
    summary: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    emphasized: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text(title, style = if (emphasized) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge)
            if (summary != null) {
                Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun <T> OptionRow(
    title: String,
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    summary: String? = null,
) {
    Column(Modifier.padding(vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 16.dp))
        if (summary != null) {
            Text(
                summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEach { option ->
                FilterChip(
                    selected = option == selected,
                    onClick = { onSelect(option) },
                    label = { Text(label(option)) },
                )
            }
        }
    }
}
