package com.ecgmonitor.app.data.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Migration tests for [EcgDatabase].
 *
 * These run against the exported schema JSONs in `app/schemas`, so they catch the
 * failure that unit tests cannot: a migration whose SQL disagrees with the entity
 * definition. Room validates the post-migration schema on open and throws, which for
 * v3→v4 would mean every existing install crashing at launch rather than at build time.
 */
@RunWith(AndroidJUnit4::class)
class EcgDatabaseMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        EcgDatabase::class.java
    )

    @Test
    fun migrate3To4_matchesTheEntitySchema() {
        helper.createDatabase(TEST_DB, VERSION_3).close()

        // runMigrationsAndValidate throws if the resulting schema differs from 4.json.
        helper.runMigrationsAndValidate(
            TEST_DB,
            VERSION_4,
            true,
            EcgDatabase.MIGRATION_3_4
        ).close()
    }

    @Test
    fun migrate3To4_preservesExistingSessionsAndLeavesHealthColumnsNull() {
        helper.createDatabase(TEST_DB, VERSION_3).apply {
            execSQL(INSERT_V3_SESSION)
            close()
        }

        val db = helper.runMigrationsAndValidate(
            TEST_DB,
            VERSION_4,
            true,
            EcgDatabase.MIGRATION_3_4
        )

        db.query(
            "SELECT id, averageHeartRate, healthStatus, uploadedAt, " +
                "modelVersion, driftScore, suppressedEvents, rateFlag " +
                "FROM ecg_sessions"
        ).use { cursor ->
            assertTrue("pre-migration session was lost", cursor.moveToFirst())
            assertEquals(1, cursor.count)

            // Pre-existing data survives untouched.
            assertEquals(SESSION_ID, cursor.getString(0))
            assertEquals(72, cursor.getInt(1))
            assertEquals("NORMAL", cursor.getString(2))
            assertEquals(1_700_000_500_000L, cursor.getLong(3))

            // Sessions recorded before monitoring existed carry no health data.
            for (column in 4..7) {
                assertTrue(
                    "column ${cursor.getColumnName(column)} should be NULL",
                    cursor.isNull(column)
                )
            }
        }
        db.close()
    }

    @Test
    fun migrate3To4_acceptsHealthMetricsAtTheirDeclaredAffinities() {
        // Guards the type half of the migration: REAL/INTEGER/TEXT in the ALTER
        // statements must accept what the entity's Float?/Int?/String? fields write.
        helper.createDatabase(TEST_DB, VERSION_3).close()

        val db = helper.runMigrationsAndValidate(
            TEST_DB,
            VERSION_4,
            true,
            EcgDatabase.MIGRATION_3_4
        )

        db.execSQL(INSERT_V4_SESSION)
        db.query(
            "SELECT meanConfidence, suppressedEvents, rateFlag, meanInferenceMs " +
                "FROM ecg_sessions WHERE id = '$MONITORED_SESSION_ID'"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0.93, cursor.getDouble(0), TOLERANCE)
            assertEquals(4, cursor.getInt(1))
            assertEquals("HIGH_TACHY_SUSPECT", cursor.getString(2))
            assertEquals(21.5, cursor.getDouble(3), TOLERANCE)
        }
        db.close()
    }

    @Test
    fun migrateAll_fromVersion1() {
        // The full chain, so an install that skipped a release still lands on v4.
        helper.createDatabase(TEST_DB, VERSION_1).close()

        val db = helper.runMigrationsAndValidate(
            TEST_DB,
            VERSION_4,
            true,
            EcgDatabase.MIGRATION_1_2,
            EcgDatabase.MIGRATION_2_3,
            EcgDatabase.MIGRATION_3_4
        )

        // Tables added along the way are present, and v4 columns reached the end.
        db.query("SELECT name FROM sqlite_master WHERE type='table'").use { cursor ->
            val tables = buildSet {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
            assertTrue("consent_records missing", "consent_records" in tables)
            assertTrue("audit_log missing", "audit_log" in tables)
        }
        db.query("SELECT * FROM ecg_sessions LIMIT 0").use { cursor ->
            val columns = cursor.columnNames.toSet()
            assertTrue("uploadedAt missing", "uploadedAt" in columns)
            assertTrue("driftScore missing", "driftScore" in columns)
            assertTrue("rateFlag missing", "rateFlag" in columns)
        }
        db.close()
    }

    @Test
    fun migrate3To4_isRunnableOnAnEmptyDatabase() {
        // No rows at all is the common case for a fresh install that never recorded.
        helper.createDatabase(TEST_DB, VERSION_3).close()

        val db = helper.runMigrationsAndValidate(
            TEST_DB,
            VERSION_4,
            true,
            EcgDatabase.MIGRATION_3_4
        )

        db.query("SELECT COUNT(*) FROM ecg_sessions").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
        db.close()
    }

    private companion object {
        const val TEST_DB = "migration-test"

        const val VERSION_1 = 1
        const val VERSION_3 = 3
        const val VERSION_4 = 4

        const val TOLERANCE = 1e-4

        const val SESSION_ID = "session-v3"
        const val MONITORED_SESSION_ID = "session-v4"

        /** A complete v3 row — every NOT NULL column of the old schema. */
        val INSERT_V3_SESSION = """
            INSERT INTO ecg_sessions (
                id, name, startTime, endTime, samplingRate, source, totalSamples,
                durationSeconds, averageHeartRate, minHeartRate, maxHeartRate,
                totalBeats, normalBeats, pvcCount, afibCount, otherArrhythmiaCount,
                healthStatus, notes, uploadedAt
            ) VALUES (
                '$SESSION_ID', 'Pre-monitoring session', 1700000000000, 1700000060000,
                250, 'ESP32_DEVICE', 15000, 60.0, 72, 60, 95,
                72, 70, 2, 0, 0,
                'NORMAL', NULL, 1700000500000
            )
        """.trimIndent()

        /** A v4 row carrying health metrics, to exercise the new columns' affinities. */
        val INSERT_V4_SESSION = """
            INSERT INTO ecg_sessions (
                id, name, startTime, endTime, samplingRate, source, totalSamples,
                durationSeconds, averageHeartRate, minHeartRate, maxHeartRate,
                totalBeats, normalBeats, pvcCount, afibCount, otherArrhythmiaCount,
                healthStatus, notes, uploadedAt,
                modelVersion, meanConfidence, lowConfidenceRate, meanEntropy,
                poorSignalRate, suppressedEvents, driftScore, classificationsPerMin,
                rateFlag, meanInferenceMs
            ) VALUES (
                '$MONITORED_SESSION_ID', 'Monitored session', 1700001000000, 1700001060000,
                250, 'ESP32_DEVICE', 15000, 60.0, 150, 140, 165,
                150, 140, 10, 0, 0,
                'ATTENTION', NULL, NULL,
                '1.0.0', 0.93, 0.07, 0.21,
                0.05, 4, 3650.0, 150.0,
                'HIGH_TACHY_SUSPECT', 21.5
            )
        """.trimIndent()
    }
}
