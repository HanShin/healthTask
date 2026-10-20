package com.hanshin.healthtask.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.doOnLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.hanshin.healthtask.running.OSM_ATTRIBUTION
import com.hanshin.healthtask.running.OSM_COPYRIGHT_URL
import com.hanshin.healthtask.running.RUNNING_MAP_STYLE
import com.hanshin.healthtask.running.RouteMapRuntime
import com.hanshin.healthtask.running.RunningPoint
import com.hanshin.healthtask.running.routeMapPoints
import kotlinx.coroutines.delay
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

@Composable
internal fun RunningRoutePreview(points: List<RunningPoint>, modifier: Modifier = Modifier) {
    val validPoints = remember(points) { routeMapPoints(points) }
    var expanded by remember { mutableStateOf(false) }
    if (validPoints.size < 2) {
        RunningRouteTrace(validPoints, modifier)
        return
    }
    Box(modifier.clip(RoundedCornerShape(18.dp))) {
        if (expanded) RunningRouteTrace(validPoints, Modifier.fillMaxSize())
        else RunningRouteMap(validPoints, Modifier.fillMaxSize(), interactive = false)
        Surface(Modifier.align(Alignment.TopEnd).padding(6.dp), shape = RoundedCornerShape(12.dp)) {
            TextButton(onClick = { expanded = true }, modifier = Modifier.testTag("expand-route-map")) {
                Text("크게 보기")
            }
        }
    }
    if (expanded) Dialog(
        onDismissRequest = { expanded = false },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("러닝 이동 경로", style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = { expanded = false }) { Text("닫기") }
                }
                RunningRouteMap(validPoints, Modifier.fillMaxSize(), interactive = true)
            }
        }
    }
}

@Composable
internal fun RunningRouteMap(
    points: List<RunningPoint>,
    modifier: Modifier = Modifier,
    interactive: Boolean = false,
    // Allows instrumentation to render local fixtures without contacting the public tile service.
    styleJson: String = RUNNING_MAP_STYLE,
    networkAvailable: Boolean = rememberMapNetworkAvailable(),
) {
    var attempt by remember { mutableIntStateOf(0) }
    var refit by remember { mutableIntStateOf(0) }
    var failed by remember(attempt, networkAvailable) { mutableStateOf(false) }
    var rendered by remember(attempt, networkAvailable) { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current

    LaunchedEffect(attempt, networkAvailable, rendered) {
        if (networkAvailable && !rendered) {
            delay(15_000L)
            failed = true
        }
    }
    Box(modifier.clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surface)) {
        if (networkAvailable && !failed) key(attempt) {
            NativeRouteMap(
                points, Modifier.fillMaxSize(), interactive, refit, styleJson,
                onRendered = { rendered = true },
                onFailure = { failed = true },
            )
        }
        if (!networkAvailable || failed || !rendered) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
                RunningRouteTrace(points, Modifier.fillMaxSize().padding(top = 40.dp, bottom = 52.dp)
                    .testTag("route-map-fallback"))
            }
            Surface(Modifier.align(Alignment.BottomCenter).padding(6.dp), shape = RoundedCornerShape(12.dp)) {
                if (failed) {
                    TextButton(onClick = { attempt++ }, modifier = Modifier.testTag("retry-route-map")) {
                        Text("지도를 불러오지 못했어요 · 다시 시도")
                    }
                } else Text(
                    if (!networkAvailable) "오프라인 · 이동 궤적을 표시합니다" else "배경 지도를 불러오는 중…",
                    Modifier.padding(10.dp), style = MaterialTheme.typography.bodySmall,
                )
            }
        } else {
            Row(
                Modifier.align(Alignment.TopStart).padding(8.dp)
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp)).padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("● 시작", color = Color(0xff15803d), style = MaterialTheme.typography.labelSmall)
                Text("● 도착", color = Color(0xffdc2626), style = MaterialTheme.typography.labelSmall)
            }
            Surface(Modifier.align(Alignment.BottomStart), color = MaterialTheme.colorScheme.surface) {
                TextButton(onClick = { uriHandler.openUri(OSM_COPYRIGHT_URL) },
                    modifier = Modifier.testTag("osm-attribution")) {
                    Text(OSM_ATTRIBUTION, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        if (interactive) Surface(
            Modifier.align(Alignment.TopEnd).padding(8.dp), shape = RoundedCornerShape(12.dp),
        ) {
            TextButton(onClick = { refit++ }, modifier = Modifier.testTag("fit-route-map")) { Text("전체 경로") }
        }
    }
}

@Composable
private fun NativeRouteMap(
    points: List<RunningPoint>, modifier: Modifier, interactive: Boolean, refit: Int, styleJson: String,
    onRendered: () -> Unit, onFailure: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentPoints by rememberUpdatedState(points)
    val renderedCallback by rememberUpdatedState(onRendered)
    val failureCallback by rememberUpdatedState(onFailure)
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    var disposed by remember { mutableStateOf(false) }
    val view = remember(context) {
        runCatching {
            RouteMapRuntime.initialize(context)
            MapView(context, MapLibreMapOptions.createFromAttributes(context).textureMode(true)).apply {
                onCreate(null)
                addOnDidFailLoadingMapListener { if (!disposed) failureCallback() }
                addOnDidFinishRenderingMapListener { fully ->
                    if (!disposed && fully && style != null) renderedCallback()
                }
            }
        }.getOrNull()
    }
    if (view == null) {
        LaunchedEffect(Unit) { failureCallback() }
        return
    }
    DisposableEffect(view, lifecycle) {
        var started = false
        var resumed = false
        var destroyed = false
        fun stop() {
            if (resumed) { view.onPause(); resumed = false }
            if (started) { view.onStop(); started = false }
        }
        val observer = LifecycleEventObserver { _, event ->
            if (!destroyed) when (event) {
                Lifecycle.Event.ON_START -> if (!started) { view.onStart(); started = true }
                Lifecycle.Event.ON_RESUME -> if (!resumed) { view.onResume(); resumed = true }
                Lifecycle.Event.ON_PAUSE -> if (resumed) { view.onPause(); resumed = false }
                Lifecycle.Event.ON_STOP -> stop()
                Lifecycle.Event.ON_DESTROY -> { stop(); disposed = true; view.onDestroy(); destroyed = true }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            disposed = true
            if (!destroyed) { stop(); view.onDestroy() }
        }
    }
    AndroidView(factory = { view }, modifier = modifier.testTag("running-native-map"))
    LaunchedEffect(view, styleJson) {
        view.getMapAsync { readyMap ->
            if (disposed) return@getMapAsync
            map = readyMap
            readyMap.setPrefetchZoomDelta(0)
            readyMap.setMaxZoomPreference(19.0)
            readyMap.uiSettings.apply {
                isLogoEnabled = false
                isAttributionEnabled = false // Always-visible linked attribution is drawn above the map.
                isCompassEnabled = false
                setAllGesturesEnabled(interactive)
                isRotateGesturesEnabled = false
                isTiltGesturesEnabled = false
            }
            view.doOnLayout {
                if (disposed) return@doOnLayout
                fitRoute(readyMap, currentPoints, context)
                readyMap.setStyle(Style.Builder().fromJson(styleJson)) { loadedStyle ->
                    if (disposed) return@setStyle
                    addRouteLayers(loadedStyle)
                    updateRoute(loadedStyle, currentPoints)
                    style = loadedStyle
                }
            }
        }
    }
    LaunchedEffect(style, points) {
        val currentStyle = style ?: return@LaunchedEffect
        updateRoute(currentStyle, points)
        // Embedded previews follow progress. Expanded maps retain the user's pan/zoom.
        if (!interactive) map?.let { fitRoute(it, points, context) }
    }
    LaunchedEffect(refit) { map?.let { fitRoute(it, points, context) } }
}

private fun addRouteLayers(style: Style) {
    for (id in listOf("run-route", "run-start", "run-end")) style.addSource(GeoJsonSource(id))
    style.addLayer(LineLayer("run-outline", "run-route").withProperties(
        lineColor("#ffffff"), lineWidth(7f), lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND),
    ))
    style.addLayer(LineLayer("run-line", "run-route").withProperties(
        lineColor("#1565c0"), lineWidth(4f), lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND),
    ))
    style.addLayer(CircleLayer("run-start-dot", "run-start").withProperties(
        circleColor("#15803d"), circleRadius(7f), circleStrokeColor("#ffffff"), circleStrokeWidth(2f),
    ))
    style.addLayer(CircleLayer("run-end-dot", "run-end").withProperties(
        circleColor("#dc2626"), circleRadius(7f), circleStrokeColor("#ffffff"), circleStrokeWidth(2f),
    ))
}

