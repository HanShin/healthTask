package com.hanshin.healthtask.ui

import android.app.Application
import android.content.ContentResolver
import android.media.RingtoneManager
import android.net.Uri
import android.os.VibrationEffect
import android.os.VibratorManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hanshin.healthtask.HealthTaskApplication
import com.hanshin.healthtask.data.ImportReport
import com.hanshin.healthtask.data.SyncPreferences
import com.hanshin.healthtask.data.db.ExerciseEntity
import com.hanshin.healthtask.data.db.HealthMeasurementEntity
import com.hanshin.healthtask.data.db.ProfileEntity
import com.hanshin.healthtask.data.db.RoutineWithItems
import com.hanshin.healthtask.data.db.SamsungWorkoutLinkEntity
import com.hanshin.healthtask.data.db.SetRecordEntity
import com.hanshin.healthtask.data.db.WorkoutItemEntity
import com.hanshin.healthtask.data.db.WorkoutSessionWithItems
import com.hanshin.healthtask.domain.HealthMetricType
import com.hanshin.healthtask.domain.WeeklyProgress
import com.hanshin.healthtask.domain.WorkoutSource
import com.hanshin.healthtask.domain.streakDays
import com.hanshin.healthtask.domain.nextRoutineIndex
import com.hanshin.healthtask.domain.weeklyGoalCount
import com.hanshin.healthtask.health.HealthConnectStatus
import com.hanshin.healthtask.health.SyncResult
import com.hanshin.healthtask.shared.DEFAULT_REST_TIMER_SECONDS
import com.hanshin.healthtask.shared.remainingRestSeconds
import java.time.LocalDate
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class RestTimerUiState(
    val totalSeconds: Int = DEFAULT_REST_TIMER_SECONDS,
    val remainingSeconds: Int = 0,
    val endsAt: Long? = null,
) {
    val isRunning: Boolean get() = remainingSeconds > 0 && endsAt != null
}

