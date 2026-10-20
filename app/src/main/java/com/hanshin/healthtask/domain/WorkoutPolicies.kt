package com.hanshin.healthtask.domain

import com.hanshin.healthtask.data.db.SetRecordEntity
import com.hanshin.healthtask.data.db.WorkoutItemWithSets
import com.hanshin.healthtask.data.db.WorkoutSessionEntity
import kotlin.math.max
import kotlin.math.min

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
