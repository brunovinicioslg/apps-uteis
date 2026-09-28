package io.github.brunovinicioslg.sigilo.app.ui

import android.content.ActivityNotFoundException
import android.telecom.TelecomManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.brunovinicioslg.sigilo.app.AppSettings
import io.github.brunovinicioslg.sigilo.app.BuildConfig
import io.github.brunovinicioslg.sigilo.app.R
import io.github.brunovinicioslg.sigilo.app.appContainer
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Page(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.back)) } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) { content() }
    }
}

/** The 60-digit number both phones show; equal digits mean nobody is in the middle. */
@Composable
fun SafetyNumberScreen(conversationId: Long, onBack: () -> Unit) {
    val container = LocalContext.current.appContainer
    val scope = rememberCoroutineScope()
    val number by rememberEngineQuery<String?>(null, conversationId) { it.safetyNumber(conversationId) }
    val verified by rememberEngineQuery(false, conversationId) { it.isVerified(conversationId) }
    Page(stringResource(R.string.safety_title), onBack) {
        val digits = number
        if (digits == null) {
            Text(stringResource(R.string.safety_none), style = MaterialTheme.typography.bodyLarge)
            return@Page
        }
        Text(stringResource(R.string.safety_text), style = MaterialTheme.typography.bodyLarge)
        // 12 groups of 5 digits, 3 per line, as Signal shows them.
        val groups = digits.filter(Char::isDigit).chunked(5)
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (line in groups.chunked(3)) {
                Text(line.joinToString("   "), style = MaterialTheme.typography.headlineSmall, fontFamily = FontFamily.Monospace)
            }
        }
        if (verified) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_check), null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.safety_verified), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            }
            OutlinedButton(onClick = { scope.launch { container.lock.withEngine { it.setVerified(conversationId, false) } } }) {
                Text(stringResource(R.string.safety_unmark))
            }
        } else {
            Button(onClick = { scope.launch { container.lock.withEngine { it.setVerified(conversationId, true) } } }) {
                Text(stringResource(R.string.safety_mark_verified))
            }
        }
    }
}

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val container = context.appContainer
    val scope = rememberCoroutineScope()
    var hasPassword by remember { mutableStateOf(container.lock.hasPassword()) }
    var passwordDialog by remember { mutableStateOf(false) }
    var removeDialog by remember { mutableStateOf(false) }
    var feedback by remember { mutableIntStateOf(0) }
    var autoLock by remember { mutableLongStateOf(container.settings.autoLockMillis) }
    var showContent by remember { mutableStateOf(container.settings.showOrdinaryContent) }

    Page(stringResource(R.string.settings), onBack) {
        Text(stringResource(R.string.settings_password), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(if (hasPassword) R.string.settings_password_on else R.string.settings_password_off))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { passwordDialog = true }) {
                Text(stringResource(if (hasPassword) R.string.settings_password_change else R.string.settings_password_set))
            }
            if (hasPassword) OutlinedButton(onClick = { removeDialog = true }) { Text(stringResource(R.string.settings_password_remove)) }
        }
        if (feedback != 0) Text(stringResource(feedback), color = MaterialTheme.colorScheme.primary)

        if (hasPassword) {
            Text(stringResource(R.string.settings_auto_lock), style = MaterialTheme.typography.titleMedium)
            for (millis in AppSettings.AUTO_LOCK_CHOICES) {
                Row(
                    Modifier.fillMaxWidth().selectable(selected = autoLock == millis, role = Role.RadioButton, onClick = {
                        autoLock = millis
                        container.settings.autoLockMillis = millis
                    }),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = autoLock == millis, onClick = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (millis == 0L) stringResource(R.string.auto_lock_immediately) else stringResource(R.string.auto_lock_minutes, (millis / 60_000).toInt()))
                }
            }
            OutlinedButton(onClick = { scope.launch { container.lock.lock() } }) { Text(stringResource(R.string.settings_lock_now)) }
        }
        HorizontalDivider()

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_notification_content), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.settings_notification_content_summary), style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = showContent, onCheckedChange = {
                showContent = it
                container.settings.showOrdinaryContent = it
            })
        }
        OutlinedButton(onClick = {
            try {
                context.startActivity(context.getSystemService(TelecomManager::class.java).createManageBlockedNumbersIntent())
            } catch (_: ActivityNotFoundException) {
                // Trimmed-down systems without the screen: blocking still works from each conversation.
            }
        }) { Text(stringResource(R.string.settings_blocked)) }
        HorizontalDivider()

        Text(stringResource(R.string.settings_about), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.about_text, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.size(24.dp))
    }

    if (passwordDialog) {
        var password by remember { mutableStateOf("") }
        var confirm by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<Int?>(null) }
        AlertDialog(
            onDismissRequest = { passwordDialog = false },
            title = { Text(stringResource(if (hasPassword) R.string.settings_password_change else R.string.settings_password_set)) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { PasswordFields(password, confirm, { password = it; error = null }, { confirm = it; error = null }, error) } },
            confirmButton = {
                TextButton(onClick = {
                    error = passwordProblem(password, confirm)
                    if (error == null) {
                        passwordDialog = false
                        scope.launch {
                            container.lock.changePassword(password.toCharArray())
                            hasPassword = true
                            feedback = R.string.settings_password_saved
                        }
                    }
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { passwordDialog = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (removeDialog) {
        AlertDialog(
            onDismissRequest = { removeDialog = false },
            title = { Text(stringResource(R.string.settings_password_remove)) },
            text = { Text(stringResource(R.string.settings_password_off)) },
            confirmButton = {
                TextButton(onClick = {
                    removeDialog = false
                    scope.launch {
                        container.lock.changePassword(null)
                        hasPassword = false
                        feedback = R.string.settings_password_removed
                    }
                }) { Text(stringResource(R.string.settings_password_remove)) }
            },
            dismissButton = { TextButton(onClick = { removeDialog = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
