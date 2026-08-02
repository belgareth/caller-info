package com.rakibulcodes.callerinfo

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.util.Base64
import android.app.KeyguardManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.rakibulcodes.callerinfo.data.database.AppDatabase
import com.rakibulcodes.callerinfo.data.database.CallerInfoEntity
import com.rakibulcodes.callerinfo.data.database.PendingCallerLookupEntity
import com.rakibulcodes.callerinfo.data.AndroidKeystoreValueCipher
import com.rakibulcodes.callerinfo.data.DatabaseKeyPlan
import com.rakibulcodes.callerinfo.data.SecureTelegramStorage
import com.rakibulcodes.callerinfo.data.SecureValueCipher
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class ComparisonAppInstrumentedTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun removeTestDatabase() {
        context.deleteDatabase(DATABASE_NAME)
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @After
    fun cleanUp() {
        activeIncomingCallGeneration.currentGeneration()?.let { generation ->
            LockedCallerCardController.clear(context, generation)
            activeIncomingCallGeneration.invalidate(generation)
        }
        context.deleteDatabase(DATABASE_NAME)
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        ISOLATED_PREFERENCE_PREFIXES.forEach { prefix ->
            context.getSharedPreferences(
                "${prefix}_${SecureTelegramStorage.PREFERENCES_NAME}",
                Context.MODE_PRIVATE
            ).edit().clear().commit()
        }
    }

    @Test
    fun comparisonApplicationIdIsExact() {
        assertEquals("com.rakibulcodes.callerinfo.test", context.packageName)
    }

    @Test
    fun roomMigrationFromTwoToThreePreservesCallerAsStale() = runBlocking {
        createVersionTwoDatabaseWithCaller()

        val database = openVersionThreeDatabase()
        val saved = database.callerInfoDao().getCallerInfo(TEST_NUMBER)

        assertEquals("Sample caller", saved?.name)
        assertNull(saved?.lastSuccessfullyUpdatedMillis)
        database.close()
    }

    @Test
    fun clearTransactionPreservesSettings() = runBlocking {
        val settings = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        assertTrue(settings.edit().putBoolean(SETTING_KEY, true).commit())
        val database = openVersionThreeDatabase()
        database.callerInfoDao().insertCallerInfo(caller())
        database.pendingCallerLookupDao().insertIfAbsent(
            PendingCallerLookupEntity(TEST_NUMBER, 1, 1, 0)
        )

        database.withTransaction {
            database.callerInfoDao().clearAll()
            database.pendingCallerLookupDao().clearAll()
        }

        assertTrue(database.callerInfoDao().getAllCallerInfo().isEmpty())
        assertEquals(0, database.pendingCallerLookupDao().count())
        assertTrue(settings.getBoolean(SETTING_KEY, false))
        database.close()
    }

    @Test
    fun clearingPendingOnlyPreservesSavedCallerAndSettings() = runBlocking {
        val settings = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        assertTrue(settings.edit().putBoolean(SETTING_KEY, true).commit())
        val database = openVersionThreeDatabase()
        database.callerInfoDao().insertCallerInfo(caller())
        database.pendingCallerLookupDao().insertIfAbsent(
            PendingCallerLookupEntity(TEST_NUMBER, 1, 1, 0)
        )

        database.pendingCallerLookupDao().clearAll()

        assertNotNull(database.callerInfoDao().getCallerInfo(TEST_NUMBER))
        assertEquals(0, database.pendingCallerLookupDao().count())
        assertTrue(settings.getBoolean(SETTING_KEY, false))
        database.close()
    }

    @Test
    fun keystoreEncryptionUsesFreshAuthenticatedEnvelopes() {
        val cipher = AndroidKeystoreValueCipher()
        val value = "synthetic secret".toByteArray()
        val first = cipher.encrypt(value)
        val second = cipher.encrypt(value)

        assertNotEquals(first, second)
        assertTrue(value.contentEquals(cipher.decrypt(first)))
        val tampered = first.dropLast(1) + if (first.last() == 'A') "B" else "A"
        assertTrue(runCatching { cipher.decrypt(tampered) }.isFailure)
    }

    @Test
    fun secureCredentialMigrationVerifiesBeforeRemovingPlaintext() {
        val isolatedContext = IsolatedPreferenceContext(context, "secure_migration_success")
        val preferences = isolatedContext.getSharedPreferences(
            SecureTelegramStorage.PREFERENCES_NAME,
            Context.MODE_PRIVATE
        )
        preferences.edit()
            .putInt(SecureTelegramStorage.LEGACY_API_ID, 12)
            .putString(SecureTelegramStorage.LEGACY_API_HASH, "synthetic hash")
            .putString(SecureTelegramStorage.LEGACY_PHONE, "0000000000")
            .commit()
        val storage = SecureTelegramStorage(isolatedContext, ReversibleTestCipher())

        assertTrue(storage.migratePlaintextCredentials())
        assertEquals(12, storage.readCredentials()?.apiId)
        assertFalse(preferences.contains(SecureTelegramStorage.LEGACY_API_ID))
        assertFalse(preferences.contains(SecureTelegramStorage.LEGACY_API_HASH))
        assertFalse(preferences.contains(SecureTelegramStorage.LEGACY_PHONE))
    }

    @Test
    fun failedCredentialMigrationPreservesPlaintext() {
        val isolatedContext = IsolatedPreferenceContext(context, "secure_migration_failure")
        val preferences = isolatedContext.getSharedPreferences(
            SecureTelegramStorage.PREFERENCES_NAME,
            Context.MODE_PRIVATE
        )
        preferences.edit()
            .putInt(SecureTelegramStorage.LEGACY_API_ID, 12)
            .putString(SecureTelegramStorage.LEGACY_API_HASH, "synthetic hash")
            .putString(SecureTelegramStorage.LEGACY_PHONE, "0000000000")
            .commit()
        val storage = SecureTelegramStorage(isolatedContext, FailingTestCipher())

        assertFalse(storage.migratePlaintextCredentials())
        assertTrue(preferences.contains(SecureTelegramStorage.LEGACY_API_ID))
        assertTrue(preferences.contains(SecureTelegramStorage.LEGACY_API_HASH))
        assertTrue(preferences.contains(SecureTelegramStorage.LEGACY_PHONE))
    }

    @Test
    fun databaseKeyIsNonEmptyEncryptedAndReused() {
        val isolatedContext = IsolatedPreferenceContext(context, "secure_database_key")
        val storage = SecureTelegramStorage(isolatedContext, ReversibleTestCipher())
        val first = storage.prepareDatabaseKey(hasExistingDatabase = false)
        val second = storage.prepareDatabaseKey(hasExistingDatabase = false)

        assertTrue(first is DatabaseKeyPlan.Active)
        assertTrue(second is DatabaseKeyPlan.Active)
        assertEquals(
            SecureTelegramStorage.DATABASE_KEY_BYTES,
            (first as DatabaseKeyPlan.Active).key.size
        )
        assertTrue(first.key.contentEquals((second as DatabaseKeyPlan.Active).key))
    }

    @Test
    fun callerNotificationHasPrivateVisibilityAndRedactedPublicVersion() {
        val notification = NotificationHelper.buildNotification(
            context = context,
            title = "Sample caller",
            message = "0000000000\nsample@example.invalid",
            result = caller()
        )
        assertEquals(NotificationCompat.VISIBILITY_PRIVATE, notification.visibility)
        val publicVersion = notification.publicVersion
        assertNotNull(publicVersion)
        val extras = publicVersion.extras
        assertEquals("Caller information", extras.getCharSequence("android.title"))
        assertEquals(
            "Details available after unlocking",
            extras.getCharSequence("android.text")
        )
        val publicText = extras.toString()
        assertFalse(publicText.contains("Sample caller"))
        assertFalse(publicText.contains("0000000000"))
        assertFalse(publicText.contains("sample@example.invalid"))
    }

    @Test
    fun lockedCallerActivityIsProtectedAndExcludedFromRecents() {
        val info = context.packageManager.getActivityInfo(
            android.content.ComponentName(context, LockedCallerCardActivity::class.java),
            PackageManager.ComponentInfoFlags.of(0)
        )
        assertFalse(info.exported)
        assertTrue(info.flags and ActivityInfo.FLAG_EXCLUDE_FROM_RECENTS != 0)
        assertEquals(ActivityInfo.LAUNCH_SINGLE_TOP, info.launchMode)
        assertTrue(info.themeResource != 0)
    }

    @Test
    fun lockedCallerPresentationAppearsRedactedAndHonorsGeneration() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        assertTrue(keyguard.isKeyguardLocked)

        val first = activeIncomingCallGeneration.begin()
        assertTrue(activeIncomingCallGeneration.attachNumber(first, "0000000001"))
        val second = activeIncomingCallGeneration.begin()
        assertTrue(activeIncomingCallGeneration.attachNumber(second, "0000000002"))
        instrumentation.runOnMainSync {
            LockedCallerCardController.present(
                context,
                second,
                "0000000002",
                "Sample caller",
                NumberVerificationState.PASSED,
                IncomingLookupStage.RESOLVED
            )
        }
        instrumentation.waitForIdleSync()
        val activity = resumedLockedActivity()
        assertNotNull(activity)
        assertEquals(
            "Sample caller",
            activity!!.findViewById<TextView>(R.id.lockedCallerName).text.toString()
        )
        assertEquals(
            "0000000002",
            activity.findViewById<TextView>(R.id.lockedCallerNumber).text.toString()
        )
        instrumentation.runOnMainSync {
            LockedCallerCardController.clear(context, first)
        }
        instrumentation.waitForIdleSync()
        assertNotNull(resumedLockedActivity())
        instrumentation.runOnMainSync {
            LockedCallerCardController.clear(context, second)
            activeIncomingCallGeneration.invalidate(second)
        }
        instrumentation.waitForIdleSync()
        assertNull(resumedLockedActivity())
    }

    private fun resumedLockedActivity(): LockedCallerCardActivity? {
        val result = AtomicReference<LockedCallerCardActivity?>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            result.set(
                ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<LockedCallerCardActivity>()
                    .firstOrNull()
            )
        }
        return result.get()
    }

    private fun createVersionTwoDatabaseWithCaller() {
        val sqlite = context.openOrCreateDatabase(DATABASE_NAME, Context.MODE_PRIVATE, null)
        sqlite.execSQL(
            "CREATE TABLE IF NOT EXISTS caller_info (" +
                "number TEXT NOT NULL, " +
                "country TEXT, " +
                "name TEXT, " +
                "carrier TEXT, " +
                "email TEXT, " +
                "location TEXT, " +
                "address1 TEXT, " +
                "address2 TEXT, " +
                "error TEXT, " +
                "timestamp INTEGER NOT NULL, " +
                "PRIMARY KEY(number))"
        )
        sqlite.execSQL(
            "INSERT INTO caller_info " +
                "(number, country, name, carrier, email, location, address1, address2, error, timestamp) " +
                "VALUES (?, NULL, ?, NULL, NULL, NULL, NULL, NULL, NULL, ?)",
            arrayOf<Any>(TEST_NUMBER, "Sample caller", 1L)
        )
        sqlite.version = 2
        sqlite.close()
    }

    private fun openVersionThreeDatabase(): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, DATABASE_NAME)
            .addMigrations(AppDatabase.MIGRATION_2_3)
            .build()

    private fun caller() = CallerInfoEntity(
        number = TEST_NUMBER,
        country = null,
        name = "Sample caller",
        carrier = null,
        email = null,
        location = null,
        address1 = null,
        address2 = null,
        error = null,
        timestamp = 1,
        lastSuccessfullyUpdatedMillis = 1
    )

    companion object {
        private const val DATABASE_NAME = "comparison_instrumentation_database"
        private const val PREFERENCES_NAME = "comparison_instrumentation_settings"
        private const val SETTING_KEY = "preserved_setting"
        private const val TEST_NUMBER = "0000000000"
        private val ISOLATED_PREFERENCE_PREFIXES = listOf(
            "secure_migration_success",
            "secure_migration_failure",
            "secure_database_key"
        )
    }

    private class IsolatedPreferenceContext(
        base: Context,
        private val prefix: String
    ) : ContextWrapper(base) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            super.getSharedPreferences("${prefix}_$name", mode)
    }

    private class ReversibleTestCipher : SecureValueCipher {
        override fun encrypt(value: ByteArray): String =
            "v1:${Base64.encodeToString(value, Base64.NO_WRAP)}"

        override fun decrypt(envelope: String): ByteArray {
            require(envelope.startsWith("v1:"))
            return Base64.decode(envelope.removePrefix("v1:"), Base64.NO_WRAP)
        }
    }

    private class FailingTestCipher : SecureValueCipher {
        override fun encrypt(value: ByteArray): String =
            throw IllegalStateException("Unavailable")

        override fun decrypt(envelope: String): ByteArray =
            throw IllegalStateException("Unavailable")
    }
}
