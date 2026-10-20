package com.hanshin.healthtask.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DirectionsRun
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.HealthAndSafety
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.AccessibilityNew
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.MonitorWeight
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.health.connect.client.PermissionController
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.hanshin.healthtask.data.db.HealthMeasurementEntity
import com.hanshin.healthtask.data.db.WorkoutSessionWithItems
import com.hanshin.healthtask.domain.HealthMetricType
import com.hanshin.healthtask.domain.INBODY_PACKAGE
import com.hanshin.healthtask.domain.WorkoutSource
import com.hanshin.healthtask.domain.isExternal
import com.hanshin.healthtask.health.HealthConnectStatus
import com.hanshin.healthtask.running.RunningPhase
import com.hanshin.healthtask.running.RunningLap
import com.hanshin.healthtask.running.RunningLapCodec
import com.hanshin.healthtask.running.RunningPoint
import com.hanshin.healthtask.running.RunningRouteCodec
import com.hanshin.healthtask.running.RunningUiState
import com.hanshin.healthtask.domain.runningDistanceKm
import com.hanshin.healthtask.domain.runningDurationMillis
import com.hanshin.healthtask.domain.isValidBodyValue
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.cos
import kotlin.math.min

private data class Tab(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)
internal val runningAppTabLabels = listOf("러닝", "러닝 기록", "인바디", "설정")
private val tabs = listOf(
    Tab("running", runningAppTabLabels[0], Icons.AutoMirrored.Rounded.DirectionsRun),
    Tab("history", runningAppTabLabels[1], Icons.Rounded.Insights),
    Tab("inbody", runningAppTabLabels[2], Icons.Rounded.AccessibilityNew),
    Tab("settings", runningAppTabLabels[3], Icons.Rounded.Tune),
)

