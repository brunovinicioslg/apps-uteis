package io.github.brunovinicioslg.sossego.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.brunovinicioslg.sossego.R
import io.github.brunovinicioslg.sossego.core.dial.Contact
import io.github.brunovinicioslg.sossego.core.dial.ContactSearch

const val CONTACTS_TAG = "contacts"

class ContactsActions(
    val onCall: (String) -> Unit,
    val onBlock: (String) -> Unit,
    val onOpenContact: (Long) -> Unit,
    val onAllowContacts: () -> Unit,
)

@Composable
fun ContactsScreen(contacts: List<Contact>?, actions: ContactsActions, contentPadding: PaddingValues) {
    var query by rememberSaveable { mutableStateOf("") }
    var open by rememberSaveable { mutableStateOf<Long?>(null) }
    val all = contacts.orEmpty()
    val found = remember(all, query) { ContactSearch.byText(all, query) }
    val favorites = if (query.isBlank()) found.filter { it.starred } else emptyList()

    Column(Modifier.fillMaxSize().padding(top = contentPadding.calculateTopPadding())) {
        if (contacts == null) {
            Box(Modifier.padding(16.dp)) {
                HintCard(
                    title = stringResource(R.string.contacts_permission_title),
                    text = stringResource(R.string.contacts_permission_text),
                    button = stringResource(R.string.hint_allow),
                    onClick = actions.onAllowContacts,
                )
            }
            return@Column
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text(stringResource(R.string.contacts_search)) },
            leadingIcon = { Icon(painterResource(R.drawable.ic_search), contentDescription = null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag(CONTACTS_TAG),
            contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 16.dp),
        ) {
            if (found.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.contacts_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            }
            if (favorites.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.contacts_favorites)) }
                items(favorites, key = { "fav-${it.id}" }) { ContactRow(it) { open = it.id } }
                item { SectionHeader(stringResource(R.string.contacts_all)) }
            }
            items(found, key = { it.id }) { ContactRow(it) { open = it.id } }
        }
    }

    // A contact deleted meanwhile just closes its dialog.
    open?.let { id -> all.firstOrNull { it.id == id } }?.let { contact ->
        ContactDialog(contact, actions, onDismiss = { open = null })
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun ContactRow(contact: Contact, onClick: () -> Unit) {
    val phone = contact.phones.first()
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(contact.name)
        Column(Modifier.padding(start = 16.dp)) {
            Text(contact.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                listOf(phone.label, displayNumber(phone.number)).filter { it.isNotEmpty() }.joinToString(" · ") +
                    if (contact.phones.size > 1) " · +${contact.phones.size - 1}" else "",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Avatar(name: String) {
    Box(
        Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "#",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

@Composable
private fun ContactDialog(contact: Contact, actions: ContactsActions, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(contact.name) },
        text = {
            Column {
                contact.phones.forEach { phone ->
                    val number = displayNumber(phone.number)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(number, style = MaterialTheme.typography.bodyLarge)
                            if (phone.label.isNotEmpty()) {
                                Text(phone.label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        IconButton(onClick = {
                            onDismiss()
                            actions.onBlock(phone.number)
                        }) {
                            Icon(painterResource(R.drawable.ic_shield), contentDescription = stringResource(R.string.contact_block, number))
                        }
                        IconButton(onClick = {
                            onDismiss()
                            actions.onCall(phone.number)
                        }) {
                            Icon(painterResource(R.drawable.ic_call), contentDescription = stringResource(R.string.call_to, number), tint = CallGreen)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) } },
        dismissButton = {
            TextButton(onClick = {
                onDismiss()
                actions.onOpenContact(contact.id)
            }) { Text(stringResource(R.string.contacts_open)) }
        },
    )
}
