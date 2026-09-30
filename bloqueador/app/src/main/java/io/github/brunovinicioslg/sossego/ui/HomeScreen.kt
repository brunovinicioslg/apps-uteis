package io.github.brunovinicioslg.sossego.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.brunovinicioslg.sossego.BuildConfig
import io.github.brunovinicioslg.sossego.R
import io.github.brunovinicioslg.sossego.core.rules.BlockAction
import io.github.brunovinicioslg.sossego.core.rules.Mode
import io.github.brunovinicioslg.sossego.core.rules.NotifyMode
import io.github.brunovinicioslg.sossego.core.rules.Settings
import io.github.brunovinicioslg.sossego.device.Manufacturer
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.format.TextStyle
import java.util.Locale

const val HOME_LIST_TAG = "home_list"

/** What the phone allows, refreshed whenever the app comes back to the front. */
data class DeviceState(
    val screeningAvailable: Boolean = true,
    val screeningEnabled: Boolean = false,
    val canDeclineContacts: Boolean = false,
    val contactsGranted: Boolean = false,
    val batteryUnrestricted: Boolean = true,
    val manufacturer: Manufacturer = Manufacturer.OTHER,
)

class HomeActions(
    val onEnableScreening: () -> Unit,
    val onSettingsChange: ((Settings) -> Settings) -> Unit,
    /** A mode was chosen ([Mode.OFF] turns blocking off); "block everything" was already confirmed. */
    val onChooseMode: (Mode) -> Unit,
    val onChooseNotifications: (NotifyMode) -> Unit,
    val onAllowDecline: () -> Unit,
    val onAllowContacts: () -> Unit,
    val onOpenBattery: () -> Unit,
    val onOpenSystemBlocked: () -> Unit,
    val onOpenSource: () -> Unit,
)

