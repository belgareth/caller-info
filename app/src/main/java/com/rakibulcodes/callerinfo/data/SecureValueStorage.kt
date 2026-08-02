package com.rakibulcodes.callerinfo.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface SecureValueCipher {
    fun encrypt(value: ByteArray): String
    fun decrypt(envelope: String): ByteArray
}

class AndroidKeystoreValueCipher : SecureValueCipher {
    override fun encrypt(value: ByteArray): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        val ciphertext = Base64.encodeToString(cipher.doFinal(value), Base64.NO_WRAP)
        return "$FORMAT_VERSION:$iv:$ciphertext"
    }

    override fun decrypt(envelope: String): ByteArray {
        val parts = envelope.split(':')
        require(parts.size == 3 && parts[0] == FORMAT_VERSION)
        val iv = Base64.decode(parts[1], Base64.NO_WRAP)
        val ciphertext = Base64.decode(parts[2], Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, existingKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ciphertext)
    }

    private fun existingKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        return (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)
            ?: throw IllegalStateException("Secure configuration unavailable")
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            KEYSTORE_PROVIDER
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    companion object {
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val KEY_ALIAS = "caller_info_secure_values_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val FORMAT_VERSION = "v1"
        private const val GCM_TAG_BITS = 128
    }
}

data class TelegramCredentials(
    val apiId: Int,
    val apiHash: String,
    val phone: String
)

sealed interface DatabaseKeyPlan {
    data class Active(val key: ByteArray) : DatabaseKeyPlan
    data class MigrateFromEmpty(val newKey: ByteArray) : DatabaseKeyPlan
    data object Unavailable : DatabaseKeyPlan
}

suspend fun activateDatabaseKeyPlan(
    plan: DatabaseKeyPlan,
    openWithKey: suspend (ByteArray) -> Unit,
    changeKey: suspend (ByteArray) -> Unit,
    markActive: (ByteArray) -> Boolean
) {
    when (plan) {
        is DatabaseKeyPlan.Active -> openWithKey(plan.key)
        is DatabaseKeyPlan.MigrateFromEmpty -> {
            val openedWithEmptyKey = try {
                openWithKey(ByteArray(0))
                true
            } catch (failure: TdlibCommandFailureException) {
                if (failure.errorCode != 401) throw failure
                false
            }
            if (openedWithEmptyKey) {
                changeKey(plan.newKey)
            } else {
                openWithKey(plan.newKey)
            }
            if (!markActive(plan.newKey)) throw TdlibUnavailableException()
        }
        DatabaseKeyPlan.Unavailable -> throw TdlibUnavailableException()
    }
}

