package com.hanshin.healthtask.health

import androidx.room.withTransaction
import com.hanshin.healthtask.data.AppPreferences
import com.hanshin.healthtask.data.db.HealthMeasurementEntity
import com.hanshin.healthtask.data.db.HealthTaskDatabase
import com.hanshin.healthtask.data.db.SamsungWorkoutLinkEntity
import com.hanshin.healthtask.data.db.SyncStateEntity
import com.hanshin.healthtask.data.db.WorkoutSessionEntity
import com.hanshin.healthtask.data.db.WorkoutItemEntity
import com.hanshin.healthtask.domain.RecordMode
import com.hanshin.healthtask.domain.SyncStatus
import com.hanshin.healthtask.domain.WorkoutSource
import com.hanshin.healthtask.domain.WorkoutStatus
import com.hanshin.healthtask.domain.WorkoutSummary
import com.hanshin.healthtask.domain.bestAutomaticLink
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class SyncResult(
    val uploaded: Int = 0,
    val downloadedWorkouts: Int = 0,
    val downloadedMeasurements: Int = 0,
    val linked: Int = 0,
    val error: String? = null,
)

class HealthSyncManager(
    private val database: HealthTaskDatabase,
    private val gateway: HealthConnectGateway,
    private val preferences: AppPreferences,
) {
    private val syncMutex = Mutex()

    suspend fun sync(force: Boolean = false): SyncResult = syncMutex.withLock {
        if (!force && !preferences.sync.first().enabled) return SyncResult()
        if (gateway.status() != HealthConnectStatus.CONNECTED) {
            return SyncResult(error = "Health Connect 권한이 필요합니다.")
        }
        return try {
            val uploaded = uploadPending()
            val changesToken = database.dao().getSyncState(CHANGES_TOKEN)?.value
            val snapshot = gateway.readSamsungData(changesToken)
            var linked = 0
            database.withTransaction {
                val dao = database.dao()
                snapshot.deletedRecordIds.forEach { recordId ->
                    dao.getSessionByHealthRecord(recordId)?.takeIf { it.source == WorkoutSource.SAMSUNG_HEALTH }?.let { session ->
                        dao.deleteWorkoutLinkForSamsungSession(session.id)
                        dao.deleteSetsForSession(session.id)
                        dao.deleteItemsForSession(session.id)
                        dao.deleteSession(session.id)
                    }
                    dao.deleteHealthMeasurementsByExternalRecord(recordId)
                }
                snapshot.workouts.forEach { record ->
                    val id = samsungSessionId(record.recordId)
                    val existing = dao.getSessionByHealthRecord(record.recordId)
                    dao.upsertSession(WorkoutSessionEntity(
                        id = existing?.id ?: id,
                        title = record.title,
                        sessionDate = record.start.atZone(ZoneId.systemDefault()).toLocalDate().toString(),
                        status = WorkoutStatus.COMPLETED,
                        source = WorkoutSource.SAMSUNG_HEALTH,
                        startedAt = record.start.toEpochMilli(),
                        endedAt = record.end.toEpochMilli(),
                        healthConnectRecordId = record.recordId,
                        sourcePackage = record.sourcePackage,
                        distanceKm = record.distanceKm,
                        caloriesKcal = record.caloriesKcal,
                        syncStatus = SyncStatus.SYNCED,
                        createdAt = existing?.createdAt ?: System.currentTimeMillis(),
                        updatedAt = System.currentTimeMillis(),
                    ))
                    dao.upsertWorkoutItems(listOf(WorkoutItemEntity(
                        id = "$id-summary",
                        sessionId = existing?.id ?: id,
                        exerciseId = "health-connect-${record.category.name.lowercase()}",
                        exerciseName = record.title,
                        orderIndex = 1,
                        category = record.category,
                        recordMode = if (record.category == com.hanshin.healthtask.domain.ExerciseCategory.CARDIO) RecordMode.CARDIO else RecordMode.SETS,
                        durationMin = java.time.Duration.between(record.start, record.end).toMillis() / 60_000.0,
                    )))
                }
                dao.upsertHealthMeasurements(snapshot.measurements.map { metric ->
                    HealthMeasurementEntity(
                        id = "samsung-${metric.recordId}-${metric.type}",
                        recordDate = metric.measuredAt.atZone(ZoneId.systemDefault()).toLocalDate().toString(),
                        measuredAt = metric.measuredAt.toEpochMilli(),
                        type = metric.type,
                        value = metric.value,
                        source = WorkoutSource.SAMSUNG_HEALTH,
                        externalRecordId = metric.recordId,
                        sourcePackage = metric.sourcePackage,
                        updatedAt = System.currentTimeMillis(),
                    )
                })

                val all = dao.getSessions()
                val categories = all.associate { full -> full.session.id to full.items.firstOrNull()?.item?.category }
                val existingLinks = dao.getWorkoutLinks()
                val linkedSamsung = existingLinks.mapTo(mutableSetOf()) { it.samsungSessionId }
                val linkedLocal = existingLinks.mapTo(mutableSetOf()) { it.localSessionId }
                val local = all.map { it.session }.filter { it.source == WorkoutSource.LOCAL && it.id !in linkedLocal }
                all.asSequence()
                    .map { it.session }
                    .filter { it.source == WorkoutSource.SAMSUNG_HEALTH && it.id !in linkedSamsung }
                    .forEach { samsung ->
                        val match = bestAutomaticLink(local, samsung, categories[samsung.id]) { categories[it] }
                        if (match != null) {
                            dao.upsertWorkoutLink(SamsungWorkoutLinkEntity(
                                id = "link-${UUID.randomUUID()}",
                                localSessionId = match.first.id,
                                samsungSessionId = samsung.id,
                                overlapRatio = match.second,
                            ))
                            linkedLocal += match.first.id
                            linkedSamsung += samsung.id
                            linked++
                        }
                    }
                dao.upsertSyncState(SyncStateEntity("last_success", System.currentTimeMillis().toString()))
                snapshot.nextChangesToken?.let { dao.upsertSyncState(SyncStateEntity(CHANGES_TOKEN, it)) }
            }
            preferences.markSynced()
            SyncResult(uploaded, snapshot.workouts.size, snapshot.measurements.size, linked)
        } catch (error: Throwable) {
            database.dao().upsertSyncState(SyncStateEntity("last_error", error.message ?: error.javaClass.simpleName))
            SyncResult(error = error.message ?: "동기화에 실패했습니다.")
        }
    }

    private suspend fun uploadPending(): Int {
        var success = 0
        val dao = database.dao()
        dao.getPendingSessions().forEach { session ->
            if (session.status == WorkoutStatus.SKIPPED || session.endedAt == null) {
                dao.updateSession(session.copy(syncStatus = SyncStatus.SYNCED, syncError = null))
                return@forEach
            }
            val full = dao.getSession(session.id) ?: return@forEach
            val category = full.items.firstOrNull()?.item?.category ?: return@forEach
            try {
                val recordId = gateway.writeWorkout(WorkoutSummary(
                    sessionId = session.id,
                    title = session.title,
                    category = category,
                    startedAt = java.time.Instant.ofEpochMilli(session.startedAt),
                    endedAt = java.time.Instant.ofEpochMilli(session.endedAt),
                ))
                dao.updateSession(session.copy(
                    healthConnectRecordId = recordId,
                    syncStatus = SyncStatus.SYNCED,
                    syncError = null,
                    updatedAt = System.currentTimeMillis(),
                ))
                success++
            } catch (error: Throwable) {
                dao.updateSession(session.copy(
                    syncStatus = SyncStatus.ERROR,
                    syncError = error.message ?: error.javaClass.simpleName,
                    updatedAt = System.currentTimeMillis(),
                ))
            }
        }
        return success
    }

    private fun samsungSessionId(recordId: String) = "samsung-$recordId"

    private companion object { const val CHANGES_TOKEN = "health_changes_token" }
}
