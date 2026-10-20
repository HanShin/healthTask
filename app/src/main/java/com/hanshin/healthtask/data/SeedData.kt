package com.hanshin.healthtask.data

import com.hanshin.healthtask.data.db.ExerciseEntity
import com.hanshin.healthtask.domain.ExerciseCategory
import com.hanshin.healthtask.domain.RecordMode

/** Only running entries are seeded. Existing legacy exercise rows are not deleted. */
object SeedData {
    val exercises = listOf(
        "easy-run" to "자유 러닝",
        "tempo-run" to "템포런",
        "long-run" to "롱런",
    ).map { (id, name) ->
        ExerciseEntity(id = id, name = name, category = ExerciseCategory.CARDIO, recordMode = RecordMode.CARDIO)
    }
}
