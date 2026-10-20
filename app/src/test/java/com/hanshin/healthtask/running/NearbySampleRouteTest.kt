package com.hanshin.healthtask.running

import org.junit.Assert.*
import org.junit.Test

class NearbySampleRouteTest {
    @Test fun `sample is a small closed loop near the supplied location`() {
        val route = nearbySampleRoute(37.5, 127.0)
        assertEquals(65, route.size)
        assertEquals(37.5, route.first().latitude, 0.0000001)
        assertEquals(127.0, route.first().longitude, 0.0000001)
        assertEquals(route.first().latitude, route.last().latitude, 0.0)
        assertEquals(route.first().longitude, route.last().longitude, 0.0)
        assertTrue(route.zipWithNext().all { (a, b) -> b.elapsedMillis > a.elapsedMillis })
        val distance = route.zipWithNext().sumOf { (a, b) ->
            RunningMetrics.distanceMeters(a.latitude, a.longitude, b.latitude, b.longitude)
        }
        assertTrue(distance in 750.0..850.0)
        assertTrue(route.all { RunningMetrics.distanceMeters(37.5, 127.0, it.latitude, it.longitude) < 300.0 })
    }

    @Test fun `sample wraps longitude and stays valid at supported latitude edges`() {
        for (latitude in listOf(-84.0, 0.0, 84.0)) for (longitude in listOf(-180.0, 179.999, 180.0)) {
            val route = nearbySampleRoute(latitude, longitude)
            assertEquals(route, routeMapPoints(route))
        }
    }

    @Test fun `sample rejects unusable centers`() {
        for ((latitude, longitude) in listOf(Double.NaN to 0.0, 0.0 to Double.POSITIVE_INFINITY, 90.0 to 0.0, 0.0 to 181.0)) {
            assertThrows(IllegalArgumentException::class.java) { nearbySampleRoute(latitude, longitude) }
        }
    }
}