@Composable
fun HomeScreen(
    settings: Settings,
    device: DeviceState,
    now: LocalDateTime,
    actions: HomeActions,
    contentPadding: PaddingValues,
) {
    var confirmBlockAll by rememberSaveable { mutableStateOf(false) }
    val usesBlockAll = settings.enabled && (settings.mode == Mode.BLOCK_ALL || settings.schedule.enabled && settings.schedule.mode == Mode.BLOCK_ALL)
    val usesAllowList = settings.enabled && (settings.mode == Mode.ALLOWLIST || settings.schedule.enabled && settings.schedule.mode == Mode.ALLOWLIST)
    val usesBlockList = settings.mode == Mode.BLOCKLIST || settings.schedule.enabled && settings.schedule.mode == Mode.BLOCKLIST

    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag(HOME_LIST_TAG),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            when {
                !device.screeningAvailable -> HintCard(
                    title = stringResource(R.string.no_telephony_title),
                    text = stringResource(R.string.no_telephony_text),
                    button = null,
                    onClick = {},
                    warning = true,
                )
                !device.screeningEnabled -> HintCard(
                    title = stringResource(R.string.setup_title),
                    text = stringResource(R.string.setup_text),
                    button = stringResource(R.string.setup_button),
                    onClick = actions.onEnableScreening,
                    warning = true,
                )
                else -> StatusCard(settings, now)
            }
        }

        item {
            SectionCard {
                SectionTitle(stringResource(R.string.mode_title))
                val current = if (settings.enabled) settings.mode else Mode.OFF
                ModeRow(Mode.OFF, current) { actions.onChooseMode(Mode.OFF) }
                ModeRow(Mode.BLOCKLIST, current) { actions.onChooseMode(Mode.BLOCKLIST) }
                ModeRow(Mode.ALLOWLIST, current) { actions.onChooseMode(Mode.ALLOWLIST) }
                ModeRow(Mode.BLOCK_ALL, current) {
                    if (current != Mode.BLOCK_ALL) confirmBlockAll = true
                }
            }
        }

        if (usesBlockAll && device.screeningEnabled && !device.canDeclineContacts) {
            item {
                HintCard(
                    title = stringResource(R.string.hint_decline_title),
                    text = stringResource(R.string.hint_decline_text),
                    button = stringResource(R.string.hint_allow),
                    onClick = actions.onAllowDecline,
                    warning = true,
                )
            }
        }
        if (usesBlockAll && device.canDeclineContacts && device.manufacturer != Manufacturer.OTHER && !device.batteryUnrestricted) {
            item {
                HintCard(
                    title = stringResource(R.string.battery_title),
                    text = stringResource(
                        if (device.manufacturer == Manufacturer.XIAOMI) R.string.battery_summary_xiaomi else R.string.battery_summary_samsung,
                    ),
                    button = stringResource(R.string.battery_button),
                    onClick = actions.onOpenBattery,
                )
            }
        }
        if (usesAllowList && device.screeningEnabled && !device.contactsGranted) {
            item {
                HintCard(
                    title = stringResource(R.string.hint_contacts_title),
                    text = stringResource(R.string.hint_contacts_text),
                    button = stringResource(R.string.hint_allow),
                    onClick = actions.onAllowContacts,
                )
            }
        }

        if (settings.enabled && usesBlockList) {
            item { FiltersCard(settings, actions.onSettingsChange) }
        }
        if (usesAllowList) {
            item {
                SectionCard {
                    SwitchRow(
                        title = stringResource(R.string.repeat_title),
                        summary = stringResource(R.string.repeat_summary),
                        checked = settings.repeatCallers,
                        onCheckedChange = { value -> actions.onSettingsChange { it.copy(repeatCallers = value) } },
                    )
                }
            }
        }
        if (settings.enabled) {
            item { WhenBlockingCard(settings, actions) }
        }
        item { ScheduleCard(settings, actions.onSettingsChange) }
        item { OtherCard(actions) }
    }

    if (confirmBlockAll) {
        AlertDialog(
            onDismissRequest = { confirmBlockAll = false },
            title = { Text(stringResource(R.string.block_all_confirm_title)) },
            text = { Text(stringResource(R.string.block_all_confirm_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmBlockAll = false
                    actions.onChooseMode(Mode.BLOCK_ALL)
                }) { Text(stringResource(R.string.block_all_confirm_button)) }
            },
            dismissButton = { TextButton(onClick = { confirmBlockAll = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun StatusCard(settings: Settings, now: LocalDateTime) {
    val mode = settings.effectiveMode(now)
    val scheduled = settings.enabled && settings.schedule.isActive(now)
    HintCard(
        title = if (mode == Mode.OFF) stringResource(R.string.status_off) else stringResource(R.string.status_on, stringResource(modeName(mode))),
        text = when {
            scheduled -> stringResource(R.string.status_schedule, timeText(settings.schedule.endMinute))
            mode == Mode.OFF -> stringResource(R.string.status_off_text)
            else -> stringResource(modeSummary(mode))
        },
        button = null,
        onClick = {},
    )
}

@Composable
private fun ModeRow(mode: Mode, current: Mode, onClick: () -> Unit) {
    RadioRow(
        title = stringResource(modeName(mode)),
        summary = stringResource(modeSummary(mode)),
        selected = mode == current,
        onClick = onClick,
        titleColor = if (mode == Mode.BLOCK_ALL) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun FiltersCard(settings: Settings, onChange: ((Settings) -> Settings) -> Unit) {
    SectionCard {
        SectionTitle(stringResource(R.string.filters_title))
        SwitchRow(
            title = stringResource(R.string.filter_telemarketing),
            summary = stringResource(R.string.filter_telemarketing_summary),
            checked = settings.blockTelemarketing,
            onCheckedChange = { value -> onChange { it.copy(blockTelemarketing = value) } },
        )
        SwitchRow(
            title = stringResource(R.string.filter_hidden),
            summary = stringResource(R.string.filter_hidden_summary),
            checked = settings.blockHidden,
            onCheckedChange = { value -> onChange { it.copy(blockHidden = value) } },
        )
        SwitchRow(
            title = stringResource(R.string.filter_international),
            summary = stringResource(R.string.filter_international_summary),
            checked = settings.blockInternational,
            onCheckedChange = { value -> onChange { it.copy(blockInternational = value) } },
        )
    }
}

@Composable
private fun WhenBlockingCard(settings: Settings, actions: HomeActions) {
    SectionCard {
        SectionTitle(stringResource(R.string.action_title))
        ChipRow(
            title = stringResource(R.string.action_what),
            summary = stringResource(if (settings.action == BlockAction.REJECT) R.string.action_reject_summary else R.string.action_silence_summary),
            options = BlockAction.entries,
            selected = { it == settings.action },
            label = { stringResource(if (it == BlockAction.REJECT) R.string.action_reject else R.string.action_silence) },
            onClick = { value -> actions.onSettingsChange { it.copy(action = value) } },
        )
        if (settings.action == BlockAction.REJECT) {
            SwitchRow(
                title = stringResource(R.string.keep_log_title),
                summary = stringResource(R.string.keep_log_summary),
                checked = settings.keepInCallLog,
                onCheckedChange = { value -> actions.onSettingsChange { it.copy(keepInCallLog = value) } },
            )
        }
        HorizontalDivider()
        ChipRow(
            title = stringResource(R.string.notifications_title),
            options = NotifyMode.entries,
            selected = { it == settings.notifications },
            label = {
                stringResource(
                    when (it) {
                        NotifyMode.NONE -> R.string.notify_none
                        NotifyMode.EACH -> R.string.notify_each
                        NotifyMode.DAILY_COUNT -> R.string.notify_daily
                    },
                )
            },
            onClick = actions.onChooseNotifications,
        )
    }
}

@Composable
private fun ScheduleCard(settings: Settings, onChange: ((Settings) -> Settings) -> Unit) {
    val schedule = settings.schedule
    val locale = LocalConfiguration.current.locales[0]
    var editing by remember { mutableStateOf<Boolean?>(null) } // true: start, false: end
    SectionCard {
        SwitchRow(
            title = stringResource(R.string.schedule_title),
            summary = stringResource(R.string.schedule_summary),
            checked = schedule.enabled,
            onCheckedChange = { value -> onChange { it.copy(schedule = it.schedule.copy(enabled = value)) } },
        )
        if (!schedule.enabled) return@SectionCard
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { editing = true }, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.schedule_start, timeText(schedule.startMinute)))
            }
            OutlinedButton(onClick = { editing = false }, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.schedule_end, timeText(schedule.endMinute)))
            }
        }
        val week = listOf(DayOfWeek.SUNDAY) + DayOfWeek.entries.filter { it != DayOfWeek.SUNDAY }
        ChipRow(
            title = stringResource(R.string.schedule_days),
            options = week,
            selected = { it in schedule.days },
            label = { it.getDisplayName(TextStyle.SHORT, locale) },
            onClick = { day ->
                onChange {
                    val days = if (day in it.schedule.days) it.schedule.days - day else it.schedule.days + day
                    it.copy(schedule = it.schedule.copy(days = days))
                }
            },
        )
        ChipRow(
            title = stringResource(R.string.schedule_mode),
            options = listOf(Mode.BLOCKLIST, Mode.ALLOWLIST, Mode.BLOCK_ALL),
            selected = { it == schedule.mode },
            label = { stringResource(modeName(it)) },
            onClick = { mode -> onChange { it.copy(schedule = it.schedule.copy(mode = mode)) } },
        )
    }
    editing?.let { start ->
        TimeDialog(
            minute = if (start) schedule.startMinute else schedule.endMinute,
            onDismiss = { editing = null },
            onPick = { minute ->
                editing = null
                onChange { it.copy(schedule = if (start) it.schedule.copy(startMinute = minute) else it.schedule.copy(endMinute = minute)) }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeDialog(minute: Int, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    val state = rememberTimePickerState(initialHour = minute / 60, initialMinute = minute % 60, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onPick(state.hour * 60 + state.minute) }) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        text = { TimePicker(state = state) },
    )
}

@Composable
private fun OtherCard(actions: HomeActions) {
    SectionCard {
        Column(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = actions.onOpenSystemBlocked)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(stringResource(R.string.system_blocked_title), style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(R.string.system_blocked_summary),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalDivider()
        SectionTitle(stringResource(R.string.about_title))
        Text(
            stringResource(R.string.about_text, BuildConfig.VERSION_NAME),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        TextButton(onClick = actions.onOpenSource, modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) {
            Text(stringResource(R.string.about_source))
        }
    }
}

fun modeName(mode: Mode): Int = when (mode) {
    Mode.OFF -> R.string.mode_off
    Mode.BLOCKLIST -> R.string.mode_blocklist
    Mode.ALLOWLIST -> R.string.mode_allowlist
    Mode.BLOCK_ALL -> R.string.mode_block_all
}

private fun modeSummary(mode: Mode): Int = when (mode) {
    Mode.OFF -> R.string.mode_off_summary
    Mode.BLOCKLIST -> R.string.mode_blocklist_summary
    Mode.ALLOWLIST -> R.string.mode_allowlist_summary
    Mode.BLOCK_ALL -> R.string.mode_block_all_summary
}

fun timeText(minute: Int): String = String.format(Locale.ROOT, "%02d:%02d", minute / 60, minute % 60)
