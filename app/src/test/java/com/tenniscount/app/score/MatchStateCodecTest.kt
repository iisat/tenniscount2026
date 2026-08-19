package com.tenniscount.app.score

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MatchStateCodecTest {

    private fun roundTrip(state: MatchState) {
        assertEquals(state, MatchStateCodec.decode(MatchStateCodec.encode(state)))
    }

    @Test
    fun `round trip fresh match`() {
        roundTrip(MatchState(firstServer = Player.ONE))
        roundTrip(MatchState(firstServer = Player.TWO))
    }

    @Test
    fun `round trip mid set`() {
        val state = MatchState(
            firstServer = Player.TWO,
            currentSet = SetState(gamesP1 = 2, gamesP2 = 3, currentGame = GameState(1, 2)),
        )
        roundTrip(state)
    }

    @Test
    fun `round trip deuce and advantage points`() {
        roundTrip(
            MatchState(
                firstServer = Player.ONE,
                currentSet = SetState(currentGame = GameState(3, 3)),
            ),
        )
        roundTrip(
            MatchState(
                firstServer = Player.ONE,
                currentSet = SetState(currentGame = GameState(5, 4)),
            ),
        )
    }

    @Test
    fun `round trip with completed sets`() {
        val state = MatchState(
            firstServer = Player.ONE,
            completedSets = listOf(SetScore(6, 4), SetScore(3, 6), SetScore(7, 5)),
            currentSet = SetState(gamesP1 = 1, gamesP2 = 0),
        )
        roundTrip(state)
    }

    @Test
    fun `decode rejects malformed input`() {
        assertNull(MatchStateCodec.decode(""))
        assertNull(MatchStateCodec.decode("v2|ONE|||0|0|0|0"))
        assertNull(MatchStateCodec.decode("v1|THREE|||0|0|0|0"))
        assertNull(MatchStateCodec.decode("v1|ONE|6:4|x|0|0|0"))
        assertNull(MatchStateCodec.decode("v1|ONE|6:4|2|3|1"))
        assertNull(MatchStateCodec.decode("v1|ONE||0|0|-1|0"))
        // Завершённый гейм/сет в «текущем» состоянии — порча данных.
        assertNull(MatchStateCodec.decode("v1|ONE||0|0|4|0"))
        assertNull(MatchStateCodec.decode("v1|ONE||6|4|0|0"))
    }

    @Test
    fun `decode rejects invalid completed sets`() {
        // Отрицательные геймы.
        assertNull(MatchStateCodec.decode("v1|ONE|-1:0|0|0|0|0"))
        // 0:0 не может считаться завершённым сетом.
        assertNull(MatchStateCodec.decode("v1|ONE|0:0|0|0|0|0"))
        // Незавершённые или не соответствующие правилам сеты отклоняются.
        assertNull(MatchStateCodec.decode("v1|ONE|5:4|0|0|0|0"))
        assertNull(MatchStateCodec.decode("v1|ONE|6:5|0|0|0|0"))
        assertNull(MatchStateCodec.decode("v1|ONE|7:6|0|0|0|0"))
        assertNull(MatchStateCodec.decode("v1|ONE|3:3|0|0|0|0"))
        // После 5:5 разница строго 2, сет с разницей >2 невозможен.
        assertNull(MatchStateCodec.decode("v1|ONE|7:4|0|0|0|0"))
        assertNull(MatchStateCodec.decode("v1|ONE|8:5|0|0|0|0"))
        assertNull(MatchStateCodec.decode("v1|ONE|100:1|0|0|0|0"))
        // Валидные завершённые сеты проходят.
        assertEquals(
            MatchState(
                firstServer = Player.ONE,
                completedSets = listOf(SetScore(6, 4)),
            ),
            MatchStateCodec.decode("v1|ONE|6:4|0|0|0|0"),
        )
        assertEquals(
            MatchState(
                firstServer = Player.ONE,
                completedSets = listOf(SetScore(6, 0)),
            ),
            MatchStateCodec.decode("v1|ONE|6:0|0|0|0|0"),
        )
        assertEquals(
            MatchState(
                firstServer = Player.TWO,
                completedSets = listOf(SetScore(7, 5)),
            ),
            MatchStateCodec.decode("v1|TWO|7:5|0|0|0|0"),
        )
        assertEquals(
            MatchState(
                firstServer = Player.ONE,
                completedSets = listOf(SetScore(8, 6)),
            ),
            MatchStateCodec.decode("v1|ONE|8:6|0|0|0|0"),
        )
        assertEquals(
            MatchState(
                firstServer = Player.ONE,
                completedSets = listOf(SetScore(98, 100)),
            ),
            MatchStateCodec.decode("v1|ONE|98:100|0|0|0|0"),
        )
    }

    @Test
    fun `encoded format example`() {
        val state = MatchState(
            firstServer = Player.ONE,
            completedSets = listOf(SetScore(6, 4), SetScore(3, 6)),
            currentSet = SetState(gamesP1 = 2, gamesP2 = 3, currentGame = GameState(1, 2)),
        )
        assertEquals("v1|ONE|6:4,3:6|2|3|1|2", MatchStateCodec.encode(state))
    }
}
