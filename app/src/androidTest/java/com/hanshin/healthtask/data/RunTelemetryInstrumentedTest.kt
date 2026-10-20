package com.hanshin.healthtask.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.gson.JsonParser
import com.hanshin.healthtask.data.db.HealthTaskDatabase
import com.hanshin.healthtask.shared.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class RunTelemetryInstrumentedTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var db: HealthTaskDatabase
    private lateinit var repository: HealthTaskRepository
    private val started = 1_790_200_000_000L
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(context, HealthTaskDatabase::class.java).build()
        repository = HealthTaskRepository(db)
    }
    @After fun close() { db.close() }

    @Test fun fullWatchTimelinePersistsExportsRestoresAndDeletesWithSession() = runBlocking {
        val workout = workout("wear-session-test", 10_000)
        assertTrue(repository.importWearWorkout(workout))
        assertFalse(repository.importWearWorkout(workout))
        val chunks = db.dao().getTelemetryChunks(workout.sessionId)
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.jsonPart.length <= 32_000 })
        assertEquals(workout.telemetry, chunks.decodeTelemetry())
        val exported = BackupCodec(db).export()
        val root = JsonParser.parseString(exported).asJsonObject
        assertEquals(5, root["schemaVersion"].asInt)
        val analysisRun = root.getAsJsonObject("analysis").getAsJsonArray("runningSessions").single().asJsonObject
        assertEquals(10_000, analysisRun.getAsJsonObject("sensorTelemetry").getAsJsonArray("records").size())
        assertEquals(10_000, analysisRun.getAsJsonObject("sensorSummary")["heartRateSampleCount"].asInt)
        val restored = Room.inMemoryDatabaseBuilder(context, HealthTaskDatabase::class.java).build()
        try {
            BackupCodec(restored).import(exported)
            BackupCodec(restored).import(exported)
            assertEquals(workout.telemetry, restored.dao().getTelemetryChunks(workout.sessionId).decodeTelemetry())
            assertEquals(db.dao().getSession(workout.sessionId), restored.dao().getSession(workout.sessionId))
        } finally { restored.close() }
        repository.deleteSession(workout.sessionId)
        assertTrue(db.dao().getTelemetryChunks().isEmpty())
    }

    @Test fun watchSensorsMergeWithoutReplacingPhoneRouteAndSurvivePhoneSaveRetry() = runBlocking {
        val workout = workout("run-phone", 20)
        repository.savePhoneRun(workout.sessionId, started, started + 60_000, 60_000, 200.0, "37.5,127.0,0", null)
        repository.importWearWorkout(workout)
        repository.savePhoneRun(workout.sessionId, started, started + 60_000, 60_000, 200.0, "37.5,127.0,0", null)
        val session = repository.getSession(workout.sessionId)!!.session
        assertEquals("37.5,127.0,0", session.routePolyline)
        assertNotNull(session.sensorSummaryJson)
        assertEquals(workout.telemetry, db.dao().getTelemetryChunks(workout.sessionId).decodeTelemetry())
    }

    @Test fun oldWatchPayloadImportsWithoutInventingSensorHistory() = runBlocking {
        val workout = workout("wear-old", 0).copy(schemaVersion = 4, telemetry = null)
        repository.importWearWorkout(workout)
        assertNull(repository.getSession(workout.sessionId)!!.session.sensorSummaryJson)
        assertTrue(db.dao().getTelemetryChunks().isEmpty())
    }

    @Test fun partialSensorBackupIsRejectedWithoutChangingExistingRecords() = runBlocking {
        val workout = workout("wear-backup", 500)
        repository.importWearWorkout(workout)
        val root = JsonParser.parseString(BackupCodec(db).export()).asJsonObject
        root.getAsJsonArray("runTelemetryChunks").remove(0)
        val before = db.dao().getTelemetryChunks()
        assertTrue(runCatching { BackupCodec(db).import(root.toString()) }.isFailure)
        assertEquals(before, db.dao().getTelemetryChunks())
    }

    @Test fun wrongSessionSensorDataIsRejectedBeforeAnyWrite() = runBlocking {
        val workout = workout("wear-invalid", 10)
        val telemetry = requireNotNull(workout.telemetry)
        val invalid = workout.copy(telemetry = telemetry.copy(metadata = telemetry.metadata.copy(sessionId = "different")))
        assertTrue(runCatching { repository.importWearWorkout(invalid) }.isFailure)
        assertTrue(db.dao().getSessions().isEmpty())
        assertTrue(db.dao().getTelemetryChunks().isEmpty())
    }

    @Test fun restoringOldBackupCannotEraseNewerSensorHistory() = runBlocking {
        val workout = workout("wear-new-sensors", 10)
        repository.importWearWorkout(workout)
        val root = JsonParser.parseString(BackupCodec(db).export()).asJsonObject
        root.addProperty("schemaVersion", 3)
        root.remove("runTelemetryChunks")
        root.getAsJsonArray("sessions").forEach { it.asJsonObject.remove("sensorSummaryJson") }
        BackupCodec(db).import(root.toString())
        assertEquals(workout.telemetry, db.dao().getTelemetryChunks(workout.sessionId).decodeTelemetry())
        assertNotNull(db.dao().getSession(workout.sessionId)!!.session.sensorSummaryJson)
    }

    private fun workout(id: String, count: Int): WearCompletedWorkout {
        val routine = WearQuickRunPreset.FREE_RUN.toRoutinePayload()
        return WearCompletedWorkout(sessionId = id, routineId = routine.routineId, title = "러닝", startedAt = started,
            endedAt = started + maxOf(60_000, count * 1_000L),
            exercises = routine.exercises.map { it.copy(durationMin = 1.0, distanceKm = 0.2) },
            distanceKm = 0.2, averageHeartRateBpm = 145.0, activeDurationMillis = 60_000,
            telemetry = RunTelemetry(metadata = RunSensorMetadata(id, started, started, 100_000, "Asia/Seoul", "test", "test", 36,
                listOf("heartRateBpm", "altitudeMeters"), listOf("heartRateBpm"), listOf("altitudeMeters")),
                records = (0 until count).map { RunSensorRecord("sample", started + it * 1_000L,
                    elapsedRealtimeMillis = 100_000 + it * 1_000L, activeElapsedMillis = it * 1_000L,
                    metric = "heartRateBpm", value = 130.0 + it % 30) }))
    }
}
