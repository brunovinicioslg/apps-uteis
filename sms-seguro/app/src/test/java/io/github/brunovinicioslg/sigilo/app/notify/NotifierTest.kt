package io.github.brunovinicioslg.sigilo.app.notify

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.brunovinicioslg.sigilo.app.AppSettings
import io.github.brunovinicioslg.sigilo.app.db.Conversation
import io.github.brunovinicioslg.sigilo.app.db.Encryption
import io.github.brunovinicioslg.sigilo.app.db.Message
import io.github.brunovinicioslg.sigilo.app.db.MessageKind
import io.github.brunovinicioslg.sigilo.app.db.MessageStatus
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "pt-rBR")
class NotifierTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val manager = app.getSystemService(NotificationManager::class.java)
    private val notifier by lazy { Notifier(app, AppSettings(app)) }

    @Before
    fun setUp() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun conversation(encryption: Encryption) = Conversation(
        id = 5, address = "+5531999998888", encryption = encryption, expireSeconds = 0, unread = 1, lastAt = 1, snippet = null,
        snippetKind = MessageKind.TEXT, subscriptionId = -1, keyChanged = false, lastEncryptedSentAt = 0,
    )

    private fun message(encrypted: Boolean) = Message(
        id = 1, conversationId = 5, outgoing = false, kind = MessageKind.TEXT, body = "Chego às 8", encrypted = encrypted,
        status = MessageStatus.RECEIVED, sentAt = 1, receivedAt = 1, parts = 1, expireSeconds = 0, expireAt = null, read = false, providerId = null,
    )

    private fun actions() = shadowOf(manager).allNotifications.single().actions.map { it.title.toString() to (it.remoteInputs?.size ?: 0) }

    @Test
    fun anOrdinarySmsCanBeAnsweredFromTheNotification() {
        notifier.showNew(conversation(Encryption.NONE), listOf(message(encrypted = false)), hideContent = false)
        assertThat(actions()).containsExactly("Marcar como lida" to 0, "Responder" to 1).inOrder()
    }

    private fun shown() = shadowOf(manager).allNotifications.single().extras

    @Test
    fun byDefaultAnOrdinarySmsShowsWhoWroteButNotTheText() {
        notifier.showNew(conversation(Encryption.NONE), listOf(message(encrypted = false)), hideContent = false)
        assertThat(shown().getCharSequence(NotificationCompat.EXTRA_TITLE).toString()).isEqualTo("+5531999998888")
        assertThat(shown().getCharSequence(NotificationCompat.EXTRA_TEXT).toString()).isEqualTo("Nova mensagem")
        manager.cancelAll()
        notifier.showReplied(conversation(Encryption.NONE), "Combinado")
        assertThat(shown().getCharSequence(NotificationCompat.EXTRA_TEXT).toString()).isEqualTo("Resposta enviada")
    }

    @Test
    fun theTextShowsWhenTheUserAsksForIt() {
        AppSettings(app).showOrdinaryContent = true
        notifier.showNew(conversation(Encryption.NONE), listOf(message(encrypted = false)), hideContent = false)
        assertThat(shown().getCharSequence(NotificationCompat.EXTRA_TEXT).toString()).isEqualTo("Chego às 8")
        manager.cancelAll()
        notifier.showReplied(conversation(Encryption.NONE), "Combinado")
        assertThat(shown().getCharSequence(NotificationCompat.EXTRA_TEXT).toString()).isEqualTo("Você: Combinado")
    }

    @Test
    fun notFromEncryptedConversationsNorPastThePassword() {
        notifier.showNew(conversation(Encryption.ACTIVE), listOf(message(encrypted = true)), hideContent = false)
        assertThat(actions()).containsExactly("Marcar como lida" to 0)
        manager.cancelAll()
        notifier.showNew(conversation(Encryption.NONE), listOf(message(encrypted = false)), hideContent = true)
        assertThat(actions()).containsExactly("Marcar como lida" to 0)
    }
}
