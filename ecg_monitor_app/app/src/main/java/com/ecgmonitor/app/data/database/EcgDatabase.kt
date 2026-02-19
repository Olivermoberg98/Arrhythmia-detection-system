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
    version = 2,
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

        fun getDatabase(context: Context): EcgDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    EcgDatabase::class.java,
                    "ecg_database"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { INSTANCE = it }
            }
    }
}