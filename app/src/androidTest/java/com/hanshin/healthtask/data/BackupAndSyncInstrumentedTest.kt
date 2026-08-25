package com.hanshin.healthtask.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hanshin.healthtask.data.db.HealthTaskDatabase
import com.hanshin.healthtask.data.db.ExerciseEntity
import com.hanshin.healthtask.data.db.WorkoutItemEntity
import com.hanshin.healthtask.data.db.WorkoutSessionEntity
import com.hanshin.healthtask.domain.ExerciseCategory
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
import com.hanshin.healthtask.shared.TABATA_EXERCISE_ID
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

    @Test fun tabataSeedIsAddedForExistingUsersAndAlwaysSavedAsFinisher() = runBlocking {
        val repository = HealthTaskRepository(database)
        database.dao().upsertExercises(listOf(ExerciseEntity(
            id = "custom-existing",
            name = "기존 운동",
            category = ExerciseCategory.WEIGHT,
            recordMode = RecordMode.SETS,
            isCustom = true,
        )))

        repository.initialize()
        assertNotNull(database.dao().getExercises().firstOrNull { it.id == TABATA_EXERCISE_ID })
        assertNotNull(database.dao().getExercises().firstOrNull { it.id == "custom-existing" })

        repository.saveRoutine(
            routineId = null,
            name = "타바타 마무리",
            exerciseIds = listOf(TABATA_EXERCISE_ID, "squat"),
        )
        val routine = database.dao().getRoutines().single()
        assertEquals(
            listOf("squat", TABATA_EXERCISE_ID),
            routine.items.sortedBy { it.orderIndex }.map { it.exerciseId },
        )

        val sessionId = repository.startSession(routine.routine.id)
        val tabata = database.dao().getSession(sessionId)!!.items.single {
            it.item.exerciseId == TABATA_EXERCISE_ID
        }
        assertEquals(null, tabata.item.durationMin)
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
            title = "웨이트",
            sessionDate = "2026-08-21",
            status = WorkoutStatus.COMPLETED,
            source = WorkoutSource.LOCAL,
            startedAt = now - 30 * 60_000,
            endedAt = now,
            syncStatus = SyncStatus.PENDING,
        ))
        dao.upsertWorkoutItems(listOf(WorkoutItemEntity(
            id = "item", sessionId = "local-session", exerciseId = "squat", exerciseName = "스쿼트",
            orderIndex = 1, category = ExerciseCategory.WEIGHT, recordMode = RecordMode.SETS,
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

    @Test fun onboardingCreatesPlanAndPlannedSessionsKeepTheirSlot() = runBlocking {
        val repository = HealthTaskRepository(database)
        repository.initialize()
        repository.finishOnboarding(workoutsPerWeek = 4, installTemplates = true)
        val plan = database.dao().getTrainingPlans().single { it.plan.isActive }

        assertEquals(4, plan.slots.size)
        assertEquals(
            listOf(
                PlannedWorkoutType.STRENGTH,
                PlannedWorkoutType.EASY_RUN,
                PlannedWorkoutType.STRENGTH,
                PlannedWorkoutType.QUALITY_RUN,
            ),
            plan.slots.sortedBy { it.orderIndex }.map { it.workoutType },
        )

        val strength = plan.slots.first { it.workoutType == PlannedWorkoutType.STRENGTH }
        val strengthSessionId = repository.startSession(strength.routineId!!, strength.id)
        assertEquals(strength.id, database.dao().getSession(strengthSessionId)!!.session.planSlotId)

        val runSlot = plan.slots.first { it.workoutType == PlannedWorkoutType.EASY_RUN }
        val startedAt = System.currentTimeMillis() - 30 * 60_000
        val run = repository.savePhoneRun(
            startedAt = startedAt,
            endedAt = System.currentTimeMillis(),
            elapsedMillis = 30 * 60_000,
            distanceMeters = 5_000.0,
            routePolyline = null,
            lapData = null,
            planSlotId = runSlot.id,
        )
        assertEquals(runSlot.id, run.planSlotId)
        assertEquals("이지런", run.title)

        val backup = BackupCodec(database).export()
        assertEquals(true, backup.contains("\"schemaVersion\": 3"))
        assertEquals(true, backup.contains("\"trainingPlans\""))
        assertEquals(true, backup.contains("\"planSlots\""))
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
