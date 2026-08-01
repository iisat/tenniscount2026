package com.tenniscount.app.score

import kotlin.math.abs

/** Итог завершённого сета. */
data class SetScore(val gamesP1: Int, val gamesP2: Int) {
    val winner: Player
        get() = if (gamesP1 > gamesP2) Player.ONE else Player.TWO

    override fun toString(): String = "$gamesP1:$gamesP2"
}

/**
 * Счёт внутри сета: выигранные геймы + текущий гейм.
 * Сет выигрывается при 6+ геймах с разницей в 2; тай-брейка нет,
 * при 6:5 игра продолжается (7:5, 8:6, ...).
 */
data class SetState(
    val gamesP1: Int = 0,
    val gamesP2: Int = 0,
    val currentGame: GameState = GameState(),
) {
    init {
        require(gamesP1 >= 0 && gamesP2 >= 0) { "Геймы не могут быть отрицательными" }
    }

    val isFinished: Boolean
        get() = (gamesP1 >= 6 || gamesP2 >= 6) && abs(gamesP1 - gamesP2) >= 2

    val winner: Player?
        get() = if (!isFinished) null else if (gamesP1 > gamesP2) Player.ONE else Player.TWO

    val score: SetScore
        get() = SetScore(gamesP1, gamesP2)

    val totalGames: Int
        get() = gamesP1 + gamesP2

    fun games(player: Player): Int = when (player) {
        Player.ONE -> gamesP1
        Player.TWO -> gamesP2
    }

    /** Добавляет очко игроку; при выигрыше гейма засчитывает гейм и начинает новый. */
    fun withPoint(player: Player): SetState {
        require(!isFinished) { "Сет уже завершён" }
        val game = currentGame.withPoint(player)
        val gameWinner = game.winner ?: return copy(currentGame = game)
        return withGameWon(gameWinner)
    }

    /** Засчитывает гейм игроку независимо от очков (команда «гейм», ручная правка). */
    fun withGameWon(player: Player): SetState {
        require(!isFinished) { "Сет уже завершён" }
        return when (player) {
            Player.ONE -> copy(gamesP1 = gamesP1 + 1, currentGame = GameState())
            Player.TWO -> copy(gamesP2 = gamesP2 + 1, currentGame = GameState())
        }
    }
}
