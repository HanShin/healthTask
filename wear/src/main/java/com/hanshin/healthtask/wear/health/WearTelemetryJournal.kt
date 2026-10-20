package com.hanshin.healthtask.wear.health

import com.google.gson.Gson
import com.hanshin.healthtask.shared.RunSensorMetadata
import com.hanshin.healthtask.shared.RunSensorRecord
import com.hanshin.healthtask.shared.RunTelemetry
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** Append-only private storage: no growing list on the UI thread and no sampling/point cap. */
internal class WearTelemetryJournal(private val directory: File) {
    private val gson = Gson()
    private fun file(id: String): File {
        val name = MessageDigest.getInstance("SHA-256").digest(id.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(directory, "$name.jsonl")
    }
    fun begin(metadata: RunSensorMetadata) {
        check(directory.isDirectory || directory.mkdirs()) { "센서 저장 폴더를 만들 수 없습니다." }
        val target = file(metadata.sessionId)
        if (!target.exists()) target.writeText(gson.toJson(metadata) + "\n", Charsets.UTF_8)
    }
    fun append(id: String, records: List<RunSensorRecord>) {
        if (records.isEmpty()) return
        val target = file(id)
        check(target.exists()) { "센서 기록이 초기화되지 않았습니다." }
        target.appendText(records.joinToString("\n", postfix = "\n") { gson.toJson(it) }, Charsets.UTF_8)
    }
    fun read(id: String): RunTelemetry? {
        val target = file(id)
        if (!target.exists()) return null
        return target.bufferedReader(Charsets.UTF_8).use { reader ->
            val metadata = gson.fromJson(reader.readLine(), RunSensorMetadata::class.java)
            require(metadata.sessionId == id)
            val records = reader.lineSequence().filter { it.isNotBlank() }
                .map { gson.fromJson(it, RunSensorRecord::class.java) }.toList()
                .distinct().sortedBy { it.timestampEpochMillis }
            RunTelemetry(metadata = metadata, records = records)
        }
    }
    fun removeQueued(id: String) {
        val target = file(id)
        check(!target.exists() || target.delete()) { "전송된 센서 임시 파일을 정리하지 못했습니다." }
    }
}

/** FIFO worker survives service teardown; snapshot is a barrier after all submitted batches. */
class WearTelemetryRecorder(directory: File) {
    private val journal = WearTelemetryJournal(directory)
    private val queue = Channel<() -> Unit>(Channel.UNLIMITED)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var failure: Throwable? = null
    init { scope.launch { for (work in queue) work() } }

    suspend fun begin(metadata: RunSensorMetadata) = execute {
        failure = null
        journal.begin(metadata)
    }
    fun append(id: String, records: List<RunSensorRecord>) {
        queue.trySend {
            runCatching { journal.append(id, records) }.onFailure {
                failure = it
                WearMetricsRepository.update { metrics -> metrics.copy(error = "상세 센서 기록 저장 실패: ${it.message}") }
            }
        }.getOrThrow()
    }
    suspend fun snapshot(id: String): RunTelemetry? = execute {
        failure?.let { throw it }
        journal.read(id)
    }
    suspend fun removeQueued(id: String) = execute { journal.removeQueued(id) }
    private suspend fun <T> execute(block: () -> T): T {
        val result = CompletableDeferred<T>()
        queue.send { runCatching(block).fold(result::complete, result::completeExceptionally) }
        return result.await()
    }
}
