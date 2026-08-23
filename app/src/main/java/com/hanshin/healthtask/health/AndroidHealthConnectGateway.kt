package com.hanshin.healthtask.health

import android.content.Context
import android.content.Intent
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.changes.DeletionChange
import androidx.health.connect.client.changes.UpsertionChange
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.request.ChangesTokenRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.hanshin.healthtask.domain.ExerciseCategory
import com.hanshin.healthtask.domain.HealthMetricType
import com.hanshin.healthtask.domain.NIKE_RUN_CLUB_PACKAGE
import com.hanshin.healthtask.domain.SAMSUNG_HEALTH_PACKAGE
import com.hanshin.healthtask.domain.ExternalHealthMeasurement
import com.hanshin.healthtask.domain.ExternalWorkout
import com.hanshin.healthtask.domain.WorkoutSource
import com.hanshin.healthtask.domain.WorkoutSummary
import java.time.Instant
import java.time.Duration
import java.time.ZoneId

class AndroidHealthConnectGateway(private val context: Context) : HealthConnectGateway {
    private val client by lazy { HealthConnectClient.getOrCreate(context) }

    override val requiredPermissions: Set<String> = setOf(
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
        HealthPermission.getWritePermission(ExerciseSessionRecord::class),
        HealthPermission.getReadPermission(WeightRecord::class),
        HealthPermission.getReadPermission(BodyFatRecord::class),
        HealthPermission.getReadPermission(DistanceRecord::class),
        HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
        HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY,
    )

    override suspend fun status(): HealthConnectStatus {
        if (HealthConnectClient.getSdkStatus(context) != HealthConnectClient.SDK_AVAILABLE) {
            return HealthConnectStatus.UNAVAILABLE
        }
        val granted = client.permissionController.getGrantedPermissions()
        return if (granted.containsAll(requiredPermissions)) HealthConnectStatus.CONNECTED
        else HealthConnectStatus.PERMISSIONS_REQUIRED
    }

    override suspend fun readHealthConnectData(changesToken: String?): HealthConnectSnapshot {
        check(status() == HealthConnectStatus.CONNECTED) { "Health Connect 권한이 필요합니다." }
        val origins = setOf(DataOrigin(SAMSUNG_HEALTH_PACKAGE), DataOrigin(NIKE_RUN_CLUB_PACKAGE))
        if (changesToken != null) return readChanges(changesToken).withRecentNikeRuns()
        val nextToken = client.getChangesToken(ChangesTokenRequest(
            recordTypes = setOf(ExerciseSessionRecord::class, WeightRecord::class, BodyFatRecord::class),
            dataOriginFilters = origins,
        ))
        val range = TimeRangeFilter.between(Instant.EPOCH, Instant.now().plusSeconds(1))
        val workouts = readAll(ExerciseSessionRecord::class, range, origins).map { toWorkout(it) }
        val samsungOrigin = setOf(DataOrigin(SAMSUNG_HEALTH_PACKAGE))
        val weights = readAll(WeightRecord::class, range, samsungOrigin).map(::toMeasurement)
        val bodyFat = readAll(BodyFatRecord::class, range, samsungOrigin).map(::toMeasurement)
        return HealthConnectSnapshot(workouts, weights + bodyFat, nextChangesToken = nextToken)
    }

    override suspend fun writeWorkout(summary: WorkoutSummary): String {
        check(status() == HealthConnectStatus.CONNECTED) { "Health Connect 권한이 필요합니다." }
        val zone = ZoneId.systemDefault().rules.getOffset(summary.startedAt)
        val response = client.insertRecords(listOf(ExerciseSessionRecord(
            startTime = summary.startedAt,
            startZoneOffset = zone,
            endTime = summary.endedAt,
            endZoneOffset = ZoneId.systemDefault().rules.getOffset(summary.endedAt),
            exerciseType = when (summary.category) {
                ExerciseCategory.CARDIO -> ExerciseSessionRecord.EXERCISE_TYPE_RUNNING
                ExerciseCategory.BODYWEIGHT -> ExerciseSessionRecord.EXERCISE_TYPE_CALISTHENICS
                ExerciseCategory.WEIGHT -> ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING
            },
            title = summary.title,
            metadata = Metadata.manualEntry(
                clientRecordId = summary.sessionId,
                clientRecordVersion = 1,
            ),
        )))
        return response.recordIdsList.first()
    }

