package com.tenniscount.app.telegram

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class TelegramOperationSequencer {
    class Ticket internal constructor(
        internal val session: Long,
        internal val sequence: Long,
        internal val required: Boolean,
        internal val prerequisite: Deferred<Unit>?,
        internal val completion: CompletableDeferred<Unit>?,
    )

    private val mutex = Mutex()
    private var session = 0L
    private var nextSequence = 0L
    private var lastStartedSequence = 0L
    private var pendingFinal: CompletableDeferred<Unit>? = null

    @Synchronized
    fun nextSession(): Ticket {
        session++
        nextSequence = 1L
        lastStartedSequence = 0L
        return ticket(sequence = nextSequence)
    }

    @Synchronized
    fun nextFinalSession(): Ticket {
        session++
        nextSequence = 1L
        lastStartedSequence = 0L
        val prerequisite = pendingFinal
        val completion = CompletableDeferred<Unit>()
        pendingFinal = completion
        return Ticket(session, nextSequence, required = true, prerequisite, completion)
    }

    @Synchronized
    fun nextOperation(): Ticket = ticket(sequence = ++nextSequence)

    @Synchronized
    fun commitIfCurrent(ticket: Ticket, block: () -> Unit): Boolean {
        if (ticket.session != session) return false
        block()
        return true
    }

    suspend fun execute(ticket: Ticket, operation: suspend () -> Unit): Boolean = try {
        ticket.prerequisite?.await()
        mutex.withLock {
            val shouldExecute = synchronized(this) {
                if (ticket.required) {
                    true
                } else if (ticket.session != session || ticket.sequence <= lastStartedSequence) {
                    false
                } else {
                    lastStartedSequence = ticket.sequence
                    true
                }
            }
            if (shouldExecute) operation()
            shouldExecute
        }
    } finally {
        ticket.completion?.let { completion ->
            completion.complete(Unit)
            synchronized(this) {
                if (pendingFinal === completion) pendingFinal = null
            }
        }
    }

    private fun ticket(sequence: Long): Ticket =
        Ticket(session, sequence, required = false, prerequisite = pendingFinal, completion = null)
}
