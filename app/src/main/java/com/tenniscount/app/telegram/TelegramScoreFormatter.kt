package com.tenniscount.app.telegram

import com.tenniscount.app.score.MatchState
import com.tenniscount.app.score.MatchSummary
import com.tenniscount.app.score.Player

/**
 * Форматирование счёта матча для публикации в Telegram.
 */
object TelegramScoreFormatter {

    /** Текущее положение дел — для живого сообщения. */
    fun liveMessage(state: MatchState, player1Name: String, player2Name: String): String {
        val game = state.currentSet.currentGame
        val points = "${game.displayPoints(Player.ONE)}:${game.displayPoints(Player.TWO)}"
        val games = "${state.currentSet.gamesP1}:${state.currentSet.gamesP2}"
        val sets = "${MatchSummary.setsWon(state, Player.ONE)}:${MatchSummary.setsWon(state, Player.TWO)}"
        return "Матч: $player1Name — $player2Name\nОчки: $points\nГеймы: $games\nСеты: $sets"
    }

    /** Итоговое сообщение с комментарием «Матч завершен». */
    fun finalMessage(state: MatchState, player1Name: String, player2Name: String): String {
        val setsP1 = MatchSummary.setsWon(state, Player.ONE)
        val setsP2 = MatchSummary.setsWon(state, Player.TWO)
        val winnerText = when {
            setsP1 > setsP2 -> "Победитель: $player1Name"
            setsP2 > setsP1 -> "Победитель: $player2Name"
            else -> "Ничья"
        }
        return "Матч завершен\n\n$player1Name — $player2Name\n" +
            "Счёт по сетам: ${MatchSummary.setsSummary(state)}\n$winnerText"
    }
}
