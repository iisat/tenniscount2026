package com.tenniscount.app.ui

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.tanh
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Короткие сигналы подтверждения/отказа. Тоны синтезируются (синус, PCM)
 * и играются через AudioTrack на медиа-канале — в отличие от ToneGenerator,
 * громкость не ограничена 100%. Регулировка 100–200%: максимум выведен
 * в полную шкалу без насыщения, минимум — на 6 дБ ниже, поэтому весь ход
 * слайдера даёт реальную разницу громкости, а не искажения сигнала.
 *
 * Пока включён [setKeepAlive], тракт держится «тёплым» непрерывной тишиной:
 * иначе на холодном старте (сон DSP, приостановленный A2DP) открытие пути
 * занимает десятки-сотни миллисекунд и съедает короткий сигнал целиком.
 */
object SignalPlayer {

    private const val SAMPLE_RATE = 44100

    /** Базовая амплитуда: при 200% ровно 0.9 (порог softClip) — громче без искажений уже нельзя. */
    private const val BASE_AMPLITUDE = 0.45

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private var keepAliveJob: Job? = null

    /**
     * Держит аудиотракт открытым потоком тишины, чтобы последующие сигналы
     * звучали с первого семпла. Включать на время прослушивания микрофона.
     */
    @Synchronized
    fun setKeepAlive(enabled: Boolean) {
        if (enabled == (keepAliveJob != null)) return
        keepAliveJob?.cancel()
        keepAliveJob = if (enabled) scope.launch { runKeepAlive() } else null
    }

    private suspend fun runKeepAlive() {
        runCatching {
            // 100 мс тишины; блокирующий write сам задаёт темп цикла.
            val chunk = ShortArray(SAMPLE_RATE / 10)
            val track = buildTrack(
                bufferBytes = maxOf(
                    chunk.size * 2,
                    AudioTrack.getMinBufferSize(
                        SAMPLE_RATE,
                        AudioFormat.CHANNEL_OUT_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                    ),
                ),
                transferMode = AudioTrack.MODE_STREAM,
            )
            try {
                track.play()
                while (currentCoroutineContext().isActive) {
                    if (track.write(chunk, 0, chunk.size) <= 0) break
                }
            } finally {
                runCatching { track.stop() }
                track.release()
            }
        }
    }

    /** Команда принята: короткий высокий beep. */
    fun accept(volume: Float) = play(listOf(880 to 150), volume)

    /** Команда отклонена: два низких тона. */
    fun reject(volume: Float) = play(listOf(260 to 120, 0 to 40, 180 to 160), volume)

    /** Сериализует воспроизведение, чтобы параллельные сигналы не накладывались друг на друга. */
    private val playMutex = Mutex()

    /** Сегменты: (частота Гц, длительность мс); частота 0 = пауза. */
    private fun play(segments: List<Pair<Int, Int>>, volume: Float) {
        scope.launch {
            playMutex.withLock {
                runCatching {
                    // Тишина в начале: на случай холодного тракта (keep-alive выключен)
                    // микшер срезает первые миллисекунды, пока открывает путь вывода.
                    val leadMs = 60
                    val samples = synthesize(listOf(0 to leadMs) + segments, volume)
                    val minBuffer = AudioTrack.getMinBufferSize(
                        SAMPLE_RATE,
                        AudioFormat.CHANNEL_OUT_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                    )
                    val track = buildTrack(maxOf(samples.size * 2, minBuffer), AudioTrack.MODE_STATIC)
                    try {
                        track.write(samples, 0, samples.size)
                        track.play()
                        delay(segments.sumOf { it.second } + leadMs + 100L)
                    } finally {
                        runCatching { track.stop() }
                        track.release()
                    }
                }
            }
        }
    }

    private fun buildTrack(bufferBytes: Int, transferMode: Int): AudioTrack =
        AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(bufferBytes)
            .setTransferMode(transferMode)
            .build()

    private fun synthesize(segments: List<Pair<Int, Int>>, volume: Float): ShortArray {
        val totalSamples = segments.sumOf { it.second } * SAMPLE_RATE / 1000
        val out = ShortArray(totalSamples)
        var offset = 0
        for ((frequency, durationMs) in segments) {
            val count = durationMs * SAMPLE_RATE / 1000
            for (i in 0 until count) {
                // Плавные края сегмента, чтобы не было щелчков.
                val envelope = minOf(i, count - i).coerceAtMost(100) / 100.0
                val wave = if (frequency > 0) {
                    sin(2.0 * PI * frequency * i / SAMPLE_RATE)
                } else {
                    0.0
                }
                val value = softClip(wave * BASE_AMPLITUDE * volume) * envelope
                out[offset + i] = (value * Short.MAX_VALUE).toInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
            offset += count
        }
        return out
    }

    /** Мягкий лимитер: до 0.9 без изменений, выше — плавное насыщение вместо цифрового клиппинга. */
    private fun softClip(x: Double): Double =
        if (abs(x) <= 0.9) x else sign(x) * (0.9 + 0.1 * tanh((abs(x) - 0.9) / 0.1))
}
