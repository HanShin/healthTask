package com.hanshin.healthtask.domain

// Used only when restoring or finishing a run from an older planned session.
val PlannedWorkoutType.isRun: Boolean
    get() = this != PlannedWorkoutType.STRENGTH
