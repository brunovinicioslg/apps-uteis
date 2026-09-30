package io.github.brunovinicioslg.sossego.core.dial

import io.github.brunovinicioslg.sossego.core.number.PhoneNumbers
import java.text.Normalizer

/** The phone keypad: letters under the digits, and the number shown while it is typed. */
object Dialpad {

    /** The keys and their letters, as printed on phones. */
    val KEYS: List<Pair<Char, String>> = listOf(
        '1' to "", '2' to "ABC", '3' to "DEF",
        '4' to "GHI", '5' to "JKL", '6' to "MNO",
        '7' to "PQRS", '8' to "TUV", '9' to "WXYZ",
        '*' to "", '0' to "+", '#' to "",
    )

    private val DIGIT_OF: Map<Char, Char> = KEYS.flatMap { (digit, letters) -> letters.lowercase().map { it to digit } }
        .filter { it.first in 'a'..'z' }
        .toMap()

    /** What can be typed: digits, the keypad symbols and a leading plus. */
    fun clean(typed: String): String {
        val kept = typed.filter { it in '0'..'9' || it == '*' || it == '#' || it == '+' }
        // "+" only makes sense first (the international prefix).
        return if (kept.startsWith("+")) "+" + kept.substring(1).replace("+", "") else kept.replace("+", "")
    }

    /**
     * Whether a contact [name] matches keys typed on the keypad ("6274" is MARIA): the keys spell
     * the start of one of the name's words, or of the whole name without spaces.
     */
    fun nameMatches(name: String, keys: String): Boolean {
        if (keys.isEmpty() || !keys.all { it in '2'..'9' }) return false
        val words = plain(name).split(' ').filter { it.isNotEmpty() }.map(::keysOf)
        if (words.any { it.startsWith(keys) }) return true
        return words.joinToString("").startsWith(keys)
    }

    /** Whether a phone [number] contains the typed digits, however it was written. */
    fun numberMatches(number: String, typed: String): Boolean {
        val digits = typed.filter { it in '0'..'9' }
        if (digits.isEmpty()) return false
        return number.filter { it in '0'..'9' }.contains(digits) || PhoneNumbers.key(number).contains(digits)
    }

    /**
     * The typed number grouped the Brazilian way as it grows: "(11) 98765-4321",
     * "(21) 3456-7890", "98765-4321", "0800 123 4567". Codes (with * or #), numbers from abroad and
     * short numbers are shown as typed.
     */
    fun format(typed: String): String {
        val number = clean(typed)
        if (number.isEmpty() || '*' in number || '#' in number || number.startsWith("+")) return number
        if (number.startsWith("0")) return formatService(number) ?: number
        return when (number.length) {
            in 0..4 -> number
            in 5..8 -> number.take(4) + "-" + number.substring(4)
            9 -> number.take(5) + "-" + number.substring(5)
            10 -> "(${number.take(2)}) ${number.substring(2, 6)}-${number.substring(6)}"
            11 -> "(${number.take(2)}) ${number.substring(2, 7)}-${number.substring(7)}"
            else -> number
        }
    }

    /** 0800, 0300, 0303, 0500 and 0900 numbers: "0800 123 4567". */
    private fun formatService(number: String): String? {
        if (listOf("0300", "0303", "0500", "0800", "0900").none { number.startsWith(it) }) return null
        return when {
            number.length <= 4 -> number
            number.length <= 7 -> number.take(4) + " " + number.substring(4)
            number.length <= 11 -> number.take(4) + " " + number.substring(4, 7) + " " + number.substring(7)
            else -> number
        }
    }

    private fun keysOf(word: String): String = buildString { word.forEach { c -> append(DIGIT_OF[c] ?: c) } }

    /** Lowercase, without accents, letters and spaces only ("José-Maria" is "jose maria"). */
    private fun plain(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
            .map { if (it in 'a'..'z') it else ' ' }
            .joinToString("")
}
