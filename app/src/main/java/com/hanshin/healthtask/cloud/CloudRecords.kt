package com.hanshin.healthtask.cloud

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.security.MessageDigest
import java.time.Instant

/** The same IDs are used by the web JSON importer. No server-side identity comes from this payload. */
object CloudRecords {
    fun fromExport(root: JsonObject): List<JsonObject> {
        val analysis = root.getAsJsonObject("analysis") ?: error("상세 분석 데이터가 없습니다.")
        val zone = analysis.getAsJsonObject("metadata").get("exportTimeZoneId").asString
        val records = mutableListOf<JsonObject>()
        analysis.getAsJsonArray("runningSessions").forEach { item ->
            val data = item.asJsonObject.deepCopy()
            val session = data.getAsJsonObject("record").getAsJsonObject("session")
            val calculations = data.getAsJsonObject("calculations")
            listOf("distanceMeters", "activeDurationMillis").forEach { data.add(it, calculations.get(it)) }
            listOf("averageHeartRateBpm", "caloriesKcal").forEach { data.add(it, session.get(it)) }
            records += record(data.get("sessionId").asString, "workout", data.get("startedAtUtc").asString, zone, data, session.get("updatedAt")?.asLong)
        }
        analysis.getAsJsonArray("bodyMeasurements").forEach { item ->
            val data = item.asJsonObject.deepCopy()
            val raw = data.getAsJsonObject("record")
            data.add("metric", raw.get("type")); data.add("value", raw.get("value")); data.add("sourcePackage", raw.get("sourcePackage"))
            records += record(raw.get("id").asString, "measurement", data.get("measuredAtUtc").asString, zone, data, raw.get("updatedAt")?.asLong)
        }
        analysis.getAsJsonArray("inBodyRecords")?.forEach { item ->
            val data = item.asJsonObject.deepCopy().apply { addProperty("recordType", "inbody_raw") }
            records += record("inbody:" + data.get("id").asString, "measurement", data.get("measuredAtUtc").asString, data.get("assumedTimeZoneId").asString, data, null)
        }
        return records
    }

    private fun record(id: String, kind: String, at: String, zone: String, data: JsonObject, updated: Long?) = JsonObject().apply {
        addProperty("source", "health_task"); addProperty("externalId", id); addProperty("kind", kind)
        addProperty("measuredAt", at); addProperty("timezone", zone)
        // Stable fallback keeps unchanged imported rows unchanged on subsequent uploads.
        addProperty("updatedAt", updated?.let { Instant.ofEpochMilli(it).toString() } ?: at)
        add("data", data)
    }

    fun hash(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    /** Keep a compact summary and immutable chunks; never truncate raw GPS, CSV or sensor data. */
    fun split(record: JsonObject, maxCharacters: Int = 32_000): List<JsonObject> {
        val data = record.getAsJsonObject("data")
        val json = data.toString()
        if (json.length <= maxCharacters) return listOf(record)
        val hash = hash(json)
        require(maxCharacters >= 2)
        val parts = mutableListOf<String>()
        var offset = 0
        while (offset < json.length) {
            var end = (offset + maxCharacters).coerceAtMost(json.length)
            // A network UTF-8 encoder replaces lone surrogates, so do not split emoji pairs.
            if (end < json.length && json[end - 1].isHighSurrogate() && json[end].isLowSurrogate()) end--
            parts += json.substring(offset, end)
            offset = end
        }
        val parent = record.deepCopy()
        val summary = data.deepCopy()
        listOf("sensorTelemetry", "routePoints", "laps", "samples", "stages", "rawFields").forEach { summary.remove(it) }
        // Stored raw route/lap strings can also be large.
        summary.getAsJsonObject("record")?.getAsJsonObject("session")?.let { it.remove("routePolyline"); it.remove("lapData") }
        summary.addProperty("detailContentHash", hash); summary.addProperty("detailPartCount", parts.size)
        parent.add("data", summary)
        check(parent.toString().length < 200_000) { "요약 데이터가 너무 큽니다." }
        val children = parts.mapIndexed { index, part -> record.deepCopy().apply {
            addProperty("externalId", record.get("externalId").asString + ":part:$hash:$index")
            add("data", JsonObject().apply {
                addProperty("recordType", "detail_part"); addProperty("parentExternalId", record.get("externalId").asString)
                addProperty("contentHash", hash); addProperty("partIndex", index); addProperty("partCount", parts.size); addProperty("jsonPart", part)
            })
        } }
        // Publish parent after all detail parts have reached the server.
        return children + parent
    }

    fun batchId(records: List<JsonObject>): String = java.util.UUID.nameUUIDFromBytes(
        JsonArray().apply { records.forEach { add(it) } }.toString().toByteArray(Charsets.UTF_8)
    ).toString()

    fun batches(records: List<JsonObject>): List<List<JsonObject>> {
        val result = mutableListOf<List<JsonObject>>()
        var current = mutableListOf<JsonObject>()
        var bytes = 0
        records.forEach { record ->
            val size = record.toString().toByteArray(Charsets.UTF_8).size + 1
            check(size < 1_800_000) { "전송할 기록이 너무 큽니다." }
            if (current.size >= 100 || bytes + size > 1_800_000) { result += current; current = mutableListOf(); bytes = 0 }
            current += record; bytes += size
        }
        if (current.isNotEmpty()) result += current
        return result
    }
}