private fun updateRoute(style: Style, points: List<RunningPoint>) {
    if (points.size < 2) return
    val coordinates = points.map { Point.fromLngLat(it.longitude, it.latitude) }
    style.getSourceAs<GeoJsonSource>("run-route")?.setGeoJson(LineString.fromLngLats(coordinates))
    style.getSourceAs<GeoJsonSource>("run-start")?.setGeoJson(coordinates.first())
    style.getSourceAs<GeoJsonSource>("run-end")?.setGeoJson(coordinates.last())
}

private fun fitRoute(map: MapLibreMap, points: List<RunningPoint>, context: Context) {
    if (points.isEmpty()) return
    val coordinates = points.map { LatLng(it.latitude, it.longitude) }
    val bounds = LatLngBounds.Builder().includes(coordinates).build()
    val padding = (48 * context.resources.displayMetrics.density).toInt()
    val update = if (bounds.latitudeSpan < 0.00001 && bounds.longitudeSpan < 0.00001) {
        CameraUpdateFactory.newLatLngZoom(coordinates.last(), 17.0)
    } else CameraUpdateFactory.newLatLngBounds(bounds, padding)
    map.moveCamera(update)
}

@Composable
private fun rememberMapNetworkAvailable(): Boolean {
    val context = LocalContext.current
    val manager = remember(context) { context.getSystemService(ConnectivityManager::class.java) }
    fun connected(): Boolean = manager.getNetworkCapabilities(manager.activeNetwork)
        ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    var available by remember { mutableStateOf(connected()) }
    DisposableEffect(manager) {
        val handler = Handler(Looper.getMainLooper())
        var active = true
        val callback = object : ConnectivityManager.NetworkCallback() {
            private fun refresh() { handler.post { if (active) available = connected() } }
            override fun onAvailable(network: Network) = refresh()
            override fun onLost(network: Network) = refresh()
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = refresh()
        }
        manager.registerDefaultNetworkCallback(callback)
        onDispose { active = false; manager.unregisterNetworkCallback(callback) }
    }
    return available
}
