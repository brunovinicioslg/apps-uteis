package io.github.brunovinicioslg.sossego.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.brunovinicioslg.sossego.BuildConfig
import io.github.brunovinicioslg.sossego.R
import io.github.brunovinicioslg.sossego.core.dial.CallKind
import io.github.brunovinicioslg.sossego.core.dial.Contact
import io.github.brunovinicioslg.sossego.core.dial.ContactPhone
import io.github.brunovinicioslg.sossego.core.dial.RecentCall
import io.github.brunovinicioslg.sossego.core.dial.RecentsFilter
import io.github.brunovinicioslg.sossego.core.lists.ListEntry
import io.github.brunovinicioslg.sossego.core.lists.ListKind
import io.github.brunovinicioslg.sossego.core.lists.Match
import io.github.brunovinicioslg.sossego.core.rules.Reason
import io.github.brunovinicioslg.sossego.core.rules.Settings
import io.github.brunovinicioslg.sossego.ui.theme.SossegoTheme
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The Google Play images, drawn from the real screens with made-up numbers. Skipped in normal runs;
 * to write them: `./gradlew :app:testPlayDebugUnitTest --tests '*StoreImagesTest' --rerun -PstoreImages=<folder>`.
 * The folder gets `<locale>/images/...`, the layout Play tools and F-Droid read.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// Android 11: the app's own colors instead of the wallpaper's.
@Config(sdk = [30])
class StoreImagesTest {

    @get:Rule
    val compose = createComposeRule()

    private val out = System.getProperty("storeImages")?.takeIf { it.isNotBlank() }?.let(::File)

    @Before
    fun onlyWhenAsked() = assumeTrue("pass -PstoreImages=<folder> to write the store images", out != null)

    @Test
    @Config(qualifiers = "pt-rBR-w360dp-h720dp-xxhdpi")
    fun `screenshots in Portuguese`() = screenshots("pt-BR", Sample.PT)

    @Test
    @Config(qualifiers = "en-rUS-w360dp-h720dp-xxhdpi")
    fun `screenshots in English`() = screenshots("en-US", Sample.EN)

