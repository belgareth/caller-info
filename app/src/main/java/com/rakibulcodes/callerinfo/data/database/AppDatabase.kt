package com.rakibulcodes.callerinfo.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [SecureCallerInfoEntity::class, SecurePendingCallerLookupEntity::class],
    version = 4,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun callerInfoDao(): CallerInfoDao
    abstract fun pendingCallerLookupDao(): PendingCallerLookupDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "caller_info_database"
            ).addMigrations(MIGRATION_2_3, MIGRATION_3_4)
                .build()
                .also { INSTANCE = it }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE caller_info ADD COLUMN " +
                        "lastSuccessfullyUpdatedMillis INTEGER DEFAULT NULL"
                )
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS pending_caller_lookup (" +
                        "normalizedNumber TEXT NOT NULL, " +
                        "createdTimestampMillis INTEGER NOT NULL, " +
                        "nextEligibleRetryTimestampMillis INTEGER NOT NULL, " +
                        "attemptCount INTEGER NOT NULL, " +
                        "PRIMARY KEY(normalizedNumber))"
                )
            }
        }

        /**
         * Creates the encrypted cache alongside the v3 plaintext table. The
         * repository performs the Keystore-backed data copy on first access,
         * then drops the legacy table only after all rows were encrypted.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS secure_caller_info (" +
                        "lookupKey TEXT NOT NULL, " +
                        "encryptedPayload TEXT NOT NULL, " +
                        "timestamp INTEGER NOT NULL, " +
                        "lastSuccessfullyUpdatedMillis INTEGER DEFAULT NULL, " +
                        "PRIMARY KEY(lookupKey))"
                )
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS secure_pending_caller_lookup (" +
                        "lookupKey TEXT NOT NULL, " +
                        "encryptedNumber TEXT NOT NULL, " +
                        "createdTimestampMillis INTEGER NOT NULL, " +
                        "nextEligibleRetryTimestampMillis INTEGER NOT NULL, " +
                        "attemptCount INTEGER NOT NULL, " +
                        "PRIMARY KEY(lookupKey))"
                )
            }
        }
    }
}
