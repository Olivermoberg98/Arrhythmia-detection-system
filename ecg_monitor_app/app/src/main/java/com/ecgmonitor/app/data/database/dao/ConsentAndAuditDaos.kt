package com.ecgmonitor.app.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.ecgmonitor.app.data.database.entities.AuditLogEntity
import com.ecgmonitor.app.data.database.entities.ConsentEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ConsentDao {
    @Insert
    suspend fun insert(consent: ConsentEntity)

    /** Returns the most recent consent event. */
    @Query("SELECT * FROM consent_records ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatest(): ConsentEntity?

    /** Full history — used if user requests a data export of their consent records. */
    @Query("SELECT * FROM consent_records ORDER BY timestamp DESC")
    fun getAll(): Flow<List<ConsentEntity>>

    @Query("DELETE FROM consent_records")
    suspend fun deleteAll()
}

@Dao
interface AuditLogDao {
    @Insert
    suspend fun insert(entry: AuditLogEntity)

    @Query("SELECT * FROM audit_log ORDER BY timestamp DESC")
    fun getAll(): Flow<List<AuditLogEntity>>

    @Query("SELECT * FROM audit_log WHERE sessionId = :sessionId ORDER BY timestamp DESC")
    fun getForSession(sessionId: String): Flow<List<AuditLogEntity>>

    @Query("DELETE FROM audit_log")
    suspend fun deleteAll()
}