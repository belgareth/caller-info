package com.rakibulcodes.callerinfo

import com.rakibulcodes.callerinfo.data.LegacyWorkingResponseParser
import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyWorkingResponseParserTest {
    @Test
    fun test7BoldCodeEntityResponseParses() {
        val text = "Name: Example Caller\nCarrier: Example Carrier\nCountry: Example Country"
        val entities = arrayOf(
            bold(text, "Name:"), code(text, "Example Caller"),
            bold(text, "Carrier:"), code(text, "Example Carrier")
        )
        val parsed = LegacyWorkingResponseParser.parse(
            "+10000000000",
            TdApi.FormattedText(text, entities),
            nowMillis = 123L
        )
        assertEquals("Example Caller", parsed.name)
        assertEquals("Example Carrier", parsed.carrier)
        assertEquals("Example Country", parsed.country)
        assertNull(parsed.error)
    }

    @Test
    fun emptyParseStillLooksUnknownSoRepositoryCanRejectIt() {
        val parsed = LegacyWorkingResponseParser.parse(
            "+10000000000",
            TdApi.FormattedText("Unrelated response", null),
            nowMillis = 123L
        )
        assertEquals("Unknown", parsed.name)
        assertNull(parsed.carrier)
        assertNull(parsed.email)
        assertNull(parsed.location)
        assertNull(parsed.address1)
        assertNull(parsed.address2)
    }

    @Test
    fun optionalNotFoundFieldDoesNotEraseUsefulField() {
        val text = "Name: Example Caller\nEmail: Not Found"
        val entities = arrayOf(
            bold(text, "Name:"), code(text, "Example Caller"),
            bold(text, "Email:"), code(text, "Not Found")
        )
        val parsed = LegacyWorkingResponseParser.parse(
            "+10000000000",
            TdApi.FormattedText(text, entities),
            nowMillis = 123L
        )
        assertEquals("Example Caller", parsed.name)
        assertNull(parsed.email)
    }

    @Test
    fun observedEditedBotShapeKeepsUsefulCountryWithOptionalNotFoundName() {
        val number = "+10000000000"
        val countryLine = "Country: Example Country"
        val text = buildString {
            append("Number: ")
            append(number)
            append('\n')
            append(countryLine)
            append("\n\nCarrier: Example Carrier\nName: Not Found")
        }
        val entities = arrayOf(
            bold(text, "Number: "),
            entity(text, number, TdApi.TextEntityTypePhoneNumber()),
            bold(text, number),
            bold(text, countryLine),
            bold(text, "Carrier:"),
            bold(text, "Example Carrier"),
            bold(text, "Name:"),
            code(text, "Not Found")
        )

        val parsed = LegacyWorkingResponseParser.parse(
            number,
            TdApi.FormattedText(text, entities),
            nowMillis = 123L
        )

        assertEquals("Example Country", parsed.country)
        assertEquals("Unknown", parsed.name)
        assertNull(parsed.carrier)
        assertNull(parsed.error)
    }

    @Test
    fun finalResponseMarkersMatchLastWorkingBehavior() {
        assertTrue(LegacyWorkingResponseParser.isFinalResponse("Country: Example"))
        assertTrue(LegacyWorkingResponseParser.isFinalResponse("Provider Says:"))
        assertTrue(LegacyWorkingResponseParser.isFinalResponse("Name: Example"))
    }

    private fun bold(text: String, value: String): TdApi.TextEntity = entity(
        text, value, TdApi.TextEntityTypeBold()
    )

    private fun code(text: String, value: String): TdApi.TextEntity = entity(
        text, value, TdApi.TextEntityTypeCode()
    )

    private fun entity(
        text: String,
        value: String,
        type: TdApi.TextEntityType
    ): TdApi.TextEntity {
        val offset = text.indexOf(value)
        require(offset >= 0)
        return TdApi.TextEntity(offset, value.length, type)
    }
}
