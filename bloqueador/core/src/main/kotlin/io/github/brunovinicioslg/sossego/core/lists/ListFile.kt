package io.github.brunovinicioslg.sossego.core.lists

/**
 * The lists as a text file that also opens in a spreadsheet: one entry per line,
 * `list;match;number;name`, separated by semicolons (what Brazilian spreadsheets expect).
 *
 * ```
 * lista;tipo;numero;nome
 * negra;exato;11987654321;Spam do cartão
 * branca;comeca;0800;
 * ```
 */
object ListFile {

    const val HEADER = "lista;tipo;numero;nome"

    private const val BLOCK = "negra"
    private const val ALLOW = "branca"
    private const val EXACT = "exato"
    private const val PREFIX = "comeca"

    fun write(entries: List<ListEntry>): String = buildString {
        append(HEADER).append('\n')
        for (entry in entries) {
            append(if (entry.list == ListKind.BLOCK) BLOCK else ALLOW).append(';')
            append(if (entry.match == Match.EXACT) EXACT else PREFIX).append(';')
            append(entry.pattern).append(';')
            append(entry.label.replace(';', ',').replace('\n', ' ').replace('\r', ' '))
            append('\n')
        }
    }

    /** What a file held: the entries it could read, and how many lines it skipped. */
    data class Parsed(val entries: List<ListEntry>, val skipped: Int)

    /**
     * Reads a file written by [write], or typed by hand: the header is optional, so is the name,
     * list and match names ignore case and accents, and commas work instead of semicolons.
     */
    fun read(text: String): Parsed {
        val entries = mutableListOf<ListEntry>()
        var skipped = 0
        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim().removePrefix("\uFEFF")
            if (line.isEmpty() || line.equals(HEADER, ignoreCase = true)) continue
            val separator = if (';' in line) ';' else ','
            val fields = line.split(separator, limit = 4).map { it.trim() }
            val list = when (fields.getOrNull(0)?.let(::plain)) {
                BLOCK, "bloquear", "block" -> ListKind.BLOCK
                ALLOW, "permitir", "allow" -> ListKind.ALLOW
                else -> null
            }
            val match = when (fields.getOrNull(1)?.let(::plain)) {
                EXACT, "numero", "exact" -> Match.EXACT
                PREFIX, "comeca com", "prefixo", "prefix" -> Match.PREFIX
                else -> null
            }
            val entry = if (list != null && match != null) {
                ListEntry.of(list, match, fields.getOrElse(2) { "" }, fields.getOrElse(3) { "" })
            } else {
                null
            }
            if (entry == null) skipped++ else entries += entry
        }
        return Parsed(entries.distinctBy { Triple(it.list, it.match, it.pattern) }, skipped)
    }

    private fun plain(value: String): String = value.lowercase()
        .replace('ç', 'c').replace('ê', 'e').replace('é', 'e').replace('á', 'a').replace('ã', 'a')
}
