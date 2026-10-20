package com.hanshin.healthtask.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DirectionsRun
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.hanshin.healthtask.running.RunningPhase
import com.hanshin.healthtask.running.RunningUiState

@Composable
internal fun RunningDashboard(
    running: RunningUiState,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onFinish: () -> Unit,
    onNew: () -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxSize().testTag("running-dashboard"),
        contentPadding = PaddingValues(20.dp, 8.dp, 20.dp, 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { RunningHero(running) }
        item {
            when (running.phase) {
                RunningPhase.IDLE -> Button(
                    onClick = onStart,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp).testTag("start-run"),
                    colors = ButtonDefaults.buttonColors(containerColor = RunPalette.Lime, contentColor = RunPalette.Ink),
                    shape = RoundedCornerShape(20.dp),
                ) {
                    Icon(Icons.Rounded.PlayArrow, null, Modifier.size(25.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("러닝 시작", style = MaterialTheme.typography.titleMedium)
                }
                RunningPhase.COMPLETED -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = onNew, modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp), shape = RoundedCornerShape(20.dp)) {
                        Icon(Icons.Rounded.Replay, null)
                        Spacer(Modifier.width(8.dp))
                        Text("새 러닝", style = MaterialTheme.typography.titleMedium)
                    }
                    Text("러닝 기록 탭에서 경로와 상세 기록을 확인하세요.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val paused = running.phase == RunningPhase.PAUSED || running.phase == RunningPhase.AUTO_PAUSED
                    Button(onClick = if (paused) onResume else onPause,
                        modifier = Modifier.weight(1f).heightIn(min = 60.dp).testTag("pause-resume-run"),
                        shape = RoundedCornerShape(20.dp)) {
                        Icon(if (paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, null)
                        Spacer(Modifier.width(6.dp))
                        Text(if (paused) "재개" else "일시정지")
                    }
                    OutlinedButton(onClick = onFinish,
                        modifier = Modifier.weight(1f).heightIn(min = 60.dp).testTag("finish-run"),
                        shape = RoundedCornerShape(20.dp)) {
                        Icon(Icons.Rounded.Stop, null)
                        Spacer(Modifier.width(6.dp))
                        Text("완료")
                    }
                }
            }
        }
        item { SectionHeading("이동 경로", "GPS 기록") }
        item {
            AppCard {
                RunningRoutePreview(running.route, Modifier.fillMaxWidth().height(if (running.phase == RunningPhase.IDLE) 158.dp else 240.dp))
                Spacer(Modifier.height(12.dp))
                Text("경로는 기기에 저장되며, 배경 지도는 OpenStreetMap에서 불러옵니다.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                NearbySampleMapButton(Modifier.fillMaxWidth())
            }
        }
        if (running.phase != RunningPhase.IDLE || running.laps.isNotEmpty()) {
            item { SectionHeading("구간별 기록", "1km 자동 랩") }
            item { RunningLapsCard(running) }
        }
    }
}

@Composable
internal fun RunningHero(running: RunningUiState) {
    val idle = running.phase == RunningPhase.IDLE
    val status = when (running.phase) {
        RunningPhase.IDLE -> "자유 러닝"
        RunningPhase.ACQUIRING_GPS -> "GPS 연결 중"
        RunningPhase.RUNNING -> "러닝 중"
        RunningPhase.AUTO_PAUSED -> "자동 일시정지"
        RunningPhase.PAUSED -> "일시정지"
        RunningPhase.COMPLETED -> "러닝 완료"
    }
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(RunPalette.Ink)) {
        // Decorative track arcs, not a GPS trace or measured data.
        Canvas(Modifier.matchParentSize()) {
            repeat(3) { index ->
                drawArc(RunPalette.Lime.copy(alpha = 0.06f), 160f, 240f, false,
                    topLeft = Offset(size.width * 0.62f + index * 24.dp.toPx(), -65.dp.toPx()),
                    size = Size(230.dp.toPx(), 230.dp.toPx()), style = Stroke(1.dp.toPx()))
            }
        }
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.AutoMirrored.Rounded.DirectionsRun, null, Modifier.size(25.dp), tint = RunPalette.Lime)
                Spacer(Modifier.width(8.dp))
                Text("TODAY RUN", style = MaterialTheme.typography.labelMedium, color = Color(0xFFBDCBC2), modifier = Modifier.weight(1f))
                StatusPill(status, dark = true)
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(if (idle) "나만의 페이스로." else status,
                    style = MaterialTheme.typography.headlineMedium, color = Color.White)
                Text(when {
                    !running.gpsAvailable -> "기기의 위치 서비스를 켜 주세요."
                    running.phase == RunningPhase.ACQUIRING_GPS -> "정확한 GPS 신호를 기다리고 있어요."
                    running.phase == RunningPhase.AUTO_PAUSED -> "다시 달리면 자동으로 기록을 이어갑니다."
                    idle -> "오늘의 움직임을 기록해 보세요."
                    running.accuracyMeters != null -> "GPS 오차 약 ${running.accuracyMeters.toInt()}m"
                    else -> "거리와 페이스를 실시간으로 기록합니다."
                }, style = MaterialTheme.typography.bodySmall, color = Color(0xFFBDCBC2))
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("러닝 시간", style = MaterialTheme.typography.labelMedium, color = Color(0xFFBDCBC2))
                Text(formatRunDuration(running.elapsedMillis),
                    style = MaterialTheme.typography.displayMedium.copy(fontFeatureSettings = "tnum"), color = Color.White)
            }
            HorizontalDivider(color = Color.White.copy(alpha = 0.12f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SummaryMetric("거리", formatRunDistance(running.distanceMeters / 1_000.0), "km", Modifier.weight(1f), dark = true)
                SummaryMetric("현재 페이스", formatRunPace(running.currentPaceMinPerKm), "/km", Modifier.weight(1f), dark = true)
                SummaryMetric("평균 페이스", formatRunPace(running.averagePaceMinPerKm), "/km", Modifier.weight(1f), dark = true)
            }
        }
    }
}
