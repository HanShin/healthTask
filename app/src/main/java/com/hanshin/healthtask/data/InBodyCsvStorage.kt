package com.hanshin.healthtask.data

import androidx.room.withTransaction
import com.hanshin.healthtask.data.db.HealthMeasurementEntity
import com.hanshin.healthtask.data.db.HealthTaskDatabase
import com.hanshin.healthtask.data.db.InBodyRecordEntity
import com.hanshin.healthtask.domain.INBODY_PACKAGE
import com.hanshin.healthtask.domain.WorkoutSource
import com.hanshin.healthtask.health.InBodyCsvParser
import com.hanshin.healthtask.health.InBodyCsvPreview
import java.time.LocalDateTime
import java.time.ZoneId

data class InBodyCsvImportReport(val inserted: Int, val expanded: Int, val skipped: Int, val conflicts: Int)

class InBodyCsvStorage(private val database: HealthTaskDatabase) {
    suspend fun save(preview: InBodyCsvPreview): InBodyCsvImportReport = database.withTransaction {
        val dao = database.dao()
        var inserted = 0
        var expanded = 0
        var skipped = 0
        var conflicts = 0
        preview.records.forEach { incoming ->
            val existing = dao.getInBodyRecord(incoming.id)
            val raw = existing?.let { InBodyCsvParser.decodeRaw(it.rawFieldsJson).toMutableMap() } ?: linkedMapOf()
            var changed = existing == null
            incoming.rawFields.forEach { (header, value) ->
                // Treat harmless header whitespace/superscript changes as the same metric.
                val type = InBodyCsvParser.metricType(header)
                val key = raw.keys.firstOrNull { it == header || (type != null && InBodyCsvParser.metricType(it) == type) } ?: header
                val old = raw[key]
                if (old == null || (InBodyCsvParser.isMissing(old) && !InBodyCsvParser.isMissing(value))) {
                    raw[key] = value; changed = true
                } else if (!InBodyCsvParser.isMissing(value) && old.trim() != value.trim()) {
                    val equivalent = type?.let { InBodyCsvParser.numericValue(it, old)?.let { number -> number == InBodyCsvParser.numericValue(it, value) } } == true
                    if (!equivalent && key !in setOf("날짜", "측정장비")) conflicts++
                }
            }
            if (!changed) { skipped++; return@forEach }
            val now = System.currentTimeMillis()
            val record = existing?.copy(rawFieldsJson = InBodyCsvParser.encodeRaw(raw), updatedAt = now)
                ?: InBodyRecordEntity(incoming.id, incoming.measuredLocal, incoming.measuredAt, incoming.timeZoneId,
                    incoming.device, preview.fileName, InBodyCsvParser.encodeRaw(raw), now, now)
            validateInBodyRecord(record)
            dao.upsertInBodyRecord(record)
            dao.upsertHealthMeasurements(record.toHealthMeasurements())
            if (existing == null) inserted++ else expanded++
        }
        InBodyCsvImportReport(inserted, expanded, skipped, conflicts)
    }

    suspend fun delete(id: String) = database.withTransaction {
        database.dao().deleteInBodyRecordMetrics(id)
        database.dao().deleteInBodyRecord(id)
    }
}

internal fun InBodyRecordEntity.toHealthMeasurements(): List<HealthMeasurementEntity> =
    InBodyCsvParser.values(InBodyCsvParser.decodeRaw(rawFieldsJson)).map { (type, value) ->
        HealthMeasurementEntity(id = "$id-${type.name}", recordDate = LocalDateTime.parse(measuredLocal).toLocalDate().toString(),
            measuredAt = measuredAt, type = type, value = value, source = WorkoutSource.LOCAL,
            externalRecordId = id, sourcePackage = INBODY_PACKAGE, createdAt = createdAt, updatedAt = updatedAt)
    }

internal fun validateInBodyRecord(record: InBodyRecordEntity) {
    require(record.rawFieldsJson.length <= 1024 * 1024) { "인바디 원본 기록이 너무 큽니다." }
    val date = LocalDateTime.parse(record.measuredLocal)
    val zone = ZoneId.of(record.timeZoneId)
    require(zone.rules.getValidOffsets(date).size == 1 && date.atZone(zone).toInstant().toEpochMilli() == record.measuredAt) {
        "인바디 측정 시간과 시간대가 맞지 않습니다."
    }
    require(record.id == InBodyCsvParser.recordId(date.toString(), record.device)) { "인바디 기록 ID가 맞지 않습니다." }
    val raw = InBodyCsvParser.decodeRaw(record.rawFieldsJson)
    require(raw.size in 2..128 && raw.values.all { it.length <= 4096 }) { "인바디 원본 항목을 확인해 주세요." }
    require(raw["측정장비"]?.trim() == record.device && raw.containsKey("날짜")) { "인바디 원본 측정정보가 맞지 않습니다." }
    require(InBodyCsvParser.parseDate(raw.getValue("날짜")) == date) { "인바디 원본 측정 시각이 맞지 않습니다." }
    val knownTypes = raw.keys.mapNotNull(InBodyCsvParser::metricType)
    require(knownTypes.distinct().size == knownTypes.size) { "인바디 원본에 중복 항목이 있습니다." }
}
