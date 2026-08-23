package com.hanshin.healthtask.health

import com.hanshin.healthtask.domain.ExternalHealthMeasurement
import com.hanshin.healthtask.domain.ExternalWorkout
import com.hanshin.healthtask.domain.WorkoutSummary

enum class HealthConnectStatus { AVAILABLE, PERMISSIONS_REQUIRED, CONNECTED, UNAVAILABLE }

data class HealthConnectSnapshot(
    val workouts: List<ExternalWorkout>,
    val measurements: List<ExternalHealthMeasurement>,
    val deletedRecordIds: Set<String> = emptySet(),
    val nextChangesToken: String? = null,
)

interface HealthConnectGateway {
    val requiredPermissions: Set<String>
    suspend fun status(): HealthConnectStatus
    suspend fun readHealthConnectData(changesToken: String? = null): HealthConnectSnapshot
    suspend fun writeWorkout(summary: WorkoutSummary): String
    fun openPermissionManager()
}
