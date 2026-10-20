package com.hanshin.healthtask.ui

import android.app.Application
import android.content.ContentResolver
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hanshin.healthtask.HealthTaskApplication
import com.hanshin.healthtask.data.ImportReport
import com.hanshin.healthtask.data.RunningPreferences
import com.hanshin.healthtask.data.InBodyGraphPreferences
import com.hanshin.healthtask.data.SyncPreferences
import com.hanshin.healthtask.data.db.HealthMeasurementEntity
import com.hanshin.healthtask.data.db.SamsungWorkoutLinkEntity
import com.hanshin.healthtask.data.db.WorkoutSessionWithItems
import com.hanshin.healthtask.domain.HealthMetricType
import com.hanshin.healthtask.domain.WorkoutSource
import com.hanshin.healthtask.health.HealthConnectStatus
import com.hanshin.healthtask.health.InBodyScreenshotReader
import com.hanshin.healthtask.health.InBodyScreenshotResult
import com.hanshin.healthtask.health.InBodyCsvParser
import com.hanshin.healthtask.health.InBodyCsvPreview
import com.hanshin.healthtask.data.db.InBodyRecordEntity
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import com.hanshin.healthtask.health.SyncResult
import com.hanshin.healthtask.running.RunningPhase
import com.hanshin.healthtask.running.RunningLapCodec
import com.hanshin.healthtask.running.RunningRouteCodec
import com.hanshin.healthtask.running.RunningTrackingService
import com.hanshin.healthtask.running.RunningUiState
import com.hanshin.healthtask.shared.WearStartWorkoutRequest
import com.hanshin.healthtask.shared.WearQuickRunPreset
import com.hanshin.healthtask.shared.toRoutinePayload
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

import com.hanshin.healthtask.domain.visibleRunningSessions

sealed interface InBodyImportUiState {
    data object Idle : InBodyImportUiState
    data object Reading : InBodyImportUiState
    data class Review(val result: InBodyScreenshotResult) : InBodyImportUiState
    data class CsvReview(val result: InBodyCsvPreview) : InBodyImportUiState
    data object Saving : InBodyImportUiState
}

