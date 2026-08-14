package com.rakibulcodes.callerinfo

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

enum class CallerDiagnosticEvent {
    SCREEN_CALLBACK_RECEIVED,
    SCREEN_RESPONSE_SENT,
    SCREEN_NOT_INCOMING,
    SCREEN_PRESENTATION_REJECTED,
    SCREEN_HANDLE_MISSING,
    SCREEN_SCHEME_REJECTED,
    NORMALIZATION_SUCCESS,
    NORMALIZATION_FAILED,
    GENERATION_CREATED,
    GENERATION_ATTACHED,
    GENERATION_ATTACH_FAILED,
    OBSERVED_CALL_SAVE_SUCCESS,
    OBSERVED_CALL_SAVE_FAILED,
    PROCESSOR_STARTED,
    INITIAL_PRESENTATION_REQUESTED,
    OVERLAY_START_REQUESTED,
    OVERLAY_PRESENTED,
    OVERLAY_START_FAILED,
    FALLBACK_NOTIFICATION_REQUESTED,
    LOCAL_CACHE_HIT,
    MANUAL_FORCE_REFRESH_REQUESTED,
    MANUAL_GENERATION_CREATED,
    NETWORK_AVAILABLE,
    NETWORK_UNAVAILABLE,
    REMOTE_LOOKUP_STARTED,
    REMOTE_AUTH_NOT_READY,
    REMOTE_SEND_STARTED,
    REMOTE_SEND_SUCCEEDED,
    REMOTE_RESPONSE_CORRELATED,
    REMOTE_USEFUL_RESULT,
    REMOTE_FAILURE,
    PERSIST_STARTED,
    PERSIST_SUCCESS,
    PERSIST_FAILURE,
    PRESENTATION_STALE,
    GENERATION_INVALIDATED,
    JOB_CANCELLED
}

enum class CallerDiagnosticStatus {
    ACTIVE_NETWORK_VALIDATED,
    ACTIVE_NETWORK_UNVALIDATED,
    NO_ACTIVE_NETWORK,
    NO_NETWORK_CAPABILITIES,
    NO_INTERNET_CAPABILITY,
    TELEGRAM_NOT_READY,
    TEMPORARY_MESSAGE_CREATED,
    SERVER_ID_REMAPPED,
    CONNECTIVITY_UNAVAILABLE,
    TEMPORARY_TRANSPORT_FAILURE,
    AUTHENTICATION_NOT_READY,
    RATE_LIMITED,
    RESPONSE_TIMEOUT,
    UNCORRELATED_RESPONSE,
    PERMANENT_NOT_FOUND,
    PARSING_OR_PROTOCOL_FAILURE,
    PERSISTENCE_FAILURE,
    CANCELLED,
    REJECTED_AFTER_USER_CLEAR,
    LOCKED_CALLER_CARD,
    UNLOCKED_OVERLAY,
    OVERLAY_PERMISSION_MISSING,
    START_NOT_ALLOWED,
    SECURITY_EXCEPTION
}

data class CallerDiagnosticEntry(
    val timestampMillis: Long,
    val generation: Long?,
    val event: CallerDiagnosticEvent,
    val status: CallerDiagnosticStatus? = null
)

interface CallerDiagnosticStorage {
    fun read(): String?
    fun write(value: String)
    fun clear()
}

class CallerDiagnosticTrail(
    private val storage: CallerDiagnosticStorage,
    private val maxEvents: Int = DEFAULT_MAX_EVENTS,
    private val now: () -> Long = System::currentTimeMillis
) {
    @Synchronized
    fun record(
        event: CallerDiagnosticEvent,
        generation: Long? = null,
        status: CallerDiagnosticStatus? = null
    ) {
        val updated = (entries() + CallerDiagnosticEntry(now(), generation, event, status))
            .takeLast(maxEvents.coerceAtLeast(1))
        storage.write(updated.joinToString("\n", transform = ::encode))
    }

    @Synchronized
    fun entries(): List<CallerDiagnosticEntry> = storage.read()
        .orEmpty()
        .lineSequence()
        .mapNotNull(::decode)
        .toList()

    @Synchronized
    fun clear() = storage.clear()

    @Synchronized
    fun summary(limit: Int = DEFAULT_SUMMARY_EVENTS): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'UTC'", Locale.ROOT).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        return entries().takeLast(limit.coerceAtLeast(1)).joinToString("\n") { entry ->
            buildString {
                append(formatter.format(Date(entry.timestampMillis)))
                append("  ")
                append(entry.event.name)
                entry.generation?.let { append("  generation=").append(it) }
                entry.status?.let { append("  status=").append(it.name) }
            }
        }.ifBlank { "No caller diagnostics recorded" }
    }

    private fun encode(entry: CallerDiagnosticEntry): String = listOf(
        entry.timestampMillis.toString(),
        entry.generation?.toString().orEmpty(),
        entry.event.name,
        entry.status?.name.orEmpty()
    ).joinToString("|")

    private fun decode(value: String): CallerDiagnosticEntry? {
        val parts = value.split('|')
        if (parts.size != 4) return null
        return runCatching {
            CallerDiagnosticEntry(
                timestampMillis = parts[0].toLong(),
                generation = parts[1].takeIf(String::isNotBlank)?.toLong(),
                event = CallerDiagnosticEvent.valueOf(parts[2]),
                status = parts[3].takeIf(String::isNotBlank)
                    ?.let(CallerDiagnosticStatus::valueOf)
            )
        }.getOrNull()
    }

    companion object {
        const val DEFAULT_MAX_EVENTS = 75
        private const val DEFAULT_SUMMARY_EVENTS = 50
    }
}

class SharedPreferencesCallerDiagnosticStorage(context: Context) : CallerDiagnosticStorage {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    override fun read(): String? = preferences.getString(KEY_EVENTS, null)

    override fun write(value: String) {
        preferences.edit().putString(KEY_EVENTS, value).apply()
    }

    override fun clear() {
        preferences.edit().remove(KEY_EVENTS).apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "CallerInfoDiagnostics"
        const val KEY_EVENTS = "bounded_events"
    }
}

object CallerDiagnostics {
    @Volatile private var instance: CallerDiagnosticTrail? = null

    fun getInstance(context: Context): CallerDiagnosticTrail =
        instance ?: synchronized(this) {
            instance ?: CallerDiagnosticTrail(
                SharedPreferencesCallerDiagnosticStorage(context)
            ).also { instance = it }
        }
}
