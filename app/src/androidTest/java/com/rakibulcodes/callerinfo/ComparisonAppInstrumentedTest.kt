package com.rakibulcodes.callerinfo

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rakibulcodes.callerinfo.data.database.AppDatabase
import com.rakibulcodes.callerinfo.data.database.CallerInfoEntity
import com.rakibulcodes.callerinfo.data.database.PendingCallerLookupEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

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
        context.deleteDatabase(DATABASE_NAME)
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
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
    }
}
