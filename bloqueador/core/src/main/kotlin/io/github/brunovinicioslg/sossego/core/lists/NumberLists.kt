package io.github.brunovinicioslg.sossego.core.lists

import io.github.brunovinicioslg.sossego.core.number.PhoneNumbers

enum class ListKind { BLOCK, ALLOW }

enum class Match {
    /** This number only (any way of writing it). */
    EXACT,

    /** Every number that starts with [ListEntry.pattern] ("0303", "11 4003", "+1"). */
    PREFIX,
}

/**
 * A number on the block or the allow list.
 *
 * @param pattern the number's [PhoneNumbers.key] for [Match.EXACT], its [PhoneNumbers.prefixKey]
 *   for [Match.PREFIX]; never empty (see [of])
 */
data class ListEntry(
    val id: Long,
    val list: ListKind,
    val match: Match,
    val pattern: String,
    val label: String = "",
    val createdAt: Long = 0,
) {
    companion object {
        /** An entry for what the user typed or picked, or null when it has no digits. */
        fun of(list: ListKind, match: Match, typed: String, label: String = "", id: Long = 0, createdAt: Long = 0): ListEntry? {
            val pattern = if (match == Match.EXACT) PhoneNumbers.key(typed) else PhoneNumbers.prefixKey(typed)
            if (pattern.isEmpty() || pattern == "+") return null
            return ListEntry(id, list, match, pattern, label.trim(), createdAt)
        }
    }
}

/**
 * Finds the entry that decides a number. The most specific entry wins: an exact number over a
 * prefix, a longer prefix over a shorter one. Between the two lists at the same level, the allow
 * list wins, so a number the user let through is never blocked by a broader rule.
 */
class ListMatcher(entries: List<ListEntry>) {

    private val exact: Map<String, List<ListEntry>> = entries.filter { it.match == Match.EXACT }.groupBy { it.pattern }
    private val prefixes: List<ListEntry> = entries.filter { it.match == Match.PREFIX }
        .sortedWith(compareByDescending<ListEntry> { it.pattern.length }.thenBy { if (it.list == ListKind.ALLOW) 0 else 1 })

    val isEmpty: Boolean = exact.isEmpty() && prefixes.isEmpty()

    /** The deciding entry for a number [key], or null when no list has it. */
    fun match(key: String): ListEntry? {
        if (key.isEmpty()) return null
        val candidates = (listOf(key) + PhoneNumbers.alternateKeys(key)).flatMap { exact[it].orEmpty() }
        candidates.firstOrNull { it.list == ListKind.ALLOW }?.let { return it }
        candidates.firstOrNull()?.let { return it }
        return prefixes.firstOrNull { key.startsWith(it.pattern) }
    }

    companion object {
        val EMPTY = ListMatcher(emptyList())
    }
}
