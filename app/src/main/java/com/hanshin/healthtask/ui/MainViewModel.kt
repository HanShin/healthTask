package com.hanshin.healthtask.ui

import android.app.Application
import android.content.ContentResolver
import android.media.RingtoneManager
import android.net.Uri
import android.os.VibrationEffect
import android.os.VibratorManager
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hanshin.healthtask.HealthTaskApplication
import com.hanshin.healthtask.data.ImportReport
import com.hanshin.healthtask.data.RunningPreferences
import com.hanshin.healthtask.data.SyncPreferences
import com.hanshin.healthtask.data.db.ExerciseEntity
import com.hanshin.healthtask.data.db.HealthMeasurementEntity
import com.hanshin.healthtask.data.db.PlanSlotEntity
import com.hanshin.healthtask.data.db.ProfileEntity
import com.hanshin.healthtask.data.db.RoutineWithItems
import com.hanshin.healthtask.data.db.SamsungWorkoutLinkEntity
import com.hanshin.healthtask.data.db.SetRecordEntity
import com.hanshin.healthtask.data.db.TrainingPlanWithSlots
import com.hanshin.healthtask.data.db.WorkoutItemEntity
import com.hanshin.healthtask.data.db.WorkoutSessionWithItems
import com.hanshin.healthtask.domain.HealthMetricType
import com.hanshin.healthtask.domain.PlannedWorkoutType
import com.hanshin.healthtask.domain.TrainingGoalType
import com.hanshin.healthtask.domain.WeeklyProgress
import com.hanshin.healthtask.domain.WeeklyPlanProgress
import com.hanshin.healthtask.domain.WorkoutSource
import com.hanshin.healthtask.domain.streakDays
import com.hanshin.healthtask.domain.nextRoutineIndex
import com.hanshin.healthtask.domain.nextPlanSlot
import com.hanshin.healthtask.domain.weeklyPlanProgress
import com.hanshin.healthtask.domain.weeklyGoalCount
import com.hanshin.healthtask.health.HealthConnectStatus
import com.hanshin.healthtask.health.InBodyScreenshotReader
import com.hanshin.healthtask.health.InBodyScreenshotResult
import com.hanshin.healthtask.health.SyncResult
import com.hanshin.healthtask.running.RunningPhase
import com.hanshin.healthtask.running.RunningLapCodec
import com.hanshin.healthtask.running.RunningRouteCodec
import com.hanshin.healthtask.running.RunningTrackingService
import com.hanshin.healthtask.running.RunningUiState
import com.hanshin.healthtask.shared.DEFAULT_REST_TIMER_SECONDS
import com.hanshin.healthtask.shared.TABATA_EXERCISE_ID
import com.hanshin.healthtask.shared.TABATA_TOTAL_SECONDS
import com.hanshin.healthtask.shared.TabataPhase
import com.hanshin.healthtask.shared.TabataTimer
import com.hanshin.healthtask.shared.TabataTimerState
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

sealed interface InBodyImportUiState {
    data object Idle : InBodyImportUiState
    data object Reading : InBodyImportUiState
    data class Review(val result: InBodyScreenshotResult) : InBodyImportUiState
}

