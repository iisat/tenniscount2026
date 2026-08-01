package com.tenniscount.app.speech

import com.tenniscount.app.score.Announcement
import com.tenniscount.app.score.GameState

/**
 * Команда, распознанная из голосового объявления.
 * Объявляет счёт всегда подающий, поэтому «больше» = преимущество подающего.
 */
sealed interface VoiceCommand {
    /** Объявление счёта — применяется через MatchEngine.applyAnnouncement. */
    data class Score(val announcement: Announcement) : VoiceCommand

    /** «Отмена» / «отмени» — откат последнего действия. */
    data object Undo : VoiceCommand

    /** «Гейм» — засчитать гейм; победитель определяется по текущему состоянию. */
    data object GameWon : VoiceCommand
}

/**
 * Парсер распознанного текста в [VoiceCommand]. Чистый Kotlin, без Android-зависимостей.
 * Понимает числа словами и цифрами («пятнадцать-ноль», «15-0», «тридцать пятнадцать»)
 * и термины «ровно», «больше», «отмена», «гейм». Лишние слова игнорируются;
 * фразы без счёта возвращают null.
 */
object ScoreParser {

    private val NUMBER_WORDS = mapOf(
        "ноль" to 0,
        "нуль" to 0,
        "пятнадцать" to 15,
        "тридцать" to 30,
        "сорок" to 40,
    )

    private val TOKEN_SPLITTER = Regex("[^0-9a-zа-яё]+")

    fun parse(text: String): VoiceCommand? {
        val tokens = text.lowercase().split(TOKEN_SPLITTER).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null

        val numbers = tokens.mapNotNull { token ->
            if (token[0].isDigit()) token.toIntOrNull() else NUMBER_WORDS[token]
        }

        return when {
            numbers.size >= 2 -> {
                val serverPoints = GameState.pointsToCount(numbers[0])
                val receiverPoints = GameState.pointsToCount(numbers[1])
                if (serverPoints == null || receiverPoints == null) null
                else VoiceCommand.Score(Announcement.Points(serverPoints, receiverPoints))
            }
            tokens.contains("ровно") -> VoiceCommand.Score(Announcement.Deuce)
            tokens.contains("больше") -> VoiceCommand.Score(Announcement.Advantage(toServer = true))
            tokens.any { it == "отмена" || it == "отмени" } -> VoiceCommand.Undo
            tokens.contains("гейм") -> VoiceCommand.GameWon
            else -> null
        }
    }
}
