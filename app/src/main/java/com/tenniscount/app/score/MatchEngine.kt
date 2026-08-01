package com.tenniscount.app.score

/**
 * Объявление счёта, распознанное из речи (или введённое вручную).
 * Числа всегда идут в порядке объявления: сначала очки подающего.
 */
sealed interface Announcement {
    /** Очки гейма счётчиками розыгрышей: 0 = «ноль», 1 = «15», 2 = «30», 3 = «40». */
    data class Points(val serverPoints: Int, val receiverPoints: Int) : Announcement

    /** «Ровно» — deuce. */
    data object Deuce : Announcement

    /** «Больше» — advantage. [toServer] = true, если преимущество у подающего. */
    data class Advantage(val toServer: Boolean) : Announcement
}

enum class RejectionReason {
    /** Объявленный счёт меньше текущего — противоречие, состояние не меняется. */
    BACKWARD,

    /** Объявленный счёт совпадает с текущим — повторное распознавание той же фразы. */
    DUPLICATE,

    /** Недопустимые значения. */
    INVALID,
}

sealed interface ApplyResult {
    data object Applied : ApplyResult
    data class Rejected(val reason: RejectionReason) : ApplyResult
}

/**
 * Движок матча: хранит текущее [MatchState], применяет очки, объявления и
 * ручные правки, ведёт историю для отмены последнего действия и лог событий.
 */
class MatchEngine(firstServer: Player) {

    var state: MatchState = MatchState(firstServer = firstServer)
        private set

    private val undoStack = ArrayDeque<MatchState>()
    private val _log = mutableListOf<String>()

    /** Лог объявлений и изменений счёта (для экрана истории). */
    val log: List<String>
        get() = _log.toList()

    val canUndo: Boolean
        get() = undoStack.isNotEmpty()

    /** Ручное добавление очка игроку. */
    fun addPoint(player: Player) {
        mutate(state.withPoint(player), "Очко: ${player.displayName}")
    }

    /** Засчитать гейм игроку (команда «гейм» / ручная правка). */
    fun winGame(player: Player) {
        mutate(state.withGameWon(player), "Гейм: ${player.displayName}")
    }

    /** Запись в лог без изменения состояния (диагностика распознавания и т.п.). */
    fun logNote(entry: String) {
        _log += entry
    }

    /** Отмена последнего действия. Возвращает false, если отменять нечего. */
    fun undo(): Boolean {
        val previous = undoStack.removeLastOrNull() ?: return false
        state = previous
        _log += "Отмена последнего действия"
        return true
    }

    /**
     * Применяет распознанное объявление. Порядок чисел — сначала очки подающего;
     * движок переводит их в очки игрока 1 / игрока 2 по текущему подающему.
     * При противоречии с текущим состоянием возвращает [ApplyResult.Rejected],
     * состояние не меняется.
     */
    fun applyAnnouncement(announcement: Announcement): ApplyResult {
        val game = state.currentSet.currentGame
        val server = state.server

        return when (announcement) {
            is Announcement.Points -> {
                val p1 = if (server == Player.ONE) announcement.serverPoints else announcement.receiverPoints
                val p2 = if (server == Player.ONE) announcement.receiverPoints else announcement.serverPoints
                if (p1 !in 0..3 || p2 !in 0..3) return ApplyResult.Rejected(RejectionReason.INVALID)

                val announced = GameState(p1, p2)
                when {
                    announced == game -> ApplyResult.Rejected(RejectionReason.DUPLICATE)
                    p1 < game.pointsP1 || p2 < game.pointsP2 ->
                        ApplyResult.Rejected(RejectionReason.BACKWARD)
                    else -> {
                        mutate(
                            state.withCurrentGame(announced),
                            "Объявлено: ${announcement.serverPoints.toTennisPoints()}-${announcement.receiverPoints.toTennisPoints()}",
                        )
                        ApplyResult.Applied
                    }
                }
            }

            Announcement.Deuce -> {
                if (game.isDeuce) return ApplyResult.Rejected(RejectionReason.DUPLICATE)
                // «Ровно» допустимо и из advantage (соперник сравнял): выравниваем счёт.
                val base = maxOf(3, game.pointsP1, game.pointsP2)
                mutate(state.withCurrentGame(GameState(base, base)), "Объявлено: ровно")
                ApplyResult.Applied
            }

            is Announcement.Advantage -> {
                val advPlayer = if (announcement.toServer) server else server.opponent
                if (game.advantagePlayer == advPlayer) {
                    return ApplyResult.Rejected(RejectionReason.DUPLICATE)
                }
                val base = maxOf(3, game.pointsP1, game.pointsP2)
                val announced = when (advPlayer) {
                    Player.ONE -> GameState(base + 1, base)
                    Player.TWO -> GameState(base, base + 1)
                }
                mutate(state.withCurrentGame(announced), "Объявлено: больше (${advPlayer.displayName})")
                ApplyResult.Applied
            }
        }
    }

    /** Ручная правка счёта в гейме (счётчики розыгрышей, 0..4). */
    fun editGameScore(pointsP1: Int, pointsP2: Int) {
        require(pointsP1 in 0..4 && pointsP2 in 0..4) { "Недопустимый счёт гейма" }
        mutate(state.withCurrentGame(GameState(pointsP1, pointsP2)), "Правка очков: $pointsP1-$pointsP2")
    }

    /** Ручная правка счёта геймов в текущем сете. */
    fun editSetScore(gamesP1: Int, gamesP2: Int) {
        require(gamesP1 >= 0 && gamesP2 >= 0) { "Недопустимый счёт сета" }
        mutate(state.withGames(gamesP1, gamesP2), "Правка геймов: $gamesP1:$gamesP2")
    }

    private fun mutate(newState: MatchState, logEntry: String) {
        undoStack.addLast(state)
        state = newState
        _log += logEntry
    }

    private fun Int.toTennisPoints(): String = when (this) {
        0 -> "0"
        1 -> "15"
        2 -> "30"
        else -> "40"
    }
}
