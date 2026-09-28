package io.github.brunovinicioslg.sigilo.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.brunovinicioslg.sigilo.app.R
import io.github.brunovinicioslg.sigilo.app.appContainer
import io.github.brunovinicioslg.sigilo.app.db.Conversation
import io.github.brunovinicioslg.sigilo.app.db.Encryption
import io.github.brunovinicioslg.sigilo.app.db.Message
import io.github.brunovinicioslg.sigilo.app.db.MessageKind
import io.github.brunovinicioslg.sigilo.app.db.MessageStatus
import io.github.brunovinicioslg.sigilo.app.sms.DefaultSmsApp
import io.github.brunovinicioslg.sigilo.app.ui.theme.LocalBubbles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed interface ChatItem {
    data class Day(val at: Long) : ChatItem

    data class Item(val message: Message) : ChatItem
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationScreen(conversationId: Long, initialDraft: String?, onBack: () -> Unit, onSafetyNumber: () -> Unit) {
    val context = LocalContext.current
    val container = context.appContainer
    val scope = rememberCoroutineScope()
    val names = rememberContactNames()
    val conversation by rememberEngineQuery<Conversation?>(null, conversationId) { it.conversation(conversationId) }
    val messages by rememberEngineQuery(emptyList<Message>(), conversationId) { it.messages(conversationId) }
    var draft by rememberSaveable(conversationId) { mutableStateOf(initialDraft.orEmpty()) }
    var estimate by remember { mutableIntStateOf(1) }
    var blockedTick by remember { mutableIntStateOf(0) }
    var menuOpen by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<Message?>(null) }

    fun engine(action: (io.github.brunovinicioslg.sigilo.app.engine.MessageEngine) -> Unit) {
        scope.launch { container.lock.withEngine(action) }
    }

    val current = conversation ?: return
    val title = names.nameOf(current.address) ?: current.address
    val blocked by produceState(false, current.address, blockedTick) {
        value = withContext(Dispatchers.IO) { DefaultSmsApp.isBlocked(context, current.address) }
    }

    // Reading the conversation marks it read, and starts the timers of received disappearing messages.
    LaunchedEffect(messages) {
        if (messages.any { !it.read }) {
            container.notifier.cancel(conversationId)
            container.lock.withEngine { it.markRead(conversationId) }
        }
    }
    // Disappearing messages leave the screen on time, even before the database deletes them.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val nextExpiry = messages.mapNotNull { it.expireAt }.minOrNull()
    LaunchedEffect(nextExpiry) {
        while (nextExpiry != null && System.currentTimeMillis() < nextExpiry) delay(500)
        now = System.currentTimeMillis()
    }
    LaunchedEffect(draft, current.encryption, current.expireSeconds) {
        delay(150)
        estimate = if (draft.isEmpty()) 0 else container.lock.withEngine { it.estimateSms(conversationId, draft) } ?: 0
    }

    val visible = messages.filter { it.expireAt == null || it.expireAt > now }
    val items = remember(visible) {
        buildList {
            var lastDay: Long? = null
            for (m in visible) {
                if (lastDay == null || !sameDay(lastDay, m.receivedAt)) add(ChatItem.Day(m.receivedAt))
                lastDay = m.receivedAt
                add(ChatItem.Item(m))
            }
        }.asReversed()
    }
    val encrypted = current.encryption == Encryption.ACTIVE

    // New messages come into view: always the ones the user sends, and incoming ones when the
    // user is reading the end of the conversation (not while scrolled back through history).
    val listState = rememberLazyListState()
    val newest = visible.lastOrNull()
    LaunchedEffect(newest?.id) {
        if (newest != null && (newest.outgoing || listState.firstVisibleItemIndex <= 2)) listState.animateScrollToItem(0)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.back), tint = MaterialTheme.colorScheme.onPrimary) }
                },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar(title, current.address, size = 36.dp)
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                            if (encrypted) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(painterResource(R.drawable.ic_lock), null, Modifier.size(12.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(stringResource(R.string.encrypted), style = MaterialTheme.typography.labelSmall)
                                }
                            } else if (title != current.address) {
                                Text(current.address, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(painterResource(R.drawable.ic_more_vert), stringResource(R.string.more), tint = MaterialTheme.colorScheme.onPrimary)
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        when (current.encryption) {
                            Encryption.NONE -> DropdownMenuItem(text = { Text(stringResource(R.string.menu_encrypt)) }, onClick = { menuOpen = false; dialog = DIALOG_ENCRYPT })
                            Encryption.INVITE_SENT -> DropdownMenuItem(text = { Text(stringResource(R.string.menu_resend_invite)) }, onClick = { menuOpen = false; dialog = DIALOG_ENCRYPT })
                            else -> Unit
                        }
                        if (encrypted || current.keyChanged) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.menu_safety)) }, onClick = { menuOpen = false; onSafetyNumber() })
                        }
                        if (encrypted) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.menu_timer)) }, onClick = { menuOpen = false; dialog = DIALOG_TIMER })
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(if (blocked) R.string.menu_unblock else R.string.menu_block)) },
                            onClick = {
                                menuOpen = false
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        if (blocked) DefaultSmsApp.unblock(context, current.address) else DefaultSmsApp.block(context, current.address)
                                    }
                                    blockedTick++
                                }
                            },
                        )
                        DropdownMenuItem(text = { Text(stringResource(R.string.menu_delete_conversation)) }, onClick = { menuOpen = false; dialog = DIALOG_DELETE })
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primary, titleContentColor = MaterialTheme.colorScheme.onPrimary),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).background(LocalBubbles.current.chatBackground).imePadding()) {
            Banners(
                conversation = current,
                blocked = blocked,
                onAcceptInvite = { engine { it.acceptInvite(conversationId) } },
                onDeclineInvite = { engine { it.declineInvite(conversationId) } },
                onAcceptKey = { engine { it.acceptNewKey(conversationId) } },
                onSafetyNumber = onSafetyNumber,
            )
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, reverseLayout = true) {
                items(items, key = { if (it is ChatItem.Item) "m${it.message.id}" else "d${(it as ChatItem.Day).at}" }) { item ->
                    when (item) {
                        is ChatItem.Day -> EventChip(dayLabel(item.at))
                        is ChatItem.Item -> if (item.message.kind == MessageKind.TEXT) {
                            Bubble(item.message, onLongPress = { deleting = item.message })
                        } else {
                            EventChip(eventText(item.message.kind, item.message.body))
                        }
                    }
                }
            }
            Composer(
                draft = draft,
                onDraft = { draft = it },
                encrypted = encrypted,
                estimate = estimate,
                onSend = {
                    val text = draft.trim()
                    if (text.isNotEmpty()) {
                        draft = ""
                        engine { it.sendText(conversationId, text) }
                    }
                },
            )
        }
    }

    when (dialog) {
        DIALOG_ENCRYPT -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(stringResource(R.string.encrypt_confirm_title)) },
            text = { Text(stringResource(R.string.encrypt_confirm_text)) },
            confirmButton = { TextButton(onClick = { dialog = null; engine { it.startEncryption(conversationId) } }) { Text(stringResource(R.string.menu_encrypt)) } },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.cancel)) } },
        )
        DIALOG_TIMER -> TimerDialog(current.expireSeconds, onDismiss = { dialog = null }, onChoose = { seconds ->
            dialog = null
            engine { it.setTimer(conversationId, seconds) }
        })
        DIALOG_DELETE -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(stringResource(R.string.delete_conversation_title)) },
            text = { Text(stringResource(R.string.delete_conversation_text)) },
            confirmButton = {
                TextButton(onClick = {
                    dialog = null
                    scope.launch {
                        container.lock.withEngine { it.deleteConversation(conversationId) }
                        onBack()
                    }
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    deleting?.let { message ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.delete_message_title)) },
            text = { Text(message.body, maxLines = 4, overflow = TextOverflow.Ellipsis) },
            confirmButton = { TextButton(onClick = { deleting = null; engine { it.deleteMessage(message.id) } }) { Text(stringResource(R.string.delete)) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

private const val DIALOG_ENCRYPT = "encrypt"
private const val DIALOG_TIMER = "timer"
private const val DIALOG_DELETE = "delete"

@Composable
private fun Banners(
    conversation: Conversation,
    blocked: Boolean,
    onAcceptInvite: () -> Unit,
    onDeclineInvite: () -> Unit,
    onAcceptKey: () -> Unit,
    onSafetyNumber: () -> Unit,
) {
    @Composable
    fun Banner(text: String, error: Boolean = false, actions: @Composable () -> Unit = {}) {
        Card(
            Modifier.fillMaxWidth().padding(8.dp),
            colors = CardDefaults.cardColors(containerColor = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer),
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(text, style = MaterialTheme.typography.bodyMedium)
                Row(Modifier.align(Alignment.End)) { actions() }
            }
        }
    }
    if (conversation.keyChanged) {
        Banner(stringResource(R.string.key_changed_banner), error = true) {
            TextButton(onClick = onSafetyNumber) { Text(stringResource(R.string.menu_safety)) }
            TextButton(onClick = onAcceptKey) { Text(stringResource(R.string.key_changed_accept)) }
        }
    }
    when (conversation.encryption) {
        Encryption.INVITE_RECEIVED -> Banner(stringResource(R.string.invite_banner)) {
            TextButton(onClick = onDeclineInvite) { Text(stringResource(R.string.decline)) }
            TextButton(onClick = onAcceptInvite) { Text(stringResource(R.string.accept)) }
        }
        Encryption.INVITE_SENT -> Banner(stringResource(R.string.invite_sent_banner))
        else -> Unit
    }
    if (blocked) Banner(stringResource(R.string.blocked_banner), error = true)
}

@Composable
private fun EventChip(text: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp, horizontal = 24.dp), horizontalArrangement = Arrangement.Center) {
        Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
            Text(
                text, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun Bubble(message: Message, onLongPress: () -> Unit) {
    val colors = LocalBubbles.current
    val outgoing = message.outgoing
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
        horizontalArrangement = if (outgoing) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            shape = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp, bottomStart = if (outgoing) 14.dp else 2.dp, bottomEnd = if (outgoing) 2.dp else 14.dp),
            color = if (outgoing) colors.outgoing else colors.incoming,
            contentColor = if (outgoing) colors.onOutgoing else colors.onIncoming,
            shadowElevation = 1.dp,
            modifier = Modifier.widthIn(max = 300.dp).pointerInput(message.id) { detectTapGestures(onLongPress = { onLongPress() }) },
        ) {
            Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                Text(message.body, style = MaterialTheme.typography.bodyLarge)
                Row(Modifier.align(Alignment.End), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    if (message.encrypted) Icon(painterResource(R.drawable.ic_lock), stringResource(R.string.encrypted), Modifier.size(11.dp))
                    if (message.expireSeconds > 0) Icon(painterResource(R.drawable.ic_timer), null, Modifier.size(11.dp))
                    Text(clockTime(message.receivedAt), style = MaterialTheme.typography.labelSmall)
                    if (outgoing) StatusIcon(message.status)
                }
            }
        }
    }
}

