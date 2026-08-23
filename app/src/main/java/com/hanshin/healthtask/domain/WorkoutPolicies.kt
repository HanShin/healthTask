package com.hanshin.healthtask.domain

import com.hanshin.healthtask.data.db.SamsungWorkoutLinkEntity
import com.hanshin.healthtask.data.db.SetRecordEntity
import com.hanshin.healthtask.data.db.WorkoutItemWithSets
import com.hanshin.healthtask.data.db.WorkoutSessionEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.max
import kotlin.math.min

fun nextRoutineIndex(
    sessions: List<WorkoutSessionEntity>,
    routineCount: Int,
    referenceDate: LocalDate = LocalDate.now(),
): Int? {
    if (routineCount <= 0) return null
    val localRoutineCompletions = sessions.count {
        it.source == WorkoutSource.LOCAL && it.routineId != null &&
            it.status != WorkoutStatus.ACTIVE && it.status != WorkoutStatus.SKIPPED &&
            LocalDate.parse(it.sessionDate).inSameSundayWeek(referenceDate)
    }
    return localRoutineCompletions % routineCount
}

fun workoutStatus(items: List<WorkoutItemWithSets>): WorkoutStatus {
    val completedItems = items.count { item ->
        if (item.item.recordMode == RecordMode.CARDIO) (item.item.durationMin ?: 0.0) > 0.0
        else item.sets.isNotEmpty() && item.sets.all { it.completed }
    }
    return when {
        completedItems == 0 -> WorkoutStatus.SKIPPED
        completedItems == items.size -> WorkoutStatus.COMPLETED
        else -> WorkoutStatus.PARTIAL
    }
}

fun latestCompletedSet(sessions: List<com.hanshin.healthtask.data.db.WorkoutSessionWithItems>, exerciseId: String): SetRecordEntity? =
    sessions.asSequence()
        .filter { it.session.status != WorkoutStatus.ACTIVE }
        .flatMap { it.items.asSequence() }
        .filter { it.item.exerciseId == exerciseId }
        .flatMap { it.sets.asSequence().sortedByDescending { set -> set.orderIndex } }
        .firstOrNull { it.completed } ?: sessions.asSequence()
        .flatMap { it.items.asSequence() }
        .filter { it.item.exerciseId == exerciseId }
        .flatMap { it.sets.asSequence().sortedByDescending { set -> set.orderIndex } }
        .firstOrNull()

fun overlapRatio(
    firstStart: Long,
    firstEnd: Long,
    secondStart: Long,
    secondEnd: Long,
): Double {
    val overlap = max(0L, min(firstEnd, secondEnd) - max(firstStart, secondStart))
    val shorter = min(firstEnd - firstStart, secondEnd - secondStart)
    return if (shorter <= 0L) 0.0 else overlap.toDouble() / shorter.toDouble()
}

fun categoriesCompatible(local: ExerciseCategory, external: ExerciseCategory): Boolean = when (local) {
    ExerciseCategory.CARDIO -> external == ExerciseCategory.CARDIO
    ExerciseCategory.WEIGHT, ExerciseCategory.BODYWEIGHT -> external != ExerciseCategory.CARDIO
}

fun bestAutomaticLink(
    localSessions: List<WorkoutSessionEntity>,
    samsung: WorkoutSessionEntity,
    externalCategory: ExerciseCategory? = null,
    localCategory: (String) -> ExerciseCategory?,
): Pair<WorkoutSessionEntity, Double>? {
    val samsungEnd = samsung.endedAt ?: return null
    val samsungCategory = externalCategory ?: when (samsung.title.lowercase()) {
        "달리기", "걷기", "러닝", "running", "walking" -> ExerciseCategory.CARDIO
        else -> if ((samsung.distanceKm ?: 0.0) > 0.0) ExerciseCategory.CARDIO else ExerciseCategory.WEIGHT
    }
    return localSessions.asSequence()
        .filter { it.source == WorkoutSource.LOCAL && it.endedAt != null && it.sessionDate == samsung.sessionDate }
        .mapNotNull { local ->
            val category = localCategory(local.id) ?: return@mapNotNull null
            if (!categoriesCompatible(category, samsungCategory)) return@mapNotNull null
            val ratio = overlapRatio(local.startedAt, local.endedAt!!, samsung.startedAt, samsungEnd)
            if (ratio >= AUTO_LINK_OVERLAP_RATIO) local to ratio else null
        }
        .maxByOrNull { it.second }
}

fun weeklyGoalCount(
    sessions: List<WorkoutSessionEntity>,
    links: List<SamsungWorkoutLinkEntity>,
    referenceDate: LocalDate = LocalDate.now(),
): Int {
    val linkedExternalIds = links.mapTo(mutableSetOf()) { it.samsungSessionId }
    return sessions.count { session ->
        val date = LocalDate.parse(session.sessionDate)
        if (!date.inSameSundayWeek(referenceDate) || session.status == WorkoutStatus.SKIPPED || session.status == WorkoutStatus.ACTIVE) {
            return@count false
        }
        when (session.source) {
            WorkoutSource.LOCAL, WorkoutSource.LEGACY_IMPORT -> true
            WorkoutSource.SAMSUNG_HEALTH, WorkoutSource.GOOGLE_FIT, WorkoutSource.NIKE_RUN_CLUB -> {
                if (session.id in linkedExternalIds) false
                else {
                    val end = session.endedAt ?: return@count false
                    java.time.Duration.between(Instant.ofEpochMilli(session.startedAt), Instant.ofEpochMilli(end)).toMinutes() >= MIN_EXTERNAL_WORKOUT_MINUTES
                }
            }
        }
    }
}

fun streakDays(sessions: List<WorkoutSessionEntity>, links: List<SamsungWorkoutLinkEntity>): Int {
    val linkedExternalIds = links.mapTo(mutableSetOf()) { it.samsungSessionId }
    val eligibleDates = sessions.filter { session ->
        session.status != WorkoutStatus.SKIPPED && session.status != WorkoutStatus.ACTIVE &&
            (!session.source.isExternal() || (session.id !in linkedExternalIds && session.endedAt?.let {
                java.time.Duration.between(Instant.ofEpochMilli(session.startedAt), Instant.ofEpochMilli(it)).toMinutes() >= MIN_EXTERNAL_WORKOUT_MINUTES
            } == true))
    }.map { LocalDate.parse(it.sessionDate) }.distinct().sortedDescending()
    if (eligibleDates.isEmpty()) return 0
    var count = 1
    var cursor = eligibleDates.first()
    for (next in eligibleDates.drop(1)) {
        if (next != cursor.minusDays(1)) break
        count++
        cursor = next
    }
    return count
}
