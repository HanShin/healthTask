package com.hanshin.healthtask.data

import androidx.room.withTransaction
import com.hanshin.healthtask.data.db.HealthMeasurementEntity
import com.hanshin.healthtask.data.db.HealthTaskDatabase
import com.hanshin.healthtask.data.db.ProfileEntity
import com.hanshin.healthtask.data.db.SetRecordEntity
import com.hanshin.healthtask.data.db.WorkoutItemEntity
import com.hanshin.healthtask.data.db.WorkoutSessionEntity
import com.hanshin.healthtask.data.db.WorkoutSessionWithItems
import com.hanshin.healthtask.domain.ExerciseCategory
import com.hanshin.healthtask.domain.HealthMetricType
import com.hanshin.healthtask.domain.INBODY_PACKAGE
import com.hanshin.healthtask.domain.PlannedWorkoutType
import com.hanshin.healthtask.domain.RecordMode
import com.hanshin.healthtask.domain.SyncStatus
import com.hanshin.healthtask.domain.WorkoutSource
import com.hanshin.healthtask.domain.WorkoutStatus
import com.hanshin.healthtask.domain.isRun
import com.hanshin.healthtask.shared.WearCompletedWorkout
import com.hanshin.healthtask.shared.WearRecordMode
import com.hanshin.healthtask.shared.RunTelemetryCodec
import com.hanshin.healthtask.shared.summarize
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import com.hanshin.healthtask.domain.isExternal

class HealthTaskRepository(private val database: HealthTaskDatabase) {
    val dao = database.dao()
    val sessions: Flow<List<WorkoutSessionWithItems>> = dao.observeSessions()
    val healthMeasurements: Flow<List<HealthMeasurementEntity>> = dao.observeHealthMeasurements()
    val inBodyRecords = dao.observeInBodyRecords()
    val inBodyCsv = InBodyCsvStorage(database)
    val workoutLinks = dao.observeWorkoutLinks()

    suspend fun initialize() = database.withTransaction {
        // Historical strength tables remain intact for lossless upgrades and backup restore.
        val existing = dao.getExercises().mapTo(mutableSetOf()) { it.id }
        dao.upsertExercises(SeedData.exercises.filterNot { it.id in existing })
        // Keep the legacy profile for backup compatibility, without configuring goals.
        if (dao.getProfile() == null) dao.upsertProfile(ProfileEntity(onboardingDone = true))
    }

    suspend fun savePhoneRun(
        sessionId: String = "run-${UUID.randomUUID()}",
        startedAt: Long,
        endedAt: Long,
        elapsedMillis: Long,
        distanceMeters: Double,
        routePolyline: String?,
        lapData: String?,
        planSlotId: String? = null,
    ): WorkoutSessionEntity {
        require(sessionId.isNotBlank()) { "러닝 세션 ID가 필요합니다." }
        require(endedAt >= startedAt) { "러닝 시간이 올바르지 않습니다." }
        require(elapsedMillis >= 1_000L) { "1초 이상 기록한 뒤 완료해 주세요." }
        val distanceKm = distanceMeters.coerceAtLeast(0.0) / 1_000.0
        val durationMinutes = elapsedMillis / 60_000.0
        val sessionDate = Instant.ofEpochMilli(startedAt)
            .atZone(ZoneId.systemDefault()).toLocalDate().toString()
        val planSlot = planSlotId?.let { dao.getPlanSlot(it) }
        require(planSlot == null || planSlot.workoutType.isRun) { "러닝 계획만 GPS 기록과 연결할 수 있습니다." }
        val exerciseId = when (planSlot?.workoutType) {
            PlannedWorkoutType.QUALITY_RUN -> "tempo-run"
            PlannedWorkoutType.LONG_RUN -> "long-run"
            else -> "easy-run"
        }
        val phoneSession = WorkoutSessionEntity(
            id = sessionId,
            planSlotId = planSlot?.id,
            title = planSlot?.title ?: "러닝",
            sessionDate = sessionDate,
            status = WorkoutStatus.COMPLETED,
            source = WorkoutSource.LOCAL,
            startedAt = startedAt,
            endedAt = endedAt,
            memo = PHONE_RUN_MEMO,
            distanceKm = distanceKm,
            routePolyline = routePolyline,
            lapData = lapData,
            activeDurationMillis = elapsedMillis,
            syncStatus = SyncStatus.PENDING,
        )
        var savedSession = phoneSession
        database.withTransaction {
            val existing = dao.getSession(sessionId)?.session
            savedSession = phoneSession.copy(
                caloriesKcal = existing?.caloriesKcal,
                averageHeartRateBpm = existing?.averageHeartRateBpm,
                sensorSummaryJson = existing?.sensorSummaryJson,
                distanceKm = distanceKm.takeIf { it > 0.0 } ?: existing?.distanceKm,
                routePolyline = routePolyline?.takeIf { it.isNotBlank() } ?: existing?.routePolyline,
                createdAt = existing?.createdAt ?: phoneSession.createdAt,
            )
            dao.deleteSetsForSession(sessionId)
            dao.deleteItemsForSession(sessionId)
            dao.upsertSession(savedSession)
            dao.upsertWorkoutItems(listOf(WorkoutItemEntity(
                id = "$sessionId-running",
                sessionId = sessionId,
                exerciseId = exerciseId,
                exerciseName = planSlot?.title ?: "러닝",
                orderIndex = 1,
                category = ExerciseCategory.CARDIO,
                recordMode = RecordMode.CARDIO,
                activityLabel = planSlot?.title ?: "GPS 러닝",
                distanceKm = savedSession.distanceKm,
                durationMin = durationMinutes,
                avgPaceMinPerKm = savedSession.distanceKm?.takeIf { it > 0.02 }?.let { durationMinutes / it },
            )))
        }
        return savedSession
    }

