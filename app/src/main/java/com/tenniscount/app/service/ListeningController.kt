package com.tenniscount.app.service

import android.content.Context
import com.tenniscount.app.speech.ModelManager
import com.tenniscount.app.speech.VoskRecognizer
import com.tenniscount.app.util.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.atomic.AtomicInteger

enum class MicState { OFF, INSTALLING, PREPARING, LISTENING, ERROR }

/** Состояние прослушивания, общее для UI и foreground-сервиса. */
data class ListeningState(
    val micState: MicState = MicState.OFF,
    val error: String? = null,
    val installProgress: Int? = null,
    val lastHeard: String = "",
    /** Текущий счёт матча для уведомления. */
    val scoreText: String = "",
    /** Состояние паузы матча для уведомления. */
    val paused: Boolean = false,
)

/**
 * Владеет распознавателем на уровне приложения (синглтон): модель Vosk
 * загружается в память один раз и переживает пересоздание экрана.
 * ViewModel подписывается через [listener] и [state]; foreground service
 * через колбэки [onPauseToggleRequested]/[onStopRequested] пробрасывает
 * действия из уведомления.
 */
class ListeningController private constructor(context: Context) {

    private val modelManager = ModelManager(context)

    private val _state = MutableStateFlow(ListeningState())
    val state: StateFlow<ListeningState> = _state.asStateFlow()

    /** Получатель распознанных фраз (назначает ViewModel, пока жив экран матча). */
    var listener: VoskRecognizer.Listener? = null

    /** Действия из уведомления foreground-сервиса. */
    var onPauseToggleRequested: (() -> Unit)? = null
    var onStopRequested: (() -> Unit)? = null

    private val forwardingListener = object : VoskRecognizer.Listener {
        override fun onPartialResult(text: String) {
            _state.update { it.copy(lastHeard = text) }
            listener?.onPartialResult(text)
        }

        override fun onFinalResult(text: String) {
            AppLog.d(TAG, "распознано: «$text»")
            _state.update { it.copy(lastHeard = text) }
            listener?.onFinalResult(text)
        }

        override fun onError(message: String) {
            AppLog.e(TAG, "ошибка распознавания: $message")
            _state.update { it.copy(micState = MicState.ERROR, error = message) }
            listener?.onError(message)
        }
    }

    private val recognizer = VoskRecognizer(modelManager.modelDir, forwardingListener)

    /** Защита от параллельного запуска (двойное быстрое нажатие «Слушать»). */
    private val startMutex = Mutex()

    /**
     * Поколение запуска: [stop] инкрементирует его, чем отменяет ещё
     * выполняющийся [start] — тот после каждого долгого шага проверяет,
     * что его поколение актуально, иначе завершается, не запуская микрофон.
     * AtomicInteger: инкремент не атомарен у обычного Int, а stop() может
     * прийти не из того потока, где выполняется start().
     */
    private val startGeneration = AtomicInteger(0)

    /**
     * Полный запуск: при необходимости устанавливает bundled-модель из assets,
     * загружает её и начинает прослушивание. Вызывать после проверки разрешения
     * RECORD_AUDIO и запуска foreground service (иначе Android 12+ не даст
     * доступ к микрофону из фона). Параллельный повторный вызов, пока идёт
     * запуск, — no-op, возвращает true (запуском владеет первый вызов).
     * Если во время подготовки пришёл [stop], возвращает false и не
     * запускает распознавание.
     */
    suspend fun start(): Boolean {
        if (!startMutex.tryLock()) {
            AppLog.i(TAG, "start: запуск уже идёт — пропускаем")
            return true
        }
        val generation = startGeneration.incrementAndGet()
        try {
            AppLog.i(TAG, "start: запуск прослушивания")
            _state.update { it.copy(error = null) }
            return try {
                if (!modelManager.isModelReady()) {
                    _state.update { it.copy(micState = MicState.INSTALLING) }
                    modelManager.ensureModel { progress ->
                        _state.update { it.copy(installProgress = progress) }
                    }
                    _state.update { it.copy(installProgress = null) }
                }
                if (isCancelled(generation, "после установки модели")) return false
                _state.update { it.copy(micState = MicState.PREPARING) }
                recognizer.prepare()
                if (isCancelled(generation, "после подготовки")) return false
                if (recognizer.start()) {
                    AppLog.i(TAG, "start: микрофон слушает")
                    _state.update { it.copy(micState = MicState.LISTENING) }
                    true
                } else {
                    AppLog.w(TAG, "start: не удалось запустить микрофон")
                    _state.update { it.copy(micState = MicState.ERROR) }
                    false
                }
            } catch (e: Exception) {
                AppLog.e(TAG, "start: ошибка подготовки", e)
                _state.update {
                    it.copy(
                        micState = MicState.ERROR,
                        installProgress = null,
                        error = e.message ?: "Ошибка подготовки распознавания",
                    )
                }
                false
            }
        } finally {
            startMutex.unlock()
        }
    }

    /** true, если за время запуска пришёл [stop] — запуск отменён. */
    private fun isCancelled(generation: Int, stage: String): Boolean {
        if (generation == startGeneration.get()) return false
        AppLog.i(TAG, "start: отменён остановкой $stage")
        return true
    }

    /** Пауза прослушивания без остановки сервиса (экономия батареи). */
    fun setPaused(paused: Boolean) {
        AppLog.i(TAG, "setPaused: $paused")
        recognizer.setPaused(paused)
    }

    /** Обновляет текст счёта в общем состоянии (уведомление подхватит сервис). */
    fun setScoreText(text: String) {
        _state.update { it.copy(scoreText = text) }
    }

    /** Обновляет флаг паузы матча в общем состоянии (уведомление подхватит сервис). */
    fun setPausedMatch(paused: Boolean) {
        _state.update { it.copy(paused = paused) }
    }

    fun stop() {
        AppLog.i(TAG, "stop: прослушивание остановлено")
        // Инвалидируем незавершённый start(): после ближайшей проверки
        // поколения он завершится, не запуская микрофон.
        startGeneration.incrementAndGet()
        recognizer.stop()
        _state.update { it.copy(micState = MicState.OFF, lastHeard = "") }
    }

    companion object {
        private const val TAG = "ListeningController"

        @Volatile
        private var instance: ListeningController? = null

        fun get(context: Context): ListeningController = instance ?: synchronized(this) {
            instance ?: ListeningController(context.applicationContext).also { instance = it }
        }
    }
}
