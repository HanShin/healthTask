package com.hanshin.healthtask.wear

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.FilledIconButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButtonDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.hanshin.healthtask.shared.WearActiveSession
import com.hanshin.healthtask.shared.WearQuickRunPreset
import com.hanshin.healthtask.shared.WearRecordMode
import com.hanshin.healthtask.shared.WearRunningMetrics
import com.hanshin.healthtask.shared.WearRoutineExercise
import com.hanshin.healthtask.shared.WearRoutinePayload
import com.hanshin.healthtask.shared.WearStartWorkoutRequest
import com.hanshin.healthtask.shared.TABATA_REST_SECONDS
import com.hanshin.healthtask.shared.TABATA_ROUNDS
import com.hanshin.healthtask.shared.TABATA_WORK_SECONDS
import com.hanshin.healthtask.shared.TabataPhase
import com.hanshin.healthtask.shared.TabataTimerState
import com.hanshin.healthtask.shared.elapsedMillis
import com.hanshin.healthtask.shared.freeWorkoutRoutinePayload
import com.hanshin.healthtask.shared.isTabata
import com.hanshin.healthtask.shared.remainingRestSeconds
import com.hanshin.healthtask.shared.toRoutinePayload
import com.hanshin.healthtask.shared.usesGpsRunning
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

class WearMainActivity : ComponentActivity() {
    private val viewModel: WearMainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleStartWorkoutIntent(intent)
        setContent { WearWorkoutApp(viewModel) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleStartWorkoutIntent(intent)
    }

    private fun handleStartWorkoutIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val data = intent.data ?: return
        val requestId = data.pathSegments
            .takeIf { data.scheme == "healthtask" && data.host == "workout" && it.size == 2 && it[0] == "start" }
            ?.get(1)
            ?.takeIf { it.isNotBlank() }
            ?: return
        viewModel.refreshStartRequest(requestId)
    }
}

private enum class WorkoutPicker { RUNNING, STRENGTH }

@Composable
private fun WearWorkoutApp(viewModel: WearMainViewModel) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val remoteStartRequest = state.pendingStartRequest?.takeIf { it.isValid() }
    var picker by remember { mutableStateOf<WorkoutPicker?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        viewModel.pendingPermissionStart.value?.let { start ->
            val canTrack = sensorPermissionsFor(start.routine).all { permission ->
                result[permission] == true || ContextCompat.checkSelfPermission(context, permission) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            }
            viewModel.completePendingPermissionStart(canTrack)
        }
    }
    val requestStart: (PendingWorkoutStart) -> Unit = { start ->
        val sensorPermissions = sensorPermissionsFor(start.routine)
        val requestedPermissions = buildList {
            addAll(sensorPermissions)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = requestedPermissions.filter { permission ->
            ContextCompat.checkSelfPermission(context, permission) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            viewModel.startWorkout(
                routine = start.routine,
                trackSensors = true,
                sessionId = start.sessionId,
                requestId = start.requestId,
            )
        } else if (viewModel.deferWorkoutStartForPermissions(start)) {
            launcher.launch(missing.toTypedArray())
        }
    }
    BackHandler(enabled = picker != null && state.active == null && remoteStartRequest == null) { picker = null }

    MaterialTheme {
        val effectiveRestSeconds = maxOf(
            state.restRemainingSeconds,
            remainingRestSeconds(state.active?.restEndsAt),
        )
        val scrollState = rememberScrollState()
        LaunchedEffect(picker, state.active?.sessionId, remoteStartRequest?.requestId) {
            scrollState.scrollTo(0)
            if (state.active != null || remoteStartRequest != null) picker = null
        }
        Column(
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
                .padding(start = 18.dp, end = 18.dp, top = 24.dp, bottom = 32.dp)
                .verticalScroll(scrollState),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (state.active != null && effectiveRestSeconds > 0) {
                WearRestTimerScreen(effectiveRestSeconds, viewModel)
            } else if (state.active != null) {
                ActiveWorkoutScreen(state, viewModel)
            } else if (remoteStartRequest != null) {
                RemoteStartScreen(
                    request = remoteStartRequest,
                    onStart = {
                        requestStart(
                            PendingWorkoutStart(
                                routine = remoteStartRequest.routine,
                                sessionId = remoteStartRequest.sessionId,
                                requestId = remoteStartRequest.requestId,
                            ),
                        )
                    },
                    onDecline = { viewModel.declineStartRequest(remoteStartRequest.requestId) },
                )
            } else if (picker == WorkoutPicker.RUNNING) {
                QuickRunPickerScreen(
                    syncedRoutine = state.routine?.takeIf { it.usesGpsRunning },
                    onBack = { picker = null },
                    onStart = { requestStart(PendingWorkoutStart(it)) },
                )
            } else if (picker == WorkoutPicker.STRENGTH) {
                StrengthPickerScreen(
                    syncedRoutine = state.routine?.takeIf { !it.usesGpsRunning },
                    onBack = { picker = null },
                    onStart = { requestStart(PendingWorkoutStart(it)) },
                )
            } else {
                WorkoutHomeScreen(
                    onRunning = { picker = WorkoutPicker.RUNNING },
                    onStrength = { picker = WorkoutPicker.STRENGTH },
                )
            }
            state.message?.let { message ->
                Spacer(Modifier.height(8.dp))
                Text(message, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.primary)
                LaunchedEffect(message) {
                    delay(3_000)
                    viewModel.consumeMessage()
                }
            }
        }
    }
}

