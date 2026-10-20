package com.hanshin.healthtask.wear.health

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import com.hanshin.healthtask.shared.WearRunningMetrics
import com.hanshin.healthtask.wear.R
import com.hanshin.healthtask.wear.WearMainActivity
import java.util.Locale

/** One OngoingActivity per service session; ordinary metric updates reuse its notification. */
internal class WearWorkoutNotification(context: Context, notificationId: Int, channelId: String) {
    private val openIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, WearMainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    private val builder = NotificationCompat.Builder(context, channelId)
        .setSmallIcon(R.drawable.ic_stat_fitness)
        .setContentTitle(context.getString(R.string.exercise_notification_title))
        .setCategory(NotificationCompat.CATEGORY_WORKOUT)
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        .setLocalOnly(true)
        .setOnlyAlertOnce(true)
        .setOngoing(true)
        .setContentIntent(openIntent)

    init {
        OngoingActivity.Builder(context, notificationId, builder)
            .setStaticIcon(R.drawable.ic_stat_fitness)
            .setTouchIntent(openIntent)
            // Session label stays valid when paused. Live details belong to the notification.
            .setStatus(Status.Builder().addTemplate("#activity#")
                .addPart("activity", Status.TextPart("러닝")).build())
            .setContentDescription("진행 중인 러닝으로 돌아가기")
            .build()
            .apply(context)
    }

    fun build(metrics: WearMetrics): Notification {
        val status = if (metrics.paused) "일시정지" else "러닝 중"
        val distance = metrics.distanceKm?.let { String.format(Locale.KOREA, " · %.2f km", it) }.orEmpty()
        val pace = if (metrics.paused) null else WearRunningMetrics.currentPaceMinutesPerKm(metrics.speedMetersPerSecond)
        val paceText = pace?.takeIf { it.isFinite() && it in 0.0..60.0 }?.let {
            val seconds = (it * 60.0).toInt()
            String.format(Locale.KOREA, " · %d'%02d\"/km", seconds / 60, seconds % 60)
        }.orEmpty()
        val heartRate = metrics.heartRateBpm?.toInt()?.let { " · 심박 $it" }.orEmpty()
        return builder.setContentText("$status$distance$paceText$heartRate").build()
    }
}
