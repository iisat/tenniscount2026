package com.tenniscount.app.score

import kotlin.math.abs

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

    /**
     * Объявление недостижимо из текущего состояния за один розыгрыш:
     * перескок очков либо прямая смена преимущества («меньше» после «больше»).
     */
    SKIP,
}

sealed interface ApplyResult {
    data object Applied : ApplyResult
    data class Rejected(val reason: RejectionReason) : ApplyResult
}

/** Источник изменения счёта: розыгрыш/объявление или ручная правка. */
enum class ChangeSource {
    /** Сыгранное очко или голосовое объявление — отражает реальный розыгрыш. */
    PLAY,

    /** Ручная правка счёта из диалога — розыгрыша не было. */
    MANUAL_EDIT,
}

/**
 * Движок матча: хранит текущее [MatchState], применяет очки, объявления и
 * ручные правки, ведёт историю для отмены последнего действия и лог событий.
 */
class MatchEngine(firstServer: Player) {

    /**
     * Строгая проверка объявлений: противоречия текущему счёту отклоняются.
     * Если false — любое объявление применяется как есть (кроме недопустимых значений).
     */
    var strictValidation = true

    var state: MatchState = MatchState(firstServer = firstServer)
        private set

    /** Запись истории отмены: состояние до действия + источник этого действия. */
    private data class UndoEntry(val state: MatchState, val source: ChangeSource)

    private val undoStack = ArrayDeque<UndoEntry>()
    private val _log = ArrayDeque<String>()

    /**
     * Лог объявлений и изменений счёта для показа на табло. Живёт только в
     * памяти и ограничен последними [MAX_LOG_ENTRIES] записями — за матч с
     * непрерывным распознаванием набираются тысячи строк.
     */
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
        addLog(entry)
    }

    /**
     * Восстанавливает сохранённое состояние (незавершённый матч между запусками
     * приложения). История отмены и лог не сохраняются: undo доступен только для
     * действий после восстановления, лог на табло начинается пустым.
     */
    fun restore(state: MatchState) {
        undoStack.clear()
        this.state = state
        _log.clear()
    }

    /**
     * Отмена последнего действия. Возвращает источник отменённого действия
     * (UI подавляет сигналы переходов при отмене ручной правки) либо null,
     * если отменять нечего.
     */
    fun undo(): ChangeSource? {
        val entry = undoStack.removeLastOrNull() ?: return null
        state = entry.state
        addLog("Отмена последнего действия")
        return entry.source
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
                val gained = (p1 - game.pointsP1) + (p2 - game.pointsP2)
                when {
                    strictValidation && announced == game ->
                        ApplyResult.Rejected(RejectionReason.DUPLICATE)
                    strictValidation && (p1 < game.pointsP1 || p2 < game.pointsP2) ->
                        ApplyResult.Rejected(RejectionReason.BACKWARD)
                    // За один розыгрыш разыгрывается одно очко: перескок невозможен.
                    strictValidation && gained > 1 ->
                        ApplyResult.Rejected(RejectionReason.SKIP)
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
                if (strictValidation && game.isDeuce) {
                    return ApplyResult.Rejected(RejectionReason.DUPLICATE)
                }
                // Строгая валидация: «ровно» должно быть достижимо ровно одним
                // следующим розыгрышем (например, 40:30 -> 40:40).
                if (strictValidation) {
                    val reachable = !game.isFinished &&
                        Player.entries.any { game.withPoint(it).isDeuce }
                    if (!reachable) {
                        return ApplyResult.Rejected(RejectionReason.SKIP)
                    }
                }
                // «Ровно» допустимо и из advantage (соперник сравнял): выравниваем счёт.
                val base = maxOf(3, game.pointsP1, game.pointsP2)
                mutate(state.withCurrentGame(GameState(base, base)), "Объявлено: ровно")
                ApplyResult.Applied
            }

            is Announcement.Advantage -> {
                val advPlayer = if (announcement.toServer) server else server.opponent
                val base = maxOf(3, game.pointsP1, game.pointsP2)
                val announced = when (advPlayer) {
                    Player.ONE -> GameState(base + 1, base)
                    Player.TWO -> GameState(base, base + 1)
                }
                if (strictValidation) {
                    // Повторное «больше»/«меньше» того же игрока — дубликат.
                    if (game.advantageOf(advPlayer)) {
                        return ApplyResult.Rejected(RejectionReason.DUPLICATE)
                    }
                    val reachable = !game.isFinished &&
                        Player.entries.any { game.withPoint(it) == announced }
                    if (!reachable) {
                        return ApplyResult.Rejected(RejectionReason.SKIP)
                    }
                }
                mutate(state.withCurrentGame(announced), "Объявлено: больше (${advPlayer.displayName})")
                ApplyResult.Applied
            }
        }
    }

    /**
     * Ручная правка счёта в гейме (счётчики розыгрышей, 0..4).
     * Правка не может завершать гейм: выигрыш гейма — только через [winGame]
     * или сыгранное очко, иначе гейм засчитался бы молча, без розыгрыша.
     */
    fun editGameScore(pointsP1: Int, pointsP2: Int) {
        require(GameState.isValidManualScore(pointsP1, pointsP2)) {
            "Ручная правка очков не может завершать гейм: $pointsP1-$pointsP2"
        }
        mutate(
            state.withCurrentGame(GameState(pointsP1, pointsP2)),
            "Правка очков: $pointsP1-$pointsP2",
            ChangeSource.MANUAL_EDIT,
        )
    }

    /**
     * Ручная правка счёта геймов в текущем сете.
     * Если заданный счёт является терминальным (завершает сет), он должен быть
     * реально достижимым (проходить [SetScore.isFinishedScore]). Например,
     * 6:4 допустим (сет завершается), а 7:4 или 100:1 — нет.
     */
    fun editSetScore(gamesP1: Int, gamesP2: Int) {
        require(gamesP1 >= 0 && gamesP2 >= 0) { "Недопустимый счёт сета" }
        val set = SetState(gamesP1, gamesP2)
        require(!set.isFinished || set.score.isFinishedScore) {
            "Недостижимый завершённый сет: $gamesP1:$gamesP2"
        }
        mutate(
            state.withGames(gamesP1, gamesP2),
            "Правка геймов: $gamesP1:$gamesP2",
            ChangeSource.MANUAL_EDIT,
        )
    }

    private fun mutate(
        newState: MatchState,
        logEntry: String,
        source: ChangeSource = ChangeSource.PLAY,
    ) {
        undoStack.addLast(UndoEntry(state, source))
        state = newState
        addLog(logEntry)
    }

    private fun addLog(entry: String) {
        _log.addLast(entry)
        while (_log.size > MAX_LOG_ENTRIES) _log.removeFirst()
    }

    /** Истинное преимущество (не путать с 40:30). */
    private fun GameState.advantageOf(player: Player): Boolean =
        pointsP1 >= 3 && pointsP2 >= 3 && abs(pointsP1 - pointsP2) == 1 &&
            ((player == Player.ONE && pointsP1 > pointsP2) ||
                (player == Player.TWO && pointsP2 > pointsP1))

    private fun Int.toTennisPoints(): String = when (this) {
        0 -> "0"
        1 -> "15"
        2 -> "30"
        else -> "40"
    }

    companion object {
        /** Лог хранит только последние записи — этого хватает для табло. */
        const val MAX_LOG_ENTRIES = 100
    }
}
