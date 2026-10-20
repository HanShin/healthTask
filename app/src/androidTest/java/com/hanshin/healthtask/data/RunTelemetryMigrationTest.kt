package com.hanshin.healthtask.data

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.JsonParser
import com.hanshin.healthtask.data.db.HealthTaskDatabase
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RunTelemetryMigrationTest {
    @Test fun migrationPreservesExistingRunsAndAddsNullableSensorSummary() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val name = "telemetry-migration-${UUID.randomUUID()}.db"
        val schema = instrumentation.context.assets.open("com.hanshin.healthtask.data.db.HealthTaskDatabase/5.json")
            .bufferedReader().use { JsonParser.parseReader(it).asJsonObject.getAsJsonObject("database") }
        val path = context.getDatabasePath(name)
        path.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
            schema.getAsJsonArray("entities").forEach { element ->
                val entity = element.asJsonObject
                val table = entity["tableName"].asString
                fun sql(raw: String) = raw.replace("\${TABLE_NAME}", table)
                old.execSQL(sql(entity["createSql"].asString))
                entity.getAsJsonArray("indices")?.forEach { old.execSQL(sql(it.asJsonObject["createSql"].asString)) }
            }
            schema.getAsJsonArray("setupQueries").forEach { old.execSQL(it.asString) }
            old.version = 5
            old.execSQL("INSERT INTO workout_sessions(id,title,sessionDate,status,source,startedAt,syncStatus,createdAt,updatedAt,averageHeartRateBpm) VALUES ('old-run','러닝','2026-09-24','COMPLETED','LOCAL',1000,'PENDING',1000,1000,145.0)")
            old.execSQL("INSERT INTO health_measurements(id,recordDate,measuredAt,type,value,source,createdAt,updatedAt) VALUES ('old-body','2026-09-24',1000,'WEIGHT_KG',70.2,'LOCAL',1000,1000)")
        }
        val upgraded = Room.databaseBuilder(context, HealthTaskDatabase::class.java, name)
            .addMigrations(HealthTaskDatabase.MIGRATION_5_6, HealthTaskDatabase.MIGRATION_6_7).build()
        try {
            // Opening Room validates the full migration chain against the current schema.
            val run = requireNotNull(upgraded.dao().getSession("old-run")).session
            assertEquals(145.0, run.averageHeartRateBpm!!, 0.0)
            assertNull(run.sensorSummaryJson)
            assertTrue(upgraded.dao().getTelemetryChunks().isEmpty())
            assertTrue(upgraded.dao().getInBodyRecords().isEmpty())
            assertEquals(70.2, upgraded.dao().getHealthMeasurement("old-body")!!.value, 0.0)
        } finally {
            upgraded.close()
            context.deleteDatabase(name)
        }
    }
}
