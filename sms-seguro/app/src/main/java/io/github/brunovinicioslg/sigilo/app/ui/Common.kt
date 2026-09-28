package io.github.brunovinicioslg.sigilo.app.ui

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.brunovinicioslg.sigilo.app.R
import io.github.brunovinicioslg.sigilo.app.appContainer
import io.github.brunovinicioslg.sigilo.app.contacts.ContactNames
import io.github.brunovinicioslg.sigilo.app.db.MessageKind
import io.github.brunovinicioslg.sigilo.app.engine.MessageEngine
import java.util.Calendar
import java.util.Date
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.onStart

/**
 * A value read through the message engine, read again whenever conversations change. Keeps the
 * last value while the app is locked.
 */
@Composable
fun <T> rememberEngineQuery(initial: T, vararg keys: Any?, query: (MessageEngine) -> T): State<T> {
    val container = LocalContext.current.appContainer
    return produceState(initial, *keys) {
        container.changes.onStart { emit(Unit) }.collectLatest {
            container.lock.withEngine(query)?.let { value = it }
        }
    }
}

@Composable
fun rememberContactNames(): ContactNames {
    val context = LocalContext.current
    return remember(context) { ContactNames(context.applicationContext) }
}

/** A colored circle with the contact's initial; the color is stable per number. */
@Composable
fun Avatar(label: String, key: String, size: Dp = 48.dp) {
    val palette = listOf(0xFF6366F1, 0xFF0EA5E9, 0xFF10B981, 0xFFF59E0B, 0xFFEF4444, 0xFFA855F7, 0xFF14B8A6, 0xFFF97316)
    val color = Color(palette[(key.hashCode() and Int.MAX_VALUE) % palette.size])
    val initial = label.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "#"
    Box(Modifier.size(size).background(color, CircleShape), contentAlignment = Alignment.Center) {
        Text(initial, color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}

/** The text shown for app events recorded in a conversation. */
@Composable
fun eventText(kind: MessageKind, body: String): String = when (kind) {
    MessageKind.TEXT -> body
    MessageKind.INVITE_SENT -> stringResource(R.string.info_invite_sent)
    MessageKind.INVITE_RECEIVED -> stringResource(R.string.info_invite_received)
    MessageKind.ENCRYPTION_ON -> stringResource(R.string.info_encryption_on)
    MessageKind.KEY_CHANGED -> stringResource(R.string.info_key_changed)
    MessageKind.KEY_ACCEPTED -> stringResource(R.string.info_key_accepted)
    MessageKind.TIMER_CHANGED -> {
        val seconds = body.toIntOrNull() ?: 0
        if (seconds == 0) stringResource(R.string.info_timer_off) else stringResource(R.string.info_timer_on, timerLabel(seconds))
    }
    MessageKind.UNREADABLE -> stringResource(R.string.info_unreadable)
    MessageKind.UNSUPPORTED -> stringResource(R.string.info_unsupported)
    MessageKind.MMS -> stringResource(R.string.info_mms)
    MessageKind.INVITE_DECLINED -> stringResource(R.string.info_invite_declined)
}

/** Disappearing-message choices, in seconds (0 = off). */
val TIMER_CHOICES = listOf(0, 30, 5 * 60, 3600, 8 * 3600, 24 * 3600, 7 * 24 * 3600)

@Composable
fun timerLabel(seconds: Int): String = when (seconds) {
    0 -> stringResource(R.string.timer_off)
    30 -> stringResource(R.string.timer_30s)
    5 * 60 -> stringResource(R.string.timer_5m)
    3600 -> stringResource(R.string.timer_1h)
    8 * 3600 -> stringResource(R.string.timer_8h)
    24 * 3600 -> stringResource(R.string.timer_1d)
    7 * 24 * 3600 -> stringResource(R.string.timer_1w)
    else -> "$seconds s"
}

/** "14:05" today, the date on other days: as message apps show it. */
@Composable
fun shortTime(millis: Long): String {
    val context = LocalContext.current
    return if (sameDay(millis, System.currentTimeMillis())) {
        DateFormat.getTimeFormat(context).format(Date(millis))
    } else {
        DateFormat.getDateFormat(context).format(Date(millis))
    }
}

@Composable
fun clockTime(millis: Long): String = DateFormat.getTimeFormat(LocalContext.current).format(Date(millis))

@Composable
fun dayLabel(millis: Long): String {
    val now = System.currentTimeMillis()
    return when {
        sameDay(millis, now) -> stringResource(R.string.today)
        sameDay(millis, now - 24 * 3600 * 1000L) -> stringResource(R.string.yesterday)
        else -> DateFormat.getLongDateFormat(LocalContext.current).format(Date(millis))
    }
}

fun sameDay(a: Long, b: Long): Boolean {
    val ca = Calendar.getInstance().apply { timeInMillis = a }
    val cb = Calendar.getInstance().apply { timeInMillis = b }
    return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR) && ca.get(Calendar.DAY_OF_YEAR) == cb.get(Calendar.DAY_OF_YEAR)
}