    @Test
    @Config(qualifiers = "w1024dp-h1024dp-mdpi")
    fun icon() {
        compose.setContent {
            Box(Modifier.testTag(IMAGE).size(512.dp).background(colorResource(R.color.brand_teal)).clipToBounds(), Alignment.Center) {
                // The launcher shows the middle 72 of the icon's 108; Play rounds only the corners.
                Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.requiredSize(512.dp * 108 / 84))
            }
        }
        val image = compose.onNodeWithTag(IMAGE).captureToImage()
        LOCALES.forEach { save(image, "$it/images/icon.png") }
    }

    @Test
    @Config(qualifiers = "pt-rBR-w1024dp-h1024dp-mdpi")
    fun `feature graphic in Portuguese`() = featureGraphic("pt-BR", "Bloqueio de chamadas", "Ligações indesejadas recusadas em silêncio, sem internet e sem anúncios")

    @Test
    @Config(qualifiers = "en-rUS-w1024dp-h1024dp-mdpi")
    fun `feature graphic in English`() = featureGraphic("en-US", "Call blocker", "Unwanted calls declined silently, with no internet and no ads")

    private fun screenshots(locale: String, sample: Sample) {
        var tab by mutableStateOf(Tab.BLOCKING)
        compose.setContent {
            SossegoTheme {
                Frame(tab) { padding ->
                    when (tab) {
                        Tab.BLOCKING -> HomeScreen(
                            settings = Settings(blockHidden = true),
                            device = DeviceState(screeningEnabled = true, canDeclineContacts = true, contactsGranted = true),
                            now = LocalDate.now().atTime(14, 30),
                            actions = HomeActions({}, {}, {}, {}, {}, {}, {}, {}, {}),
                            contentPadding = padding,
                        )
                        Tab.RECENTS -> RecentsScreen(
                            calls = sample.blocked(),
                            callLogShown = false,
                            filter = RecentsFilter.ALL,
                            onFilter = {},
                            actions = RecentsActions({}, {}, {}, {}, {}, {}),
                            contentPadding = padding,
                            zone = ZoneId.systemDefault(),
                            callLogAvailable = BuildConfig.CALL_LOG,
                        )
                        Tab.LISTS -> ListsScreen(sample.entries, ListsActions({ _, _, _ -> }, {}, { null }, {}), padding)
                        Tab.DIALPAD -> DialpadScreen(sample.contacts, null, DialpadActions({}, {}, {}), padding)
                        Tab.CONTACTS -> ContactsScreen(sample.contacts, ContactsActions({}, {}, {}, {}), padding)
                    }
                }
            }
        }
        shoot("$locale/images/phoneScreenshots/1.png")
        tab = Tab.RECENTS
        shoot("$locale/images/phoneScreenshots/2.png")
        tab = Tab.LISTS
        shoot("$locale/images/phoneScreenshots/3.png")
        tab = Tab.DIALPAD
        compose.waitForIdle()
        sample.typed.forEach { compose.onNodeWithTag(dialKeyTag(it)).performClick() }
        shoot("$locale/images/phoneScreenshots/4.png")
        tab = Tab.CONTACTS
        shoot("$locale/images/phoneScreenshots/5.png")
    }

    /** The app's top bar and tabs around a screen, as in [MainRoute]. */
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Frame(tab: Tab, content: @Composable (PaddingValues) -> Unit) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(tabTitle(tab))) },
                    actions = {
                        if (tab == Tab.LISTS) {
                            IconButton(onClick = {}) { Icon(painterResource(R.drawable.ic_more_vert), contentDescription = null) }
                        }
                    },
                )
            },
            bottomBar = { TabBar(tab) {} },
            content = content,
        )
    }

    private fun featureGraphic(locale: String, title: String, text: String) {
        compose.setContent {
            Row(
                Modifier.testTag(IMAGE).size(1024.dp, 500.dp).background(colorResource(R.color.brand_teal)).padding(horizontal = 72.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.requiredSize(360.dp))
                Spacer(Modifier.width(32.dp))
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Sossego", color = Color.White, fontSize = 88.sp, fontWeight = FontWeight.Bold)
                    Text(title, color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.Medium)
                    Text(text, color = Color.White.copy(alpha = 0.85f), fontSize = 28.sp, lineHeight = 36.sp)
                }
            }
        }
        save(compose.onNodeWithTag(IMAGE).captureToImage(), "$locale/images/featureGraphic.png")
    }

    private fun shoot(path: String) {
        compose.waitForIdle()
        save(compose.onRoot().captureToImage(), path)
    }

    private fun save(image: ImageBitmap, path: String) {
        val file = File(out, path)
        file.parentFile!!.mkdirs()
        file.outputStream().use { image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Made-up names and numbers for each language. */
    private class Sample(val entries: List<ListEntry>, val contacts: List<Contact>, val typed: String, labels: List<String>) {
        private val telemarketingLabel = labels[0]

        fun blocked(): List<RecentCall> {
            val today = LocalDate.now()
            fun at(daysAgo: Long, hour: Int, minute: Int) =
                LocalDateTime.of(today.minusDays(daysAgo), java.time.LocalTime.of(hour, minute)).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            return listOf(
                RecentCall(at(0, 13, 52), "03031234567", CallKind.BLOCKED, reason = Reason.TELEMARKETING, blockId = 1),
                RecentCall(at(0, 11, 8), "11912345678", CallKind.BLOCKED, name = entries.first { it.pattern == "11912345678" }.label, reason = Reason.BLOCK_LISTED, blockId = 2),
                RecentCall(at(0, 9, 15), "", CallKind.BLOCKED, reason = Reason.HIDDEN, blockId = 3),
                RecentCall(at(1, 19, 41), "03037654321", CallKind.BLOCKED, reason = Reason.TELEMARKETING, blockId = 4),
                RecentCall(at(1, 16, 3), "40031234", CallKind.BLOCKED, name = telemarketingLabel, reason = Reason.BLOCK_LISTED, blockId = 5),
                RecentCall(at(1, 10, 27), "03035550000", CallKind.BLOCKED, reason = Reason.TELEMARKETING, blockId = 6),
                RecentCall(at(2, 20, 12), "11912345678", CallKind.BLOCKED, name = entries.first { it.pattern == "11912345678" }.label, reason = Reason.BLOCK_LISTED, blockId = 7),
                RecentCall(at(2, 8, 49), "", CallKind.BLOCKED, reason = Reason.HIDDEN, blockId = 8),
            )
        }

        companion object {
            private fun block(match: Match, number: String, label: String, id: Long) = ListEntry.of(ListKind.BLOCK, match, number, label, id)!!
            private fun allow(number: String, label: String, id: Long) = ListEntry.of(ListKind.ALLOW, Match.EXACT, number, label, id)!!

            val PT = Sample(
                entries = listOf(
                    block(Match.PREFIX, "0303", "Telemarketing", 1),
                    block(Match.PREFIX, "4003", "Central de ofertas", 2),
                    block(Match.EXACT, "(11) 91234-5678", "Cobrança insistente", 3),
                    block(Match.PREFIX, "+1", "Estados Unidos", 4),
                    allow("(31) 3222-1100", "Escola das crianças", 5),
                    allow("(11) 3456-7000", "Consultório", 6),
                ),
                contacts = listOf(
                    Contact(1, "Ana Paula", listOf(ContactPhone("(11) 98111-2233", "Celular")), starred = true),
                    Contact(2, "Mãe", listOf(ContactPhone("(31) 99222-3344", "Celular")), starred = true),
                    Contact(3, "Carlos Pereira", listOf(ContactPhone("(21) 97333-4455", "Celular"))),
                    Contact(4, "Helena Martins", listOf(ContactPhone("(11) 3222-5566", "Trabalho"))),
                    Contact(5, "José Almeida", listOf(ContactPhone("(31) 3333-2222", "Casa"), ContactPhone("(31) 98888-7777", "Celular"))),
                    Contact(6, "Maria Souza", listOf(ContactPhone("(11) 98765-4321", "Celular"))),
                    Contact(7, "Mariana Costa", listOf(ContactPhone("(41) 99666-7788", "Celular"))),
                    Contact(8, "Paulo Henrique", listOf(ContactPhone("(51) 98555-6677", "Celular"))),
                    Contact(9, "Rafael Lima", listOf(ContactPhone("(62) 99444-5566", "Celular"))),
                ),
                // M-A-R-I on the keys.
                typed = "6274",
                labels = listOf("Central de ofertas"),
            )

            val EN = Sample(
                entries = listOf(
                    block(Match.PREFIX, "0303", "Telemarketing", 1),
                    block(Match.PREFIX, "4003", "Sales line", 2),
                    block(Match.EXACT, "(11) 91234-5678", "Pushy debt collector", 3),
                    block(Match.PREFIX, "+1", "United States", 4),
                    allow("(31) 3222-1100", "Kids' school", 5),
                    allow("(11) 3456-7000", "Doctor's office", 6),
                ),
                contacts = listOf(
                    Contact(1, "Anna Parker", listOf(ContactPhone("(11) 98111-2233", "Mobile")), starred = true),
                    Contact(2, "Mom", listOf(ContactPhone("(31) 99222-3344", "Mobile")), starred = true),
                    Contact(3, "Charles Porter", listOf(ContactPhone("(21) 97333-4455", "Mobile"))),
                    Contact(4, "Helen Martin", listOf(ContactPhone("(11) 3222-5566", "Work"))),
                    Contact(5, "Joseph Allen", listOf(ContactPhone("(31) 3333-2222", "Home"), ContactPhone("(31) 98888-7777", "Mobile"))),
                    Contact(6, "Mary Smith", listOf(ContactPhone("(11) 98765-4321", "Mobile"))),
                    Contact(7, "Marianne Cole", listOf(ContactPhone("(41) 99666-7788", "Mobile"))),
                    Contact(8, "Paul Henry", listOf(ContactPhone("(51) 98555-6677", "Mobile"))),
                    Contact(9, "Ralph Lewis", listOf(ContactPhone("(62) 99444-5566", "Mobile"))),
                ),
                // M-A-R-Y on the keys.
                typed = "6279",
                labels = listOf("Sales line"),
            )
        }
    }

    private companion object {
        const val IMAGE = "store_image"
        val LOCALES = listOf("pt-BR", "en-US")
    }
}
