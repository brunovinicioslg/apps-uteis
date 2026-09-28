package io.github.brunovinicioslg.sigilo.app.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.brunovinicioslg.sigilo.app.AppContainer
import io.github.brunovinicioslg.sigilo.app.R
import io.github.brunovinicioslg.sigilo.app.appContainer
import io.github.brunovinicioslg.sigilo.app.db.Conversation
import io.github.brunovinicioslg.sigilo.app.db.Encryption
import io.github.brunovinicioslg.sigilo.app.sms.DefaultSmsApp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationListScreen(onOpen: (Long) -> Unit, onNew: () -> Unit, onSettings: () -> Unit) {
    val context = LocalContext.current
    val container = context.appContainer
    val conversations by rememberEngineQuery(emptyList<Conversation>()) { it.conversations() }
    val names = rememberContactNames()

    // Checked again every time the screen comes back, e.g. after the system's "default app" dialog.
    var isDefault by remember { mutableStateOf(DefaultSmsApp.isDefault(context)) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { isDefault = DefaultSmsApp.isDefault(context) }
    }
    val becomeDefault = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        isDefault = DefaultSmsApp.isDefault(context)
        if (isDefault) container.afterUnlock() // brings in the SMS history
    }
    AskForNotificationsOnce()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primary, titleContentColor = MaterialTheme.colorScheme.onPrimary),
                actions = {
                    IconButton(onClick = onSettings) {
                        Icon(painterResource(R.drawable.ic_settings), stringResource(R.string.settings), tint = MaterialTheme.colorScheme.onPrimary)
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onNew) { Icon(painterResource(R.drawable.ic_add_comment), stringResource(R.string.new_conversation)) }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            if (!isDefault) {
                item {
                    Card(
                        Modifier.fillMaxWidth().padding(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.default_title), style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(R.string.default_text), style = MaterialTheme.typography.bodyMedium)
                            Button(onClick = { becomeDefault.launch(DefaultSmsApp.requestIntent(context)) }) { Text(stringResource(R.string.default_button)) }
                        }
                    }
                }
            }
            if (conversations.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.no_conversations), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            items(conversations, key = { it.id }) { conversation ->
                ConversationRow(conversation, names.nameOf(conversation.address), onClick = { onOpen(conversation.id) })
                HorizontalDivider(Modifier.padding(start = 76.dp))
            }
        }
    }
}

@Composable
private fun ConversationRow(conversation: Conversation, name: String?, onClick: () -> Unit) {
    val title = name ?: conversation.address
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(title, conversation.address)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    fontWeight = if (conversation.unread > 0) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.weight(1f, fill = false),
                )
                if (conversation.encryption == Encryption.ACTIVE) {
                    Spacer(Modifier.width(4.dp))
                    Icon(painterResource(R.drawable.ic_lock), stringResource(R.string.encrypted), Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                }
            }
            Text(
                eventText(conversation.snippetKind, conversation.snippet.orEmpty()),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                shortTime(conversation.lastAt), style = MaterialTheme.typography.labelSmall,
                color = if (conversation.unread > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (conversation.unread > 0) {
                val description = stringResource(R.string.unread_count_description, conversation.unread)
                Badge(Modifier.semantics { contentDescription = description }) { Text(conversation.unread.toString()) }
            }
        }
    }
}

/** Android 13+: message alerts need permission; asked once, on the first visit to the list. */
@Composable
private fun AskForNotificationsOnce() {
    if (!AppContainer.needsNotificationPermission) return
    var asked by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (!asked) {
            asked = true
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