    suspend fun deleteSession(id: String) = database.withTransaction {
        dao.deleteTelemetryChunks(id)
        dao.deleteSetsForSession(id)
        dao.deleteItemsForSession(id)
        dao.deleteSession(id)
    }

    suspend fun saveManualHealthMetric(date: LocalDate, type: HealthMetricType, value: Double) =
        saveBodyMeasurement(date, type, value)

    suspend fun saveBodyMeasurement(date: LocalDate, type: HealthMetricType, value: Double, existingId: String? = null) {
        require(!date.isAfter(LocalDate.now())) { "측정일은 오늘 또는 과거 날짜로 입력해 주세요." }
        require(com.hanshin.healthtask.domain.isValidBodyValue(type, value)) { "올바른 측정값을 입력해 주세요." }
        val existing = existingId?.let { requireNotNull(dao.getHealthMeasurement(it)) { "기록을 찾을 수 없습니다." } }
        require(existing?.externalRecordId?.startsWith("inbody-csv-") != true) { "CSV 기록은 원본 파일을 보존합니다. 측정 기록 전체를 삭제한 후 다시 가져와 주세요." }
        require(existing == null || !existing.source.isExternal()) { "연동 기록은 원본 앱에서 수정해 주세요." }
        val now = System.currentTimeMillis()
        dao.upsertHealthMeasurements(listOf(HealthMeasurementEntity(
            id = existing?.id ?: "health-local-${UUID.randomUUID()}",
            recordDate = date.toString(),
            measuredAt = if (date == LocalDate.now()) now else date.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
            type = type, value = value, source = WorkoutSource.LOCAL,
            sourcePackage = existing?.sourcePackage,
            createdAt = existing?.createdAt ?: now, updatedAt = now,
        )))
    }

    suspend fun deleteBodyMeasurement(id: String) {
        val metric = dao.getHealthMeasurement(id) ?: return
        require(metric.externalRecordId?.startsWith("inbody-csv-") != true) { "CSV 기록은 측정 기록 전체에서 삭제해 주세요." }
        require(!metric.source.isExternal()) { "연동 기록은 원본 앱에서 삭제해 주세요." }
        dao.deleteHealthMeasurement(id)
    }

