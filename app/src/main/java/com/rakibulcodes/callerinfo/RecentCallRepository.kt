package com.rakibulcodes.callerinfo

import android.content.Context
import android.content.SharedPreferences
import com.rakibulcodes.callerinfo.data.AndroidKeystoreValueCipher
import com.rakibulcodes.callerinfo.data.SecureValueCipher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class RecentCallPreferences private constructor(context: Context) {
    private val preferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun isEnabled(): Boolean = preferences.all[KEY_ENABLED] as? Boolean ?: false

    fun setEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    companion object {
        private const val PREFERENCES_NAME = "Settings"
        private const val KEY_ENABLED = "showPreviousCall"

        @Volatile
        private var instance: RecentCallPreferences? = null

        fun getInstance(context: Context): RecentCallPreferences =
            instance ?: synchronized(this) {
                instance ?: RecentCallPreferences(context).also { instance = it }
            }
    }
}

class RecentCallRepository private constructor(
    context: Context,
    private val store: ObservedRecentCallStore = EncryptedObservedRecentCallStore(context)
) {
    private val applicationContext = context.applicationContext
    private val preferences = RecentCallPreferences.getInstance(applicationContext)
    private val finder = RecentCallFinder(
        recordSource = RecentCallRecordSource { cutoffMillis, predicate ->
            store.findFirstBefore(cutoffMillis, predicate)
        },
        featureEnabled = preferences::isEnabled
    )

    suspend fun recordObservedIncomingCall(
        normalizedNumber: String,
        timestampMillis: Long
    ) = withContext(Dispatchers.IO) {
        if (normalizedNumber.isBlank()) return@withContext
        store.record(
            RecentCallRecord(
                number = normalizedNumber,
                type = RecentCallType.INCOMING,
                timestampMillis = timestampMillis
            )
        )
    }

    suspend fun findPreviousCall(
        incomingNumber: String?,
        cutoffMillis: Long,
        config: NumberNormalizationConfig
    ): RecentCallInteraction? = withContext(Dispatchers.IO) {
        finder.findPreviousCall(incomingNumber, cutoffMillis, config)
    }

    companion object {
        @Volatile
        private var instance: RecentCallRepository? = null

        fun getInstance(context: Context): RecentCallRepository =
            instance ?: synchronized(this) {
                instance ?: RecentCallRepository(context).also { instance = it }
            }
    }
}

interface ObservedRecentCallStore {
    fun record(record: RecentCallRecord)

    fun findFirstBefore(
        cutoffMillis: Long,
        predicate: (RecentCallRecord) -> Boolean
    ): RecentCallRecord?
}

class InMemoryObservedRecentCallStore(
    initialRecords: List<RecentCallRecord> = emptyList()
) : ObservedRecentCallStore {
    private val records = initialRecords.toMutableList()

    override fun record(record: RecentCallRecord) {
        records += record
        records.sortByDescending { it.timestampMillis }
    }

    override fun findFirstBefore(
        cutoffMillis: Long,
        predicate: (RecentCallRecord) -> Boolean
    ): RecentCallRecord? =
        records
            .asSequence()
            .filter { it.timestampMillis < cutoffMillis }
            .firstOrNull(predicate)
}

class EncryptedObservedRecentCallStore(
    context: Context,
    private val cipher: SecureValueCipher = AndroidKeystoreValueCipher(),
    private val maxRecords: Int = MAX_RECORDS
) : ObservedRecentCallStore {
    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    @Synchronized
    override fun record(record: RecentCallRecord) {
        val normalized = record.number?.takeIf(String::isNotBlank) ?: return
        val current = readRecords().toMutableList()
        current.removeAll { it.number == normalized && it.timestampMillis == record.timestampMillis }
        current += record.copy(number = normalized)
        val trimmed = current
            .sortedByDescending { it.timestampMillis }
            .take(maxRecords.coerceAtLeast(1))
        writeRecords(trimmed)
    }

    @Synchronized
    override fun findFirstBefore(
        cutoffMillis: Long,
        predicate: (RecentCallRecord) -> Boolean
    ): RecentCallRecord? =
        readRecords()
            .asSequence()
            .filter { it.timestampMillis < cutoffMillis }
            .sortedByDescending { it.timestampMillis }
            .firstOrNull(predicate)

    private fun readRecords(): List<RecentCallRecord> {
        val envelope = preferences.getString(KEY_RECORDS, null) ?: return emptyList()
        return try {
            val text = cipher.decrypt(envelope).toString(Charsets.UTF_8)
            val array = JSONArray(text)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    add(
                        RecentCallRecord(
                            number = item.optString("number").takeIf(String::isNotBlank),
                            type = item.optString("type")
                                .takeIf(String::isNotBlank)
                                ?.let { runCatching { RecentCallType.valueOf(it) }.getOrNull() },
                            timestampMillis = item.optLong("timestampMillis")
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun writeRecords(records: List<RecentCallRecord>) {
        val array = JSONArray()
        records.forEach { record ->
            array.put(
                JSONObject()
                    .put("number", record.number)
                    .put("type", record.type?.name)
                    .put("timestampMillis", record.timestampMillis)
            )
        }
        val envelope = cipher.encrypt(array.toString().toByteArray(Charsets.UTF_8))
        preferences.edit().putString(KEY_RECORDS, envelope).apply()
    }

    companion object {
        private const val PREFERENCES_NAME = "RecentCallObservations"
        private const val KEY_RECORDS = "encrypted_records"
        private const val MAX_RECORDS = 100
    }
}
