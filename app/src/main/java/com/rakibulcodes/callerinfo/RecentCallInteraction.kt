package com.rakibulcodes.callerinfo

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

enum class RecentCallType {
    INCOMING,
    OUTGOING,
    MISSED
}

data class RecentCallInteraction(
    val type: RecentCallType,
    val timestampMillis: Long
)

enum class RecentInteractionType {
    CALL_INCOMING,
    CALL_OUTGOING,
    CALL_MISSED,
    MESSAGE
}

data class RecentInteraction(
    val type: RecentInteractionType,
    val timestampMillis: Long
)

fun selectNewestInteraction(
    call: RecentCallInteraction?,
    message: RecentMessageInteraction?
): RecentInteraction? {
    if (call == null && message == null) return null
    if (call == null) {
        return RecentInteraction(RecentInteractionType.MESSAGE, message!!.timestampMillis)
    }

    val callInteraction = RecentInteraction(
        type = when (call.type) {
            RecentCallType.INCOMING -> RecentInteractionType.CALL_INCOMING
            RecentCallType.OUTGOING -> RecentInteractionType.CALL_OUTGOING
            RecentCallType.MISSED -> RecentInteractionType.CALL_MISSED
        },
        timestampMillis = call.timestampMillis
    )

    return if (message != null && message.timestampMillis > call.timestampMillis) {
        RecentInteraction(RecentInteractionType.MESSAGE, message.timestampMillis)
    } else {
        callInteraction
    }
}

data class RecentCallRecord(
    val number: String?,
    val type: RecentCallType?,
    val timestampMillis: Long
)

fun mapRecentCallType(
    value: Int,
    incomingValue: Int,
    outgoingValue: Int,
    missedValue: Int
): RecentCallType? = when (value) {
    incomingValue -> RecentCallType.INCOMING
    outgoingValue -> RecentCallType.OUTGOING
    missedValue -> RecentCallType.MISSED
    else -> null
}

fun interface RecentCallRecordSource {
    fun findFirstBefore(
        cutoffMillis: Long,
        predicate: (RecentCallRecord) -> Boolean
    ): RecentCallRecord?
}

class RecentCallFinder(
    private val recordSource: RecentCallRecordSource,
    private val featureEnabled: () -> Boolean,
    private val permissionGranted: () -> Boolean
) {
    fun findPreviousCall(
        incomingNumber: String?,
        cutoffMillis: Long,
        config: NumberNormalizationConfig
    ): RecentCallInteraction? {
        if (!featureEnabled() || !permissionGranted()) return null

        val normalizedIncoming = normalizePhoneNumber(incomingNumber, config)
        if (normalizedIncoming.isBlank()) return null

        return try {
            recordSource.findFirstBefore(cutoffMillis) { record ->
                record.timestampMillis < cutoffMillis &&
                    record.type != null &&
                    normalizePhoneNumber(record.number, config)
                        .takeIf(String::isNotBlank) == normalizedIncoming
            }?.let { record ->
                record.type?.let { RecentCallInteraction(it, record.timestampMillis) }
            }
        } catch (_: Exception) {
            null
        }
    }
}

enum class RecentCallDateStyle {
    TODAY,
    YESTERDAY,
    CURRENT_YEAR,
    PREVIOUS_YEAR
}

object RecentCallDateFormatter {
    fun styleFor(
        timestampMillis: Long,
        nowMillis: Long,
        timeZone: TimeZone
    ): RecentCallDateStyle {
        val event = Calendar.getInstance(timeZone).apply { timeInMillis = timestampMillis }
        val today = Calendar.getInstance(timeZone).apply { timeInMillis = nowMillis }

        if (sameDay(event, today)) return RecentCallDateStyle.TODAY

        val yesterday = today.clone() as Calendar
        yesterday.add(Calendar.DAY_OF_YEAR, -1)
        if (sameDay(event, yesterday)) return RecentCallDateStyle.YESTERDAY

        return if (event.get(Calendar.YEAR) == today.get(Calendar.YEAR)) {
            RecentCallDateStyle.CURRENT_YEAR
        } else {
            RecentCallDateStyle.PREVIOUS_YEAR
        }
    }

    fun format(
        timestampMillis: Long,
        nowMillis: Long,
        locale: Locale,
        timeZone: TimeZone,
        yesterdayLabel: String,
        patternFor: (RecentCallDateStyle) -> String
    ): String {
        val style = styleFor(timestampMillis, nowMillis, timeZone)
        val dateText = SimpleDateFormat(patternFor(style), locale).apply {
            this.timeZone = timeZone
        }.format(timestampMillis)

        return if (style == RecentCallDateStyle.YESTERDAY) {
            "$yesterdayLabel, $dateText"
        } else {
            dateText
        }
    }

    private fun sameDay(first: Calendar, second: Calendar): Boolean =
        first.get(Calendar.ERA) == second.get(Calendar.ERA) &&
            first.get(Calendar.YEAR) == second.get(Calendar.YEAR) &&
            first.get(Calendar.DAY_OF_YEAR) == second.get(Calendar.DAY_OF_YEAR)
}
