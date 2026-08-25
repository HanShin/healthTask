package com.hanshin.healthtask.shared

enum class WearQuickRunPreset(
    val title: String,
    val subtitle: String,
    val targetDurationMin: Double? = null,
    val targetDistanceKm: Double? = null,
) {
    FREE_RUN(
        title = "자유 러닝",
        subtitle = "목표 없이 바로 시작",
    ),
    THIRTY_MINUTES(
        title = "30분 러닝",
        subtitle = "시간 목표 · 30분",
        targetDurationMin = 30.0,
    ),
    FIVE_KILOMETERS(
        title = "5km 러닝",
        subtitle = "거리 목표 · 5.0km",
        targetDistanceKm = 5.0,
    ),
}

fun WearQuickRunPreset.toRoutinePayload(
    updatedAt: Long = System.currentTimeMillis(),
): WearRoutinePayload = WearRoutinePayload(
    routineId = "watch-${name.lowercase()}",
    title = title,
    exercises = listOf(
        WearRoutineExercise(
            id = "watch-${name.lowercase()}-exercise",
            exerciseId = "watch-quick-run",
            name = title,
            order = 1,
            recordMode = WearRecordMode.CARDIO,
            category = "CARDIO",
            targetDurationMin = targetDurationMin,
            targetDistanceKm = targetDistanceKm,
        ),
    ),
    updatedAt = updatedAt,
    sensorMode = WearSensorMode.RUNNING,
)

fun freeWorkoutRoutinePayload(
    updatedAt: Long = System.currentTimeMillis(),
): WearRoutinePayload = WearRoutinePayload(
    routineId = "watch-free-workout",
    title = "바로 운동",
    exercises = listOf(
        WearRoutineExercise(
            id = "watch-free-workout-exercise",
            exerciseId = "watch-free-workout",
            name = "자유 운동",
            order = 1,
            recordMode = WearRecordMode.CARDIO,
            category = "WEIGHT",
        ),
    ),
    updatedAt = updatedAt,
    sensorMode = WearSensorMode.STRENGTH,
)
