package com.tenniscount.app.telegram

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class TelegramOperationSequencer {
    class Ticket internal constructor(
        internal val session: Long,
        internal val sequence: Long,
    )

    private val mutex = Mutex()
    private var session = 0L
    private var nextSequence = 0L
    private var lastStartedSequence = 0L

    @Synchronized
    fun nextSession(): Ticket {
        session++
        nextSequence = 1L
        lastStartedSequence = 0L
        return Ticket(session, nextSequence)
    }

    @Synchronized
    fun nextOperation(): Ticket = Ticket(session, ++nextSequence)

    @Synchronized
    fun commitIfCurrent(ticket: Ticket, block: () -> Unit): Boolean {
        if (ticket.session != session) return false
        block()
        return true
    }

    suspend fun execute(ticket: Ticket, operation: suspend () -> Unit): Boolean = mutex.withLock {
        val shouldExecute = synchronized(this) {
            if (ticket.session != session || ticket.sequence <= lastStartedSequence) {
                false
            } else {
                lastStartedSequence = ticket.sequence
                true
            }
        }
        if (shouldExecute) operation()
        shouldExecute
    }
}
