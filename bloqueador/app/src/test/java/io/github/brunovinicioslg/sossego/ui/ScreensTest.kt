package io.github.brunovinicioslg.sossego.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.google.common.truth.Truth.assertThat
import io.github.brunovinicioslg.sossego.core.lists.ListEntry
import io.github.brunovinicioslg.sossego.core.lists.ListKind
import io.github.brunovinicioslg.sossego.core.lists.Match
import io.github.brunovinicioslg.sossego.core.rules.BlockAction
import io.github.brunovinicioslg.sossego.core.rules.Mode
import io.github.brunovinicioslg.sossego.core.rules.NotifyMode
import io.github.brunovinicioslg.sossego.core.rules.Settings
import io.github.brunovinicioslg.sossego.ui.theme.SossegoTheme
import java.time.LocalDateTime
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// A tall screen keeps every list item composed, so absence checks are meaningful.
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "pt-rBR-w411dp-h2600dp")
class ScreensTest {

    @get:Rule
    val compose = createComposeRule()

    private var settings = Settings()
    private val modes = mutableListOf<Mode>()
    private var screeningRequests = 0
    private var declineRequests = 0
    private val saved = mutableListOf<Triple<ListEntry, ListEntry?, Boolean>>()
    private val deleted = mutableListOf<ListEntry>()

    private val ready = DeviceState(screeningEnabled = true, canDeclineContacts = true, contactsGranted = true)

    private fun home(settings: Settings = Settings(), device: DeviceState = ready) {
        this.settings = settings
        compose.setContent {
            SossegoTheme {
                HomeScreen(
                    settings = settings,
                    device = device,
                    now = LocalDateTime.of(2026, 9, 28, 12, 0),
                    actions = HomeActions(
                        onEnableScreening = { screeningRequests++ },
                        onSettingsChange = { transform -> this.settings = transform(this.settings) },
                        onChooseMode = { modes += it },
                        onChooseNotifications = { mode -> this.settings = this.settings.copy(notifications = mode) },
                        onAllowDecline = { declineRequests++ },
                        onAllowContacts = {},
                        onOpenBattery = {},
                        onOpenSystemBlocked = {},
                        onOpenSource = {},
                    ),
                    contentPadding = PaddingValues(),
                )
            }
        }
    }

    private fun lists(entries: List<ListEntry> = emptyList()) {
        compose.setContent {
            SossegoTheme {
                ListsScreen(
                    entries = entries,
                    actions = ListsActions(
                        onSave = { entry, replacing, fromContacts -> saved += Triple(entry, replacing, fromContacts) },
                        onDelete = { deleted += it },
                        readPickedContact = { null },
                        onOpenSystemBlocked = {},
                    ),
                    contentPadding = PaddingValues(),
                )
            }
        }
    }

    @Test
    fun `without the call screening role the app asks for it first`() {
        home(device = DeviceState(screeningEnabled = false))
        compose.onNodeWithText("Ative o Sossego").assertExists()
        compose.onNodeWithText("Ativar").performClick()
        assertThat(screeningRequests).isEqualTo(1)
    }

    @Test
    fun `the status shows the mode in force`() {
        home()
        compose.onNodeWithText("Bloqueando: Lista negra").assertExists()
    }

    @Test
    fun `block everything asks for confirmation`() {
        home()
        compose.onNodeWithText("Bloquear tudo").performClick()
        assertThat(modes).isEmpty()
        compose.onNodeWithText("Bloquear todas as chamadas?").assertExists()
        compose.onNodeWithText("Cancelar").performClick()
        assertThat(modes).isEmpty()
        compose.onNodeWithText("Bloquear tudo").performClick()
        compose.onNodeWithText("Bloquear todas as chamadas?").assertExists()
        // The dialog's button has the same words as the mode: the last one is the dialog's.
        compose.onAllNodesWithText("Bloquear tudo").onLast().performClick()
        assertThat(modes).containsExactly(Mode.BLOCK_ALL)
    }

    @Test
    fun `other modes apply right away`() {
        home()
        compose.onNodeWithText("Só lista branca").performClick()
        compose.onNodeWithText("Desligado").performClick()
        assertThat(modes).containsExactly(Mode.ALLOWLIST, Mode.OFF).inOrder()
    }

    @Test
    fun `block everything without its permissions warns that contacts still ring`() {
        home(Settings(mode = Mode.BLOCK_ALL), ready.copy(canDeclineContacts = false))
        compose.onNodeWithText("Contatos ainda tocam").assertExists()
        compose.onNodeWithText("Permitir").performClick()
        assertThat(declineRequests).isEqualTo(1)
    }

    @Test
    fun `filters belong to the block list and the insist rule to the allow list`() {
        home(Settings(mode = Mode.BLOCKLIST))
        compose.onNodeWithText("Telemarketing (0303)").assertExists()
        compose.onNodeWithText("Deixar passar quem insiste").assertDoesNotExist()
    }

    @Test
    fun `the allow list shows the insist rule and offers to protect contacts`() {
        home(Settings(mode = Mode.ALLOWLIST), ready.copy(contactsGranted = false))
        compose.onNodeWithText("Deixar passar quem insiste").assertExists()
        compose.onNodeWithText("Proteja seus contatos").assertExists()
        compose.onNodeWithText("Telemarketing (0303)").assertDoesNotExist()
    }

    @Test
    fun `switches and choices change the settings`() {
        home()
        compose.onNodeWithText("Números ocultos").performClick()
        assertThat(settings.blockHidden).isTrue()
        compose.onNodeWithText("Resumo do dia").performClick()
        assertThat(settings.notifications).isEqualTo(NotifyMode.DAILY_COUNT)
        compose.onNodeWithText("Silenciar").performClick()
        assertThat(settings.action).isEqualTo(BlockAction.SILENCE)
    }

    @Test
    fun `the schedule shows its times once turned on`() {
        home(Settings(schedule = Settings().schedule.copy(enabled = true)))
        compose.onNodeWithText("Início 22:00").assertExists()
        compose.onNodeWithText("Fim 07:00").assertExists()
    }


    @Test
    fun `entries show formatted and can be deleted`() {
        val entry = ListEntry(7, ListKind.BLOCK, Match.EXACT, "1133334444", "Loja")
        lists(listOf(entry, ListEntry(8, ListKind.BLOCK, Match.PREFIX, "0303")))
        compose.onNodeWithText("(11) 3333-4444").assertExists()
        compose.onNodeWithText("Loja").assertExists()
        compose.onNodeWithText("Começa com 0303").assertExists()
        compose.onNodeWithContentDescription("Apagar (11) 3333-4444").performClick()
        assertThat(deleted).containsExactly(entry)
    }

}
