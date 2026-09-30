package io.github.brunovinicioslg.sossego.screening

import android.Manifest
import android.app.NotificationManager
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.google.common.truth.Truth.assertThat
import io.github.brunovinicioslg.sossego.core.lists.ListEntry
import io.github.brunovinicioslg.sossego.core.lists.ListKind
import io.github.brunovinicioslg.sossego.core.lists.Match
import io.github.brunovinicioslg.sossego.core.rules.BlockAction
import io.github.brunovinicioslg.sossego.core.rules.Mode
import io.github.brunovinicioslg.sossego.core.rules.NotifyMode
import io.github.brunovinicioslg.sossego.core.rules.Reason
import io.github.brunovinicioslg.sossego.core.rules.Schedule
import io.github.brunovinicioslg.sossego.core.rules.Settings
import io.github.brunovinicioslg.sossego.data.HistoryRepository
import io.github.brunovinicioslg.sossego.data.ListRepository
import io.github.brunovinicioslg.sossego.data.SettingsRepository
import io.github.brunovinicioslg.sossego.data.SossegoDatabase
import io.github.brunovinicioslg.sossego.notify.BlockNotifier
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class CallGateTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val context = RuntimeEnvironment.getApplication()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val settings by lazy {
        SettingsRepository(PreferenceDataStoreFactory.create(scope = scope) { File(folder.root, "settings.preferences_pb") })
    }
    private val db = SossegoDatabase(context, name = null)
    private val lists = ListRepository(db)
    private val history = HistoryRepository(db)
    private val contacts = mutableSetOf<String>()

    // Monday 2026-09-28, noon, in UTC.
    private var now = LocalDateTime.of(2026, 9, 28, 12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

    private val gate by lazy {
        CallGate(
            settings,
            lists,
            history,
            { number -> number in contacts },
            BlockNotifier(context, history, zone = { ZoneOffset.UTC }),
            clock = { now },
            zone = { ZoneOffset.UTC },
        )
    }

    @After
    fun tearDown() = scope.cancel()

    private fun set(transform: (Settings) -> Settings) = runBlocking { settings.update(transform) }

    private fun screen(number: String?, withheld: Boolean = number == null) = gate.screen(RingingCall(number, withheld))

    @Test
    fun `block list mode blocks listed numbers and records them`() {
        lists.add(ListEntry.of(ListKind.BLOCK, Match.EXACT, "(11) 3333-4444")!!)
        val verdict = screen("+551133334444")
        assertThat(verdict.decision.reason).isEqualTo(Reason.BLOCK_LISTED)
        val recorded = history.recent.value!!.single()
        assertThat(recorded.key).isEqualTo("1133334444")
        assertThat(recorded.reason).isEqualTo(Reason.BLOCK_LISTED)
        assertThat(recorded.time).isEqualTo(now)
    }

    @Test
    fun `calls that get through are not recorded`() {
        assertThat(screen("11987654321").decision.block).isFalse()
        set { it.copy(enabled = false) }
        assertThat(screen("0303 555 1234").decision.reason).isEqualTo(Reason.OFF)
        assertThat(history.load()).isEmpty()
    }

    @Test
    fun `telemarketing is blocked by default`() {
        assertThat(screen("03035551234").decision.reason).isEqualTo(Reason.TELEMARKETING)
    }

    @Test
    fun `hidden numbers follow their filter`() {
        assertThat(screen(null).decision.block).isFalse()
        set { it.copy(blockHidden = true) }
        assertThat(screen(null).decision.reason).isEqualTo(Reason.HIDDEN)
        // A number the network marked as withheld counts as hidden even if it came along.
        assertThat(screen("11987654321", withheld = true).decision.reason).isEqualTo(Reason.HIDDEN)
    }

    @Test
    fun `allow list mode never blocks a contact, even if the phone sends its call`() {
        set { it.copy(mode = Mode.ALLOWLIST) }
        contacts += "11987654321"
        assertThat(screen("11987654321").decision.reason).isEqualTo(Reason.CONTACT)
        assertThat(screen("21987654321").decision.reason).isEqualTo(Reason.NOT_ALLOWED)
    }

    @Test
    fun `someone who calls again within three minutes gets through`() {
        set { it.copy(mode = Mode.ALLOWLIST) }
        assertThat(screen("21987654321").decision.block).isTrue()
        now += 2 * 60_000
        assertThat(screen("21987654321").decision.reason).isEqualTo(Reason.REPEATED)
        now += 10 * 60_000
        assertThat(screen("21987654321").decision.block).isTrue()
    }

    @Test
    fun `the schedule switches the mode at its times`() {
        set { it.copy(schedule = Schedule(enabled = true, startMinute = 11 * 60, endMinute = 13 * 60, mode = Mode.BLOCK_ALL)) }
        assertThat(screen("11987654321").decision.reason).isEqualTo(Reason.BLOCK_ALL)
        assertThat(gate.mustDeclineRinging()).isNotNull()
        now += 2 * 3_600_000 // 14:00
        assertThat(screen("11987654321").decision.block).isFalse()
        assertThat(gate.mustDeclineRinging()).isNull()
    }

    @Test
    fun `declined ringing calls are recorded once`() {
        set { it.copy(mode = Mode.BLOCK_ALL) }
        val current = gate.mustDeclineRinging()!!
        gate.recordDeclined(null, current)
        now += 2_000
        gate.recordDeclined(null, current) // the phone announced the same ringing again
        assertThat(history.load()).hasSize(1)
        now += 60_000
        gate.recordDeclined(null, current)
        assertThat(history.load()).hasSize(2)
        assertThat(history.recent.value!!.first().reason).isEqualTo(Reason.BLOCK_ALL)
    }

    @Test
    fun `the silence action is recorded as silenced`() {
        set { it.copy(action = BlockAction.SILENCE) }
        screen("03035551234")
        assertThat(history.recent.value!!.single().silenced).isTrue()
    }

    @Test
    fun `notifications are off by default and one per call when asked`() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val manager = shadowOf(context.getSystemService(NotificationManager::class.java))
        screen("03035551234")
        assertThat(manager.allNotifications).isEmpty()
        set { it.copy(notifications = NotifyMode.EACH) }
        screen("03035551234")
        now += 5_000
        screen("03035559999")
        assertThat(manager.allNotifications).hasSize(2)
    }

    @Test
    fun `the daily summary is a single notification with the count`() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val manager = shadowOf(context.getSystemService(NotificationManager::class.java))
        set { it.copy(notifications = NotifyMode.DAILY_COUNT) }
        screen("03035551234")
        now += 5_000
        screen("03035559999")
        val shown = manager.allNotifications.single()
        assertThat(shown.extras.getString("android.title")).contains("2")
    }
}
