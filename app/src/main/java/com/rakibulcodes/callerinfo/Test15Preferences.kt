package com.rakibulcodes.callerinfo

import android.content.Context

enum class CacheFreshness(val days: Int) {
    SEVEN(7), THIRTY(30), NINETY(90);
    val millis: Long get() = days.toLong() * 24L * 60L * 60L * 1000L
}

enum class CallerCardSize { COMPACT, EXPANDED }
enum class CallerCardPosition { UPPER, CENTER, LOWER }

class Test15Preferences private constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("Settings", Context.MODE_PRIVATE)

    fun cacheFreshness(): CacheFreshness = runCatching {
        CacheFreshness.valueOf(prefs.getString(KEY_CACHE_FRESHNESS, CacheFreshness.THIRTY.name)!!)
    }.getOrDefault(CacheFreshness.THIRTY)

    fun setCacheFreshness(value: CacheFreshness) {
        prefs.edit().putString(KEY_CACHE_FRESHNESS, value.name).apply()
    }

    fun callerCardSize(): CallerCardSize = runCatching {
        CallerCardSize.valueOf(prefs.getString(KEY_CARD_SIZE, CallerCardSize.EXPANDED.name)!!)
    }.getOrDefault(CallerCardSize.EXPANDED)

    fun setCallerCardSize(value: CallerCardSize) {
        prefs.edit().putString(KEY_CARD_SIZE, value.name).apply()
    }

    fun callerCardPosition(): CallerCardPosition = runCatching {
        CallerCardPosition.valueOf(prefs.getString(KEY_CARD_POSITION, CallerCardPosition.CENTER.name)!!)
    }.getOrDefault(CallerCardPosition.CENTER)

    fun setCallerCardPosition(value: CallerCardPosition) {
        prefs.edit().putString(KEY_CARD_POSITION, value.name).apply()
    }

    companion object {
        private const val KEY_CACHE_FRESHNESS = "cache_freshness"
        private const val KEY_CARD_SIZE = "caller_card_size"
        private const val KEY_CARD_POSITION = "caller_card_position"
        @Volatile private var instance: Test15Preferences? = null
        fun getInstance(context: Context): Test15Preferences = instance ?: synchronized(this) {
            instance ?: Test15Preferences(context).also { instance = it }
        }
    }
}

fun callerCacheIsFresh(lastUpdated: Long?, now: Long, freshnessMillis: Long): Boolean =
    lastUpdated != null && now >= lastUpdated && now - lastUpdated < freshnessMillis
