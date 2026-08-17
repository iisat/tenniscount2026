package com.tenniscount.app.score

import org.junit.Assert.assertEquals
import org.junit.Test

class MatchSummaryTest {

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

    @Test
    fun `scoreLine показывает очки, геймы и сеты`() {
        val s = state(
            completedSets = listOf(SetScore(6, 4)),
            gamesP1 = 3, gamesP2 = 2, pointsP1 = 3, pointsP2 = 1,
        )
        assertEquals("40:15 · геймы 3:2 · сеты 1:0", MatchSummary.scoreLine(s))
    }

    @Test
    fun `scoreLine в начале матча`() {
        assertEquals("0:0 · геймы 0:0 · сеты 0:0", MatchSummary.scoreLine(state()))
    }

    @Test
    fun `scoreLine при преимуществе`() {
        val s = state(pointsP1 = 4, pointsP2 = 3)
        assertEquals("AD:40 · геймы 0:0 · сеты 0:0", MatchSummary.scoreLine(s))
    }

    @Test
    fun `setsSummary только завершённые сеты`() {
        val s = state(completedSets = listOf(SetScore(6, 4), SetScore(3, 6)))
        assertEquals("6:4 3:6", MatchSummary.setsSummary(s))
    }

    @Test
    fun `setsSummary добавляет незавершённый сет в скобках`() {
        val s = state(completedSets = listOf(SetScore(6, 4)), gamesP1 = 3, gamesP2 = 2)
        assertEquals("6:4 (3:2)", MatchSummary.setsSummary(s))
    }

    @Test
    fun `setsSummary матч прерван в первом сете`() {
        val s = state(gamesP1 = 1, gamesP2 = 2, pointsP2 = 2)
        assertEquals("(1:2)", MatchSummary.setsSummary(s))
    }

    @Test
    fun `setsWon считает выигранные сеты`() {
        val s = state(completedSets = listOf(SetScore(6, 4), SetScore(3, 6), SetScore(7, 5)))
        assertEquals(2, MatchSummary.setsWon(s, Player.ONE))
        assertEquals(1, MatchSummary.setsWon(s, Player.TWO))
    }

    @Test
    fun `setsWon не засчитывает только что начатый пустой сет 0-0`() {
        // 6:4 → выигран первый сет, новый сет ещё не начался (0:0).
        val s = state(completedSets = listOf(SetScore(6, 4)), gamesP1 = 0, gamesP2 = 0)
        assertEquals(1, MatchSummary.setsWon(s, Player.ONE))
        assertEquals(0, MatchSummary.setsWon(s, Player.TWO))
    }

    @Test
    fun `setsWon не засчитывает незавершённый сет при ручном завершении матча`() {
        // 3:2 в текущем сете — матч завершён вручную, сет не доигран.
        val s = state(gamesP1 = 3, gamesP2 = 2)
        assertEquals(0, MatchSummary.setsWon(s, Player.ONE))
        assertEquals(0, MatchSummary.setsWon(s, Player.TWO))
    }
}
