package com.hanshin.healthtask.running

import java.net.InetAddress
import okhttp3.Cache
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RouteMapTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `map rejects corrupt and non-Mercator coordinates while preserving a closed run`() {
        val first = RunningPoint(37.5, 127.0, 0L)
        val last = first.copy(elapsedMillis = 60_000L)
        val middle = RunningPoint(37.501, 127.002, 30_000L)
        assertEquals(listOf(first, middle, last), routeMapPoints(listOf(
            first, RunningPoint(Double.NaN, 127.0, 1L),
            RunningPoint(37.5, Double.POSITIVE_INFINITY, 2L),
            RunningPoint(90.0, 0.0, 3L), RunningPoint(0.0, 181.0, 4L), middle, last,
        )))
        assertTrue(routeMapPoints(emptyList()).isEmpty())
    }

    @Test fun `tile requests identify this app and reuse fresh cached tiles`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Cache-Control", "public, max-age=604800")
                .setHeader("ETag", "tile-v1").setBody("test tile"))
            server.start()
            val client = client()
            val url = server.url("/15/27943/12673.png").newBuilder().host("tile.openstreetmap.org").build()
            repeat(2) {
                client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    assertEquals("test tile", response.body!!.string())
                    assertEquals("tile-v1", response.header("ETag"))
                }
            }
            assertEquals(1, server.requestCount)
            val request = server.takeRequest()
            assertTrue(request.getHeader("User-Agent")!!.startsWith("TodayWorkout/"))
            assertTrue(request.getHeader("User-Agent")!!.contains("com.hanshin.healthtask"))
            assertNull(request.getHeader("Cache-Control"))
            client.cache!!.close()
        }
    }

    @Test fun `missing cache freshness gets seven days without overriding the server policy`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("no freshness"))
            server.enqueue(MockResponse().setHeader("Cache-Control", "max-age=3600").setBody("server ttl"))
            server.start()
            val client = client()
            listOf("public, max-age=604800", "max-age=3600").forEachIndexed { index, expected ->
                val url = server.url("/$index.png").newBuilder().host("tile.openstreetmap.org").build()
                client.newCall(Request.Builder().url(url).build()).execute().use {
                    assertEquals(expected, it.header("Cache-Control"))
                }
            }
            client.cache!!.close()
        }
    }

    private fun client() = OkHttpClient.Builder()
        .dns(object : Dns {
            override fun lookup(hostname: String) = listOf(InetAddress.getByName("127.0.0.1"))
        })
        .cache(Cache(temporary.newFolder(), 1024L * 1024L))
        .addInterceptor(OsmRequestIdentification())
        .addNetworkInterceptor(OsmCacheHeaders())
        .build()
}
