package com.tenniscount.app.speech

import com.tenniscount.app.score.Announcement
import com.tenniscount.app.score.ApplyResult
import com.tenniscount.app.score.MatchEngine
import com.tenniscount.app.score.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Regression tests for split voice recognition.
 *
 * When Vosk delivers a two-word score phrase as two separate final results,
 * a single number must not be interpreted as a complete score announcement.
 */
class VoiceRecognitionRegressionTest {

    private fun MatchEngine.applyCommand(command: VoiceCommand?) {
        when (command) {
            is VoiceCommand.Score -> applyAnnouncement(command.announcement)
            is VoiceCommand.GameWon -> {
                state.currentSet.currentGame.announcedWinner?.let { winGame(it) }
            }
            else -> Unit
        }
    }

    private fun MatchEngine.assertNoGameWon() {
        assertEquals(0, state.currentSet.gamesP1)
        assertEquals(0, state.currentSet.gamesP2)
    }

    @Test
    fun `split recognition of thirty and forty does not corrupt game winner when serving Player 2`() {
        val engine = MatchEngine(firstServer = Player.TWO)
        engine.strictValidation = false
        engine.editGameScore(2, 2) // 30:30

        engine.applyCommand(ScoreParser.parse("тридцать"))
        engine.applyCommand(ScoreParser.parse("сорок"))
        val gameCommand = ScoreParser.parse("гейм")
        assertEquals(VoiceCommand.GameWon, gameCommand)
        engine.applyCommand(gameCommand)

        engine.assertNoGameWon()
        assertNull(engine.state.currentSet.currentGame.announcedWinner)
    }

    @Test
    fun `split recognition of thirty and forty does not corrupt game winner when serving Player 1`() {
        val engine = MatchEngine(firstServer = Player.ONE)
        engine.strictValidation = false
        engine.editGameScore(2, 2) // 30:30

        engine.applyCommand(ScoreParser.parse("тридцать"))
        engine.applyCommand(ScoreParser.parse("сорок"))
        val gameCommand = ScoreParser.parse("гейм")
        assertEquals(VoiceCommand.GameWon, gameCommand)
        engine.applyCommand(gameCommand)

        engine.assertNoGameWon()
        assertNull(engine.state.currentSet.currentGame.announcedWinner)
    }

    @Test
    fun `normal game point flow thirty forty wins for receiver when serving Player 2`() {
        val engine = MatchEngine(firstServer = Player.TWO)
        engine.strictValidation = false

        val score = ScoreParser.parse("тридцать сорок") as VoiceCommand.Score
        assertEquals(Announcement.Points(serverPoints = 2, receiverPoints = 3), score.announcement)
        assertEquals(ApplyResult.Applied, engine.applyAnnouncement(score.announcement))

        val gameCommand = ScoreParser.parse("гейм") as VoiceCommand.GameWon
        engine.applyCommand(gameCommand)

        assertEquals(1, engine.state.currentSet.gamesP1)
        assertEquals(0, engine.state.currentSet.gamesP2)
    }

    @Test
    fun `normal game point flow thirty forty wins for receiver when serving Player 1`() {
        val engine = MatchEngine(firstServer = Player.ONE)
        engine.strictValidation = false

        val score = ScoreParser.parse("тридцать сорок") as VoiceCommand.Score
        assertEquals(Announcement.Points(serverPoints = 2, receiverPoints = 3), score.announcement)
        assertEquals(ApplyResult.Applied, engine.applyAnnouncement(score.announcement))

        val gameCommand = ScoreParser.parse("гейм") as VoiceCommand.GameWon
        engine.applyCommand(gameCommand)

        assertEquals(0, engine.state.currentSet.gamesP1)
        assertEquals(1, engine.state.currentSet.gamesP2)
    }

    @Test
    fun `normal game point flow fifteen forty wins for receiver when serving Player 2`() {
        val engine = MatchEngine(firstServer = Player.TWO)
        engine.strictValidation = false

        val score = ScoreParser.parse("пятнадцать сорок") as VoiceCommand.Score
        assertEquals(Announcement.Points(serverPoints = 1, receiverPoints = 3), score.announcement)
        assertEquals(ApplyResult.Applied, engine.applyAnnouncement(score.announcement))

        val gameCommand = ScoreParser.parse("гейм") as VoiceCommand.GameWon
        engine.applyCommand(gameCommand)

        assertEquals(1, engine.state.currentSet.gamesP1)
        assertEquals(0, engine.state.currentSet.gamesP2)
    }
}
