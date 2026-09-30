package io.github.brunovinicioslg.sossego.core.number

/**
 * Brazilian phone numbers, as the network shows them and as people write them: one [key] per
 * number, so "(11) 98765-4321", "+55 11 98765-4321", "011 98765-4321" and "0 21 11 98765-4321"
 * (with a carrier code) all compare equal.
 */
object PhoneNumbers {

    /** Service numbers dialed with their leading zero: they have no area code. */
    private val SERVICE_PREFIXES = listOf("0300", "0303", "0500", "0800", "0900")

    /** Telemarketing calls must come from 0303 numbers (Anatel, since 2022). */
    private const val TELEMARKETING = "0303"

    /**
     * The number's key: area code and number for Brazilian numbers ("11987654321"), service
     * numbers as dialed ("08001234567"), other countries with "+" and the country code
     * ("+15551234567"), short numbers as they are. Empty when there is no number (hidden).
     */
    fun key(raw: String?): String {
        val digits = digits(raw ?: return "")
        if (digits.isEmpty()) return ""
        if (digits.startsWith("+")) return international(digits.substring(1))
        // Dialed abroad from Brazil: 00 + carrier code (2 digits) + country code + number.
        if (digits.startsWith("00") && digits.length > 6) return international(digits.substring(4))
        return national(digits)
    }

    /**
     * The key a list entry that matches numbers starting with [raw] is compared with: "011" means
     * area code 11 ("11"), "0303" stays (service numbers keep their zero), "+1" is the US.
     */
    fun prefixKey(raw: String): String {
        val digits = digits(raw)
        if (digits.startsWith("+")) {
            val number = digits.substring(1)
            return if (number.startsWith("55")) national(number.substring(2), prefix = true) else "+$number"
        }
        return national(digits, prefix = true)
    }

    /**
     * Other keys the same number may have: a Brazilian mobile number saved before the ninth digit
     * (2012–2016) as "11 8765-4321" is today "11 98765-4321".
     */
    fun alternateKeys(key: String): List<String> {
        if (key.startsWith("+") || !key.all { it.isDigit() } || isService(key)) return emptyList()
        return when {
            key.length == 10 && key[2] in '6'..'9' -> listOf(key.substring(0, 2) + "9" + key.substring(2))
            key.length == 11 && key[2] == '9' && key[3] in '6'..'9' -> listOf(key.substring(0, 2) + key.substring(3))
            else -> emptyList()
        }
    }

    fun isTelemarketing(key: String): Boolean = key.startsWith(TELEMARKETING)

    /** From another country (the key keeps its "+"). */
    fun isInternational(key: String): Boolean = key.startsWith("+")

    /** For showing: "(11) 98765-4321", "0800 123 4567", "4004-1234"; anything else as it is. */
    fun format(key: String): String = when {
        key.startsWith("+") || !key.all { it.isDigit() } -> key
        isService(key) && key.length == 11 -> "${key.take(4)} ${key.substring(4, 7)} ${key.substring(7)}"
        key.length == 11 -> "(${key.take(2)}) ${key.substring(2, 7)}-${key.substring(7)}"
        key.length == 10 -> "(${key.take(2)}) ${key.substring(2, 6)}-${key.substring(6)}"
        key.length == 8 -> "${key.take(4)}-${key.substring(4)}"
        else -> key
    }

    /** Digits only, keeping a leading "+" (the rest of the punctuation people write is dropped). */
    fun digits(raw: String): String {
        val trimmed = raw.trim()
        val digits = trimmed.filter { it in '0'..'9' }
        return if (trimmed.startsWith("+") && digits.isNotEmpty()) "+$digits" else digits
    }

    private fun international(number: String): String =
        if (number.startsWith("55") && number.length > 2) national(number.substring(2)) else "+$number"

    private fun national(digits: String, prefix: Boolean = false): String {
        if (isService(digits)) return digits
        // A prefix the user is typing: "08" or "030" may be the start of a service number.
        if (prefix && digits.startsWith("0") && SERVICE_PREFIXES.any { it.startsWith(digits) }) return digits
        // Service numbers shown without their zero ("800 123 4567"): no area code starts with 30,
        // 50, 80 or 90, so the zero is put back.
        if (!prefix && digits.length == 10 && SERVICE_PREFIXES.any { digits.startsWith(it.substring(1)) }) {
            return "0$digits"
        }
        if (prefix) return if (digits.startsWith("0")) digits.substring(1) else digits
        return when {
            // 0 + carrier code + area code + number (8 or 9 digits).
            digits.startsWith("0") && digits.length in 13..14 -> digits.substring(3)
            // 0 + area code + number.
            digits.startsWith("0") && digits.length in 11..12 -> digits.substring(1)
            // Country code without the "+"; area codes have 2 digits, so 12 or 13 digits means it.
            digits.startsWith("55") && digits.length in 12..13 -> digits.substring(2)
            else -> digits
        }
    }

    private fun isService(digits: String): Boolean = SERVICE_PREFIXES.any { digits.startsWith(it) }
}
