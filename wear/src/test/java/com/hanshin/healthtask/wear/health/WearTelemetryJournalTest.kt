package com.hanshin.healthtask.wear.health

import com.hanshin.healthtask.shared.RunSensorMetadata
import com.hanshin.healthtask.shared.RunSensorRecord
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlinx.coroutines.runBlocking

class WearTelemetryJournalTest {
    @get:Rule val temp = TemporaryFolder()
    private val metadata = RunSensorMetadata("watch/run", 1_000, 1_000, 0, "Asia/Seoul", "test", "test", 36,
        listOf("heartRateBpm"), listOf("heartRateBpm"), emptyList())

    @Test fun appendSurvivesRecorderRecreationAndKeepsAllBatchedPoints() {
        val directory = temp.newFolder()
        val journal = WearTelemetryJournal(directory)
        journal.begin(metadata)
        val samples = (0..4_000).map { RunSensorRecord("sample", 1_000L + it, metric = "heartRateBpm", value = 150.0) }
        samples.chunked(200).forEach { journal.append(metadata.sessionId, it) }
        val recreated = WearTelemetryJournal(directory)
        recreated.begin(metadata)
        assertEquals(samples, recreated.read(metadata.sessionId)!!.records)
        assertEquals(metadata, recreated.read(metadata.sessionId)!!.metadata)
        assertEquals(1, directory.listFiles()!!.size)
    }

    @Test fun sessionsAreIsolatedAndCleanupOnlyRemovesSuccessfullyQueuedSession() {
        val journal = WearTelemetryJournal(temp.newFolder())
        journal.begin(metadata)
        journal.begin(metadata.copy(sessionId = "another"))
        journal.removeQueued(metadata.sessionId)
        assertNull(journal.read(metadata.sessionId))
        assertNotNull(journal.read("another"))
    }

    @Test fun exactDuplicateBatchesAreDeduplicatedButDifferentMetricsAreRetained() {
        val journal = WearTelemetryJournal(temp.newFolder())
        journal.begin(metadata)
        val point = RunSensorRecord("sample", 1_000, metric = "heartRateBpm", value = 150.0)
        journal.append(metadata.sessionId, listOf(point, point, point.copy(metric = "cadenceStepsPerMinute")))
        assertEquals(2, journal.read(metadata.sessionId)!!.records.size)
    }

    @Test fun snapshotWaitsForEveryQueuedBatchBeforeFinishing() = runBlocking {
        val recorder = WearTelemetryRecorder(temp.newFolder())
        recorder.begin(metadata)
        repeat(100) { batch ->
            recorder.append(metadata.sessionId, (0 until 100).map {
                RunSensorRecord("sample", (batch * 100L) + it, metric = "heartRateBpm", value = 150.0)
            })
        }
        assertEquals(10_000, recorder.snapshot(metadata.sessionId)!!.records.size)
    }
}
