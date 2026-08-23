package com.hanshin.healthtask.wear

import android.Manifest
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.hanshin.healthtask.shared.WearActiveSession
import com.hanshin.healthtask.shared.WearRecordMode
import com.hanshin.healthtask.shared.WearRoutineExercise
import com.hanshin.healthtask.shared.WearRoutinePayload
import com.hanshin.healthtask.shared.elapsedMillis
import com.hanshin.healthtask.shared.remainingRestSeconds
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

class WearMainActivity : ComponentActivity() {
    private val viewModel: WearMainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { WearWorkoutApp(viewModel) }
    }
}

@Composable
private fun WearWorkoutApp(viewModel: WearMainViewModel) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val requiredPermissions = remember {
        buildList {
            add(Manifest.permission.ACTIVITY_RECOGNITION)
            if (Build.VERSION.SDK_INT >= 36) add("android.permission.health.READ_HEART_RATE")
            else add(Manifest.permission.BODY_SENSORS)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray()
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val canTrack = result[Manifest.permission.ACTIVITY_RECOGNITION] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        viewModel.startWorkout(canTrack)
    }

    MaterialTheme {
        val effectiveRestSeconds = maxOf(
            state.restRemainingSeconds,
            remainingRestSeconds(state.active?.restEndsAt),
        )
        Column(
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
                .padding(horizontal = 14.dp, vertical = 8.dp).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (state.active != null && effectiveRestSeconds > 0) {
                WearRestTimerScreen(effectiveRestSeconds, viewModel)
            } else if (state.active != null) {
                ActiveWorkoutScreen(state, viewModel)
            } else {
                RoutineScreen(state.routine, onStart = {
                    val missing = requiredPermissions.filter {
                        ContextCompat.checkSelfPermission(context, it) != android.content.pm.PackageManager.PERMISSION_GRANTED
                    }
                    if (missing.isEmpty()) viewModel.startWorkout(trackSensors = true)
                    else launcher.launch(missing.toTypedArray())
                })
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
    Button(onClick = viewModel::skipRestTimer, modifier = Modifier.fillMaxWidth()) { Text("휴식 종료") }
}

@Composable
private fun RoutineScreen(routine: WearRoutinePayload?, onStart: () -> Unit) {
    Text("오늘운동", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(8.dp))
    if (routine == null) {
        Text("휴대폰 앱을 열어\n오늘 루틴을 동기화해 주세요.", textAlign = TextAlign.Center)
        return
    }
    Text(routine.title, style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center)
    Spacer(Modifier.height(6.dp))
    routine.exercises.forEach { exercise ->
        val target = if (exercise.recordMode == WearRecordMode.SETS) {
            "${exercise.sets.size}세트"
        } else {
            "${formatNumber(exercise.targetDurationMin)}분"
        }
        Text("${exercise.order}. ${exercise.name} · $target", style = MaterialTheme.typography.bodySmall)
    }
    Spacer(Modifier.height(10.dp))
    Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) { Text("운동 시작") }
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
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("♥ ${state.metrics.heartRateBpm?.roundToInt() ?: "--"}")
        Text("${state.metrics.caloriesKcal?.roundToInt() ?: 0} kcal")
    }
    Spacer(Modifier.height(6.dp))
    state.currentExercise?.let { exercise -> ExerciseEditor(exercise, viewModel) }
    Spacer(Modifier.height(8.dp))
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Button(onClick = { viewModel.moveExercise(-1) }, modifier = Modifier.weight(1f)) { Text("이전") }
        Button(onClick = { viewModel.moveExercise(1) }, modifier = Modifier.weight(1f)) { Text("다음") }
    }
    Button(onClick = viewModel::togglePause, modifier = Modifier.fillMaxWidth()) {
        Text(if (active.paused) "계속" else "일시정지")
    }
    Button(onClick = viewModel::finishWorkout, modifier = Modifier.fillMaxWidth()) { Text("운동 완료") }
    state.metrics.error?.let { Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center) }
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
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Button(onClick = { viewModel.adjustReps(-1) }, modifier = Modifier.weight(1f)) { Text("횟수−") }
            Button(onClick = { viewModel.adjustReps(1) }, modifier = Modifier.weight(1f)) { Text("횟수+") }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Button(onClick = { viewModel.adjustWeight(-0.5) }, modifier = Modifier.weight(1f)) { Text("중량−") }
            Button(onClick = { viewModel.adjustWeight(0.5) }, modifier = Modifier.weight(1f)) { Text("중량+") }
        }
        Button(onClick = { viewModel.toggleSet(next.order) }, modifier = Modifier.fillMaxWidth()) {
            Text("${next.order}세트 기록")
        }
    } else {
        Text("목표 세트를 모두 기록했어요.", color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center)
    }
    if (completed.isNotEmpty()) {
        Text("완료 기록", style = MaterialTheme.typography.labelSmall)
        completed.forEach { set ->
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
