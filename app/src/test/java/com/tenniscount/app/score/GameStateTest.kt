package com.tenniscount.app.score

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameStateTest {

    @Test
    fun `progression 0 - 15 - 30 - 40 - game`() {
        var game = GameState()
        assertEquals("0", game.displayPoints(Player.ONE))

        game = game.withPoint(Player.ONE)
        assertEquals("15", game.displayPoints(Player.ONE))
        assertFalse(game.isFinished)

        game = game.withPoint(Player.ONE)
        assertEquals("30", game.displayPoints(Player.ONE))

        game = game.withPoint(Player.ONE)
        assertEquals("40", game.displayPoints(Player.ONE))
        assertNull(game.winner)

        game = game.withPoint(Player.ONE)
        assertTrue(game.isFinished)
        assertEquals(Player.ONE, game.winner)
    }

    @Test
    fun `game requires margin of two points`() {
        // 40-30 -> не гейм, а «больше»/advantage
        val game = GameState(pointsP1 = 4, pointsP2 = 3)
        assertFalse(game.isFinished)
        assertEquals(Player.ONE, game.advantagePlayer)
    }

    @Test
    fun `announcedWinner при deuce и равном счёте — null (ошибочная команда)`() {
        assertNull(GameState(3, 3).announcedWinner) // 40-40
        assertNull(GameState(4, 4).announcedWinner) // deuce после advantage
        assertNull(GameState(2, 2).announcedWinner) // 30-30
        assertNull(GameState(0, 0).announcedWinner) // 0-0
        assertNull(GameState(1, 0).announcedWinner) // 15-0: гейм не мог закончиться
    }

    @Test
    fun `announcedWinner при преимуществе и 40 против меньшего`() {
        assertEquals(Player.ONE, GameState(4, 3).announcedWinner) // advantage P1
        assertEquals(Player.TWO, GameState(3, 4).announcedWinner) // advantage P2
        assertEquals(Player.ONE, GameState(3, 2).announcedWinner) // 40-30
        assertEquals(Player.ONE, GameState(3, 1).announcedWinner) // 40-15
        assertEquals(Player.TWO, GameState(0, 3).announcedWinner) // 0-40
    }

    @Test
    fun `deuce at 40-40, advantage, back to deuce, win`() {
        var game = GameState(pointsP1 = 3, pointsP2 = 3)
        assertTrue(game.isDeuce)
        assertNull(game.advantagePlayer)
        assertNull(game.winner)

        game = game.withPoint(Player.TWO) // advantage P2
        assertFalse(game.isDeuce)
        assertEquals(Player.TWO, game.advantagePlayer)
        assertEquals("AD", game.displayPoints(Player.TWO))
        assertEquals("40", game.displayPoints(Player.ONE))

        game = game.withPoint(Player.ONE) // снова ровно
        assertTrue(game.isDeuce)
        assertNull(game.advantagePlayer)

        game = game.withPoint(Player.ONE).withPoint(Player.ONE) // AD + гейм
        assertTrue(game.isFinished)
        assertEquals(Player.ONE, game.winner)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `cannot add point to finished game`() {
        GameState(pointsP1 = 4, pointsP2 = 0).withPoint(Player.TWO)
    }

    @Test
    fun `pointsToCount maps tennis values only`() {
        assertEquals(0, GameState.pointsToCount(0))
        assertEquals(1, GameState.pointsToCount(15))
        assertEquals(2, GameState.pointsToCount(30))
        assertEquals(3, GameState.pointsToCount(40))
        assertNull(GameState.pointsToCount(10))
        assertNull(GameState.pointsToCount(45))
    }
}
