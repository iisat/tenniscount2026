package com.tenniscount.app.ui

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tenniscount.app.data.FinishedMatchEntity
import com.tenniscount.app.data.MatchDatabase
import com.tenniscount.app.score.ApplyResult
import com.tenniscount.app.score.MatchEngine
import com.tenniscount.app.score.MatchState
import com.tenniscount.app.score.MatchStateCodec
import com.tenniscount.app.score.MatchSummary
import com.tenniscount.app.score.Player
import com.tenniscount.app.score.RejectionReason
import com.tenniscount.app.score.ScoreSpeech
import com.tenniscount.app.service.ListeningController
import com.tenniscount.app.service.ListeningService
import com.tenniscount.app.service.MicState
import com.tenniscount.app.speech.ScoreParser
import com.tenniscount.app.speech.VoiceCommand
import com.tenniscount.app.speech.VoskRecognizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import java.util.Locale

enum class Screen { SETUP, SCOREBOARD, HISTORY }

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
    /** Относительная громкость сигналов приложения (150–250% от медиа-громкости). */
    val signalVolume: Float = 2f,
    /** Озвучивать счёт по геймам в конце гейма. */
    val speakGameEnd: Boolean = true,
    /** Озвучивать «сет-поинт», когда игрок может выиграть сет в текущем гейме. */
    val speakSetPoint: Boolean = true,
    /** Озвучивать итоговый счёт по геймам после окончания сета. */
    val speakSetEnd: Boolean = true,
    /** Проверять объявленный счёт на противоречия текущему (иначе применять любой). */
    val strictValidation: Boolean = true,
) {
    fun name(player: Player): String = when (player) {
        Player.ONE -> player1Name
        Player.TWO -> player2Name
    }
}

class MatchViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _uiState = MutableStateFlow(
        // coerceIn: в старых версиях диапазон был ниже — значение подтягивается к минимуму 1.5.
        MatchUiState(
            player1Name = prefs.getString(KEY_PLAYER1_NAME, null) ?: "Игрок 1",
            player2Name = prefs.getString(KEY_PLAYER2_NAME, null) ?: "Игрок 2",
            firstServer = prefs.getString(KEY_FIRST_SERVER, null)
                ?.let { runCatching { Player.valueOf(it) }.getOrNull() } ?: Player.ONE,
            signalVolume = prefs.getFloat(KEY_SIGNAL_VOLUME, 2f).coerceIn(1.5f, 2.5f),
            speakGameEnd = prefs.getBoolean(KEY_SPEAK_GAME_END, true),
            speakSetPoint = prefs.getBoolean(KEY_SPEAK_SET_POINT, true),
            speakSetEnd = prefs.getBoolean(KEY_SPEAK_SET_END, true),
            strictValidation = prefs.getBoolean(KEY_STRICT_VALIDATION, true),
        ),
    )
    val uiState: StateFlow<MatchUiState> = _uiState.asStateFlow()

    private var engine: MatchEngine? = null

    /** Последнее синхронизированное состояние — для звуковых сигналов о гейме/сете. */
    private var lastSyncedState: MatchState? = null

    private val controller = ListeningController.get(application)
    private val db = MatchDatabase.get(application)

    /** Озвучка счёта по команде «счёт» (on-device TTS, русский). */
    private var tts: TextToSpeech? = null

    private val audioManager = application.getSystemService(AudioManager::class.java)

    /**
     * Фокус с приглушением музыки на время объявлений (звонок гейма/сета, TTS).
     * Работает, если музыкальный плеер уважает AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
     * (Spotify, Яндекс Музыка и др. приглушаются). Короткие beep не приглушают
     * музыку: duck не успевает сработать за 150 мс.
     */
    private val duckFocusRequest =
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build(),
            )
            .build()

    /** Счётчик активных объявлений: фокус держим, пока звучит хоть одно. */
    private var duckCount = 0

    /** Число произносимых в данный момент фраз TTS (для watchdog). */
    private var activeSpeechCount = 0

    /** Watchdog, принудительно сбрасывающий ducking, если колбэки TTS не пришли. */
    private var duckWatchdogJob: kotlinx.coroutines.Job? = null

    /** Завершённые матчи (история), новые сверху. */
    val history: StateFlow<List<FinishedMatchEntity>> = db.matchDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val recognitionListener = object : VoskRecognizer.Listener {
        override fun onPartialResult(text: String) = Unit

        override fun onFinalResult(text: String) {
            val command = ScoreParser.parse(text)
            if (command == null) {
                val s = _uiState.value
                if (!s.paused && !s.finished) {
                    engine?.logNote("Слышал: «$text» — не счёт")
                    sync()
                }
                return
            }
            applyVoiceCommand(command, text)
        }

        override fun onError(message: String) = Unit
    }

    init {
        runCatching {
            // Колбэк OnInitListener может сработать ДО того, как конструктор вернёт
            // объект и мы сохраним его в поле tts. Используем локальную переменную,
            // чтобы гарантированно настроить движок и повесить UtteranceProgressListener.
            var newTts: TextToSpeech? = null
            newTts = TextToSpeech(getApplication()) { status ->
                if (status != TextToSpeech.SUCCESS) return@TextToSpeech
                newTts?.let { t ->
                    t.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .build(),
                    )
                    Log.d(TAG, "tts: setLanguage(ru)=${t.setLanguage(Locale("ru"))}")
                    t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            activeSpeechCount++
                            cancelDuckWatchdog()
                        }

                        override fun onDone(utteranceId: String?) = onSpeechFinished()
                        override fun onStop(utteranceId: String?, interrupted: Boolean) =
                            onSpeechFinished()

                        @Deprecated("onError(String, Int) делегирует сюда")
                        override fun onError(utteranceId: String?) = onSpeechFinished()
                    })
                }
            }
            tts = newTts
        }.onFailure { Log.w(TAG, "tts: недоступен", it) }

        restoreSavedMatch()
        controller.listener = recognitionListener
        // Действия из уведомления foreground-сервиса (экран выключен).
        controller.onPauseToggleRequested = { togglePause() }
        controller.onStopRequested = { stopListening() }
        viewModelScope.launch {
            controller.state.collect { ls ->
                _uiState.update {
                    it.copy(
                        micState = ls.micState,
                        micError = ls.error,
                        downloadProgress = ls.downloadProgress,
                        lastHeard = ls.lastHeard,
                    )
                }
            }
        }
    }

    fun setPlayer1Name(name: String) {
        _uiState.update { it.copy(player1Name = name) }
        prefs.edit { putString(KEY_PLAYER1_NAME, name) }
    }

    fun setPlayer2Name(name: String) {
        _uiState.update { it.copy(player2Name = name) }
        prefs.edit { putString(KEY_PLAYER2_NAME, name) }
    }

    fun setFirstServer(player: Player) {
        _uiState.update { it.copy(firstServer = player) }
        prefs.edit { putString(KEY_FIRST_SERVER, player.name) }
    }

    fun startMatch() {
        engine = MatchEngine(_uiState.value.firstServer)
            .also { it.strictValidation = _uiState.value.strictValidation }
        lastSyncedState = null
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
        controller.setPaused(newPaused)
        updateNotification()
    }

    fun finishMatch() {
        val state = engine?.state
        val s = _uiState.value
        stopListening()
        if (state != null) {
            saveMatch(state, s.player1Name, s.player2Name, s.log)
        }
        // Матч завершён — сохранённый слепок больше не нужен.
        clearPersistedMatch()
        _uiState.update { it.copy(finished = true, paused = false) }
    }

    /** Сброс к экрану настройки нового матча; имена и первый подающий сохраняются. */
    fun newMatch() {
        stopListening()
        val s = _uiState.value
        engine = null
        lastSyncedState = null
        clearPersistedMatch()
        _uiState.value = MatchUiState(
            player1Name = s.player1Name,
            player2Name = s.player2Name,
            firstServer = s.firstServer,
            signalVolume = s.signalVolume,
            speakGameEnd = s.speakGameEnd,
            speakSetPoint = s.speakSetPoint,
            speakSetEnd = s.speakSetEnd,
            strictValidation = s.strictValidation,
        )
    }

    // --- История ---

    fun openHistory() = _uiState.update { it.copy(screen = Screen.HISTORY) }

    fun closeHistory() = _uiState.update { it.copy(screen = Screen.SETUP) }

    fun deleteMatch(id: Long) {
        viewModelScope.launch(Dispatchers.IO) { db.matchDao().delete(id) }
    }

    private fun saveMatch(
        state: MatchState,
        player1Name: String,
        player2Name: String,
        log: List<String>,
    ) {
        val entity = FinishedMatchEntity(
            finishedAt = System.currentTimeMillis(),
            player1Name = player1Name,
            player2Name = player2Name,
            setsP1 = MatchSummary.setsWon(state, Player.ONE),
            setsP2 = MatchSummary.setsWon(state, Player.TWO),
            setsSummary = MatchSummary.setsSummary(state),
            log = log.joinToString("\n"),
        )
        viewModelScope.launch(Dispatchers.IO) { db.matchDao().insert(entity) }
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

        // Foreground service поднимается ДО старта микрофона: иначе Android 12+
        // не даст доступ к микрофону при уходе приложения в фон.
        _uiState.update { it.copy(warning = null) }
        ContextCompat.startForegroundService(
            context,
            ListeningService.startIntent(context, currentScoreText()),
        )
        // Держим аудиотракт «тёплым», иначе холодный старт съедает короткие сигналы.
        SignalPlayer.setKeepAlive(true)
        viewModelScope.launch { controller.start() }
    }

    fun clearWarning() = _uiState.update { it.copy(warning = null) }

    private fun stopListening() {
        SignalPlayer.setKeepAlive(false)
        controller.stop()
        // stopService, а не ACTION_STOP: колбэк сервиса уже привёл нас сюда,
        // повторный интент зациклит остановку.
        getApplication<Application>().stopService(
            android.content.Intent(getApplication(), ListeningService::class.java)
        )
    }

    /** Обновляет текст счёта в уведомлении, если прослушивание активно. */
    private fun updateNotification() {
        val s = _uiState.value
        if (s.micState != MicState.LISTENING) return
        val context = getApplication<Application>()
        context.startService(ListeningService.updateIntent(context, currentScoreText(), s.paused))
    }

    private fun currentScoreText(): String {
        val state = engine?.state ?: return ""
        return MatchSummary.scoreLine(state)
    }

    private fun applyVoiceCommand(command: VoiceCommand, rawText: String) {
        val s = _uiState.value
        if (s.paused || s.finished) return
        val currentEngine = engine ?: return
        val prev = currentEngine.state

        currentEngine.logNote("Распознано: «$rawText»")
        when (command) {
            is VoiceCommand.Score ->
                when (val result = currentEngine.applyAnnouncement(command.announcement)) {
                    ApplyResult.Applied -> {
                        Log.d(TAG, "счёт применён: «$rawText»")
                        confirmApplied(prev)
                    }
                    is ApplyResult.Rejected -> {
                        Log.d(TAG, "счёт отклонён (${result.reason}): «$rawText»")
                        nack()
                        currentEngine.logNote(
                            when (result.reason) {
                                RejectionReason.BACKWARD -> "→ не применено: меньше текущего счёта"
                                RejectionReason.DUPLICATE -> "→ дубликат, пропущено"
                                RejectionReason.INVALID -> "→ недопустимые значения, пропущено"
                                RejectionReason.SKIP -> "→ не применено: перескок счёта"
                            },
                        )
                        if (result.reason == RejectionReason.BACKWARD ||
                            result.reason == RejectionReason.SKIP
                        ) {
                            _uiState.update {
                                it.copy(warning = "Противоречие: «$rawText» — счёт не изменён")
                            }
                        }
                    }
                }

            VoiceCommand.Undo -> if (currentEngine.undo()) beep() else nack()

            VoiceCommand.ScoreQuery -> {
                // Проговариваем записанный счёт гейма; сама речь — и есть подтверждение.
                val text = ScoreSpeech.gameScore(currentEngine.state, s::name)
                Log.d(TAG, "счёт озвучен: $text")
                currentEngine.logNote("→ озвучено: $text")
                speak(text)
            }

            VoiceCommand.GameWon -> {
                val winner = currentEngine.state.currentSet.currentGame.announcedWinner
                when {
                    winner != null -> {
                        currentEngine.winGame(winner)
                        confirmApplied(prev)
                    }
                    !s.strictValidation -> {
                        // Валидация отключена, но победитель гейма не определяется —
                        // не засчитываем, а спрашиваем счёт голосом.
                        Log.d(TAG, "«гейм»: победитель не определён, запрошен счёт")
                        currentEngine.logNote("→ «гейм»: непонятно, в чью пользу — запрошен счёт")
                        speak("Счёт?")
                    }
                    else -> {
                        // При таком счёте гейм не мог закончиться — ошибочная команда.
                        Log.d(TAG, "«гейм» отклонён: гейм не мог завершиться при текущем счёте")
                        currentEngine.logNote("→ «гейм» не мог завершиться при текущем счёте — ошибочная команда")
                        nack()
                    }
                }
            }
        }
        sync()
    }

    /**
     * Подтверждение применённой команды. Если команда завершила гейм/сет —
     * beep не звучит: вместо него из sync() зазвонит звонок гейма/сета.
     */
    private fun confirmApplied(prev: MatchState) {
        val new = engine?.state
        if (new == null || new.totalGames > prev.totalGames) return
        beep()
    }

    /** Регулировка громкости сигналов относительно медиа-громкости (музыка в наушниках). */
    fun setSignalVolume(volume: Float) {
        val clamped = volume.coerceIn(1.5f, 2.5f)
        _uiState.update { it.copy(signalVolume = clamped) }
        prefs.edit { putFloat(KEY_SIGNAL_VOLUME, clamped) }
    }

    /** Пробный beep при отпускании слайдера громкости. */
    fun previewSignal() = beep()

    // --- Настройки озвучки ---

    fun setSpeakGameEnd(enabled: Boolean) {
        _uiState.update { it.copy(speakGameEnd = enabled) }
        prefs.edit { putBoolean(KEY_SPEAK_GAME_END, enabled) }
    }

    fun setSpeakSetPoint(enabled: Boolean) {
        _uiState.update { it.copy(speakSetPoint = enabled) }
        prefs.edit { putBoolean(KEY_SPEAK_SET_POINT, enabled) }
    }

    fun setSpeakSetEnd(enabled: Boolean) {
        _uiState.update { it.copy(speakSetEnd = enabled) }
        prefs.edit { putBoolean(KEY_SPEAK_SET_END, enabled) }
    }

    /** Строгая проверка объявлений; действует и на уже идущий матч. */
    fun setStrictValidation(enabled: Boolean) {
        _uiState.update { it.copy(strictValidation = enabled) }
        prefs.edit { putBoolean(KEY_STRICT_VALIDATION, enabled) }
        engine?.strictValidation = enabled
    }

    /**
     * Озвучка через on-device TTS. [enqueue] = true — фраза встаёт в очередь
     * следом за уже звучащей (например, «сет-поинт» после счёта гейма).
     * На время речи музыка приглушается (фокус снимается в колбэке utterance).
     */
    private fun speak(text: String, enqueue: Boolean = false) {
        val t = tts ?: return
        val mode = if (enqueue) TextToSpeech.QUEUE_ADD else TextToSpeech.QUEUE_FLUSH
        requestDuck()
        val result = runCatching { t.speak(text, mode, null, "score-${utteranceSeq++}") }
            .onFailure { Log.w(TAG, "tts: ошибка озвучки", it) }
        if (result.getOrNull() != TextToSpeech.SUCCESS) releaseDuck()
    }

    private var utteranceSeq = 0

    private fun requestDuck() {
        duckCount++
        if (duckCount == 1) {
            Log.d(TAG, "duck: запрос фокуса (приглушение музыки)")
            audioManager.requestAudioFocus(duckFocusRequest)
        }
        restartDuckWatchdog()
    }

    private fun releaseDuck() {
        if (duckCount == 0) return
        if (--duckCount == 0) {
            Log.d(TAG, "duck: фокус возвращён музыке")
            cancelDuckWatchdog()
            audioManager.abandonAudioFocusRequest(duckFocusRequest)
        } else {
            restartDuckWatchdog()
        }
    }

    private fun onSpeechFinished() {
        activeSpeechCount = (activeSpeechCount - 1).coerceAtLeast(0)
        releaseDuck()
    }

    private fun forceReleaseDuck() {
        if (duckCount == 0 && activeSpeechCount == 0) return
        Log.w(
            TAG,
            "duck: принудительный сброс фокуса (duckCount=$duckCount, activeSpeech=$activeSpeechCount)",
        )
        duckCount = 0
        activeSpeechCount = 0
        cancelDuckWatchdog()
        audioManager.abandonAudioFocusRequest(duckFocusRequest)
    }

    private fun restartDuckWatchdog() {
        cancelDuckWatchdog()
        duckWatchdogJob = viewModelScope.launch {
            delay(DUCK_WATCHDOG_MS)
            if (duckCount > 0 && activeSpeechCount == 0) {
                forceReleaseDuck()
            }
        }
    }

    private fun cancelDuckWatchdog() {
        duckWatchdogJob?.cancel()
        duckWatchdogJob = null
    }

    /** Короткий beep — подтверждение, что объявление услышано, не глядя на экран. */
    private fun beep() {
        Log.d(TAG, "beep: volume=${_uiState.value.signalVolume}")
        SignalPlayer.accept(_uiState.value.signalVolume)
    }

    /** Низкий двойной сигнал — команда распознана, но отклонена. */
    private fun nack() {
        Log.d(TAG, "nack: volume=${_uiState.value.signalVolume}")
        SignalPlayer.reject(_uiState.value.signalVolume)
    }

    /** Звонок при выигранном гейме; тройной — если этим геймом завершён сет. */
    private fun ring(times: Int) {
        viewModelScope.launch {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            Log.d(TAG, "ring: times=$times, uri=$uri")
            if (uri == null) {
                // Нет системного рингтона уведомлений — серия beep'ов вместо звонка.
                repeat(times * 2) { beep(); delay(300) }
                return@launch
            }
            // Музыка приглушается на всю серию звонков, а не дёргается на каждый.
            requestDuck()
            try {
                repeat(times) { i ->
                    playRingtone(uri)
                    if (i < times - 1) delay(400)
                }
            } finally {
                releaseDuck()
            }
        }
    }

    /**
     * Звонок через MediaPlayer (у Ringtone нет регулировки громкости).
     * Медиа-канал + относительная громкость сигналов; возвращается по окончании.
     */
    private suspend fun playRingtone(uri: Uri) {
        suspendCancellableCoroutine<Unit> { cont ->
            runCatching {
                val volume = _uiState.value.signalVolume
                val player = MediaPlayer().apply {
                    setDataSource(getApplication(), uri)
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .build(),
                    )
                    setVolume(volume, volume)
                    setOnPreparedListener { it.start() }
                    setOnCompletionListener {
                        it.release()
                        if (cont.isActive) cont.resume(Unit)
                    }
                    setOnErrorListener { mp, _, _ ->
                        mp.release()
                        if (cont.isActive) cont.resume(Unit)
                        true
                    }
                }
                cont.invokeOnCancellation { runCatching { player.release() } }
                player.prepareAsync()
            }.onFailure {
                Log.w(TAG, "ring: ошибка воспроизведения", it)
                if (cont.isActive) cont.resume(Unit)
            }
        }
    }

    override fun onCleared() {
        stopListening()
        cancelDuckWatchdog()
        audioManager.abandonAudioFocusRequest(duckFocusRequest)
        controller.listener = null
        controller.onPauseToggleRequested = null
        controller.onStopRequested = null
        tts?.shutdown()
        tts = null
    }

    private fun sync(screen: Screen? = null) {
        val e = engine ?: return
        signalGameTransitions(e.state)
        _uiState.update {
            it.copy(
                matchState = e.state,
                canUndo = e.canUndo,
                log = e.log,
                screen = screen ?: it.screen,
            )
        }
        persistCurrentMatch()
        updateNotification()
    }

    // --- Персистентность незавершённого матча ---

    /**
     * Слепок текущего матча в SharedPreferences — счёт переживает закрытие
     * приложения и сбрасывается только по «Завершить матч» / «Новый матч».
     */
    private fun persistCurrentMatch() {
        val e = engine ?: return
        prefs.edit {
            putString(KEY_MATCH_STATE, MatchStateCodec.encode(e.state))
            putString(KEY_MATCH_LOG, e.log.joinToString("\n"))
        }
    }

    private fun clearPersistedMatch() {
        prefs.edit {
            remove(KEY_MATCH_STATE)
            remove(KEY_MATCH_LOG)
        }
    }

    /** Восстановление незавершённого матча при запуске приложения. */
    private fun restoreSavedMatch() {
        val saved = prefs.getString(KEY_MATCH_STATE, null) ?: return
        val state = MatchStateCodec.decode(saved) ?: return
        val log = prefs.getString(KEY_MATCH_LOG, "").orEmpty()
            .split("\n").filter { it.isNotEmpty() }
        engine = MatchEngine(state.firstServer).apply {
            strictValidation = _uiState.value.strictValidation
            restore(state, log)
        }
        // Сигналы о переходах сравнивают с lastSyncedState: восстановленное
        // состояние не должно вызывать звонок гейма/сета при первом sync().
        lastSyncedState = state
        _uiState.update {
            it.copy(screen = Screen.SCOREBOARD, matchState = state, log = log)
        }
    }

    /**
     * Звонок и озвучка при засчитанном гейме: тройной звонок и итог по геймам,
     * если этим геймом завершился сет; «сет-поинт», когда игрок может
     * выиграть сет в только что начавшемся гейме.
     */
    private fun signalGameTransitions(new: MatchState) {
        val prev = lastSyncedState
        lastSyncedState = new
        if (prev == null || prev == new) return
        val s = _uiState.value
        var speaking = false
        when {
            new.completedSets.size > prev.completedSets.size -> {
                ring(times = 3)
                if (s.speakSetEnd) {
                    speak(ScoreSpeech.setEnd(new.completedSets.last(), s::name))
                    speaking = true
                }
            }
            new.totalGames > prev.totalGames -> {
                ring(times = 1)
                if (s.speakGameEnd) {
                    val winner =
                        if (new.currentSet.gamesP1 > prev.currentSet.gamesP1) Player.ONE else Player.TWO
                    speak(ScoreSpeech.gameEnd(new, winner, s::name))
                    speaking = true
                }
            }
        }
        // Сет-поинт объявляем при смене ситуации (игрок или его счёт), чтобы
        // не повторяться на каждом очке и объявить заново после 5-5 → 6-5.
        if (s.speakSetPoint) {
            val now = ScoreSpeech.setPointPlayer(new)?.let { it to new.currentSet.games(it) }
            val before = ScoreSpeech.setPointPlayer(prev)?.let { it to prev.currentSet.games(it) }
            if (now != null && now != before) {
                speak(ScoreSpeech.setPoint(now.first, s::name), enqueue = speaking)
            }
        }
    }

    private companion object {
        const val TAG = "MatchViewModel"
        const val PREFS_NAME = "settings"
        const val KEY_SIGNAL_VOLUME = "signal_volume"
        const val KEY_SPEAK_GAME_END = "speak_game_end"
        const val KEY_SPEAK_SET_POINT = "speak_set_point"
        const val KEY_SPEAK_SET_END = "speak_set_end"
        const val KEY_STRICT_VALIDATION = "strict_validation"
        const val KEY_PLAYER1_NAME = "player1_name"
        const val KEY_PLAYER2_NAME = "player2_name"
        const val KEY_FIRST_SERVER = "first_server"
        const val KEY_MATCH_STATE = "current_match_state"
        const val KEY_MATCH_LOG = "current_match_log"

        /** Таймаут watchdog: если фокус удерживается без активной TTS-озвучки, сбрасываем. */
        const val DUCK_WATCHDOG_MS = 10_000L
    }
}
