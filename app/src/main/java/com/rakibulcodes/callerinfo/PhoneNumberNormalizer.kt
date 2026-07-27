package com.rakibulcodes.callerinfo

import java.util.Locale

private val HIDDEN_PHONE_NUMBERS = setOf(
    "unknown",
    "private",
    "private number",
    "withheld",
    "restricted",
    "anonymous",
    "unavailable"
)

private val PHONE_NUMBER_FORMATTING = Regex("[\\s.()\\[\\]{}-]")
private val PHONE_NUMBER_CHARACTERS = Regex("^\\+?[0-9]+$")
private val ASCII_DIGITS = Regex("^[0-9]+$")

/**
 * Converts supported phone-number representations to a stable lookup and storage key.
 */
fun normalizePhoneNumber(input: String?): String {
    val trimmed = input?.trim().orEmpty()
    if (trimmed.isEmpty()) return ""
    if (trimmed.lowercase(Locale.ROOT) in HIDDEN_PHONE_NUMBERS) return ""

    var number = trimmed.replace(PHONE_NUMBER_FORMATTING, "")
    if (!PHONE_NUMBER_CHARACTERS.matches(number)) return ""

    number = when {
        number.startsWith("00") -> "+${number.substring(2)}"
        number.startsWith("011") -> "+${number.substring(3)}"
        else -> number
    }

    if (number.startsWith("256")) {
        number = "+$number"
    }

    if (number.startsWith("+256")) {
        var subscriber = number.substring(4)
        if (subscriber.startsWith("0")) {
            subscriber = subscriber.substring(1)
        }
        return if (subscriber.length == 9 && ASCII_DIGITS.matches(subscriber)) {
            "+256$subscriber"
        } else {
            ""
        }
    }

    if (!number.startsWith("+")) {
        number = when {
            number.startsWith("0") && number.length == 10 ->
                "+256${number.substring(1)}"
            number.startsWith("7") && number.length == 9 ->
                "+256$number"
            number.startsWith("0880") ->
                "+${number.substring(1)}"
            number.startsWith("880") ->
                "+$number"
            number.startsWith("0") && number.length == 11 ->
                "+88$number"
            number.length == 10 ->
                "+880$number"
            else -> return ""
        }
    }

    return when {
        number.startsWith("+880") ->
            if (number.length == 14 && number[4] in '1'..'9') number else ""
        number.startsWith("+") && number.length in 8..16 ->
            number
        else -> ""
    }
}
