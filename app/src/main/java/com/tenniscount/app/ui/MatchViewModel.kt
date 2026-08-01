package com.tenniscount.app.ui

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tenniscount.app.score.ApplyResult
import com.tenniscount.app.score.MatchEngine
import com.tenniscount.app.score.MatchState
import com.tenniscount.app.score.Player
import com.tenniscount.app.score.RejectionReason
import com.tenniscount.app.speech.ModelManager
import com.tenniscount.app.speech.ScoreParser
import com.tenniscount.app.speech.VoiceCommand
import com.tenniscount.app.speech.VoskRecognizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class Screen { SETUP, SCOREBOARD }

enum class MicState { OFF, DOWNLOADING, PREPARING, LISTENING, ERROR }

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
    val micState: MicState = MicState.OFF,
    val micError: String? = null,
    val downloadProgress: Int? = null,
    /** Последняя услышанная фраза (частичный или финальный результат). */
    val lastHeard: String = "",
    /** Предупреждение о противоречии объявления текущему счёту. */
    val warning: String? = null,
) {
    fun name(player: Player): String = when (player) {
        Player.ONE -> player1Name
        Player.TWO -> player2Name
    }
}

class MatchViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(MatchUiState())
    val uiState: StateFlow<MatchUiState> = _uiState.asStateFlow()

    private var engine: MatchEngine? = null

    private val modelManager = ModelManager(application.filesDir)

    private val recognitionListener = object : VoskRecognizer.Listener {
        override fun onPartialResult(text: String) {
            _uiState.update { it.copy(lastHeard = text) }
        }

        override fun onFinalResult(text: String) {
            _uiState.update { it.copy(lastHeard = text) }
            ScoreParser.parse(text)?.let { applyVoiceCommand(it, text) }
        }

        override fun onError(message: String) {
            _uiState.update { it.copy(micState = MicState.ERROR, micError = message) }
        }
    }

    private val recognizer = VoskRecognizer(
        modelDir = modelManager.modelDir,
        listener = recognitionListener,
    )
    private val toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)

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

    fun togglePause() {
        val newPaused = !_uiState.value.paused
        _uiState.update { it.copy(paused = newPaused) }
        recognizer.setPaused(newPaused)
    }

    fun finishMatch() {
        stopListening()
        _uiState.update { it.copy(finished = true, paused = false) }
    }

    /** Сброс к экрану настройки нового матча; имена и первый подающий сохраняются. */
    fun newMatch() {
        stopListening()
        val s = _uiState.value
        engine = null
        _uiState.value = MatchUiState(
            player1Name = s.player1Name,
            player2Name = s.player2Name,
            firstServer = s.firstServer,
        )
    }

    // --- Распознавание речи ---

    fun toggleListening() {
        when (_uiState.value.micState) {
            MicState.OFF, MicState.ERROR -> startListening()
            MicState.LISTENING -> stopListening()
            // Идёт загрузка/подготовка модели — повторное нажатие игнорируем.
            MicState.DOWNLOADING, MicState.PREPARING -> Unit
        }
    }

    private fun startListening() {
        val context = getApplication<Application>()
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            _uiState.update {
                it.copy(micState = MicState.ERROR, micError = "Нет разрешения на микрофон")
            }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(micError = null, warning = null) }
            try {
                if (!modelManager.isModelReady()) {
                    _uiState.update { it.copy(micState = MicState.DOWNLOADING) }
                    modelManager.ensureModel { progress ->
                        _uiState.update { it.copy(downloadProgress = progress) }
                    }
                    _uiState.update { it.copy(downloadProgress = null) }
                }
                _uiState.update { it.copy(micState = MicState.PREPARING) }
                recognizer.prepare()
                if (recognizer.start()) {
                    _uiState.update { it.copy(micState = MicState.LISTENING) }
                } else {
                    _uiState.update { it.copy(micState = MicState.ERROR) }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        micState = MicState.ERROR,
                        downloadProgress = null,
                        micError = e.message ?: "Ошибка подготовки распознавания",
                    )
                }
            }
        }
    }

    fun clearWarning() = _uiState.update { it.copy(warning = null) }

    private fun stopListening() {
        recognizer.stop()
        _uiState.update { it.copy(micState = MicState.OFF, lastHeard = "") }
    }

    private fun applyVoiceCommand(command: VoiceCommand, rawText: String) {
        val s = _uiState.value
        if (s.paused || s.finished) return
        val currentEngine = engine ?: return

        when (command) {
            is VoiceCommand.Score ->
                when (val result = currentEngine.applyAnnouncement(command.announcement)) {
                    ApplyResult.Applied -> beep()
                    is ApplyResult.Rejected -> if (result.reason == RejectionReason.BACKWARD) {
                        _uiState.update {
                            it.copy(warning = "Противоречие: «$rawText» — счёт не изменён")
                        }
                    }
                }

            VoiceCommand.Undo -> if (currentEngine.undo()) beep()

            VoiceCommand.GameWon -> {
                currentEngine.winGame(resolveGameWinner(currentEngine.state))
                beep()
            }
        }
        sync()
    }

    /**
     * Победитель по объявлению «гейм»: игрок с преимуществом, иначе лидер по очкам,
     * иначе подающий (счёт объявляет именно он).
     */
    private fun resolveGameWinner(state: MatchState): Player {
        val game = state.currentSet.currentGame
        return game.advantagePlayer
            ?: if (game.pointsP1 != game.pointsP2) {
                if (game.pointsP1 > game.pointsP2) Player.ONE else Player.TWO
            } else {
                state.server
            }
    }

    /** Короткий beep — подтверждение, что объявление услышано, не глядя на экран. */
    private fun beep() {
        runCatching { toneGenerator.startTone(ToneGenerator.TONE_PROP_ACK, 150) }
    }

    override fun onCleared() {
        recognizer.release()
        toneGenerator.release()
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
