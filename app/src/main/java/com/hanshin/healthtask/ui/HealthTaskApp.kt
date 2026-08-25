package com.hanshin.healthtask.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DirectionsRun
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.AddCircle
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.HealthAndSafety
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.LibraryAdd
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.GpsFixed
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Rowing
import androidx.compose.material.icons.rounded.SelfImprovement
import androidx.compose.material.icons.rounded.AccessibilityNew
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.TipsAndUpdates
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.health.connect.client.PermissionController
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.hanshin.healthtask.R
import com.hanshin.healthtask.data.SeedData
import com.hanshin.healthtask.data.db.ExerciseEntity
import com.hanshin.healthtask.data.db.HealthMeasurementEntity
import com.hanshin.healthtask.data.db.PlanSlotEntity
import com.hanshin.healthtask.data.db.RoutineWithItems
import com.hanshin.healthtask.data.db.WorkoutItemWithSets
import com.hanshin.healthtask.data.db.WorkoutSessionWithItems
import com.hanshin.healthtask.domain.HealthMetricType
import com.hanshin.healthtask.domain.INBODY_PACKAGE
import com.hanshin.healthtask.domain.ExerciseCategory
import com.hanshin.healthtask.domain.PlannedWorkoutType
import com.hanshin.healthtask.domain.RecordMode
import com.hanshin.healthtask.domain.TrainingGoalType
import com.hanshin.healthtask.domain.WorkoutSource
import com.hanshin.healthtask.domain.completedPlanSlotIds
import com.hanshin.healthtask.domain.isRun
import com.hanshin.healthtask.domain.isExternal
import com.hanshin.healthtask.health.HealthConnectStatus
import com.hanshin.healthtask.shared.TABATA_EXERCISE_ID
import com.hanshin.healthtask.shared.TABATA_REST_SECONDS
import com.hanshin.healthtask.shared.TABATA_ROUNDS
import com.hanshin.healthtask.shared.TABATA_WORK_SECONDS
import com.hanshin.healthtask.shared.TabataPhase
import com.hanshin.healthtask.shared.TabataTimerState
import com.hanshin.healthtask.running.RunningPhase
import com.hanshin.healthtask.running.RunningLap
import com.hanshin.healthtask.running.RunningLapCodec
import com.hanshin.healthtask.running.RunningPoint
import com.hanshin.healthtask.running.RunningRouteCodec
import com.hanshin.healthtask.running.RunningUiState
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.cos
import kotlin.math.min

private data class Tab(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)
private val tabs = listOf(
    Tab("today", "오늘", Icons.Rounded.CalendarMonth),
    Tab("running", "러닝", Icons.AutoMirrored.Rounded.DirectionsRun),
    Tab("routines", "계획", Icons.Rounded.FitnessCenter),
    Tab("history", "기록", Icons.Rounded.Insights),
    Tab("settings", "설정", Icons.Rounded.Tune),
)

@Composable
fun HealthTaskRoot(vm: MainViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val rootNav = rememberNavController()
    val onboarded = state.profile?.onboardingDone == true
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.onForeground() }
    LaunchedEffect(state.profile?.onboardingDone) {
        rootNav.navigate(if (onboarded) "app" else "onboarding") {
            popUpTo(rootNav.graph.findStartDestination().id) { inclusive = true }
            launchSingleTop = true
        }
    }
    NavHost(rootNav, startDestination = "loading") {
        composable("loading") { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
        composable("onboarding") { OnboardingPage(vm) }
        composable("app") { MainScaffold(state, vm) }
    }
}

@Composable
private fun OnboardingPage(vm: MainViewModel) {
    OnboardingContent { goal, templates -> vm.finishOnboarding(goal, templates) }
}

