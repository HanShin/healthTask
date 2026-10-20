package com.hanshin.healthtask.running

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

internal fun hasSampleLocationPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

/** One foreground fix only. Closing the preview cancels every outstanding request. */
@SuppressLint("MissingPermission") // Checked here and rechecked by the platform; revocation is handled below.
internal suspend fun currentSampleLocation(context: Context): Location? {
    if (!hasSampleLocationPermission(context)) return null
    val manager = context.getSystemService(LocationManager::class.java)
    val providers = listOf(LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
        .filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
    if (providers.isEmpty()) return null
    val cancellations = providers.map { CancellationSignal() }
    return try {
        withTimeoutOrNull(20_000L) {
            suspendCancellableCoroutine { continuation ->
                var remaining = providers.size
                fun complete(location: Location?) {
                    if (!continuation.isActive) return
                    remaining--
                    val usable = location?.takeIf {
                        it.latitude.isFinite() && it.latitude in -84.0..84.0 &&
                            it.longitude.isFinite() && it.longitude in -180.0..180.0 &&
                            it.hasAccuracy() && it.accuracy in 0f..1_000f
                    }
                    if (usable != null || remaining == 0) continuation.resume(usable)
                }
                providers.forEachIndexed { index, provider ->
                    if (continuation.isActive) runCatching {
                        manager.getCurrentLocation(provider, cancellations[index], context.mainExecutor, ::complete)
                    }.onFailure { complete(null) }
                }
            }
        }
    } finally {
        cancellations.forEach { it.cancel() }
    }
}
