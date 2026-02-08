package com.ecgmonitor.app.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.ecgmonitor.app.data.database.converters.Converters
import com.ecgmonitor.app.data.database.dao.*
import com.ecgmonitor.app.data.database.entities.*

@Database(
    entities = [
        EcgSessionEntity::class,
        EcgReadingEntity::class,
        ArrhythmiaEventEntity::class
    ],
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class EcgDatabase : RoomDatabase() {

    abstract fun sessionDao(): EcgSessionDao
    abstract fun readingDao(): EcgReadingDao
    abstract fun eventDao(): ArrhythmiaEventDao

    companion object {
        @Volatile
        private var INSTANCE: EcgDatabase? = null

        fun getDatabase(context: Context): EcgDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    EcgDatabase::class.java,
                    "ecg_database"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}