package com.rakibulcodes.callerinfo

import java.util.Locale

data class NumberNormalizationConfig(
    val callingCode: String = "",
    val localPrefix: String = "",
    val nationalNumberLength: Int? = null,
    val acceptWithoutPrefix: Boolean = false
)

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
private const val MAX_INTERNATIONAL_DIGITS = 15
private const val MIN_GENERIC_INTERNATIONAL_DIGITS = 7

fun parseNumberNormalizationConfig(
    callingCode: String?,
    localPrefix: String?,
    nationalNumberLength: String?,
    acceptWithoutPrefix: Boolean
): NumberNormalizationConfig {
    val code = callingCode.orEmpty()
    val prefix = localPrefix.orEmpty()
    val length = nationalNumberLength?.toIntOrNull()

    return if (
        isValidCallingCode(code) &&
        isValidLocalPrefix(prefix) &&
        isValidNationalNumberLength(code, length)
    ) {
        NumberNormalizationConfig(code, prefix, length, acceptWithoutPrefix)
    } else {
        NumberNormalizationConfig()
    }
}

fun isValidCallingCode(value: String): Boolean =
    value.length in 1..3 && ASCII_DIGITS.matches(value) && !value.startsWith("0")

fun isValidLocalPrefix(value: String): Boolean =
    value.isEmpty() || ASCII_DIGITS.matches(value)

fun isValidNationalNumberLength(callingCode: String, value: Int?): Boolean =
    value != null &&
        value > 0 &&
        isValidCallingCode(callingCode) &&
        callingCode.length + value <= MAX_INTERNATIONAL_DIGITS

fun normalizePhoneNumber(
    input: String?,
    config: NumberNormalizationConfig
): String {
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

    val validConfig = config.takeIf {
        isValidCallingCode(it.callingCode) &&
            isValidLocalPrefix(it.localPrefix) &&
            isValidNationalNumberLength(it.callingCode, it.nationalNumberLength)
    }

    if (number.startsWith("+")) {
        return normalizeInternationalNumber(number.substring(1), validConfig)
    }

    if (validConfig == null) return ""
    return normalizeLocalNumber(number, validConfig)
}

private fun normalizeInternationalNumber(
    digits: String,
    config: NumberNormalizationConfig?
): String {
    if (!ASCII_DIGITS.matches(digits) || digits.length > MAX_INTERNATIONAL_DIGITS) return ""

    if (config != null && digits.startsWith(config.callingCode)) {
        val remainder = digits.substring(config.callingCode.length)
        val withoutPrefix = removeConfiguredPrefix(remainder, config)
        if (withoutPrefix != null) {
            return "+${config.callingCode}$withoutPrefix"
        }
        if (remainder.length == config.nationalNumberLength) {
            return "+$digits"
        }
    }

    return if (digits.length in MIN_GENERIC_INTERNATIONAL_DIGITS..MAX_INTERNATIONAL_DIGITS) {
        "+$digits"
    } else {
        ""
    }
}

private fun normalizeLocalNumber(
    digits: String,
    config: NumberNormalizationConfig
): String {
    if (!ASCII_DIGITS.matches(digits)) return ""

    if (digits.startsWith(config.callingCode)) {
        val remainder = digits.substring(config.callingCode.length)
        val withoutPrefix = removeConfiguredPrefix(remainder, config)
        if (withoutPrefix != null) {
            return "+${config.callingCode}$withoutPrefix"
        }
        if (remainder.length == config.nationalNumberLength) {
            return "+$digits"
        }
    }

    val withoutPrefix = removeConfiguredPrefix(digits, config)
    if (withoutPrefix != null) {
        return "+${config.callingCode}$withoutPrefix"
    }

    return if (
        config.acceptWithoutPrefix &&
        digits.length == config.nationalNumberLength
    ) {
        "+${config.callingCode}$digits"
    } else {
        ""
    }
}

private fun removeConfiguredPrefix(
    digits: String,
    config: NumberNormalizationConfig
): String? {
    if (config.localPrefix.isEmpty() || !digits.startsWith(config.localPrefix)) return null
    val withoutPrefix = digits.substring(config.localPrefix.length)
    return withoutPrefix.takeIf { it.length == config.nationalNumberLength }
}