data class MainUiState(
    val profile: ProfileEntity? = null,
    val exercises: List<ExerciseEntity> = emptyList(),
    val routines: List<RoutineWithItems> = emptyList(),
    val sessions: List<WorkoutSessionWithItems> = emptyList(),
    val health: List<HealthMeasurementEntity> = emptyList(),
    val links: List<SamsungWorkoutLinkEntity> = emptyList(),
    val syncPreferences: SyncPreferences = SyncPreferences(),
    val restTimerSeconds: Int = DEFAULT_REST_TIMER_SECONDS,
    val healthStatus: HealthConnectStatus = HealthConnectStatus.AVAILABLE,
    val restTimer: RestTimerUiState = RestTimerUiState(),
    val message: String? = null,
) {
    val progress: WeeklyProgress
        get() = WeeklyProgress(
            completed = weeklyGoalCount(sessions.map { it.session }, links),
            goal = profile?.workoutsPerWeek ?: 3,
            streakDays = streakDays(sessions.map { it.session }, links),
        )

    val activeSession: WorkoutSessionWithItems?
        get() = sessions.firstOrNull { it.session.status.name == "ACTIVE" }

    val nextRoutine: RoutineWithItems?
        get() {
            if (routines.isEmpty()) return null
            val index = nextRoutineIndex(sessions.map { it.session }, routines.size) ?: return null
            return routines.sortedWith(compareBy({ it.routine.createdAt }, { it.routine.id }))[index]
        }
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as HealthTaskApplication
    private val repository = app.repository
    private val healthStatus = MutableStateFlow(HealthConnectStatus.AVAILABLE)
    private val restTimer = MutableStateFlow(RestTimerUiState())
    private val message = MutableStateFlow<String?>(null)
    private var restTimerJob: Job? = null

    val state: StateFlow<MainUiState> = combine(
        repository.profile,
        repository.exercises,
        repository.routines,
        repository.sessions,
        repository.healthMeasurements,
        repository.workoutLinks,
        app.preferences.sync,
        app.preferences.restTimerSeconds,
        healthStatus,
        restTimer,
        message,
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        MainUiState(
            profile = values[0] as ProfileEntity?,
            exercises = values[1] as List<ExerciseEntity>,
            routines = values[2] as List<RoutineWithItems>,
            sessions = values[3] as List<WorkoutSessionWithItems>,
            health = values[4] as List<HealthMeasurementEntity>,
            links = values[5] as List<SamsungWorkoutLinkEntity>,
            syncPreferences = values[6] as SyncPreferences,
            restTimerSeconds = values[7] as Int,
            healthStatus = values[8] as HealthConnectStatus,
            restTimer = values[9] as RestTimerUiState,
            message = values[10] as String?,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MainUiState())

    val requiredPermissions get() = app.healthConnect.requiredPermissions

    init {
        viewModelScope.launch {
            repository.initialize()
            refreshConnectionStatuses()
            if (app.preferences.sync.first().enabled) sync()
        }
        viewModelScope.launch {
            combine(
                repository.routines,
                repository.sessions,
                repository.exercises,
                app.preferences.restTimerSeconds,
            ) { routines, sessions, exercises, restTimerSeconds ->
                val sorted = routines.sortedWith(compareBy({ it.routine.createdAt }, { it.routine.id }))
                val index = nextRoutineIndex(sessions.map { it.session }, sorted.size)
                WatchSyncState(index?.let(sorted::get), exercises, sessions, restTimerSeconds)
            }.collectLatest { watchState ->
                runCatching {
                    app.phoneWatchGateway.publishRoutine(
                        watchState.routine,
                        watchState.exercises,
                        watchState.sessions,
                        watchState.restTimerSeconds,
                    )
                }
            }
        }
    }

    fun finishOnboarding(goal: Int, templates: Boolean) = launchAction {
        repository.finishOnboarding(goal, templates)
    }

    fun saveRoutine(id: String?, name: String, exerciseIds: List<String>) = launchAction {
        repository.saveRoutine(id, name, exerciseIds)
    }

    fun deleteRoutine(id: String) = launchAction { repository.deleteRoutine(id) }
    fun installTemplate(id: String) = launchAction { repository.installTemplate(id) }

    fun startSession(routineId: String) = launchAction {
        stopRestTimer()
        repository.startSession(routineId)
    }
    fun updateSet(set: SetRecordEntity) = launchAction { repository.updateSet(set) }
    fun setSetCompleted(set: SetRecordEntity, completed: Boolean) = launchAction {
        repository.updateSet(set.copy(completed = completed))
        if (completed && !set.completed) startRestTimer()
    }
    fun updateWorkoutItem(item: WorkoutItemEntity) = launchAction { repository.updateWorkoutItem(item) }
    fun finishSession(id: String) = launchAction {
        repository.finishSession(id)
        stopRestTimer()
        sync()
    }
    fun deleteSession(id: String) = launchAction { repository.deleteSession(id) }

    fun saveHealth(type: HealthMetricType, value: Double) = launchAction {
        repository.saveManualHealthMetric(LocalDate.now(), type, value)
    }

    fun setGoal(value: Int) = launchAction { repository.updateProfileGoal(value) }

    fun setRestTimerSeconds(seconds: Int) = launchAction {
        app.preferences.setRestTimerSeconds(seconds)
        if (!restTimer.value.isRunning) {
            restTimer.value = RestTimerUiState(totalSeconds = seconds)
        }
    }

    fun addRestTime(seconds: Int = 30) {
        val current = restTimer.value
        val currentEnd = current.endsAt ?: return
        if (!current.isRunning) return
        val newEnd = maxOf(currentEnd, System.currentTimeMillis()) + seconds * 1_000L
        restTimer.value = current.copy(
            totalSeconds = current.totalSeconds + seconds,
            remainingSeconds = remainingRestSeconds(newEnd),
            endsAt = newEnd,
        )
    }

    fun skipRestTimer() = stopRestTimer()

    fun setSyncEnabled(enabled: Boolean) = launchAction {
        app.preferences.setSyncEnabled(enabled)
        if (enabled) sync(force = true)
    }

    fun sync(force: Boolean = false) = launchAction(showSuccess = false) {
        val result = app.syncManager.sync(force)
        refreshConnectionStatuses()
        message.value = result.message()
    }

    fun onForeground() {
        viewModelScope.launch {
            refreshConnectionStatuses()
            if (state.value.syncPreferences.enabled) sync()
        }
    }

    fun openHealthPermissions() = app.healthConnect.openPermissionManager()

    fun exportBackup(resolver: ContentResolver, uri: Uri) = launchAction {
        val json = app.backupCodec.export()
        resolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(json) }
            ?: error("백업 파일을 열 수 없습니다.")
        message.value = "schemaVersion 2 백업을 저장했습니다."
    }

    fun importBackup(resolver: ContentResolver, uri: Uri) = launchAction {
        val json = resolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            ?: error("백업 파일을 읽을 수 없습니다.")
        val report = app.backupCodec.import(json)
        message.value = report.message()
    }

    fun consumeMessage() { message.value = null }

    private suspend fun refreshConnectionStatuses() {
        healthStatus.value = app.healthConnect.status()
    }

    private fun startRestTimer() {
        restTimerJob?.cancel()
        val totalSeconds = state.value.restTimerSeconds.coerceIn(15, 600)
        val endsAt = System.currentTimeMillis() + totalSeconds * 1_000L
        restTimer.value = RestTimerUiState(totalSeconds, totalSeconds, endsAt)
        restTimerJob = viewModelScope.launch {
            while (true) {
                delay(250L)
                val current = restTimer.value
                val remaining = remainingRestSeconds(current.endsAt)
                if (remaining <= 0) {
                    restTimer.value = RestTimerUiState(totalSeconds = current.totalSeconds)
                    signalRestComplete()
                    message.value = "휴식 끝! 다음 세트를 시작하세요."
                    return@launch
                }
                if (remaining != current.remainingSeconds) {
                    restTimer.value = current.copy(remainingSeconds = remaining)
                }
            }
        }
    }

    private fun stopRestTimer() {
        restTimerJob?.cancel()
        restTimerJob = null
        restTimer.value = RestTimerUiState(totalSeconds = state.value.restTimerSeconds)
    }

    private fun signalRestComplete() {
        app.getSystemService(VibratorManager::class.java)?.defaultVibrator?.vibrate(
            VibrationEffect.createWaveform(longArrayOf(0L, 250L, 150L, 350L), -1),
        )
        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)?.let { uri ->
            RingtoneManager.getRingtone(app, uri)?.play()
        }
    }

    private fun launchAction(showSuccess: Boolean = false, action: suspend () -> Unit) {
        viewModelScope.launch {
            runCatching { action() }.onFailure { message.value = it.message ?: "작업에 실패했습니다." }
            if (showSuccess) message.value = "저장했습니다."
        }
    }
}

