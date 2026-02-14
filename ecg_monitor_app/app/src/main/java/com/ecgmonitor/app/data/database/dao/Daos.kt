package com.ecgmonitor.app.data.database.dao

import androidx.room.*
import com.ecgmonitor.app.data.database.entities.*
import kotlinx.coroutines.flow.Flow

@Dao
interface EcgSessionDao {

    @Query("SELECT * FROM ecg_sessions ORDER BY startTime DESC")
    fun getAllSessions(): Flow<List<EcgSessionEntity>>

    @Query("SELECT * FROM ecg_sessions WHERE id = :sessionId")
    suspend fun getSession(sessionId: String): EcgSessionEntity?

    @Query("SELECT * FROM ecg_sessions WHERE id = :sessionId")
    fun getSessionFlow(sessionId: String): Flow<EcgSessionEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: EcgSessionEntity)

    @Update
    suspend fun updateSession(session: EcgSessionEntity)

    @Delete
    suspend fun deleteSession(session: EcgSessionEntity)

    @Query("DELETE FROM ecg_sessions WHERE id = :sessionId")
    suspend fun deleteSessionById(sessionId: String)

    @Query("DELETE FROM ecg_sessions")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM ecg_sessions")
    suspend fun getSessionCount(): Int
}

@Dao
interface EcgReadingDao {

    @Query("SELECT * FROM ecg_readings WHERE sessionId = :sessionId ORDER BY sampleIndex ASC")
    suspend fun getReadingsForSession(sessionId: String): List<EcgReadingEntity>

    @Query("SELECT * FROM ecg_readings WHERE sessionId = :sessionId ORDER BY sampleIndex ASC")
    fun getReadingsForSessionFlow(sessionId: String): Flow<List<EcgReadingEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReading(reading: EcgReadingEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReadings(readings: List<EcgReadingEntity>)

    @Query("DELETE FROM ecg_readings WHERE sessionId = :sessionId")
    suspend fun deleteReadingsForSession(sessionId: String)

    @Query("DELETE FROM ecg_readings")
    suspend fun deleteAll()
}

@Dao
interface ArrhythmiaEventDao {

    @Query("SELECT * FROM arrhythmia_events WHERE sessionId = :sessionId ORDER BY sampleIndex ASC")
    suspend fun getEventsForSession(sessionId: String): List<ArrhythmiaEventEntity>

    @Query("SELECT * FROM arrhythmia_events WHERE sessionId = :sessionId ORDER BY sampleIndex ASC")
    fun getEventsForSessionFlow(sessionId: String): Flow<List<ArrhythmiaEventEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvent(event: ArrhythmiaEventEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvents(events: List<ArrhythmiaEventEntity>)

    @Query("DELETE FROM arrhythmia_events WHERE sessionId = :sessionId")
    suspend fun deleteEventsForSession(sessionId: String)

    @Query("DELETE FROM arrhythmia_events")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM arrhythmia_events WHERE sessionId = :sessionId AND type = :type")
    suspend fun getEventCountByType(sessionId: String, type: String): Int
}