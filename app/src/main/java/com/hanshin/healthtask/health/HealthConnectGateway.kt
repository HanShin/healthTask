package com.hanshin.healthtask.health

import com.hanshin.healthtask.domain.SamsungHealthMeasurement
import com.hanshin.healthtask.domain.SamsungWorkout
import com.hanshin.healthtask.domain.WorkoutSummary

enum class HealthConnectStatus { AVAILABLE, PERMISSIONS_REQUIRED, CONNECTED, UNAVAILABLE }

data class HealthConnectSnapshot(
    val workouts: List<SamsungWorkout>,
    val measurements: List<SamsungHealthMeasurement>,
    val deletedRecordIds: Set<String> = emptySet(),
    val nextChangesToken: String? = null,
)

interface HealthConnectGateway {
    val requiredPermissions: Set<String>
    suspend fun status(): HealthConnectStatus
    suspend fun readSamsungData(changesToken: String? = null): HealthConnectSnapshot
    suspend fun writeWorkout(summary: WorkoutSummary): String
    fun openPermissionManager()
}