@Composable
fun HealthTaskRoot(vm: MainViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    if (!state.loaded) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.onForeground() }
    MainScaffold(state, vm)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScaffold(state: MainUiState, vm: MainViewModel) {
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val current = nav.currentBackStackEntryFlow.collectAsStateWithLifecycle(nav.currentBackStackEntry).value?.destination?.route ?: "running"
    LaunchedEffect(state.running.isActive) {
        if (state.running.isActive && current != "running") {
            nav.navigate("running") { launchSingleTop = true }
        }
    }
    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); vm.consumeMessage() }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(if (current == "running") "오늘러닝" else tabs.firstOrNull { it.route == current }?.label.orEmpty(),
                            style = MaterialTheme.typography.headlineSmall)
                        Text(when (current) {
                            "running" -> LocalDate.now().format(DateTimeFormatter.ofPattern("M월 d일 EEEE", Locale.KOREA))
                            "history" -> "달려온 시간을 한눈에"
                            "inbody" -> "몸의 변화를 차곡차곡"
                            else -> "나에게 맞는 러닝 환경"
                        }, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                tabs.forEach { tab ->
                    NavigationBarItem(
                        modifier = Modifier.testTag("tab-${tab.route}"),
                        selected = current == tab.route,
                        colors = NavigationBarItemDefaults.colors(
                            indicatorColor = RunPalette.Lime, selectedIconColor = RunPalette.Ink,
                            selectedTextColor = RunPalette.Ink, unselectedIconColor = RunPalette.Muted,
                            unselectedTextColor = RunPalette.Muted,
                        ),
                        onClick = { nav.navigate(tab.route) { popUpTo(nav.graph.findStartDestination().id) { saveState = true }; launchSingleTop = true; restoreState = true } },
                        icon = { Icon(tab.icon, null) },
                        label = { Text(tab.label, style = MaterialTheme.typography.labelMedium) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = "running", modifier = Modifier.padding(padding)) {
            composable("running") { RunningPage(state, vm) }
            composable("inbody") { InBodyPage(state, vm) }
            composable("history") { HistoryPage(state, vm) }
            composable("settings") { SettingsPage(state, vm) }
        }
    }
}

@Composable
private fun RunningPage(state: MainUiState, vm: MainViewModel) {
    val context = LocalContext.current
    var permissionRevision by remember { mutableIntStateOf(0) }
    var confirmFinish by remember { mutableStateOf(false) }
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
            vm.startRun()
        } else vm.showMessage("러닝 기록에는 위치 권한이 필요합니다.")
    }

    RunningDashboard(
        running = state.running,
        onStart = {
            if (hasLocationPermission) vm.startRun() else permissionLauncher.launch(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.POST_NOTIFICATIONS,
            ))
        },
        onPause = vm::pauseRun, onResume = vm::resumeRun,
        onFinish = { confirmFinish = true }, onNew = vm::newRun,
    )

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
internal fun RunningLapsCard(running: RunningUiState) {
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
internal fun RunningRouteTrace(points: List<RunningPoint>, modifier: Modifier = Modifier) {
    val background = MaterialTheme.colorScheme.surfaceVariant
    val routeColor = MaterialTheme.colorScheme.primary
    val startColor = MaterialTheme.colorScheme.secondary
    Box(
        modifier = modifier.background(background, RoundedCornerShape(18.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (points.size < 2) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AppIconBadge(Icons.Rounded.Route)
                Text(if (points.isEmpty()) "지나온 길이 나의 기록으로" else "GPS 이동을 기다리는 중…",
                    style = MaterialTheme.typography.titleSmall)
                Text(if (points.isEmpty()) "러닝을 시작하면 이곳에 경로가 표시돼요." else "이동하면 경로가 이어집니다.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
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
private fun HistoryPage(state: MainUiState, vm: MainViewModel) {
    RunningHistoryContent(state.runs, vm::deleteSession)
}

@Composable
internal fun RunningHistoryContent(runs: List<WorkoutSessionWithItems>, onDelete: (String) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<WorkoutSessionWithItems?>(null) }
    val visible = runs
    val totalMinutes = visible.sumOf { it.runningDurationMillis() } / 60_000
    val totalDistance = visible.sumOf { it.runningDistanceKm() }
    LazyColumn(
        Modifier.fillMaxSize().testTag("running-history"),
        contentPadding = PaddingValues(20.dp, 8.dp, 20.dp, 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Surface(shape = RoundedCornerShape(28.dp), color = RunPalette.Ink) {
                Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    Text("누적 러닝", style = MaterialTheme.typography.labelLarge, color = RunPalette.Lime)
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(formatRunDistance(totalDistance), style = MaterialTheme.typography.displayLarge, color = Color.White)
                        Text("km", Modifier.padding(bottom = 10.dp), style = MaterialTheme.typography.titleMedium, color = Color(0xFFBDCBC2))
                    }
                    HorizontalDivider(color = Color.White.copy(alpha = 0.12f))
                    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        SummaryMetric("러닝 횟수", "${visible.size}", "회", Modifier.weight(1f), dark = true)
                        SummaryMetric("달린 시간", "$totalMinutes", "분", Modifier.weight(1f), dark = true)
                    }
                }
            }
        }
        item { SectionHeading("러닝 로그", "${visible.size}개의 기록") }
        if (visible.isEmpty()) item {
            EmptyRecordState(Icons.AutoMirrored.Rounded.DirectionsRun, "첫 러닝을 기다리고 있어요",
                "휴대폰이나 워치에서 러닝을 시작해 보세요.\n완료한 기록이 이곳에 차곡차곡 쌓입니다.")
        }
        items(visible, key = { it.session.id }) { run ->
            val session = run.session
            val distance = run.runningDistanceKm()
            val pace = distance.takeIf { it > 0.02 }?.let { run.runningDurationMillis() / 60_000.0 / it }
            AppCard(
                modifier = Modifier.semantics { stateDescription = if (expanded == session.id) "펼쳐짐" else "접힘" },
                onClick = { expanded = if (expanded == session.id) null else session.id },
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AppIconBadge(Icons.AutoMirrored.Rounded.DirectionsRun)
                    Column(Modifier.weight(1f)) {
                        Text(session.title, style = MaterialTheme.typography.titleMedium)
                        Text("${recordDateLabel(session.sessionDate)} · ${durationText(run)}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(if (expanded == session.id) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        contentDescription = if (expanded == session.id) "상세 접기" else "상세 보기",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    SummaryMetric("거리", formatRunDistance(distance), "km", Modifier.weight(1f))
                    SummaryMetric("평균 페이스", formatRunPace(pace), "/km", Modifier.weight(1f))
                }
                Spacer(Modifier.height(14.dp))
                Text(sourceLabel(session.source), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (expanded == session.id) {
                    session.routePolyline?.let { encoded ->
                        val route = RunningRouteCodec.decode(encoded)
                        if (route.size >= 2) {
                            Spacer(Modifier.height(8.dp))
                            RunningRoutePreview(route, Modifier.fillMaxWidth().height(220.dp))
                        }
                    }
                    session.lapData?.let { encoded ->
                        val laps = RunningLapCodec.decode(encoded)
                        if (laps.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Text("랩 기록", fontWeight = FontWeight.Bold)
                            laps.forEach { RunningLapRow(it) }
                        }
                    }
                    session.averageHeartRateBpm?.let { Text("평균 심박 ${formatNumber(it)} bpm") }
                    val sensorSummary = remember(session.sensorSummaryJson) {
                        com.hanshin.healthtask.shared.RunTelemetryCodec.decodeSummary(session.sensorSummaryJson)
                    }
                    sensorSummary?.let { summary ->
                        Spacer(Modifier.height(8.dp))
                        Text("상세 센서 기록", fontWeight = FontWeight.Bold)
                        if (!summary.collectionWarnings.isNullOrEmpty()) {
                            Text("일부 센서 기록이 누락됐을 수 있습니다. 내보내기 파일에서 수집 경고를 확인하세요.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                        if (summary.heartRateSampleCount > 0) {
                            Text("심박 ${summary.minHeartRateBpm?.let(::formatNumber) ?: "—"}–${summary.maxHeartRateBpm?.let(::formatNumber) ?: "—"} bpm · ${summary.heartRateSampleCount}회 측정")
                        }
                        summary.sampleMeanCadenceStepsPerMinute?.let { Text("평균 케이던스 ${formatNumber(it)} 걸음/분") }
                        summary.stepsTotal?.let { Text("걸음 수 ${formatNumber(it)}") }
                        summary.elevationGainMeters?.let { Text("누적 상승 ${formatNumber(it)} m") }
                        summary.elevationLossMeters?.let { Text("누적 하강 ${formatNumber(it)} m") }
                        if (summary.heartRateBandCoveredMillis > 0) {
                            Text("심박 범위별 측정 시간", style = MaterialTheme.typography.labelMedium)
                            summary.heartRateBands.filter { it.durationMillis > 0 }.forEach { band ->
                                val label = band.upperExclusiveBpm?.let { "${band.lowerInclusiveBpm}–${it - 1}" }
                                    ?: "${band.lowerInclusiveBpm} 이상"
                                Text("$label bpm · ${band.durationMillis / 60_000}분 ${(band.durationMillis / 1_000) % 60}초",
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            Text("개인별 운동 강도 구간이 아닌 실제 bpm 범위입니다. 측정 공백·정지 시간은 제외합니다.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text("시간별 측정값·GPS 정확도는 상세 데이터 내보내기에 포함됩니다. 미지원 항목은 표시하지 않습니다.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    session.caloriesKcal?.let { Text("소모 ${formatNumber(it)} kcal") }
                    session.syncError?.let { Text("전송 오류: $it", color = MaterialTheme.colorScheme.error) }
                    if (!session.source.isExternal()) TextButton(onClick = { deleting = run }) { Text("기록 삭제") }
                    else Text("연동 기록의 수정·삭제는 원본 앱에서 해 주세요.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    deleting?.let { run ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("러닝 기록 삭제") },
            text = { Text("${run.session.sessionDate} · ${run.session.title} 기록을 기기에서 삭제할까요? 연결된 외부 앱 기록은 그대로 남습니다.") },
            confirmButton = { TextButton(onClick = { onDelete(run.session.id); deleting = null }) { Text("삭제") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("취소") } },
        )
    }
}

@Composable
private fun InBodyPage(state: MainUiState, vm: MainViewModel) {
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<HealthMeasurementEntity?>(null) }
    var deleting by remember { mutableStateOf<HealthMeasurementEntity?>(null) }
    var deletingCsv by remember { mutableStateOf<com.hanshin.healthtask.data.db.InBodyRecordEntity?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let(vm::importInBodyScreenshot)
    }
    val csvPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::importInBodyCsv) }
    val health = state.health.filter { it.type in healthTrendMetricTypes || it.externalRecordId?.startsWith("inbody-csv-") == true }
        .sortedWith(compareByDescending<HealthMeasurementEntity> { it.recordDate }.thenByDescending { it.measuredAt })
    InBodyRecordsContent(
        health = health, reading = state.inBodyImport != InBodyImportUiState.Idle,
        onAdd = { adding = true }, onImport = { picker.launch("image/*") },
        onEdit = { editing = it }, onDelete = { deleting = it },
        csvRecords = state.inBodyRecords,
        onImportCsv = { csvPicker.launch(arrayOf("text/*", "application/csv", "application/vnd.ms-excel", "application/octet-stream")) },
        onDeleteCsv = { deletingCsv = it },
        graphPreferences = state.inBodyGraphPreferences,
        onGraphPreferencesChange = vm::setInBodyGraphPreferences,
    )
    if (adding || editing != null) HealthEntryDialog(
        existing = editing,
        onDismiss = { adding = false; editing = null },
        onSave = { date, type, value ->
            vm.saveHealth(date, type, value, editing?.id)
            adding = false
            editing = null
        },
    )
    deleting?.let { metric ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("인바디 기록 삭제") },
            text = { Text("${metric.recordDate}의 ${metricLabel(metric.type)} 기록을 삭제할까요?") },
            confirmButton = { TextButton(onClick = { vm.deleteHealth(metric.id); deleting = null }) { Text("삭제") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("취소") } },
        )
    }
    (state.inBodyImport as? InBodyImportUiState.Review)?.let { review ->
        InBodyReviewDialog(review.result, vm::dismissInBodyImport, vm::saveInBodyImport)
    }
    (state.inBodyImport as? InBodyImportUiState.CsvReview)?.let { review ->
        InBodyCsvReviewDialog(review.result, vm::dismissInBodyImport, vm::saveInBodyCsv)
    }
    if (state.inBodyImport is InBodyImportUiState.Saving) AlertDialog(
        onDismissRequest = {}, title = { Text("인바디 기록 저장 중") },
        text = { CircularProgressIndicator() }, confirmButton = {},
    )
    deletingCsv?.let { record ->
        AlertDialog(onDismissRequest = { deletingCsv = null }, title = { Text("인바디 측정 기록 삭제") },
            text = { Text("${record.measuredLocal.replace('T', ' ')} · ${record.device}의 모든 수치와 앱 안의 원본 항목을 삭제할까요? CSV 파일은 그대로 남습니다.") },
            confirmButton = { TextButton(onClick = { vm.deleteInBodyRecord(record.id); deletingCsv = null }) { Text("삭제") } },
            dismissButton = { TextButton(onClick = { deletingCsv = null }) { Text("취소") } })
    }
}


@Composable
internal fun InBodyRecordsContent(
    health: List<HealthMeasurementEntity>,
    reading: Boolean = false,
    onAdd: () -> Unit,
    onImport: () -> Unit,
    onEdit: (HealthMeasurementEntity) -> Unit,
    onDelete: (HealthMeasurementEntity) -> Unit,
    csvRecords: List<com.hanshin.healthtask.data.db.InBodyRecordEntity> = emptyList(),
    onImportCsv: () -> Unit = {},
    onDeleteCsv: (com.hanshin.healthtask.data.db.InBodyRecordEntity) -> Unit = {},
    graphPreferences: com.hanshin.healthtask.data.InBodyGraphPreferences? = null,
    onGraphPreferencesChange: (com.hanshin.healthtask.data.InBodyGraphPreferences) -> Unit = {},
) {
    var recordsTab by rememberSaveable { mutableStateOf(false) }
    val hasGraphs = remember(health) { bodyHistoryMeasurements(health).isNotEmpty() }
    val showingRecords = recordsTab || !hasGraphs
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            listOf("변화", "측정 기록").forEachIndexed { index, label ->
                val selected = (index == 1) == showingRecords
                Column(Modifier.weight(1f).selectable(selected, enabled = index == 1 || hasGraphs,
                    role = androidx.compose.ui.semantics.Role.Tab, onClick = { recordsTab = index == 1 })
                    .testTag(if (index == 0) "body-trends-tab" else "body-records-tab"), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(label, Modifier.padding(vertical = 14.dp), style = MaterialTheme.typography.titleSmall,
                        color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                    HorizontalDivider(thickness = 3.dp, color = if (selected) MaterialTheme.colorScheme.onSurface else Color.Transparent)
                }
            }
        }
        Box(Modifier.weight(1f)) {
            if (!showingRecords) HealthTrendChart(health, csvRecords, graphPreferences, onGraphPreferencesChange)
            else InBodyHistoryContent(health, reading, onAdd, onImport, onEdit, onDelete, csvRecords, onImportCsv, onDeleteCsv)
        }
    }
}

@Composable
private fun InBodyHistoryContent(
    health: List<HealthMeasurementEntity>, reading: Boolean,
    onAdd: () -> Unit, onImport: () -> Unit,
    onEdit: (HealthMeasurementEntity) -> Unit, onDelete: (HealthMeasurementEntity) -> Unit,
    csvRecords: List<com.hanshin.healthtask.data.db.InBodyRecordEntity>,
    onImportCsv: () -> Unit, onDeleteCsv: (com.hanshin.healthtask.data.db.InBodyRecordEntity) -> Unit,
) {
    val dailyRecords = health.filterNot { it.externalRecordId?.startsWith("inbody-csv-") == true }
        .groupBy { it.recordDate }.toSortedMap(compareByDescending { it })
    LazyColumn(
        Modifier.fillMaxSize().testTag("inbody-records"),
        contentPadding = PaddingValues(20.dp, 8.dp, 20.dp, 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            AppCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    AppIconBadge(Icons.Rounded.MonitorWeight, accent = true)
                    Column(Modifier.weight(1f)) {
                        Text("변화를 기록하는 습관", style = MaterialTheme.typography.titleMedium)
                        Text("숫자 하나부터 천천히 쌓아보세요.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(20.dp))
                Button(onClick = onImportCsv, enabled = !reading,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("import-inbody-csv"), shape = RoundedCornerShape(16.dp)) {
                    Icon(Icons.Rounded.FileDownload, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (reading) "처리 중…" else "인바디 CSV 가져오기")
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = onAdd, modifier = Modifier.weight(1f).heightIn(min = 52.dp).testTag("add-inbody"),
                        shape = RoundedCornerShape(16.dp)) {
                        Icon(Icons.Rounded.Add, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("직접 입력")
                    }
                    OutlinedButton(onClick = onImport, enabled = !reading,
                        modifier = Modifier.weight(1f).heightIn(min = 52.dp), shape = RoundedCornerShape(16.dp)) {
                        if (reading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Rounded.PhotoCamera, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(5.dp))
                        Text(if (reading) "인식 중…" else "캡처 불러오기")
                    }
                }
            }
        }
        if (health.isEmpty() && csvRecords.isEmpty()) {
            item { EmptyRecordState(Icons.Rounded.Insights, "몸의 변화를 만나보세요",
                "CSV 파일로 상세 측정값을 한 번에 가져오세요.\n직접 입력과 캡처 불러오기도 가능합니다.") }
        } else {
            if (csvRecords.isNotEmpty()) {
                item { SectionHeading("CSV 측정 기록", "${csvRecords.size}회 측정") }
                items(csvRecords, key = { it.id }) { record -> InBodyCsvRecordCard(record, onDeleteCsv) }
            }
            if (dailyRecords.isNotEmpty()) item { SectionHeading("직접 입력·연동 기록", "${dailyRecords.size}일의 기록") }
            items(dailyRecords.entries.toList(), key = { it.key }) { (date, metrics) ->
                AppCard(modifier = Modifier.testTag("body-daily-$date")) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(recordDateLabel(date), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        StatusPill("${metrics.size}개 항목")
                    }
                    Spacer(Modifier.height(8.dp))
                    metrics.forEachIndexed { index, metric ->
                        if (index > 0) HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
                        Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(metricLabel(metric.type), style = MaterialTheme.typography.titleSmall)
                                    Text(healthSourceLabel(metric), style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text("${bodyValueText(metric.value)} ${metricUnit(metric.type)}",
                                    style = MaterialTheme.typography.titleLarge)
                            }
                            if (!metric.source.isExternal()) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                TextButton(onClick = { onEdit(metric) }) { Text("수정") }
                                TextButton(onClick = { onDelete(metric) },
                                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)) { Text("삭제") }
                            } else Text("수정·삭제는 원본 앱에서", Modifier.padding(top = 6.dp),
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

private fun recordDateLabel(value: String): String = runCatching {
    LocalDate.parse(value).format(DateTimeFormatter.ofPattern("M월 d일 EEEE", Locale.KOREA))
}.getOrDefault(value)

@Composable
internal fun HealthEntryDialog(
    existing: HealthMeasurementEntity? = null,
    onDismiss: () -> Unit,
    onSave: (LocalDate, HealthMetricType, Double) -> Unit,
) {
    var type by remember(existing) { mutableStateOf(existing?.type ?: HealthMetricType.WEIGHT_KG) }
    var value by remember(existing) { mutableStateOf(existing?.value?.let(::formatNumber).orEmpty()) }
    var dateText by remember(existing) { mutableStateOf(existing?.recordDate ?: LocalDate.now().toString()) }
    val date = runCatching { LocalDate.parse(dateText.trim()) }.getOrNull()?.takeUnless { it.isAfter(LocalDate.now()) }
    val parsed = value.trim().toDoubleOrNull()?.takeIf { isValidBodyValue(type, it) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "인바디 직접 입력" else "인바디 기록 수정") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MeasurementDateField(dateText, { dateText = it }, Modifier.testTag("body-entry-date"))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    (listOf(HealthMetricType.WEIGHT_KG) + healthTrendMetricTypes.filterNot { it == HealthMetricType.WEIGHT_KG }).forEach { candidate ->
                        FilterChip(selected = type == candidate, onClick = { type = candidate },
                            label = { Text(metricLabel(candidate)) })
                    }
                }
                OutlinedTextField(value, { value = it }, label = { Text("${metricLabel(type)} (${metricUnit(type)})") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    isError = value.isNotBlank() && parsed == null, singleLine = true,
                    modifier = Modifier.testTag("body-entry-value"))
            }
        },
        confirmButton = { TextButton(enabled = date != null && parsed != null,
            onClick = { onSave(requireNotNull(date), type, requireNotNull(parsed)) },
            modifier = Modifier.testTag("save-body-entry")) { Text("저장") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

private val inBodyImportTypes = healthTrendMetricTypes

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
    val parsedDate = runCatching { LocalDate.parse(dateText.trim()) }.getOrNull()?.takeUnless { it.isAfter(LocalDate.now()) }
    val invalidValues = inBodyImportTypes.any { type ->
        val text = valueText[type].orEmpty().trim()
        text.isNotEmpty() && text.toDoubleOrNull()?.let { isValidBodyValue(type, it) } != true
    }
    val parsedValues = inBodyImportTypes.mapNotNull { type ->
        valueText[type]?.trim()?.toDoubleOrNull()?.takeIf { isValidBodyValue(type, it) }?.let { type to it }
    }.toMap()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("인바디 캡처 확인") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("자동 인식한 수치를 확인해 주세요. 잘못된 값은 수정하고, 보이지 않는 항목은 비워도 됩니다.")
                MeasurementDateField(dateText, { dateText = it })
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
                enabled = parsedDate != null && parsedValues.isNotEmpty() && !invalidValues,
                onClick = { onSave(requireNotNull(parsedDate), parsedValues) },
            ) { Text("저장") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

@Composable
private fun SettingsPage(state: MainUiState, vm: MainViewModel) {
    val context = LocalContext.current
    val cloudStatus by vm.cloudStatus.collectAsStateWithLifecycle()
    var cloudCode by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        while (true) { vm.refreshCloudStatus(); kotlinx.coroutines.delay(5_000) }
    }
    val permissionLauncher = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { vm.sync(force = true) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> uri?.let { vm.exportBackup(context.contentResolver, it) } }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { vm.importBackup(context.contentResolver, it) } }
    LazyColumn(
        Modifier.fillMaxSize().testTag("settings-page"), contentPadding = PaddingValues(20.dp, 8.dp, 20.dp, 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            AppCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AppIconBadge(Icons.Rounded.Tune)
                    Text("러닝 안내", style = MaterialTheme.typography.titleMedium)
                }
                Spacer(Modifier.height(16.dp))
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
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AppIconBadge(Icons.Rounded.Link)
                    Column(Modifier.weight(1f)) {
                        Text("앱 연결", style = MaterialTheme.typography.titleMedium)
                        Text("Health Connect · 삼성 헬스 · NRC", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(14.dp))
                StatusPill(healthStatusLabel(state.healthStatus))
                Spacer(Modifier.height(12.dp))
                Text("러닝·체성분과 허용한 수면·걸음·심박을 연결합니다. GPS 기록은 연결 없이도 사용할 수 있어요.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("앱을 열 때 동기화", style = MaterialTheme.typography.titleSmall)
                        Text("연결된 앱의 새 기록을 가져옵니다.", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(state.syncPreferences.enabled, onCheckedChange = vm::setSyncEnabled)
                }
                Text("최근 동기화 · ${state.syncPreferences.lastSyncAt?.let(::formatTime) ?: "아직 없음"}",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                if (state.healthStatus != HealthConnectStatus.UNAVAILABLE) {
                    Button(onClick = { permissionLauncher.launch(vm.requiredPermissions) }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Rounded.Link, null)
                        Spacer(Modifier.width(6.dp))
                        Text("권한 연결 · 추가 항목 허용")
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
                Text("health-web 클라우드 연결", style = MaterialTheme.typography.titleMedium)
                Text(cloudStatus, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(10.dp))
                Text("웹의 연결·설정에서 발급한 일회용 코드를 붙여넣으세요. 연결 후 러닝·인바디·권한을 허용한 삼성 헬스 기록을 Supabase에 전송합니다.")
                if (cloudStatus == "연결 안 됨") {
                    OutlinedTextField(cloudCode, { cloudCode = it.trim() }, label = { Text("일회용 연결 코드") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Button(onClick = { vm.pairCloud(cloudCode); cloudCode = "" }, enabled = cloudCode.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("웹 연결 및 전체 기록 전송") }
                } else {
                    OutlinedButton(onClick = vm::syncCloud, modifier = Modifier.fillMaxWidth()) { Text("지금 클라우드 전송") }
                    TextButton(onClick = vm::disconnectCloud, modifier = Modifier.fillMaxWidth()) { Text("연결 해제 · 기기 권한 폐기") }
                }
                Text("연결 해제 시 웹의 기존 기록은 보존됩니다. 예약 전송은 네트워크와 Android 백그라운드 실행 정책에 따라 지연될 수 있습니다.", style = MaterialTheme.typography.bodySmall)
            }
        }
        item {
            AppCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AppIconBadge(Icons.Rounded.Shield)
                    Text("JSON 백업", style = MaterialTheme.typography.titleMedium)
                }
                Spacer(Modifier.height(14.dp))
                Text("전체 기간의 러닝·GPS 좌표·구간·인바디와 새 워치 기록의 시간별 심박·페이스·케이던스·고도·센서 정확도를 담습니다. 기기 지원 범위·단위·출처·복원용 데이터도 포함합니다.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Text("연동 기록은 현재 휴대폰에 저장된 범위만 분석용으로 포함됩니다. 정확한 위치와 건강 정보가 담기므로 파일 공유에 주의하세요.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Button(onClick = { exportLauncher.launch("today-running-${LocalDate.now()}.json") }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.UploadFile, null)
                    Spacer(Modifier.width(6.dp))
                    Text("상세 데이터 내보내기 (JSON)")
                }
                OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/plain")) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.FileDownload, null)
                    Spacer(Modifier.width(6.dp))
                    Text("백업 불러오기 (v1–v5)")
                }
            }
        }
        item {
            Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("오늘러닝", style = MaterialTheme.typography.titleSmall, color = RunPalette.Muted)
                Text("나의 기록은 나의 기기에 · Android 14+", style = MaterialTheme.typography.labelSmall, color = RunPalette.Muted)
            }
        }
    }
}

private fun sourceLabel(source: WorkoutSource) = when (source) {
    WorkoutSource.LOCAL -> "오늘러닝"
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
internal fun formatRunDuration(millis: Long): String {
    val totalSeconds = millis.coerceAtLeast(0L) / 1_000L
    return "%02d:%02d:%02d".format(totalSeconds / 3_600L, (totalSeconds / 60L) % 60L, totalSeconds % 60L)
}
internal fun formatRunPace(pace: Double?): String {
    val totalSeconds = pace?.takeIf { it.isFinite() && it in 0.0..60.0 }?.times(60.0)?.toInt() ?: return "--'--\""
    return "%d'%02d\"".format(totalSeconds / 60, totalSeconds % 60)
}
internal fun formatRunDistance(distanceKm: Double): String =
    String.format(Locale.KOREA, if (distanceKm < 10.0) "%.2f" else "%.1f", distanceKm.coerceAtLeast(0.0))
private fun metricLabel(type: HealthMetricType) = type.label
internal fun metricUnit(type: HealthMetricType) = when (type.unit) {
    "level" -> "단계"
    "points" -> "점"
    "years" -> "세"
    "ratio" -> "비율"
    "code" -> "원본 코드"
    else -> type.unit
}
internal fun healthSourceLabel(metric: HealthMeasurementEntity): String =
    if (metric.externalRecordId?.startsWith("inbody-csv-") == true) "인바디 CSV"
    else if (metric.sourcePackage == INBODY_PACKAGE) "인바디 캡처" else sourceLabel(metric.source)
private fun formatNumber(value: Double) = if (value % 1.0 == 0.0) value.toInt().toString() else String.format(Locale.KOREA, "%.1f", value)
