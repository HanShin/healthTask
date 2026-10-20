package com.hanshin.healthtask.running

import android.content.Context
import com.hanshin.healthtask.BuildConfig
import java.io.File
import java.util.concurrent.TimeUnit
import okhttp3.Cache
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import org.maplibre.android.MapLibre
import org.maplibre.android.module.http.HttpRequestUtil

internal object RouteMapRuntime {
    private var initialized = false

    @Synchronized
    fun initialize(context: Context) {
        if (initialized) return
        MapLibre.getInstance(context.applicationContext)
        HttpRequestUtil.setOkHttpClient(
            OkHttpClient.Builder()
                .cache(Cache(File(context.cacheDir, "route-map-tiles"), 64L * 1024L * 1024L))
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .addInterceptor(OsmRequestIdentification())
                .addNetworkInterceptor(OsmCacheHeaders())
                .build(),
        )
        HttpRequestUtil.setPrintRequestUrlOnFailure(false)
        initialized = true
    }
}

internal class OsmRequestIdentification : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        return chain.proceed(
            if (request.url.host == "tile.openstreetmap.org") {
                request.newBuilder().header(
                    "User-Agent",
                    "TodayWorkout/${BuildConfig.VERSION_NAME} (com.hanshin.healthtask; https://github.com/HanShin/healthTask)",
                ).build()
            } else request,
        )
    }
}

internal class OsmCacheHeaders : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        // Both OkHttp and MapLibre's native cache receive the server's validators and TTL.
        // The OSM policy requires at least 7 days only when no freshness headers are given.
        return if (response.request.url.host == "tile.openstreetmap.org" && response.isSuccessful &&
            response.header("Cache-Control") == null && response.header("Expires") == null
        ) response.newBuilder().header("Cache-Control", "public, max-age=604800").build()
        else response
    }
}
