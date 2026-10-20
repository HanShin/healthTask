package com.hanshin.healthtask.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.gson.JsonParser
import com.hanshin.healthtask.data.db.HealthTaskDatabase
import com.hanshin.healthtask.domain.HealthMetricType
import com.hanshin.healthtask.health.InBodyCsvParser
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class InBodyCsvInstrumentedTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var db: HealthTaskDatabase
    private lateinit var storage: InBodyCsvStorage
    private val header = "날짜,측정장비,체중(kg),근육량(kg),복부지방률,상체하체,체수분(L),추가항목\n"
    private val row = "20260924092730,H40,70.2,55.3,0.95,0,-,원본문자"
    private fun preview(csv: String = header + row) = InBodyCsvParser.parse(csv, "synthetic.csv", ZoneId.of("Asia/Seoul"), LocalDate.of(2026, 9, 24))
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(context, HealthTaskDatabase::class.java).build()
        storage = InBodyCsvStorage(db)
    }
    @After fun close() { db.close() }

    @Test fun importsAllPresentValuesAndRepeatedImportsDoNotDuplicateOrChangeTimes() = runBlocking {
        assertEquals(InBodyCsvImportReport(1, 0, 0, 0), storage.save(preview()))
        val before = db.dao().getHealthMeasurements()
        assertEquals(4, before.size)
        assertEquals(0.0, before.single { it.type == HealthMetricType.UPPER_LOWER_BALANCE_CODE }.value, 0.0)
        assertEquals(0.95, before.single { it.type == HealthMetricType.WAIST_HIP_RATIO }.value, 0.0)
        val original = db.dao().getInBodyRecords().single()
        assertEquals("2026-09-24T09:27:30", original.measuredLocal)
        assertEquals(InBodyCsvImportReport(0, 0, 1, 0), storage.save(preview()))
        assertEquals(original, db.dao().getInBodyRecords().single())
        assertEquals(before, db.dao().getHealthMeasurements())
    }

    @Test fun enrichedImportAddsMissingFieldsWithoutOverwritingConflictingValues() = runBlocking {
        storage.save(preview())
        val enriched = preview(header + row.replace("70.2", "71.0").replace(",-,", ",40.123,"))
        assertEquals(InBodyCsvImportReport(0, 1, 0, 1), storage.save(enriched))
        val metrics = db.dao().getHealthMeasurements()
        assertEquals(5, metrics.size)
        assertEquals(70.2, metrics.single { it.type == HealthMetricType.WEIGHT_KG }.value, 0.0)
        assertEquals(40.123, metrics.single { it.type == HealthMetricType.BODY_WATER_L }.value, 0.0)
        assertEquals("원본문자", InBodyCsvParser.decodeRaw(db.dao().getInBodyRecords().single().rawFieldsJson)["추가항목"])
    }

    @Test fun wholeDayMultipleTimesAndMultipleEquipmentRemainSeparateAndDeleteIsScoped() = runBlocking {
        storage.save(preview(header + row + "\n" + row.replace("092730", "120000") + "\n" + row.replace("H40", "H50")))
        assertEquals(3, db.dao().getInBodyRecords().size)
        assertEquals(12, db.dao().getHealthMeasurements().size)
        storage.delete(db.dao().getInBodyRecords().first().id)
        assertEquals(2, db.dao().getInBodyRecords().size)
        assertEquals(8, db.dao().getHealthMeasurements().size)
    }

    @Test fun backupRoundTripPreservesEveryRawFieldAndMetricWithRepeatedRestore() = runBlocking {
        storage.save(preview())
        val exported = BackupCodec(db).export()
        val json = JsonParser.parseString(exported).asJsonObject
        assertEquals(5, json["schemaVersion"].asInt)
        assertEquals(3, json.getAsJsonObject("analysis")["schemaVersion"].asInt)
        val raw = json.getAsJsonObject("analysis").getAsJsonArray("inBodyRecords").single().asJsonObject.getAsJsonObject("rawFields")
        assertEquals("원본문자", raw["추가항목"].asString)
        assertEquals("-", raw["체수분(L)"].asString)
        val restored = Room.inMemoryDatabaseBuilder(context, HealthTaskDatabase::class.java).build()
        try {
            repeat(2) { BackupCodec(restored).import(exported) }
            assertEquals(db.dao().getInBodyRecords(), restored.dao().getInBodyRecords())
            assertEquals(db.dao().getHealthMeasurements().sortedBy { it.id }, restored.dao().getHealthMeasurements().sortedBy { it.id })
        } finally { restored.close() }
    }

    @Test fun inconsistentBackupRejectsBeforeAnyWritesAndInvalidBatchRollsBack() = runBlocking {
        storage.save(preview())
        val before = db.dao().getInBodyRecords()
        val json = JsonParser.parseString(BackupCodec(db).export()).asJsonObject
        json.getAsJsonArray("healthMeasurements").first().asJsonObject.addProperty("value", 999.0)
        assertTrue(runCatching { BackupCodec(db).import(json.toString()) }.isFailure)
        assertEquals(before, db.dao().getInBodyRecords())
        val valid = preview(header + row.replace("092730", "120000"))
        val bad = valid.records.single().copy(id = "bad")
        assertTrue(runCatching { storage.save(valid.copy(records = valid.records + bad)) }.isFailure)
        assertEquals(before, db.dao().getInBodyRecords())
    }

    @Test fun csvMetricsCannotBeEditedOrDeletedIndividually() = runBlocking {
        storage.save(preview())
        val repo = HealthTaskRepository(db)
        val metric = db.dao().getHealthMeasurements().first()
        assertTrue(runCatching { repo.deleteBodyMeasurement(metric.id) }.isFailure)
        assertTrue(runCatching { repo.saveBodyMeasurement(LocalDate.of(2026, 9, 24), metric.type, 80.0, metric.id) }.isFailure)
        assertEquals(4, db.dao().getHealthMeasurements().size)
    }
}
