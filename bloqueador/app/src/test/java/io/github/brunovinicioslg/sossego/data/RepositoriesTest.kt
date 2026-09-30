package io.github.brunovinicioslg.sossego.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.common.truth.Truth.assertThat
import io.github.brunovinicioslg.sossego.core.lists.ListEntry
import io.github.brunovinicioslg.sossego.core.lists.ListKind
import io.github.brunovinicioslg.sossego.core.lists.Match
import io.github.brunovinicioslg.sossego.core.number.PhoneNumbers
import io.github.brunovinicioslg.sossego.core.rules.Mode
import io.github.brunovinicioslg.sossego.core.rules.Reason
import io.github.brunovinicioslg.sossego.core.rules.Schedule
import io.github.brunovinicioslg.sossego.core.rules.Settings
import java.io.File
import java.time.DayOfWeek
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class RepositoriesTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val context = RuntimeEnvironment.getApplication()
    private val db = SossegoDatabase(context, name = null) // in memory
    private val lists = ListRepository(db, clock = { 1000L })
    private val history = HistoryRepository(db)

    private fun entry(list: ListKind, typed: String, match: Match = Match.EXACT, label: String = "") =
        ListEntry.of(list, match, typed, label)!!

    @Test
    fun `added entries decide calls right away`() {
        assertThat(lists.add(entry(ListKind.BLOCK, "(11) 3333-4444", label = "Loja"))).isEqualTo(ListRepository.Added.NEW)
        val found = lists.matcher().match(PhoneNumbers.key("+55 11 3333-4444"))
        assertThat(found?.list).isEqualTo(ListKind.BLOCK)
        assertThat(found?.label).isEqualTo("Loja")
        assertThat(lists.entries.value).hasSize(1)
    }

    @Test
    fun `adding again only updates the name`() {
        lists.add(entry(ListKind.BLOCK, "11 3333-4444", label = "Loja"))
        assertThat(lists.add(entry(ListKind.BLOCK, "113333 4444"))).isEqualTo(ListRepository.Added.UPDATED)
        assertThat(lists.all().single().label).isEqualTo("Loja") // an empty name keeps the old one
        lists.add(entry(ListKind.BLOCK, "11 3333-4444", label = "Loja nova"))
        assertThat(lists.all().single().label).isEqualTo("Loja nova")
    }

    @Test
    fun `a number moves between the lists instead of being on both`() {
        lists.add(entry(ListKind.ALLOW, "21 99999-0000"))
        assertThat(lists.add(entry(ListKind.BLOCK, "21 99999-0000"))).isEqualTo(ListRepository.Added.MOVED)
        assertThat(lists.all().map { it.list }).containsExactly(ListKind.BLOCK)
        assertThat(lists.matcher().match(PhoneNumbers.key("21 99999-0000"))?.list).isEqualTo(ListKind.BLOCK)
    }

    @Test
    fun `exact numbers and prefixes with the same digits are different entries`() {
        lists.add(entry(ListKind.BLOCK, "4003", Match.PREFIX))
        lists.add(entry(ListKind.ALLOW, "4003"))
        assertThat(lists.all()).hasSize(2)
    }

    @Test
    fun `entries can be changed and deleted`() {
        lists.add(entry(ListKind.BLOCK, "11 3333-4444"))
        val id = lists.all().single().id
        lists.replace(id, entry(ListKind.BLOCK, "0303", Match.PREFIX))
        assertThat(lists.all().single().pattern).isEqualTo("0303")
        assertThat(lists.matcher().match(PhoneNumbers.key("11 3333-4444"))).isNull()
        lists.delete(lists.all().single().id)
        assertThat(lists.all()).isEmpty()
        assertThat(lists.matcher().isEmpty).isTrue()
    }

    @Test
    fun `import counts only new numbers`() {
        lists.add(entry(ListKind.BLOCK, "11 3333-4444"))
        val added = lists.import(listOf(entry(ListKind.BLOCK, "11 3333-4444"), entry(ListKind.ALLOW, "0800", Match.PREFIX)))
        assertThat(added).isEqualTo(1)
        assertThat(lists.all()).hasSize(2)
    }

    @Test
    fun `lists survive a restart`() {
        val file = File(folder.root, "sossego.db")
        ListRepository(SossegoDatabase(context, file.path)).add(entry(ListKind.BLOCK, "11 3333-4444"))
        val reopened = ListRepository(SossegoDatabase(context, file.path))
        assertThat(reopened.matcher().match("1133334444")?.list).isEqualTo(ListKind.BLOCK)
    }

    @Test
    fun `history keeps blocked calls newest first`() {
        history.record(BlockedCall(time = 100, key = "1133334444", reason = Reason.BLOCK_LISTED))
        history.record(BlockedCall(time = 200, key = "", reason = Reason.HIDDEN, silenced = true))
        val recent = history.recent.value!!
        assertThat(recent.map { it.time }).containsExactly(200L, 100L).inOrder()
        assertThat(recent.first().silenced).isTrue()
        assertThat(history.lastBlockedAt("1133334444")).isEqualTo(100L)
        assertThat(history.lastBlockedAt("")).isNull()
        assertThat(history.countSince(150)).isEqualTo(1)
        assertThat(history.recordedSince("", Reason.HIDDEN, 150)).isTrue()
        assertThat(history.recordedSince("", Reason.BLOCK_ALL, 150)).isFalse()
    }

    @Test
    fun `history keeps only the latest 500`() {
        repeat(510) { history.record(BlockedCall(time = it.toLong(), key = "1", reason = Reason.BLOCK_LISTED)) }
        val recent = history.load()
        assertThat(recent).hasSize(500)
        assertThat(recent.last().time).isEqualTo(10L)
    }

    @Test
    fun `history entries can be deleted one by one or all`() {
        history.record(BlockedCall(time = 1, key = "1", reason = Reason.BLOCK_LISTED))
        history.record(BlockedCall(time = 2, key = "2", reason = Reason.BLOCK_LISTED))
        history.delete(history.recent.value!!.first().id)
        assertThat(history.recent.value!!.map { it.key }).containsExactly("1")
        history.clear()
        assertThat(history.recent.value).isEmpty()
    }

    @Test
    fun `settings round-trip, schedule included`() = runTest {
        val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { File(folder.root, "s.preferences_pb") }
        val repository = SettingsRepository(store)
        assertThat(repository.settings.first()).isEqualTo(Settings())
        val wanted = Settings(
            enabled = false,
            mode = Mode.ALLOWLIST,
            blockHidden = true,
            schedule = Schedule(enabled = true, startMinute = 60, endMinute = 120, days = setOf(DayOfWeek.MONDAY, DayOfWeek.SUNDAY), mode = Mode.BLOCK_ALL),
        )
        repository.update { wanted }
        assertThat(repository.settings.first()).isEqualTo(wanted)
    }

    @Test
    fun `stored nonsense falls back to valid settings`() = runTest {
        val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { File(folder.root, "bad.preferences_pb") }
        store.edit {
            it[stringPreferencesKey("mode")] = "OFF"
            it[stringPreferencesKey("action")] = "EXPLODE"
            it[intPreferencesKey("schedule_start")] = 99_999
        }
        val settings = SettingsRepository(store).settings.first()
        assertThat(settings.mode).isEqualTo(Mode.BLOCKLIST)
        assertThat(settings.action).isEqualTo(Settings().action)
        assertThat(settings.schedule.startMinute).isEqualTo(Schedule.MINUTES_PER_DAY - 1)
    }
}
