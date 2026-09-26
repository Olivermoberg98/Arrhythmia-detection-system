package com.ecgmonitor.app.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.ecgmonitor.app.data.database.converters.Converters
import com.ecgmonitor.app.data.database.dao.AuditLogDao
import com.ecgmonitor.app.data.database.dao.ArrhythmiaEventDao
import com.ecgmonitor.app.data.database.dao.ConsentDao
import com.ecgmonitor.app.data.database.dao.EcgReadingDao
import com.ecgmonitor.app.data.database.dao.EcgSessionDao
import com.ecgmonitor.app.data.database.entities.ArrhythmiaEventEntity
import com.ecgmonitor.app.data.database.entities.AuditLogEntity
import com.ecgmonitor.app.data.database.entities.ConsentEntity
import com.ecgmonitor.app.data.database.entities.EcgReadingEntity
import com.ecgmonitor.app.data.database.entities.EcgSessionEntity

@Database(
    entities = [
        EcgSessionEntity::class,
        EcgReadingEntity::class,
        ArrhythmiaEventEntity::class,
        ConsentEntity::class,
        AuditLogEntity::class
    ],
    version = 4,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class EcgDatabase : RoomDatabase() {

    abstract fun sessionDao(): EcgSessionDao
    abstract fun readingDao(): EcgReadingDao
    abstract fun eventDao(): ArrhythmiaEventDao
    abstract fun consentDao(): ConsentDao
    abstract fun auditLogDao(): AuditLogDao

    companion object {

        private const val VERSION_2 = 2
        private const val VERSION_3 = 3
        private const val VERSION_4 = 4

        @Volatile
        private var INSTANCE: EcgDatabase? = null

        /** Adds consent_records and audit_log tables introduced in version 2. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """CREATE TABLE IF NOT EXISTS consent_records (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        timestamp INTEGER NOT NULL,
                        consentVersion TEXT NOT NULL,
                        granted INTEGER NOT NULL,
                        ipAddress TEXT
                    )"""
                )
                database.execSQL(
                    """CREATE TABLE IF NOT EXISTS audit_log (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        timestamp INTEGER NOT NULL,
                        action TEXT NOT NULL,
                        sessionId TEXT,
                        detail TEXT
                    )"""
                )
            }
        }

        /** Adds the uploadedAt cloud-sync marker introduced in version 3. */
        val MIGRATION_2_3 = object : Migration(VERSION_2, VERSION_3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE ecg_sessions ADD COLUMN uploadedAt INTEGER")
            }
        }

        /**
         * Adds the per-session model-health columns introduced in version 4.
         * All nullable — existing sessions predate monitoring and stay NULL.
         */
        val MIGRATION_3_4 = object : Migration(VERSION_3, VERSION_4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                listOf(
                    "modelVersion TEXT",
                    "meanConfidence REAL",
                    "lowConfidenceRate REAL",
                    "meanEntropy REAL",
                    "poorSignalRate REAL",
                    "suppressedEvents INTEGER",
                    "driftScore REAL",
                    "classificationsPerMin REAL",
                    "rateFlag TEXT",
                    "meanInferenceMs REAL"
                ).forEach { column ->
                    database.execSQL("ALTER TABLE ecg_sessions ADD COLUMN $column")
                }
            }
        }

        fun getDatabase(context: Context): EcgDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    EcgDatabase::class.java,
                    "ecg_database"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .build()
                    .also { INSTANCE = it }
            }
    }
}