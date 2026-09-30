package io.github.brunovinicioslg.sossego.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.brunovinicioslg.sossego.R
import io.github.brunovinicioslg.sossego.core.dial.CallKind
import io.github.brunovinicioslg.sossego.core.dial.RecentCall
import io.github.brunovinicioslg.sossego.core.dial.Recents
import io.github.brunovinicioslg.sossego.core.dial.RecentsFilter
import io.github.brunovinicioslg.sossego.core.rules.Reason
import io.github.brunovinicioslg.sossego.text.reasonText
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

const val RECENTS_TAG = "recents"

class RecentsActions(
    val onCall: (String) -> Unit,
    val onAllow: (RecentCall) -> Unit,
    val onBlock: (RecentCall) -> Unit,
    val onCopy: (RecentCall) -> Unit,
    /** Deletes a block from this app's history (the phone's own log is the phone app's). */
    val onDeleteBlock: (RecentCall) -> Unit,
    val onAllowCallLog: () -> Unit,
)

@Composable
fun RecentsScreen(
    calls: List<RecentCall>,
    callLogShown: Boolean,
    filter: RecentsFilter,
    onFilter: (RecentsFilter) -> Unit,
    actions: RecentsActions,
    contentPadding: PaddingValues,
    zone: ZoneId = ZoneId.systemDefault(),
) {
    val shown = remember(calls, filter) { Recents.filter(calls, filter) }
    val today = LocalDate.now(zone)
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag(RECENTS_TAG),
        contentPadding = PaddingValues(top = contentPadding.calculateTopPadding(), bottom = contentPadding.calculateBottomPadding() + 16.dp),
    ) {
        if (!callLogShown) {
            item {
                Box(Modifier.padding(16.dp)) {
                    HintCard(
                        title = stringResource(R.string.recents_permission_title),
                        text = stringResource(R.string.recents_permission_text),
                        button = stringResource(R.string.hint_allow),
                        onClick = actions.onAllowCallLog,
                    )
                }
            }
        }
        item {
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RecentsFilter.entries.forEach { option ->
                    FilterChip(selected = option == filter, onClick = { onFilter(option) }, label = { Text(stringResource(filterName(option))) })
                }
            }
        }
        if (shown.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.recents_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
            }
        }
        items(shown, key = { "${it.logId}-${it.blockId}-${it.time}" }) { call ->
            RecentRow(call, today, zone, actions)
            HorizontalDivider()
        }
    }
}

@Composable
private fun RecentRow(call: RecentCall, today: LocalDate, zone: ZoneId, actions: RecentsActions) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    val time = Instant.ofEpochMilli(call.time).atZone(zone)
    val whenText = time.format(if (time.toLocalDate() == today) TIME else DAY_TIME)
    val caller = call.name ?: callerName(call)
    val details = buildList {
        add(stringResource(kindName(call.kind)) + (call.reason?.let { " · " + reasonText(context, it) } ?: ""))
        if (call.silenced) add(stringResource(R.string.history_silenced))
        add(whenText)
        durationText(call.durationSeconds)?.let(::add)
        call.sim?.let(::add)
    }.joinToString(" · ")
    val missed = call.kind == CallKind.MISSED || call.kind == CallKind.REJECTED || call.kind == CallKind.BLOCKED
    Box {
        Row(
            Modifier.fillMaxWidth().clickable { menu = true }.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painterResource(kindIcon(call.kind)),
                contentDescription = null,
                tint = if (missed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(Modifier.weight(1f).padding(start = 16.dp)) {
                Text(caller, style = MaterialTheme.typography.bodyLarge)
                Text(details, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (call.number.isNotEmpty()) {
                IconButton(onClick = { actions.onCall(call.number) }) {
                    Icon(painterResource(R.drawable.ic_call), contentDescription = stringResource(R.string.call_to, caller), tint = CallGreen)
                }
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            if (call.number.isNotEmpty()) {
                DropdownMenuItem(text = { Text(stringResource(R.string.dialpad_call)) }, onClick = {
                    menu = false
                    actions.onCall(call.number)
                })
                if (call.reason != null) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.history_allow)) }, onClick = {
                        menu = false
                        actions.onAllow(call)
                    })
                }
                if (call.reason != Reason.BLOCK_LISTED) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.history_block)) }, onClick = {
                        menu = false
                        actions.onBlock(call)
                    })
                }
                DropdownMenuItem(text = { Text(stringResource(R.string.history_copy)) }, onClick = {
                    menu = false
                    actions.onCopy(call)
                })
            }
            if (call.blockId != null) {
                DropdownMenuItem(text = { Text(stringResource(R.string.history_delete)) }, onClick = {
                    menu = false
                    actions.onDeleteBlock(call)
                })
            }
        }
    }
}

/** A caller without a contact name: the number, "hidden number", or "a contact" whose number the app never got. */
@Composable
private fun callerName(call: RecentCall): String = when {
    call.number.isNotEmpty() -> displayNumber(call.number)
    call.reason == Reason.BLOCK_ALL -> stringResource(R.string.caller_contact)
    else -> stringResource(R.string.caller_hidden)
}

@Composable
private fun durationText(seconds: Long): String? = when {
    seconds <= 0 -> null
    seconds < 60 -> stringResource(R.string.duration_seconds, seconds.toInt())
    else -> stringResource(R.string.duration_minutes, (seconds / 60).toInt())
}

private fun filterName(filter: RecentsFilter): Int = when (filter) {
    RecentsFilter.ALL -> R.string.filter_all
    RecentsFilter.MISSED -> R.string.filter_missed
    RecentsFilter.INCOMING -> R.string.filter_incoming
    RecentsFilter.OUTGOING -> R.string.filter_outgoing
    RecentsFilter.BLOCKED -> R.string.filter_blocked
}

private fun kindName(kind: CallKind): Int = when (kind) {
    CallKind.INCOMING -> R.string.kind_incoming
    CallKind.OUTGOING -> R.string.kind_outgoing
    CallKind.MISSED -> R.string.kind_missed
    CallKind.REJECTED -> R.string.kind_rejected
    CallKind.BLOCKED -> R.string.kind_blocked
    CallKind.VOICEMAIL -> R.string.kind_voicemail
    CallKind.OTHER -> R.string.kind_other
}

private fun kindIcon(kind: CallKind): Int = when (kind) {
    CallKind.INCOMING -> R.drawable.ic_call_received
    CallKind.OUTGOING -> R.drawable.ic_call_made
    CallKind.MISSED, CallKind.REJECTED -> R.drawable.ic_call_missed
    CallKind.BLOCKED -> R.drawable.ic_shield
    CallKind.VOICEMAIL -> R.drawable.ic_voicemail
    CallKind.OTHER -> R.drawable.ic_call
}

private val TIME = DateTimeFormatter.ofPattern("HH:mm")
private val DAY_TIME = DateTimeFormatter.ofPattern("dd/MM HH:mm")
