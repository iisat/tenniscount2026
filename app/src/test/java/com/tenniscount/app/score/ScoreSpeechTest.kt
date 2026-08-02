package com.tenniscount.app.score

import org.junit.Assert.assertEquals
import org.junit.Test

class ScoreSpeechTest {

    private val names: (Player) -> String = {
        if (it == Player.ONE) "Игрок 1" else "Игрок 2"
    }

    private fun state(pointsP1: Int, pointsP2: Int, serverTwo: Boolean = false) = MatchState(
        firstServer = Player.ONE,
        // 7+4 = 11 сыгранных геймов (нечётное) — подаёт игрок 2.
        completedSets = if (serverTwo) listOf(SetScore(7, 4)) else emptyList(),
        currentSet = SetState(0, 0, GameState(pointsP1, pointsP2)),
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
}
