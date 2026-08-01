package com.tenniscount.app.service

import android.content.Context
import com.tenniscount.app.speech.ModelManager
import com.tenniscount.app.speech.VoskRecognizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class MicState { OFF, DOWNLOADING, PREPARING, LISTENING, ERROR }

/** Состояние прослушивания, общее для UI и foreground-сервиса. */
data class ListeningState(
    val micState: MicState = MicState.OFF,
    val error: String? = null,
    val downloadProgress: Int? = null,
    val lastHeard: String = "",
)

/**
 * Владеет распознавателем на уровне приложения (синглтон): модель Vosk
 * загружается в память один раз и переживает пересоздание экрана.
 * ViewModel подписывается через [listener] и [state]; foreground service
 * через колбэки [onPauseToggleRequested]/[onStopRequested] пробрасывает
 * действия из уведомления.
 */
class ListeningController private constructor(context: Context) {

    private val modelManager = ModelManager(context.applicationContext.filesDir)

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
            _state.update { it.copy(lastHeard = text) }
            listener?.onFinalResult(text)
        }

        override fun onError(message: String) {
            _state.update { it.copy(micState = MicState.ERROR, error = message) }
            listener?.onError(message)
        }
    }

    private val recognizer = VoskRecognizer(modelManager.modelDir, forwardingListener)

    /**
     * Полный запуск: при необходимости скачивает модель, загружает её
     * и начинает прослушивание. Вызывать после проверки разрешения
     * RECORD_AUDIO и запуска foreground service (иначе Android 12+ не даст
     * доступ к микрофону из фона).
     */
    suspend fun start(): Boolean {
        _state.update { it.copy(error = null) }
        return try {
            if (!modelManager.isModelReady()) {
                _state.update { it.copy(micState = MicState.DOWNLOADING) }
                modelManager.ensureModel { progress ->
                    _state.update { it.copy(downloadProgress = progress) }
                }
                _state.update { it.copy(downloadProgress = null) }
            }
            _state.update { it.copy(micState = MicState.PREPARING) }
            recognizer.prepare()
            if (recognizer.start()) {
                _state.update { it.copy(micState = MicState.LISTENING) }
                true
            } else {
                _state.update { it.copy(micState = MicState.ERROR) }
                false
            }
        } catch (e: Exception) {
            _state.update {
                it.copy(
                    micState = MicState.ERROR,
                    downloadProgress = null,
                    error = e.message ?: "Ошибка подготовки распознавания",
                )
            }
            false
        }
    }

    /** Пауза прослушивания без остановки сервиса (экономия батареи). */
    fun setPaused(paused: Boolean) = recognizer.setPaused(paused)

    fun stop() {
        recognizer.stop()
        _state.update { it.copy(micState = MicState.OFF, lastHeard = "") }
    }

    companion object {
        @Volatile
        private var instance: ListeningController? = null

        fun get(context: Context): ListeningController = instance ?: synchronized(this) {
            instance ?: ListeningController(context.applicationContext).also { instance = it }
        }
    }
}