@Composable
internal fun OnboardingContent(onFinish: (Int, Boolean) -> Unit) {
    var goal by rememberSaveable { mutableIntStateOf(3) }
    var templates by rememberSaveable { mutableStateOf(true) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("오늘운동", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Text("계정 없이 이 기기에만 운동을 기록합니다. 삼성 헬스 연동은 나중에 설정에서 선택할 수 있어요.")
        Spacer(Modifier.height(32.dp))
        SectionTitle("일주일에 몇 번 운동할까요?")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (1..7).forEach { number ->
                AssistChip(onClick = { goal = number }, label = { Text("$number") }, leadingIcon = if (goal == number) {{ Icon(Icons.Rounded.Check, "선택됨") }} else null)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(templates, onCheckedChange = { templates = it })
            Text("추천 루틴을 함께 만들기")
        }
        Spacer(Modifier.height(20.dp))
        Button(onClick = { onFinish(goal, templates) }, modifier = Modifier.fillMaxWidth().testTag("onboarding-start")) {
            Icon(Icons.Rounded.PlayArrow, null)
            Spacer(Modifier.width(6.dp))
            Text("시작하기")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScaffold(state: MainUiState, vm: MainViewModel) {
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val current = nav.currentBackStackEntryFlow.collectAsStateWithLifecycle(nav.currentBackStackEntry).value?.destination?.route ?: "today"
    LaunchedEffect(state.running.isActive) {
        if (state.running.isActive && current != "running") {
            nav.navigate("running") { launchSingleTop = true }
        }
    }
    LaunchedEffect(state.selectedRunPlanSlotId) {
        if (state.selectedRunPlanSlotId != null && current != "running") {
            nav.navigate("running") { launchSingleTop = true }
        }
    }
    LaunchedEffect(state.activeSession?.session?.id) {
        if (state.activeSession != null && current != "today") {
            nav.navigate("today") { launchSingleTop = true }
        }
    }
    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); vm.consumeMessage() }
    }
    Scaffold(
        topBar = { TopAppBar(title = { Text(tabs.firstOrNull { it.route == current }?.label ?: "오늘운동", fontWeight = FontWeight.Bold) }) },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar(Modifier.navigationBarsPadding()) {
                tabs.forEach { tab ->
                    NavigationBarItem(
                        selected = current == tab.route,
                        onClick = { nav.navigate(tab.route) { popUpTo(nav.graph.findStartDestination().id) { saveState = true }; launchSingleTop = true; restoreState = true } },
                        icon = { Icon(tab.icon, null) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = "today", modifier = Modifier.padding(padding)) {
            composable("today") { TodayPage(state, vm, onOpenPlans = { nav.navigate("routines") }) }
            composable("running") { RunningPage(state, vm) }
            composable("routines") { RoutinesPage(state, vm) }
            composable("history") { HistoryPage(state, vm) }
            composable("settings") { SettingsPage(state, vm) }
        }
    }
    if (state.restTimer.isRunning) {
        FullScreenRestTimerDialog(state.restTimer, vm)
    }
}

@Composable
private fun FullScreenRestTimerDialog(timer: RestTimerUiState, vm: MainViewModel) {
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize().testTag("rest-timer-modal"),
            color = MaterialTheme.colorScheme.tertiaryContainer,
        ) {
            Column(
                modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(28.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("휴식 중", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                Text(
                    formatCountdown(timer.remainingSeconds),
                    style = MaterialTheme.typography.displayLarge,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(20.dp))
                LinearProgressIndicator(
                    progress = { timer.remainingSeconds.toFloat() / timer.totalSeconds.coerceAtLeast(1) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    "휴식을 종료하거나 시간이 끝날 때까지 운동 입력이 잠깁니다.",
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                Spacer(Modifier.height(28.dp))
                OutlinedButton(onClick = { vm.addRestTime() }, modifier = Modifier.fillMaxWidth()) {
                    Text("+30초")
                }
                Button(
                    onClick = vm::skipRestTimer,
                    modifier = Modifier.fillMaxWidth().testTag("end-rest-timer"),
                ) {
                    Text("휴식 종료")
                }
            }
        }
    }
}

@Composable
private fun TodayPage(state: MainUiState, vm: MainViewModel, onOpenPlans: () -> Unit) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            AppCard {
                val planned = state.planProgress
                val completed = planned?.completed ?: state.progress.completed
                val goal = planned?.goal ?: state.progress.goal
                Text("이번 주 $completed / ${goal}회", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { (completed.toFloat() / goal.coerceAtLeast(1)).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                if (planned != null) {
                    Text(
                        "근력 ${planned.strengthCompleted}/${planned.strengthGoal}회 · 러닝 ${planned.runningCompleted}/${planned.runningGoal}회",
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
                Text("연속 ${state.progress.streakDays}일", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        val active = state.activeSession
        if (active != null) {
            item { Text("진행 중인 운동", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            item { ActiveSessionCard(active, state.restTimerSeconds, state.tabataTimer, vm) }
        } else {
            item { Text("오늘 추천", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            item {
                AppCard {
                    val slot = state.todayPlanSlot
                    val routine = state.nextRoutine.takeIf { slot == null && state.activeTrainingPlan == null }
                    if (slot != null) {
                        PlanSlotSummary(slot)
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = {
                                if (slot.workoutType == PlannedWorkoutType.STRENGTH && slot.routineId == null) onOpenPlans()
                                else vm.selectPlanSlot(slot)
                            },
                            modifier = Modifier.fillMaxWidth().testTag("start-today-plan"),
                        ) {
                            Icon(Icons.Rounded.PlayArrow, null)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                when {
                                    slot.workoutType.isRun -> "러닝으로 이동"
                                    slot.routineId == null -> "루틴 만들기"
                                    else -> "운동 시작"
                                }
                            )
                        }
                        TextButton(onClick = onOpenPlans, modifier = Modifier.fillMaxWidth()) {
                            Text("이번 주 전체 계획 보기")
                        }
                    } else if (state.activeTrainingPlan != null) {
                        Text("이번 주 계획 완료", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("계획한 러닝과 근력운동을 모두 마쳤어요.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = onOpenPlans, modifier = Modifier.fillMaxWidth()) { Text("계획 확인") }
                    } else if (routine == null) {
                        Text("먼저 주간 계획을 만들어 주세요.")
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = onOpenPlans, modifier = Modifier.fillMaxWidth()) { Text("계획 만들기") }
                    } else {
                        Text(routine.routine.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        routine.items.sortedBy { it.orderIndex }.forEach { item ->
                            val exercise = state.exercises.firstOrNull { it.id == item.exerciseId }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                ExerciseIconBadge(item.exerciseId, item.category, size = 30.dp)
                                Spacer(Modifier.width(8.dp))
                                Text(exercise?.name ?: item.exerciseId)
                            }
                            Spacer(Modifier.height(5.dp))
                        }
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { vm.startSession(routine.routine.id) }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Rounded.PlayArrow, null)
                            Spacer(Modifier.width(6.dp))
                            Text("운동 시작")
                        }
                    }
                }
            }
        }
        val external = state.sessions.firstOrNull { it.session.source.isExternal() }
        if (external != null) item {
            AppCard {
                Text("최근 외부 운동 · ${sourceLabel(external.session.source)}", fontWeight = FontWeight.Bold)
                Text("${external.session.title} · ${durationText(external)}")
                Text(external.session.sessionDate, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun PlanSlotSummary(slot: PlanSlotEntity, completed: Boolean = false) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Surface(
            modifier = Modifier.size(44.dp),
            shape = CircleShape,
            color = if (completed) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.primaryContainer,
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(
                    if (completed) Icons.Rounded.Check else if (slot.workoutType.isRun) Icons.AutoMirrored.Rounded.DirectionsRun else Icons.Rounded.FitnessCenter,
                    null,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(slot.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(planSlotDetail(slot), color = MaterialTheme.colorScheme.onSurfaceVariant)
            slot.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

private fun planSlotDetail(slot: PlanSlotEntity): String = buildList {
    slot.preferredDayOfWeek?.let { day ->
        add(listOf("월", "화", "수", "목", "금", "토", "일").getOrNull(day - 1)?.let { "${it}요일 권장" } ?: "주간")
    }
    add(when (slot.workoutType) {
        PlannedWorkoutType.STRENGTH -> "근력"
        PlannedWorkoutType.EASY_RUN -> "이지런"
        PlannedWorkoutType.QUALITY_RUN -> "빠른 러닝"
        PlannedWorkoutType.LONG_RUN -> "장거리 러닝"
    })
    slot.targetDurationMin?.let { add("${formatNumber(it)}분") }
    slot.targetDistanceKm?.let { add("${formatNumber(it)}km") }
    slot.targetPaceMinPerKm?.let { add("${formatRunPace(it)}/km") }
}.joinToString(" · ")

@Composable
private fun RunningPage(state: MainUiState, vm: MainViewModel) {
    val context = LocalContext.current
    var permissionRevision by remember { mutableIntStateOf(0) }
    var confirmFinish by remember { mutableStateOf(false) }
    var startFreeAfterPermission by remember { mutableStateOf(false) }
    val hasLocationPermission = remember(permissionRevision) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        permissionRevision++
        val locationGranted = granted[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            granted[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (locationGranted) {
            if (startFreeAfterPermission) vm.startFreeRun() else vm.startRun()
        } else vm.showMessage("러닝 기록에는 위치 권한이 필요합니다.")
        startFreeAfterPermission = false
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        state.currentRunPlanSlot?.let { slot ->
            item {
                AppCard {
                    Text("계획 러닝", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(4.dp))
                    PlanSlotSummary(slot)
                }
            }
        }
        item {
            AppCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        modifier = Modifier.size(52.dp),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Icon(Icons.AutoMirrored.Rounded.DirectionsRun, null, modifier = Modifier.size(30.dp))
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            when (state.running.phase) {
                                RunningPhase.IDLE -> "새 러닝"
                                RunningPhase.ACQUIRING_GPS -> "GPS 연결 중"
                                RunningPhase.RUNNING -> "러닝 중"
                                RunningPhase.AUTO_PAUSED -> "자동 일시정지"
                                RunningPhase.PAUSED -> "일시정지"
                                RunningPhase.COMPLETED -> "러닝 완료"
                            },
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            when {
                                !state.running.gpsAvailable -> "기기의 위치 서비스를 켜 주세요."
                                state.running.phase == RunningPhase.ACQUIRING_GPS -> "정확한 GPS 신호를 기다리고 있어요."
                                state.running.phase == RunningPhase.AUTO_PAUSED -> "다시 달리면 자동으로 기록을 재개합니다."
                                state.running.accuracyMeters != null -> "GPS 오차 약 ${state.running.accuracyMeters.toInt()}m"
                                else -> "시간·거리·페이스를 휴대폰에서 직접 기록합니다."
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        item {
            AppCard {
                Text(
                    formatRunDuration(state.running.elapsedMillis),
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.displayMedium,
                    fontWeight = FontWeight.Bold,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                Spacer(Modifier.height(18.dp))
                Row(Modifier.fillMaxWidth()) {
                    RunningMetric("거리", String.format(Locale.KOREA, "%.2f", state.running.distanceMeters / 1_000.0), "km", Modifier.weight(1f))
                    RunningMetric("현재 페이스", formatRunPace(state.running.currentPaceMinPerKm), "/km", Modifier.weight(1f))
                    RunningMetric("평균 페이스", formatRunPace(state.running.averagePaceMinPerKm), "/km", Modifier.weight(1f))
                }
            }
        }

        item {
            RunningLapsCard(state.running)
        }

        item {
            AppCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.GpsFixed, null)
                    Spacer(Modifier.width(7.dp))
                    Text("이동 경로", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(10.dp))
                RunningRoutePreview(
                    state.running.route,
                    Modifier.fillMaxWidth().height(if (state.running.phase == RunningPhase.IDLE) 150.dp else 220.dp),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "좌표는 이 기기에만 저장하며, 지도 API 없이 실제 이동 궤적만 표시합니다.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        item {
            when (state.running.phase) {
                RunningPhase.IDLE -> {
                    Button(
                        onClick = {
                            startFreeAfterPermission = false
                            if (hasLocationPermission) vm.startRun() else permissionLauncher.launch(arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION,
                                Manifest.permission.POST_NOTIFICATIONS,
                            ))
                        },
                        enabled = state.activeSession == null,
                        modifier = Modifier.fillMaxWidth().height(58.dp).testTag("start-run"),
                    ) {
                        Icon(Icons.Rounded.PlayArrow, null)
                        Spacer(Modifier.width(7.dp))
                        Text(if (state.currentRunPlanSlot != null) "계획대로 시작" else "자유 러닝 시작")
                    }
                    if (state.currentRunPlanSlot != null) {
                        OutlinedButton(
                            onClick = {
                                startFreeAfterPermission = true
                                if (hasLocationPermission) {
                                    vm.startFreeRun()
                                    startFreeAfterPermission = false
                                } else permissionLauncher.launch(arrayOf(
                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                    Manifest.permission.ACCESS_COARSE_LOCATION,
                                    Manifest.permission.POST_NOTIFICATIONS,
                                ))
                            },
                            enabled = state.activeSession == null,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("계획 없이 자유 러닝") }
                    }
                    if (state.activeSession != null) {
                        Text("진행 중인 근력운동을 완료하면 러닝을 시작할 수 있어요.", color = MaterialTheme.colorScheme.error)
                    }
                }
                RunningPhase.ACQUIRING_GPS, RunningPhase.RUNNING, RunningPhase.AUTO_PAUSED, RunningPhase.PAUSED -> {
                    val paused = state.running.phase == RunningPhase.PAUSED || state.running.phase == RunningPhase.AUTO_PAUSED
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = if (paused) vm::resumeRun else vm::pauseRun,
                            modifier = Modifier.weight(1f).height(58.dp),
                        ) {
                            Icon(if (paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, null)
                            Spacer(Modifier.width(5.dp))
                            Text(if (paused) "재개" else "일시정지")
                        }
                        OutlinedButton(
                            onClick = { confirmFinish = true },
                            modifier = Modifier.weight(1f).height(58.dp).testTag("finish-run"),
                        ) {
                            Icon(Icons.Rounded.Stop, null)
                            Spacer(Modifier.width(5.dp))
                            Text("완료")
                        }
                    }
                }
                RunningPhase.COMPLETED -> {
                    Button(onClick = vm::newRun, modifier = Modifier.fillMaxWidth().height(58.dp)) {
                        Icon(Icons.Rounded.Replay, null)
                        Spacer(Modifier.width(6.dp))
                        Text("새 러닝")
                    }
                    Text(
                        "완료 기록은 기록 탭에 저장되고 Health Connect 연결 시 거리와 함께 전송됩니다.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    if (confirmFinish) {
        AlertDialog(
            onDismissRequest = { confirmFinish = false },
            title = { Text("러닝을 완료할까요?") },
            text = { Text("현재 시간·거리·평균 페이스와 이동 경로를 저장합니다.") },
            confirmButton = {
                TextButton(onClick = { confirmFinish = false; vm.finishRun() }) { Text("완료") }
            },
            dismissButton = { TextButton(onClick = { confirmFinish = false }) { Text("계속 달리기") } },
        )
    }
}

@Composable
private fun RunningLapsCard(running: RunningUiState) {
    AppCard {
        Text("1km 랩", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            "1km마다 구간별 시간과 평균 페이스를 기록합니다.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))
        if (running.laps.isEmpty() && running.distanceMeters < 1.0) {
            Text("첫 번째 랩을 기다리고 있어요.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            running.laps.takeLast(5).forEach { lap -> RunningLapRow(lap) }
            if (running.phase != RunningPhase.COMPLETED && running.currentLapDistanceMeters >= 1.0) {
                RunningLapRow(
                    RunningLap(
                        index = running.laps.count { it.isComplete } + 1,
                        distanceMeters = running.currentLapDistanceMeters,
                        durationMillis = running.currentLapElapsedMillis,
                        totalElapsedMillis = running.elapsedMillis,
                    ),
                    inProgress = true,
                )
            }
        }
    }
}

@Composable
private fun RunningLapRow(lap: RunningLap, inProgress: Boolean = false) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (lap.isComplete) "${lap.index} km" else String.format(Locale.KOREA, "%.2f km", lap.distanceMeters / 1_000.0),
            modifier = Modifier.weight(1f),
            fontWeight = FontWeight.SemiBold,
        )
        if (inProgress) {
            Text("진행 중 · ${formatRunDuration(lap.durationMillis)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text("${formatRunPace(lap.averagePaceMinPerKm)}/km")
        }
    }
}

@Composable
private fun RunningMetric(label: String, value: String, unit: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(unit, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun RunningRoutePreview(points: List<RunningPoint>, modifier: Modifier = Modifier) {
    val background = MaterialTheme.colorScheme.surface
    val routeColor = MaterialTheme.colorScheme.primary
    val startColor = MaterialTheme.colorScheme.secondary
    Box(
        modifier = modifier.background(background, RoundedCornerShape(18.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (points.size < 2) {
            Text(
                if (points.isEmpty()) "러닝을 시작하면 경로가 표시됩니다." else "GPS 이동을 기다리는 중…",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Canvas(Modifier.fillMaxSize().padding(18.dp)) {
                val centerLatitude = points.map { it.latitude }.average()
                val longitudeScale = cos(Math.toRadians(centerLatitude)).coerceAtLeast(0.1)
                val coordinates = points.map { point ->
                    (point.longitude * longitudeScale) to point.latitude
                }
                val minX = coordinates.minOf { it.first }
                val maxX = coordinates.maxOf { it.first }
                val minY = coordinates.minOf { it.second }
                val maxY = coordinates.maxOf { it.second }
                val widthRange = (maxX - minX).coerceAtLeast(0.000001)
                val heightRange = (maxY - minY).coerceAtLeast(0.000001)
                val scale = min(size.width / widthRange, size.height / heightRange) * 0.9
                val usedWidth = widthRange * scale
                val usedHeight = heightRange * scale
                fun offset(coordinate: Pair<Double, Double>) = Offset(
                    x = ((coordinate.first - minX) * scale + (size.width - usedWidth) / 2.0).toFloat(),
                    y = ((maxY - coordinate.second) * scale + (size.height - usedHeight) / 2.0).toFloat(),
                )
                val path = Path()
                coordinates.forEachIndexed { index, coordinate ->
                    val target = offset(coordinate)
                    if (index == 0) path.moveTo(target.x, target.y) else path.lineTo(target.x, target.y)
                }
                drawPath(path, routeColor, style = Stroke(width = 7f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawCircle(startColor, radius = 8f, center = offset(coordinates.first()))
                drawCircle(routeColor, radius = 9f, center = offset(coordinates.last()))
            }
        }
    }
}

@Composable
private fun ActiveSessionCard(
    session: WorkoutSessionWithItems,
    configuredRestSeconds: Int,
    tabataTimer: TabataTimerState,
    vm: MainViewModel,
) {
    AppCard {
        Text(session.session.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("시작 ${formatTime(session.session.startedAt)}")
        Spacer(Modifier.height(12.dp))
        Text(
            "세트를 기록하면 ${formatCountdown(configuredRestSeconds)} 휴식 화면이 자동으로 열립니다.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        session.items.sortedBy { it.item.orderIndex }.forEach { item ->
            WorkoutInput(item, tabataTimer, vm)
            HorizontalDivider(Modifier.padding(vertical = 10.dp))
        }
        Button(onClick = { vm.finishSession(session.session.id) }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Rounded.CheckCircle, null)
            Spacer(Modifier.width(6.dp))
            Text("운동 완료")
        }
    }
}

@Composable
private fun WorkoutInput(full: WorkoutItemWithSets, tabataTimer: TabataTimerState, vm: MainViewModel) {
    val context = LocalContext.current
    var guide by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        ExerciseIconBadge(full.item.exerciseId, full.item.category)
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = { guide = true }, contentPadding = PaddingValues(0.dp)) {
            Text(full.item.exerciseName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Icon(Icons.Rounded.TipsAndUpdates, "운동 가이드", Modifier.padding(start = 5.dp).size(18.dp))
        }
    }
    if (full.item.exerciseId == TABATA_EXERCISE_ID) {
        TabataTimerCard(full, tabataTimer, vm)
    } else if (full.item.recordMode == RecordMode.SETS) {
        StrengthSetRecorder(full, vm)
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField("분", full.item.durationMin) { vm.updateWorkoutItem(full.item.copy(durationMin = it)) }
            NumberField("km", full.item.distanceKm) { vm.updateWorkoutItem(full.item.copy(distanceKm = it)) }
        }
    }
    if (guide) {
        val exercise = SeedData.exercises.firstOrNull { it.id == full.item.exerciseId }
        GuideDialog(exercise, onDismiss = { guide = false }, onOpen = { url -> context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) })
    }
}

@Composable
private fun TabataTimerCard(
    full: WorkoutItemWithSets,
    timer: TabataTimerState,
    vm: MainViewModel,
) {
    val isCurrent = timer.targetId == full.item.id
    val phase = when {
        isCurrent -> timer.phase
        (full.item.durationMin ?: 0.0) > 0.0 -> TabataPhase.COMPLETED
        else -> TabataPhase.IDLE
    }
    val remaining = when {
        isCurrent -> timer.remainingSeconds
        phase == TabataPhase.COMPLETED -> 0
        else -> TABATA_WORK_SECONDS
    }
    val round = if (isCurrent) timer.round else if (phase == TabataPhase.COMPLETED) TABATA_ROUNDS else 1
    val phaseTotal = when (phase) {
        TabataPhase.REST -> TABATA_REST_SECONDS
        else -> TABATA_WORK_SECONDS
    }
    val progress = when (phase) {
        TabataPhase.COMPLETED -> 1f
        TabataPhase.IDLE -> 0f
        else -> 1f - remaining.toFloat() / phaseTotal.coerceAtLeast(1)
    }.coerceIn(0f, 1f)
    val label = when (phase) {
        TabataPhase.WORK -> "운동"
        TabataPhase.REST -> "휴식"
        TabataPhase.PAUSED -> "일시정지"
        TabataPhase.COMPLETED -> "완료"
        TabataPhase.IDLE -> "준비"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when (phase) {
                TabataPhase.WORK -> MaterialTheme.colorScheme.errorContainer
                TabataPhase.REST -> MaterialTheme.colorScheme.secondaryContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("$label · $round / $TABATA_ROUNDS 라운드", fontWeight = FontWeight.Bold)
            Text(
                formatCountdown(remaining),
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.Bold,
            )
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            Text(
                "${TABATA_WORK_SECONDS}초 운동 · ${TABATA_REST_SECONDS}초 휴식 × $TABATA_ROUNDS",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when (phase) {
                TabataPhase.IDLE, TabataPhase.COMPLETED -> Button(
                    onClick = { vm.startTabata(full.item) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Rounded.PlayArrow, null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (phase == TabataPhase.COMPLETED) "다시 시작" else "타바타 시작")
                }
                else -> Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Button(
                        onClick = {
                            if (phase == TabataPhase.PAUSED) vm.resumeTabata() else vm.pauseTabata()
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(if (phase == TabataPhase.PAUSED) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, null)
                        Spacer(Modifier.width(4.dp))
                        Text(if (phase == TabataPhase.PAUSED) "재개" else "일시정지")
                    }
                    OutlinedButton(
                        onClick = { vm.resetTabata(full.item) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Rounded.Replay, null)
                        Spacer(Modifier.width(4.dp))
                        Text("초기화")
                    }
                }
            }
        }
    }
}

@Composable
private fun StrengthSetRecorder(full: WorkoutItemWithSets, vm: MainViewModel) {
    val sets = full.sets.sortedBy { it.orderIndex }
    val completed = sets.filter { it.completed }
    val next = sets.firstOrNull { !it.completed }
    val targetSets = sets.size
    val targetReps = sets.firstNotNullOfOrNull { it.plannedReps }

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                buildString {
                    append("목표 ${targetSets}세트")
                    targetReps?.let { append(" · 세트당 ${it}회") }
                },
                fontWeight = FontWeight.Bold,
            )
            Text("운동한 세트만 아래 기록에 쌓입니다.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("${completed.size} / $targetSets", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
    }
    Spacer(Modifier.height(8.dp))
    LinearProgressIndicator(
        progress = { completed.size.toFloat() / targetSets.coerceAtLeast(1) },
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(12.dp))

    if (next != null) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("다음 ${next.orderIndex} / $targetSets 세트", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField(
                        label = "중량 (kg)",
                        value = next.actualWeightKg,
                        modifier = Modifier.weight(1f),
                    ) { value -> vm.updateSet(next.copy(actualWeightKg = value)) }
                    NumberField(
                        label = "횟수",
                        value = next.actualReps?.toDouble(),
                        modifier = Modifier.weight(1f),
                    ) { value -> vm.updateSet(next.copy(actualReps = value?.toInt())) }
                }
                Button(
                    onClick = { vm.setSetCompleted(next, true) },
                    enabled = (next.actualReps ?: 0) > 0,
                    modifier = Modifier.fillMaxWidth().testTag("record-set-${full.item.id}-${next.orderIndex}"),
                ) {
                    Icon(Icons.Rounded.CheckCircle, null)
                    Spacer(Modifier.width(6.dp))
                    Text("${next.orderIndex}세트 기록")
                }
            }
        }
    } else {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        ) {
            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.secondary)
                Spacer(Modifier.width(8.dp))
                Text("목표 세트를 모두 기록했어요.", fontWeight = FontWeight.Bold)
            }
        }
    }

    if (completed.isNotEmpty()) {
        Spacer(Modifier.height(14.dp))
        Text("완료 기록", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        completed.forEach { set ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("${set.orderIndex}세트", fontWeight = FontWeight.Bold)
                    Text(setRecordSummary(set), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { vm.setSetCompleted(set, false) }) {
                    Icon(Icons.Rounded.Edit, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("수정")
                }
            }
        }
    }
}

@Composable
private fun NumberField(
    label: String,
    value: Double?,
    modifier: Modifier = Modifier.width(84.dp),
    onChange: (Double?) -> Unit,
) {
    var text by remember(value) { mutableStateOf(value?.let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() } ?: "") }
    OutlinedTextField(
        value = text,
        onValueChange = { new -> text = new; onChange(new.toDoubleOrNull()) },
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier,
        singleLine = true,
    )
}

@Composable
private fun GuideDialog(exercise: ExerciseEntity?, onDismiss: () -> Unit, onOpen: (String) -> Unit) {
    var motionAngle by rememberSaveable(exercise?.id) { mutableStateOf("front") }
    val motionResource = when (exercise?.id) {
        "squat" -> if (motionAngle == "front") R.raw.squat_front else R.raw.squat_side
        "flat-dumbbell-press" -> if (motionAngle == "front") {
            R.raw.flat_dumbbell_press_front
        } else {
            R.raw.flat_dumbbell_press_side
        }
        "one-arm-dumbbell-row" -> if (motionAngle == "front") {
            R.raw.one_arm_dumbbell_row_front
        } else {
            R.raw.one_arm_dumbbell_row_side
        }
        "shoulder-press" -> if (motionAngle == "front") {
            R.raw.shoulder_press_front
        } else {
            R.raw.shoulder_press_side
        }
        "hammer-curl" -> if (motionAngle == "front") {
            R.raw.hammer_curl_front
        } else {
            R.raw.hammer_curl_side
        }
        "dumbbell-goblet-squat" -> if (motionAngle == "front") {
            R.raw.dumbbell_goblet_squat_front
        } else {
            R.raw.dumbbell_goblet_squat_side
        }
        else -> null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(exercise?.name ?: "운동 가이드") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (motionResource != null) {
                    Text("3D 모션 샘플", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip(onClick = { motionAngle = "front" }, label = { Text(if (motionAngle == "front") "✓ 정면" else "정면") })
                        AssistChip(onClick = { motionAngle = "side" }, label = { Text(if (motionAngle == "side") "✓ 측면" else "측면") })
                    }
                    MotionGuidePlayer(motionResource)
                    Text("오프라인 3D 가이드 · 자동 반복", style = MaterialTheme.typography.labelSmall)
                } else exercise?.guideAssetPath?.let { asset ->
                    AndroidView(
                        factory = { context -> android.webkit.WebView(context).apply { settings.javaScriptEnabled = false } },
                        update = { it.loadUrl("file:///android_asset/$asset") },
                        modifier = Modifier.fillMaxWidth().height(180.dp),
                    )
                }
                Text(exercise?.guideHeadline ?: "편안한 범위에서 정확한 자세를 우선하세요.")
                exercise?.guideCues?.split("\n")?.forEach { Text("• $it") }
                exercise?.guideWarning?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            exercise?.guideVideoUrl?.let { url -> TextButton(onClick = { onOpen(url) }) { Text("YouTube 열기") } }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
    )
}

@Composable
@androidx.annotation.OptIn(UnstableApi::class)
private fun MotionGuidePlayer(resourceId: Int) {
    val context = LocalContext.current
    val player = remember(context) {
        ExoPlayer.Builder(context).build().apply {
            repeatMode = Player.REPEAT_MODE_ONE
            volume = 0f
            playWhenReady = true
        }
    }
    LaunchedEffect(player, resourceId) {
        val uri = "android.resource://${context.packageName}/$resourceId".toUri()
        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()
        player.play()
    }
    DisposableEffect(player) {
        onDispose { player.release() }
    }
    AndroidView(
        factory = { viewContext ->
            PlayerView(viewContext).apply {
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                this.player = player
            }
        },
        update = { it.player = player },
        modifier = Modifier.fillMaxWidth().height(260.dp),
    )
}

@Composable
private fun RoutinesPage(state: MainUiState, vm: MainViewModel) {
    var editing by remember { mutableStateOf<RoutineWithItems?>(null) }
    var creating by remember { mutableStateOf(false) }
    var pendingGoal by remember { mutableStateOf<TrainingGoalType?>(null) }
    val activePlan = state.activeTrainingPlan
    val completedSlotIds = completedPlanSlotIds(state.sessions.map { it.session })
    LazyColumn(
        Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            AppCard {
                Text("이번 주 계획", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    activePlan?.plan?.name ?: "아직 주간 계획이 없어요.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        TrainingGoalType.BALANCED to "균형형",
                        TrainingGoalType.RUNNING to "러닝 중심",
                        TrainingGoalType.STRENGTH to "근력 중심",
                    ).forEach { (goal, label) ->
                        AssistChip(
                            onClick = {
                                if (activePlan == null) vm.rebuildTrainingPlan(goal)
                                else if (activePlan.plan.goalType != goal) pendingGoal = goal
                            },
                            label = { Text(label) },
                            leadingIcon = if (activePlan?.plan?.goalType == goal) {{ Icon(Icons.Rounded.Check, null) }} else null,
                        )
                    }
                }
                state.planProgress?.let { progress ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "근력 ${progress.strengthCompleted}/${progress.strengthGoal}회 · 러닝 ${progress.runningCompleted}/${progress.runningGoal}회",
                        color = MaterialTheme.colorScheme.secondary,
                    )
                    LinearProgressIndicator(
                        progress = { progress.completed.toFloat() / progress.goal.coerceAtLeast(1) },
                        modifier = Modifier.fillMaxWidth().padding(top = 7.dp),
                    )
                }
            }
        }
        if (activePlan != null) {
            items(activePlan.slots.sortedBy { it.orderIndex }, key = { it.id }) { slot ->
                val completed = slot.id in completedSlotIds
                AppCard {
                    Text("${slot.orderIndex}번째 운동", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(4.dp))
                    PlanSlotSummary(slot, completed)
                    if (!completed) {
                        Spacer(Modifier.height(9.dp))
                        OutlinedButton(
                            onClick = {
                                if (slot.routineId == null && slot.workoutType == PlannedWorkoutType.STRENGTH) creating = true
                                else vm.selectPlanSlot(slot)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                if (slot.workoutType == PlannedWorkoutType.STRENGTH && slot.routineId == null) "근력 루틴 만들기"
                                else "이 운동 시작"
                            )
                        }
                    }
                }
            }
        }
        item {
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Text("운동 루틴", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("계획에서 재사용할 웨이트 구성을 관리합니다.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            Button(onClick = { creating = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.AddCircle, null)
                Spacer(Modifier.width(6.dp))
                Text("루틴 만들기")
            }
        }
        items(state.routines, key = { it.routine.id }) { routine ->
            AppCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(routine.routine.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("${routine.items.size}개 운동")
                        Spacer(Modifier.height(7.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            routine.items.sortedBy { it.orderIndex }.take(6).forEach { item ->
                                ExerciseIconBadge(item.exerciseId, item.category, size = 28.dp)
                            }
                        }
                        Spacer(Modifier.height(7.dp))
                        TextButton(
                            onClick = { vm.startSession(routine.routine.id) },
                            enabled = state.activeSession == null && !state.running.isActive,
                        ) {
                            Icon(Icons.Rounded.PlayArrow, null)
                            Spacer(Modifier.width(5.dp))
                            Text("계획과 별도로 시작")
                        }
                    }
                    IconButton(onClick = { editing = routine }) { Icon(Icons.Rounded.Edit, "편집") }
                    IconButton(onClick = { vm.deleteRoutine(routine.routine.id) }) { Icon(Icons.Rounded.DeleteOutline, "삭제") }
                }
            }
        }
        item { Text("추천 템플릿", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
        items(SeedData.templates, key = { it.routine.id }) { template ->
            AppCard {
                Text(template.routine.name, fontWeight = FontWeight.Bold)
                Text("${template.items.size}개 운동")
                Spacer(Modifier.height(7.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    template.items.sortedBy { it.orderIndex }.take(6).forEach { item ->
                        ExerciseIconBadge(item.exerciseId, item.category, size = 28.dp)
                    }
                }
                TextButton(onClick = { vm.installTemplate(template.routine.id) }) {
                    Icon(Icons.Rounded.LibraryAdd, null)
                    Spacer(Modifier.width(5.dp))
                    Text("내 루틴에 추가")
                }
            }
        }
    }
    if (creating || editing != null) {
        RoutineDialog(editing, state.exercises, onDismiss = { creating = false; editing = null }) { name, ids ->
            vm.saveRoutine(editing?.routine?.id, name, ids)
            creating = false
            editing = null
        }
    }
    pendingGoal?.let { goal ->
        AlertDialog(
            onDismissRequest = { pendingGoal = null },
            title = { Text("주간 계획을 바꿀까요?") },
            text = { Text("운동 기록은 그대로 유지되지만 이번 주 계획 달성 표시는 새 구성으로 다시 시작합니다.") },
            confirmButton = {
                TextButton(onClick = { vm.rebuildTrainingPlan(goal); pendingGoal = null }) { Text("계획 변경") }
            },
            dismissButton = { TextButton(onClick = { pendingGoal = null }) { Text("취소") } },
        )
    }
}

@Composable
internal fun RoutineDialog(existing: RoutineWithItems?, exercises: List<ExerciseEntity>, onDismiss: () -> Unit, onSave: (String, List<String>) -> Unit) {
    var name by remember(existing) { mutableStateOf(existing?.routine?.name ?: "") }
    val selected = remember(existing) { mutableStateListOf<String>().apply { addAll(existing?.items?.sortedBy { it.orderIndex }?.map { it.exerciseId }.orEmpty()) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "새 루틴" else "루틴 편집") },
        text = {
            Column(Modifier.height(430.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("루틴 이름") }, modifier = Modifier.fillMaxWidth().testTag("routine-name"))
                Spacer(Modifier.height(8.dp))
                LazyColumn {
                    items(exercises, key = { it.id }) { exercise ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(exercise.id in selected, onCheckedChange = { checked -> if (checked) selected.add(exercise.id) else selected.remove(exercise.id) }, modifier = Modifier.testTag("exercise-${exercise.id}"))
                            ExerciseIconBadge(exercise.id, exercise.category, size = 30.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(exercise.name)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(enabled = name.isNotBlank() && selected.isNotEmpty(), onClick = { onSave(name, selected.toList()) }, modifier = Modifier.testTag("routine-save")) { Text("저장") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

@Composable
private fun HistoryPage(state: MainUiState, vm: MainViewModel) {
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    var healthDialog by remember { mutableStateOf(false) }
    val inBodyImageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let(vm::importInBodyScreenshot)
    }
    val linkedExternalIds = state.links.mapTo(mutableSetOf()) { it.samsungSessionId }
    val visible = state.sessions.filterNot { it.session.source.isExternal() && it.session.id in linkedExternalIds }
    val totalMinutes = visible.sumOf { session ->
        session.session.activeDurationMillis?.div(60_000)
            ?: session.session.endedAt?.let { ((it - session.session.startedAt) / 60_000).coerceAtLeast(0) }
            ?: 0
    }
    val totalVolume = visible.sumOf { session ->
        session.items.sumOf { item -> item.sets.filter { it.completed }.sumOf { (it.actualWeightKg ?: 0.0) * (it.actualReps ?: 0) } }
    }
    val totalDistance = visible.sumOf { session -> session.items.sumOf { it.item.distanceKm ?: 0.0 } }
    LazyColumn(
        Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            AppCard {
                Text("누적 통계", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("운동 ${visible.size}회 · ${totalMinutes}분")
                Text("볼륨 ${formatNumber(totalVolume)} kg · 유산소 ${formatNumber(totalDistance)} km")
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("통합 운동 기록", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                FilledTonalButton(onClick = { healthDialog = true }) {
                    Icon(Icons.Rounded.AddCircle, null)
                    Spacer(Modifier.width(5.dp))
                    Text("건강 기록")
                }
            }
        }
        items(visible, key = { it.session.id }) { session ->
            val link = state.links.firstOrNull { it.localSessionId == session.session.id }
            val linkedExternal = link?.let { target -> state.sessions.firstOrNull { it.session.id == target.samsungSessionId } }
            AppCard(onClick = { expanded = if (expanded == session.session.id) null else session.session.id }) {
                Text(session.session.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("${session.session.sessionDate} · ${durationText(linkedExternal ?: session)}")
                Text(sourceLabel(session.session.source) + if (linkedExternal != null) " + ${sourceLabel(linkedExternal.session.source)} 자동 연결" else "", color = MaterialTheme.colorScheme.secondary)
                if (expanded == session.session.id) {
                    Spacer(Modifier.height(8.dp))
                    session.items.sortedBy { it.item.orderIndex }.forEach { item ->
                        val detail = if (item.item.recordMode == RecordMode.SETS) {
                            "${item.sets.count { it.completed }}/${item.sets.size}세트"
                        } else {
                            val duration = item.item.durationMin ?: 0.0
                            val distance = item.item.distanceKm ?: 0.0
                            buildString {
                                append("${formatNumber(duration)}분 · ${formatRunDistance(distance)}km")
                                item.item.avgPaceMinPerKm?.let { append(" · ${formatRunPace(it)}/km") }
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ExerciseIconBadge(item.item.exerciseId, item.item.category, size = 28.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("${item.item.exerciseName}  $detail")
                        }
                        Spacer(Modifier.height(5.dp))
                    }
                    session.session.routePolyline?.let { encodedRoute ->
                        val route = RunningRouteCodec.decode(encodedRoute)
                        if (route.size >= 2) {
                            Spacer(Modifier.height(8.dp))
                            RunningRoutePreview(route, Modifier.fillMaxWidth().height(160.dp))
                        }
                    }
                    session.session.lapData?.let { encodedLaps ->
                        val laps = RunningLapCodec.decode(encodedLaps)
                        if (laps.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Text("랩 기록", fontWeight = FontWeight.Bold)
                            laps.forEach { lap -> RunningLapRow(lap) }
                        }
                    }
                    linkedExternal?.session?.distanceKm?.let { Text("외부 거리 ${formatNumber(it)} km") }
                    linkedExternal?.session?.caloriesKcal?.let { Text("외부 소모 ${formatNumber(it)} kcal") }
                    session.session.averageHeartRateBpm?.let { Text("워치 평균 심박 ${formatNumber(it)} bpm") }
                    val metricSource = when {
                        session.session.source.isExternal() -> "외부"
                        session.session.routePolyline != null -> "GPS"
                        else -> "워치"
                    }
                    if (linkedExternal == null) session.session.caloriesKcal?.let { Text("$metricSource 소모 ${formatNumber(it)} kcal") }
                    if (linkedExternal == null) session.session.distanceKm?.let { Text("$metricSource 거리 ${formatNumber(it)} km") }
                    session.session.syncError?.let { Text("전송 오류: $it", color = MaterialTheme.colorScheme.error) }
                    TextButton(onClick = { vm.deleteSession(session.session.id) }) {
                        Icon(Icons.Rounded.DeleteOutline, null)
                        Spacer(Modifier.width(5.dp))
                        Text("기록 삭제")
                    }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("체성분 추이", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                FilledTonalButton(
                    enabled = state.inBodyImport !is InBodyImportUiState.Reading,
                    onClick = { inBodyImageLauncher.launch("image/*") },
                ) {
                    if (state.inBodyImport is InBodyImportUiState.Reading) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Rounded.UploadFile, null)
                    }
                    Spacer(Modifier.width(5.dp))
                    Text(if (state.inBodyImport is InBodyImportUiState.Reading) "인식 중" else "인바디 캡처")
                }
            }
        }
        val health = effectiveHealthMeasurements(state.health)
        if (health.isEmpty()) item { AppCard { Text("아직 건강 기록이 없습니다.") } }
        if (health.isNotEmpty()) item { HealthTrendChart(health) }
        items(health.take(20), key = { it.id }) { metric -> HealthRow(metric) }
    }
    if (healthDialog) HealthEntryDialog(onDismiss = { healthDialog = false }) { type, value -> vm.saveHealth(type, value); healthDialog = false }
    (state.inBodyImport as? InBodyImportUiState.Review)?.let { review ->
        InBodyReviewDialog(
            result = review.result,
            onDismiss = vm::dismissInBodyImport,
            onSave = vm::saveInBodyImport,
        )
    }
}

@Composable
private fun HealthTrendChart(health: List<HealthMeasurementEntity>) {
    val availableTypes = availableHealthTrendTypes(health)
    var selectedTypeName by rememberSaveable {
        mutableStateOf(HealthMetricType.SKELETAL_MUSCLE_KG.name)
    }
    val selectedType = availableTypes.firstOrNull { it.name == selectedTypeName }
        ?: availableTypes.firstOrNull()
        ?: return
    val series = remember(health, selectedType) { healthTrendSeries(health, selectedType) }
    val bounds = remember(series, selectedType) { healthTrendBounds(series, selectedType) }
    val latest = series.last()
    val previous = series.getOrNull(series.lastIndex - 1)
    val delta = previous?.let { latest.value - it.value }
    val lineColor = when (selectedType) {
        HealthMetricType.SKELETAL_MUSCLE_KG -> MaterialTheme.colorScheme.secondary
        HealthMetricType.VISCERAL_FAT_LEVEL -> MaterialTheme.colorScheme.tertiary
        HealthMetricType.INBODY_SCORE -> MaterialTheme.colorScheme.primary
        HealthMetricType.WEIGHT_KG,
        HealthMetricType.BODY_FAT_PERCENT,
        HealthMetricType.BODY_FAT_MASS_KG -> MaterialTheme.colorScheme.error
        HealthMetricType.BODY_WATER_L -> MaterialTheme.colorScheme.primary
    }
    val gridColor = MaterialTheme.colorScheme.outlineVariant

    AppCard {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            availableTypes.forEach { type ->
                FilterChip(
                    selected = type == selectedType,
                    onClick = { selectedTypeName = type.name },
                    label = { Text(metricLabel(type)) },
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text(metricLabel(selectedType), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "최근 ${latest.recordDate} · ${healthSourceLabel(latest)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Text(formatNumber(latest.value), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(4.dp))
                Text(metricUnit(selectedType), modifier = Modifier.padding(bottom = 4.dp))
            }
        }
        delta?.let { change ->
            Text(
                "이전 측정 대비 ${if (change >= 0) "+" else ""}${formatNumber(change)} ${metricUnit(selectedType)}",
                style = MaterialTheme.typography.bodySmall,
                color = lineColor,
            )
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth().height(190.dp)) {
            Column(
                modifier = Modifier.width(48.dp).fillMaxHeight(),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.End,
            ) {
                Text(formatNumber(bounds.maximum), style = MaterialTheme.typography.labelSmall)
                Text(formatNumber((bounds.minimum + bounds.maximum) / 2.0), style = MaterialTheme.typography.labelSmall)
                Text(formatNumber(bounds.minimum), style = MaterialTheme.typography.labelSmall)
            }
            Spacer(Modifier.width(8.dp))
            Canvas(Modifier.weight(1f).fillMaxHeight()) {
                repeat(3) { index ->
                    val y = size.height * index / 2f
                    drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 2f)
                }
                fun pointOffset(index: Int, value: Double): Offset {
                    val x = if (series.size == 1) size.width / 2f
                    else size.width * index / (series.lastIndex).toFloat()
                    val normalized = ((value - bounds.minimum) / bounds.range).coerceIn(0.0, 1.0)
                    return Offset(x, size.height * (1f - normalized.toFloat()))
                }
                if (series.size > 1) {
                    val path = Path()
                    series.forEachIndexed { index, metric ->
                        val point = pointOffset(index, metric.value)
                        if (index == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
                    }
                    drawPath(path, lineColor, style = Stroke(width = 6f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
                series.forEachIndexed { index, metric ->
                    drawCircle(
                        color = lineColor,
                        radius = if (index == series.lastIndex) 10f else 7f,
                        center = pointOffset(index, metric.value),
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth()) {
            Spacer(Modifier.width(56.dp))
            Box(Modifier.weight(1f).height(22.dp)) {
                Text(trendDateLabel(series.first().recordDate), style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.CenterStart))
                if (series.size > 2) {
                    Text(
                        trendDateLabel(series[series.lastIndex / 2].recordDate),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
                if (series.size > 1) {
                    Text(trendDateLabel(series.last().recordDate), style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.CenterEnd))
                }
            }
        }
        Text(
            if (series.size == 1) "한 번 더 기록하면 변화가 선으로 연결됩니다."
            else "최근 ${series.size}회 기록",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun trendDateLabel(recordDate: String): String = runCatching {
    LocalDate.parse(recordDate).format(DateTimeFormatter.ofPattern("M.d", Locale.KOREA))
}.getOrDefault(recordDate)

@Composable
private fun HealthRow(metric: HealthMeasurementEntity) {
    AppCard {
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text(metricLabel(metric.type), fontWeight = FontWeight.Bold)
                Text(metric.recordDate)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("${formatNumber(metric.value)} ${metricUnit(metric.type)}", style = MaterialTheme.typography.titleMedium)
                Text(healthSourceLabel(metric), color = MaterialTheme.colorScheme.secondary)
            }
        }
    }
}

@Composable
private fun HealthEntryDialog(onDismiss: () -> Unit, onSave: (HealthMetricType, Double) -> Unit) {
    var type by remember { mutableStateOf(HealthMetricType.WEIGHT_KG) }
    var value by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("건강 기록") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    HealthMetricType.WEIGHT_KG,
                    HealthMetricType.BODY_FAT_PERCENT,
                    HealthMetricType.BODY_FAT_MASS_KG,
                    HealthMetricType.SKELETAL_MUSCLE_KG,
                    HealthMetricType.VISCERAL_FAT_LEVEL,
                    HealthMetricType.INBODY_SCORE,
                ).forEach { candidate ->
                    AssistChip(
                        onClick = { type = candidate },
                        label = { Text(metricLabel(candidate)) },
                        leadingIcon = if (type == candidate) {{ Icon(Icons.Rounded.Check, "선택됨") }} else null,
                    )
                }
                OutlinedTextField(value, { value = it }, label = { Text("값 (${metricUnit(type)})") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            }
        },
        confirmButton = { TextButton(enabled = value.toDoubleOrNull()?.let { it > 0 } == true, onClick = { onSave(type, value.toDouble()) }) { Text("저장") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

private val inBodyImportTypes = listOf(
    HealthMetricType.SKELETAL_MUSCLE_KG,
    HealthMetricType.BODY_FAT_MASS_KG,
    HealthMetricType.VISCERAL_FAT_LEVEL,
    HealthMetricType.INBODY_SCORE,
)

@Composable
private fun InBodyReviewDialog(
    result: com.hanshin.healthtask.health.InBodyScreenshotResult,
    onDismiss: () -> Unit,
    onSave: (LocalDate, Map<HealthMetricType, Double>) -> Unit,
) {
    var dateText by remember(result) { mutableStateOf((result.measuredDate ?: LocalDate.now()).toString()) }
    val valueText = remember(result) {
        mutableStateMapOf<HealthMetricType, String>().apply {
            inBodyImportTypes.forEach { type -> this[type] = result.values[type]?.let(::formatNumber).orEmpty() }
        }
    }
    val parsedDate = runCatching { LocalDate.parse(dateText.trim()) }.getOrNull()
    val parsedValues = inBodyImportTypes.mapNotNull { type ->
        valueText[type]?.trim()?.toDoubleOrNull()?.takeIf { it > 0.0 }?.let { type to it }
    }.toMap()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("인바디 캡처 확인") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("자동 인식한 수치를 확인해 주세요. 잘못된 값은 수정하고, 보이지 않는 항목은 비워도 됩니다.")
                OutlinedTextField(
                    value = dateText,
                    onValueChange = { dateText = it },
                    label = { Text("측정일 (YYYY-MM-DD)") },
                    isError = parsedDate == null,
                    singleLine = true,
                )
                inBodyImportTypes.forEach { type ->
                    OutlinedTextField(
                        value = valueText[type].orEmpty(),
                        onValueChange = { valueText[type] = it },
                        label = { Text("${metricLabel(type)} (${metricUnit(type)})") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = parsedDate != null && parsedValues.isNotEmpty(),
                onClick = { onSave(requireNotNull(parsedDate), parsedValues) },
            ) { Text("저장") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

@Composable
private fun SettingsPage(state: MainUiState, vm: MainViewModel) {
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { vm.sync(force = true) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> uri?.let { vm.exportBackup(context.contentResolver, it) } }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { vm.importBackup(context.contentResolver, it) } }
    LazyColumn(
        Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            AppCard {
                Text("Health Connect · 삼성 헬스", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(healthStatusLabel(state.healthStatus))
                Text("삼성 헬스의 운동·체성분을 가져오고 오늘운동에서 직접 기록한 러닝을 공유합니다.")
                Text("GPS 러닝은 연결 없이도 사용할 수 있으며, 다른 건강 앱과 공유할 때만 권한을 연결하세요.")
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("포그라운드 동기화", modifier = Modifier.weight(1f))
                    Switch(state.syncPreferences.enabled, onCheckedChange = vm::setSyncEnabled)
                }
                Text("마지막 동기화: ${state.syncPreferences.lastSyncAt?.let(::formatTime) ?: "없음"}")
                Spacer(Modifier.height(8.dp))
                if (state.healthStatus != HealthConnectStatus.CONNECTED) {
                    Button(onClick = { permissionLauncher.launch(vm.requiredPermissions) }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Rounded.Link, null)
                        Spacer(Modifier.width(6.dp))
                        Text("권한 연결")
                    }
                }
                OutlinedButton(onClick = { vm.sync(force = true) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.Sync, null)
                    Spacer(Modifier.width(6.dp))
                    Text("새로고침 · 오류 재시도")
                }
                TextButton(onClick = vm::openHealthPermissions, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.HealthAndSafety, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Health Connect 권한 관리")
                }
            }
        }
        item {
            AppCard {
                Text("러닝 안내", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("자동 일시정지", fontWeight = FontWeight.SemiBold)
                        Text("8초간 움직임이 없으면 멈추고 달리면 자동 재개합니다.", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(
                        checked = state.runningPreferences.autoPauseEnabled,
                        onCheckedChange = vm::setRunningAutoPauseEnabled,
                    )
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("1km 음성 페이스 안내", fontWeight = FontWeight.SemiBold)
                        Text("랩 완료와 자동 일시정지·재개를 한국어 음성으로 알려줍니다.", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(
                        checked = state.runningPreferences.voiceGuidanceEnabled,
                        onCheckedChange = vm::setRunningVoiceGuidanceEnabled,
                    )
                }
            }
        }
        item {
            AppCard {
                Text("세트 휴식 타이머", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("근력운동 세트를 완료하면 자동으로 시작하고, 종료 시 소리와 진동으로 알려줍니다.")
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    listOf(60, 90, 120, 180).forEach { seconds ->
                        AssistChip(
                            onClick = { vm.setRestTimerSeconds(seconds) },
                            label = { Text(if (seconds < 60) "${seconds}초" else "${seconds / 60}분${if (seconds % 60 == 0) "" else " ${seconds % 60}초"}") },
                            leadingIcon = if (state.restTimerSeconds == seconds) {{ Icon(Icons.Rounded.Check, "선택됨") }} else null,
                        )
                    }
                }
                Text("현재 ${formatCountdown(state.restTimerSeconds)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            AppCard {
                Text("주간 목표", fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    (1..7).forEach { number ->
                        AssistChip(
                            onClick = { vm.setGoal(number) },
                            label = { Text("$number") },
                            leadingIcon = if (state.profile?.workoutsPerWeek == number) {{ Icon(Icons.Rounded.Check, "선택됨") }} else null,
                        )
                    }
                }
            }
        }
        item {
            AppCard {
                Text("JSON 백업", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("schemaVersion 3으로 주간 계획·로컬 기록·연결 ID를 저장합니다. 외부 앱 캐시는 복원 후 다시 동기화합니다.")
                Spacer(Modifier.height(8.dp))
                Button(onClick = { exportLauncher.launch("today-workout-${LocalDate.now()}.json") }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.UploadFile, null)
                    Spacer(Modifier.width(6.dp))
                    Text("JSON 내보내기")
                }
                OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/plain")) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.FileDownload, null)
                    Spacer(Modifier.width(6.dp))
                    Text("legacy-v1 / v2 불러오기")
                }
            }
        }
        item { Text("오늘운동 · 기기 로컬 전용 · Android 14+", color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun AppCard(onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    if (onClick == null) Card(Modifier.fillMaxWidth(), colors = colors) { Column(Modifier.padding(16.dp), content = content) }
    else Card(onClick = onClick, modifier = Modifier.fillMaxWidth(), colors = colors) { Column(Modifier.padding(16.dp), content = content) }
}

@Composable
private fun ExerciseIconBadge(
    exerciseId: String,
    category: ExerciseCategory,
    size: androidx.compose.ui.unit.Dp = 36.dp,
) {
    val (containerColor, contentColor) = when (category) {
        ExerciseCategory.WEIGHT -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        ExerciseCategory.BODYWEIGHT -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        ExerciseCategory.CARDIO -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
    }
    Surface(
        modifier = Modifier.size(size),
        shape = CircleShape,
        color = containerColor,
        contentColor = contentColor,
    ) {
        Box(Modifier.fillMaxSize().padding(5.dp), contentAlignment = Alignment.Center) {
            Icon(exerciseIconVector(exerciseId, category), contentDescription = null, modifier = Modifier.fillMaxSize())
        }
    }
}

private fun exerciseIconVector(
    exerciseId: String,
    category: ExerciseCategory,
): androidx.compose.ui.graphics.vector.ImageVector {
    if (category == ExerciseCategory.CARDIO) return Icons.AutoMirrored.Rounded.DirectionsRun
    return when (SeedData.exercises.firstOrNull { it.id == exerciseId }?.muscleGroup) {
        "back" -> Icons.Rounded.Rowing
        "legs" -> Icons.AutoMirrored.Rounded.DirectionsWalk
        "core" -> Icons.Rounded.SelfImprovement
        "shoulders" -> Icons.Rounded.AccessibilityNew
        else -> if (category == ExerciseCategory.BODYWEIGHT) Icons.Rounded.AccessibilityNew else Icons.Rounded.FitnessCenter
    }
}

@Composable private fun SectionTitle(value: String) = Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

private fun sourceLabel(source: WorkoutSource) = when (source) {
    WorkoutSource.LOCAL -> "오늘운동"
    WorkoutSource.LEGACY_IMPORT -> "기존 PWA 가져오기"
    WorkoutSource.SAMSUNG_HEALTH -> "삼성 헬스"
    WorkoutSource.GOOGLE_FIT -> "Nike Run Club · Google Fit(기존)"
    WorkoutSource.NIKE_RUN_CLUB -> "Nike Run Club · Health Connect"
}
private fun healthStatusLabel(status: HealthConnectStatus) = when (status) {
    HealthConnectStatus.CONNECTED -> "연결됨"
    HealthConnectStatus.PERMISSIONS_REQUIRED -> "권한 필요"
    HealthConnectStatus.UNAVAILABLE -> "이 기기에서 사용할 수 없음"
    HealthConnectStatus.AVAILABLE -> "확인 중"
}
private fun durationText(session: WorkoutSessionWithItems): String {
    val end = session.session.endedAt ?: return "진행 중"
    val durationMillis = session.session.activeDurationMillis
        ?: Duration.between(Instant.ofEpochMilli(session.session.startedAt), Instant.ofEpochMilli(end)).toMillis()
    return "${(durationMillis / 60_000L).coerceAtLeast(0)}분"
}
private fun formatTime(epoch: Long): String = java.time.ZonedDateTime.ofInstant(Instant.ofEpochMilli(epoch), java.time.ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("M월 d일 HH:mm", Locale.KOREA))
private fun formatCountdown(seconds: Int): String = "%02d:%02d".format(seconds.coerceAtLeast(0) / 60, seconds.coerceAtLeast(0) % 60)
private fun formatRunDuration(millis: Long): String {
    val totalSeconds = millis.coerceAtLeast(0L) / 1_000L
    return "%02d:%02d:%02d".format(totalSeconds / 3_600L, (totalSeconds / 60L) % 60L, totalSeconds % 60L)
}
private fun formatRunPace(pace: Double?): String {
    val totalSeconds = pace?.takeIf { it.isFinite() && it in 0.0..60.0 }?.times(60.0)?.toInt() ?: return "--'--\""
    return "%d'%02d\"".format(totalSeconds / 60, totalSeconds % 60)
}
private fun formatRunDistance(distanceKm: Double): String =
    String.format(Locale.KOREA, if (distanceKm < 10.0) "%.2f" else "%.1f", distanceKm.coerceAtLeast(0.0))
private fun setRecordSummary(set: com.hanshin.healthtask.data.db.SetRecordEntity): String = buildList {
    set.actualWeightKg?.let { add("${formatNumber(it)}kg") }
    set.actualReps?.let { add("${it}회") }
}.joinToString(" × ").ifBlank { "세트 기록됨" }
private fun metricLabel(type: HealthMetricType) = when (type) {
    HealthMetricType.WEIGHT_KG -> "체중"
    HealthMetricType.BODY_FAT_PERCENT -> "체지방률"
    HealthMetricType.BODY_FAT_MASS_KG -> "체지방량"
    HealthMetricType.SKELETAL_MUSCLE_KG -> "골격근량"
    HealthMetricType.BODY_WATER_L -> "체수분"
    HealthMetricType.VISCERAL_FAT_LEVEL -> "내장지방 레벨"
    HealthMetricType.INBODY_SCORE -> "인바디 점수"
}
private fun metricUnit(type: HealthMetricType) = when (type) {
    HealthMetricType.BODY_FAT_PERCENT -> "%"
    HealthMetricType.VISCERAL_FAT_LEVEL -> "단계"
    HealthMetricType.BODY_WATER_L -> "L"
    HealthMetricType.INBODY_SCORE -> "점"
    else -> "kg"
}
private fun healthSourceLabel(metric: HealthMeasurementEntity): String =
    if (metric.sourcePackage == INBODY_PACKAGE) "인바디 캡처" else sourceLabel(metric.source)
private fun formatNumber(value: Double) = if (value % 1.0 == 0.0) value.toInt().toString() else String.format(Locale.KOREA, "%.1f", value)
