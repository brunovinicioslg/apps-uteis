package io.github.brunovinicioslg.sossego.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import io.github.brunovinicioslg.sossego.core.rules.Reason
import io.github.brunovinicioslg.sossego.data.BlockedCall
import io.github.brunovinicioslg.sossego.text.callerText
import io.github.brunovinicioslg.sossego.text.reasonText
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

const val HISTORY_TAG = "history"

class HistoryActions(
    val onAllow: (BlockedCall) -> Unit,
    val onBlock: (BlockedCall) -> Unit,
    val onCopy: (BlockedCall) -> Unit,
    val onDelete: (BlockedCall) -> Unit,
)

@Composable
fun HistoryScreen(calls: List<BlockedCall>, actions: HistoryActions, contentPadding: PaddingValues, zone: ZoneId = ZoneId.systemDefault()) {
    if (calls.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(contentPadding), contentAlignment = Alignment.Center) {
            Text(
                stringResource(R.string.history_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(24.dp),
            )
        }
        return
    }
    val today = LocalDate.now(zone)
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag(HISTORY_TAG),
        contentPadding = PaddingValues(top = contentPadding.calculateTopPadding(), bottom = contentPadding.calculateBottomPadding() + 16.dp),
    ) {
        items(calls, key = { it.id }) { call ->
            CallRow(call, today, zone, actions)
            HorizontalDivider()
        }
    }
}

@Composable
private fun CallRow(call: BlockedCall, today: LocalDate, zone: ZoneId, actions: HistoryActions) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    val time = Instant.ofEpochMilli(call.time).atZone(zone)
    val whenText = time.format(if (time.toLocalDate() == today) TIME else DAY_TIME)
    val reason = reasonText(context, call.reason) + if (call.silenced) " · " + stringResource(R.string.history_silenced) else ""
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(callerText(context, call.key, call.reason), style = MaterialTheme.typography.bodyLarge)
            Text("$whenText · $reason", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(painterResource(R.drawable.ic_more_vert), contentDescription = stringResource(R.string.menu_more))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (call.key.isNotEmpty()) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.history_allow)) }, onClick = {
                        menu = false
                        actions.onAllow(call)
                    })
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
                DropdownMenuItem(text = { Text(stringResource(R.string.history_delete)) }, onClick = {
                    menu = false
                    actions.onDelete(call)
                })
            }
        }
    }
}

private val TIME = DateTimeFormatter.ofPattern("HH:mm")
private val DAY_TIME = DateTimeFormatter.ofPattern("dd/MM HH:mm")
