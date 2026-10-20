package com.hanshin.healthtask.data

import com.hanshin.healthtask.BuildConfig
import com.hanshin.healthtask.data.db.*
import com.hanshin.healthtask.domain.*
import com.hanshin.healthtask.running.RunningLapCodec
import com.hanshin.healthtask.running.RunningRouteCodec
import com.hanshin.healthtask.shared.RunTelemetry
import com.hanshin.healthtask.shared.RunSensorSummary
import com.hanshin.healthtask.shared.summarize
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/** Additive, read-only analysis view. The v5 root remains the authoritative restore payload. */
data class AnalysisExport(
    val schemaVersion: Int = 3,
    val metadata: AnalysisMetadata,
    val fieldGuide: Map<String, String>,
    val sourceCodes: Map<String, String>,
    val unavailableMeasurements: List<String>,
    val notes: List<String>,
    val summary: RunningAnalysisSummary,
    val dailySummaries: List<RunningAnalysisPeriod>,
    val weeklySummaries: List<RunningAnalysisPeriod>,
    val monthlySummaries: List<RunningAnalysisPeriod>,
    val runningSessions: List<AnalysisRun>,
    val bodyMeasurements: List<AnalysisBodyMeasurement>,
    val workoutLinks: List<SamsungWorkoutLinkEntity>,
    val cachedExternalWorkouts: List<WorkoutSessionWithItems>,
    val inBodyRecords: List<AnalysisInBodyRecord> = emptyList(),
)

data class AnalysisInBodyRecord(
    val id: String,
    val measuredLocal: String,
    val measuredAtUtc: String,
    val assumedTimeZoneId: String,
    val device: String,
    val sourceFileName: String,
    val rawFields: Map<String, String>,
)

data class AnalysisMetadata(
    val exportedAtUtc: String,
    val exportTimeZoneId: String,
    val applicationId: String = BuildConfig.APPLICATION_ID,
    val appVersionName: String = BuildConfig.VERSION_NAME,
    val appVersionCode: Int = BuildConfig.VERSION_CODE,
    val storedSessionCount: Int,
    val storedBodyMeasurementCount: Int,
    val cachedExternalSessionCount: Int,
    val exportScope: String = "All records currently stored in this phone database; no date limit or network fetch",
)

data class AnalysisRun(
    val sessionId: String,
    val record: WorkoutSessionWithItems,
    val startedAtUtc: String,
    val endedAtUtc: String?,
    val startedAtInExportTimeZone: String,
    val endedAtInExportTimeZone: String?,
    val externalCacheOnly: Boolean,
    val includedInSummary: Boolean,
    val summaryExclusionReason: String?,
    val duplicateOfSessionId: String?,
    val calculations: RunAnalysisCalculations,
    val routePoints: List<AnalysisRoutePoint>,
    val laps: List<AnalysisLap>,
    val dataQuality: RunExportQuality,
    val sensorTelemetry: RunTelemetry? = null,
    val sensorSummary: RunSensorSummary? = null,
)

data class RunAnalysisCalculations(
    val distanceMeters: Double?,
    val distanceBasis: String?,
    val activeDurationMillis: Long?,
    val wallClockDurationMillis: Long?,
    val nonActiveDurationMillis: Long?,
    val paceDurationBasis: String?,
    val averagePaceSecondsPerKm: Double?,
    val averageSpeedKmPerHour: Double?,
)

data class AnalysisRoutePoint(
    val pointIndex: Int,
    val latitude: Double,
    val longitude: Double,
    val activeElapsedMillis: Long,
)

data class AnalysisLap(
    val sourceEntryIndex: Int,
    val lapIndex: Int,
    val distanceMeters: Double,
    val durationMillis: Long,
    val startActiveElapsedMillis: Long?,
    val endActiveElapsedMillis: Long,
    val averagePaceSecondsPerKm: Double?,
    val isFullKilometer: Boolean,
)

data class RunExportQuality(
    val encodedRoutePointCount: Int,
    val decodedRoutePointCount: Int,
    val rejectedRoutePointCount: Int,
    val routeTimesNondecreasing: Boolean,
    val encodedLapCount: Int,
    val decodedLapCount: Int,
    val rejectedLapCount: Int,
    val issues: List<String>,
)

data class AnalysisBodyMeasurement(
    val record: HealthMeasurementEntity,
    val metricLabel: String,
    val unit: String,
    val measuredAtUtc: String,
    val measuredAtInExportTimeZone: String,
    val externalCacheOnly: Boolean,
    val sameDateAndMetricRecordCount: Int,
)

