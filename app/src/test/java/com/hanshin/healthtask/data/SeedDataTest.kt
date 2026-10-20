package com.hanshin.healthtask.data

import com.hanshin.healthtask.domain.ExerciseCategory
import com.hanshin.healthtask.domain.RecordMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SeedDataTest {
    @Test fun `new installations offer only running exercises`() {
        assertEquals(setOf("easy-run", "tempo-run", "long-run"), SeedData.exercises.map { it.id }.toSet())
        assertTrue(SeedData.exercises.all { it.recordMode == RecordMode.CARDIO && it.category == ExerciseCategory.CARDIO })
        assertTrue(SeedData.exercises.all { it.guideAssetPath == null && it.guideVideoUrl == null })
    }
}
