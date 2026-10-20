package com.hanshin.healthtask.cloud

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class CloudRecordsTest {
    @Test fun exportUsesStableIdsAndRetainsMissingValues() {
        val root = JsonParser.parseString("""{"analysis":{"metadata":{"exportTimeZoneId":"Asia/Seoul"},
          "runningSessions":[{"sessionId":"run-1","startedAtUtc":"2026-10-01T00:00:00Z","includedInSummary":false,
            "duplicateOfSessionId":"run-0","record":{"session":{"updatedAt":1000,"averageHeartRateBpm":null,"caloriesKcal":null}},
            "calculations":{"distanceMeters":null,"activeDurationMillis":5000}}],
          "bodyMeasurements":[{"record":{"id":"body-1","type":"WEIGHT_KG","value":70,"updatedAt":1000,"sourcePackage":"com.inbody2014.inbody"},"unit":"kg","measuredAtUtc":"2026-10-01T00:00:00Z"}],
          "inBodyRecords":[{"id":"csv-1","measuredAtUtc":"2026-10-01T00:00:00Z","assumedTimeZoneId":"Asia/Seoul","rawFields":{"unknown":"original"}}]}}""").asJsonObject
        val records = CloudRecords.fromExport(root)
        assertEquals(listOf("run-1", "body-1", "inbody:csv-1"), records.map { it.get("externalId").asString })
        assertTrue(records.first().getAsJsonObject("data").get("distanceMeters").isJsonNull)
        assertFalse(records.first().getAsJsonObject("data").get("includedInSummary").asBoolean)
        assertEquals("1970-01-01T00:00:01Z", records.first().get("updatedAt").asString)
        assertEquals("WEIGHT_KG", records[1].getAsJsonObject("data").get("metric").asString)
        assertEquals("original", records[2].getAsJsonObject("data").getAsJsonObject("rawFields").get("unknown").asString)
        assertEquals(records, CloudRecords.fromExport(root))
    }

    @Test fun largeUnicodeTelemetryCanBeReconstructedExactlyAndParentIsLast() {
        val raw = JsonObject().apply {
            addProperty("distanceMeters", 5000)
            addProperty("sensorTelemetry", "가나다😀".repeat(25_000))
        }
        val record = JsonObject().apply { addProperty("externalId", "run-1"); addProperty("source", "health_task"); addProperty("kind", "workout"); add("data", raw) }
        val split = CloudRecords.split(record)
        assertTrue(split.size > 2)
        assertEquals("run-1", split.last().get("externalId").asString)
        val reconstructed = split.dropLast(1).joinToString("") {
            val wire = String(it.toString().toByteArray(Charsets.UTF_8), Charsets.UTF_8)
            JsonParser.parseString(wire).asJsonObject.getAsJsonObject("data").get("jsonPart").asString
        }
        assertEquals(raw.toString(), reconstructed)
        assertEquals(CloudRecords.hash(raw.toString()), split.last().getAsJsonObject("data").get("detailContentHash").asString)
        assertFalse(split.last().getAsJsonObject("data").has("sensorTelemetry"))
        assertEquals(split, CloudRecords.split(record))
        CloudRecords.batches(split).forEach { batch ->
            assertTrue(batch.size <= 100)
            assertTrue(batch.sumOf { it.toString().toByteArray(Charsets.UTF_8).size + 1 } <= 1_800_000)
        }
    }

    @Test fun batchesRespectRecordLimit() {
        val records = List(251) { JsonObject().apply { addProperty("id", it) } }
        assertEquals(listOf(100, 100, 51), CloudRecords.batches(records).map { it.size })
        assertEquals(emptyList<List<JsonObject>>(), CloudRecords.batches(emptyList()))
    }

    @Test fun retryBatchIdIsStableAndChangesWithContent() {
        val first = JsonObject().apply { addProperty("externalId", "run-1"); addProperty("deleted", true) }
        val second = first.deepCopy().apply { addProperty("deleted", false) }
        assertEquals(CloudRecords.batchId(listOf(first)), CloudRecords.batchId(listOf(first.deepCopy())))
        assertNotEquals(CloudRecords.batchId(listOf(first)), CloudRecords.batchId(listOf(second)))
        assertNotEquals(CloudRecords.batchId(listOf(first, second)), CloudRecords.batchId(listOf(second, first)))
        java.util.UUID.fromString(CloudRecords.batchId(listOf(first)))
    }
}
