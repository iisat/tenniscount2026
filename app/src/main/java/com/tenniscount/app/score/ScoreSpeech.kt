package com.tenniscount.app.score

/**
 * Текст счёта для голосового проговаривания (команда «счёт»).
 * Формат — как объявляют игроки: сначала очки подающего.
 * Чистый Kotlin, без Android-зависимостей.
 */
object ScoreSpeech {

    private val POINT_WORDS = arrayOf("ноль", "пятнадцать", "тридцать", "сорок")

    /** Счёт внутри текущего гейма: «пятнадцать ноль», «ровно», «больше, Игрок 1». */
    fun gameScore(state: MatchState, playerName: (Player) -> String): String {
        val game = state.currentSet.currentGame
        val advantage = game.advantagePlayer
        return when {
            game.isDeuce -> "ровно"
            advantage != null -> "больше, ${playerName(advantage)}"
            else -> {
                val server = state.server
                val serverPoints = POINT_WORDS[game.points(server).coerceAtMost(3)]
                val receiverPoints = POINT_WORDS[game.points(server.opponent).coerceAtMost(3)]
                "$serverPoints $receiverPoints"
            }
        }
    }
}
