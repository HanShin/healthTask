package com.hanshin.healthtask.data

import com.hanshin.healthtask.data.db.*
import com.hanshin.healthtask.domain.*
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class AnalysisExportTest {
    private val start = Instant.parse("2026-09-23T23:00:00Z").toEpochMilli()
    private val zone = ZoneId.of("Asia/Seoul")

    @Test fun `exports decoded samples raw records and correctly labelled calculations`() {
        val raw = session("run-local").copy(
            distanceKm = 5.0, activeDurationMillis = 1_800_000,
            averageHeartRateBpm = 143.0, caloriesKcal = 310.0,
            routePolyline = "37.5,127.0,0;37.501,127.001,1000",
            lapData = "1,1000.0,360000,360000;2,250.0,90000,450000",
        )
        val result = build(listOf(wrapped(raw)))
        val run = result.runningSessions.single()
        assertEquals(raw, run.record.session)
        assertEquals("2026-09-23T23:00:00Z", run.startedAtUtc)
        assertEquals("2026-09-24T08:00+09:00", run.startedAtInExportTimeZone)
        assertEquals(5_000.0, run.calculations.distanceMeters!!, 0.0)
        assertEquals(300_000L, run.calculations.nonActiveDurationMillis)
        assertEquals(360.0, run.calculations.averagePaceSecondsPerKm!!, 0.0)
        assertEquals(10.0, run.calculations.averageSpeedKmPerHour!!, 0.0)
        assertEquals(1_000L, run.routePoints.last().activeElapsedMillis)
        assertEquals(360.0, run.laps.last().averagePaceSecondsPerKm!!, 0.0)
        assertFalse(run.laps.last().isFullKilometer)
        assertEquals(360_000L, run.laps.last().startActiveElapsedMillis)
        assertEquals("2026-09-21", result.weeklySummaries.single().period)
        assertEquals("2026-09", result.monthlySummaries.single().period)
    }

    @Test fun `external duplicates remain in export but not in aggregate totals`() {
        val local = wrapped(session("run-local").copy(distanceKm = 5.0, activeDurationMillis = 1_800_000))
        val external = wrapped(session("external").copy(source = WorkoutSource.SAMSUNG_HEALTH, distanceKm = 5.1))
        val link = SamsungWorkoutLinkEntity("link", local.session.id, external.session.id, 0.95)
        val result = build(listOf(local, external), links = listOf(link))
        assertEquals(2, result.runningSessions.size)
        assertEquals(1, result.summary.sessionCount)
        assertEquals(5_000.0, result.summary.totalKnownDistanceMeters!!, 0.0)
        val copy = result.runningSessions.first { it.externalCacheOnly }
        assertEquals("run-local", copy.duplicateOfSessionId)
        assertEquals("LINKED_EXTERNAL_DUPLICATE", copy.summaryExclusionReason)
        assertEquals(listOf(external), result.cachedExternalWorkouts)
        val afterLocalDeletion = build(listOf(external), links = listOf(link))
        assertEquals(1, afterLocalDeletion.summary.sessionCount)
        assertNull(afterLocalDeletion.runningSessions.single().duplicateOfSessionId)
    }

    @Test fun `unknown values stay null while a measured zero remains zero`() {
        val result = build(listOf(wrapped(session("missing").copy(endedAt = null))))
        val run = result.runningSessions.single()
        assertNull(run.calculations.distanceMeters)
        assertNull(run.calculations.averagePaceSecondsPerKm)
        assertNull(run.calculations.wallClockDurationMillis)
        assertNull(result.summary.totalKnownDistanceMeters)
        assertEquals(0, result.summary.distanceKnownCount)
        val zero = build(listOf(wrapped(session("zero").copy(distanceKm = 0.0, activeDurationMillis = 0))))
        assertEquals(0.0, zero.summary.totalKnownDistanceMeters!!, 0.0)
        assertEquals(1, zero.summary.distanceKnownCount)
        assertNull(zero.summary.distanceWeightedActivePaceSecondsPerKm)
    }

    @Test fun `wall time fallback is explicit and never called active pace in totals`() {
        val run = wrapped(session("external").copy(source = WorkoutSource.NIKE_RUN_CLUB, distanceKm = 5.0))
        val result = build(listOf(run))
        assertEquals("wallClockDurationMillis", result.runningSessions.single().calculations.paceDurationBasis)
        assertEquals(420.0, result.runningSessions.single().calculations.averagePaceSecondsPerKm!!, 0.0)
        assertNull(result.summary.distanceWeightedActivePaceSecondsPerKm)
    }

    @Test fun `average pace uses paired distances and times not arithmetic mean of paces`() {
        val runs = listOf(
            wrapped(session("one").copy(distanceKm = 1.0, activeDurationMillis = 600_000)),
            wrapped(session("nine").copy(distanceKm = 9.0, activeDurationMillis = 2_700_000)),
            wrapped(session("unknown-time").copy(distanceKm = 20.0)),
        )
        val result = build(runs)
        assertEquals(330.0, result.summary.distanceWeightedActivePaceSecondsPerKm!!, 0.0)
        assertEquals(2, result.summary.activePaceSessionCount)
    }

    @Test fun `malformed samples are flagged without altering the original strings`() {
        val route = "37.5,127.0,2000;bad;NaN,127.0,4;91.0,127.0,5;37.501,127.001,1000"
        val laps = "1,1000.0,300000,300000;2,NaN,1000,301000;broken;3,2.0,5,4"
        val result = build(listOf(wrapped(session("bad").copy(routePolyline = route, lapData = laps))))
        val run = result.runningSessions.single()
        assertEquals(route, run.record.session.routePolyline)
        assertEquals(laps, run.record.session.lapData)
        assertEquals(listOf(1, 5), run.routePoints.map { it.pointIndex })
        assertEquals(3, run.dataQuality.rejectedRoutePointCount)
        assertEquals(3, run.dataQuality.rejectedLapCount)
        assertFalse(run.dataQuality.routeTimesNondecreasing)
    }

    @Test fun `all body values including same day duplicates and archived water are retained`() {
        val records = HealthMetricType.entries.mapIndexed { index, type ->
            HealthMeasurementEntity("metric-$index", "2026-09-24", start, type, index + 1.5, WorkoutSource.LOCAL)
        } + HealthMeasurementEntity("external-weight", "2026-09-24", start, HealthMetricType.WEIGHT_KG, 70.1, WorkoutSource.SAMSUNG_HEALTH)
        val result = build(emptyList(), records)
        assertEquals(HealthMetricType.entries.size + 1, result.bodyMeasurements.size)
        assertEquals("L", result.bodyMeasurements.first { it.record.type == HealthMetricType.BODY_WATER_L }.unit)
        assertTrue(result.bodyMeasurements.filter { it.record.type == HealthMetricType.WEIGHT_KG }.all { it.sameDateAndMetricRecordCount == 2 })
        assertEquals(1, result.bodyMeasurements.count { it.externalCacheOnly })
    }

    @Test fun `large routes are not simplified or truncated`() {
        val route = (0 until 5_000).joinToString(";") { "37.5,127.0,${it * 1000L}" }
        val result = build(listOf(wrapped(session("large").copy(routePolyline = route))))
        assertEquals(5_000, result.runningSessions.single().routePoints.size)
        assertEquals(5_000, result.runningSessions.single().routePoints.last().pointIndex)
    }

    @Test fun `partial item distances cannot become a misleading whole run pace`() {
        val raw = session("run-items")
        val item = WorkoutItemEntity("one", raw.id, "easy-run", "러닝", 1, ExerciseCategory.CARDIO, RecordMode.CARDIO, distanceKm = 2.0)
        val result = build(listOf(WorkoutSessionWithItems(raw, listOf(
            WorkoutItemWithSets(item, emptyList()), WorkoutItemWithSets(item.copy(id = "two", distanceKm = null), emptyList())))))
        assertNull(result.runningSessions.single().calculations.distanceMeters)
        assertNull(result.runningSessions.single().calculations.averagePaceSecondsPerKm)
        assertTrue(result.runningSessions.single().dataQuality.issues.contains("PARTIAL_WORKOUT_ITEM_DISTANCE"))
    }

    @Test fun `unfinished and legacy strength records do not affect running totals`() {
        val active = wrapped(session("run-active").copy(status = WorkoutStatus.ACTIVE))
        val strength = WorkoutSessionWithItems(session("strength").copy(title = "스쿼트"), listOf(
            WorkoutItemWithSets(WorkoutItemEntity("item", "strength", "squat", "스쿼트", 1, ExerciseCategory.WEIGHT, RecordMode.SETS), emptyList())))
        val result = build(listOf(active, strength))
        assertEquals(1, result.runningSessions.size)
        assertFalse(result.runningSessions.single().includedInSummary)
        assertEquals(0, result.summary.sessionCount)
        assertEquals(2, result.metadata.storedSessionCount)
    }

    private fun session(id: String) = WorkoutSessionEntity(id = id, title = "러닝", sessionDate = "2026-09-24",
        status = WorkoutStatus.COMPLETED, source = WorkoutSource.LOCAL, startedAt = start, endedAt = start + 2_100_000)
    private fun wrapped(session: WorkoutSessionEntity) = WorkoutSessionWithItems(session, emptyList())
    private fun build(sessions: List<WorkoutSessionWithItems>, body: List<HealthMeasurementEntity> = emptyList(), links: List<SamsungWorkoutLinkEntity> = emptyList()) =
        AnalysisExportBuilder.build(sessions, body, links, "2026-09-24T00:00:00Z", zone)
}
