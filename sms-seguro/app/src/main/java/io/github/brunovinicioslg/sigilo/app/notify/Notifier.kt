package io.github.brunovinicioslg.sigilo.app.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import io.github.brunovinicioslg.sigilo.app.R
import io.github.brunovinicioslg.sigilo.app.AppSettings
import io.github.brunovinicioslg.sigilo.app.contacts.ContactNames
import io.github.brunovinicioslg.sigilo.app.db.Conversation
import io.github.brunovinicioslg.sigilo.app.db.Message
import io.github.brunovinicioslg.sigilo.app.db.MessageKind
import io.github.brunovinicioslg.sigilo.app.ui.MainActivity

/**
 * Message notifications. Encrypted conversations never show who wrote or what: just "New
 * message". With a password set, no notification shows content at all.
 */
class Notifier(private val context: Context, private val settings: AppSettings) {

    private val manager = NotificationManagerCompat.from(context)
    private val names = ContactNames(context)

    init {
        val channel = NotificationChannel(CHANNEL_MESSAGES, context.getString(R.string.channel_messages), NotificationManager.IMPORTANCE_HIGH).apply {
            description = context.getString(R.string.channel_messages_description)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun showNew(conversation: Conversation, messages: List<Message>, hideContent: Boolean) {
        if (!allowed()) return
        val private = hideContent || messages.any { it.encrypted } || !settings.showOrdinaryContent
        val title = if (private && (hideContent || messages.any { it.encrypted })) {
            context.getString(R.string.app_name)
        } else {
            names.nameOf(conversation.address) ?: conversation.address
        }
        val text = if (private) {
            if (messages.size == 1) context.getString(R.string.notif_new_message) else context.getString(R.string.notif_new_messages, messages.size)
        } else {
            messages.joinToString("\n") { describe(it) }
        }
        val builder = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text.lineSequence().last())
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            // The lock screen shows only "Sigilo: new message", whatever the content setting.
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion())
            .setAutoCancel(true)
            .setContentIntent(openConversation(conversation.id))
            .addAction(0, context.getString(R.string.notif_mark_read), markRead(conversation.id))
        // Replying from here only where the notification says who wrote: never past the password
        // lock, and not to encrypted conversations, whose notifications name no one.
        if (!private) builder.addAction(replyAction(conversation.id))
        notify(idFor(conversation.id), builder.build())
    }

    /**
     * A quick reply left. Android 15+ keeps a replied notification on screen and ignores the app
     * cancelling it: the notification is updated to show the reply, silently, and goes away soon.
     */
    fun showReplied(conversation: Conversation, reply: String) {
        if (!allowed()) return
        val notification = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(names.nameOf(conversation.address) ?: conversation.address)
            .setContentText(context.getString(R.string.notif_you_replied, reply))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion())
            .setSilent(true)
            .setAutoCancel(true)
            .setTimeoutAfter(REPLIED_VISIBLE_MS)
            .setContentIntent(openConversation(conversation.id))
            .build()
        notify(idFor(conversation.id), notification)
    }

    /** A quick reply could not be sent (the app was locked meanwhile, or the radio refused). */
    fun showReplyFailed(conversationId: Long) {
        if (!allowed()) return
        val notification = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.getString(R.string.notif_reply_failed))
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setAutoCancel(true)
            .setContentIntent(openConversation(conversationId))
            .build()
        notify(idFor(conversationId), notification)
    }

    private fun replyAction(conversationId: Long): NotificationCompat.Action {
        val input = RemoteInput.Builder(NotificationActionReceiver.KEY_REPLY).setLabel(context.getString(R.string.notif_reply_hint)).build()
        val intent = Intent(context, NotificationActionReceiver::class.java)
            .setAction(NotificationActionReceiver.ACTION_REPLY)
            .putExtra(NotificationActionReceiver.EXTRA_CONVERSATION_ID, conversationId)
        // Mutable so Android can add the typed text; the intent is explicit, so nothing else can use it.
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val pending = PendingIntent.getBroadcast(context, REPLY_REQUEST_BASE + conversationId.toInt(), intent, flags)
        return NotificationCompat.Action.Builder(0, context.getString(R.string.notif_reply), pending)
            .addRemoteInput(input)
            .setAllowGeneratedReplies(false)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setShowsUserInterface(false)
            .build()
    }

    /** SMS are waiting and the app is locked: nothing about them can be read yet. */
    fun showLocked(count: Int) {
        if (!allowed() || count <= 0) return
        val notification = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(if (count == 1) context.getString(R.string.notif_new_message) else context.getString(R.string.notif_new_messages, count))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOnlyAlertOnce(false)
            .setAutoCancel(true)
            .setContentIntent(openConversation(null))
            .build()
        notify(LOCKED_ID, notification)
    }

    fun cancelLocked() = manager.cancel(LOCKED_ID)

    fun cancel(conversationId: Long) = manager.cancel(idFor(conversationId))

    private fun describe(message: Message): String = when (message.kind) {
        MessageKind.TEXT -> message.body
        MessageKind.INVITE_RECEIVED -> context.getString(R.string.notif_invite)
        MessageKind.KEY_CHANGED -> context.getString(R.string.notif_key_changed)
        MessageKind.MMS -> context.getString(R.string.notif_mms)
        else -> context.getString(R.string.notif_new_message)
    }

    private fun publicVersion() = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(context.getString(R.string.app_name))
        .setContentText(context.getString(R.string.notif_new_message))
        .build()

    private fun openConversation(conversationId: Long?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        conversationId?.let { intent.putExtra(MainActivity.EXTRA_CONVERSATION_ID, it) }
        return PendingIntent.getActivity(context, (conversationId ?: 0L).toInt(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun markRead(conversationId: Long): PendingIntent {
        val intent = Intent(context, NotificationActionReceiver::class.java)
            .setAction(NotificationActionReceiver.ACTION_MARK_READ)
            .putExtra(NotificationActionReceiver.EXTRA_CONVERSATION_ID, conversationId)
        return PendingIntent.getBroadcast(context, conversationId.toInt(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun allowed(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    @Suppress("MissingPermission") // checked in allowed()
    private fun notify(id: Int, notification: android.app.Notification) {
        if (allowed()) manager.notify(id, notification)
    }

    private fun idFor(conversationId: Long) = (conversationId % Int.MAX_VALUE).toInt() + FIRST_CONVERSATION_ID

    companion object {
        const val CHANNEL_MESSAGES = "messages"
        private const val LOCKED_ID = 1
        private const val FIRST_CONVERSATION_ID = 100
        private const val REPLY_REQUEST_BASE = 1_000_000
        private const val REPLIED_VISIBLE_MS = 3_000L
    }
}
