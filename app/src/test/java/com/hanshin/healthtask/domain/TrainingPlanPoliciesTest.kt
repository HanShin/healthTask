package com.hanshin.healthtask.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrainingPlanPoliciesTest {
    @Test fun `legacy running slots remain distinguishable when restoring old records`() {
        assertTrue(PlannedWorkoutType.EASY_RUN.isRun)
        assertTrue(PlannedWorkoutType.QUALITY_RUN.isRun)
        assertTrue(PlannedWorkoutType.LONG_RUN.isRun)
        assertFalse(PlannedWorkoutType.STRENGTH.isRun)
    }
}
