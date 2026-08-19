package com.tenniscount.app.score

import org.junit.Assert.assertEquals
import org.junit.Test

class ScoreSpeechTest {

    private val names: (Player) -> String = {
        if (it == Player.ONE) "Игрок 1" else "Игрок 2"
    }

    private fun state(pointsP1: Int, pointsP2: Int, serverTwo: Boolean = false) = MatchState(
        firstServer = Player.ONE,
        // 6+4+1 = 11 сыгранных геймов (нечётное) — подаёт игрок 2.
        completedSets = if (serverTwo) listOf(SetScore(6, 4)) else emptyList(),
        currentSet = SetState(
            gamesP1 = if (serverTwo) 1 else 0,
            gamesP2 = 0,
            currentGame = GameState(pointsP1, pointsP2),
        ),
    )

    @Test
    fun `счёт словами, сначала очки подающего`() {
        // Подаёт игрок 1: 30-15
        assertEquals("тридцать пятнадцать", ScoreSpeech.gameScore(state(2, 1), names))
    }

    @Test
    fun `порядок чисел следует за подающим`() {
        // Подаёт игрок 2: у него 30, у игрока 1 — 0 → «тридцать ноль»
        assertEquals("тридцать ноль", ScoreSpeech.gameScore(state(0, 2, serverTwo = true), names))
    }

    @Test
    fun `начало гейма — ноль ноль`() {
        assertEquals("ноль ноль", ScoreSpeech.gameScore(state(0, 0), names))
    }

    @Test
    fun `ровно при deuce`() {
        assertEquals("ровно", ScoreSpeech.gameScore(state(3, 3), names))
        assertEquals("ровно", ScoreSpeech.gameScore(state(5, 5), names))
    }

    @Test
    fun `больше с именем игрока при advantage`() {
        assertEquals("больше, Игрок 1", ScoreSpeech.gameScore(state(4, 3), names))
        assertEquals("больше, Игрок 2", ScoreSpeech.gameScore(state(3, 4), names))
    }

    private fun gamesState(gamesP1: Int, gamesP2: Int) = MatchState(
        firstServer = Player.ONE,
        currentSet = SetState(gamesP1, gamesP2),
    )

    @Test
    fun `конец гейма — геймы победителя первыми`() {
        assertEquals("Гейм, Игрок 1. 3 2", ScoreSpeech.gameEnd(gamesState(3, 2), Player.ONE, names))
        assertEquals("Гейм, Игрок 2. 5 4", ScoreSpeech.gameEnd(gamesState(4, 5), Player.TWO, names))
    }

    @Test
    fun `итог сета — геймы победителя первыми`() {
        assertEquals("Сет, Игрок 1. 6 4", ScoreSpeech.setEnd(SetScore(6, 4), names))
        assertEquals("Сет, Игрок 2. 7 5", ScoreSpeech.setEnd(SetScore(5, 7), names))
    }

    @Test
    fun `сет-поинт при 5-4 и 6-5`() {
        assertEquals(Player.ONE, ScoreSpeech.setPointPlayer(gamesState(5, 4)))
        assertEquals(Player.ONE, ScoreSpeech.setPointPlayer(gamesState(6, 5)))
        assertEquals(Player.TWO, ScoreSpeech.setPointPlayer(gamesState(3, 5)))
    }

    @Test
    fun `нет сет-поинта при равном или недостаточном счёте`() {
        assertEquals(null, ScoreSpeech.setPointPlayer(gamesState(0, 0)))
        assertEquals(null, ScoreSpeech.setPointPlayer(gamesState(4, 4)))
        // При 5-5 выигрыш гейма даёт 6-5 — сет не завершается.
        assertEquals(null, ScoreSpeech.setPointPlayer(gamesState(5, 5)))
    }

    @Test
    fun `фраза сет-поинта с именем игрока`() {
        assertEquals("Сет-поинт, Игрок 2", ScoreSpeech.setPoint(Player.TWO, names))
    }
}
