package com.rakibulcodes.callerinfo

import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneNumberNormalizerTest {

    @Test
    fun normalizesLocalNumber() {
        val inputs = listOf(
            "0700123456",
            "700123456",
            "256700123456",
            "+256700123456",
            "+2560700123456",
            "00256700123456",
            "002560700123456",
            "0700 123 456",
            "0700-123-456",
            "(0700) 123456"
        )

        inputs.forEach { input ->
            assertEquals(input, "+256700123456", normalizePhoneNumber(input))
        }
    }

    @Test
    fun normalizesInternationalNumber() {
        val inputs = listOf(
            "+256700123456",
            "00256700123456",
            "0112560700123456"
        )

        inputs.forEach { input ->
            assertEquals(input, "+256700123456", normalizePhoneNumber(input))
        }

        assertEquals("+8801712345678", normalizePhoneNumber("01712345678"))
        assertEquals("+8801712345678", normalizePhoneNumber("1712345678"))
        assertEquals("+8801712345678", normalizePhoneNumber("+8801712345678"))
        assertEquals("+14155552671", normalizePhoneNumber("+14155552671"))
    }

    @Test
    fun removesFormattingCharacters() {
        assertEquals("+256700123456", normalizePhoneNumber("  [0700].123-456  "))
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
            assertEquals(input, "", normalizePhoneNumber(input))
        }
    }

    @Test
    fun rejectsInvalidInput() {
        val inputs = listOf(
            "1234",
            "0700ABC456",
            "++256700123456",
            "+256700123456+"
        )

        inputs.forEach { input ->
            assertEquals(input, "", normalizePhoneNumber(input))
        }
    }
}