data class RunningAnalysisSummary(
    val sessionCount: Int,
    val distanceKnownCount: Int,
    val totalKnownDistanceMeters: Double?,
    val activeDurationKnownCount: Int,
    val totalKnownActiveDurationMillis: Long?,
    val wallClockDurationKnownCount: Int,
    val totalKnownWallClockDurationMillis: Long?,
    val caloriesKnownCount: Int,
    val totalKnownCaloriesKcal: Double?,
    val activePaceSessionCount: Int,
    val distanceWeightedActivePaceSecondsPerKm: Double?,
)

data class RunningAnalysisPeriod(val period: String, val sessionIds: List<String>, val summary: RunningAnalysisSummary)

internal object AnalysisExportBuilder {
    fun build(
        sessions: List<WorkoutSessionWithItems>,
        measurements: List<HealthMeasurementEntity>,
        links: List<SamsungWorkoutLinkEntity>,
        exportedAt: String,
        zone: ZoneId = ZoneId.systemDefault(),
        telemetryChunks: List<RunTelemetryChunkEntity> = emptyList(),
        inBodyRecords: List<InBodyRecordEntity> = emptyList(),
    ): AnalysisExport {
        val sensorChunks = telemetryChunks.groupBy { it.sessionId }
        val visibleIds = visibleRunningSessions(sessions, links).mapTo(hashSetOf()) { it.session.id }
        val duplicateLinks = links.filter { it.localSessionId in visibleIds }.associateBy { it.samsungSessionId }
        val runs = sessions.filter { it.isRunningRecord() }
            .sortedWith(compareBy<WorkoutSessionWithItems> { it.session.startedAt }.thenBy { it.session.id })
            .map { run ->
                val duplicate = duplicateLinks[run.session.id]?.takeIf { run.session.source.isExternal() }?.localSessionId
                run(run, zone, run.session.id in visibleIds, duplicate, sensorChunks[run.session.id].orEmpty())
            }
        val included = runs.filter { it.includedInSummary }
        val bodyGroups = measurements.groupingBy { it.recordDate to it.type }.eachCount()
        return AnalysisExport(
            metadata = AnalysisMetadata(exportedAt, zone.id, storedSessionCount = sessions.size,
                storedBodyMeasurementCount = measurements.size,
                cachedExternalSessionCount = sessions.count { it.session.source.isExternal() }),
            fieldGuide = linkedMapOf(
                "record" to "Unmodified stored record, including IDs, source/package, memo, sync status/error, creation/update times and original routePolyline/lapData.",
                "timestamps" to "Raw timestamps are Unix epoch milliseconds. *Utc are ISO 8601 UTC. *InExportTimeZone uses exportTimeZoneId, NOT the original workout time zone (not stored).",
                "recordDate/sessionDate" to "Stored calendar date. Body measurement date may be manually entered; measuredAt is not necessarily the actual measurement time.",
                "routePoints" to "All valid saved GPS points in original order, no simplification. latitude/longitude: WGS84 degrees. pointIndex: 1-based entry in original string, including rejected-entry gaps.",
                "activeElapsedMillis" to "Milliseconds on the recording device's active clock, excluding pauses. NOT a UTC timestamp: adding it to startedAt cannot recover actual GPS wall time. Phone/watch clock origins may differ slightly.",
                "laps" to "Saved lap data only. distanceMeters: m; durationMillis/endActiveElapsedMillis: ms; averagePaceSecondsPerKm: s/km. Partial last lap retained. Watch sessions may have no saved laps.",
                "calculations" to "Derived values, separate from raw record. distanceMeters uses session.distanceKm or the sum of workout-item distances only if every item has a valid distance; basis identifies fallback. Pace/speed use saved active duration, otherwise wall-clock interval with explicit basis.",
                "nonActiveDurationMillis" to "Wall-clock duration minus saved active duration when consistent. May include pauses/waiting/clock differences; not measured pause events.",
                "averageHeartRateBpm/caloriesKcal" to "Saved session average heart rate in beats/minute and energy in kcal. Not instantaneous samples, maxima, zones or estimates generated by this export.",
                "summary" to "Completed/partial running records only; excludes linked external duplicates like the app. Known-value totals have coverage counts; null means no known values, never a fabricated zero. Active pace = sum(active duration seconds) / sum(distance km), using only sessions with both positive values.",
                "dailySummaries/weeklySummaries/monthlySummaries" to "Grouped by stored sessionDate. Weekly period is Monday's ISO date; monthly period is YYYY-MM. Invalid dates remain in records/overall summary but not date groups.",
                "bodyMeasurements" to "Every stored measurement, including multiple values/sources on one day and archived body water. No averaging, overwriting, missing-day interpolation or unit conversion.",
                "inBodyRecords" to "CSV measurement rows with original header/value strings, missing markers and unknown fields. measuredLocal is the source wall-clock time; assumedTimeZoneId is chosen during import because CSV has no offset. device is the measurement equipment, not a personal identifier. bodyMeasurements.externalRecordId links each numeric value to this record's id. Balance codes are raw codes, not interpreted classifications; ratio values are not percentages.",
                "externalCacheOnly/cachedExternalWorkouts" to "Read-only analysis snapshot of external records already synchronized to this phone, not a complete external-account history. Ignored during backup restore; sync from original app again.",
                "null" to "Unknown/unavailable, not zero. Empty route/lap arrays mean no decodable saved samples, not no movement.",
                "sensorTelemetry" to "New watch runs only. Every delivered sample/location/event is retained with original timestamps; no resampling. metadata lists requested/supported/unsupported metrics and watch model (no device identifier). Legacy and phone-only runs may be null.",
                "sensorTelemetry.records" to "timestampEpochMillis: Unix ms anchored to watch wall clock; elapsedRealtimeMillis: monotonic ms since boot; activeElapsedMillis: Health Services active time when available (GPS uses route clock). Sample timestamps are NOT batch arrival times. Event timestamps are receipt/request times: exerciseStateObserved is not the exact internal transition instant. collectionWarnings lists incomplete-final-update and other recorder warnings.",
                "sensorTelemetry.units" to "heartRateBpm: beats/min; speedMetersPerSecond: m/s (pace s/km = 1000/speed for speed > 0); cadenceStepsPerMinute: steps/min; stepsTotal: steps; distanceMeters, altitudeMeters, elevationGainMeters, elevationLossMeters and GPS accuracies: m; caloriesKcal: kcal; bearingDegrees: degrees. Cumulative metrics are snapshots: use last value, DO NOT sum.",
                "sensorSummary" to "Sample mean is not time-weighted and does not replace the device's average HR. Min/max use received samples. heartRateBands are absolute BPM bins, NOT personalized zones: count only intervals <=5 s with known advancing active clocks, without filling gaps/pauses or extending the last sample. heartRateBandCoveredMillis shows coverage. Raw low-accuracy samples remain available.",
                "lapSummary" to "Watch Health Services lap aggregates in sensorTelemetry.records, grouped by lapIndex and intervalStartEpochMillis/timestampEpochMillis. Requested on observed kilometer crossings and finish; batching means intervals need not be exactly 1 km. Partial final lap is retained. Values are per-lap, not workout cumulative totals.",
            ),
            sourceCodes = linkedMapOf(
                "LOCAL" to "오늘러닝 휴대폰/워치 기록 또는 직접 입력·인바디 캡처·CSV; CSV 원본 출처는 inBodyRecords에 보존",
                "LEGACY_IMPORT" to "이전 앱에서 가져온 기록",
                "SAMSUNG_HEALTH" to "삼성 헬스 연동 캐시",
                "NIKE_RUN_CLUB" to "Nike Run Club 연동 캐시",
                "GOOGLE_FIT" to "이전 Google Fit/NRC 호환 코드; sourcePackage 확인",
            ),
            unavailableMeasurements = listOf("personalizedHeartRateZones", "weather", "VO2Max", "rawInBodyScreenshot",
                "ECG", "bloodPressure", "bloodOxygenSaturation"),
            notes = listOf(
                "분석용 analysis 영역은 복원하지 않습니다. schemaVersion 5 최상위 데이터·runTelemetryChunks·inBodyRecords가 복원 기준이며 이전 v1~v4 백업도 가져올 수 있습니다.",
                "상세 센서 기록은 기능 추가 후 워치에서 수집한 러닝만 포함합니다. 지원하지 않거나 수신되지 않은 항목은 추정하지 않습니다. 이전 기록의 누락된 시계열은 복원할 수 없습니다.",
                "러닝으로 분류되지 않는 과거 로컬 운동은 최상위 sessions/workoutItems/setRecords에서 원본 그대로 확인할 수 있습니다.",
                "현재 휴대폰 DB에 저장된 전체 기간만 포함합니다. 진행 중인 트래커 메모리와 아직 동기화되지 않은 워치·외부 앱 기록은 포함하지 않습니다.",
                "원본 문자열은 보존하고 해독 실패 항목 수를 표시합니다. GPS 점 간 직선거리로 공식 거리를 대체하거나 저장되지 않은 랩·심박 시계열을 생성하지 않습니다.",
                "정확한 위치·시간·건강 수치가 담긴 비암호화 파일입니다. 공유할 때 주의하세요.",
            ),
            summary = summarize(included),
            dailySummaries = periods(included) { it.toString() },
            weeklySummaries = periods(included) { it.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString() },
            monthlySummaries = periods(included) { it.toString().take(7) },
            runningSessions = runs,
            bodyMeasurements = measurements.sortedWith(compareBy<HealthMeasurementEntity> { it.recordDate }.thenBy { it.measuredAt }.thenBy { it.id }).map {
                val (label, unit) = bodyMetric(it.type)
                AnalysisBodyMeasurement(it, label, unit, utc(it.measuredAt), zoned(it.measuredAt, zone),
                    it.source.isExternal(), bodyGroups.getValue(it.recordDate to it.type))
            },
            workoutLinks = links.sortedBy { it.id },
            cachedExternalWorkouts = sessions.filter { it.session.source.isExternal() }.sortedBy { it.session.id },
            inBodyRecords = inBodyRecords.sortedBy { it.measuredLocal }.map {
                AnalysisInBodyRecord(it.id, it.measuredLocal, utc(it.measuredAt), it.timeZoneId, it.device,
                    it.sourceFileName, com.hanshin.healthtask.health.InBodyCsvParser.decodeRaw(it.rawFieldsJson))
            },
        )
    }

