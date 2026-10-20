package com.hanshin.healthtask.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.gson.JsonParser
import com.hanshin.healthtask.data.db.*
import com.hanshin.healthtask.domain.*
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AnalysisBackupInstrumentedTest {
    private lateinit var database: HealthTaskDatabase
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val start = Instant.parse("2026-09-24T00:00:00Z").toEpochMilli()

    @Before fun setup() { database = Room.inMemoryDatabaseBuilder(context, HealthTaskDatabase::class.java).build() }
    @After fun close() { database.close() }

    @Test fun extendedBackupIncludesAnalysisButRestoresOnlyOriginalLocalPayload() = runBlocking {
        val local = run("run-local", WorkoutSource.LOCAL).copy(
            distanceKm = 5.0, activeDurationMillis = 1_800_000,
            routePolyline = "37.5,127.0,0;37.501,127.001,1000",
            lapData = "1,1000.0,360000,360000;2,250.0,90000,450000", averageHeartRateBpm = 143.0,
        )
        val external = run("external-run", WorkoutSource.NIKE_RUN_CLUB).copy(distanceKm = 5.1, sourcePackage = NIKE_RUN_CLUB_PACKAGE)
        val localBody = HealthMeasurementEntity("body-local", "2026-09-24", start, HealthMetricType.SKELETAL_MUSCLE_KG, 33.2, WorkoutSource.LOCAL)
        val externalBody = localBody.copy(id = "body-external", source = WorkoutSource.SAMSUNG_HEALTH, externalRecordId = "health-connect-id")
        val dao = database.dao()
        dao.upsertSession(local)
        dao.upsertSession(external)
        dao.upsertHealthMeasurements(listOf(localBody, externalBody))
        dao.upsertWorkoutLink(SamsungWorkoutLinkEntity("link", local.id, external.id, 0.95))
        val before = dao.getSessions()
        val json = BackupCodec(database).export()
        val root = JsonParser.parseString(json).asJsonObject
        assertEquals(5, root["schemaVersion"].asInt)
        assertEquals(1, root.getAsJsonArray("sessions").size())
        assertEquals(1, root.getAsJsonArray("healthMeasurements").size())
        val analysis = root.getAsJsonObject("analysis")
        assertEquals(3, analysis["schemaVersion"].asInt)
        assertEquals(2, analysis.getAsJsonArray("runningSessions").size())
        assertEquals(2, analysis.getAsJsonArray("bodyMeasurements").size())
        assertEquals(1, analysis.getAsJsonObject("summary")["sessionCount"].asInt)
        val exportedLocal = analysis.getAsJsonArray("runningSessions").map { it.asJsonObject }.single { it["sessionId"].asString == local.id }
        assertEquals(2, exportedLocal.getAsJsonArray("routePoints").size())
        assertEquals(2, exportedLocal.getAsJsonArray("laps").size())
        assertTrue(exportedLocal["duplicateOfSessionId"].isJsonNull)
        assertEquals(before, dao.getSessions())
        assertEquals(2, dao.getHealthMeasurements().size)
        val restored = Room.inMemoryDatabaseBuilder(context, HealthTaskDatabase::class.java).build()
        try {
            BackupCodec(restored).import(json)
            BackupCodec(restored).import(json)
            assertEquals(local, restored.dao().getSessions().single().session)
            assertEquals(listOf(localBody), restored.dao().getHealthMeasurements())
            assertNull(restored.dao().getSession(external.id))
        } finally { restored.close() }
    }

    @Test fun originalV3WithoutAnalysisStillRestoresAndUnknownValuesSerializeAsNull() = runBlocking {
        val local = run("run-missing", WorkoutSource.LOCAL)
        database.dao().upsertSession(local)
        val root = JsonParser.parseString(BackupCodec(database).export()).asJsonObject
        val analysisRun = root.getAsJsonObject("analysis").getAsJsonArray("runningSessions").single().asJsonObject
        assertTrue(analysisRun.getAsJsonObject("calculations")["distanceMeters"].isJsonNull)
        assertTrue(analysisRun.getAsJsonObject("record").getAsJsonObject("session")["averageHeartRateBpm"].isJsonNull)
        root.remove("analysis")
        root.addProperty("schemaVersion", 3)
        root.remove("runTelemetryChunks")
        val restored = Room.inMemoryDatabaseBuilder(context, HealthTaskDatabase::class.java).build()
        try {
            BackupCodec(restored).import(root.toString())
            assertEquals(local, restored.dao().getSession(local.id)!!.session)
        } finally { restored.close() }
    }

    @Test fun malformedEncodedPointsKeepRawBackupAndProduceValidAnalysisJson() = runBlocking {
        val raw = "NaN,127.0,0;broken;37.5,127.0,2000"
        database.dao().upsertSession(run("run-corrupt-route", WorkoutSource.LOCAL).copy(routePolyline = raw))
        val root = JsonParser.parseString(BackupCodec(database).export()).asJsonObject
        assertEquals(raw, root.getAsJsonArray("sessions").single().asJsonObject["routePolyline"].asString)
        val run = root.getAsJsonObject("analysis").getAsJsonArray("runningSessions").single().asJsonObject
        assertEquals(1, run.getAsJsonArray("routePoints").size())
        assertEquals(2, run.getAsJsonObject("dataQuality")["rejectedRoutePointCount"].asInt)
    }

    private fun run(id: String, source: WorkoutSource) = WorkoutSessionEntity(id = id, title = "러닝", sessionDate = "2026-09-24",
        status = WorkoutStatus.COMPLETED, source = source, startedAt = start, endedAt = start + 2_100_000)
}
