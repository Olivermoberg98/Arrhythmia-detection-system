package com.ecgmonitor.app.data.database.converters

import androidx.room.TypeConverter
import com.ecgmonitor.app.data.database.entities.HealthStatus
import com.ecgmonitor.app.data.models.DataSource

class Converters {

    @TypeConverter
    fun fromDataSource(value: DataSource): String = value.name

    @TypeConverter
    fun toDataSource(value: String): DataSource = DataSource.valueOf(value)

    @TypeConverter
    fun fromHealthStatus(value: HealthStatus): String = value.name

    @TypeConverter
    fun toHealthStatus(value: String): HealthStatus = HealthStatus.valueOf(value)
}