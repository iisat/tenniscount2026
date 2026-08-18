package com.tenniscount.app.score

/**
 * Сериализация [MatchState] в компактную строку для сохранения незавершённого
 * матча между запусками приложения (SharedPreferences).
 * Формат v1: `v1|<firstServer>|<сеты g1:g2 через запятую>|<геймы1>|<геймы2>|<очки1>|<очки2>`
 * Пример: `v1|ONE|6:4,3:6|2|3|1|2`.
 */
object MatchStateCodec {

    private const val VERSION = "v1"
    private const val DELIM = "|"
    private const val SETS_DELIM = ","

    fun encode(state: MatchState): String = buildString {
        append(VERSION).append(DELIM)
        append(state.firstServer.name).append(DELIM)
        state.completedSets.joinTo(this, SETS_DELIM) { "${it.gamesP1}:${it.gamesP2}" }
        append(DELIM)
        append(state.currentSet.gamesP1).append(DELIM)
        append(state.currentSet.gamesP2).append(DELIM)
        append(state.currentSet.currentGame.pointsP1).append(DELIM)
        append(state.currentSet.currentGame.pointsP2)
    }

    /** Разбор строки; при любом несоответствии формату возвращает null. */
    fun decode(text: String): MatchState? = runCatching {
        val parts = text.split(DELIM)
        require(parts.size == 7 && parts[0] == VERSION)
        val firstServer = Player.valueOf(parts[1])
        val completedSets = if (parts[2].isEmpty()) {
            emptyList()
        } else {
            parts[2].split(SETS_DELIM).map {
                val games = it.split(":")
                require(games.size == 2)
                val score = SetScore(games[0].toInt(), games[1].toInt())
                require(score.isFinishedScore) { "Завершённый сет должен соответствовать правилам: $score" }
                score
            }
        }
        val currentSet = SetState(
            gamesP1 = parts[3].toInt(),
            gamesP2 = parts[4].toInt(),
            currentGame = GameState(parts[5].toInt(), parts[6].toInt()),
        )
        // Завершённых сетов/геймов в «текущих» быть не может — признак порчи данных.
        require(!currentSet.isFinished && !currentSet.currentGame.isFinished)
        MatchState(firstServer, completedSets, currentSet)
    }.getOrNull()
}
