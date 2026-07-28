package com.rakibulcodes.callerinfo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PhoneNumberNormalizerTest {
    private val configuredProfile = NumberNormalizationConfig(
        callingCode = "123",
        localPrefix = "0",
        nationalNumberLength = 9,
        acceptWithoutPrefix = false
    )

    @Test
    fun normalizesLocalNumber() {
        assertEquals(
            "+123712345678",
            normalizePhoneNumber("0712345678", configuredProfile)
        )
    }

    @Test
    fun removesFormattingCharacters() {
        val inputs = listOf(
            "  (0712) 345-678  ",
            "[0712].345.678"
        )

        inputs.forEach { input ->
            assertEquals(input, "+123712345678", normalizePhoneNumber(input, configuredProfile))
        }
    }

    @Test
    fun normalizesInternationalNumber() {
        val inputs = listOf(
            "123712345678",
            "+123712345678",
            "+1230712345678",
            "001230712345678",
            "0111230712345678"
        )

        inputs.forEach { input ->
            assertEquals(input, "+123712345678", normalizePhoneNumber(input, configuredProfile))
        }
    }

    @Test
    fun acceptsNumberWithoutPrefixWhenEnabled() {
        val config = configuredProfile.copy(acceptWithoutPrefix = true)

        assertEquals(
            "+123712345678",
            normalizePhoneNumber("712345678", config)
        )
    }

    @Test
    fun rejectsNumberWithoutPrefixWhenDisabled() {
        val result = normalizePhoneNumber("712345678", configuredProfile)

        assertNotEquals("+123712345678", result)
        assertEquals("", result)
    }

    @Test
    fun leavesLocalNumberUnconfigured() {
        assertEquals("", normalizePhoneNumber("0712345678", NumberNormalizationConfig()))
    }

    @Test
    fun preservesInternationalNumberWhenUnconfigured() {
        val inputs = mapOf(
            "+123712345678" to "+123712345678",
            "+9876543210" to "+9876543210",
            "009876543210" to "+9876543210",
            "0119876543210" to "+9876543210"
        )

        inputs.forEach { (input, expected) ->
            assertEquals(input, expected, normalizePhoneNumber(input, NumberNormalizationConfig()))
        }
    }

    @Test
    fun rejectsInvalidCallingCode() {
        val invalidValues = listOf("", "0", "012", "1234", "ABC")

        invalidValues.forEach { callingCode ->
            val config = configuredProfile.copy(callingCode = callingCode)
            assertEquals(callingCode, "", normalizePhoneNumber("0712345678", config))
        }
    }

    @Test
    fun rejectsInvalidNationalNumberLength() {
        val invalidValues = listOf(null, 0, -1, 13)

        invalidValues.forEach { length ->
            val config = configuredProfile.copy(nationalNumberLength = length)
            assertEquals(length?.toString(), "", normalizePhoneNumber("0712345678", config))
        }
    }

    @Test
    fun handlesHiddenCallerValue() {
        val inputs = listOf(
            null,
            "",
            "   ",
            "Unknown",
            "Private",
            "Private number",
            "Withheld",
            "Restricted",
            "Anonymous",
            "Unavailable"
        )

        inputs.forEach { input ->
            assertEquals(input, "", normalizePhoneNumber(input, configuredProfile))
        }
    }

    @Test
    fun rejectsInvalidInput() {
        val inputs = listOf(
            "0712ABC678",
            "1234",
            "++123712345678",
            "+123712345678+"
        )

        inputs.forEach { input ->
            assertEquals(input, "", normalizePhoneNumber(input, configuredProfile))
        }
    }

    @Test
    fun parsesStoredSettings() {
        assertEquals(
            configuredProfile,
            parseNumberNormalizationConfig("123", "0", "9", false)
        )
    }

    @Test
    fun handlesInvalidStoredSettings() {
        val invalidValues = listOf(
            arrayOf("", "0", "9"),
            arrayOf("0", "0", "9"),
            arrayOf("012", "0", "9"),
            arrayOf("1234", "0", "9"),
            arrayOf("ABC", "0", "9"),
            arrayOf("123", "A", "9"),
            arrayOf("123", "0", ""),
            arrayOf("123", "0", "0"),
            arrayOf("123", "0", "-1"),
            arrayOf("123", "0", "13")
        )

        invalidValues.forEach { values ->
            assertEquals(
                values.joinToString(),
                NumberNormalizationConfig(),
                parseNumberNormalizationConfig(values[0], values[1], values[2], true)
            )
        }
    }
}
