package com.tenniscount.app.telegram

import com.tenniscount.app.score.MatchState

class TelegramScorePublisher(
    private val client: TelegramClient,
) {
    data class Configuration(
        val enabled: Boolean,
        val token: String,
        val chatId: String,
        val player1Name: String,
        val player2Name: String,
    ) {
        val isConfigured: Boolean
            get() = enabled && token.isNotBlank() && chatId.isNotBlank()
    }

    private val sequencer = TelegramOperationSequencer()
    private var messageId: Int? = null
    private var lastPublishedState: MatchState? = null

    fun startSession() {
        sequencer.nextSession()
        messageId = null
        lastPublishedState = null
    }

    fun reset() = startSession()

    fun liveUpdate(configuration: Configuration, state: MatchState): suspend () -> Boolean {
        val ticket = sequencer.nextOperation()
        return operation@{
            if (!configuration.isConfigured) return@operation false
            sequencer.execute(ticket) {
                val previous = lastPublishedState
                if (state == previous) return@execute
                val boundaryChanged = previous == null ||
                    state.completedSets.size != previous.completedSets.size ||
                    state.totalGames != previous.totalGames
                val text = TelegramScoreFormatter.liveMessage(
                    state,
                    configuration.player1Name,
                    configuration.player2Name,
                )
                if (boundaryChanged) {
                    val oldMessageId = messageId
                    val newMessageId = client.sendMessage(
                        configuration.token,
                        configuration.chatId,
                        text,
                    )
                    if (newMessageId != null) {
                        val committed = sequencer.commitIfCurrent(ticket) {
                            messageId = newMessageId
                            lastPublishedState = state
                        }
                        if (committed) {
                            if (oldMessageId != null) {
                                client.deleteMessage(
                                    configuration.token,
                                    configuration.chatId,
                                    oldMessageId,
                                )
                            }
                        } else {
                            client.deleteMessage(
                                configuration.token,
                                configuration.chatId,
                                newMessageId,
                            )
                        }
                    }
                } else {
                    val currentMessageId = messageId ?: return@execute
                    val edited = client.editMessage(
                        configuration.token,
                        configuration.chatId,
                        currentMessageId,
                        text,
                    )
                    if (edited) {
                        sequencer.commitIfCurrent(ticket) { lastPublishedState = state }
                    }
                }
            }
        }
    }

    fun finish(configuration: Configuration, state: MatchState): suspend () -> Boolean {
        if (!configuration.isConfigured) {
            reset()
            return { false }
        }
        val oldMessageId = messageId
        val ticket = sequencer.nextFinalSession()
        return {
            sequencer.execute(ticket) {
                val text = TelegramScoreFormatter.finalMessage(
                    state,
                    configuration.player1Name,
                    configuration.player2Name,
                )
                val sent = client.sendMessage(configuration.token, configuration.chatId, text)
                if (sent != null) {
                    sequencer.commitIfCurrent(ticket) {
                        messageId = null
                        lastPublishedState = null
                    }
                    if (oldMessageId != null) {
                        client.deleteMessage(configuration.token, configuration.chatId, oldMessageId)
                    }
                }
            }
        }
    }
}
