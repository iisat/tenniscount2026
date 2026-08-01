package com.tenniscount.app.score

/**
 * Форматирование счёта матча для уведомления foreground-сервиса
 * и для сохранения завершённого матча в историю. Чистый Kotlin.
 */
object MatchSummary {

    /**
     * Текущий счёт одной строкой: «40:15 · геймы 3:2 · сеты 1:0».
     * Порядок — от мелкого к крупному, чтобы в обрезанном уведомлении
     * было видно очки текущего гейма.
     */
    fun scoreLine(state: MatchState): String {
        val game = state.currentSet.currentGame
        val points = "${game.displayPoints(Player.ONE)}:${game.displayPoints(Player.TWO)}"
        val games = "${state.currentSet.gamesP1}:${state.currentSet.gamesP2}"
        val sets = "${setsWon(state, Player.ONE)}:${setsWon(state, Player.TWO)}"
        return "$points · геймы $games · сеты $sets"
    }

    /**
     * Счёт по сетам: «6:4 3:6». Незавершённый текущий сет (матч прерван
     * вручную) добавляется в скобках: «6:4 (3:2)».
     */
    fun setsSummary(state: MatchState): String {
        val completed = state.completedSets.joinToString(" ")
        val current = state.currentSet
        val currentPlayed = current.totalGames > 0 ||
            current.currentGame.pointsP1 > 0 || current.currentGame.pointsP2 > 0
        return when {
            currentPlayed && completed.isNotEmpty() -> "$completed (${current.gamesP1}:${current.gamesP2})"
            currentPlayed -> "(${current.gamesP1}:${current.gamesP2})"
            else -> completed
        }
    }

    /** Количество выигранных сетов (учитывается и текущий, если он завершён). */
    fun setsWon(state: MatchState, player: Player): Int =
        state.completedSets.count { it.winner == player } +
            if (state.currentSet.winner == player) 1 else 0
}
