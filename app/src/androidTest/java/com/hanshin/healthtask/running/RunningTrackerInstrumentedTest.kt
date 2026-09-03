package com.hanshin.healthtask.running

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RunningTrackerInstrumentedTest {
    private lateinit var context: Context

    @Before fun clearPersistedRun() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("running_tracker", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After fun cleanupPersistedRun() {
        context.getSharedPreferences("running_tracker", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun sessionIdentitySurvivesRestoreAndCompletion() {
        RunningTracker(context).apply {
            start(sessionId = "run-persisted", plannedSlotId = "slot-1", now = 1_000L)
            pause(now = 6_000L)
        }

        val restored = RunningTracker(context)
        assertEquals("run-persisted", restored.state.value.sessionId)
        assertEquals("slot-1", restored.state.value.plannedSlotId)

        val completed = restored.finish(now = 9_000L)
        assertNotNull(completed)
        assertEquals("run-persisted", completed!!.sessionId)
        assertEquals(5_000L, completed.elapsedMillis)
    }
}
