package com.hanshin.healthtask.data

import com.hanshin.healthtask.domain.RecordMode
import com.hanshin.healthtask.shared.TABATA_EXERCISE_ID
import com.hanshin.healthtask.shared.TABATA_TOTAL_SECONDS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SeedDataTest {
    @Test fun `tabata is selectable and provided as a routine finisher`() {
        val exercise = SeedData.exercises.single { it.id == TABATA_EXERCISE_ID }
        val template = SeedData.templates.single { it.routine.id == "template-strength-tabata" }
        val finisher = template.items.maxBy { it.orderIndex }

        assertEquals(RecordMode.CARDIO, exercise.recordMode)
        assertEquals(TABATA_EXERCISE_ID, finisher.exerciseId)
        assertEquals(TABATA_TOTAL_SECONDS / 60.0, finisher.targetDurationMin!!, 0.001)
        assertTrue(finisher.targetActivityLabel.orEmpty().contains("20초 운동"))
    }
}
