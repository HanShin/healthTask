package com.hanshin.healthtask.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hanshin.healthtask.data.db.HealthTaskDatabase
import com.hanshin.healthtask.data.db.ExerciseEntity
import com.hanshin.healthtask.data.db.SetRecordEntity
import com.hanshin.healthtask.data.db.WorkoutItemEntity
import com.hanshin.healthtask.data.db.WorkoutSessionEntity
import com.hanshin.healthtask.domain.ExerciseCategory
import com.hanshin.healthtask.domain.HealthMetricType
import com.hanshin.healthtask.domain.RecordMode
import com.hanshin.healthtask.domain.SyncStatus
import com.hanshin.healthtask.domain.WorkoutSource
import com.hanshin.healthtask.domain.WorkoutStatus
import com.hanshin.healthtask.domain.WorkoutSummary
import com.hanshin.healthtask.health.HealthConnectGateway
import com.hanshin.healthtask.health.HealthConnectSnapshot
import com.hanshin.healthtask.health.HealthConnectStatus
import com.hanshin.healthtask.health.HealthSyncManager
import com.hanshin.healthtask.domain.ExternalWorkout
import com.hanshin.healthtask.domain.NIKE_RUN_CLUB_PACKAGE
import com.hanshin.healthtask.domain.PlannedWorkoutType
import java.time.Instant
import com.hanshin.healthtask.shared.WearCompletedWorkout
import com.hanshin.healthtask.shared.WearRecordMode
import com.hanshin.healthtask.shared.WearRoutineExercise
import com.hanshin.healthtask.shared.WearRoutineSet
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupAndSyncInstrumentedTest {
    private lateinit var context: Context
    private lateinit var database: HealthTaskDatabase

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, HealthTaskDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After fun close() = database.close()

    @Test fun initializationSeedsOnlyRunningAndPreservesLegacyData() = runBlocking {
        val repository = HealthTaskRepository(database)
        val old = ExerciseEntity(id = "custom-existing", name = "기존 운동", category = ExerciseCategory.WEIGHT,
            recordMode = RecordMode.SETS, isCustom = true)
        database.dao().upsertExercises(listOf(old))
        repository.initialize()
        assertEquals(true, database.dao().getProfile()!!.onboardingDone)
        assertEquals(old, database.dao().getExercise(old.id))
        assertEquals(setOf("custom-existing", "easy-run", "tempo-run", "long-run"),
            database.dao().getExercises().map { it.id }.toSet())
        assertEquals(emptyList<Any>(), database.dao().getRoutines())
        assertEquals(emptyList<Any>(), database.dao().getTrainingPlans())
    }

    @Test fun initializationPreservesExistingProfileEvenIfOldOnboardingWasIncomplete() = runBlocking {
        val old = com.hanshin.healthtask.data.db.ProfileEntity(workoutsPerWeek = 6, onboardingDone = false)
        database.dao().upsertProfile(old)
        val repository = HealthTaskRepository(database)
        repository.initialize()
        repository.initialize()
        assertEquals(old, database.dao().getProfile())
    }

    @Test fun legacyV1ImportIsLosslessAndIdempotent() = runBlocking {
        val legacy = """
            {
              "exportedAt":"2026-08-21T00:00:00Z",
              "profile":{"id":"local-profile","weeklyGoalCount":3,"onboardingDone":true},
              "exercises":[{"id":"legacy-squat","name":"예전 스쿼트","kind":"strength","isCustom":true}],
              "routines":[{"id":"routine-1","name":"예전 루틴","source":"manual","isActive":true,"items":[{"id":"plan-1","exerciseId":"legacy-squat","kind":"strength","order":1,"sets":1,"targetReps":10}]}],
              "sessions":[{"id":"session-1","routineId":"routine-1","sessionDate":"2026-08-20","status":"completed","createdAt":"2026-08-20T01:00:00Z","items":[{"id":"item-1","exerciseId":"legacy-squat","kind":"strength","order":1,"sets":[{"order":1,"actualReps":10,"actualWeightKg":20,"completed":true}]}]}],
              "healthEntries":[{"id":"health-1","recordDate":"2026-08-20","weightKg":75.2,"skeletalMuscleKg":34.1,"bodyFatKg":12.3,"visceralFatLevel":4,"createdAt":"2026-08-20T00:00:00Z","updatedAt":"2026-08-20T00:00:00Z"}]
            }
        """.trimIndent()
        val codec = BackupCodec(database)
        val first = codec.import(legacy)
        val second = codec.import(legacy)

        assertEquals(1, first.routines)
        assertEquals(1, second.sessions)
        assertEquals(1, database.dao().getRoutines().count { it.routine.id == "routine-1" })
        assertEquals(1, database.dao().getSessions().count { it.session.id == "session-1" })
        assertEquals(4, database.dao().getHealthMeasurements().size)
        assertEquals(WorkoutSource.LEGACY_IMPORT, database.dao().getSession("session-1")!!.session.source)
    }

    @Test fun failedHealthConnectUploadRetriesWithoutDuplicates() = runBlocking {
        val dao = database.dao()
        val now = System.currentTimeMillis()
        dao.upsertSession(WorkoutSessionEntity(
            id = "local-session",
            title = "러닝",
            sessionDate = "2026-08-21",
            status = WorkoutStatus.COMPLETED,
            source = WorkoutSource.LOCAL,
            startedAt = now - 30 * 60_000,
            endedAt = now,
            syncStatus = SyncStatus.PENDING,
        ))
        dao.upsertWorkoutItems(listOf(WorkoutItemEntity(
            id = "item", sessionId = "local-session", exerciseId = "easy-run", exerciseName = "러닝",
            orderIndex = 1, category = ExerciseCategory.CARDIO, recordMode = RecordMode.CARDIO,
        )))
        val fake = FakeGateway(failuresBeforeSuccess = 1)
        val manager = HealthSyncManager(database, fake, AppPreferences(context))

        manager.sync(force = true)
        assertEquals(SyncStatus.ERROR, dao.getSession("local-session")!!.session.syncStatus)
        manager.sync(force = true)
        manager.sync(force = true)

        assertEquals(2, fake.writeCalls)
        val saved = dao.getSession("local-session")!!.session
        assertEquals(SyncStatus.SYNCED, saved.syncStatus)
        assertNotNull(saved.healthConnectRecordId)
    }

    @Test fun activeSessionSurvivesDatabaseReopen() = runBlocking {
        val databaseName = "restart-${System.nanoTime()}.db"
        val first = Room.databaseBuilder(context, HealthTaskDatabase::class.java, databaseName).build()
        first.dao().upsertSession(WorkoutSessionEntity(
            id = "active-session",
            title = "진행 중 루틴",
            sessionDate = "2026-08-21",
            status = WorkoutStatus.ACTIVE,
            source = WorkoutSource.LOCAL,
            startedAt = System.currentTimeMillis(),
        ))
        first.close()

        val reopened = Room.databaseBuilder(context, HealthTaskDatabase::class.java, databaseName).build()
        assertEquals(WorkoutStatus.ACTIVE, reopened.dao().getSession("active-session")!!.session.status)
        reopened.close()
        context.deleteDatabase(databaseName)
        Unit
    }

    @Test fun healthConnectNikeRunImportsAndDoesNotDuplicate() = runBlocking {
        val started = Instant.parse("2026-08-20T22:00:00Z")
        val run = ExternalWorkout(
            recordId = "health-connect-nrc-run-1",
            title = "Morning Run",
            category = ExerciseCategory.CARDIO,
            start = started,
            end = started.plusSeconds(30 * 60),
            distanceKm = 5.25,
            caloriesKcal = 320.0,
            source = WorkoutSource.NIKE_RUN_CLUB,
            sourcePackage = NIKE_RUN_CLUB_PACKAGE,
        )
        val manager = HealthSyncManager(
            database,
            FakeGateway(snapshot = HealthConnectSnapshot(listOf(run), emptyList())),
            AppPreferences(context),
        )

        manager.sync(force = true)
        manager.sync(force = true)

        val imported = database.dao().getSessions().filter { it.session.source == WorkoutSource.NIKE_RUN_CLUB }
        assertEquals(1, imported.size)
        assertEquals(5.25, imported.single().session.distanceKm!!, 0.001)
        assertEquals(5.25, imported.single().items.single().item.distanceKm!!, 0.001)
        assertEquals(320.0, imported.single().session.caloriesKcal!!, 0.001)
        assertEquals(NIKE_RUN_CLUB_PACKAGE, imported.single().session.sourcePackage)
    }

    @Test fun watchWorkoutImportIsIdempotentAndKeepsSensorSummary() = runBlocking {
        val repository = HealthTaskRepository(database)
        val workout = WearCompletedWorkout(
            sessionId = "wear-session-1",
            routineId = "routine-1",
            planSlotId = "plan-slot-1",
            title = "워치 루틴",
            startedAt = 1_755_700_000_000,
            endedAt = 1_755_701_800_000,
            exercises = listOf(WearRoutineExercise(
                id = "plan-1",
                exerciseId = "squat",
                name = "스쿼트",
                order = 1,
                recordMode = WearRecordMode.SETS,
                category = ExerciseCategory.WEIGHT.name,
                sets = listOf(WearRoutineSet(1, reps = 10, weightKg = 40.0, completed = true)),
            )),
            averageHeartRateBpm = 121.5,
            caloriesKcal = 185.0,
        )

        assertEquals(true, repository.importWearWorkout(workout))
        assertEquals(false, repository.importWearWorkout(workout))
        val saved = database.dao().getSession("wear-session-1")!!
        assertEquals(1, database.dao().getSessions().count { it.session.id == "wear-session-1" })
        assertEquals(121.5, saved.session.averageHeartRateBpm!!, 0.01)
        assertEquals(SyncStatus.PENDING, saved.session.syncStatus)
        assertEquals("plan-slot-1", saved.session.planSlotId)
        assertEquals(true, saved.items.single().sets.single().completed)
    }

    @Test fun watchWorkoutCompletesAndReplacesMatchingActivePhoneSession() = runBlocking {
        val repository = HealthTaskRepository(database)
        val dao = database.dao()
        dao.upsertSession(WorkoutSessionEntity(
            id = "shared-session",
            routineId = "routine-1",
            title = "진행 중 루틴",
            sessionDate = "2026-08-21",
            status = WorkoutStatus.ACTIVE,
            source = WorkoutSource.LOCAL,
            startedAt = 1_755_700_000_000,
        ))
        dao.upsertWorkoutItems(listOf(WorkoutItemEntity(
            id = "old-item",
            sessionId = "shared-session",
            exerciseId = "old-exercise",
            exerciseName = "이전 운동",
            orderIndex = 1,
            category = ExerciseCategory.WEIGHT,
            recordMode = RecordMode.SETS,
        )))
        dao.upsertSetRecords(listOf(SetRecordEntity(
            id = "old-set",
            workoutItemId = "old-item",
            orderIndex = 1,
        )))
        val workout = WearCompletedWorkout(
            sessionId = "shared-session",
            routineId = "routine-1",
            planSlotId = null,
            title = "완료 루틴",
            startedAt = 1_755_700_000_000,
            endedAt = 1_755_701_800_000,
            exercises = listOf(WearRoutineExercise(
                id = "watch-item",
                exerciseId = "squat",
                name = "스쿼트",
                order = 1,
                recordMode = WearRecordMode.SETS,
                category = ExerciseCategory.WEIGHT.name,
                sets = listOf(WearRoutineSet(1, reps = 12, weightKg = 45.0, completed = true)),
            )),
            averageHeartRateBpm = 128.0,
        )

        assertEquals(true, repository.importWearWorkout(workout))
        assertEquals(false, repository.importWearWorkout(workout))
        val saved = dao.getSession("shared-session")!!
        assertEquals(WorkoutStatus.COMPLETED, saved.session.status)
        assertEquals("완료 루틴", saved.session.title)
        assertEquals(128.0, saved.session.averageHeartRateBpm!!, 0.001)
        assertEquals(1, saved.items.size)
        assertEquals("squat", saved.items.single().item.exerciseId)
        assertEquals(12, saved.items.single().sets.single().actualReps)
    }

    @Test fun savingPhoneRunAgainReplacesChildrenUnderTheSameSessionId() = runBlocking {
        val repository = HealthTaskRepository(database)
        val startedAt = 1_755_700_000_000

        repository.savePhoneRun(
            sessionId = "phone-run-session",
            startedAt = startedAt,
            endedAt = startedAt + 30 * 60_000,
            elapsedMillis = 30 * 60_000,
            distanceMeters = 5_000.0,
            routePolyline = "first-route",
            lapData = null,
        )
        repository.savePhoneRun(
            sessionId = "phone-run-session",
            startedAt = startedAt,
            endedAt = startedAt + 31 * 60_000,
            elapsedMillis = 31 * 60_000,
            distanceMeters = 5_500.0,
            routePolyline = "replacement-route",
            lapData = null,
        )

        val saved = database.dao().getSession("phone-run-session")!!
        assertEquals(1, database.dao().getSessions().count { it.session.id == "phone-run-session" })
        assertEquals(1, saved.items.size)
        assertEquals(5.5, saved.session.distanceKm!!, 0.001)
        assertEquals("replacement-route", saved.session.routePolyline)
    }

    @Test fun watchMetricsMergeIntoCompletedPhoneRunWithoutReplacingPhoneRoute() = runBlocking {
        val repository = HealthTaskRepository(database)
        val startedAt = 1_755_700_000_000
        repository.savePhoneRun(
            sessionId = "run-shared-session",
            startedAt = startedAt,
            endedAt = startedAt + 30 * 60_000,
            elapsedMillis = 30 * 60_000,
            distanceMeters = 5_000.0,
            routePolyline = "phone-route",
            lapData = "phone-laps",
        )
        val watchWorkout = WearCompletedWorkout(
            sessionId = "run-shared-session",
            routineId = "free-run",
            title = "자유 러닝",
            startedAt = startedAt,
            endedAt = startedAt + 29 * 60_000,
            exercises = listOf(WearRoutineExercise(
                id = "watch-run",
                exerciseId = "easy-run",
                name = "자유 러닝",
                order = 1,
                recordMode = WearRecordMode.CARDIO,
                category = ExerciseCategory.CARDIO.name,
                durationMin = 29.0,
                distanceKm = 4.8,
            )),
            averageHeartRateBpm = 142.0,
            caloriesKcal = 321.0,
            distanceKm = 4.8,
            activeDurationMillis = 29 * 60_000L,
            routePolyline = "watch-route",
        )

        assertEquals(true, repository.importWearWorkout(watchWorkout))
        assertEquals(false, repository.importWearWorkout(watchWorkout))

        val saved = database.dao().getSession("run-shared-session")!!
        assertEquals(5.0, saved.session.distanceKm!!, 0.001)
        assertEquals("phone-route", saved.session.routePolyline)
        assertEquals("phone-laps", saved.session.lapData)
        assertEquals(142.0, saved.session.averageHeartRateBpm!!, 0.001)
        assertEquals(321.0, saved.session.caloriesKcal!!, 0.001)
        assertEquals(5.0, saved.items.single().item.distanceKm!!, 0.001)
    }

    @Test fun phoneRunSavePreservesWatchMetricsWhenWatchArrivesFirst() = runBlocking {
        val repository = HealthTaskRepository(database)
        val startedAt = 1_755_700_000_000
        val watchWorkout = WearCompletedWorkout(
            sessionId = "run-watch-first",
            routineId = "free-run",
            title = "자유 러닝",
            startedAt = startedAt,
            endedAt = startedAt + 20 * 60_000,
            exercises = listOf(WearRoutineExercise(
                id = "watch-run",
                exerciseId = "easy-run",
                name = "자유 러닝",
                order = 1,
                recordMode = WearRecordMode.CARDIO,
                category = ExerciseCategory.CARDIO.name,
                durationMin = 20.0,
                distanceKm = 3.0,
            )),
            averageHeartRateBpm = 135.0,
            caloriesKcal = 210.0,
            distanceKm = 3.0,
            routePolyline = "watch-route",
        )

        assertEquals(true, repository.importWearWorkout(watchWorkout))
        repository.savePhoneRun(
            sessionId = "run-watch-first",
            startedAt = startedAt,
            endedAt = startedAt + 21 * 60_000,
            elapsedMillis = 21 * 60_000,
            distanceMeters = 3_200.0,
            routePolyline = "phone-route",
            lapData = "phone-laps",
        )

        val saved = database.dao().getSession("run-watch-first")!!
        assertEquals(3.2, saved.session.distanceKm!!, 0.001)
        assertEquals("phone-route", saved.session.routePolyline)
        assertEquals(135.0, saved.session.averageHeartRateBpm!!, 0.001)
        assertEquals(210.0, saved.session.caloriesKcal!!, 0.001)
        assertEquals(false, repository.importWearWorkout(watchWorkout))
    }

    @Test fun watchCompletionReplacesAlreadyCompletedPhoneStrengthOnlyOnce() = runBlocking {
        val repository = HealthTaskRepository(database)
        val dao = database.dao()
        val startedAt = 1_755_700_000_000
        dao.upsertSession(WorkoutSessionEntity(
            id = "shared-finished-strength",
            routineId = "routine-1",
            title = "폰에서 완료한 루틴",
            sessionDate = "2026-08-21",
            status = WorkoutStatus.COMPLETED,
            source = WorkoutSource.LOCAL,
            startedAt = startedAt,
            endedAt = startedAt + 10 * 60_000,
        ))
        val watchWorkout = WearCompletedWorkout(
            sessionId = "shared-finished-strength",
            routineId = "routine-1",
            title = "워치에서 완료한 루틴",
            startedAt = startedAt,
            endedAt = startedAt + 12 * 60_000,
            exercises = listOf(WearRoutineExercise(
                id = "watch-item",
                exerciseId = "deadlift",
                name = "데드리프트",
                order = 1,
                recordMode = WearRecordMode.SETS,
                category = ExerciseCategory.WEIGHT.name,
                sets = listOf(WearRoutineSet(1, reps = 5, weightKg = 80.0, completed = true)),
            )),
            averageHeartRateBpm = 126.0,
        )

        assertEquals(true, repository.importWearWorkout(watchWorkout))
        assertEquals(false, repository.importWearWorkout(watchWorkout))
        val saved = dao.getSession("shared-finished-strength")!!
        assertEquals("워치에서 완료한 루틴", saved.session.title)
        assertEquals("deadlift", saved.items.single().item.exerciseId)
        assertEquals(126.0, saved.session.averageHeartRateBpm!!, 0.001)
    }

    @Test fun runningAndAllInBodyFieldsSurviveBackupRoundTrip() = runBlocking {
        val repository = HealthTaskRepository(database)
        repository.initialize()
        val start = System.currentTimeMillis() - 1_800_000
        repository.savePhoneRun(sessionId = "run-roundtrip", startedAt = start, endedAt = start + 1_800_000,
            elapsedMillis = 1_800_000, distanceMeters = 5_000.0,
            routePolyline = "37.5,127.0,0;37.501,127.001,1000", lapData = null)
        val values = mapOf(
            HealthMetricType.WEIGHT_KG to 70.0, HealthMetricType.BODY_FAT_PERCENT to 18.0,
            HealthMetricType.BODY_FAT_MASS_KG to 12.6, HealthMetricType.SKELETAL_MUSCLE_KG to 33.0,
            HealthMetricType.VISCERAL_FAT_LEVEL to 6.0, HealthMetricType.INBODY_SCORE to 82.0,
        )
        repository.saveInBodyScreenshotMetrics(java.time.LocalDate.now(), values)
        val backup = BackupCodec(database).export()
        val restored = Room.inMemoryDatabaseBuilder(context, HealthTaskDatabase::class.java).build()
        try {
            BackupCodec(restored).import(backup)
            val run = restored.dao().getSession("run-roundtrip")!!
            assertEquals(5.0, run.session.distanceKm!!, 0.001)
            assertEquals("37.5,127.0,0;37.501,127.001,1000", run.session.routePolyline)
            assertEquals(values, restored.dao().getHealthMeasurements().associate { it.type to it.value })
        } finally { restored.close() }
    }

    @Test fun bodyMeasurementsCanBeDatedEditedAndDeletedWithoutTouchingRuns() = runBlocking {
        val repository = HealthTaskRepository(database)
        val start = System.currentTimeMillis() - 1_800_000L
        repository.savePhoneRun(sessionId = "run-body-edit", startedAt = start, endedAt = start + 1_800_000L,
            elapsedMillis = 1_800_000L, distanceMeters = 5_000.0, routePolyline = "kept-route", lapData = null)
        val originalRun = database.dao().getSession("run-body-edit")
        val date = java.time.LocalDate.now().minusDays(2)
        repository.saveBodyMeasurement(date, HealthMetricType.SKELETAL_MUSCLE_KG, 32.0)
        val original = database.dao().getHealthMeasurements().single()
        repository.saveBodyMeasurement(date.plusDays(1), original.type, 32.5, original.id)
        val updated = database.dao().getHealthMeasurements().single()
        assertEquals(original.id, updated.id)
        assertEquals(date.plusDays(1).toString(), updated.recordDate)
        assertEquals(32.5, updated.value, 0.001)
        repository.saveBodyMeasurement(date, original.type, 31.0)
        assertEquals(2, database.dao().getHealthMeasurements().size)
        assertEquals(updated, database.dao().getHealthMeasurement(updated.id))
        repository.deleteBodyMeasurement(updated.id)
        assertEquals(31.0, database.dao().getHealthMeasurements().single().value, 0.001)
        assertEquals(originalRun, database.dao().getSession("run-body-edit"))
    }

    @Test fun healthSyncSkipsStrengthAndWalkingButKeepsBodyMeasurements() = runBlocking {
        val start = Instant.now().minusSeconds(1_800)
        val strength = ExternalWorkout("strength", "근력", ExerciseCategory.WEIGHT, start, start.plusSeconds(1_200),
            source = WorkoutSource.SAMSUNG_HEALTH, sourcePackage = "com.sec.android.app.shealth")
        val walking = strength.copy(recordId = "walking", title = "걷기", category = ExerciseCategory.CARDIO)
        val running = strength.copy(recordId = "running", title = "아침 운동", category = ExerciseCategory.CARDIO, isRunning = true)
        val body = com.hanshin.healthtask.domain.ExternalHealthMeasurement("body", HealthMetricType.WEIGHT_KG, 70.0, start)
        val fake = FakeGateway(snapshot = HealthConnectSnapshot(listOf(strength, walking, running), listOf(body)))
        val result = HealthSyncManager(database, fake, AppPreferences(context)).sync(force = true)
        assertEquals(1, result.downloadedWorkouts)
        assertEquals("아침 운동", database.dao().getSessions().single().session.title)
        assertEquals(70.0, database.dao().getHealthMeasurements().single().value, 0.001)
    }

    @Test fun pendingLegacyStrengthIsPreservedButNotUploadedAsRunning() = runBlocking {
        val dao = database.dao()
        dao.upsertSession(WorkoutSessionEntity(id = "legacy-strength", title = "근력", sessionDate = "2026-08-21",
            status = WorkoutStatus.COMPLETED, source = WorkoutSource.LOCAL, startedAt = 0, endedAt = 1_800_000))
        dao.upsertWorkoutItems(listOf(WorkoutItemEntity(id = "old-item", sessionId = "legacy-strength",
            exerciseId = "squat", exerciseName = "스쿼트", orderIndex = 1,
            category = ExerciseCategory.WEIGHT, recordMode = RecordMode.SETS)))
        val fake = FakeGateway()
        HealthSyncManager(database, fake, AppPreferences(context)).sync(force = true)
        assertEquals(0, fake.writeCalls)
        assertNotNull(dao.getSession("legacy-strength"))
    }

    @Test fun watchRunImportKeepsDistanceActiveTimeAndAveragePace() = runBlocking {
        val repository = HealthTaskRepository(database)
        val workout = WearCompletedWorkout(
            sessionId = "wear-run-1",
            routineId = "planned-run-slot-run",
            planSlotId = "slot-run",
            title = "템포런",
            startedAt = 1_755_700_000_000,
            endedAt = 1_755_702_000_000,
            exercises = listOf(WearRoutineExercise(
                id = "slot-run-exercise",
                exerciseId = "tempo-run",
                name = "템포런",
                order = 1,
                recordMode = WearRecordMode.CARDIO,
                category = ExerciseCategory.CARDIO.name,
                durationMin = 25.0,
                distanceKm = 5.0,
            )),
            averageHeartRateBpm = 155.0,
            distanceKm = 5.0,
            caloriesKcal = 320.0,
            activeDurationMillis = 1_500_000L,
            routePolyline = "37.566535,126.977969,0;37.567123,126.979321,5000",
        )

        assertEquals(true, repository.importWearWorkout(workout))
        val saved = database.dao().getSession("wear-run-1")!!

        assertEquals("slot-run", saved.session.planSlotId)
        assertEquals(5.0, saved.session.distanceKm!!, 0.001)
        assertEquals(1_500_000L, saved.session.activeDurationMillis)
        assertEquals(workout.routePolyline, saved.session.routePolyline)
        assertEquals(5.0, saved.items.single().item.avgPaceMinPerKm!!, 0.001)
        assertEquals(WorkoutStatus.COMPLETED, saved.session.status)
    }
}

private class FakeGateway(
    private var failuresBeforeSuccess: Int = 0,
    private val statusValue: HealthConnectStatus = HealthConnectStatus.CONNECTED,
    private val snapshot: HealthConnectSnapshot = HealthConnectSnapshot(emptyList(), emptyList(), nextChangesToken = "next-token"),
) : HealthConnectGateway {
    var writeCalls = 0
    override val requiredPermissions = emptySet<String>()
    override suspend fun status() = statusValue
    override suspend fun readHealthConnectData(changesToken: String?) = snapshot
    override suspend fun writeWorkout(summary: WorkoutSummary): String {
        writeCalls++
        if (failuresBeforeSuccess-- > 0) error("temporary")
        return "health-${summary.sessionId}"
    }
    override fun openPermissionManager() = Unit
}
