package com.rakibulcodes.callerinfo

import android.content.Context
import android.content.SharedPreferences

class NumberFormattingPreferences private constructor(context: Context) {
    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun getConfig(): NumberNormalizationConfig {
        val values = preferences.all
        if (
            hasInvalidType(values, KEY_CALLING_CODE, String::class.java) ||
            hasInvalidType(values, KEY_LOCAL_PREFIX, String::class.java) ||
            hasInvalidType(values, KEY_NATIONAL_NUMBER_LENGTH, String::class.java) ||
            hasInvalidType(values, KEY_ACCEPT_WITHOUT_PREFIX, Boolean::class.javaObjectType)
        ) {
            return NumberNormalizationConfig()
        }

        return parseNumberNormalizationConfig(
            callingCode = values[KEY_CALLING_CODE] as? String,
            localPrefix = values[KEY_LOCAL_PREFIX] as? String,
            nationalNumberLength = values[KEY_NATIONAL_NUMBER_LENGTH] as? String,
            acceptWithoutPrefix = values[KEY_ACCEPT_WITHOUT_PREFIX] as? Boolean ?: false
        )
    }

    fun getCallingCode(): String = storedString(KEY_CALLING_CODE).orEmpty()

    fun getLocalPrefix(): String = storedString(KEY_LOCAL_PREFIX).orEmpty()

    fun getNationalNumberLength(): String = storedString(KEY_NATIONAL_NUMBER_LENGTH).orEmpty()

    fun getAcceptWithoutPrefix(): Boolean =
        preferences.all[KEY_ACCEPT_WITHOUT_PREFIX] as? Boolean ?: false

    fun save(
        callingCode: String,
        localPrefix: String,
        nationalNumberLength: String,
        acceptWithoutPrefix: Boolean
    ) {
        val parsedLength = nationalNumberLength.toIntOrNull()
        val editor = preferences.edit()

        updateString(editor, KEY_CALLING_CODE, callingCode.takeIf(::isValidCallingCode))
        updateString(editor, KEY_LOCAL_PREFIX, localPrefix.takeIf(::isValidLocalPrefix))
        updateString(
            editor,
            KEY_NATIONAL_NUMBER_LENGTH,
            nationalNumberLength.takeIf {
                parsedLength != null &&
                    parsedLength > 0 &&
                    isValidLocalPrefix(localPrefix) &&
                    (
                        callingCode.isEmpty() ||
                            !isValidCallingCode(callingCode) ||
                            isValidNationalNumberLength(callingCode, parsedLength)
                        )
            }
        )
        editor.putBoolean(KEY_ACCEPT_WITHOUT_PREFIX, acceptWithoutPrefix)
        editor.apply()
    }

    private fun storedString(key: String): String? = preferences.all[key] as? String

    private fun hasInvalidType(
        values: Map<String, *>,
        key: String,
        expectedType: Class<*>
    ): Boolean = values.containsKey(key) && !expectedType.isInstance(values[key])

    private fun updateString(
        editor: SharedPreferences.Editor,
        key: String,
        value: String?
    ) {
        if (value.isNullOrEmpty()) {
            editor.remove(key)
        } else {
            editor.putString(key, value)
        }
    }

    companion object {
        private const val PREFERENCES_NAME = "Settings"
        private const val KEY_CALLING_CODE = "callingCode"
        private const val KEY_LOCAL_PREFIX = "localPrefix"
        private const val KEY_NATIONAL_NUMBER_LENGTH = "nationalNumberLength"
        private const val KEY_ACCEPT_WITHOUT_PREFIX = "acceptNationalNumberWithoutPrefix"

        @Volatile
        private var instance: NumberFormattingPreferences? = null

        fun getInstance(context: Context): NumberFormattingPreferences {
            return instance ?: synchronized(this) {
                instance ?: NumberFormattingPreferences(context).also { instance = it }
            }
        }
    }
}
