package com.hanshin.healthtask.data

import com.hanshin.healthtask.data.db.RunTelemetryChunkEntity
import com.hanshin.healthtask.shared.RunTelemetry
import com.hanshin.healthtask.shared.RunTelemetryCodec

internal fun RunTelemetry.toStorageChunks(): List<RunTelemetryChunkEntity> =
    RunTelemetryCodec.encode(this).chunked(32_000).mapIndexed { index, part ->
        RunTelemetryChunkEntity(metadata.sessionId, index, part)
    }

internal fun List<RunTelemetryChunkEntity>.decodeTelemetry(): RunTelemetry? {
    if (isEmpty()) return null
    val sorted = sortedBy { it.chunkIndex }
    require(sorted.map { it.sessionId }.distinct().size == 1 &&
        sorted.map { it.chunkIndex } == sorted.indices.toList()) { "상세 센서 기록의 일부가 누락됐습니다." }
    return RunTelemetryCodec.decode(sorted.joinToString("") { it.jsonPart }).also {
        require(it.metadata.sessionId == sorted.first().sessionId) { "센서 기록과 러닝 ID가 다릅니다." }
    }
}
