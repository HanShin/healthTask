package com.hanshin.healthtask.wear

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.AmbientMode
import androidx.wear.compose.foundation.AmbientTickEffect
import androidx.wear.compose.foundation.LocalAmbientModeManager
import androidx.wear.compose.material3.Text
import com.hanshin.healthtask.shared.elapsedMillis
import com.hanshin.healthtask.shared.usesGpsRunning
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal data class AmbientRunSnapshot(
    val clock: String,
    val status: String,
    val distance: String?,
    val duration: String?,
)

internal fun ambientRunSnapshot(state: WearUiState, now: Long): AmbientRunSnapshot {
    val active = state.active?.takeIf { it.routine.usesGpsRunning }
    return AmbientRunSnapshot(
        clock = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm")),
        status = when {
            active == null -> "오늘러닝"
            active.paused -> "일시정지"
            else -> "러닝 중"
        },
        distance = active?.let {
            state.metrics.distanceKm?.takeIf { it.isFinite() && it >= 0.0 }
                ?.let { String.format(Locale.KOREA, "%.2f km", it) } ?: "-- km"
        },
        // Ambient updates are minute-based: never display a frozen second counter or live pace.
        duration = active?.let { "누적 ${it.elapsedMillis(now).coerceAtLeast(0L) / 60_000}분" },
    )
}

@Composable
internal fun WearAmbientLayout(state: WearUiState, interactiveContent: @Composable () -> Unit) {
    val manager = LocalAmbientModeManager.current
    if (manager?.currentAmbientMode is AmbientMode.Ambient) {
        val latestState by rememberUpdatedState(state)
        var snapshot by remember(state.active?.sessionId, state.active?.paused) {
            mutableStateOf(ambientRunSnapshot(state, System.currentTimeMillis()))
        }
        manager.AmbientTickEffect {
            snapshot = ambientRunSnapshot(latestState, System.currentTimeMillis())
        }
        AmbientRunningScreen(snapshot)
    } else {
        // Leaving composition cancels the interactive screen's one-second timer in ambient mode.
        interactiveContent()
    }
}

@Composable
internal fun AmbientRunningScreen(snapshot: AmbientRunSnapshot) {
    // Black background, no animated controls, and safe edges for the system's burn-in shift.
    Column(
        Modifier.fillMaxSize().background(Color.Black).padding(horizontal = 22.dp, vertical = 18.dp)
            .testTag("ambient-running"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
    ) {
        Text(snapshot.clock, color = Color.White, fontSize = 12.sp)
        Text(snapshot.status, color = Color.White, fontSize = 14.sp)
        snapshot.distance?.let {
            Text(it, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Normal)
            Text(snapshot.duration.orEmpty(), color = Color.White, fontSize = 13.sp)
            Text("최근 기록 · ${snapshot.clock}", color = Color.White, fontSize = 10.sp)
        }
        Text("손목을 올려 상세 보기", color = Color.White, fontSize = 10.sp)
    }
}
