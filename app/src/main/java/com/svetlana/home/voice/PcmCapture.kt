package com.svetlana.home.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

/**
 * Короткая запись с микрофона (16 кГц, моно) только для проверки/записи голоса владельца.
 * Аудио живёт только в памяти и никуда не сохраняется. Без разрешения RECORD_AUDIO ничего не делает.
 */
object PcmCapture {
    const val SAMPLE_RATE = 16_000

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    internal fun newRecord(): AudioRecord? {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return null
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(minBuf * 2, 8192)
            )
        } catch (t: Throwable) {
            return null
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return null
        }
        return rec
    }

    /** Записать [durationMs] (0,5..10 с). null: нет разрешения, микрофон занят или запись слишком короткая. */
    suspend fun record(context: Context, durationMs: Int): FloatArray? = withContext(Dispatchers.IO) {
        if (!hasPermission(context)) return@withContext null
        val rec = newRecord() ?: return@withContext null
        val total = SAMPLE_RATE / 1000 * durationMs.coerceIn(500, 10_000)
        val out = ShortArray(total)
        var n = 0
        try {
            rec.startRecording()
            while (n < total && isActive) {
                val r = rec.read(out, n, min(1024, total - n))
                if (r <= 0) break
                n += r
            }
        } catch (t: Throwable) {
            n = 0
        } finally {
            runCatching { rec.stop() }
            runCatching { rec.release() }
        }
        if (n < SAMPLE_RATE / 2) null else VoicePrint.pcm16ToFloat(out, n).also { java.util.Arrays.fill(out, 0.toShort()) }
    }
}