    override fun openPermissionManager() {
        context.startActivity(Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private suspend fun <T : androidx.health.connect.client.records.Record> readAll(
        type: kotlin.reflect.KClass<T>,
        range: TimeRangeFilter,
        origins: Set<DataOrigin>,
    ): List<T> {
        val result = mutableListOf<T>()
        var token: String? = null
        do {
            val response = client.readRecords(ReadRecordsRequest(
                recordType = type,
                timeRangeFilter = range,
                dataOriginFilter = origins,
                pageToken = token,
            ))
            result += response.records
            token = response.pageToken
        } while (token != null)
        return result
    }

    private suspend fun readChanges(initialToken: String): HealthConnectSnapshot {
        val workouts = mutableListOf<ExternalWorkout>()
        val measurements = mutableListOf<ExternalHealthMeasurement>()
        val deleted = mutableSetOf<String>()
        var token = initialToken
        var hasMore: Boolean
        do {
            val response = client.getChanges(token)
            if (response.changesTokenExpired) return readHealthConnectData(null)
            response.changes.forEach { change ->
                when (change) {
                    is DeletionChange -> deleted += change.recordId
                    is UpsertionChange -> when (val record = change.record) {
                        is ExerciseSessionRecord -> workouts += toWorkout(record)
                        is WeightRecord -> measurements += toMeasurement(record)
                        is BodyFatRecord -> measurements += toMeasurement(record)
                    }
                }
            }
            token = response.nextChangesToken
            hasMore = response.hasMore
        } while (hasMore)
        return HealthConnectSnapshot(workouts, measurements, deleted, token)
    }

    private suspend fun HealthConnectSnapshot.withRecentNikeRuns(): HealthConnectSnapshot {
        val range = TimeRangeFilter.between(Instant.now().minus(Duration.ofDays(7)), Instant.now().plusSeconds(1))
        val recent = readAll(
            ExerciseSessionRecord::class,
            range,
            setOf(DataOrigin(NIKE_RUN_CLUB_PACKAGE)),
        ).map { toWorkout(it) }
        return copy(workouts = (workouts + recent).distinctBy { it.recordId })
    }

    private suspend fun toWorkout(record: ExerciseSessionRecord): ExternalWorkout {
        val packageName = record.metadata.dataOrigin.packageName
        val details = if (packageName == NIKE_RUN_CLUB_PACKAGE) readNikeDetails(record) else WorkoutDetails()
        return ExternalWorkout(
            recordId = record.metadata.id,
            title = record.title?.takeIf { it.isNotBlank() }
                ?: if (packageName == NIKE_RUN_CLUB_PACKAGE) "Nike Run Club 달리기" else exerciseTitle(record.exerciseType),
            category = exerciseCategory(record.exerciseType),
            start = record.startTime,
            end = record.endTime,
            distanceKm = details.distanceKm,
            caloriesKcal = details.caloriesKcal,
            source = if (packageName == NIKE_RUN_CLUB_PACKAGE) WorkoutSource.NIKE_RUN_CLUB else WorkoutSource.SAMSUNG_HEALTH,
            sourcePackage = packageName,
        )
    }

    private suspend fun readNikeDetails(record: ExerciseSessionRecord): WorkoutDetails {
        val result = client.aggregate(AggregateRequest(
            metrics = setOf(DistanceRecord.DISTANCE_TOTAL, TotalCaloriesBurnedRecord.ENERGY_TOTAL),
            timeRangeFilter = TimeRangeFilter.between(record.startTime, record.endTime),
            dataOriginFilter = setOf(DataOrigin(NIKE_RUN_CLUB_PACKAGE)),
        ))
        return WorkoutDetails(
            distanceKm = result[DistanceRecord.DISTANCE_TOTAL]?.inKilometers?.takeIf { it > 0.0 },
            caloriesKcal = result[TotalCaloriesBurnedRecord.ENERGY_TOTAL]?.inKilocalories?.takeIf { it > 0.0 },
        )
    }

    private fun toMeasurement(record: WeightRecord) = ExternalHealthMeasurement(
        recordId = record.metadata.id,
        type = HealthMetricType.WEIGHT_KG,
        value = record.weight.inKilograms,
        measuredAt = record.time,
        sourcePackage = record.metadata.dataOrigin.packageName,
    )

    private fun toMeasurement(record: BodyFatRecord) = ExternalHealthMeasurement(
        recordId = record.metadata.id,
        type = HealthMetricType.BODY_FAT_PERCENT,
        value = record.percentage.value,
        measuredAt = record.time,
        sourcePackage = record.metadata.dataOrigin.packageName,
    )

    private fun exerciseCategory(type: Int): ExerciseCategory = when (type) {
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,
        ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> ExerciseCategory.CARDIO
        ExerciseSessionRecord.EXERCISE_TYPE_CALISTHENICS -> ExerciseCategory.BODYWEIGHT
        else -> ExerciseCategory.WEIGHT
    }

    private fun exerciseTitle(type: Int): String = when (type) {
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING -> "달리기"
        ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> "걷기"
        ExerciseSessionRecord.EXERCISE_TYPE_CALISTHENICS -> "맨몸운동"
        else -> "근력 운동"
    }

    private data class WorkoutDetails(
        val distanceKm: Double? = null,
        val caloriesKcal: Double? = null,
    )
}
