package com.tenniscount.app.speech

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService

/**
 * Обёртка над Vosk: непрерывное распознавание с ограниченной грамматикой
 * (только числа счёта и теннисные термины — это резко повышает точность
 * и отсекает посторонние разговоры). Аудио не покидает устройство.
 *
 * Колбэки [Listener] приходят на главном потоке.
 */
class VoskRecognizer(
    private val modelDir: File,
    private val listener: Listener,
) : RecognitionListener {

    interface Listener {
        /** Промежуточная гипотеза (ещё может измениться) — для индикации «слышу…». */
        fun onPartialResult(text: String)

        /** Финальная распознанная фраза — кандидат на применение счёта. */
        fun onFinalResult(text: String)

        fun onError(message: String)
    }

    private var model: Model? = null
    private var speechService: SpeechService? = null
    private var finalDelivered = false

    /** Загружает модель в память. Блокирующая операция — выполняется в IO-потоке. */
    suspend fun prepare() = withContext(Dispatchers.IO) {
        if (model == null) {
            LibVosk.setLogLevel(LogLevel.WARNINGS)
            model = Model(modelDir.absolutePath)
        }
    }

    /** Начинает прослушивание. Возвращает false при ошибке запуска микрофона. */
    fun start(): Boolean {
        val currentModel = model ?: return false
        return try {
            val recognizer = Recognizer(currentModel, SAMPLE_RATE, GRAMMAR)
            val service = SpeechService(recognizer, SAMPLE_RATE)
            service.startListening(this)
            speechService = service
            true
        } catch (e: Exception) {
            listener.onError(e.message ?: "Не удалось запустить микрофон")
            false
        }
    }

    /** Пауза прослушивания без освобождения ресурсов (экономия батареи). */
    fun setPaused(paused: Boolean) {
        speechService?.setPause(paused)
    }

    fun stop() {
        speechService?.let {
            it.stop()
            it.shutdown()
        }
        speechService = null
    }

    fun release() {
        stop()
        model?.close()
        model = null
    }

    override fun onPartialResult(hypothesis: String?) {
        extractText(hypothesis, "partial")?.let(listener::onPartialResult)
    }

    override fun onResult(hypothesis: String?) {
        deliverFinal(hypothesis)
    }

    override fun onFinalResult(hypothesis: String?) {
        deliverFinal(hypothesis)
    }

    override fun onError(exception: Exception?) {
        listener.onError(exception?.message ?: "Ошибка распознавания")
    }

    override fun onTimeout() = Unit

    /** onResult и onFinalResult могут прийти оба — фразу доставляем один раз. */
    private fun deliverFinal(hypothesis: String?) {
        if (finalDelivered) return
        val text = extractText(hypothesis, "text") ?: return
        finalDelivered = true
        listener.onFinalResult(text)
    }

    private fun extractText(json: String?, key: String): String? {
        if (json == null) return null
        if (key == "partial") finalDelivered = false
        return runCatching { JSONObject(json).optString(key) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
    }

    companion object {
        private const val SAMPLE_RATE = 16000.0f

        /** Грамматика: только слова, которые несут счёт или команду. */
        private val GRAMMAR = listOf(
            "ноль", "нуль", "пятнадцать", "тридцать", "сорок",
            "ровно", "больше", "меньше", "гейм", "сет", "сколько", "отмена", "отмени",
            "[unk]",
        ).joinToString(prefix = "[", postfix = "]") { "\"$it\"" }
    }
}
