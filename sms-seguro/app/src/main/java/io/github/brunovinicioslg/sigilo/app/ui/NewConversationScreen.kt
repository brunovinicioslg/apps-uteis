package io.github.brunovinicioslg.sigilo.app.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.brunovinicioslg.sigilo.app.R
import io.github.brunovinicioslg.sigilo.app.appContainer
import io.github.brunovinicioslg.sigilo.app.contacts.ContactNames
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewConversationScreen(onBack: () -> Unit, onStart: (Long) -> Unit) {
    val context = LocalContext.current
    val container = context.appContainer
    val scope = rememberCoroutineScope()
    val names = rememberContactNames()
    var query by remember { mutableStateOf("") }
    var permissionTick by remember { mutableIntStateOf(0) }
    val askContacts = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        names.forget()
        permissionTick++
    }
    val contacts by produceState(emptyList<ContactNames.Contact>(), permissionTick) {
        value = withContext(Dispatchers.IO) { names.all() }
    }
    val matches = remember(query, contacts) {
        val q = query.trim()
        if (q.isEmpty()) contacts else contacts.filter { it.name.contains(q, ignoreCase = true) || it.number.filter(Char::isDigit).contains(q.filter(Char::isDigit).ifEmpty { "\u0000" }) }
    }
    val typedNumber = query.trim().takeIf { q -> q.count { it.isDigit() } >= 3 && q.all { it.isDigit() || it in "+-() " } }

    fun start(number: String) {
        scope.launch { container.lock.withEngine { it.conversationFor(number).id }?.let(onStart) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.new_conversation)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.back)) } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = query, onValueChange = { query = it }, label = { Text(stringResource(R.string.new_conversation_hint)) },
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            )
            LazyColumn(Modifier.weight(1f)) {
                typedNumber?.let { number ->
                    item {
                        Row(Modifier.fillMaxWidth().clickable { start(number) }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Avatar(number, number)
                            Spacer(Modifier.width(12.dp))
                            Text(stringResource(R.string.start_with_number, number), style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
                if (!names.canRead()) {
                    item {
                        OutlinedButton(onClick = { askContacts.launch(Manifest.permission.READ_CONTACTS) }, modifier = Modifier.padding(16.dp)) {
                            Text(stringResource(R.string.contacts_permission))
                        }
                    }
                }
                items(matches, key = { it.name + it.number }) { contact ->
                    Row(Modifier.fillMaxWidth().clickable { start(contact.number) }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(contact.name, contact.number)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(contact.name, style = MaterialTheme.typography.titleMedium)
                            Text(contact.number, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}
