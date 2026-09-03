package com.hanshin.healthtask.ui

import com.hanshin.healthtask.shared.TABATA_EXERCISE_ID

internal enum class GuideMotionAngle(
    val id: String,
    val label: String,
) {
    FRONT("front", "정면"),
    SIDE("side", "측면"),
    ;

    companion object {
        fun fromId(id: String): GuideMotionAngle = entries.firstOrNull { it.id == id } ?: FRONT
    }
}

internal enum class TabataGuideMovement(
    val id: String,
    val label: String,
    internal val resourceStem: String,
) {
    BURPEE("burpee", "로우 임팩트 버피", "tabata_burpee"),
    MOUNTAIN_CLIMBER("mountain-climber", "마운틴 클라이머", "tabata_mountain_climber"),
    BODYWEIGHT_SQUAT("bodyweight-squat", "맨몸 스쿼트", "tabata_bodyweight_squat"),
    ;

    companion object {
        fun fromId(id: String): TabataGuideMovement = entries.firstOrNull { it.id == id } ?: BURPEE
    }
}

internal data class GuideMotionDescriptor(
    val rawResourceName: String,
    val accessibilityLabel: String,
)

internal fun guideMotionDescriptor(
    exerciseId: String?,
    angle: GuideMotionAngle,
    tabataMovement: TabataGuideMovement = TabataGuideMovement.BURPEE,
): GuideMotionDescriptor? {
    val (resourceStem, exerciseLabel) = when (exerciseId) {
        "squat" -> "squat" to "스쿼트"
        "flat-dumbbell-press" -> "flat_dumbbell_press" to "플랫 덤벨 프레스"
        "one-arm-dumbbell-row" -> "one_arm_dumbbell_row" to "원암 덤벨 로우"
        "shoulder-press" -> "shoulder_press" to "숄더 프레스"
        "hammer-curl" -> "hammer_curl" to "해머 컬"
        "dumbbell-goblet-squat" -> "dumbbell_goblet_squat" to "덤벨 고블릿 스쿼트"
        "dumbbell-romanian-deadlift" -> "dumbbell_romanian_deadlift" to "덤벨 루마니안 데드리프트"
        "dumbbell-bulgarian-split-squat" -> "dumbbell_bulgarian_split_squat" to "덤벨 불가리안 스플릿 스쿼트"
        "plank" -> "plank" to "플랭크"
        "push-up" -> "push_up" to "푸시업"
        TABATA_EXERCISE_ID -> tabataMovement.resourceStem to tabataMovement.label
        else -> return null
    }
    return GuideMotionDescriptor(
        rawResourceName = "${resourceStem}_${angle.id}",
        accessibilityLabel = "$exerciseLabel ${angle.label} 자세 시범 영상",
    )
}
