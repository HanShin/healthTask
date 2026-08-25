package com.hanshin.healthtask.shared

import org.junit.Assert.assertEquals
import org.junit.Test

class TabataProtocolTest {
    @Test fun `eight work rest rounds complete in four minutes`() {
        var state = TabataTimer.start(TabataTimer.ready("item"))
        repeat(TABATA_TOTAL_SECONDS) { state = TabataTimer.tick(state) }

        assertEquals(TabataPhase.COMPLETED, state.phase)
        assertEquals(TABATA_ROUNDS, state.round)
        assertEquals(0, state.remainingSeconds)
    }

    @Test fun `timer moves from work to rest and next round`() {
        var state = TabataTimer.start(TabataTimer.ready("item"))
        repeat(TABATA_WORK_SECONDS) { state = TabataTimer.tick(state) }
        assertEquals(TabataPhase.REST, state.phase)
        assertEquals(1, state.round)
        assertEquals(TABATA_REST_SECONDS, state.remainingSeconds)

        repeat(TABATA_REST_SECONDS) { state = TabataTimer.tick(state) }
        assertEquals(TabataPhase.WORK, state.phase)
        assertEquals(2, state.round)
        assertEquals(TABATA_WORK_SECONDS, state.remainingSeconds)
    }

    @Test fun `pause keeps time and resumes previous phase`() {
        val working = TabataTimer.tick(TabataTimer.start(TabataTimer.ready("item")))
        val paused = TabataTimer.pause(working)

        assertEquals(paused, TabataTimer.tick(paused))
        assertEquals(TabataPhase.WORK, TabataTimer.resume(paused).phase)
        assertEquals(TABATA_WORK_SECONDS - 1, TabataTimer.resume(paused).remainingSeconds)
    }
}
