package com.rakibulcodes.callerinfo.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.google.gson.Gson
import com.rakibulcodes.callerinfo.data.database.CallerInfoEntity
import com.rakibulcodes.callerinfo.data.database.SecureCallerInfoEntity
import com.rakibulcodes.callerinfo.data.database.PendingCallerLookupEntity
import com.rakibulcodes.callerinfo.data.database.SecurePendingCallerLookupEntity
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal data class CallerCachePayload(
    val number: String,
    val country: String?,
    val name: String?,
    val carrier: String?,
    val email: String?,
    val location: String?,
    val address1: String?,
    val address2: String?,
    val error: String?,
    val userAlias: String?,
    val userNote: String?,
    val favorite: Boolean
)

interface CallerCacheCodec {
    fun lookupKey(normalizedNumber: String): String
    fun encode(info: CallerInfoEntity): SecureCallerInfoEntity
    fun decode(entity: SecureCallerInfoEntity): CallerInfoEntity
    fun encodePending(item: PendingCallerLookupEntity): SecurePendingCallerLookupEntity
    fun decodePending(entity: SecurePendingCallerLookupEntity): PendingCallerLookupEntity
}

class AndroidCallerCacheCodec(
    private val gson: Gson = Gson()
) : CallerCacheCodec {
    override fun lookupKey(normalizedNumber: String): String {
        require(normalizedNumber.isNotBlank())
        val mac = Mac.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256)
        mac.init(getOrCreateHmacKey())
        return Base64.encodeToString(
            mac.doFinal(normalizedNumber.toByteArray(Charsets.UTF_8)),
            Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING
        )
    }

    override fun encode(info: CallerInfoEntity): SecureCallerInfoEntity {
        val payload = CallerCachePayload(
            number = info.number,
            country = info.country,
            name = info.name,
            carrier = info.carrier,
            email = info.email,
            location = info.location,
            address1 = info.address1,
            address2 = info.address2,
            error = info.error,
            userAlias = info.userAlias,
            userNote = info.userNote,
            favorite = info.favorite
        )
        val plaintext = gson.toJson(payload).toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance(AES_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateAesKey())
        val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        val ciphertext = Base64.encodeToString(cipher.doFinal(plaintext), Base64.NO_WRAP)
        return SecureCallerInfoEntity(
            lookupKey = lookupKey(info.number),
            encryptedPayload = "$FORMAT_VERSION:$iv:$ciphertext",
            timestamp = info.timestamp,
            lastSuccessfullyUpdatedMillis = info.lastSuccessfullyUpdatedMillis
        )
    }

    override fun encodePending(item: PendingCallerLookupEntity): SecurePendingCallerLookupEntity {
        val encryptedNumber = encryptText(item.normalizedNumber)
        return SecurePendingCallerLookupEntity(
            lookupKey = lookupKey(item.normalizedNumber),
            encryptedNumber = encryptedNumber,
            createdTimestampMillis = item.createdTimestampMillis,
            nextEligibleRetryTimestampMillis = item.nextEligibleRetryTimestampMillis,
            attemptCount = item.attemptCount
        )
    }

    override fun decodePending(entity: SecurePendingCallerLookupEntity): PendingCallerLookupEntity =
        PendingCallerLookupEntity(
            normalizedNumber = decryptText(entity.encryptedNumber),
            createdTimestampMillis = entity.createdTimestampMillis,
            nextEligibleRetryTimestampMillis = entity.nextEligibleRetryTimestampMillis,
            attemptCount = entity.attemptCount
        )

    override fun decode(entity: SecureCallerInfoEntity): CallerInfoEntity {
        val parts = entity.encryptedPayload.split(':')
        require(parts.size == 3 && parts[0] == FORMAT_VERSION)
        val cipher = Cipher.getInstance(AES_TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            existingAesKey(),
            GCMParameterSpec(GCM_TAG_BITS, Base64.decode(parts[1], Base64.NO_WRAP))
        )
        val payload = gson.fromJson(
            cipher.doFinal(Base64.decode(parts[2], Base64.NO_WRAP)).toString(Charsets.UTF_8),
            CallerCachePayload::class.java
        )
        return CallerInfoEntity(
            number = payload.number,
            country = payload.country,
            name = payload.name,
            carrier = payload.carrier,
            email = payload.email,
            location = payload.location,
            address1 = payload.address1,
            address2 = payload.address2,
            error = payload.error,
            timestamp = entity.timestamp,
            lastSuccessfullyUpdatedMillis = entity.lastSuccessfullyUpdatedMillis,
            userAlias = payload.userAlias,
            userNote = payload.userNote,
            favorite = payload.favorite
        )
    }

    private fun encryptText(value: String): String {
        val cipher = Cipher.getInstance(AES_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateAesKey())
        val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        val ciphertext = Base64.encodeToString(cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        return "$FORMAT_VERSION:$iv:$ciphertext"
    }

    private fun decryptText(envelope: String): String {
        val parts = envelope.split(':')
        require(parts.size == 3 && parts[0] == FORMAT_VERSION)
        val cipher = Cipher.getInstance(AES_TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            existingAesKey(),
            GCMParameterSpec(GCM_TAG_BITS, Base64.decode(parts[1], Base64.NO_WRAP))
        )
        return cipher.doFinal(Base64.decode(parts[2], Base64.NO_WRAP)).toString(Charsets.UTF_8)
    }

    private fun existingAesKey(): SecretKey = existingKey(AES_ALIAS)
    private fun getOrCreateAesKey(): SecretKey = runCatching { existingAesKey() }.getOrElse {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                AES_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        generator.generateKey()
    }

    private fun getOrCreateHmacKey(): SecretKey = runCatching { existingKey(HMAC_ALIAS) }.getOrElse {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, KEYSTORE_PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                HMAC_ALIAS,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
            ).build()
        )
        generator.generateKey()
    }

    private fun existingKey(alias: String): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        return store.getKey(alias, null) as? SecretKey
            ?: throw IllegalStateException("Caller cache key unavailable")
    }

    companion object {
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val AES_ALIAS = "caller_info_cache_aes_v1"
        private const val HMAC_ALIAS = "caller_info_cache_hmac_v1"
        private const val AES_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val FORMAT_VERSION = "v1"
        private const val GCM_TAG_BITS = 128
    }
}
