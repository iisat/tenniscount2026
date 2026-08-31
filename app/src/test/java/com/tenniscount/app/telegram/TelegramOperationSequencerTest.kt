package com.tenniscount.app.telegram

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
        val final = sequencer.nextFinalSession()
        val events = mutableListOf<String>()

        val liveExecuted = sequencer.execute(live) { events += "live" }
        val finalExecuted = sequencer.execute(final) { events += "final" }

        assertFalse(liveExecuted)
        assertTrue(finalExecuted)
        assertEquals(listOf("final"), events)
    }

    @Test
    fun `new match waits for pending final from finished match`() = runBlocking {
        val sequencer = TelegramOperationSequencer()
        sequencer.nextSession()
        val final = sequencer.nextFinalSession()
        sequencer.nextSession()
        val newMatchUpdate = sequencer.nextOperation()
        val events = mutableListOf<String>()
        val newUpdateStarted = CompletableDeferred<Unit>()
        val newUpdate = launch(start = CoroutineStart.UNDISPATCHED) {
            sequencer.execute(newMatchUpdate) {
                events += "new-live"
                newUpdateStarted.complete(Unit)
            }
        }

        assertFalse(newUpdateStarted.isCompleted)
        assertTrue(sequencer.execute(final) { events += "final" })
        newUpdate.join()

        assertTrue(newUpdateStarted.isCompleted)
        assertEquals(listOf("final", "new-live"), events)
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
    fun `commit is rejected when session changes after operation starts`() = runBlocking {
        val sequencer = TelegramOperationSequencer()
        sequencer.nextSession()
        val oldUpdate = sequencer.nextOperation()
        val reachedCommit = CompletableDeferred<Unit>()
        val releaseCommit = CompletableDeferred<Unit>()
        var messageId: Int? = null
        var committed = true
        val operation = launch(Dispatchers.Default) {
            sequencer.execute(oldUpdate) {
                reachedCommit.complete(Unit)
                releaseCommit.await()
                committed = sequencer.commitIfCurrent(oldUpdate) { messageId = 42 }
            }
        }

        reachedCommit.await()
        sequencer.nextSession()
        releaseCommit.complete(Unit)
        operation.join()

        assertFalse(committed)
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