    private fun run(run: WorkoutSessionWithItems, zone: ZoneId, included: Boolean, duplicate: String?,
        sensorChunks: List<RunTelemetryChunkEntity>): AnalysisRun {
        val session = run.session
        val issues = mutableListOf<String>()
        val telemetry = runCatching { sensorChunks.decodeTelemetry() }
            .onFailure { issues += "INVALID_SENSOR_TELEMETRY" }.getOrNull()
        val routeTokens = tokens(session.routePolyline)
        val route = routeTokens.mapIndexedNotNull { index, token ->
            RunningRouteCodec.decode(token).singleOrNull()?.takeIf {
                it.latitude in -90.0..90.0 && it.longitude in -180.0..180.0 && it.elapsedMillis >= 0
            }?.let { AnalysisRoutePoint(index + 1, it.latitude, it.longitude, it.elapsedMillis) }
        }
        val lapTokens = tokens(session.lapData)
        val laps = lapTokens.mapIndexedNotNull { index, token ->
            RunningLapCodec.decode(token).singleOrNull()?.takeIf {
                it.index > 0 && it.distanceMeters.isFinite() && it.distanceMeters >= 0 && it.durationMillis >= 0 && it.totalElapsedMillis >= it.durationMillis
            }?.let {
                AnalysisLap(index + 1, it.index, it.distanceMeters, it.durationMillis,
                    it.totalElapsedMillis - it.durationMillis, it.totalElapsedMillis,
                    pace(it.distanceMeters, it.durationMillis), it.isComplete)
            }
        }
        val monotonic = route.zipWithNext().all { (a, b) -> b.activeElapsedMillis >= a.activeElapsedMillis }
        if (route.size != routeTokens.size) issues += "INVALID_ROUTE_ENTRIES_OMITTED_FROM_DECODED_VIEW"
        if (laps.size != lapTokens.size) issues += "INVALID_LAP_ENTRIES_OMITTED_FROM_DECODED_VIEW"
        if (!monotonic) issues += "NON_MONOTONIC_ROUTE_ACTIVE_TIME"
        if (runCatching { LocalDate.parse(session.sessionDate) }.isFailure) issues += "INVALID_SESSION_DATE"
        if (session.endedAt != null && session.endedAt < session.startedAt) issues += "END_BEFORE_START"
        val wall = session.endedAt?.minus(session.startedAt)?.takeIf { it >= 0 }
        val active = session.activeDurationMillis?.takeIf { it >= 0 }
        if (session.activeDurationMillis != null && active == null) issues += "INVALID_ACTIVE_DURATION"
        if (wall != null && active != null && active > wall) issues += "ACTIVE_DURATION_EXCEEDS_WALL_CLOCK"
        val itemDistances = run.items.mapNotNull { it.item.distanceKm?.takeIf { value -> value.isFinite() && value >= 0 } }
        val distanceKm = if (session.distanceKm != null) session.distanceKm.takeIf { it.isFinite() && it >= 0 }
            else itemDistances.takeIf { it.isNotEmpty() && it.size == run.items.size }?.sum()
        if (session.distanceKm != null && distanceKm == null) issues += "INVALID_SESSION_DISTANCE"
        if (session.distanceKm == null && itemDistances.isNotEmpty() && itemDistances.size != run.items.size) issues += "PARTIAL_WORKOUT_ITEM_DISTANCE"
        val distanceMeters = distanceKm?.times(1_000.0)
        val duration = active ?: wall
        val pace = pace(distanceMeters, duration)
        return AnalysisRun(
            session.id, run, utc(session.startedAt), session.endedAt?.let(::utc), zoned(session.startedAt, zone),
            session.endedAt?.let { zoned(it, zone) }, session.source.isExternal(), included,
            if (included) null else if (duplicate != null) "LINKED_EXTERNAL_DUPLICATE" else "NOT_COMPLETED_OR_PARTIAL",
            duplicate,
            RunAnalysisCalculations(distanceMeters,
                if (distanceMeters == null) null else if (session.distanceKm != null) "session.distanceKm" else "workoutItems.distanceKm",
                active, wall, if (active != null && wall != null && wall >= active) wall - active else null,
                if (duration == null) null else if (active != null) "activeDurationMillis" else "wallClockDurationMillis",
                pace, pace?.let { 3_600.0 / it }),
            route, laps, RunExportQuality(routeTokens.size, route.size, routeTokens.size - route.size, monotonic,
                lapTokens.size, laps.size, lapTokens.size - laps.size, issues),
            sensorTelemetry = telemetry,
            sensorSummary = telemetry?.summarize(),
        )
    }