private fun sensorPermissionsFor(routine: WearRoutinePayload): List<String> = buildList {
    add(Manifest.permission.ACTIVITY_RECOGNITION)
    if (Build.VERSION.SDK_INT >= 36) add("android.permission.health.READ_HEART_RATE")
    else add(Manifest.permission.BODY_SENSORS)
    if (routine.usesGpsRunning) {
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        add(Manifest.permission.ACCESS_FINE_LOCATION)
    }
}

@Composable
private fun WearRestTimerScreen(remainingSeconds: Int, viewModel: WearMainViewModel) {
    Spacer(Modifier.height(12.dp))
    Text("휴식 중", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(8.dp))
    Text(formatCountdown(remainingSeconds), style = MaterialTheme.typography.displayLarge, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(8.dp))
    Text("휴식이 끝날 때까지\n운동 입력이 잠깁니다.", textAlign = TextAlign.Center)
    Spacer(Modifier.height(12.dp))
    Button(onClick = { viewModel.addRestTime() }, modifier = Modifier.fillMaxWidth()) { Text("+30초") }
    Spacer(Modifier.height(10.dp))
    Button(onClick = viewModel::skipRestTimer, modifier = Modifier.fillMaxWidth()) { Text("휴식 종료") }
}

@Composable
private fun WorkoutHomeScreen(
    onRunning: () -> Unit,
    onStrength: () -> Unit,
) {
    Spacer(Modifier.height(8.dp))
    Text("운동 선택", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    Text("워치에서 바로 시작하세요", style = MaterialTheme.typography.bodySmall)
    Spacer(Modifier.height(14.dp))
    Button(onClick = onRunning, modifier = Modifier.fillMaxWidth().height(64.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("러닝", fontWeight = FontWeight.Bold)
            Text("거리 · 시간 · 자유 러닝", style = MaterialTheme.typography.labelSmall)
        }
    }
    Spacer(Modifier.height(12.dp))
    Button(onClick = onStrength, modifier = Modifier.fillMaxWidth().height(64.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("중량 운동", fontWeight = FontWeight.Bold)
            Text("오늘 계획 · 자유 중량", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun StrengthPickerScreen(
    syncedRoutine: WearRoutinePayload?,
    onBack: () -> Unit,
    onStart: (WearRoutinePayload) -> Unit,
) {
    Spacer(Modifier.height(12.dp))
    Text("중량 운동", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    Text("운동 방식을 고르세요", style = MaterialTheme.typography.bodySmall)
    Spacer(Modifier.height(10.dp))
    if (syncedRoutine != null) {
        Button(onClick = { onStart(syncedRoutine) }, modifier = Modifier.fillMaxWidth()) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("오늘 계획", fontWeight = FontWeight.Bold)
                Text(syncedRoutine.title, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
            }
        }
        Spacer(Modifier.height(10.dp))
        SyncedWorkoutSummary(syncedRoutine)
        Spacer(Modifier.height(12.dp))
    }
    Button(onClick = { onStart(freeWorkoutRoutinePayload()) }, modifier = Modifier.fillMaxWidth()) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("자유 중량", fontWeight = FontWeight.Bold)
            Text("계획 없이 바로 기록", style = MaterialTheme.typography.labelSmall)
        }
    }
    Spacer(Modifier.height(10.dp))
    Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("돌아가기") }
}

@Composable
private fun RemoteStartScreen(
    request: WearStartWorkoutRequest,
    onStart: () -> Unit,
    onDecline: () -> Unit,
) {
    Spacer(Modifier.height(10.dp))
    Text("폰에서 시작한 운동", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(6.dp))
    Text(request.routine.title, style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center)
    Text(
        if (request.routine.usesGpsRunning) {
            "러닝 측정과 제어를 워치에서 이어갈까요?"
        } else {
            "${request.routine.exercises.size}개 운동을 워치에서 이어서 기록할까요?"
        },
        style = MaterialTheme.typography.bodySmall,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(14.dp))
    Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
        Text("워치에서 시작", fontWeight = FontWeight.Bold)
    }
    Spacer(Modifier.height(10.dp))
    Button(onClick = onDecline, modifier = Modifier.fillMaxWidth()) { Text("폰에서만 계속") }
}

@Composable
private fun QuickRunPickerScreen(
    syncedRoutine: WearRoutinePayload?,
    onBack: () -> Unit,
    onStart: (WearRoutinePayload) -> Unit,
) {
    Spacer(Modifier.height(12.dp))
    Text("빠른 러닝", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    Text("목표를 고르세요", style = MaterialTheme.typography.bodySmall)
    Spacer(Modifier.height(8.dp))
    if (syncedRoutine != null) {
        Button(
            onClick = { onStart(syncedRoutine) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("오늘 계획", fontWeight = FontWeight.Bold)
                Text(syncedRoutine.title, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
            }
        }
        Spacer(Modifier.height(8.dp))
        SyncedWorkoutSummary(syncedRoutine)
        Spacer(Modifier.height(10.dp))
    }
    WearQuickRunPreset.entries.forEach { preset ->
        Button(
            onClick = { onStart(preset.toRoutinePayload()) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(preset.title, fontWeight = FontWeight.Bold)
                Text(preset.subtitle, style = MaterialTheme.typography.labelSmall)
            }
        }
        Spacer(Modifier.height(10.dp))
    }
    Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("돌아가기") }
}

@Composable
private fun SyncedWorkoutSummary(routine: WearRoutinePayload) {
    routine.exercises.forEach { exercise ->
        val target = if (exercise.recordMode == WearRecordMode.SETS) {
            "${exercise.sets.size}세트"
        } else if (exercise.isTabata) {
            "${exercise.intervalWorkSeconds ?: TABATA_WORK_SECONDS}초 / ${exercise.intervalRestSeconds ?: TABATA_REST_SECONDS}초 × ${exercise.intervalRounds ?: TABATA_ROUNDS}"
        } else {
            buildList {
                exercise.targetDurationMin?.let { add("${formatNumber(it)}분") }
                exercise.targetDistanceKm?.let { add("${formatNumber(it)}km") }
            }.joinToString(" · ").ifBlank { "자유 러닝" }
        }
        Text("${exercise.name} · $target", style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
        exercise.targetPaceMinPerKm?.let { pace ->
            Text("목표 페이스 ${formatPace(pace)} /km", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ActiveWorkoutScreen(state: WearUiState, viewModel: WearMainViewModel) {
    val active = state.active ?: return
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(active.paused) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val elapsed = active.elapsedMillis(now) / 1_000
    Text("%02d:%02d".format(elapsed / 60, elapsed % 60), style = MaterialTheme.typography.titleLarge)
    val currentExercise = state.currentExercise
    if (active.routine.usesGpsRunning) {
        currentExercise?.let { RunningExerciseScreen(it, state) }
    } else if (currentExercise?.isTabata == true) {
        TabataExerciseScreen(currentExercise, state.tabataTimer, viewModel)
    } else if (currentExercise?.recordMode == WearRecordMode.CARDIO) {
        GeneralWorkoutScreen(state)
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("♥ ${state.metrics.heartRateBpm?.roundToInt() ?: "--"}")
            Text("${state.metrics.caloriesKcal?.roundToInt() ?: 0} kcal")
        }
        Spacer(Modifier.height(6.dp))
        currentExercise?.let { exercise -> ExerciseEditor(exercise, viewModel) }
    }
    Spacer(Modifier.height(14.dp))
    if (active.exercises.size > 1) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { viewModel.moveExercise(-1) }, modifier = Modifier.weight(1f)) { Text("이전") }
            Button(onClick = { viewModel.moveExercise(1) }, modifier = Modifier.weight(1f)) { Text("다음") }
        }
        Spacer(Modifier.height(10.dp))
    }
    WorkoutControls(
        paused = active.paused,
        onTogglePause = viewModel::togglePause,
        onStop = viewModel::finishWorkout,
    )
    state.metrics.error?.let {
        Spacer(Modifier.height(12.dp))
        Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
    }
}

@Composable
private fun TabataExerciseScreen(
    exercise: WearRoutineExercise,
    timer: TabataTimerState,
    viewModel: WearMainViewModel,
) {
    val isCurrent = timer.targetId == exercise.id
    val phase = when {
        isCurrent -> timer.phase
        (exercise.durationMin ?: 0.0) > 0.0 -> TabataPhase.COMPLETED
        else -> TabataPhase.IDLE
    }
    val remaining = if (isCurrent) timer.remainingSeconds else if (phase == TabataPhase.COMPLETED) 0 else TABATA_WORK_SECONDS
    val round = if (isCurrent) timer.round else if (phase == TabataPhase.COMPLETED) TABATA_ROUNDS else 1
    val label = when (phase) {
        TabataPhase.WORK -> "운동"
        TabataPhase.REST -> "휴식"
        TabataPhase.PAUSED -> "일시정지"
        TabataPhase.COMPLETED -> "완료"
        TabataPhase.IDLE -> "타바타 준비"
    }

    Spacer(Modifier.height(6.dp))
    Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    Text("$round / ${exercise.intervalRounds ?: TABATA_ROUNDS}", style = MaterialTheme.typography.labelSmall)
    Text(formatCountdown(remaining), style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold)
    Text(
        "${exercise.intervalWorkSeconds ?: TABATA_WORK_SECONDS}초 운동 · ${exercise.intervalRestSeconds ?: TABATA_REST_SECONDS}초 휴식",
        style = MaterialTheme.typography.bodySmall,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(10.dp))
    when (phase) {
        TabataPhase.IDLE, TabataPhase.COMPLETED -> FilledIconButton(
            onClick = { viewModel.startTabata(exercise) },
            modifier = Modifier.size(68.dp),
            shapes = IconButtonDefaults.animatedShapes(),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_play_workout),
                contentDescription = if (phase == TabataPhase.COMPLETED) "타바타 다시 시작" else "타바타 시작",
                modifier = Modifier.size(32.dp),
            )
        }
        else -> Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledIconButton(
                onClick = {
                    if (phase == TabataPhase.PAUSED) viewModel.resumeTabata() else viewModel.pauseTabata()
                },
                modifier = Modifier.size(62.dp),
                shapes = IconButtonDefaults.animatedShapes(),
            ) {
                Icon(
                    painter = painterResource(
                        if (phase == TabataPhase.PAUSED) R.drawable.ic_play_workout else R.drawable.ic_pause_workout,
                    ),
                    contentDescription = if (phase == TabataPhase.PAUSED) "타바타 재개" else "타바타 일시정지",
                    modifier = Modifier.size(29.dp),
                )
            }
            Button(onClick = { viewModel.resetTabata(exercise) }) { Text("초기화") }
        }
    }
}

