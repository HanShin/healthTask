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
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.HeartRateRecord
import com.google.gson.JsonObject
import com.google.gson.JsonArray
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Length
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
        HealthPermission.getWritePermission(DistanceRecord::class),
        HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
        HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY,
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
    )

    override suspend fun permissionSignature(): String = client.permissionController.getGrantedPermissions().sorted().joinToString("|")

    override suspend fun cloudAllowedMetrics(): Set<String> {
        if (HealthConnectClient.getSdkStatus(context) != HealthConnectClient.SDK_AVAILABLE) return emptySet()
        val granted = client.permissionController.getGrantedPermissions()
        return mapOf(
            HealthPermission.getReadPermission(SleepSessionRecord::class) to "sleep",
            HealthPermission.getReadPermission(StepsRecord::class) to "steps",
            HealthPermission.getReadPermission(HeartRateRecord::class) to "heart_rate",
        ).filterKeys { it in granted }.values.toSet()
    }

    override suspend fun status(): HealthConnectStatus {
        if (HealthConnectClient.getSdkStatus(context) != HealthConnectClient.SDK_AVAILABLE) {
            return HealthConnectStatus.UNAVAILABLE
        }
        val granted = client.permissionController.getGrantedPermissions()
        val optional = setOf(HealthPermission.getReadPermission(SleepSessionRecord::class), HealthPermission.getReadPermission(StepsRecord::class), HealthPermission.getReadPermission(HeartRateRecord::class))
        return if (granted.containsAll(requiredPermissions - optional)) HealthConnectStatus.CONNECTED
        else HealthConnectStatus.PERMISSIONS_REQUIRED
    }

    override suspend fun readHealthConnectData(changesToken: String?): HealthConnectSnapshot {
        check(status() == HealthConnectStatus.CONNECTED) { "Health Connect 권한이 필요합니다." }
        val origins = setOf(DataOrigin(SAMSUNG_HEALTH_PACKAGE), DataOrigin(NIKE_RUN_CLUB_PACKAGE))
        if (changesToken != null) return readChanges(changesToken).withRecentNikeRuns()
        val nextToken = client.getChangesToken(ChangesTokenRequest(
            recordTypes = setOf(ExerciseSessionRecord::class, WeightRecord::class, BodyFatRecord::class) + optionalRecordTypes(),
            dataOriginFilters = origins,
        ))
        val range = TimeRangeFilter.between(Instant.EPOCH, Instant.now().plusSeconds(1))
        val workouts = readAll(ExerciseSessionRecord::class, range, origins).filter { isRunningType(it.exerciseType) }.map { toWorkout(it) }
        val samsungOrigin = setOf(DataOrigin(SAMSUNG_HEALTH_PACKAGE))
        val weights = readAll(WeightRecord::class, range, samsungOrigin).map(::toMeasurement)
        val bodyFat = readAll(BodyFatRecord::class, range, samsungOrigin).map(::toMeasurement)
        val cloud = mutableListOf<JsonObject>()
        val types = optionalRecordTypes()
        if (SleepSessionRecord::class in types) cloud += readAll(SleepSessionRecord::class, range, samsungOrigin).map(::cloudRecord)
        if (StepsRecord::class in types) cloud += readAll(StepsRecord::class, range, samsungOrigin).map(::cloudRecord)
        if (HeartRateRecord::class in types) cloud += readAll(HeartRateRecord::class, range, samsungOrigin).map(::cloudRecord)
        val readMetrics = mapOf(SleepSessionRecord::class to "sleep", StepsRecord::class to "steps", HeartRateRecord::class to "heart_rate")
            .filterKeys { it in types }.values.toSet().intersect(cloudAllowedMetrics())
        return HealthConnectSnapshot(workouts, weights + bodyFat, nextChangesToken = nextToken, cloudRecords = cloud, fullSnapshot = true, reconciledCloudMetrics = readMetrics)
    }

    override suspend fun writeWorkout(summary: WorkoutSummary): String {
        check(status() == HealthConnectStatus.CONNECTED) { "Health Connect 권한이 필요합니다." }
        val zone = ZoneId.systemDefault().rules.getOffset(summary.startedAt)
        val exerciseSession = ExerciseSessionRecord(
            startTime = summary.startedAt,
            startZoneOffset = zone,
            endTime = summary.endedAt,
            endZoneOffset = ZoneId.systemDefault().rules.getOffset(summary.endedAt),
            exerciseType = ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,
            title = summary.title,
            metadata = Metadata.manualEntry(
                clientRecordId = summary.sessionId,
                clientRecordVersion = 1,
            ),
        )
        val records = mutableListOf<Record>(exerciseSession)
        summary.distanceKm?.takeIf { it > 0.0 }?.let { distanceKm ->
            records += DistanceRecord(
                startTime = summary.startedAt,
                startZoneOffset = zone,
                endTime = summary.endedAt,
                endZoneOffset = ZoneId.systemDefault().rules.getOffset(summary.endedAt),
                distance = Length.kilometers(distanceKm),
                metadata = Metadata.manualEntry(
                    clientRecordId = "${summary.sessionId}-distance",
                    clientRecordVersion = 1,
                ),
            )
        }
        val response = client.insertRecords(records)
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
            token = response.pageToken?.takeIf { it.isNotBlank() }
        } while (token != null)
        return result
    }

    private suspend fun readChanges(initialToken: String): HealthConnectSnapshot {
        val workouts = mutableListOf<ExternalWorkout>()
        val measurements = mutableListOf<ExternalHealthMeasurement>()
        val deleted = mutableSetOf<String>()
        val cloud = mutableListOf<JsonObject>()
        var token = initialToken
        var hasMore: Boolean
        do {
            val response = client.getChanges(token)
            if (response.changesTokenExpired) return readHealthConnectData(null)
            response.changes.forEach { change ->
                when (change) {
                    is DeletionChange -> deleted += change.recordId
                    is UpsertionChange -> when (val record = change.record) {
                        is ExerciseSessionRecord -> if (isRunningType(record.exerciseType)) workouts += toWorkout(record)
                        is WeightRecord -> measurements += toMeasurement(record)
                        is BodyFatRecord -> measurements += toMeasurement(record)
                        is SleepSessionRecord, is StepsRecord, is HeartRateRecord -> if (record.metadata.dataOrigin.packageName == SAMSUNG_HEALTH_PACKAGE) cloud += cloudRecord(record)
                    }
                }
            }
            token = response.nextChangesToken
            hasMore = response.hasMore
        } while (hasMore)
        return HealthConnectSnapshot(workouts, measurements, deleted, token, cloud)
    }

    private suspend fun HealthConnectSnapshot.withRecentNikeRuns(): HealthConnectSnapshot {
        val range = TimeRangeFilter.between(Instant.now().minus(Duration.ofDays(7)), Instant.now().plusSeconds(1))
        val recent = readAll(
            ExerciseSessionRecord::class,
            range,
            setOf(DataOrigin(NIKE_RUN_CLUB_PACKAGE)),
        ).filter { isRunningType(it.exerciseType) }.map { toWorkout(it) }
        return copy(workouts = (workouts + recent).distinctBy { it.recordId })
    }

    private suspend fun toWorkout(record: ExerciseSessionRecord): ExternalWorkout {
        val packageName = record.metadata.dataOrigin.packageName
        val details = readRunningDetails(record)
        return ExternalWorkout(
            recordId = record.metadata.id,
            title = record.title?.takeIf { it.isNotBlank() }
                ?: if (packageName == NIKE_RUN_CLUB_PACKAGE) "Nike Run Club 달리기" else exerciseTitle(record.exerciseType),
            category = ExerciseCategory.CARDIO,
            start = record.startTime,
            end = record.endTime,
            distanceKm = details.distanceKm,
            caloriesKcal = details.caloriesKcal,
            source = if (packageName == NIKE_RUN_CLUB_PACKAGE) WorkoutSource.NIKE_RUN_CLUB else WorkoutSource.SAMSUNG_HEALTH,
            sourcePackage = packageName,
            isRunning = true,
        )
    }

    private suspend fun readRunningDetails(record: ExerciseSessionRecord): WorkoutDetails {
        val result = client.aggregate(AggregateRequest(
            metrics = setOf(DistanceRecord.DISTANCE_TOTAL, TotalCaloriesBurnedRecord.ENERGY_TOTAL),
            timeRangeFilter = TimeRangeFilter.between(record.startTime, record.endTime),
            dataOriginFilter = setOf(record.metadata.dataOrigin),
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

    private suspend fun optionalRecordTypes(): Set<kotlin.reflect.KClass<out Record>> {
        val granted = client.permissionController.getGrantedPermissions()
        return setOf(SleepSessionRecord::class, StepsRecord::class, HeartRateRecord::class)
            .filterTo(mutableSetOf()) { HealthPermission.getReadPermission(it) in granted }
    }

    private fun cloudRecord(record: Record): JsonObject {
        val data = JsonObject().apply { addProperty("sourcePackage", record.metadata.dataOrigin.packageName) }
        val start: Instant
        val end: Instant
        when (record) {
            is SleepSessionRecord -> {
                start = record.startTime; end = record.endTime
                data.addProperty("metric", "sleep"); data.addProperty("unit", "seconds")
                data.addProperty("value", Duration.between(start, end).seconds)
                data.addProperty("durationBasis", "session_window")
                data.add("stages", JsonArray().apply { record.stages.forEach { stage -> add(JsonObject().apply {
                    addProperty("startTime", stage.startTime.toString()); addProperty("endTime", stage.endTime.toString()); addProperty("stage", stage.stage)
                }) } })
            }
            is StepsRecord -> {
                start = record.startTime; end = record.endTime
                data.addProperty("metric", "steps"); data.addProperty("unit", "steps"); data.addProperty("value", record.count)
            }
            is HeartRateRecord -> {
                start = record.startTime; end = record.endTime
                data.addProperty("metric", "heart_rate"); data.addProperty("unit", "bpm")
                data.add("samples", JsonArray().apply { record.samples.forEach { sample -> add(JsonObject().apply {
                    addProperty("time", sample.time.toString()); addProperty("beatsPerMinute", sample.beatsPerMinute)
                }) } })
            }
            else -> error("Unsupported cloud record")
        }
        data.addProperty("startTime", start.toString()); data.addProperty("endTime", end.toString())
        return JsonObject().apply {
            addProperty("source", "health_connect"); addProperty("externalId", record.metadata.id)
            addProperty("kind", "measurement"); addProperty("measuredAt", start.toString())
            addProperty("updatedAt", record.metadata.lastModifiedTime.toString())
            addProperty("timezone", ZoneId.systemDefault().id); add("data", data)
        }
    }

    private fun isRunningType(type: Int): Boolean =
        type == ExerciseSessionRecord.EXERCISE_TYPE_RUNNING ||
            type == ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL

    private fun exerciseTitle(type: Int): String =
        if (type == ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL) "트레드밀 러닝" else "러닝"

    private data class WorkoutDetails(
        val distanceKm: Double? = null,
        val caloriesKcal: Double? = null,
    )
}