    private fun summarize(runs: List<AnalysisRun>): RunningAnalysisSummary {
        val distances = runs.mapNotNull { it.calculations.distanceMeters }
        val active = runs.mapNotNull { it.calculations.activeDurationMillis }
        val wall = runs.mapNotNull { it.calculations.wallClockDurationMillis }
        val calories = runs.mapNotNull { it.record.session.caloriesKcal?.takeIf { value -> value.isFinite() && value >= 0 } }
        val paired = runs.filter { (it.calculations.distanceMeters ?: 0.0) > 0 && (it.calculations.activeDurationMillis ?: 0) > 0 }
        return RunningAnalysisSummary(runs.size, distances.size, distances.takeIf { it.isNotEmpty() }?.sum(),
            active.size, active.takeIf { it.isNotEmpty() }?.sum(), wall.size, wall.takeIf { it.isNotEmpty() }?.sum(),
            calories.size, calories.takeIf { it.isNotEmpty() }?.sum(), paired.size,
            paired.takeIf { it.isNotEmpty() }?.let { records ->
                records.sumOf { it.calculations.activeDurationMillis!!.toDouble() } / records.sumOf { it.calculations.distanceMeters!! }
            })
    }

    private fun periods(runs: List<AnalysisRun>, key: (LocalDate) -> String): List<RunningAnalysisPeriod> =
        runs.mapNotNull { run -> runCatching { key(LocalDate.parse(run.record.session.sessionDate)) }.getOrNull()?.let { it to run } }
            .groupBy({ it.first }, { it.second }).toSortedMap().map { (period, records) ->
                RunningAnalysisPeriod(period, records.map { it.sessionId }, summarize(records))
            }

    private fun tokens(value: String?): List<String> = value?.takeIf { it.isNotBlank() }?.split(';').orEmpty()
    private fun pace(distanceMeters: Double?, durationMillis: Long?): Double? =
        if (distanceMeters != null && distanceMeters > 0 && durationMillis != null && durationMillis > 0)
            (durationMillis.toDouble() / distanceMeters).takeIf { it.isFinite() } else null
    private fun utc(value: Long): String = Instant.ofEpochMilli(value).toString()
    private fun zoned(value: Long, zone: ZoneId): String = Instant.ofEpochMilli(value).atZone(zone).toOffsetDateTime().toString()
    private fun bodyMetric(type: HealthMetricType): Pair<String, String> = type.label to type.unit
}
