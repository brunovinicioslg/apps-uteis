package io.github.brunovinicioslg.sossego.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
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
import io.github.brunovinicioslg.sossego.core.rules.Reason
import io.github.brunovinicioslg.sossego.core.rules.Settings
import io.github.brunovinicioslg.sossego.data.BlockedCall
import io.github.brunovinicioslg.sossego.ui.theme.SossegoTheme
import java.time.LocalDateTime
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The number dialog, on Robolectric's default screen: on wider ones (411dp and up) Robolectric
 * never settles a text field inside a dialog, a quirk of the test runtime (the app itself was
 * checked on a 411dp emulator).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "pt-rBR")
class ListDialogTest {

    @get:Rule
    val compose = createComposeRule()

    private val saved = mutableListOf<Triple<ListEntry, ListEntry?, Boolean>>()

    private fun lists(entries: List<ListEntry> = emptyList()) {
        compose.setContent {
            SossegoTheme {
                ListsScreen(
                    entries = entries,
                    actions = ListsActions(
                        onSave = { entry, replacing, fromContacts -> saved += Triple(entry, replacing, fromContacts) },
                        onDelete = {},
                        readPickedContact = { null },
                        onOpenSystemBlocked = {},
                    ),
                    contentPadding = PaddingValues(),
                )
            }
        }
    }

    @Test
    fun `a number without digits is refused`() {
        lists()
        compose.onNodeWithContentDescription("Adicionar número").performClick()
        compose.onNodeWithText("Salvar").performClick()
        compose.onNodeWithText("Digite um número.").assertExists()
        assertThat(saved).isEmpty()
    }

    @Test
    fun `a typed number is saved on the list shown`() {
        lists()
        compose.onNodeWithContentDescription("Adicionar número").performClick()
        compose.onNodeWithText("Número").performTextInput("(11) 3333-4444")
        compose.onNodeWithText("Salvar").performClick()
        val (entry, replacing, fromContacts) = saved.single()
        assertThat(entry.list).isEqualTo(ListKind.BLOCK)
        assertThat(entry.pattern).isEqualTo("1133334444")
        assertThat(replacing).isNull()
        assertThat(fromContacts).isFalse()
    }

    @Test
    fun `a prefix is saved as one`() {
        lists()
        compose.onNodeWithText("Lista branca (0)").performClick()
        compose.onNodeWithContentDescription("Adicionar número").performClick()
        compose.onNodeWithText("Número").performTextInput("0800")
        compose.onNodeWithText("Todos que começam com esse número").performClick()
        compose.onNodeWithText("Salvar").performClick()
        val entry = saved.single().first
        assertThat(entry.list).isEqualTo(ListKind.ALLOW)
        assertThat(entry.match).isEqualTo(Match.PREFIX)
        assertThat(entry.pattern).isEqualTo("0800")
    }

}
