package com.hanshin.healthtask.health

import android.content.Context
import android.content.Intent
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.changes.DeletionChange
import androidx.health.connect.client.changes.UpsertionChange
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.request.ChangesTokenRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.hanshin.healthtask.domain.ExerciseCategory
import com.hanshin.healthtask.domain.HealthMetricType
import com.hanshin.healthtask.domain.SAMSUNG_HEALTH_PACKAGE
import com.hanshin.healthtask.domain.SamsungHealthMeasurement
import com.hanshin.healthtask.domain.SamsungWorkout
import com.hanshin.healthtask.domain.WorkoutSummary
import java.time.Instant
import java.time.ZoneId

class AndroidHealthConnectGateway(private val context: Context) : HealthConnectGateway {
    private val client by lazy { HealthConnectClient.getOrCreate(context) }

    override val requiredPermissions: Set<String> = setOf(
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
        HealthPermission.getWritePermission(ExerciseSessionRecord::class),
        HealthPermission.getReadPermission(WeightRecord::class),
        HealthPermission.getReadPermission(BodyFatRecord::class),
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

    override suspend fun readSamsungData(changesToken: String?): HealthConnectSnapshot {
        check(status() == HealthConnectStatus.CONNECTED) { "Health Connect 권한이 필요합니다." }
        val origin = setOf(DataOrigin(SAMSUNG_HEALTH_PACKAGE))
        if (changesToken != null) return readChanges(changesToken)
        val nextToken = client.getChangesToken(ChangesTokenRequest(
            recordTypes = setOf(ExerciseSessionRecord::class, WeightRecord::class, BodyFatRecord::class),
            dataOriginFilters = origin,
        ))
        val range = TimeRangeFilter.between(Instant.EPOCH, Instant.now().plusSeconds(1))
        val workouts = readAll(ExerciseSessionRecord::class, range, origin).map(::toWorkout)
        val weights = readAll(WeightRecord::class, range, origin).map(::toMeasurement)
        val bodyFat = readAll(BodyFatRecord::class, range, origin).map(::toMeasurement)
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
        val workouts = mutableListOf<SamsungWorkout>()
        val measurements = mutableListOf<SamsungHealthMeasurement>()
        val deleted = mutableSetOf<String>()
        var token = initialToken
        var hasMore: Boolean
        do {
            val response = client.getChanges(token)
            if (response.changesTokenExpired) return readSamsungData(null)
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

    private fun toWorkout(record: ExerciseSessionRecord) = SamsungWorkout(
        recordId = record.metadata.id,
        title = record.title?.takeIf { it.isNotBlank() } ?: exerciseTitle(record.exerciseType),
        category = exerciseCategory(record.exerciseType),
        start = record.startTime,
        end = record.endTime,
    )

    private fun toMeasurement(record: WeightRecord) = SamsungHealthMeasurement(
        recordId = record.metadata.id,
        type = HealthMetricType.WEIGHT_KG,
        value = record.weight.inKilograms,
        measuredAt = record.time,
    )

    private fun toMeasurement(record: BodyFatRecord) = SamsungHealthMeasurement(
        recordId = record.metadata.id,
        type = HealthMetricType.BODY_FAT_PERCENT,
        value = record.percentage.value,
        measuredAt = record.time,
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
}