@Composable
private fun StatusIcon(status: MessageStatus) {
    val (icon, label) = when (status) {
        MessageStatus.PENDING -> R.drawable.ic_schedule to R.string.status_pending
        MessageStatus.SENT -> R.drawable.ic_check to R.string.status_sent
        MessageStatus.DELIVERED -> R.drawable.ic_done_all to R.string.status_delivered
        MessageStatus.FAILED -> R.drawable.ic_error to R.string.status_failed
        MessageStatus.RECEIVED -> return
    }
    Icon(
        painterResource(icon), stringResource(label), Modifier.size(14.dp),
        tint = if (status == MessageStatus.FAILED) MaterialTheme.colorScheme.error else LocalContentColor.current,
    )
}

@Composable
private fun Composer(draft: String, onDraft: (String) -> Unit, encrypted: Boolean, estimate: Int, onSend: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).navigationBarsPadding().padding(8.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            OutlinedTextField(
                value = draft,
                onValueChange = onDraft,
                placeholder = { Text(stringResource(if (encrypted) R.string.message_hint_encrypted else R.string.message_hint)) },
                maxLines = 5,
                shape = RoundedCornerShape(24.dp),
                leadingIcon = if (encrypted) ({ Icon(painterResource(R.drawable.ic_lock), null, Modifier.size(18.dp)) }) else null,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            FilledIconButton(onClick = onSend, enabled = draft.isNotBlank(), modifier = Modifier.size(52.dp)) {
                Icon(painterResource(R.drawable.ic_send), stringResource(R.string.send))
            }
        }
        if (estimate > 0) {
            Text(
                stringResource(R.string.sms_count, estimate), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp, top = 2.dp),
            )
        }
    }
}

@Composable
private fun TimerDialog(current: Int, onDismiss: () -> Unit, onChoose: (Int) -> Unit) {
    var chosen by remember { mutableIntStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.timer_title)) },
        text = {
            Column {
                Text(stringResource(R.string.timer_text), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.width(8.dp))
                for (seconds in TIMER_CHOICES) {
                    Row(
                        Modifier.fillMaxWidth().selectable(selected = chosen == seconds, role = Role.RadioButton, onClick = { chosen = seconds }).padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = chosen == seconds, onClick = null)
                        Spacer(Modifier.width(8.dp))
                        Text(timerLabel(seconds))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onChoose(chosen) }) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