    suspend fun saveInBodyScreenshotMetrics(date: LocalDate, values: Map<HealthMetricType, Double>) {
        require(!date.isAfter(LocalDate.now())) { "측정일을 확인해 주세요." }
        val accepted = values.filterKeys { it != HealthMetricType.BODY_WATER_L }
        require(accepted.isNotEmpty()) { "저장할 인바디 수치가 없습니다." }
        require(accepted.all { (type, value) -> com.hanshin.healthtask.domain.isValidBodyValue(type, value) }) {
            "올바른 인바디 수치를 입력해 주세요."
        }
        val now = System.currentTimeMillis()
        val measuredAt = if (date == LocalDate.now()) {
            now
        } else {
            date.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }
        dao.upsertHealthMeasurements(accepted.map { (type, value) ->
            HealthMeasurementEntity(
                id = "health-inbody-${date}-$type",
                recordDate = date.toString(),
                measuredAt = measuredAt,
                type = type,
                value = value,
                source = WorkoutSource.LOCAL,
                sourcePackage = INBODY_PACKAGE,
                updatedAt = now,
            )
        })
    }

    suspend fun getSession(id: String) = dao.getSession(id)
    suspend fun getAllSessions() = dao.getSessions()
    suspend fun getLinks() = dao.getWorkoutLinks()

