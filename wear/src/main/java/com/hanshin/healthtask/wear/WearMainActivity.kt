package com.hanshin.healthtask.wear

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.wear.compose.foundation.LocalAmbientModeManager
import androidx.wear.compose.foundation.rememberAmbientModeManager
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.FilledIconButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButtonDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.hanshin.healthtask.shared.WearQuickRunPreset
import com.hanshin.healthtask.shared.WearRunningMetrics
import com.hanshin.healthtask.shared.WearRoutineExercise
import com.hanshin.healthtask.shared.WearRoutinePayload
import com.hanshin.healthtask.shared.WearStartWorkoutRequest
import com.hanshin.healthtask.shared.elapsedMillis
import com.hanshin.healthtask.shared.toRoutinePayload
import com.hanshin.healthtask.shared.usesGpsRunning
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

class WearMainActivity : ComponentActivity() {
    private val viewModel: WearMainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleStartWorkoutIntent(intent)
        setContent {
            val ambientModeManager = rememberAmbientModeManager()
            CompositionLocalProvider(LocalAmbientModeManager provides ambientModeManager) {
                WearWorkoutApp(viewModel)
            }
        }
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

@Composable
private fun WearWorkoutApp(viewModel: WearMainViewModel) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val remoteStartRequest = state.pendingStartRequest?.takeIf { it.isValid() && it.routine.usesGpsRunning }
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

    MaterialTheme {
        val scrollState = rememberScrollState()
        LaunchedEffect(state.active?.sessionId, remoteStartRequest?.requestId) { scrollState.scrollTo(0) }
        WearAmbientLayout(state) {
            Column(
                modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
                    .padding(start = 18.dp, end = 18.dp, top = 24.dp, bottom = 32.dp)
                    .verticalScroll(scrollState),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (state.active != null) {
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
                } else {
                    QuickRunPickerScreen(onStart = { requestStart(PendingWorkoutStart(it)) })
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
private fun RemoteStartScreen(
    request: WearStartWorkoutRequest,
    onStart: () -> Unit,
    onDecline: () -> Unit,
) {
    Spacer(Modifier.height(10.dp))
    Text("폰에서 시작한 러닝", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(6.dp))
    Text(request.routine.title, style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center)
    Text(
        "러닝 측정과 제어를 워치에서 이어갈까요?",
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
internal fun QuickRunPickerScreen(onStart: (WearRoutinePayload) -> Unit) {
    Spacer(Modifier.height(12.dp))
    Text("오늘러닝", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    Text("워치만 차고 달려보세요", style = MaterialTheme.typography.bodySmall)
    Spacer(Modifier.height(12.dp))
    WearQuickRunPreset.entries.forEach { preset ->
        Button(onClick = { onStart(preset.toRoutinePayload()) }, modifier = Modifier.fillMaxWidth()) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(preset.title, fontWeight = FontWeight.Bold)
                Text(preset.subtitle, style = MaterialTheme.typography.labelSmall)
            }
        }
        Spacer(Modifier.height(10.dp))
    }
}

@Composable
private fun ActiveWorkoutScreen(state: WearUiState, viewModel: WearMainViewModel) {
    val active = state.active ?: return
    if (!active.routine.usesGpsRunning) {
        Text("이전 버전의 기록", style = MaterialTheme.typography.titleMedium)
        Text("러닝 전용으로 변경됐습니다. 진행 중이던 이전 운동은 보관한 뒤 러닝을 시작할 수 있어요.",
            textAlign = TextAlign.Center)
        Button(onClick = viewModel::finishWorkout, modifier = Modifier.fillMaxWidth()) { Text("이전 기록 보관 후 종료") }
        return
    }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(active.paused) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val elapsed = active.elapsedMillis(now) / 1_000
    Text("%02d:%02d".format(elapsed / 60, elapsed % 60), style = MaterialTheme.typography.titleLarge)
    state.currentExercise?.let { RunningExerciseScreen(it, state) }
    Spacer(Modifier.height(14.dp))
    if (active.finishedAt != null) {
        Text("측정 종료 · 기록 저장 중", style = MaterialTheme.typography.bodySmall)
        Button(onClick = viewModel::finishWorkout, modifier = Modifier.fillMaxWidth()) { Text("저장 다시 시도") }
    } else WorkoutControls(
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
                contentDescription = "러닝 일시정지",
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
                contentDescription = "러닝 종료",
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
                contentDescription = "러닝 재개",
                modifier = Modifier.size(32.dp),
            )
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
    state.metrics.cadenceStepsPerMinute?.let {
        Text("케이던스 ${it.roundToInt()} 걸음/분", style = MaterialTheme.typography.bodySmall)
    }
    state.metrics.elevationGainMeters?.let {
        Text("누적 상승 ${it.roundToInt()} m", style = MaterialTheme.typography.bodySmall)
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