class SecureTelegramStorage(
    context: Context,
    private val cipher: SecureValueCipher = AndroidKeystoreValueCipher(),
    private val randomBytes: (Int) -> ByteArray = { size ->
        ByteArray(size).also(SecureRandom()::nextBytes)
    }
) {
    private val preferences =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun migratePlaintextCredentials(): Boolean {
        if (!hasPlaintextCredentials()) {
            return encryptedCredentialsAreReadableOrAbsent()
        }
        val original = TelegramCredentials(
            apiId = preferences.getInt(LEGACY_API_ID, 0),
            apiHash = preferences.getString(LEGACY_API_HASH, "").orEmpty(),
            phone = preferences.getString(LEGACY_PHONE, "").orEmpty()
        )
        return try {
            if (!writeEncryptedCredentials(original)) return false
            val verified = readEncryptedCredentials() == original
            if (!verified) return false
            preferences.edit()
                .remove(LEGACY_API_ID)
                .remove(LEGACY_API_HASH)
                .remove(LEGACY_PHONE)
                .putBoolean(CREDENTIAL_MIGRATION_COMPLETE, true)
                .commit()
        } catch (_: Exception) {
            false
        }
    }

    @Synchronized
    fun saveCredentials(credentials: TelegramCredentials): Boolean {
        return try {
            if (!writeEncryptedCredentials(credentials)) return false
            if (readEncryptedCredentials() != credentials) return false
            preferences.edit()
                .remove(LEGACY_API_ID)
                .remove(LEGACY_API_HASH)
                .remove(LEGACY_PHONE)
                .putBoolean(CREDENTIAL_MIGRATION_COMPLETE, true)
                .commit()
        } catch (_: Exception) {
            false
        }
    }

    @Synchronized
    fun readCredentials(): TelegramCredentials? {
        if (!migratePlaintextCredentials()) return null
        return try {
            readEncryptedCredentials()
        } catch (_: Exception) {
            null
        }
    }

    @Synchronized
    fun clearCredentials(): Boolean =
        preferences.edit()
            .remove(ENCRYPTED_API_ID)
            .remove(ENCRYPTED_API_HASH)
            .remove(ENCRYPTED_PHONE)
            .remove(LEGACY_API_ID)
            .remove(LEGACY_API_HASH)
            .remove(LEGACY_PHONE)
            .remove(CREDENTIAL_MIGRATION_COMPLETE)
            .putBoolean(LOGGED_IN_HINT, false)
            .putBoolean(INITIAL_SETUP_DONE, false)
            .commit()

    @Synchronized
    fun prepareDatabaseKey(hasExistingDatabase: Boolean): DatabaseKeyPlan {
        return try {
            val encryptedCandidate = preferences.getString(ENCRYPTED_DATABASE_KEY, null)
            val state = preferences.getString(DATABASE_KEY_STATE, null)
            if (encryptedCandidate != null) {
                val key = cipher.decrypt(encryptedCandidate)
                if (key.size != DATABASE_KEY_BYTES) return DatabaseKeyPlan.Unavailable
                return if (state == DATABASE_KEY_PENDING_EMPTY_MIGRATION) {
                    DatabaseKeyPlan.MigrateFromEmpty(key)
                } else {
                    DatabaseKeyPlan.Active(key)
                }
            }

            val generated = randomBytes(DATABASE_KEY_BYTES)
            if (generated.size != DATABASE_KEY_BYTES) return DatabaseKeyPlan.Unavailable
            val encrypted = cipher.encrypt(generated)
            val targetState = if (hasExistingDatabase) {
                DATABASE_KEY_PENDING_EMPTY_MIGRATION
            } else {
                DATABASE_KEY_ACTIVE
            }
            val stored = preferences.edit()
                .putString(ENCRYPTED_DATABASE_KEY, encrypted)
                .putString(DATABASE_KEY_STATE, targetState)
                .commit()
            if (!stored || !generated.contentEquals(cipher.decrypt(encrypted))) {
                DatabaseKeyPlan.Unavailable
            } else if (hasExistingDatabase) {
                DatabaseKeyPlan.MigrateFromEmpty(generated)
            } else {
                DatabaseKeyPlan.Active(generated)
            }
        } catch (_: Exception) {
            DatabaseKeyPlan.Unavailable
        }
    }

    @Synchronized
    fun markDatabaseKeyActive(expectedKey: ByteArray): Boolean {
        return try {
            val encrypted = preferences.getString(ENCRYPTED_DATABASE_KEY, null) ?: return false
            if (!expectedKey.contentEquals(cipher.decrypt(encrypted))) return false
            preferences.edit()
                .putString(DATABASE_KEY_STATE, DATABASE_KEY_ACTIVE)
                .commit()
        } catch (_: Exception) {
            false
        }
    }

    @Synchronized
    fun databaseMigrationIsPending(): Boolean =
        preferences.getString(DATABASE_KEY_STATE, null) ==
            DATABASE_KEY_PENDING_EMPTY_MIGRATION

    private fun writeEncryptedCredentials(credentials: TelegramCredentials): Boolean {
        val encryptedId = cipher.encrypt(credentials.apiId.toString().toByteArray(Charsets.UTF_8))
        val encryptedHash = cipher.encrypt(credentials.apiHash.toByteArray(Charsets.UTF_8))
        val encryptedPhone = cipher.encrypt(credentials.phone.toByteArray(Charsets.UTF_8))
        return preferences.edit()
            .putString(ENCRYPTED_API_ID, encryptedId)
            .putString(ENCRYPTED_API_HASH, encryptedHash)
            .putString(ENCRYPTED_PHONE, encryptedPhone)
            .commit()
    }

    private fun readEncryptedCredentials(): TelegramCredentials? {
        val idEnvelope = preferences.getString(ENCRYPTED_API_ID, null) ?: return null
        val hashEnvelope = preferences.getString(ENCRYPTED_API_HASH, null) ?: return null
        val phoneEnvelope = preferences.getString(ENCRYPTED_PHONE, null) ?: return null
        val id = cipher.decrypt(idEnvelope).toString(Charsets.UTF_8).toIntOrNull() ?: return null
        val hash = cipher.decrypt(hashEnvelope).toString(Charsets.UTF_8)
        val phone = cipher.decrypt(phoneEnvelope).toString(Charsets.UTF_8)
        return TelegramCredentials(id, hash, phone)
    }

    private fun encryptedCredentialsAreReadableOrAbsent(): Boolean {
        val hasAnyEncrypted =
            preferences.contains(ENCRYPTED_API_ID) ||
                preferences.contains(ENCRYPTED_API_HASH) ||
                preferences.contains(ENCRYPTED_PHONE)
        return !hasAnyEncrypted || try {
            readEncryptedCredentials() != null
        } catch (_: Exception) {
            false
        }
    }

    private fun hasPlaintextCredentials(): Boolean =
        preferences.contains(LEGACY_API_ID) ||
            preferences.contains(LEGACY_API_HASH) ||
            preferences.contains(LEGACY_PHONE)

    companion object {
        const val PREFERENCES_NAME = "TelegramSettings"
        const val LEGACY_API_ID = "api_id"
        const val LEGACY_API_HASH = "api_hash"
        const val LEGACY_PHONE = "phone"
        private const val ENCRYPTED_API_ID = "secure_api_id"
        private const val ENCRYPTED_API_HASH = "secure_api_hash"
        private const val ENCRYPTED_PHONE = "secure_phone"
        private const val ENCRYPTED_DATABASE_KEY = "secure_tdlib_database_key"
        private const val DATABASE_KEY_STATE = "tdlib_database_key_state"
        private const val DATABASE_KEY_ACTIVE = "active"
        private const val DATABASE_KEY_PENDING_EMPTY_MIGRATION = "pending_empty_migration"
        private const val CREDENTIAL_MIGRATION_COMPLETE = "secure_migration_complete"
        private const val LOGGED_IN_HINT = "is_logged_in"
        private const val INITIAL_SETUP_DONE = "initial_setup_done"
        const val DATABASE_KEY_BYTES = 32
    }
}
