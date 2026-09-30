package io.github.brunovinicioslg.sossego.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import com.google.common.truth.Truth.assertThat
import io.github.brunovinicioslg.sossego.core.dial.CallKind
import io.github.brunovinicioslg.sossego.core.dial.Contact
import io.github.brunovinicioslg.sossego.core.dial.ContactPhone
import io.github.brunovinicioslg.sossego.core.dial.RecentCall
import io.github.brunovinicioslg.sossego.core.dial.RecentsFilter
import io.github.brunovinicioslg.sossego.core.rules.Reason
import io.github.brunovinicioslg.sossego.ui.theme.SossegoTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "pt-rBR-w411dp-h2600dp")
class CallingScreensTest {

    @get:Rule
    val compose = createComposeRule()

    private val calls = mutableListOf<String>()
    private val blocked = mutableListOf<String>()
    private val allowed = mutableListOf<RecentCall>()
    private var voicemail = 0

    private val maria = Contact(1, "Maria Souza", listOf(ContactPhone("(11) 98765-4321", "Celular")))
    private val jose = Contact(
        2,
        "José Antônio",
        listOf(ContactPhone("(31) 3333-2222", "Casa"), ContactPhone("(31) 98888-7777", "Celular")),
        starred = true,
    )

    private fun dialpad(contacts: List<Contact>? = listOf(maria, jose), lastDialed: String? = null) {
        compose.setContent {
            SossegoTheme {
                DialpadScreen(
                    contacts = contacts,
                    lastDialed = lastDialed,
                    actions = DialpadActions(onCall = { calls += it }, onVoicemail = { voicemail++ }, onAllowContacts = {}),
                    contentPadding = PaddingValues(),
                )
            }
        }
    }

    private fun contacts(list: List<Contact>? = listOf(maria, jose)) {
        compose.setContent {
            SossegoTheme {
                ContactsScreen(
                    contacts = list,
                    actions = ContactsActions(onCall = { calls += it }, onBlock = { blocked += it }, onOpenContact = {}, onAllowContacts = {}),
                    contentPadding = PaddingValues(),
                )
            }
        }
    }

    @Test
    fun `keys type a number shown the Brazilian way and call it`() {
        dialpad()
        "11987654321".forEach { compose.onNodeWithTag(dialKeyTag(it)).performClick() }
        compose.onNodeWithTag(DIALPAD_NUMBER_TAG).assertTextEquals("(11) 98765-4321")
        compose.onNodeWithContentDescription("Ligar").performClick()
        assertThat(calls).containsExactly("11987654321")
    }

    @Test
    fun `delete takes the last digit off`() {
        dialpad()
        "190".forEach { compose.onNodeWithTag(dialKeyTag(it)).performClick() }
        compose.onNodeWithContentDescription("Apagar").performClick()
        compose.onNodeWithTag(DIALPAD_NUMBER_TAG).assertTextEquals("19")
    }

    @Test
    fun `holding zero types a plus and holding one calls the voicemail`() {
        dialpad()
        compose.onNodeWithTag(dialKeyTag('1')).performTouchInput { longClick() }
        assertThat(voicemail).isEqualTo(1)
        compose.onNodeWithTag(dialKeyTag('0')).performTouchInput { longClick() }
        compose.onNodeWithTag(DIALPAD_NUMBER_TAG).assertTextEquals("+")
    }

    @Test
    fun `typing suggests contacts, and their call button calls them`() {
        dialpad()
        "6274".forEach { compose.onNodeWithTag(dialKeyTag(it)).performClick() }
        compose.onNodeWithText("Maria Souza").assertExists()
        compose.onNodeWithContentDescription("Ligar para Maria Souza").performClick()
        assertThat(calls).containsExactly("(11) 98765-4321")
    }

    @Test
    fun `the call button with nothing typed brings back the last number`() {
        dialpad(lastDialed = "(21) 3456-7890")
        compose.onNodeWithContentDescription("Ligar").performClick()
        assertThat(calls).isEmpty()
        compose.onNodeWithTag(DIALPAD_NUMBER_TAG).assertTextEquals("(21) 3456-7890")
    }

    @Test
    fun `without contacts the keypad offers to allow them`() {
        dialpad(contacts = null)
        compose.onNodeWithText("Permita o acesso aos contatos para achá-los pelo teclado.").assertExists()
    }

    @Test
    fun `contacts are listed with favorites first and searched`() {
        contacts()
        compose.onNodeWithText("Favoritos").assertExists()
        compose.onNodeWithText("Buscar nos contatos").performTextInput("maria")
        compose.onNodeWithText("Favoritos").assertDoesNotExist()
        compose.onNodeWithText("José Antônio").assertDoesNotExist()
        compose.onNodeWithText("Maria Souza").assertExists()
    }

    @Test
    fun `a contact opens with each number to call or block`() {
        contacts()
        compose.onAllNodesWithText("José Antônio").onFirst().performClick()
        compose.onNodeWithContentDescription("Ligar para (31) 98888-7777").performClick()
        assertThat(calls).containsExactly("(31) 98888-7777")
        compose.onAllNodesWithText("José Antônio").onFirst().performClick()
        compose.onNodeWithContentDescription("Bloquear (31) 3333-2222").performClick()
        assertThat(blocked).containsExactly("(31) 3333-2222")
    }

    @Test
    fun `without permission the contacts tab asks for it`() {
        contacts(list = null)
        compose.onNodeWithText("Seus contatos").assertExists()
    }

    @Test
    fun `recent calls show kinds and reasons and call back`() {
        val now = System.currentTimeMillis()
        val recents = listOf(
            RecentCall(now, "11987654321", CallKind.INCOMING, name = "Maria Souza", durationSeconds = 125),
            RecentCall(now - 1000, "1133334444", CallKind.BLOCKED, reason = Reason.BLOCK_LISTED, blockId = 7),
            RecentCall(now - 2000, "", CallKind.BLOCKED, reason = Reason.BLOCK_ALL, blockId = 8),
        )
        compose.setContent {
            SossegoTheme {
                RecentsScreen(
                    calls = recents,
                    callLogShown = true,
                    filter = RecentsFilter.ALL,
                    onFilter = {},
                    actions = RecentsActions(
                        onCall = { calls += it },
                        onAllow = { allowed += it },
                        onBlock = {},
                        onCopy = {},
                        onDeleteBlock = {},
                        onAllowCallLog = {},
                    ),
                    contentPadding = PaddingValues(),
                )
            }
        }
        compose.onNodeWithText("Maria Souza").assertExists()
        compose.onNodeWithText("(11) 3333-4444").assertExists()
        compose.onNodeWithText("Contato").assertExists() // a contact declined by "block everything"
        compose.onNodeWithContentDescription("Ligar para Maria Souza").performClick()
        assertThat(calls).containsExactly("11987654321")
        compose.onNodeWithText("(11) 3333-4444").performClick()
        compose.onNodeWithText("Liberar este número").performClick()
        assertThat(allowed.single().blockId).isEqualTo(7L)
    }

    @Test
    fun `without the call log the recents offer to allow it`() {
        compose.setContent {
            SossegoTheme {
                RecentsScreen(
                    calls = emptyList(),
                    callLogShown = false,
                    filter = RecentsFilter.ALL,
                    onFilter = {},
                    actions = RecentsActions({}, {}, {}, {}, {}, {}),
                    contentPadding = PaddingValues(),
                )
            }
        }
        compose.onNodeWithText("Veja todas as suas chamadas").assertExists()
        compose.onNodeWithText("Nenhuma chamada.").assertExists()
    }

}
