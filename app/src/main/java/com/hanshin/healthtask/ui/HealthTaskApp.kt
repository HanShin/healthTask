package com.hanshin.healthtask.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
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
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Sync
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.health.connect.client.PermissionController
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
import com.hanshin.healthtask.data.db.RoutineWithItems
import com.hanshin.healthtask.data.db.WorkoutItemWithSets
import com.hanshin.healthtask.data.db.WorkoutSessionWithItems
import com.hanshin.healthtask.domain.HealthMetricType
import com.hanshin.healthtask.domain.RecordMode
import com.hanshin.healthtask.domain.WorkoutSource
import com.hanshin.healthtask.health.HealthConnectStatus
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private data class Tab(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)
private val tabs = listOf(
    Tab("today", "오늘", Icons.Rounded.CalendarMonth),
    Tab("routines", "루틴", Icons.Rounded.FitnessCenter),
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
            composable("today") { TodayPage(state, vm) }
            composable("routines") { RoutinesPage(state, vm) }
            composable("history") { HistoryPage(state, vm) }
            composable("settings") { SettingsPage(state, vm) }
        }
    }
}

@Composable
private fun TodayPage(state: MainUiState, vm: MainViewModel) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            AppCard {
                Text("이번 주 ${state.progress.completed} / ${state.progress.goal}회", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { (state.progress.completed.toFloat() / state.progress.goal.coerceAtLeast(1)).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text("연속 ${state.progress.streakDays}일", color = MaterialTheme.colorScheme.secondary)
            }
        }
        val active = state.activeSession
        if (active != null) {
            item { Text("진행 중인 운동", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            item { ActiveSessionCard(active, vm) }
        } else {
            item { Text("다음 루틴", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            item {
                AppCard {
                    val routine = state.nextRoutine
                    if (routine == null) {
                        Text("먼저 루틴을 만들어 주세요.")
                    } else {
                        Text(routine.routine.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(routine.items.sortedBy { it.orderIndex }.joinToString(" · ") { item -> state.exercises.firstOrNull { it.id == item.exerciseId }?.name ?: item.exerciseId })
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
        val samsung = state.sessions.firstOrNull { it.session.source == WorkoutSource.SAMSUNG_HEALTH }
        if (samsung != null) item {
            AppCard {
                Text("최근 삼성 헬스", fontWeight = FontWeight.Bold)
                Text("${samsung.session.title} · ${durationText(samsung)}")
                Text(samsung.session.sessionDate, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ActiveSessionCard(session: WorkoutSessionWithItems, vm: MainViewModel) {
    AppCard {
        Text(session.session.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("시작 ${formatTime(session.session.startedAt)}")
        Spacer(Modifier.height(12.dp))
        session.items.sortedBy { it.item.orderIndex }.forEach { item ->
            WorkoutInput(item, vm)
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
private fun WorkoutInput(full: WorkoutItemWithSets, vm: MainViewModel) {
    val context = LocalContext.current
    var guide by remember { mutableStateOf(false) }
    TextButton(onClick = { guide = true }, contentPadding = PaddingValues(0.dp)) {
        Text(full.item.exerciseName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Icon(Icons.Rounded.TipsAndUpdates, "운동 가이드", Modifier.padding(start = 5.dp).size(18.dp))
    }
    if (full.item.recordMode == RecordMode.SETS) {
        full.sets.sortedBy { it.orderIndex }.forEach { set ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${set.orderIndex}세트", modifier = Modifier.size(width = 46.dp, height = 48.dp).padding(top = 14.dp))
                NumberField("kg", set.actualWeightKg) { value -> vm.updateSet(set.copy(actualWeightKg = value)) }
                NumberField("회", set.actualReps?.toDouble()) { value -> vm.updateSet(set.copy(actualReps = value?.toInt())) }
                Checkbox(set.completed, onCheckedChange = { vm.updateSet(set.copy(completed = it)) })
            }
        }
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
private fun NumberField(label: String, value: Double?, onChange: (Double?) -> Unit) {
    var text by remember(value) { mutableStateOf(value?.let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() } ?: "") }
    OutlinedTextField(
        value = text,
        onValueChange = { new -> text = new; onChange(new.toDoubleOrNull()) },
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.width(84.dp),
        singleLine = true,
    )
}

@Composable
private fun GuideDialog(exercise: ExerciseEntity?, onDismiss: () -> Unit, onOpen: (String) -> Unit) {
    var motionAngle by rememberSaveable(exercise?.id) { mutableStateOf("front") }
    val motionResource = when (exercise?.id) {
        "squat" -> if (motionAngle == "front") R.raw.squat_front else R.raw.squat_side
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
    LazyColumn(
        Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
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
    val linkedSamsungIds = state.links.mapTo(mutableSetOf()) { it.samsungSessionId }
    val visible = state.sessions.filterNot { it.session.source == WorkoutSource.SAMSUNG_HEALTH && it.session.id in linkedSamsungIds }
    val totalMinutes = visible.sumOf { session ->
        session.session.endedAt?.let { ((it - session.session.startedAt) / 60_000).coerceAtLeast(0) } ?: 0
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
            val samsung = link?.let { target -> state.sessions.firstOrNull { it.session.id == target.samsungSessionId } }
            AppCard(onClick = { expanded = if (expanded == session.session.id) null else session.session.id }) {
                Text(session.session.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("${session.session.sessionDate} · ${durationText(samsung ?: session)}")
                Text(sourceLabel(session.session.source) + if (samsung != null) " + 삼성 헬스 자동 연결" else "", color = MaterialTheme.colorScheme.secondary)
                if (expanded == session.session.id) {
                    Spacer(Modifier.height(8.dp))
                    session.items.sortedBy { it.item.orderIndex }.forEach { item ->
                        val detail = if (item.item.recordMode == RecordMode.SETS) "${item.sets.count { it.completed }}/${item.sets.size}세트" else "${item.item.durationMin ?: 0.0}분 · ${item.item.distanceKm ?: 0.0}km"
                        Text("${item.item.exerciseName}  $detail")
                    }
                    samsung?.session?.distanceKm?.let { Text("삼성 거리 ${formatNumber(it)} km") }
                    samsung?.session?.caloriesKcal?.let { Text("삼성 소모 ${formatNumber(it)} kcal") }
                    session.session.averageHeartRateBpm?.let { Text("워치 평균 심박 ${formatNumber(it)} bpm") }
                    if (samsung == null) session.session.caloriesKcal?.let { Text("워치 소모 ${formatNumber(it)} kcal") }
                    if (samsung == null) session.session.distanceKm?.let { Text("워치 거리 ${formatNumber(it)} km") }
                    session.session.syncError?.let { Text("전송 오류: $it", color = MaterialTheme.colorScheme.error) }
                    TextButton(onClick = { vm.deleteSession(session.session.id) }) {
                        Icon(Icons.Rounded.DeleteOutline, null)
                        Spacer(Modifier.width(5.dp))
                        Text("기록 삭제")
                    }
                }
            }
        }
        item { Text("체성분 추이", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
        val health = effectiveHealthMeasurements(state.health)
        if (health.isEmpty()) item { AppCard { Text("아직 건강 기록이 없습니다.") } }
        if (health.isNotEmpty()) item { HealthTrendSummary(health) }
        items(health.take(20), key = { it.id }) { metric -> HealthRow(metric) }
    }
    if (healthDialog) HealthEntryDialog(onDismiss = { healthDialog = false }) { type, value -> vm.saveHealth(type, value); healthDialog = false }
}

@Composable
private fun HealthTrendSummary(health: List<HealthMeasurementEntity>) {
    AppCard {
        health.groupBy { it.type }.entries.sortedBy { it.key.ordinal }.forEach { (type, values) ->
            val ordered = values.sortedByDescending { it.measuredAt }
            val latest = ordered.first()
            val delta = ordered.getOrNull(1)?.let { latest.value - it.value }
            Row(Modifier.fillMaxWidth()) {
                Text(metricLabel(type), modifier = Modifier.weight(1f))
                Text("${formatNumber(latest.value)} ${metricUnit(type)}" + (delta?.let { "  ${if (it >= 0) "+" else ""}${formatNumber(it)}" } ?: ""))
            }
        }
    }
}

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
                Text(sourceLabel(metric.source), color = MaterialTheme.colorScheme.secondary)
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
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    HealthMetricType.WEIGHT_KG,
                    HealthMetricType.BODY_FAT_PERCENT,
                    HealthMetricType.BODY_FAT_MASS_KG,
                    HealthMetricType.SKELETAL_MUSCLE_KG,
                    HealthMetricType.VISCERAL_FAT_LEVEL,
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
                Text("삼성 헬스 원본만 가져오며, 권한이 없어도 로컬 운동은 그대로 작동합니다.")
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
                Text("schemaVersion 2로 로컬 기록과 연결 ID를 저장합니다. 삼성 캐시는 복원 후 다시 동기화합니다.")
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

@Composable private fun SectionTitle(value: String) = Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

private fun sourceLabel(source: WorkoutSource) = when (source) {
    WorkoutSource.LOCAL -> "오늘운동"
    WorkoutSource.LEGACY_IMPORT -> "기존 PWA 가져오기"
    WorkoutSource.SAMSUNG_HEALTH -> "삼성 헬스"
}
private fun healthStatusLabel(status: HealthConnectStatus) = when (status) {
    HealthConnectStatus.CONNECTED -> "연결됨"
    HealthConnectStatus.PERMISSIONS_REQUIRED -> "권한 필요"
    HealthConnectStatus.UNAVAILABLE -> "이 기기에서 사용할 수 없음"
    HealthConnectStatus.AVAILABLE -> "확인 중"
}
private fun durationText(session: WorkoutSessionWithItems): String {
    val end = session.session.endedAt ?: return "진행 중"
    return "${Duration.between(Instant.ofEpochMilli(session.session.startedAt), Instant.ofEpochMilli(end)).toMinutes().coerceAtLeast(0)}분"
}
private fun formatTime(epoch: Long): String = java.time.ZonedDateTime.ofInstant(Instant.ofEpochMilli(epoch), java.time.ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("M월 d일 HH:mm", Locale.KOREA))
private fun metricLabel(type: HealthMetricType) = when (type) {
    HealthMetricType.WEIGHT_KG -> "체중"
    HealthMetricType.BODY_FAT_PERCENT -> "체지방률"
    HealthMetricType.BODY_FAT_MASS_KG -> "체지방량"
    HealthMetricType.SKELETAL_MUSCLE_KG -> "골격근량"
    HealthMetricType.VISCERAL_FAT_LEVEL -> "복부비만 단계"
}
private fun metricUnit(type: HealthMetricType) = when (type) {
    HealthMetricType.BODY_FAT_PERCENT -> "%"
    HealthMetricType.VISCERAL_FAT_LEVEL -> "단계"
    else -> "kg"
}
private fun formatNumber(value: Double) = if (value % 1.0 == 0.0) value.toInt().toString() else String.format(Locale.KOREA, "%.1f", value)
