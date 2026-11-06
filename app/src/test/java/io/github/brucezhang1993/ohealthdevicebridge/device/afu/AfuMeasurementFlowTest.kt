package io.github.brucezhang1993.ohealthdevicebridge.device.afu

import io.github.brucezhang1993.ohealthdevicebridge.device.*
import org.junit.Assert.*
import org.junit.Test

class AfuMeasurementFlowTest {
    private class Session(historyOnly: Boolean = false) {
        val commands = mutableListOf<String>()
        val final = mutableListOf<MeasurementRecord>()
        val history = mutableListOf<MeasurementRecord>()
        val live = mutableListOf<Double>()
        var closed = 0
        val flow = AfuMeasurementFlow(UserProfile(29, false, 60.0), historyOnly,
            { _, name -> commands.add(name) }, live::add, final::add, history::add, { closed++ })
    }
    private fun live(locked: Boolean = true) = AfuProtocol.LiveWeight(61050, locked, 2, byteArrayOf())
    private fun stored(time: Long = 1000L, sequence: Int = 0, locked: Boolean = false) =
        AfuProtocol.StoredRecord(0, sequence, time, 61050, 551, locked, byteArrayOf())

    @Test fun earlyLockedLiveSurvivesHandshakeAndCommitsAfterSilence() {
        val s = Session(); s.flow.receive(live(), 0, 1000)
        assertTrue(s.commands.isEmpty())
        s.flow.ready(10, 1000); assertEquals(AfuSessionState.WAIT_IDLE, s.flow.state)
        s.flow.ready(20, 1000); assertEquals(AfuSessionState.WAIT_IDLE, s.flow.state)
        s.flow.poll(2009, 1002); assertFalse(s.commands.contains("profile-4b"))
        s.flow.poll(2010, 1002); assertEquals(AfuSessionState.COMMIT, s.flow.state)
        s.flow.receive(stored(970), 2100, 1002)
        assertEquals(551, s.final.single().resistanceOhm)
        assertTrue(s.history.isEmpty())
    }
    @Test fun repeatedHistoryAndFreshReplayAreDeduplicated() {
        val s = Session(); s.flow.ready(0, 1000)
        s.flow.receive(stored(900), 10, 1000); s.flow.receive(stored(900, 5), 20, 1000)
        assertEquals(1, s.history.size)
        s.flow.receive(live(), 30, 1000); s.flow.poll(2030, 1002)
        s.flow.receive(stored(), 2100, 1002); s.flow.receive(stored(sequence = 6), 2200, 1002)
        assertEquals(1, s.final.size); assertEquals(1, s.history.size)
        assertEquals(4, s.commands.count { it == "ack-stored" })
        assertEquals(1, s.commands.count { it == "history-dump-retry" })
    }
    @Test fun staleRecordDoesNotCompleteCurrentMeasurement() {
        val s = Session(); s.flow.ready(0, 1000); s.flow.receive(live(), 10, 1000); s.flow.poll(2010, 1002)
        s.flow.receive(stored(939), 2200, 1002)
        assertTrue(s.final.isEmpty()); assertEquals(1, s.history.size)
        s.flow.poll(17010, 1017)
        assertNull(s.final.single().resistanceOhm); assertEquals(1, s.closed)
        assertEquals(AfuSessionState.FINISHED, s.flow.state)
    }
    @Test fun cancelRejectsAllLateResultsAndPolling() {
        val s = Session(); s.flow.ready(0, 1000); s.flow.receive(live(), 1, 1000); s.flow.cancel()
        s.flow.receive(stored(), 3000, 1003); s.flow.poll(20000, 1020)
        assertTrue(s.final.isEmpty()); assertTrue(s.history.isEmpty())
    }
    @Test fun historyBeforeHandshakeIsImportedAfterReady() {
        val s = Session(true); s.flow.receive(stored(900), 0, 1000)
        assertTrue(s.history.isEmpty()); assertTrue(s.commands.isEmpty())
        s.flow.ready(10, 1000); assertEquals(1, s.history.size)
        s.flow.poll(2010, 1002); assertEquals(1, s.closed)
    }
    @Test fun cleanupWaitsForHistoryBeyondFiveSecondsButHasFifteenSecondBound() {
        val s = Session(); s.flow.ready(0, 1000); s.flow.receive(live(), 0, 1000); s.flow.poll(2000, 1002)
        s.flow.receive(stored(), 2100, 1002)
        for (now in 3000L..16000L step 1000) {
            s.flow.receive(stored(900 - now / 1000), now, 1002); s.flow.poll(now, 1002)
        }
        assertEquals(0, s.closed)
        s.flow.poll(17100, 1017); assertEquals(1, s.closed)
    }
    @Test fun downstreamFailureCanBeCancelledWithoutAnotherFinalResult() {
        var calls = 0
        val flow = AfuMeasurementFlow(UserProfile(29, true, 60.0), false, { _, _ -> }, {},
            { calls++; throw IllegalStateException("host failed") }, {}, {})
        flow.ready(0, 1000); flow.receive(live(), 0, 1000); flow.poll(2000, 1002)
        try { flow.receive(stored(), 2100, 1002); fail("Expected failure") } catch (_: IllegalStateException) { flow.cancel() }
        flow.receive(stored(), 2200, 1002); assertEquals(1, calls)
    }
}
