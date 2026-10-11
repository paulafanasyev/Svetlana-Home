package com.svetlana.home.voice

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.os.Build
import android.os.ParcelFileDescriptor
import android.speech.RecognizerIntent
import androidx.annotation.RequiresApi
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

/** Источник аудио команды для проверки голоса владельца (без привязки к API-уровню). */
interface OwnerAudioSource {
    /** Остановить запись (распознаватель получит конец аудио). Буфер сохраняется. */
    fun stop()
    /** Забрать записанную речь (и стереть буфер). */
    fun takeAudio(): FloatArray?
    /** Остановить и стереть без использования. */
    fun discard()
}

/**
 * Android 13+: микрофон открывает Светлана и отдаёт тот же звук распознавателю через pipe
 * (RecognizerIntent.EXTRA_AUDIO_SOURCE). Так проверяется ИМЕННО та фраза, которая станет командой:
 * подменить «чужую команду + свой голос» нельзя. Буфер ограничен 15 с, только в памяти.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class OwnerAudioTap private constructor(
    private val record: AudioRecord,
    private val readSide: ParcelFileDescriptor,
    private val writeSide: ParcelFileDescriptor,
) : OwnerAudioSource {

    private val lock = Any()
    private val buffer = ShortArray(MAX_SAMPLES)
    private var count = 0

    @Volatile
    private var running = true
    private val stopped = AtomicBoolean(false)
    private val worker = Thread({ pump() }, "svetlana-owner-tap").apply { isDaemon = true }

    private fun pump() {
        val chunk = ShortArray(CHUNK)
        val bytes = ByteArray(CHUNK * 2)
        try {
            ParcelFileDescriptor.AutoCloseOutputStream(writeSide).use { out ->
                record.startRecording()
                while (running) {
                    val n = record.read(chunk, 0, CHUNK)
                    if (n <= 0) break
                    synchronized(lock) {
                        val take = min(n, MAX_SAMPLES - count)
                        if (take > 0) {
                            System.arraycopy(chunk, 0, buffer, count, take)
                            count += take
                        }
                    }
                    for (i in 0 until n) {
                        val s = chunk[i].toInt()
                        bytes[2 * i] = (s and 0xFF).toByte()
                        bytes[2 * i + 1] = ((s shr 8) and 0xFF).toByte()
                    }
                    out.write(bytes, 0, n * 2)
                }
            }
        } catch (t: Throwable) {
            // распознаватель закрыл pipe или микрофон освободили
        } finally {
            runCatching { record.stop() }
            runCatching { record.release() }
            java.util.Arrays.fill(chunk, 0.toShort())
        }
    }

    override fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        running = false
        runCatching { record.stop() }
        runCatching { worker.join(200) }
        runCatching { readSide.close() }
        // Если поток завис на записи в pipe, микрофон всё равно освобождаем сразу.
        if (worker.isAlive) runCatching { record.release() }
    }

    override fun takeAudio(): FloatArray? {
        stop()
        synchronized(lock) {
            val out = if (count < PcmCapture.SAMPLE_RATE / 2) null else VoicePrint.pcm16ToFloat(buffer, count)
            java.util.Arrays.fill(buffer, 0, count, 0.toShort())
            count = 0
            return out
        }
    }

    override fun discard() {
        stop()
        synchronized(lock) {
            java.util.Arrays.fill(buffer, 0, count, 0.toShort())
            count = 0
        }
    }

    companion object {
        const val MAX_SAMPLES = PcmCapture.SAMPLE_RATE * 15
        private const val CHUNK = 1024

        /** Подключить наш микрофон к intent распознавателя. null: не получилось, распознаватель слушает сам. */
        fun attach(context: Context, intent: Intent): OwnerAudioTap? {
            if (!PcmCapture.hasPermission(context)) return null
            val rec = PcmCapture.newRecord() ?: return null
            return try {
                val pipe = ParcelFileDescriptor.createPipe()
                intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0])
                intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, PcmCapture.SAMPLE_RATE)
                OwnerAudioTap(rec, pipe[0], pipe[1]).also { it.worker.start() }
            } catch (t: Throwable) {
                runCatching { rec.release() }
                null
            }
        }
    }
}
