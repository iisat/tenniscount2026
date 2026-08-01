package com.tenniscount.app.ui

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Короткие сигналы подтверждения/отказа. Тоны синтезируются (синус, PCM)
 * и играются через AudioTrack на медиа-канале — в отличие от ToneGenerator,
 * громкость не ограничена 100% (усиление до 150% для игры поверх музыки).
 */
object SignalPlayer {

    private const val SAMPLE_RATE = 44100

    /** Базовая амплитуда: при громкости 150% ещё нет клиппинга (0.6 * 1.5 = 0.9). */
    private const val BASE_AMPLITUDE = 0.6

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    /** Команда принята: короткий высокий beep. */
    fun accept(volume: Float) = play(listOf(880 to 150), volume)

    /** Команда отклонена: два низких тона. */
    fun reject(volume: Float) = play(listOf(260 to 120, 0 to 40, 180 to 160), volume)

    /** Сегменты: (частота Гц, длительность мс); частота 0 = пауза. */
    private fun play(segments: List<Pair<Int, Int>>, volume: Float) {
        scope.launch {
            runCatching {
                val samples = synthesize(segments, volume)
                val track = AudioTrack.Builder()
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
                    .setBufferSizeInBytes(samples.size * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
                track.write(samples, 0, samples.size)
                track.play()
                delay(segments.sumOf { it.second } + 100L)
                track.release()
            }
        }
    }

    private fun synthesize(segments: List<Pair<Int, Int>>, volume: Float): ShortArray {
        val totalSamples = segments.sumOf { it.second } * SAMPLE_RATE / 1000
        val out = ShortArray(totalSamples)
        var offset = 0
        for ((frequency, durationMs) in segments) {
            val count = durationMs * SAMPLE_RATE / 1000
            for (i in 0 until count) {
                // Затухание в конце сегмента, чтобы не было щелчка.
                val envelope = if (i > count - 100) (count - i) / 100.0 else 1.0
                val wave = if (frequency > 0) {
                    sin(2.0 * PI * frequency * i / SAMPLE_RATE)
                } else {
                    0.0
                }
                val value = wave * BASE_AMPLITUDE * volume * envelope
                out[offset + i] = (value * Short.MAX_VALUE).toInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
            offset += count
        }
        return out
    }
}
