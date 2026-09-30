package io.github.brunovinicioslg.sossego.ui

import android.content.ClipboardManager
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.brunovinicioslg.sossego.R
import io.github.brunovinicioslg.sossego.core.dial.Contact
import io.github.brunovinicioslg.sossego.core.dial.ContactSearch
import io.github.brunovinicioslg.sossego.core.dial.Dialpad
import io.github.brunovinicioslg.sossego.core.number.PhoneNumbers

const val DIALPAD_NUMBER_TAG = "dialpad_number"

/** The green of phone call buttons, the same in light and dark themes. */
val CallGreen = Color(0xFF1E8E3E)

class DialpadActions(
    val onCall: (String) -> Unit,
    val onVoicemail: () -> Unit,
    val onAllowContacts: () -> Unit,
)

@Composable
fun DialpadScreen(
    contacts: List<Contact>?,
    lastDialed: String?,
    actions: DialpadActions,
    contentPadding: PaddingValues,
) {
    var typed by rememberSaveable { mutableStateOf("") }
    val suggestions = remember(typed, contacts) { ContactSearch.byKeys(contacts.orEmpty(), typed) }
    val view = LocalView.current
    val context = LocalContext.current

    fun type(key: Char) {
        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        typed = Dialpad.clean(typed + key)
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(top = contentPadding.calculateTopPadding(), bottom = contentPadding.calculateBottomPadding() + 12.dp),
    ) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                typed.isNotEmpty() -> LazyColumn(Modifier.fillMaxSize()) {
                    items(suggestions, key = { it.contact.id to it.phone.number }) { suggestion ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { typed = Dialpad.clean(suggestion.phone.number) }
                                .padding(start = 24.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(suggestion.contact.name, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    listOf(suggestion.phone.label, displayNumber(suggestion.phone.number)).filter { it.isNotEmpty() }.joinToString(" · "),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = { actions.onCall(suggestion.phone.number) }) {
                                Icon(
                                    painterResource(R.drawable.ic_call),
                                    contentDescription = stringResource(R.string.call_to, suggestion.contact.name),
                                    tint = CallGreen,
                                )
                            }
                        }
                        HorizontalDivider()
                    }
                }
                contacts == null -> TextButton(
                    onClick = actions.onAllowContacts,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                ) { Text(stringResource(R.string.dialpad_allow_contacts), textAlign = TextAlign.Center) }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(64.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(48.dp))
            Text(
                Dialpad.format(typed),
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Clip,
                modifier = Modifier.weight(1f).testTag(DIALPAD_NUMBER_TAG),
            )
            if (typed.isNotEmpty()) {
                val delete = stringResource(R.string.dialpad_delete)
                Box(
                    Modifier
                        .size(48.dp)
                        .semantics { contentDescription = delete }
                        .combinedClickable(
                            onClick = { typed = typed.dropLast(1) },
                            onLongClick = { typed = "" },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(painterResource(R.drawable.ic_backspace), contentDescription = null)
                }
            } else {
                TextButton(onClick = {
                    val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip
                    val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                    typed = Dialpad.clean(text)
                }) { Text(stringResource(R.string.dialpad_paste)) }
            }
        }

        Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Dialpad.KEYS.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { (digit, letters) ->
                        DialKey(
                            digit = digit,
                            letters = letters,
                            voicemail = digit == '1',
                            modifier = Modifier.weight(1f),
                            onClick = { type(digit) },
                            onLongClick = when (digit) {
                                '0' -> ({ type('+') })
                                '1' -> if (typed.isEmpty()) actions.onVoicemail else null
                                else -> null
                            },
                        )
                    }
                }
            }
        }

        Box(Modifier.fillMaxWidth().padding(top = 12.dp), contentAlignment = Alignment.Center) {
            FloatingActionButton(
                onClick = {
                    when {
                        typed.isNotEmpty() -> actions.onCall(typed)
                        lastDialed != null -> typed = Dialpad.clean(lastDialed) // like phones: the last number comes back
                    }
                },
                shape = CircleShape,
                containerColor = CallGreen,
                contentColor = Color.White,
                modifier = Modifier.size(64.dp),
            ) {
                Icon(painterResource(R.drawable.ic_call), contentDescription = stringResource(R.string.dialpad_call))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DialKey(
    digit: Char,
    letters: String,
    voicemail: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier
            .height(60.dp)
            .testTag(dialKeyTag(digit))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(digit.toString(), style = MaterialTheme.typography.headlineSmall)
            if (voicemail) {
                Icon(
                    painterResource(R.drawable.ic_voicemail),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            } else {
                Text(letters, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

fun dialKeyTag(digit: Char) = "dial_key_$digit"

/** A stored number shown the Brazilian way. */
fun displayNumber(number: String): String = PhoneNumbers.format(PhoneNumbers.key(number).ifEmpty { return number })
