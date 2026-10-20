package com.hanshin.healthtask.domain

import com.hanshin.healthtask.data.db.SamsungWorkoutLinkEntity
import com.hanshin.healthtask.data.db.WorkoutSessionWithItems

private val runningExerciseIds = setOf("running", "easy-run", "tempo-run", "long-run", "watch-quick-run", "external-running")

internal fun String.isRunningLabel(): Boolean =
    Regex("러닝|달리기|이지런|템포런|롱런|트레드밀|런닝|(?i)\\b(run|running|jog|jogging|treadmill)\\b").containsMatchIn(this)

fun WorkoutSessionWithItems.isRunningRecord(): Boolean {
    if (items.any { it.item.recordMode == RecordMode.SETS || it.item.category != ExerciseCategory.CARDIO }) return false
    if (items.any { it.item.exerciseId in runningExerciseIds }) return true
    if (session.id.startsWith("run-") || session.memo == "휴대폰 GPS로 기록") return true
    return session.title.isRunningLabel() || items.any { it.item.exerciseName.isRunningLabel() }
}

fun visibleRunningSessions(
    sessions: List<WorkoutSessionWithItems>,
    links: List<SamsungWorkoutLinkEntity>,
): List<WorkoutSessionWithItems> {
    val runs = sessions.filter { it.isRunningRecord() && it.session.status in setOf(WorkoutStatus.COMPLETED, WorkoutStatus.PARTIAL) }
    val localIds = runs.filterNot { it.session.source.isExternal() }.mapTo(mutableSetOf()) { it.session.id }
    // If the local counterpart has been deleted, show the external run again.
    val linkedExternalIds = links.filter { it.localSessionId in localIds }.mapTo(mutableSetOf()) { it.samsungSessionId }
    return runs.filterNot { it.session.source.isExternal() && it.session.id in linkedExternalIds }
}

fun WorkoutSessionWithItems.runningDistanceKm(): Double =
    (session.distanceKm ?: items.sumOf { it.item.distanceKm ?: 0.0 }).coerceAtLeast(0.0)

fun WorkoutSessionWithItems.runningDurationMillis(): Long =
    (session.activeDurationMillis ?: session.endedAt?.minus(session.startedAt) ?: 0L).coerceAtLeast(0L)

fun isValidBodyValue(type: HealthMetricType, value: Double): Boolean =
    value.isFinite() && value > 0.0 && when (type) {
        HealthMetricType.BODY_FAT_PERCENT -> value <= 100.0
        HealthMetricType.INBODY_SCORE -> value <= 120.0
        else -> true
    }