@Composable
private fun WorkoutControls(
    paused: Boolean,
    onTogglePause: () -> Unit,
    onStop: () -> Unit,
) {
    if (!paused) {
        FilledIconButton(
            onClick = onTogglePause,
            modifier = Modifier.size(76.dp),
            shapes = IconButtonDefaults.animatedShapes(),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_pause_workout),
                contentDescription = "운동 일시정지",
                modifier = Modifier.size(34.dp),
            )
        }
        return
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(22.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilledIconButton(
            onClick = onStop,
            modifier = Modifier.size(68.dp),
            shapes = IconButtonDefaults.animatedShapes(),
            colors = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_stop_workout),
                contentDescription = "운동 종료",
                modifier = Modifier.size(30.dp),
            )
        }
        FilledIconButton(
            onClick = onTogglePause,
            modifier = Modifier.size(68.dp),
            shapes = IconButtonDefaults.animatedShapes(),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_play_workout),
                contentDescription = "운동 재개",
                modifier = Modifier.size(32.dp),
            )
        }
    }
}

@Composable
private fun GeneralWorkoutScreen(state: WearUiState) {
    Spacer(Modifier.height(8.dp))
    Text("자유 운동", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    Text("GPS 없이 운동을 기록합니다", style = MaterialTheme.typography.bodySmall)
    Spacer(Modifier.height(10.dp))
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("심박", style = MaterialTheme.typography.labelSmall)
            Text("${state.metrics.heartRateBpm?.roundToInt() ?: "--"}")
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("평균", style = MaterialTheme.typography.labelSmall)
            Text("${state.metrics.averageHeartRateBpm?.roundToInt() ?: "--"}")
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("칼로리", style = MaterialTheme.typography.labelSmall)
            Text("${state.metrics.caloriesKcal?.roundToInt() ?: 0}")
        }
    }
}

