package com.tenniscount.app.score

import kotlin.math.abs

/**
 * Счёт внутри одного гейма. Очки хранятся как счётчики выигранных розыгрышей:
 * 0 = «ноль», 1 = «15», 2 = «30», 3 = «40», 4+ = advantage/гейм в зоне deuce.
 */
data class GameState(
    val pointsP1: Int = 0,
    val pointsP2: Int = 0,
) {
    init {
        require(pointsP1 >= 0 && pointsP2 >= 0) { "Очки не могут быть отрицательными" }
    }

    val winner: Player?
        get() {
            val max = maxOf(pointsP1, pointsP2)
            val diff = abs(pointsP1 - pointsP2)
            return when {
                max < 4 || diff < 2 -> null
                pointsP1 > pointsP2 -> Player.ONE
                else -> Player.TWO
            }
        }

    val isFinished: Boolean get() = winner != null

    /** «Ровно»: оба игрока набрали минимум «40» и сравнялись. */
    val isDeuce: Boolean
        get() = pointsP1 >= 3 && pointsP1 == pointsP2

    /** Игрок с преимуществом («больше»), либо null. */
    val advantagePlayer: Player?
        get() = when {
            pointsP1 >= 3 && pointsP1 == pointsP2 + 1 -> Player.ONE
            pointsP2 >= 3 && pointsP2 == pointsP1 + 1 -> Player.TWO
            else -> null
        }

    fun points(player: Player): Int = when (player) {
        Player.ONE -> pointsP1
        Player.TWO -> pointsP2
    }

    fun withPoint(player: Player): GameState {
        require(!isFinished) { "Гейм уже завершён" }
        return when (player) {
            Player.ONE -> copy(pointsP1 = pointsP1 + 1)
            Player.TWO -> copy(pointsP2 = pointsP2 + 1)
        }
    }

    /** Отображение очков игрока на табло: «0», «15», «30», «40» или «AD». */
    fun displayPoints(player: Player): String {
        val own = points(player)
        return when {
            pointsP1 >= 3 && pointsP2 >= 3 -> if (own > points(player.opponent)) "AD" else "40"
            else -> POINT_LABELS[own.coerceAtMost(3)]
        }
    }

    companion object {
        private val POINT_LABELS = arrayOf("0", "15", "30", "40")

        /**
         * Переводит теннисное значение очков (0, 15, 30, 40) в счётчик розыгрышей.
         * Возвращает null для недопустимого значения.
         */
        fun pointsToCount(value: Int): Int? = when (value) {
            0 -> 0
            15 -> 1
            30 -> 2
            40 -> 3
            else -> null
        }
    }
}
