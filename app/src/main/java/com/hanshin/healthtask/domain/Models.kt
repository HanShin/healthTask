package com.hanshin.healthtask.domain

import java.time.Instant
import java.time.LocalDate

enum class WorkoutSource { LOCAL, LEGACY_IMPORT, SAMSUNG_HEALTH, GOOGLE_FIT, NIKE_RUN_CLUB }
enum class SyncStatus { PENDING, SYNCED, ERROR }
enum class ExerciseCategory { WEIGHT, BODYWEIGHT, CARDIO }
enum class RecordMode { SETS, CARDIO }
enum class WorkoutStatus { COMPLETED, PARTIAL, SKIPPED, ACTIVE }
enum class HealthMetricType {
    WEIGHT_KG,
    BODY_FAT_PERCENT,
    BODY_FAT_MASS_KG,
    SKELETAL_MUSCLE_KG,
    VISCERAL_FAT_LEVEL
}

data class ExternalWorkout(
    val recordId: String,
    val title: String,
    val category: ExerciseCategory,
    val start: Instant,
    val end: Instant,
    val distanceKm: Double? = null,
    val caloriesKcal: Double? = null,
    val source: WorkoutSource,
    val sourcePackage: String,
)

data class ExternalHealthMeasurement(
    val recordId: String,
    val type: HealthMetricType,
    val value: Double,
    val measuredAt: Instant,
    val source: WorkoutSource = WorkoutSource.SAMSUNG_HEALTH,
    val sourcePackage: String = SAMSUNG_HEALTH_PACKAGE,
)

data class WorkoutSummary(
    val sessionId: String,
    val title: String,
    val category: ExerciseCategory,
    val startedAt: Instant,
    val endedAt: Instant,
)

data class WeeklyProgress(
    val completed: Int,
    val goal: Int,
    val streakDays: Int,
)

const val SAMSUNG_HEALTH_PACKAGE = "com.sec.android.app.shealth"
const val NIKE_RUN_CLUB_PACKAGE = "com.nike.plusgps"
const val MIN_EXTERNAL_WORKOUT_MINUTES = 10L
const val AUTO_LINK_OVERLAP_RATIO = 0.70

fun ExternalWorkout.qualifiesForGoal(): Boolean =
    java.time.Duration.between(start, end).toMinutes() >= MIN_EXTERNAL_WORKOUT_MINUTES

fun WorkoutSource.isExternal(): Boolean =
    this == WorkoutSource.SAMSUNG_HEALTH || this == WorkoutSource.GOOGLE_FIT || this == WorkoutSource.NIKE_RUN_CLUB

fun LocalDate.inSameSundayWeek(other: LocalDate): Boolean {
    fun startOfWeek(date: LocalDate): LocalDate = date.minusDays((date.dayOfWeek.value % 7).toLong())
    return startOfWeek(this) == startOfWeek(other)
}
