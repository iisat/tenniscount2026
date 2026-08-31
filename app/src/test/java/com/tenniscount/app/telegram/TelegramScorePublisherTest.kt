package com.tenniscount.app.telegram

import com.tenniscount.app.score.GameState
import com.tenniscount.app.score.MatchState
import com.tenniscount.app.score.Player
import com.tenniscount.app.score.SetScore
import com.tenniscount.app.score.SetState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TelegramScorePublisherTest {
    private val configuration = TelegramScorePublisher.Configuration(
        enabled = true,
        token = "token",
        chatId = "chat",
        player1Name = "Анна",
        player2Name = "Борис",
    )

    @Test
    fun `initial score sends a message`() = runBlocking {
        val client = FakeTelegramClient()
        val publisher = TelegramScorePublisher(client)
        publisher.startSession()

        assertTrue(publisher.liveUpdate(configuration, state())())

        assertEquals(listOf("send:1"), client.calls)
    }

    @Test
    fun `following points edit the same message`() = runBlocking {
        val client = FakeTelegramClient()
        val publisher = TelegramScorePublisher(client)
        publisher.startSession()
        publisher.liveUpdate(configuration, state())()

        publisher.liveUpdate(configuration, state(pointsP1 = 1))()
        publisher.liveUpdate(configuration, state(pointsP1 = 2))()

        assertEquals(listOf("send:1", "edit:1", "edit:1"), client.calls)
    }

    @Test
    fun `new game sends a new message then deletes old one`() = runBlocking {
        val client = FakeTelegramClient()
        val publisher = TelegramScorePublisher(client)
        publisher.startSession()
        publisher.liveUpdate(configuration, state(pointsP1 = 3))()

        publisher.liveUpdate(configuration, state(gamesP1 = 1))()

        assertEquals(listOf("send:1", "send:2", "delete:1"), client.calls)
    }

    @Test
    fun `new set sends a new message`() = runBlocking {
        val client = FakeTelegramClient()
        val publisher = TelegramScorePublisher(client)
        publisher.startSession()
        publisher.liveUpdate(configuration, state(gamesP1 = 5, gamesP2 = 4))()

        val nextSet = state(completedSets = listOf(SetScore(6, 4)))
        publisher.liveUpdate(configuration, nextSet)()

        assertEquals(listOf("send:1", "send:2", "delete:1"), client.calls)
    }

    @Test
    fun `restore mid-game starts a new publishing session`() = runBlocking {
        val client = FakeTelegramClient()
        val publisher = TelegramScorePublisher(client)
        publisher.startSession()

        publisher.liveUpdate(configuration, state(gamesP1 = 2, pointsP1 = 2))()
        publisher.liveUpdate(configuration, state(gamesP1 = 2, pointsP1 = 3))()

        assertEquals(listOf("send:1", "edit:1"), client.calls)
    }

    @Test
    fun `failed send does not advance state`() = runBlocking {
        val client = FakeTelegramClient().apply { failNextSend = true }
        val publisher = TelegramScorePublisher(client)
        publisher.startSession()
        val score = state(pointsP1 = 1)

        publisher.liveUpdate(configuration, score)()
        publisher.liveUpdate(configuration, score)()

        assertEquals(listOf("send:failed", "send:1"), client.calls)
    }

    @Test
    fun `failed edit can be retried`() = runBlocking {
        val client = FakeTelegramClient()
        val publisher = TelegramScorePublisher(client)
        publisher.startSession()
        publisher.liveUpdate(configuration, state())()
        client.failNextEdit = true
        val score = state(pointsP1 = 1)

        publisher.liveUpdate(configuration, score)()
        publisher.liveUpdate(configuration, score)()

        assertEquals(listOf("send:1", "edit:failed:1", "edit:1"), client.calls)
    }

    @Test
    fun `disabled Telegram makes no API calls`() = runBlocking {
        val client = FakeTelegramClient()
        val publisher = TelegramScorePublisher(client)
        publisher.startSession()
        val disabled = configuration.copy(enabled = false)

        assertFalse(publisher.liveUpdate(disabled, state())())
        assertFalse(publisher.finish(disabled, state())())

        assertTrue(client.calls.isEmpty())
    }

    @Test
    fun `final formatter includes unfinished current set`() {
        val match = state(
            completedSets = listOf(SetScore(6, 4)),
            gamesP1 = 3,
            gamesP2 = 2,
        )

        val text = TelegramScoreFormatter.finalMessage(match, "Анна", "Борис")

        assertTrue(text.contains("Счёт по сетам: 6:4 (3:2)"))
    }

    private fun state(
        completedSets: List<SetScore> = emptyList(),
        gamesP1: Int = 0,
        gamesP2: Int = 0,
        pointsP1: Int = 0,
        pointsP2: Int = 0,
    ) = MatchState(
        firstServer = Player.ONE,
        completedSets = completedSets,
        currentSet = SetState(gamesP1, gamesP2, GameState(pointsP1, pointsP2)),
    )

    private class FakeTelegramClient : TelegramClient {
        val calls = mutableListOf<String>()
        var failNextSend = false
        var failNextEdit = false
        private var nextMessageId = 1

        override suspend fun sendMessage(token: String, chatId: String, text: String): Int? {
            if (failNextSend) {
                failNextSend = false
                calls += "send:failed"
                return null
            }
            val id = nextMessageId++
            calls += "send:$id"
            return id
        }

        override suspend fun editMessage(
            token: String,
            chatId: String,
            messageId: Int,
            text: String,
        ): Boolean {
            if (failNextEdit) {
                failNextEdit = false
                calls += "edit:failed:$messageId"
                return false
            }
            calls += "edit:$messageId"
            return true
        }

        override suspend fun deleteMessage(token: String, chatId: String, messageId: Int): Boolean {
            calls += "delete:$messageId"
            return true
        }
    }
}
