package com.hanshin.healthtask.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface HealthTaskDao {
    @Query("SELECT * FROM profiles WHERE id = 'local-profile' LIMIT 1")
    fun observeProfile(): Flow<ProfileEntity?>

    @Query("SELECT * FROM profiles WHERE id = 'local-profile' LIMIT 1")
    suspend fun getProfile(): ProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProfile(profile: ProfileEntity)

    @Query("SELECT * FROM exercises ORDER BY name")
    fun observeExercises(): Flow<List<ExerciseEntity>>

    @Query("SELECT * FROM exercises ORDER BY name")
    suspend fun getExercises(): List<ExerciseEntity>

    @Query("SELECT * FROM exercises WHERE id = :id LIMIT 1")
    suspend fun getExercise(id: String): ExerciseEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertExercises(exercises: List<ExerciseEntity>)

    @Transaction
    @Query("SELECT * FROM routines ORDER BY createdAt, id")
    fun observeRoutines(): Flow<List<RoutineWithItems>>

    @Transaction
    @Query("SELECT * FROM routines ORDER BY createdAt, id")
    suspend fun getRoutines(): List<RoutineWithItems>

    @Transaction
    @Query("SELECT * FROM routines WHERE id = :id LIMIT 1")
    suspend fun getRoutine(id: String): RoutineWithItems?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRoutine(routine: RoutineEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRoutineItems(items: List<RoutineItemEntity>)

    @Query("DELETE FROM routine_items WHERE routineId = :routineId")
    suspend fun deleteRoutineItems(routineId: String)

    @Query("DELETE FROM routines WHERE id = :routineId")
    suspend fun deleteRoutine(routineId: String)

    @Transaction
    @Query("SELECT * FROM workout_sessions ORDER BY sessionDate DESC, startedAt DESC")
    fun observeSessions(): Flow<List<WorkoutSessionWithItems>>

    @Transaction
    @Query("SELECT * FROM workout_sessions ORDER BY sessionDate DESC, startedAt DESC")
    suspend fun getSessions(): List<WorkoutSessionWithItems>

    @Transaction
    @Query("SELECT * FROM workout_sessions WHERE id = :id LIMIT 1")
    suspend fun getSession(id: String): WorkoutSessionWithItems?

    @Query("SELECT * FROM workout_sessions WHERE healthConnectRecordId = :recordId LIMIT 1")
    suspend fun getSessionByHealthRecord(recordId: String): WorkoutSessionEntity?

    @Query("SELECT * FROM workout_sessions WHERE source = 'LOCAL' AND syncStatus IN ('PENDING', 'ERROR') AND status != 'ACTIVE'")
    suspend fun getPendingSessions(): List<WorkoutSessionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSession(session: WorkoutSessionEntity)

    @Update
    suspend fun updateSession(session: WorkoutSessionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertWorkoutItems(items: List<WorkoutItemEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSetRecords(sets: List<SetRecordEntity>)

    @Query("DELETE FROM set_records WHERE workoutItemId IN (SELECT id FROM workout_items WHERE sessionId = :sessionId)")
    suspend fun deleteSetsForSession(sessionId: String)

    @Query("DELETE FROM workout_items WHERE sessionId = :sessionId")
    suspend fun deleteItemsForSession(sessionId: String)

    @Query("DELETE FROM workout_sessions WHERE id = :sessionId")
    suspend fun deleteSession(sessionId: String)

    @Query("DELETE FROM health_measurements WHERE externalRecordId = :recordId")
    suspend fun deleteHealthMeasurementsByExternalRecord(recordId: String)

    @Query("SELECT * FROM health_measurements ORDER BY recordDate DESC, measuredAt DESC")
    fun observeHealthMeasurements(): Flow<List<HealthMeasurementEntity>>

    @Query("SELECT * FROM health_measurements ORDER BY recordDate DESC, measuredAt DESC")
    suspend fun getHealthMeasurements(): List<HealthMeasurementEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertHealthMeasurements(values: List<HealthMeasurementEntity>)

    @Query("DELETE FROM health_measurements WHERE id = :id")
    suspend fun deleteHealthMeasurement(id: String)

    @Query("DELETE FROM health_measurements WHERE source = 'SAMSUNG_HEALTH'")
    suspend fun clearSamsungHealthMeasurements()

    @Query("DELETE FROM workout_sessions WHERE source = 'SAMSUNG_HEALTH'")
    suspend fun clearSamsungSessions()

    @Query("SELECT * FROM samsung_workout_links")
    suspend fun getWorkoutLinks(): List<SamsungWorkoutLinkEntity>

    @Query("SELECT * FROM samsung_workout_links")
    fun observeWorkoutLinks(): Flow<List<SamsungWorkoutLinkEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertWorkoutLink(link: SamsungWorkoutLinkEntity)

    @Query("DELETE FROM samsung_workout_links WHERE samsungSessionId = :samsungSessionId")
    suspend fun deleteWorkoutLinkForSamsungSession(samsungSessionId: String)

    @Query("SELECT * FROM sync_state WHERE `key` = :key LIMIT 1")
    suspend fun getSyncState(key: String): SyncStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSyncState(value: SyncStateEntity)

    @Query("DELETE FROM profiles") suspend fun clearProfiles()
    @Query("DELETE FROM exercises") suspend fun clearExercises()
    @Query("DELETE FROM routines") suspend fun clearRoutines()
    @Query("DELETE FROM routine_items") suspend fun clearRoutineItems()
    @Query("DELETE FROM workout_sessions") suspend fun clearSessions()
    @Query("DELETE FROM workout_items") suspend fun clearWorkoutItems()
    @Query("DELETE FROM set_records") suspend fun clearSetRecords()
    @Query("DELETE FROM health_measurements") suspend fun clearHealthMeasurements()
    @Query("DELETE FROM samsung_workout_links") suspend fun clearWorkoutLinks()
    @Query("DELETE FROM sync_state") suspend fun clearSyncState()
}