private data class WatchSyncState(
    val routine: RoutineWithItems?,
    val exercises: List<ExerciseEntity>,
    val sessions: List<WorkoutSessionWithItems>,
    val restTimerSeconds: Int,
)

private fun SyncResult.message(): String = error ?: "동기화 완료: 운동 ${downloadedWorkouts}개, 측정 ${downloadedMeasurements}개, 연결 ${linked}개"
private fun ImportReport.message(): String = "백업(v$schemaVersion)을 불러왔습니다: 루틴 ${routines}개, 운동 ${sessions}개, 건강 ${healthMeasurements}개"

fun effectiveHealthMeasurements(values: List<HealthMeasurementEntity>): List<HealthMeasurementEntity> =
    values.groupBy { it.recordDate to it.type }.values.mapNotNull { sameDay ->
        val type = sameDay.firstOrNull()?.type ?: return@mapNotNull null
        if (type == HealthMetricType.WEIGHT_KG || type == HealthMetricType.BODY_FAT_PERCENT) {
            sameDay.filter { it.source == WorkoutSource.SAMSUNG_HEALTH }.maxByOrNull { it.measuredAt }
                ?: sameDay.maxByOrNull { it.measuredAt }
        } else {
            sameDay.filter { it.source != WorkoutSource.SAMSUNG_HEALTH }.maxByOrNull { it.measuredAt }
                ?: sameDay.maxByOrNull { it.measuredAt }
        }
    }.sortedByDescending { it.measuredAt }
