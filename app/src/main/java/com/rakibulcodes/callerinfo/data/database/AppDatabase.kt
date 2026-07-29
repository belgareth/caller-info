package com.rakibulcodes.callerinfo.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [CallerInfoEntity::class, PendingCallerLookupEntity::class],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun callerInfoDao(): CallerInfoDao
    abstract fun pendingCallerLookupDao(): PendingCallerLookupDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "caller_info_database"
                )
                .addMigrations(MIGRATION_2_3)
                .build()
                INSTANCE = instance
                instance
            }
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
    }
}
