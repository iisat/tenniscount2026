package com.tenniscount.app.score

/**
 * Тексты счёта для голосового проговаривания (команда «сколько»,
 * авто-озвучка конца гейма/сета и сет-поинта).
 * Формат — как объявляют игроки: сначала очки подающего, геймы — победителя.
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

    /** Конец гейма: «гейм, Игрок 1. Три два» — геймы победителя первыми. */
    fun gameEnd(state: MatchState, winner: Player, playerName: (Player) -> String): String {
        val set = state.currentSet
        return "Гейм, ${playerName(winner)}. ${set.games(winner)} ${set.games(winner.opponent)}"
    }

    /**
     * Игрок, для которого текущий гейм — сет-поинт: выигрыш этого гейма
     * завершает сет (у игрока 5+ геймов и перевес минимум в один).
     */
    fun setPointPlayer(state: MatchState): Player? {
        val set = state.currentSet
        return Player.entries.firstOrNull { p ->
            set.games(p) >= 5 && set.games(p) - set.games(p.opponent) >= 1
        }
    }

    /** Фраза сет-поинта: «сет-поинт, Игрок 1». */
    fun setPoint(player: Player, playerName: (Player) -> String): String =
        "Сет-поинт, ${playerName(player)}"

    /** Итог завершённого сета: «сет, Игрок 1. Шесть четыре» — геймы победителя первыми. */
    fun setEnd(set: SetScore, playerName: (Player) -> String): String {
        val winner = set.winner
        val winnerGames = if (winner == Player.ONE) set.gamesP1 else set.gamesP2
        val loserGames = if (winner == Player.ONE) set.gamesP2 else set.gamesP1
        return "Сет, ${playerName(winner)}. $winnerGames $loserGames"
    }
}
