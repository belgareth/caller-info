package com.rakibulcodes.callerinfo

enum class RecentMessageType {
    INBOX,
    SENT
}

data class RecentMessageInteraction(
    val timestampMillis: Long
)

data class RecentMessageRecord(
    val address: String?,
    val type: RecentMessageType?,
    val timestampMillis: Long
)

fun mapRecentMessageType(
    value: Int,
    inboxValue: Int,
    sentValue: Int
): RecentMessageType? = when (value) {
    inboxValue -> RecentMessageType.INBOX
    sentValue -> RecentMessageType.SENT
    else -> null
}

fun interface RecentMessageRecordSource {
    fun findFirstBefore(
        cutoffMillis: Long,
        shouldContinue: () -> Boolean,
        predicate: (RecentMessageRecord) -> Boolean
    ): RecentMessageRecord?
}

class RecentMessageFinder(
    private val recordSource: RecentMessageRecordSource,
    private val featureEnabled: () -> Boolean,
    private val permissionGranted: () -> Boolean
) {
    fun findPreviousMessage(
        incomingNumber: String?,
        cutoffMillis: Long,
        config: NumberNormalizationConfig
    ): RecentMessageInteraction? {
        if (!featureEnabled() || !permissionGranted()) return null

        val normalizedIncoming = normalizePhoneNumber(incomingNumber, config)
        if (normalizedIncoming.isBlank()) return null

        return try {
            recordSource.findFirstBefore(
                cutoffMillis = cutoffMillis,
                shouldContinue = {
                    featureEnabled() && permissionGranted()
                }
            ) { record ->
                record.timestampMillis < cutoffMillis &&
                    record.type != null &&
                    normalizePhoneNumber(record.address, config)
                        .takeIf(String::isNotBlank) == normalizedIncoming
            }?.let { RecentMessageInteraction(it.timestampMillis) }
        } catch (_: Exception) {
            null
        }
    }
}
