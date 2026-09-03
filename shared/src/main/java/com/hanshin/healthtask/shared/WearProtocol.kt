package com.hanshin.healthtask.shared

object WearPaths {
    const val TODAY_ROUTINE = "/today-routine"
    const val START_WORKOUT_PREFIX = "/start-workout/"
    const val COMPLETED_WORKOUT_PREFIX = "/completed-workout/"
    const val KEY_JSON = "json"
    const val KEY_UPDATED_AT = "updatedAt"
}

enum class WearRecordMode { SETS, CARDIO }
enum class WearSensorMode { RUNNING, STRENGTH }

const val DEFAULT_REST_TIMER_SECONDS = 90

data class WearRoutinePayload(
    val schemaVersion: Int = 4,
    val routineId: String,
    val planSlotId: String? = null,
    val title: String,
    val exercises: List<WearRoutineExercise>,
    val updatedAt: Long,
    val restTimerSeconds: Int = DEFAULT_REST_TIMER_SECONDS,
    val sensorMode: WearSensorMode? = null,
)

data class WearStartWorkoutRequest(
    val schemaVersion: Int = 1,
    val requestId: String,
    val sessionId: String,
    val routine: WearRoutinePayload,
    val requestedAt: Long,
    val expiresAt: Long,
) {
    fun isExpired(now: Long = System.currentTimeMillis()): Boolean = now >= expiresAt

    fun isValid(now: Long = System.currentTimeMillis()): Boolean = runCatching {
        schemaVersion == 1 &&
            requestId.isNotBlank() && '/' !in requestId &&
            sessionId.isNotBlank() &&
            requestedAt >= 0L && expiresAt > requestedAt &&
            routine.routineId.isNotBlank() && routine.title.isNotBlank() &&
            !isExpired(now)
    }.getOrDefault(false)
}

val WearRoutinePayload.usesGpsRunning: Boolean
    get() = sensorMode == WearSensorMode.RUNNING ||
        (sensorMode == null && exercises.isNotEmpty() && exercises.all {
            it.recordMode == WearRecordMode.CARDIO && it.category == "CARDIO"
        })

data class WearRoutineExercise(
    val id: String,
    val exerciseId: String,
    val name: String,
    val order: Int,
    val recordMode: WearRecordMode,
    val category: String,
    val sets: List<WearRoutineSet> = emptyList(),
    val targetDurationMin: Double? = null,
    val targetDistanceKm: Double? = null,
    val targetPaceMinPerKm: Double? = null,
    val durationMin: Double? = null,
    val distanceKm: Double? = null,
    val intervalWorkSeconds: Int? = null,
    val intervalRestSeconds: Int? = null,
    val intervalRounds: Int? = null,
)

data class WearRoutineSet(
    val order: Int,
    val reps: Int? = null,
    val weightKg: Double? = null,
    val completed: Boolean = false,
)

data class WearActiveSession(
    val sessionId: String,
    val routine: WearRoutinePayload,
    val startedAt: Long,
    val currentExerciseIndex: Int = 0,
    val exercises: List<WearRoutineExercise> = routine.exercises,
    val paused: Boolean = false,
    val pausedAt: Long? = null,
    val accumulatedPausedMillis: Long = 0L,
    val restEndsAt: Long? = null,
)

fun WearActiveSession.elapsedMillis(now: Long = System.currentTimeMillis()): Long =
    ((pausedAt ?: now) - startedAt - accumulatedPausedMillis).coerceAtLeast(0L)

fun remainingRestSeconds(restEndsAt: Long?, now: Long = System.currentTimeMillis()): Int {
    val remainingMillis = (restEndsAt ?: return 0) - now
    if (remainingMillis <= 0L) return 0
    return ((remainingMillis + 999L) / 1_000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}

data class WearCompletedWorkout(
    val schemaVersion: Int = 4,
    val sessionId: String,
    val routineId: String,
    val planSlotId: String? = null,
    val title: String,
    val startedAt: Long,
    val endedAt: Long,
    val exercises: List<WearRoutineExercise>,
    val averageHeartRateBpm: Double? = null,
    val distanceKm: Double? = null,
    val caloriesKcal: Double? = null,
    val activeDurationMillis: Long? = null,
    val routePolyline: String? = null,
)
