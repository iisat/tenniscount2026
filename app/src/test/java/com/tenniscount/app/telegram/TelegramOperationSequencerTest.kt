package com.tenniscount.app.telegram

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TelegramOperationSequencerTest {

    @Test
    fun `finish invalidates queued live update and remains last event`() = runBlocking {
        val sequencer = TelegramOperationSequencer()
        sequencer.nextSession()
        val live = sequencer.nextOperation()
        val final = sequencer.nextSession()
        val events = mutableListOf<String>()

        val liveExecuted = sequencer.execute(live) { events += "live" }
        val finalExecuted = sequencer.execute(final) { events += "final" }

        assertFalse(liveExecuted)
        assertTrue(finalExecuted)
        assertEquals(listOf("final"), events)
    }

    @Test
    fun `new match invalidates operations and message state from old match`() = runBlocking {
        val sequencer = TelegramOperationSequencer()
        sequencer.nextSession()
        val oldMatchUpdate = sequencer.nextOperation()
        sequencer.nextSession()
        var messageId: Int? = null

        val executed = sequencer.execute(oldMatchUpdate) { messageId = 42 }

        assertFalse(executed)
        assertEquals(null, messageId)
    }

    @Test
    fun `rapid updates cannot regress when newer update starts first`() = runBlocking {
        val sequencer = TelegramOperationSequencer()
        sequencer.nextSession()
        val first = sequencer.nextOperation()
        val second = sequencer.nextOperation()
        val events = mutableListOf<String>()

        assertTrue(sequencer.execute(second) { events += "second" })
        assertFalse(sequencer.execute(first) { events += "first" })
        assertEquals(listOf("second"), events)
    }
}
