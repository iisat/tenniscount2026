package com.tenniscount.app.score

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MatchEngineTest {

    private fun MatchEngine.playGame(winner: Player) = winGame(winner)

    private fun MatchEngine.playSet(p1Games: Int, p2Games: Int) {
        repeat(maxOf(p1Games, p2Games)) { i ->
            if (i < p1Games) playGame(Player.ONE)
            if (i < p2Games) playGame(Player.TWO)
        }
    }

    @Test
    fun `server alternates every game starting from first server`() {
        val engine = MatchEngine(firstServer = Player.ONE)
        assertEquals(Player.ONE, engine.state.server)

        engine.playGame(Player.ONE)
        assertEquals(Player.TWO, engine.state.server)

        engine.playGame(Player.TWO)
        assertEquals(Player.ONE, engine.state.server)

        val engine2 = MatchEngine(firstServer = Player.TWO)
        assertEquals(Player.TWO, engine2.state.server)
        engine2.playGame(Player.ONE)
        assertEquals(Player.ONE, engine2.state.server)
    }

    @Test
    fun `announced points are server-relative and mapped to players`() {
        val engine = MatchEngine(firstServer = Player.ONE)

        // Подаёт игрок 1: «15-0» -> очко игроку 1.
        assertEquals(ApplyResult.Applied, engine.applyAnnouncement(Announcement.Points(1, 0)))
        assertEquals(1, engine.state.currentSet.currentGame.pointsP1)
        assertEquals(0, engine.state.currentSet.currentGame.pointsP2)

        // После гейма подаёт игрок 2: «15-0» -> очко игроку 2.
        engine.playGame(Player.ONE)
        assertEquals(Player.TWO, engine.state.server)
        assertEquals(ApplyResult.Applied, engine.applyAnnouncement(Announcement.Points(1, 0)))
        assertEquals(0, engine.state.currentSet.currentGame.pointsP1)
        assertEquals(1, engine.state.currentSet.currentGame.pointsP2)
    }

    @Test
    fun `full game by announcements 0-0 to game`() {
        val engine = MatchEngine(firstServer = Player.ONE)
        assertEquals(ApplyResult.Applied, engine.applyAnnouncement(Announcement.Points(1, 0))) // 15-0
        assertEquals(ApplyResult.Applied, engine.applyAnnouncement(Announcement.Points(1, 1))) // 15-15
        assertEquals(ApplyResult.Applied, engine.applyAnnouncement(Announcement.Points(2, 1))) // 30-15
        assertEquals(ApplyResult.Applied, engine.applyAnnouncement(Announcement.Points(3, 1))) // 40-15
        engine.winGame(Player.ONE) // «гейм»
        assertEquals(1, engine.state.currentSet.gamesP1)
        assertEquals(GameState(), engine.state.currentSet.currentGame)
    }

    @Test
    fun `announcement lower than current is rejected without changing state`() {
        val engine = MatchEngine(firstServer = Player.ONE)
        engine.editGameScore(2, 1) // 30-15
        val before = engine.state

        val result = engine.applyAnnouncement(Announcement.Points(1, 0)) // 15-0 — меньше текущего

        assertEquals(ApplyResult.Rejected(RejectionReason.BACKWARD), result)
        assertEquals(before, engine.state)
    }

    @Test
    fun `duplicate announcement is rejected`() {
        val engine = MatchEngine(firstServer = Player.ONE)
        engine.editGameScore(2, 2) // 30-30
        val result = engine.applyAnnouncement(Announcement.Points(2, 2))
        assertEquals(ApplyResult.Rejected(RejectionReason.DUPLICATE), result)
    }

    @Test
    fun `skipping points is rejected without changing state`() {
        val engine = MatchEngine(firstServer = Player.ONE)
        engine.applyAnnouncement(Announcement.Points(1, 0)) // 15-0
        val before = engine.state

        // 15-30 недостижимо из 15-0 за один розыгрыш.
        val result = engine.applyAnnouncement(Announcement.Points(1, 2))

        assertEquals(ApplyResult.Rejected(RejectionReason.SKIP), result)
        assertEquals(before, engine.state)

        // А допустимые шаги проходят: +1 очко любому игроку.
        assertEquals(ApplyResult.Applied, engine.applyAnnouncement(Announcement.Points(1, 1))) // 15-15
        assertEquals(ApplyResult.Applied, engine.applyAnnouncement(Announcement.Points(2, 1))) // 30-15
    }

    @Test
    fun `invalid announcement values are rejected`() {
        val engine = MatchEngine(firstServer = Player.ONE)
        val result = engine.applyAnnouncement(Announcement.Points(5, 0))
        assertEquals(ApplyResult.Rejected(RejectionReason.INVALID), result)
    }

    @Test
    fun `deuce and advantage announcements`() {
        val engine = MatchEngine(firstServer = Player.ONE)
        engine.editGameScore(3, 3) // 40-40

        // «ровно» на уже ровном счёте — дубликат
        assertEquals(
            ApplyResult.Rejected(RejectionReason.DUPLICATE),
            engine.applyAnnouncement(Announcement.Deuce),
        )

        // «больше» у подающего (игрок 1)
        assertEquals(ApplyResult.Applied, engine.applyAnnouncement(Announcement.Advantage(toServer = true)))
        assertEquals(Player.ONE, engine.state.currentSet.currentGame.advantagePlayer)

        // повторное «больше» — дубликат
        assertEquals(
            ApplyResult.Rejected(RejectionReason.DUPLICATE),
            engine.applyAnnouncement(Announcement.Advantage(toServer = true)),
        )

        // «ровно» после advantage — принимающий сравнял
        assertEquals(ApplyResult.Applied, engine.applyAnnouncement(Announcement.Deuce))
        assertTrue(engine.state.currentSet.currentGame.isDeuce)
    }

    @Test
    fun `deuce and advantage must be reachable in exactly one rally`() {
        val engine = MatchEngine(firstServer = Player.ONE)
        val before = engine.state

        // Из 0:0 нельзя сразу объявить «ровно» или «больше».
        assertEquals(
            ApplyResult.Rejected(RejectionReason.SKIP),
            engine.applyAnnouncement(Announcement.Deuce),
        )
        assertEquals(
            ApplyResult.Rejected(RejectionReason.SKIP),
            engine.applyAnnouncement(Announcement.Advantage(toServer = true)),
        )
        assertEquals(before, engine.state)

        // Из 30-30 — ещё не deuce/advantage.
        engine.editGameScore(2, 2) // 30-30
        val beforeThirtyAll = engine.state
        assertEquals(
            ApplyResult.Rejected(RejectionReason.SKIP),
            engine.applyAnnouncement(Announcement.Deuce),
        )
        assertEquals(
            ApplyResult.Rejected(RejectionReason.SKIP),
            engine.applyAnnouncement(Announcement.Advantage(toServer = true)),
        )
        assertEquals(beforeThirtyAll, engine.state)

        // Из 40:30 / 30:40 «ровно» — нормальный переход за один мяч.
        engine.editGameScore(3, 2) // 40-30
        assertEquals(
            ApplyResult.Applied,
            engine.applyAnnouncement(Announcement.Deuce),
        )
        assertTrue(engine.state.currentSet.currentGame.isDeuce)

        engine.editGameScore(2, 3) // 30-40
        assertEquals(
            ApplyResult.Applied,
            engine.applyAnnouncement(Announcement.Deuce),
        )
        assertTrue(engine.state.currentSet.currentGame.isDeuce)

        // Из 40-30 advantage для кого-либо недостижим за один розыгрыш.
        engine.editGameScore(3, 2) // 40-30
        val beforeFortyThirty = engine.state
        assertEquals(
            ApplyResult.Rejected(RejectionReason.SKIP),
            engine.applyAnnouncement(Announcement.Advantage(toServer = true)),
        )
        assertEquals(
            ApplyResult.Rejected(RejectionReason.SKIP),
            engine.applyAnnouncement(Announcement.Advantage(toServer = false)),
        )
        assertEquals(beforeFortyThirty, engine.state)

        // Из advantage соперника «ровно» — допустимый переход.
        engine.editGameScore(3, 4) // 40-AD
        assertEquals(
            ApplyResult.Applied,
            engine.applyAnnouncement(Announcement.Deuce),
        )
        assertTrue(engine.state.currentSet.currentGame.isDeuce)
    }

    @Test
    fun `advantage cannot switch directly to the other player`() {
        val engine = MatchEngine(firstServer = Player.ONE)
        engine.editGameScore(3, 3) // ровно
        assertEquals(
            ApplyResult.Applied,
            engine.applyAnnouncement(Announcement.Advantage(toServer = true)), // «больше»
        )
        val before = engine.state

        // «меньше» сразу после «больше» недопустимо: только «ровно» или «гейм».
        assertEquals(
            ApplyResult.Rejected(RejectionReason.SKIP),
            engine.applyAnnouncement(Announcement.Advantage(toServer = false)),
        )
        assertEquals(before, engine.state)

        // «ровно» — можно, и после него преимущество вправе взять другой игрок.
        assertEquals(ApplyResult.Applied, engine.applyAnnouncement(Announcement.Deuce))
        assertEquals(
            ApplyResult.Applied,
            engine.applyAnnouncement(Announcement.Advantage(toServer = false)),
        )
        assertEquals(Player.TWO, engine.state.currentSet.currentGame.advantagePlayer)
    }

    @Test
    fun `validation disabled applies any announcement`() {
        val engine = MatchEngine(firstServer = Player.ONE)
        engine.strictValidation = false
        engine.editGameScore(2, 1) // 30-15

        // Назад — применяется.
        assertEquals(ApplyResult.Applied, engine.applyAnnouncement(Announcement.Points(1, 0)))
        assertEquals(1, engine.state.currentSet.currentGame.pointsP1)

        // Дубликат — применяется.
        assertEquals(ApplyResult.Applied, engine.applyAnnouncement(Announcement.Points(1, 0)))

        // Перескок — применяется.
        assertEquals(ApplyResult.Applied, engine.applyAnnouncement(Announcement.Points(3, 2)))
        assertEquals(GameState(3, 2), engine.state.currentSet.currentGame)
    }

    @Test
    fun `validation disabled still rejects invalid values`() {
        val engine = MatchEngine(firstServer = Player.ONE)
        engine.strictValidation = false
        assertEquals(
            ApplyResult.Rejected(RejectionReason.INVALID),
            engine.applyAnnouncement(Announcement.Points(5, 0)),
        )
    }

    @Test
    fun `validation disabled allows duplicate deuce and direct advantage switch`() {
        val engine = MatchEngine(firstServer = Player.ONE)
        engine.strictValidation = false

        assertEquals(ApplyResult.Applied, engine.applyAnnouncement(Announcement.Deuce))
        // Повторное «ровно» — применяется.
        assertEquals(ApplyResult.Applied, engine.applyAnnouncement(Announcement.Deuce))
        assertTrue(engine.state.currentSet.currentGame.isDeuce)

        assertEquals(
            ApplyResult.Applied,
            engine.applyAnnouncement(Announcement.Advantage(toServer = true)),
        )
        // Прямая смена преимущества «больше» -> «меньше» — применяется.
        assertEquals(
            ApplyResult.Applied,
            engine.applyAnnouncement(Announcement.Advantage(toServer = false)),
        )
        assertEquals(Player.TWO, engine.state.currentSet.currentGame.advantagePlayer)
    }

    @Test
    fun `undo restores previous state including server`() {
        val engine = MatchEngine(firstServer = Player.ONE)
        engine.playGame(Player.ONE)
        assertEquals(Player.TWO, engine.state.server)

        assertEquals(ChangeSource.PLAY, engine.undo())
        assertEquals(Player.ONE, engine.state.server)
        assertEquals(MatchState(firstServer = Player.ONE), engine.state)
        assertNull(engine.undo()) // больше нечего отменять
    }

    @Test
    fun `sets are unlimited - match continues after set ends`() {
        val engine = MatchEngine(firstServer = Player.ONE)
        engine.playSet(p1Games = 6, p2Games = 4)

        assertEquals(listOf(SetScore(6, 4)), engine.state.completedSets)
        assertEquals(SetState(), engine.state.currentSet)

        // Матч не завершён — следующий сет начался автоматически.
        engine.playSet(p1Games = 2, p2Games = 6)
        assertEquals(listOf(SetScore(6, 4), SetScore(2, 6)), engine.state.completedSets)

        // Подающий корректно определяется по общему числу геймов матча (10 + 8 = 18 -> чётное).
        assertEquals(Player.ONE, engine.state.server)
        engine.playGame(Player.ONE)
        assertEquals(Player.TWO, engine.state.server)
    }

    @Test
    fun `manual game score edit applies playable score`() {
        val engine = MatchEngine(firstServer = Player.ONE)
        engine.editGameScore(3, 2) // 40-30
        assertEquals(GameState(3, 2), engine.state.currentSet.currentGame)

        engine.editGameScore(4, 3) // AD — допустимая правка, гейм не завершён
        assertEquals(GameState(4, 3), engine.state.currentSet.currentGame)
        assertEquals(0, engine.state.currentSet.gamesP1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `manual game score edit cannot silently finish game`() {
        val engine = MatchEngine(firstServer = Player.ONE)
        engine.editGameScore(3, 2) // 40-30
        engine.editGameScore(4, 1) // AD против 15 — это выигранный гейм, правка запрещена
    }

    @Test(expected = IllegalArgumentException::class)
    fun `manual game score edit cannot finish game from scratch`() {
        val engine = MatchEngine(firstServer = Player.ONE)
        engine.editGameScore(4, 0) // AD против 0 — выигранный гейм, правка запрещена
    }

    @Test
    fun `manual set score edit closes finished set`() {
        val engine = MatchEngine(firstServer = Player.ONE)
        engine.playGame(Player.ONE) // 1:0
        engine.editSetScore(6, 4) // сет завершён -> переносится в историю

        assertEquals(listOf(SetScore(6, 4)), engine.state.completedSets)
        assertEquals(SetState(), engine.state.currentSet)

        assertEquals(ChangeSource.MANUAL_EDIT, engine.undo()) // правку можно отменить
        assertEquals(0, engine.state.completedSets.size)
        assertEquals(1, engine.state.currentSet.gamesP1)
        assertEquals(0, engine.state.currentSet.gamesP2)
    }

    @Test
    fun `undo reports source of undone action`() {
        val engine = MatchEngine(firstServer = Player.ONE)

        engine.addPoint(Player.ONE)
        assertEquals(ChangeSource.PLAY, engine.undo())

        engine.editGameScore(3, 3) // 40-40
        assertEquals(ChangeSource.MANUAL_EDIT, engine.undo())

        engine.editSetScore(4, 1)
        assertEquals(ChangeSource.MANUAL_EDIT, engine.undo())

        engine.applyAnnouncement(Announcement.Points(1, 0))
        assertEquals(ChangeSource.PLAY, engine.undo())

        assertNull(engine.undo()) // история пуста
    }

    // Regression (#9): undo ручной правки вниз возвращает счёт с большим
    // totalGames — UI полагается на ChangeSource.MANUAL_EDIT, чтобы подавить
    // ложный звонок/TTS «Гейм».
    @Test
    fun `undo of manual edit down restores higher score and reports MANUAL_EDIT`() {
        val engine = MatchEngine(firstServer = Player.ONE)
        repeat(4) { engine.playGame(Player.ONE) }
        engine.playGame(Player.TWO) // 4:1
        engine.editSetScore(2, 1) // ошибочная правка вниз

        assertEquals(ChangeSource.MANUAL_EDIT, engine.undo())
        assertEquals(4, engine.state.currentSet.gamesP1)
        assertEquals(1, engine.state.currentSet.gamesP2)

        // Отмена последнего сыгранного гейма (его выиграл игрок 2) — обычный PLAY.
        assertEquals(ChangeSource.PLAY, engine.undo())
        assertEquals(4, engine.state.currentSet.gamesP1)
        assertEquals(0, engine.state.currentSet.gamesP2)
    }

    @Test
    fun `restore sets state, log and undo history are cleared`() {
        val engine = MatchEngine(firstServer = Player.ONE)
        engine.addPoint(Player.ONE)
        val saved = engine.state

        val restored = MatchEngine(firstServer = Player.TWO)
        restored.restore(saved)

        assertEquals(saved, restored.state)
        assertTrue(restored.log.isEmpty()) // лог не персистится и не восстанавливается
        assertFalse(restored.canUndo) // история отмены не переживает восстановление

        // Новые действия после восстановления работают и отменяются.
        restored.addPoint(Player.TWO)
        assertTrue(restored.canUndo)
        assertEquals(ChangeSource.PLAY, restored.undo())
        assertEquals(saved, restored.state)
    }

    @Test
    fun `log records events`() {
        val engine = MatchEngine(firstServer = Player.ONE)
        engine.addPoint(Player.ONE)
        engine.applyAnnouncement(Announcement.Points(1, 1))
        engine.undo()
        assertEquals(3, engine.log.size)
    }

    @Test
    fun `log is bounded by MAX_LOG_ENTRIES`() {
        val engine = MatchEngine(firstServer = Player.ONE)
        repeat(MatchEngine.MAX_LOG_ENTRIES + 50) { engine.logNote("событие $it") }

        assertEquals(MatchEngine.MAX_LOG_ENTRIES, engine.log.size)
        // Остались самые свежие записи.
        assertEquals("событие 149", engine.log.last())
        assertEquals("событие 50", engine.log.first())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `manual set edit rejects impossible finished score 7-4`() {
        val engine = MatchEngine(firstServer = Player.ONE)
        engine.editSetScore(7, 4)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `manual set edit rejects impossible finished score 100-1`() {
        val engine = MatchEngine(firstServer = Player.ONE)
        engine.editSetScore(100, 1)
    }

    @Test
    fun `manual set edit allows valid finishing score 6-4`() {
        val engine = MatchEngine(firstServer = Player.ONE)
        engine.editSetScore(6, 4)
        assertEquals(listOf(SetScore(6, 4)), engine.state.completedSets)
    }
}
