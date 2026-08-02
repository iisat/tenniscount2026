package com.tenniscount.app.speech

import com.tenniscount.app.score.Announcement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScoreParserTest {

    @Test
    fun `числа словами через дефис`() {
        assertEquals(
            VoiceCommand.Score(Announcement.Points(serverPoints = 1, receiverPoints = 0)),
            ScoreParser.parse("пятнадцать-ноль"),
        )
    }

    @Test
    fun `числа словами через пробел`() {
        assertEquals(
            VoiceCommand.Score(Announcement.Points(serverPoints = 2, receiverPoints = 1)),
            ScoreParser.parse("тридцать пятнадцать"),
        )
    }

    @Test
    fun `числа цифрами`() {
        assertEquals(
            VoiceCommand.Score(Announcement.Points(serverPoints = 3, receiverPoints = 2)),
            ScoreParser.parse("40-30"),
        )
    }

    @Test
    fun `нуль как синоним нуля`() {
        assertEquals(
            VoiceCommand.Score(Announcement.Points(serverPoints = 0, receiverPoints = 0)),
            ScoreParser.parse("нуль ноль"),
        )
    }

    @Test
    fun `лишние слова вокруг счёта игнорируются`() {
        assertEquals(
            VoiceCommand.Score(Announcement.Points(serverPoints = 1, receiverPoints = 3)),
            ScoreParser.parse("счёт пятнадцать сорок"),
        )
    }

    @Test
    fun `ровно даёт deuce`() {
        assertEquals(VoiceCommand.Score(Announcement.Deuce), ScoreParser.parse("ровно"))
    }

    @Test
    fun `больше даёт advantage подающему`() {
        assertEquals(
            VoiceCommand.Score(Announcement.Advantage(toServer = true)),
            ScoreParser.parse("больше"),
        )
    }

    @Test
    fun `меньше даёт advantage принимающему`() {
        assertEquals(
            VoiceCommand.Score(Announcement.Advantage(toServer = false)),
            ScoreParser.parse("меньше"),
        )
    }

    @Test
    fun `отмена и отмени дают undo`() {
        assertEquals(VoiceCommand.Undo, ScoreParser.parse("отмена"))
        assertEquals(VoiceCommand.Undo, ScoreParser.parse("отмени"))
    }

    @Test
    fun `гейм даёт команду завершения гейма`() {
        assertEquals(VoiceCommand.GameWon, ScoreParser.parse("гейм"))
    }

    @Test
    fun `счёт даёт команду озвучки счёта`() {
        assertEquals(VoiceCommand.ScoreQuery, ScoreParser.parse("счет"))
        assertEquals(VoiceCommand.ScoreQuery, ScoreParser.parse("счёт"))
    }

    @Test
    fun `одно число — сокращённое объявление с нулём принимающего`() {
        // Движок часто слышит только первое слово: «15-0» → «пятнадцать».
        assertEquals(
            VoiceCommand.Score(Announcement.Points(serverPoints = 1, receiverPoints = 0)),
            ScoreParser.parse("пятнадцать"),
        )
        assertEquals(
            VoiceCommand.Score(Announcement.Points(serverPoints = 2, receiverPoints = 0)),
            ScoreParser.parse("тридцать"),
        )
        assertEquals(
            VoiceCommand.Score(Announcement.Points(serverPoints = 3, receiverPoints = 0)),
            ScoreParser.parse("40"),
        )
        assertEquals(
            VoiceCommand.Score(Announcement.Points(serverPoints = 0, receiverPoints = 0)),
            ScoreParser.parse("ноль"),
        )
    }

    @Test
    fun `недопустимые значения очков отбрасываются`() {
        assertNull(ScoreParser.parse("15-50"))
    }

    @Test
    fun `посторонняя речь не распознаётся как команда`() {
        assertNull(ScoreParser.parse("подача была хорошая"))
        assertNull(ScoreParser.parse(""))
    }
}
