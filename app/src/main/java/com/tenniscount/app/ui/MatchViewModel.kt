package com.tenniscount.app.ui

import androidx.lifecycle.ViewModel
import com.tenniscount.app.score.MatchEngine
import com.tenniscount.app.score.MatchState
import com.tenniscount.app.score.Player
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class Screen { SETUP, SCOREBOARD }

/** Состояние UI матча. Имена игроков живут только в UI, ядро оперирует [Player]. */
data class MatchUiState(
    val screen: Screen = Screen.SETUP,
    val player1Name: String = "Игрок 1",
    val player2Name: String = "Игрок 2",
    val firstServer: Player = Player.ONE,
    val matchState: MatchState? = null,
    val paused: Boolean = false,
    val finished: Boolean = false,
    val canUndo: Boolean = false,
    val log: List<String> = emptyList(),
) {
    fun name(player: Player): String = when (player) {
        Player.ONE -> player1Name
        Player.TWO -> player2Name
    }
}

class MatchViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(MatchUiState())
    val uiState: StateFlow<MatchUiState> = _uiState.asStateFlow()

    private var engine: MatchEngine? = null

    fun setPlayer1Name(name: String) = _uiState.update { it.copy(player1Name = name) }

    fun setPlayer2Name(name: String) = _uiState.update { it.copy(player2Name = name) }

    fun setFirstServer(player: Player) = _uiState.update { it.copy(firstServer = player) }

    fun startMatch() {
        engine = MatchEngine(_uiState.value.firstServer)
        sync(screen = Screen.SCOREBOARD)
    }

    fun addPoint(player: Player) {
        val s = _uiState.value
        if (s.paused || s.finished) return
        engine?.addPoint(player)
        sync()
    }

    fun undo() {
        engine?.undo()
        sync()
    }

    fun editGameScore(pointsP1: Int, pointsP2: Int) {
        engine?.editGameScore(pointsP1, pointsP2)
        sync()
    }

    fun editSetScore(gamesP1: Int, gamesP2: Int) {
        engine?.editSetScore(gamesP1, gamesP2)
        sync()
    }

    fun togglePause() = _uiState.update { it.copy(paused = !it.paused) }

    fun finishMatch() = _uiState.update { it.copy(finished = true, paused = false) }

    /** Сброс к экрану настройки нового матча; имена и первый подающий сохраняются. */
    fun newMatch() {
        val s = _uiState.value
        engine = null
        _uiState.value = MatchUiState(
            player1Name = s.player1Name,
            player2Name = s.player2Name,
            firstServer = s.firstServer,
        )
    }

    private fun sync(screen: Screen? = null) {
        val e = engine ?: return
        _uiState.update {
            it.copy(
                matchState = e.state,
                canUndo = e.canUndo,
                log = e.log,
                screen = screen ?: it.screen,
            )
        }
    }
}
