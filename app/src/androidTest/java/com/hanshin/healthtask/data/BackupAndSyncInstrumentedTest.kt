package com.hanshin.healthtask.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hanshin.healthtask.data.db.HealthTaskDatabase
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
    }

    @Test fun watchWorkoutImportIsIdempotentAndKeepsSensorSummary() = runBlocking {
        val repository = HealthTaskRepository(database)
        val workout = WearCompletedWorkout(
            sessionId = "wear-session-1",
            routineId = "routine-1",
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
        assertEquals(true, saved.items.single().sets.single().completed)
    }
}

private class FakeGateway(private var failuresBeforeSuccess: Int = 0) : HealthConnectGateway {
    var writeCalls = 0
    override val requiredPermissions = emptySet<String>()
    override suspend fun status() = HealthConnectStatus.CONNECTED
    override suspend fun readSamsungData(changesToken: String?) = HealthConnectSnapshot(emptyList(), emptyList(), nextChangesToken = "next-token")
    override suspend fun writeWorkout(summary: WorkoutSummary): String {
        writeCalls++
        if (failuresBeforeSuccess-- > 0) error("temporary")
        return "health-${summary.sessionId}"
    }
    override fun openPermissionManager() = Unit
}