    // Old watch queues may still contain strength workouts. Preserve them as archived
    // records; the running-only UI and Health Connect upload never include them.
    /** Imports a completed watch workout exactly once, even if the Data Layer retries delivery. */
    suspend fun importWearWorkout(workout: WearCompletedWorkout): Boolean {
        require(workout.endedAt >= workout.startedAt) { "워치 운동 시간이 올바르지 않습니다." }
        require(workout.schemaVersion in 1..5) { "워치 앱과 휴대폰 앱을 모두 업데이트해 주세요." }
        val telemetryChunks = workout.telemetry?.let {
            require(it.metadata.sessionId == workout.sessionId) { "센서 기록의 러닝 ID가 다릅니다." }
            it.toStorageChunks().also { chunks -> chunks.decodeTelemetry() }
        }
        val sensorSummary = workout.telemetry?.summarize()?.let(RunTelemetryCodec::encodeSummary)
        val sessionDate = Instant.ofEpochMilli(workout.startedAt)
            .atZone(ZoneId.systemDefault()).toLocalDate().toString()
        val completedItems = workout.exercises.count { exercise ->
            when (exercise.recordMode) {
                WearRecordMode.SETS -> exercise.sets.isNotEmpty() && exercise.sets.all { it.completed }
                WearRecordMode.CARDIO -> (exercise.durationMin ?: 0.0) > 0.0
            }
        }
        val status = when {
            completedItems == 0 -> WorkoutStatus.SKIPPED
            completedItems == workout.exercises.size -> WorkoutStatus.COMPLETED
            else -> WorkoutStatus.PARTIAL
        }
        var imported = false
        database.withTransaction {
            val existing = dao.getSession(workout.sessionId)
            if (telemetryChunks != null) {
                dao.deleteTelemetryChunks(workout.sessionId)
                dao.upsertTelemetryChunks(telemetryChunks)
            }
            if (existing != null && workout.isPhoneStartedRun()) {
                val current = existing.session
                val mergedDistance = current.distanceKm?.takeIf { it > 0.0 } ?: workout.distanceKm
                val mergedRoute = current.routePolyline?.takeIf { it.isNotBlank() } ?: workout.routePolyline
                val mergedDuration = current.activeDurationMillis?.takeIf { it > 0L } ?: workout.activeDurationMillis
                val merged = current.copy(
                    distanceKm = mergedDistance,
                    caloriesKcal = workout.caloriesKcal ?: current.caloriesKcal,
                    averageHeartRateBpm = workout.averageHeartRateBpm ?: current.averageHeartRateBpm,
                    routePolyline = mergedRoute,
                    activeDurationMillis = mergedDuration,
                    sensorSummaryJson = sensorSummary ?: current.sensorSummaryJson,
                )
                val mergedItems = existing.items.map { itemWithSets ->
                    val item = itemWithSets.item
                    if (item.recordMode != RecordMode.CARDIO) item else {
                        val durationMinutes = mergedDuration?.div(60_000.0) ?: item.durationMin
                        item.copy(
                            distanceKm = mergedDistance,
                            durationMin = durationMinutes,
                            avgPaceMinPerKm = mergedDistance?.takeIf { it > 0.02 }
                                ?.let { distance -> durationMinutes?.div(distance) },
                        )
                    }
                }
                val childrenChanged = mergedItems.zip(existing.items).any { (updated, original) ->
                    updated != original.item
                }
                if (merged != current || childrenChanged) {
                    imported = true
                    dao.updateSession(merged.copy(
                        syncStatus = SyncStatus.PENDING,
                        syncError = null,
                        updatedAt = System.currentTimeMillis(),
                    ))
                    if (childrenChanged) dao.upsertWorkoutItems(mergedItems)
                }
                return@withTransaction
            }
            if (existing?.session?.memo == WATCH_WORKOUT_MEMO) {
                if (sensorSummary != null && sensorSummary != existing.session.sensorSummaryJson) {
                    dao.updateSession(existing.session.copy(sensorSummaryJson = sensorSummary))
                    imported = true
                }
                return@withTransaction
            }
            imported = true
            dao.deleteSetsForSession(workout.sessionId)
            dao.deleteItemsForSession(workout.sessionId)
            dao.upsertSession(WorkoutSessionEntity(
                id = workout.sessionId,
                routineId = workout.routineId,
                planSlotId = workout.planSlotId,
                title = workout.title,
                sessionDate = sessionDate,
                status = status,
                source = WorkoutSource.LOCAL,
                startedAt = workout.startedAt,
                endedAt = workout.endedAt,
                memo = WATCH_WORKOUT_MEMO,
                distanceKm = workout.distanceKm,
                caloriesKcal = workout.caloriesKcal,
                averageHeartRateBpm = workout.averageHeartRateBpm,
                routePolyline = workout.routePolyline,
                activeDurationMillis = workout.activeDurationMillis,
                sensorSummaryJson = sensorSummary,
                syncStatus = SyncStatus.PENDING,
                createdAt = existing?.session?.createdAt ?: System.currentTimeMillis(),
            ))
            workout.exercises.sortedBy { it.order }.forEach { exercise ->
                val itemId = "${workout.sessionId}-item-${exercise.order}"
                val recordMode = if (exercise.recordMode == WearRecordMode.CARDIO) RecordMode.CARDIO else RecordMode.SETS
                val category = runCatching { ExerciseCategory.valueOf(exercise.category) }
                    .getOrDefault(if (recordMode == RecordMode.CARDIO) ExerciseCategory.CARDIO else ExerciseCategory.WEIGHT)
                dao.upsertWorkoutItems(listOf(WorkoutItemEntity(
                    id = itemId,
                    sessionId = workout.sessionId,
                    exerciseId = exercise.exerciseId,
                    exerciseName = exercise.name,
                    orderIndex = exercise.order,
                    category = category,
                    recordMode = recordMode,
                    distanceKm = exercise.distanceKm,
                    durationMin = exercise.durationMin,
                    avgPaceMinPerKm = if ((exercise.distanceKm ?: 0.0) > 0.0) {
                        exercise.durationMin?.div(exercise.distanceKm!!)
                    } else null,
                )))
                if (recordMode == RecordMode.SETS) {
                    dao.upsertSetRecords(exercise.sets.map { set ->
                        SetRecordEntity(
                            id = "$itemId-set-${set.order}",
                            workoutItemId = itemId,
                            orderIndex = set.order,
                            plannedReps = set.reps,
                            actualReps = set.reps,
                            plannedWeightKg = set.weightKg,
                            actualWeightKg = set.weightKg,
                            completed = set.completed,
                        )
                    })
                }
            }
        }
        return imported
    }

    private fun WearCompletedWorkout.isPhoneStartedRun(): Boolean =
        sessionId.startsWith("run-") && exercises.isNotEmpty() &&
            exercises.all { it.recordMode == WearRecordMode.CARDIO }

    private companion object {
        const val PHONE_RUN_MEMO = "휴대폰 GPS로 기록"
        const val WATCH_WORKOUT_MEMO = "Galaxy Watch에서 기록"
    }
}