data class MainUiState(
    val profile: ProfileEntity? = null,
    val exercises: List<ExerciseEntity> = emptyList(),
    val routines: List<RoutineWithItems> = emptyList(),
    val trainingPlans: List<TrainingPlanWithSlots> = emptyList(),
    val sessions: List<WorkoutSessionWithItems> = emptyList(),
    val health: List<HealthMeasurementEntity> = emptyList(),
    val links: List<SamsungWorkoutLinkEntity> = emptyList(),
    val syncPreferences: SyncPreferences = SyncPreferences(),
    val restTimerSeconds: Int = DEFAULT_REST_TIMER_SECONDS,
    val runningPreferences: RunningPreferences = RunningPreferences(),
    val healthStatus: HealthConnectStatus = HealthConnectStatus.AVAILABLE,
    val restTimer: RestTimerUiState = RestTimerUiState(),
    val tabataTimer: TabataTimerState = TabataTimerState(),
    val running: RunningUiState = RunningUiState(),
    val message: String? = null,
    val selectedRunPlanSlotId: String? = null,
    val inBodyImport: InBodyImportUiState = InBodyImportUiState.Idle,
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

    val activeTrainingPlan: TrainingPlanWithSlots?
        get() = trainingPlans.firstOrNull { it.plan.isActive }

    val planProgress: WeeklyPlanProgress?
        get() = activeTrainingPlan?.let { weeklyPlanProgress(it.slots, sessions.map { session -> session.session }) }

    val todayPlanSlot: PlanSlotEntity?
        get() = activeTrainingPlan?.let { nextPlanSlot(it.slots, sessions.map { session -> session.session }) }

    val currentRunPlanSlot: PlanSlotEntity?
        get() {
            val slotId = running.plannedSlotId ?: selectedRunPlanSlotId ?: return null
            return trainingPlans.asSequence().flatMap { it.slots.asSequence() }.firstOrNull { it.id == slotId }
        }
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as HealthTaskApplication
    private val repository = app.repository
    private val healthStatus = MutableStateFlow(HealthConnectStatus.AVAILABLE)
    private val restTimer = MutableStateFlow(RestTimerUiState())
    private val tabataTimer = MutableStateFlow(TabataTimerState())
    private val message = MutableStateFlow<String?>(null)
    private val selectedRunPlanSlotId = MutableStateFlow<String?>(null)
    private val inBodyImport = MutableStateFlow<InBodyImportUiState>(InBodyImportUiState.Idle)
    private val inBodyScreenshotReader = InBodyScreenshotReader(application)
    private var restTimerJob: Job? = null
    private var tabataTimerJob: Job? = null

    val state: StateFlow<MainUiState> = combine(
        repository.profile,
        repository.exercises,
        repository.routines,
        repository.trainingPlans,
        repository.sessions,
        repository.healthMeasurements,
        repository.workoutLinks,
        app.preferences.sync,
        app.preferences.restTimerSeconds,
        app.preferences.running,
        healthStatus,
        restTimer,
        tabataTimer,
        app.runningTracker.state,
        message,
        selectedRunPlanSlotId,
        inBodyImport,
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        MainUiState(
            profile = values[0] as ProfileEntity?,
            exercises = values[1] as List<ExerciseEntity>,
            routines = values[2] as List<RoutineWithItems>,
            trainingPlans = values[3] as List<TrainingPlanWithSlots>,
            sessions = values[4] as List<WorkoutSessionWithItems>,
            health = values[5] as List<HealthMeasurementEntity>,
            links = values[6] as List<SamsungWorkoutLinkEntity>,
            syncPreferences = values[7] as SyncPreferences,
            restTimerSeconds = values[8] as Int,
            runningPreferences = values[9] as RunningPreferences,
            healthStatus = values[10] as HealthConnectStatus,
            restTimer = values[11] as RestTimerUiState,
            tabataTimer = values[12] as TabataTimerState,
            running = values[13] as RunningUiState,
            message = values[14] as String?,
            selectedRunPlanSlotId = values[15] as String?,
            inBodyImport = values[16] as InBodyImportUiState,
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
                repository.trainingPlans,
                repository.sessions,
                repository.exercises,
                app.preferences.restTimerSeconds,
            ) { routines, plans, sessions, exercises, restTimerSeconds ->
                val activePlan = plans.firstOrNull { it.plan.isActive }
                val plannedSlot = activePlan?.let { nextPlanSlot(it.slots, sessions.map { session -> session.session }) }
                val plannedRoutine = plannedSlot
                    ?.takeIf { it.workoutType == PlannedWorkoutType.STRENGTH }
                    ?.routineId
                    ?.let { id -> routines.firstOrNull { it.routine.id == id } }
                val fallbackRoutine = if (activePlan == null) {
                    val sorted = routines.sortedWith(compareBy({ it.routine.createdAt }, { it.routine.id }))
                    nextRoutineIndex(sessions.map { it.session }, sorted.size)?.let(sorted::get)
                } else null
                WatchSyncState(
                    plannedRoutine ?: fallbackRoutine,
                    plannedSlot,
                    exercises,
                    sessions,
                    restTimerSeconds,
                )
            }.collectLatest { watchState ->
                runCatching {
                    app.phoneWatchGateway.publishRoutine(
                        watchState.routine,
                        watchState.plannedSlot,
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

    fun rebuildTrainingPlan(goalType: TrainingGoalType) = launchAction {
        repository.rebuildTrainingPlan(goalType)
        selectedRunPlanSlotId.value = null
        message.value = "주간 계획을 새로 구성했습니다."
    }

    fun startSession(routineId: String, planSlotId: String? = null) = launchAction {
        stopRestTimer()
        stopTabataTimer()
        repository.startSession(routineId, planSlotId)
    }

    fun selectPlanSlot(slot: PlanSlotEntity) {
        if (slot.workoutType == PlannedWorkoutType.STRENGTH) {
            val routineId = slot.routineId
            if (routineId == null) {
                message.value = "먼저 근력 루틴을 만든 뒤 계획을 다시 구성해 주세요."
            } else {
                startSession(routineId, slot.id)
            }
        } else {
            if (state.value.running.phase == RunningPhase.COMPLETED) app.runningTracker.reset()
            selectedRunPlanSlotId.value = slot.id
        }
    }
    fun updateSet(set: SetRecordEntity) = launchAction { repository.updateSet(set) }
    fun setSetCompleted(set: SetRecordEntity, completed: Boolean) = launchAction {
        repository.updateSet(set.copy(completed = completed))
        if (completed && !set.completed) startRestTimer()
    }
    fun updateWorkoutItem(item: WorkoutItemEntity) = launchAction { repository.updateWorkoutItem(item) }
    fun startTabata(item: WorkoutItemEntity) {
        if (item.exerciseId != TABATA_EXERCISE_ID) return
        tabataTimerJob?.cancel()
        tabataTimer.value = TabataTimer.start(TabataTimer.ready(item.id))
        launchAction { repository.updateWorkoutItem(item.copy(durationMin = null, distanceKm = null)) }
        signalTabataPhase(TabataPhase.WORK)
        tabataTimerJob = viewModelScope.launch {
            while (true) {
                delay(1_000L)
                val before = tabataTimer.value
                val after = TabataTimer.tick(before)
                tabataTimer.value = after
                if (after.phase != before.phase) signalTabataPhase(after.phase)
                if (after.isCompleted) {
                    state.value.activeSession?.items
                        ?.firstOrNull { it.item.id == after.targetId }
                        ?.item
                        ?.let { repository.updateWorkoutItem(it.copy(durationMin = TABATA_TOTAL_SECONDS / 60.0)) }
                    message.value = "타바타 8라운드를 완료했습니다."
                    tabataTimerJob = null
                    return@launch
                }
            }
        }
    }

    fun pauseTabata() {
        tabataTimer.value = TabataTimer.pause(tabataTimer.value)
    }

    fun resumeTabata() {
        tabataTimer.value = TabataTimer.resume(tabataTimer.value)
    }

    fun resetTabata(item: WorkoutItemEntity) {
        if (tabataTimer.value.targetId != item.id && item.exerciseId != TABATA_EXERCISE_ID) return
        stopTabataTimer(item.id)
        launchAction { repository.updateWorkoutItem(item.copy(durationMin = null, distanceKm = null)) }
    }

    fun finishSession(id: String) = launchAction {
        repository.finishSession(id)
        stopRestTimer()
        stopTabataTimer()
        sync()
    }
    fun deleteSession(id: String) = launchAction { repository.deleteSession(id) }

    fun startRun(planSlotId: String? = selectedRunPlanSlotId.value) {
        if (state.value.activeSession != null) {
            message.value = "진행 중인 근력운동을 먼저 완료해 주세요."
            return
        }
        RunningTrackingService.start(app, planSlotId)
    }

    fun startFreeRun() {
        selectedRunPlanSlotId.value = null
        startRun(null)
    }

    fun pauseRun() {
        RunningTrackingService.pause(app)
    }

    fun resumeRun() {
        RunningTrackingService.resume(app)
    }

    fun finishRun() {
        val completed = app.runningTracker.finish() ?: return
        RunningTrackingService.stop(app)
        launchAction {
            repository.savePhoneRun(
                startedAt = completed.startedAt,
                endedAt = completed.endedAt,
                elapsedMillis = completed.elapsedMillis,
                distanceMeters = completed.distanceMeters,
                routePolyline = RunningRouteCodec.encode(completed.route).takeIf { it.isNotBlank() },
                lapData = RunningLapCodec.encode(completed.laps).takeIf { it.isNotBlank() },
                planSlotId = completed.plannedSlotId,
            )
            selectedRunPlanSlotId.value = null
            message.value = "러닝을 저장했습니다."
            sync()
        }
    }

    fun newRun() {
        if (state.value.running.phase == RunningPhase.COMPLETED) {
            app.runningTracker.reset()
            selectedRunPlanSlotId.value = null
        }
    }

    fun showMessage(value: String) {
        message.value = value
    }

    fun saveHealth(type: HealthMetricType, value: Double) = launchAction {
        repository.saveManualHealthMetric(LocalDate.now(), type, value)
    }

    fun importInBodyScreenshot(uri: Uri) {
        inBodyImport.value = InBodyImportUiState.Reading
        viewModelScope.launch {
            runCatching { inBodyScreenshotReader.read(uri) }
                .onSuccess { result ->
                    Log.i("InBodyOCR", "Recognized ${result.values.size} metrics from ${result.recognizedText.length} characters")
                    if (result.recognizedText.isBlank()) {
                        inBodyImport.value = InBodyImportUiState.Idle
                        message.value = "이미지에서 글자를 찾지 못했습니다. 결과 화면이 선명하게 보이는 캡처를 선택해 주세요."
                    } else {
                        inBodyImport.value = InBodyImportUiState.Review(result)
                        if (result.values.isEmpty()) {
                            message.value = "수치를 자동으로 찾지 못했습니다. 인식 화면에서 직접 확인해 주세요."
                        }
                    }
                }
                .onFailure { error ->
                    Log.e("InBodyOCR", "Failed to recognize screenshot", error)
                    inBodyImport.value = InBodyImportUiState.Idle
                    message.value = error.message ?: "인바디 이미지를 읽지 못했습니다."
                }
        }
    }

    fun dismissInBodyImport() {
        inBodyImport.value = InBodyImportUiState.Idle
    }

    fun saveInBodyImport(date: LocalDate, values: Map<HealthMetricType, Double>) = launchAction {
        repository.saveInBodyScreenshotMetrics(date, values)
        inBodyImport.value = InBodyImportUiState.Idle
        message.value = "인바디 수치 ${values.size}개를 저장했습니다."
    }

    fun setGoal(value: Int) = launchAction { repository.updateProfileGoal(value) }

    fun setRestTimerSeconds(seconds: Int) = launchAction {
        app.preferences.setRestTimerSeconds(seconds)
        if (!restTimer.value.isRunning) {
            restTimer.value = RestTimerUiState(totalSeconds = seconds)
        }
    }

    fun setRunningAutoPauseEnabled(enabled: Boolean) = launchAction {
        app.runningTracker.setAutoPauseEnabled(enabled)
        app.preferences.setRunningAutoPauseEnabled(enabled)
    }

    fun setRunningVoiceGuidanceEnabled(enabled: Boolean) = launchAction {
        app.preferences.setRunningVoiceGuidanceEnabled(enabled)
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
        message.value = "schemaVersion 3 백업을 저장했습니다."
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

    private fun stopTabataTimer(targetId: String? = null) {
        tabataTimerJob?.cancel()
        tabataTimerJob = null
        tabataTimer.value = targetId?.let { TabataTimer.ready(it) } ?: TabataTimerState()
    }

    private fun signalTabataPhase(phase: TabataPhase) {
        val pattern = when (phase) {
            TabataPhase.WORK -> longArrayOf(0L, 180L, 80L, 180L)
            TabataPhase.REST -> longArrayOf(0L, 280L)
            TabataPhase.COMPLETED -> longArrayOf(0L, 250L, 120L, 250L, 120L, 450L)
            else -> return
        }
        app.getSystemService(VibratorManager::class.java)?.defaultVibrator?.vibrate(
            VibrationEffect.createWaveform(pattern, -1),
        )
        if (phase == TabataPhase.COMPLETED) {
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)?.let { uri ->
                RingtoneManager.getRingtone(app, uri)?.play()
            }
        }
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
    val plannedSlot: PlanSlotEntity?,
    val exercises: List<ExerciseEntity>,
    val sessions: List<WorkoutSessionWithItems>,
    val restTimerSeconds: Int,
)

private fun SyncResult.message(): String = error ?: "동기화 완료: 운동 ${downloadedWorkouts}개, 측정 ${downloadedMeasurements}개, 연결 ${linked}개"
private fun ImportReport.message(): String =
    "백업(v$schemaVersion)을 불러왔습니다: 계획 ${trainingPlans}개, 루틴 ${routines}개, 운동 ${sessions}개, 건강 ${healthMeasurements}개"

fun effectiveHealthMeasurements(values: List<HealthMeasurementEntity>): List<HealthMeasurementEntity> =
    values.filterNot { it.type == HealthMetricType.BODY_WATER_L }
        .groupBy { it.recordDate to it.type }.values.mapNotNull { sameDay ->
        val type = sameDay.firstOrNull()?.type ?: return@mapNotNull null
        sameDay.filter { it.sourcePackage == com.hanshin.healthtask.domain.INBODY_PACKAGE }.maxByOrNull { it.measuredAt }
            ?: if (type == HealthMetricType.WEIGHT_KG || type == HealthMetricType.BODY_FAT_PERCENT) {
            sameDay.filter { it.source == WorkoutSource.SAMSUNG_HEALTH }.maxByOrNull { it.measuredAt }
                ?: sameDay.maxByOrNull { it.measuredAt }
        } else {
            sameDay.filter { it.source != WorkoutSource.SAMSUNG_HEALTH }.maxByOrNull { it.measuredAt }
                ?: sameDay.maxByOrNull { it.measuredAt }
        }
    }.sortedByDescending { it.measuredAt }
