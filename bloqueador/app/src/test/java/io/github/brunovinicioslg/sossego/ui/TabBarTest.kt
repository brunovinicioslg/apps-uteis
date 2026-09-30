package io.github.brunovinicioslg.sossego.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.google.common.truth.Truth.assertWithMessage
import io.github.brunovinicioslg.sossego.ui.theme.SossegoTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

// Real text measurement (native graphics) on a 360 dp wide phone, the Galaxy S22's width.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TabBarTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    @Config(qualifiers = "pt-rBR-w360dp-h800dp")
    fun `every tab label fits on a narrow phone in Portuguese`() = assertLabelsFit()

    @Test
    @Config(qualifiers = "en-rUS-w360dp-h800dp")
    fun `every tab label fits on a narrow phone in English`() = assertLabelsFit()

    private fun assertLabelsFit() {
        compose.setContent { SossegoTheme { TabBar(Tab.BLOCKING) {} } }
        val context = ApplicationProvider.getApplicationContext<Context>()
        Tab.entries.forEach { tab ->
            val label = context.getString(tabLabel(tab))
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(label, useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val layout = layouts.single()
            // A word too long for one line would need a second line (breaking it) or end in "…".
            assertWithMessage("\"$label\" does not fit").that(layout.multiParagraph.didExceedMaxLines || layout.isLineEllipsized(0)).isFalse()
        }
    }
}