@Composable
private fun RunningExerciseScreen(exercise: WearRoutineExercise, state: WearUiState) {
    val distance = state.metrics.distanceKm ?: 0.0
    val currentPace = WearRunningMetrics.currentPaceMinutesPerKm(state.metrics.speedMetersPerSecond)
    val activeDuration = state.metrics.activeDurationMillis.takeIf { it > 0L }
        ?: state.active?.elapsedMillis() ?: 0L
    val averagePace = WearRunningMetrics.averagePaceMinutesPerKm(distance, activeDuration)
    Spacer(Modifier.height(5.dp))
    Text(exercise.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    Text("%.2f km".format(distance), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("현재", style = MaterialTheme.typography.labelSmall)
            Text(formatPace(currentPace))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("평균", style = MaterialTheme.typography.labelSmall)
            Text(formatPace(averagePace))
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("♥ ${state.metrics.heartRateBpm?.roundToInt() ?: "--"}")
        Text("${state.metrics.caloriesKcal?.roundToInt() ?: 0} kcal")
    }
    val target = buildList {
        exercise.targetDistanceKm?.let { add("${formatNumber(it)}km") }
        exercise.targetDurationMin?.let { add("${formatNumber(it)}분") }
    }.joinToString(" · ")
    if (target.isNotBlank()) Text("목표 $target", style = MaterialTheme.typography.bodySmall)
    state.metrics.lastLapDurationMillis?.let { duration ->
        Text(
            "${state.metrics.completedKilometers}km 랩 · ${formatPace(duration / 60_000.0)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
    if (state.metrics.tracking && distance == 0.0) {
        Text("GPS 신호를 찾는 중…", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ExerciseEditor(exercise: WearRoutineExercise, viewModel: WearMainViewModel) {
    Text(exercise.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
    if (exercise.recordMode == WearRecordMode.CARDIO) {
        Text("목표 ${formatNumber(exercise.targetDurationMin)}분 · ${formatNumber(exercise.targetDistanceKm)}km")
        return
    }
    val sets = exercise.sets.sortedBy { it.order }
    val completed = sets.filter { it.completed }
    val next = sets.firstOrNull { !it.completed }
    Text("목표 ${sets.size}세트 · 완료 ${completed.size}", style = MaterialTheme.typography.bodySmall)
    if (next != null) {
        Text("다음 ${next.order}/${sets.size}세트", style = MaterialTheme.typography.titleSmall)
        Text("${next.reps ?: 0}회 · ${formatNumber(next.weightKg)}kg")
        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { viewModel.adjustReps(-1) }, modifier = Modifier.weight(1f)) { Text("횟수−") }
            Button(onClick = { viewModel.adjustReps(1) }, modifier = Modifier.weight(1f)) { Text("횟수+") }
        }
        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { viewModel.adjustWeight(-0.5) }, modifier = Modifier.weight(1f)) { Text("중량−") }
            Button(onClick = { viewModel.adjustWeight(0.5) }, modifier = Modifier.weight(1f)) { Text("중량+") }
        }
        Spacer(Modifier.height(10.dp))
        Button(onClick = { viewModel.toggleSet(next.order) }, modifier = Modifier.fillMaxWidth()) {
            Text("${next.order}세트 기록")
        }
    } else {
        Text("목표 세트를 모두 기록했어요.", color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center)
    }
    if (completed.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        Text("완료 기록", style = MaterialTheme.typography.labelSmall)
        completed.forEach { set ->
            Spacer(Modifier.height(8.dp))
            Button(onClick = { viewModel.toggleSet(set.order) }, modifier = Modifier.fillMaxWidth()) {
                Text("${set.order}세트 · ${set.reps ?: 0}회 · ${formatNumber(set.weightKg)}kg · 수정")
            }
        }
    }
}

private fun formatNumber(value: Double?): String = when {
    value == null -> "0"
    value % 1.0 == 0.0 -> value.toInt().toString()
    else -> "%.1f".format(value)
}

private fun formatCountdown(seconds: Int): String = "%02d:%02d".format(seconds.coerceAtLeast(0) / 60, seconds.coerceAtLeast(0) % 60)

private fun formatPace(paceMinutesPerKm: Double?): String {
    val totalSeconds = paceMinutesPerKm?.takeIf { it.isFinite() && it in 0.0..60.0 }
        ?.times(60.0)?.roundToInt() ?: return "--'--\""
    return "%d'%02d\"".format(totalSeconds / 60, totalSeconds % 60)
}
