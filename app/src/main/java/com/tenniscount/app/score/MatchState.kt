package com.tenniscount.app.score

/**
 * Полное состояние матча. Сеты не ограничены: после завершения сета
 * автоматически начинается следующий; матч завершается только вручную.
 */
data class MatchState(
    val firstServer: Player,
    val completedSets: List<SetScore> = emptyList(),
    val currentSet: SetState = SetState(),
) {
    /** Сколько геймов сыграно за матч — определяет подающего. */
    val totalGames: Int
        get() = completedSets.sumOf { it.gamesP1 + it.gamesP2 } + currentSet.totalGames

    /** Подающий меняется каждый гейм. */
    val server: Player
        get() = if (totalGames % 2 == 0) firstServer else firstServer.opponent

    fun withPoint(player: Player): MatchState = withSet(currentSet.withPoint(player))

    fun withGameWon(player: Player): MatchState = withSet(currentSet.withGameWon(player))

    /**
     * Устанавливает счёт текущего гейма напрямую (объявление/ручная правка).
     * Если заданный счёт означает выигранный гейм — гейм засчитывается.
     */
    fun withCurrentGame(game: GameState): MatchState {
        val winner = game.winner
        return if (winner != null) withGameWon(winner)
        else copy(currentSet = currentSet.copy(currentGame = game))
    }

    /** Устанавливает счёт геймов в текущем сете (ручная правка). */
    fun withGames(gamesP1: Int, gamesP2: Int): MatchState =
        withSet(currentSet.copy(gamesP1 = gamesP1, gamesP2 = gamesP2))

    private fun withSet(set: SetState): MatchState =
        if (set.isFinished) {
            copy(completedSets = completedSets + set.score, currentSet = SetState())
        } else {
            copy(currentSet = set)
        }
}
