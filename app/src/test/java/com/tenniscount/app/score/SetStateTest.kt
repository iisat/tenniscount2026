package com.tenniscount.app.score

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SetStateTest {

    private fun playGame(set: SetState, winner: Player): SetState = set.withGameWon(winner)

    private fun playGames(p1: Int, p2: Int): SetState {
        // Геймы чередуются, чтобы сет не завершился раньше последнего гейма.
        var set = SetState()
        repeat(maxOf(p1, p2)) { i ->
            if (i < p1) set = playGame(set, Player.ONE)
            if (i < p2) set = playGame(set, Player.TWO)
        }
        return set
    }

    @Test
    fun `set is won at 6 games with margin of 2`() {
        val set = playGames(p1 = 6, p2 = 4)
        assertTrue(set.isFinished)
        assertEquals(Player.ONE, set.winner)
        assertEquals(SetScore(6, 4), set.score)
    }

    @Test
    fun `set is won 6-0`() {
        assertTrue(playGames(p1 = 6, p2 = 0).isFinished)
    }

    @Test
    fun `set is not finished at 6-5`() {
        val set = playGames(p1 = 6, p2 = 5)
        assertFalse(set.isFinished)
        assertNull(set.winner)
    }

    @Test
    fun `no tiebreak - set continues to 7-5, 8-6`() {
        assertTrue(playGames(p1 = 7, p2 = 5).isFinished)
        assertTrue(playGames(p1 = 8, p2 = 6).isFinished)
        assertFalse(playGames(p1 = 7, p2 = 6).isFinished)
        assertFalse(playGames(p1 = 10, p2 = 9).isFinished)
        assertTrue(playGames(p1 = 10, p2 = 8).isFinished)
    }

    @Test
    fun `points inside game advance to game win`() {
        var set = SetState()
        repeat(4) { set = set.withPoint(Player.TWO) }
        assertEquals(0, set.gamesP1)
        assertEquals(1, set.gamesP2)
        assertEquals(GameState(), set.currentGame) // новый гейм начат заново
    }

    @Test(expected = IllegalArgumentException::class)
    fun `cannot add point to finished set`() {
        playGames(p1 = 6, p2 = 0).withPoint(Player.ONE)
    }

    @Test
    fun `SetScore winner is null when games are equal`() {
        assertNull(SetScore(0, 0).winner)
        assertNull(SetScore(3, 3).winner)
    }

    @Test
    fun `SetScore winner reflects the player with more games`() {
        assertEquals(Player.ONE, SetScore(6, 4).winner)
        assertEquals(Player.TWO, SetScore(4, 6).winner)
    }

    @Test
    fun `SetScore finished score follows tennis rules`() {
        assertTrue(SetScore(6, 4).isFinishedScore)
        assertTrue(SetScore(7, 5).isFinishedScore)
        assertTrue(SetScore(8, 6).isFinishedScore)
        assertFalse(SetScore(0, 0).isFinishedScore)
        assertFalse(SetScore(3, 3).isFinishedScore)
        assertFalse(SetScore(5, 4).isFinishedScore)
        assertFalse(SetScore(6, 5).isFinishedScore)
        assertFalse(SetScore(7, 6).isFinishedScore)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `SetScore rejects negative games`() {
        SetScore(-1, 0)
    }
}
