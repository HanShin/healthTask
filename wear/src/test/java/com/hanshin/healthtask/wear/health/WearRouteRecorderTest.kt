package com.hanshin.healthtask.wear.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WearRouteRecorderTest {
    @Test fun `records usable movement and rejects jitter inaccurate and teleport points`() {
        val recorder = WearRouteRecorder()

        assertNull(recorder.record(37.0, 127.0, 0L, 80f))
        assertEquals(1, recorder.record(37.0, 127.0, 0L, 5f)!!.size)
        assertNull(recorder.record(37.000001, 127.000001, 3_000L, 5f))
        assertEquals(2, recorder.record(37.0001, 127.0, 3_000L, 5f)!!.size)
        assertNull(recorder.record(37.01, 127.0, 5_000L, 5f))
    }

    @Test fun `break accepts first point of resumed segment and cap compacts whole route`() {
        val recorder = WearRouteRecorder(maxPoints = 4)
        recorder.record(37.0000, 127.0, 0L, 3f)
        recorder.record(37.0001, 127.0, 3_000L, 3f)
        recorder.record(37.0002, 127.0, 6_000L, 3f)
        recorder.record(37.0003, 127.0, 9_000L, 3f)
        val compacted = recorder.record(37.0004, 127.0, 12_000L, 3f)!!

        assertEquals(3, compacted.size)
        assertEquals(37.0000, compacted.first().latitude, 0.0)
        assertEquals(37.0004, compacted.last().latitude, 0.0)

        recorder.breakSegment()
        val resumed = recorder.record(37.1, 127.1, 12_500L, 3f)!!
        assertEquals(4, resumed.size)
    }
}
