package com.rakibulcodes.callerinfo

import android.content.Context
import com.rakibulcodes.callerinfo.data.database.CallerInfoEntity

enum class CallerLookupSource {
    CONTACT,
    LOCAL,
    REMOTE
}

data class CallerLookupResult(
    val callerInfo: CallerInfoEntity,
    val source: CallerLookupSource
)

fun visibleLookupSource(
    source: CallerLookupSource?,
    settingEnabled: Boolean,
    hasCallerInformation: Boolean
): CallerLookupSource? =
    source.takeIf { settingEnabled && hasCallerInformation }

fun hasDisplayableCallerInformation(callerInfo: CallerInfoEntity): Boolean =
    hasDisplayableCallerInformation(
        name = callerInfo.name,
        carrier = callerInfo.carrier,
        email = callerInfo.email,
        hasError = callerInfo.error != null
    )

fun hasDisplayableCallerInformation(
    name: String?,
    carrier: String?,
    email: String?,
    hasError: Boolean
): Boolean =
    !hasError &&
        (
            !name.isNullOrBlank() && name != "Unknown" ||
                !carrier.isNullOrBlank() ||
                !email.isNullOrBlank()
            )

class LookupSourcePreferences private constructor(context: Context) {
    private val preferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun isEnabled(): Boolean = preferences.all[KEY_ENABLED] as? Boolean ?: false

    fun setEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    companion object {
        private const val PREFERENCES_NAME = "Settings"
        private const val KEY_ENABLED = "showLookupSource"

        @Volatile
        private var instance: LookupSourcePreferences? = null

        fun getInstance(context: Context): LookupSourcePreferences =
            instance ?: synchronized(this) {
                instance ?: LookupSourcePreferences(context).also { instance = it }
            }
    }
}
