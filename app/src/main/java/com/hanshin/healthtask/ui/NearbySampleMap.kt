package com.hanshin.healthtask.ui

import android.Manifest
import android.location.Location
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.hanshin.healthtask.running.RunningMetrics
import com.hanshin.healthtask.running.RunningPoint
import com.hanshin.healthtask.running.currentSampleLocation
import com.hanshin.healthtask.running.hasSampleLocationPermission
import com.hanshin.healthtask.running.nearbySampleRoute
import java.util.Locale

@Composable
internal fun NearbySampleMapButton(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var showPreview by remember { mutableStateOf(false) }
    var permissionDenied by remember { mutableStateOf(false) }
    // Kept separate from the run-start launcher: preview permission must never start a workout.
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        showPreview = hasSampleLocationPermission(context)
        permissionDenied = !showPreview
    }
    OutlinedButton(onClick = {
        permissionDenied = false
        if (hasSampleLocationPermission(context)) showPreview = true
        else permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }, modifier = modifier.testTag("nearby-sample-map")) { Text("내 주변 샘플 지도") }
    if (permissionDenied) Text(
        "주변 지도를 보려면 위치 권한이 필요합니다. 설정에서 허용한 뒤 다시 눌러 주세요.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
    )
    if (showPreview) NearbySampleMapDialog(onDismiss = { showPreview = false })
}

@Composable
internal fun NearbySampleMapDialog(
    onDismiss: () -> Unit,
    locationReader: suspend () -> Location? = defaultSampleLocationReader(),
    // Instrumentation uses a local style without public map traffic or device location access.
    mapContent: @Composable (List<RunningPoint>, Modifier) -> Unit = { points, modifier ->
        RunningRouteMap(points, modifier, interactive = true)
    },
) {
    var attempt by remember { mutableIntStateOf(0) }
    var location by remember(attempt) { mutableStateOf<Location?>(null) }
    var loading by remember(attempt) { mutableStateOf(true) }
    LaunchedEffect(attempt) {
        location = locationReader()
        loading = false
    }
    val route = remember(location) { location?.let { nearbySampleRoute(it.latitude, it.longitude) } }
    val distance = remember(route) { route?.zipWithNext()?.sumOf { (a, b) ->
        RunningMetrics.distanceMeters(a.latitude, a.longitude, b.latitude, b.longitude)
    } }
    Dialog(onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("테스트 · 주변 한 바퀴", style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = onDismiss, modifier = Modifier.testTag("close-sample-map")) { Text("닫기") }
                }
                Text("가상 경로 · 운동 기록에 저장되지 않습니다",
                    Modifier.padding(horizontal = 16.dp, vertical = 4.dp).testTag("sample-not-saved"),
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text("실제 도로를 따라 만든 경로가 아닌 지도 표시용 샘플입니다.",
                    Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall)
                if (distance != null) Text(
                    String.format(Locale.KOREA, "한 바퀴 약 %.2f km · 위치 오차 약 %d m", distance / 1_000.0, location!!.accuracy.toInt()),
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium)
                if (route != null) mapContent(route, Modifier.fillMaxSize())
                else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(24.dp)) {
                        if (loading) {
                            CircularProgressIndicator()
                            Text("현재 위치를 확인하는 중…")
                        } else {
                            Text("현재 위치를 찾지 못했어요. 위치 서비스를 켜고 다시 시도해 주세요.")
                            TextButton(onClick = { attempt++ }, modifier = Modifier.testTag("retry-sample-location")) {
                                Text("위치 다시 확인")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun defaultSampleLocationReader(): suspend () -> Location? {
    val context = LocalContext.current
    return remember(context) { { currentSampleLocation(context) } }
}
