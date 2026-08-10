package com.rakibulcodes.callerinfo.data

import com.rakibulcodes.callerinfo.data.database.CallerInfoEntity
import org.drinkless.tdlib.TdApi

/**
 * Compatibility parser intentionally based on the last known live-working lookup path
 * from test.7. It preserves the legacy TDLib entity -> HTML conversion and field
 * extraction semantics, while the repository applies the newer correlation and
 * useful-information safety checks before persistence.
 */
internal object LegacyWorkingResponseParser {
    fun isFinalResponse(text: String): Boolean =
        !text.contains("Searching...", ignoreCase = true) &&
            listOf(
                "Country:",
                "Not Found",
                "exceeded",
                "Says:",
                "Name:",
                "invalid number",
                "Oops"
            ).any { text.contains(it, ignoreCase = true) }

    fun parse(
        number: String,
        formattedText: TdApi.FormattedText,
        nowMillis: Long = System.currentTimeMillis()
    ): CallerInfoEntity {
        val responseText = formattedText.toLegacyHtml()

        var error: String? = "Internal Processing Error"
        if (
            responseText.contains("invalid number", ignoreCase = true) ||
            responseText.contains("Oops", ignoreCase = true)
        ) {
            error = "Invalid Number"
        } else if (responseText.contains("exceeded your daily search limit", ignoreCase = true)) {
            val timeMatch = Regex("reset in\\s+(.*?)$", RegexOption.IGNORE_CASE).find(responseText)
            error = "Daily Limit Exceeded"
            if (timeMatch != null) {
                error += ". Resets in ${timeMatch.groupValues[1].trim()}"
            }
        } else if (responseText.contains("Limit exceeded", ignoreCase = true)) {
            error = "Limit Exceeded"
        } else {
            error = null
        }

        val country = Regex(
            "Country:\\s*([^<]+)(?:<\\/strong>)?",
            RegexOption.IGNORE_CASE
        ).find(responseText)?.groupValues?.get(1)?.trim()

        val names = linkedSetOf<String>()
        var carrier: String? = null
        var email: String? = null
        var location: String? = null
        var address1: String? = null
        var address2: String? = null

        val keyValues = Regex(
            "<strong>([^<]+?):\\s*<\\/strong>\\s*<code>(.*?)<\\/code>"
        ).findAll(responseText)

        keyValues.forEach { match ->
            val key = match.groupValues[1].trim().lowercase().replace(Regex("\\s"), "_")
            val value = match.groupValues[2].replace(Regex("<[^>]*>?"), "").trim()
            if (value.isEmpty() || value.equals("Not Found", ignoreCase = true)) return@forEach

            when (key) {
                "name" -> names += value
                "carrier" -> if (carrier == null) carrier = value
                "email" -> if (email == null) email = value
                "location" -> if (location == null) location = value
                "address1", "address_1" -> if (address1 == null) address1 = value
                "address2", "address_2" -> if (address2 == null) address2 = value
            }
        }

        return CallerInfoEntity(
            number = number,
            country = country,
            name = names.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: "Unknown",
            carrier = carrier,
            email = email,
            location = location,
            address1 = address1,
            address2 = address2,
            error = error,
            timestamp = nowMillis,
            lastSuccessfullyUpdatedMillis = if (error == null) nowMillis else null
        )
    }

    internal fun TdApi.FormattedText.toLegacyHtml(): String {
        val insertions = mutableListOf<Pair<Int, String>>()
        entities?.forEach { entity ->
            val tag = when (entity.type) {
                is TdApi.TextEntityTypeBold -> "strong"
                is TdApi.TextEntityTypeCode -> "code"
                else -> null
            }
            if (tag != null) {
                insertions += entity.offset to "<$tag>"
                insertions += entity.offset + entity.length to "</$tag>"
            }
        }

        var resultText = text
        insertions.sortedByDescending { it.first }.forEach { (offset, tag) ->
            if (offset in 0..resultText.length) {
                resultText = resultText.substring(0, offset) + tag + resultText.substring(offset)
            }
        }
        return resultText
    }
}
