package io.github.brunovinicioslg.sossego.core.dial

import io.github.brunovinicioslg.sossego.core.rules.Reason
import kotlin.math.abs

/** Kinds of calls in the phone's call log. */
enum class CallKind { INCOMING, OUTGOING, MISSED, REJECTED, BLOCKED, VOICEMAIL, OTHER }

/** A call from the phone's call log. */
data class LoggedCall(
    val id: Long,
    val time: Long,
    /** The number as the phone stored it; empty when hidden. */
    val number: String,
    val kind: CallKind,
    val name: String? = null,
    val durationSeconds: Long = 0,
    /** Which SIM, as the phone names it (null when unknown or single SIM). */
    val sim: String? = null,
)

/** A call this app blocked, from its own history. */
data class BlockEvent(
    val id: Long,
    val time: Long,
    /** Number key; empty when hidden or not known (a contact declined by "block everything"). */
    val key: String,
    val reason: Reason,
    val silenced: Boolean = false,
)

/** One line of the recent calls: from the phone's log, or a block (which says why). */
data class RecentCall(
    val time: Long,
    val number: String,
    val kind: CallKind,
    val name: String? = null,
    val durationSeconds: Long = 0,
    val sim: String? = null,
    /** Why it was blocked, when this app blocked it. */
    val reason: Reason? = null,
    /** Blocked by letting it through without ringing (the "silence" action). */
    val silenced: Boolean = false,
    val logId: Long? = null,
    val blockId: Long? = null,
)

enum class RecentsFilter { ALL, MISSED, INCOMING, OUTGOING, BLOCKED }

object Recents {

    /** How far apart the phone's log and this app's history may date the same call. */
    const val SAME_CALL_MS = 15_000L

    /**
     * The phone's log and this app's blocks as one list, newest first, without showing a call
     * twice: a block this app recorded replaces the log line the phone wrote for it (blocked, a
     * contact declined by "block everything", or a silenced call ended as missed).
     *
     * @param key turns a logged number into this app's number key
     */
    fun merge(log: List<LoggedCall>, blocks: List<BlockEvent>, key: (String) -> String): List<RecentCall> {
        val unmatched = blocks.toMutableList()
        val lines = mutableListOf<RecentCall>()
        for (call in log) {
            val numberKey = key(call.number)
            val twin = unmatched.firstOrNull { block ->
                abs(block.time - call.time) <= SAME_CALL_MS &&
                    (block.key.isEmpty() || block.key == numberKey) &&
                    when (call.kind) {
                        CallKind.BLOCKED -> true
                        CallKind.REJECTED -> block.reason == Reason.BLOCK_ALL || !block.silenced
                        CallKind.MISSED -> block.silenced
                        else -> false
                    }
            }
            if (twin != null) {
                unmatched.remove(twin)
                lines += blockLine(twin, call.number.ifEmpty { twin.key }, call.name, call.sim, call.id)
            } else {
                lines += RecentCall(call.time, call.number, call.kind, call.name, call.durationSeconds, call.sim, logId = call.id)
            }
        }
        unmatched.forEach { lines += blockLine(it, it.key, null, null, null) }
        return lines.sortedByDescending { it.time }
    }

    fun filter(calls: List<RecentCall>, filter: RecentsFilter): List<RecentCall> = when (filter) {
        RecentsFilter.ALL -> calls
        RecentsFilter.MISSED -> calls.filter { it.kind == CallKind.MISSED }
        RecentsFilter.INCOMING -> calls.filter { it.kind == CallKind.INCOMING }
        RecentsFilter.OUTGOING -> calls.filter { it.kind == CallKind.OUTGOING }
        RecentsFilter.BLOCKED -> calls.filter { it.kind == CallKind.BLOCKED }
    }

    private fun blockLine(block: BlockEvent, number: String, name: String?, sim: String?, logId: Long?) = RecentCall(
        time = block.time,
        number = number,
        kind = CallKind.BLOCKED,
        name = name,
        sim = sim,
        reason = block.reason,
        silenced = block.silenced,
        logId = logId,
        blockId = block.id,
    )
}
