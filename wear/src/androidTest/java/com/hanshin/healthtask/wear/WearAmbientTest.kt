package com.hanshin.healthtask.wear

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.wear.compose.foundation.AmbientMode
import androidx.wear.compose.foundation.AmbientModeManager
import androidx.wear.compose.foundation.LocalAmbientModeManager
import androidx.wear.compose.foundation.rememberAmbientModeManager
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import androidx.wear.ongoing.OngoingActivity
import com.hanshin.healthtask.shared.WearActiveSession
import com.hanshin.healthtask.shared.WearQuickRunPreset
import com.hanshin.healthtask.shared.toRoutinePayload
import com.hanshin.healthtask.wear.health.WearMetrics
import com.hanshin.healthtask.wear.health.WearWorkoutNotification
import java.io.File
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class WearAmbientTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val fixture = WearUiState(
        active = WearActiveSession("ui-only-run", WearQuickRunPreset.FREE_RUN.toRoutinePayload(), System.currentTimeMillis() - 1_800_000L),
        metrics = WearMetrics(distanceKm = 5.24, tracking = true),
    )

    @Test fun ambientTransitionStopsInteractiveContentAndRefreshesOnlyOnTick() {
        val manager = FakeAmbientManager()
        var state by mutableStateOf(fixture)
        var interactiveMounted = false
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalAmbientModeManager provides manager) {
                WearAmbientLayout(state) {
                    DisposableEffect(Unit) { interactiveMounted = true; onDispose { interactiveMounted = false } }
                    Text("실시간 러닝")
                }
            }
        } }
        compose.onNodeWithText("실시간 러닝").assertIsDisplayed()
        compose.runOnIdle { manager.mode = AmbientMode.Ambient(false, false) }
        compose.onNodeWithTag("ambient-running").assertIsDisplayed()
        compose.onNodeWithText("5.24 km").assertIsDisplayed()
        compose.runOnIdle { assertFalse(interactiveMounted); state = state.copy(metrics = state.metrics.copy(distanceKm = 5.30)) }
        compose.onNodeWithText("5.24 km").assertIsDisplayed()
        compose.onNodeWithText("5.30 km").assertDoesNotExist()
        compose.runOnIdle { manager.ticks.trySend(Unit) }
        compose.waitUntil { compose.onAllNodesWithText("5.30 km").fetchSemanticsNodes().isNotEmpty() }
        screenshot("ambient-running")
        compose.runOnIdle { manager.mode = AmbientMode.Interactive }
        compose.onNodeWithText("실시간 러닝").assertIsDisplayed()
        compose.onNodeWithTag("ambient-running").assertDoesNotExist()
        compose.runOnIdle { assertTrue(interactiveMounted) }
    }

    @Test fun pausedAndFinishedSessionsUpdateAmbientScreen() {
        val manager = FakeAmbientManager().apply { mode = AmbientMode.Ambient(true, true) }
        var state by mutableStateOf(fixture.copy(active = fixture.active!!.copy(paused = true, pausedAt = System.currentTimeMillis())))
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalAmbientModeManager provides manager) {
                WearAmbientLayout(state) { Text("실시간 러닝") }
            }
        } }
        compose.onNodeWithText("일시정지").assertIsDisplayed()
        compose.runOnIdle { state = state.copy(active = null) }
        compose.onNodeWithText("오늘러닝").assertIsDisplayed()
        compose.onNodeWithText("5.24 km").assertDoesNotExist()
    }

    @Test fun ongoingNotificationSurvivesPauseAndIsRemovedOnCancel() {
        val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
        val notifications = context.getSystemService(NotificationManager::class.java)
        val id = 9911
        val channel = "ambient-test"
        ui.adoptShellPermissionIdentity(Manifest.permission.POST_NOTIFICATIONS)
        try {
            notifications.createNotificationChannel(NotificationChannel(channel, "UI test", NotificationManager.IMPORTANCE_LOW))
            val notification = WearWorkoutNotification(context, id, channel)
            notifications.notify(id, notification.build(fixture.metrics))
            compose.waitUntil(5_000) { OngoingActivity.recoverOngoingActivity(context) { it.notificationId == id } != null }
            val ongoing = requireNotNull(OngoingActivity.recoverOngoingActivity(context) { it.notificationId == id })
            assertNotNull(ongoing.staticIcon)
            assertNotNull(ongoing.touchIntent)
            assertEquals("진행 중인 러닝으로 돌아가기", ongoing.contentDescription)
            val paused = notification.build(fixture.metrics.copy(paused = true))
            notifications.notify(id, paused)
            assertTrue(paused.flags and Notification.FLAG_ONGOING_EVENT != 0)
            assertTrue(paused.extras.getCharSequence(Notification.EXTRA_TEXT).toString().startsWith("일시정지"))
            assertNotNull(OngoingActivity.recoverOngoingActivity(context) { it.notificationId == id })
            notifications.cancel(id)
            compose.waitUntil(5_000) { OngoingActivity.recoverOngoingActivity(context) { it.notificationId == id } == null }
            assertNull(OngoingActivity.recoverOngoingActivity(context) { it.notificationId == id })
        } finally {
            notifications.cancel(id)
            notifications.deleteNotificationChannel(channel)
            ui.dropShellPermissionIdentity()
        }
    }

    @Test fun realSystemSleepAndWakeSwitchAmbientLayout() {
        compose.setContent { MaterialTheme {
            val manager = rememberAmbientModeManager()
            CompositionLocalProvider(LocalAmbientModeManager provides manager) {
                WearAmbientLayout(fixture) { Text("실시간 러닝") }
            }
        } }
        compose.onNodeWithText("실시간 러닝").assertIsDisplayed()
        try {
            shell("input keyevent KEYCODE_SLEEP")
            compose.waitUntil(15_000) { compose.onAllNodesWithTag("ambient-running").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("5.24 km").assertIsDisplayed()
            screenshot("system-ambient")
        } finally {
            shell("input keyevent KEYCODE_WAKEUP")
        }
        compose.waitUntil(15_000) { compose.onAllNodesWithText("실시간 러닝").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("ambient-running").assertDoesNotExist()
    }

    private fun shell(command: String) {
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).use {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()
        }
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val directory = File(context.cacheDir, "ui-review").apply { mkdirs() }
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private class FakeAmbientManager : AmbientModeManager {
        var mode: AmbientMode by mutableStateOf(AmbientMode.Interactive)
        val ticks = Channel<Unit>(Channel.CONFLATED)
        override val currentAmbientMode: AmbientMode get() = mode
        override suspend fun withAmbientTick(block: () -> Unit) {
            for (tick in ticks) block()
        }
    }
}