data class MainUiState(
    val sessions: List<WorkoutSessionWithItems> = emptyList(),
    val health: List<HealthMeasurementEntity> = emptyList(),
    val inBodyRecords: List<InBodyRecordEntity> = emptyList(),
    val links: List<SamsungWorkoutLinkEntity> = emptyList(),
    val syncPreferences: SyncPreferences = SyncPreferences(),
    val runningPreferences: RunningPreferences = RunningPreferences(),
    val inBodyGraphPreferences: InBodyGraphPreferences = InBodyGraphPreferences(),
    val healthStatus: HealthConnectStatus = HealthConnectStatus.AVAILABLE,
    val running: RunningUiState = RunningUiState(),
    val message: String? = null,
    val inBodyImport: InBodyImportUiState = InBodyImportUiState.Idle,
    val loaded: Boolean = false,
) {
    val runs: List<WorkoutSessionWithItems> get() = visibleRunningSessions(sessions, links)
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as HealthTaskApplication
    private val repository = app.repository
    private val healthStatus = MutableStateFlow(HealthConnectStatus.AVAILABLE)
    private val message = MutableStateFlow<String?>(null)
    private val inBodyImport = MutableStateFlow<InBodyImportUiState>(InBodyImportUiState.Idle)
    private val loaded = MutableStateFlow(false)
    private val inBodyScreenshotReader = InBodyScreenshotReader(application)

    val state: StateFlow<MainUiState> = combine(
        repository.sessions, repository.healthMeasurements, repository.workoutLinks,
        app.preferences.sync, app.preferences.running, healthStatus, app.runningTracker.state,
        message, inBodyImport, loaded, repository.inBodyRecords, app.preferences.inBodyGraphs,
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        MainUiState(
            sessions = values[0] as List<WorkoutSessionWithItems>,
            health = values[1] as List<HealthMeasurementEntity>,
            links = values[2] as List<SamsungWorkoutLinkEntity>,
            syncPreferences = values[3] as SyncPreferences,
            runningPreferences = values[4] as RunningPreferences,
            healthStatus = values[5] as HealthConnectStatus,
            running = values[6] as RunningUiState,
            message = values[7] as String?,
            inBodyImport = values[8] as InBodyImportUiState,
            loaded = values[9] as Boolean,
            inBodyRecords = values[10] as List<InBodyRecordEntity>,
            inBodyGraphPreferences = values[11] as InBodyGraphPreferences,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MainUiState())

    private val cloudStatusState = MutableStateFlow(app.cloudSync.status())
    val cloudStatus: StateFlow<String> = cloudStatusState
    fun refreshCloudStatus() { cloudStatusState.value = app.cloudSync.status() }
    fun pairCloud(code: String) = launchAction {
        app.cloudSync.pair(code)
        refreshCloudStatus()
        message.value = "웹 연결 완료. 전체 기록 전송을 예약했습니다."
    }
    fun disconnectCloud() = launchAction {
        app.cloudSync.disconnect()
        refreshCloudStatus()
        message.value = "기기 업로드 권한을 폐기했습니다. 웹에 저장된 기록은 유지됩니다."
    }
    fun syncCloud() {
        com.hanshin.healthtask.cloud.CloudSync.enqueue(app)
        message.value = "네트워크가 연결되면 클라우드 전송을 시작합니다."
    }

    val requiredPermissions get() = app.healthConnect.requiredPermissions

    fun setInBodyGraphPreferences(value: InBodyGraphPreferences) = launchAction { app.preferences.setInBodyGraphs(value) }

    init {
        viewModelScope.launch {
            repository.initialize()
            loaded.value = true
            refreshConnectionStatuses()
            if (app.preferences.sync.first().enabled) sync()
        }
        viewModelScope.launch {
            runCatching { app.phoneWatchGateway.publishRunningPreset() }
            runCatching { app.watchWorkoutImporter.importPending() }
        }
    }

    fun deleteSession(id: String) = launchAction { repository.deleteSession(id) }

    fun startRun() {
        if (app.runningTracker.state.value.isActive) return
        val sessionId = "run-${UUID.randomUUID()}"
        app.runningTracker.start(sessionId)
        val serviceStarted = runCatching { RunningTrackingService.start(app, sessionId) }.getOrNull() != null
        if (!serviceStarted) {
            if (app.runningTracker.state.value.sessionId == sessionId) app.runningTracker.reset()
            message.value = "러닝 기록 서비스를 시작하지 못했습니다. 다시 시도해 주세요."
            return
        }
        val requestedAt = System.currentTimeMillis()
        viewModelScope.launch {
            val watchOpened = runCatching {
                app.phoneWatchGateway.requestWorkoutStart(WearStartWorkoutRequest(
                    requestId = "start-$sessionId", sessionId = sessionId,
                    routine = WearQuickRunPreset.FREE_RUN.toRoutinePayload(updatedAt = requestedAt),
                    requestedAt = requestedAt, expiresAt = requestedAt + WATCH_START_REQUEST_TTL_MILLIS,
                ))
            }.getOrDefault(false)
            if (!watchOpened) message.value = "러닝을 시작했습니다. 워치가 연결되어 있으면 워치 앱에서도 확인해 주세요."
        }
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
                sessionId = completed.sessionId,
                startedAt = completed.startedAt,
                endedAt = completed.endedAt,
                elapsedMillis = completed.elapsedMillis,
                distanceMeters = completed.distanceMeters,
                routePolyline = RunningRouteCodec.encode(completed.route).takeIf { it.isNotBlank() },
                lapData = RunningLapCodec.encode(completed.laps).takeIf { it.isNotBlank() },
                planSlotId = completed.plannedSlotId,
            )
            message.value = "러닝을 저장했습니다."
            sync()
        }
    }

    fun newRun() {
        if (state.value.running.phase == RunningPhase.COMPLETED) {
            app.runningTracker.reset()
        }
    }

    fun showMessage(value: String) {
        message.value = value
    }

    fun saveHealth(date: LocalDate, type: HealthMetricType, value: Double, existingId: String? = null) = launchAction {
        repository.saveBodyMeasurement(date, type, value, existingId)
    }

    fun deleteHealth(id: String) = launchAction { repository.deleteBodyMeasurement(id) }

    fun deleteInBodyRecord(id: String) = launchAction { repository.inBodyCsv.delete(id) }

    fun importInBodyCsv(uri: Uri) {
        if (inBodyImport.value != InBodyImportUiState.Idle) return
        inBodyImport.value = InBodyImportUiState.Reading
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val resolver = app.contentResolver
                    val fileName = runCatching {
                        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                            if (it.moveToFirst()) it.getString(0) else null
                        }
                    }.getOrNull() ?: "InBody.csv"
                    val bytes = resolver.openInputStream(uri)?.use { input ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val size = input.read(buffer)
                            if (size == -1) break
                            require(output.size() + size <= InBodyCsvParser.MAX_BYTES) { "CSV 파일은 5MB 이하로 선택해 주세요." }
                            output.write(buffer, 0, size)
                        }
                        output.toByteArray()
                    } ?: error("CSV 파일을 열 수 없습니다.")
                    InBodyCsvParser.parse(InBodyCsvParser.decode(bytes), fileName)
                }
            }.onSuccess { inBodyImport.value = InBodyImportUiState.CsvReview(it) }
                .onFailure {
                    inBodyImport.value = InBodyImportUiState.Idle
                    message.value = it.message ?: "CSV를 읽지 못했습니다."
                }
        }
    }

    fun saveInBodyCsv(preview: InBodyCsvPreview) {
        if (inBodyImport.value !is InBodyImportUiState.CsvReview) return
        inBodyImport.value = InBodyImportUiState.Saving
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { repository.inBodyCsv.save(preview) } }
                .onSuccess { report ->
                    inBodyImport.value = InBodyImportUiState.Idle
                    message.value = "새 기록 ${report.inserted}건 · 추가 항목 반영 ${report.expanded}건 · 중복 ${report.skipped}건" +
                        if (report.conflicts > 0) " · 값이 다른 ${report.conflicts}개 항목은 기존 값을 유지했습니다." else "을 처리했습니다."
                }.onFailure {
                    inBodyImport.value = InBodyImportUiState.CsvReview(preview)
                    message.value = it.message ?: "저장하지 못했습니다. 다시 시도해 주세요."
                }
        }
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

    fun setRunningAutoPauseEnabled(enabled: Boolean) = launchAction {
        app.runningTracker.setAutoPauseEnabled(enabled)
        app.preferences.setRunningAutoPauseEnabled(enabled)
    }

    fun setRunningVoiceGuidanceEnabled(enabled: Boolean) = launchAction {
        app.preferences.setRunningVoiceGuidanceEnabled(enabled)
    }

    fun setSyncEnabled(enabled: Boolean) = launchAction {
        app.preferences.setSyncEnabled(enabled)
        if (enabled) sync(force = true)
    }

    fun sync(force: Boolean = false) = launchAction(showSuccess = false) {
        val result = app.syncManager.sync(force)
        com.hanshin.healthtask.cloud.CloudSync.enqueue(app)
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
        withContext(Dispatchers.IO) {
            resolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { it.write(json) }
                ?: error("백업 파일을 열 수 없습니다.")
        }
        message.value = "상세 분석 데이터와 복원용 백업을 함께 저장했습니다."
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

    private fun launchAction(showSuccess: Boolean = false, action: suspend () -> Unit) {
        viewModelScope.launch {
            runCatching { action() }.onFailure { message.value = it.message ?: "작업에 실패했습니다." }
            if (showSuccess) message.value = "저장했습니다."
        }
    }
}

private const val WATCH_START_REQUEST_TTL_MILLIS = 2 * 60 * 1_000L

private fun SyncResult.message(): String = error ?: "동기화 완료: 러닝 ${downloadedWorkouts}개, 체성분 ${downloadedMeasurements}개"
private fun ImportReport.message(): String =
    "백업(v$schemaVersion)을 불러왔습니다: 기록 ${sessions}개, 체성분 ${healthMeasurements}개. 러닝 외 기록은 보관만 합니다."

fun effectiveHealthMeasurements(values: List<HealthMeasurementEntity>): List<HealthMeasurementEntity> =
    values.filterNot { it.type.isCode || (it.type == HealthMetricType.BODY_WATER_L && it.externalRecordId?.startsWith("inbody-csv-") != true) }
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
