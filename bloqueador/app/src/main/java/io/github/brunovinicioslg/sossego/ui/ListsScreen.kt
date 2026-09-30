package io.github.brunovinicioslg.sossego.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.brunovinicioslg.sossego.R
import io.github.brunovinicioslg.sossego.core.lists.ListEntry
import io.github.brunovinicioslg.sossego.core.lists.ListKind
import io.github.brunovinicioslg.sossego.core.lists.Match
import io.github.brunovinicioslg.sossego.core.number.PhoneNumbers
import io.github.brunovinicioslg.sossego.device.PickedContact

const val LISTS_TAG = "lists"

class ListsActions(
    /** Saves a new entry ([replacing] null) or a change; [fromContacts] when picked there. */
    val onSave: (entry: ListEntry, replacing: ListEntry?, fromContacts: Boolean) -> Unit,
    val onDelete: (ListEntry) -> Unit,
    val readPickedContact: (Uri) -> PickedContact?,
    val onOpenSystemBlocked: () -> Unit,
)

@Composable
fun ListsScreen(entries: List<ListEntry>, actions: ListsActions, contentPadding: PaddingValues) {
    var tab by rememberSaveable { mutableStateOf(ListKind.BLOCK) }
    var editing by rememberSaveable { mutableStateOf<EditState?>(null) }
    var picked by remember { mutableStateOf<PickedContact?>(null) }
    var pickFailed by remember { mutableStateOf(false) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        picked = result.data?.data?.let(actions.readPickedContact)
    }
    val shown = entries.filter { it.list == tab }

    Box(Modifier.fillMaxSize().padding(top = contentPadding.calculateTopPadding())) {
        Column(Modifier.fillMaxSize()) {
            PrimaryTabRow(selectedTabIndex = tab.ordinal) {
                ListKind.entries.forEach { kind ->
                    Tab(
                        selected = tab == kind,
                        onClick = { tab = kind },
                        text = { Text(stringResource(listName(kind), entries.count { it.list == kind })) },
                    )
                }
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize().testTag(LISTS_TAG),
                contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 88.dp),
            ) {
                if (tab == ListKind.BLOCK) {
                    item {
                        Box(Modifier.padding(16.dp)) {
                            HintCard(
                                title = stringResource(R.string.list_contacts_title),
                                text = stringResource(R.string.list_contacts_note),
                                button = stringResource(R.string.contact_warning_open),
                                onClick = actions.onOpenSystemBlocked,
                            )
                        }
                    }
                }
                if (shown.isEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.list_empty),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(24.dp),
                        )
                    }
                }
                items(shown, key = { it.id }) { entry ->
                    EntryRow(entry, onEdit = { editing = EditState.of(entry) }, onDelete = { actions.onDelete(entry) })
                    HorizontalDivider()
                }
            }
        }
        FloatingActionButton(
            onClick = { editing = EditState(tab) },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = contentPadding.calculateBottomPadding() + 16.dp),
        ) {
            Icon(painterResource(R.drawable.ic_add), contentDescription = stringResource(R.string.list_add))
        }
    }

    editing?.let { state ->
        EntryDialog(
            initial = state,
            picked = picked,
            pickFailed = pickFailed,
            onPickContact = {
                pickFailed = try {
                    pick.launch(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI))
                    false
                } catch (e: ActivityNotFoundException) {
                    true
                }
            },
            onPickUsed = { picked = null },
            onDismiss = {
                editing = null
                picked = null
                pickFailed = false
            },
            onSave = { entry, fromContacts ->
                val replacing = state.id?.let { id -> entries.firstOrNull { it.id == id } }
                editing = null
                pickFailed = false
                actions.onSave(entry, replacing, fromContacts)
            },
        )
    }
}

@Composable
private fun EntryRow(entry: ListEntry, onEdit: () -> Unit, onDelete: () -> Unit) {
    val number = entryText(entry)
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onEdit).padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(number, style = MaterialTheme.typography.bodyLarge)
            if (entry.label.isNotEmpty()) {
                Text(entry.label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        IconButton(onClick = onDelete) {
            Icon(painterResource(R.drawable.ic_delete), contentDescription = stringResource(R.string.list_delete, number))
        }
    }
}

/** The dialog's content, kept across rotations. */
data class EditState(
    val list: ListKind,
    val id: Long? = null,
    val number: String = "",
    val prefix: Boolean = false,
    val label: String = "",
) : java.io.Serializable {
    companion object {
        fun of(entry: ListEntry) = EditState(
            list = entry.list,
            id = entry.id,
            number = if (entry.match == Match.EXACT) PhoneNumbers.format(entry.pattern) else entry.pattern,
            prefix = entry.match == Match.PREFIX,
            label = entry.label,
        )
    }
}

@Composable
private fun EntryDialog(
    initial: EditState,
    picked: PickedContact?,
    pickFailed: Boolean,
    onPickContact: () -> Unit,
    onPickUsed: () -> Unit,
    onDismiss: () -> Unit,
    onSave: (ListEntry, fromContacts: Boolean) -> Unit,
) {
    var number by rememberSaveable { mutableStateOf(initial.number) }
    var prefix by rememberSaveable { mutableStateOf(initial.prefix) }
    var label by rememberSaveable { mutableStateOf(initial.label) }
    var fromContacts by rememberSaveable { mutableStateOf(false) }
    var invalid by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(picked) {
        val contact = picked ?: return@LaunchedEffect
        number = contact.number
        if (label.isBlank()) label = contact.name
        prefix = false
        fromContacts = true
        invalid = false
        onPickUsed()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (initial.id == null) addTitle(initial.list) else R.string.list_edit_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = number,
                    onValueChange = {
                        number = it
                        invalid = false
                        fromContacts = false
                    },
                    label = { Text(stringResource(R.string.entry_number)) },
                    placeholder = { Text(stringResource(if (prefix) R.string.entry_prefix_hint else R.string.entry_number_hint)) },
                    isError = invalid,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (invalid) {
                    Text(stringResource(R.string.entry_invalid), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .toggleable(value = prefix, role = Role.Checkbox, onValueChange = { prefix = it }),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = prefix, onCheckedChange = null)
                    Text(stringResource(R.string.entry_prefix), modifier = Modifier.padding(start = 8.dp))
                }
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text(stringResource(R.string.entry_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (initial.id == null) {
                    TextButton(onClick = onPickContact) { Text(stringResource(R.string.entry_pick_contact)) }
                    if (pickFailed) Text(stringResource(R.string.entry_pick_failed), color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val entry = ListEntry.of(initial.list, if (prefix) Match.PREFIX else Match.EXACT, number, label)
                if (entry == null) invalid = true else onSave(entry, fromContacts)
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
fun entryText(entry: ListEntry): String =
    if (entry.match == Match.EXACT) PhoneNumbers.format(entry.pattern) else stringResource(R.string.list_prefix, entry.pattern)

private fun listName(kind: ListKind): Int = if (kind == ListKind.BLOCK) R.string.list_block_count else R.string.list_allow_count

private fun addTitle(kind: ListKind): Int = if (kind == ListKind.BLOCK) R.string.list_add_block else R.string.list_add_allow
