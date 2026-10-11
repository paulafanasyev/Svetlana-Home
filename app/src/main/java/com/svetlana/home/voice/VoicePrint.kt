package com.svetlana.home.voice

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Отпечаток голоса владельца. Один-в-один с десктопным src/services/voice/VoicePrint.ts
 * (те же константы, те же формулы), чтобы телефон и ПК принимали решение одинаково.
 *
 * MFCC: 26 мел-фильтров, c1..c12, среднее + разброс = вектор из 24 чисел.
 * Всё считается на устройстве, запись голоса не сохраняется и никуда не уходит: хранится
 * только шаблон из чисел (в EncryptedSharedPreferences).
 *
 * Честно: это отсекает чужие голоса, но не заменяет подтверждение опасных действий на экране
 * (запись голоса можно воспроизвести). Поэтому ActionRiskPolicy остаётся в силе.
 */
object VoicePrint {
    const val N_MEL = 26
    const val N_CEP = 13
    const val MIN_VOICED_FRAMES = 40
    const val MIN_ENROLL_SAMPLES = 3
    const val DIM = 2 * (N_CEP - 1)
    const val MAX_THRESHOLD = 2.5
    const val MIN_THRESHOLD = 1.2
    private const val MAX_SERIALIZED = 20_000

    const val MSG_SILENT = "Не расслышала голос (слишком тихо или коротко). Команда не выполнена."
    const val MSG_FOREIGN = "Голос не узнан: команды принимаются только от владельца. Команда не выполнена."

    class Print(val v: DoubleArray, val frames: Int)

    class Template(
        val mean: DoubleArray,
        val sd: DoubleArray,
        val distThreshold: Double,
        val samples: Int,
        val createdAt: Long,
    )

    class Verdict(val ok: Boolean, val score: Double, val reason: String?)

    /** PCM16 → -1..1. */
    fun pcm16ToFloat(buf: ShortArray, count: Int = buf.size): FloatArray {
        val n = count.coerceIn(0, buf.size)
        return FloatArray(n) { buf[it] / 32768f }
    }

    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wr = cos(ang)
            val wi = sin(ang)
            val half = len / 2
            var i = 0
            while (i < n) {
                var cr = 1.0
                var ci = 0.0
                for (k in 0 until half) {
                    val a = i + k
                    val b = a + half
                    val tr = re[b] * cr - im[b] * ci
                    val ti = re[b] * ci + im[b] * cr
                    re[b] = re[a] - tr
                    im[b] = im[a] - ti
                    re[a] += tr
                    im[a] += ti
                    val ncr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
    }

    private fun hz2mel(f: Double) = 2595.0 * log10(1.0 + f / 700.0)
    private fun mel2hz(m: Double) = 700.0 * (10.0.pow(m / 2595.0) - 1.0)

    /** Признаки голоса из PCM (моно, -1..1). null: слишком мало речи или тишина. */
    fun compute(pcm: FloatArray, sampleRate: Int): Print? {
        if (sampleRate < 8000 || pcm.size < sampleRate * 0.5) return null
        val frameLen = (0.025 * sampleRate).roundToInt()
        val hop = (0.010 * sampleRate).roundToInt()
        var nfft = 1
        while (nfft < frameLen) nfft = nfft shl 1
        val frames = (pcm.size - frameLen) / hop + 1
        if (frames < MIN_VOICED_FRAMES) return null

        val rms = DoubleArray(frames)
        var maxRms = 0.0
        for (f in 0 until frames) {
            var s = 0.0
            val off = f * hop
            for (i in 0 until frameLen) {
                val x = pcm[off + i].toDouble()
                s += x * x
            }
            rms[f] = sqrt(s / frameLen)
            if (rms[f] > maxRms) maxRms = rms[f]
        }
        if (maxRms < 0.003) return null
        val gate = max(maxRms * 0.1, 0.002)

        val fMax = min(7600.0, sampleRate / 2.0 - 100.0)
        val melLo = hz2mel(80.0)
        val melHi = hz2mel(fMax)
        val bins = IntArray(N_MEL + 2) { i ->
            val f = mel2hz(melLo + i * (melHi - melLo) / (N_MEL + 1))
            kotlin.math.floor((nfft + 1) * f / sampleRate).toInt()
        }
        val window = DoubleArray(frameLen) { i -> 0.54 - 0.46 * cos(2.0 * PI * i / (frameLen - 1)) }

        val dimC = N_CEP - 1
        val ceps = ArrayList<DoubleArray>()
        val re = DoubleArray(nfft)
        val im = DoubleArray(nfft)
        val mel = DoubleArray(N_MEL)
        for (f in 0 until frames) {
            if (rms[f] < gate) continue
            re.fill(0.0); im.fill(0.0)
            val off = f * hop
            for (i in 0 until frameLen) {
                val prev = if (i > 0) pcm[off + i - 1].toDouble() else if (off > 0) pcm[off - 1].toDouble() else 0.0
                val x = pcm[off + i].toDouble() - 0.97 * prev
                re[i] = x * window[i]
            }
            fft(re, im)
            for (m in 1..N_MEL) {
                val a = bins[m - 1]
                val b = bins[m]
                val c = bins[m + 1]
                var e = 0.0
                for (k in a until c) {
                    val w = if (k < b) (k - a).toDouble() / max(1, b - a) else (c - k).toDouble() / max(1, c - b)
                    e += w * (re[k] * re[k] + im[k] * im[k])
                }
                mel[m - 1] = ln(e + 1e-10)
            }
            val c = DoubleArray(dimC)
            for (k in 1 until N_CEP) {
                var s = 0.0
                for (m in 0 until N_MEL) s += mel[m] * cos(PI * k * (m + 0.5) / N_MEL)
                c[k - 1] = s * sqrt(2.0 / N_MEL)
            }
            ceps.add(c)
        }
        if (ceps.size < MIN_VOICED_FRAMES) return null
        val mean = DoubleArray(dimC)
        val sd = DoubleArray(dimC)
        for (c in ceps) for (k in 0 until dimC) mean[k] += c[k] / ceps.size
        for (c in ceps) for (k in 0 until dimC) { val d = c[k] - mean[k]; sd[k] += d * d / ceps.size }
        val v = DoubleArray(DIM)
        for (k in 0 until dimC) { v[k] = mean[k]; v[dimC + k] = sqrt(sd[k]) }
        return Print(v, ceps.size)
    }

    private fun dist(v: DoubleArray, mean: DoubleArray, sd: DoubleArray): Double {
        var s = 0.0
        for (k in mean.indices) { val z = (v[k] - mean[k]) / sd[k]; s += z * z }
        return sqrt(s / mean.size)
    }

    private fun stats(prints: List<Print>): Pair<DoubleArray, DoubleArray> {
        val d = prints[0].v.size
        val mean = DoubleArray(d)
        val sd = DoubleArray(d)
        for (p in prints) for (k in 0 until d) mean[k] += p.v[k] / prints.size
        val div = max(1, prints.size - 1).toDouble()
        for (p in prints) for (k in 0 until d) { val x = p.v[k] - mean[k]; sd[k] += x * x / div }
        // Нижняя граница разброса: голос от записи к записи немного «плавает».
        for (k in 0 until d) sd[k] = max(sqrt(sd[k]), 0.35 + 0.08 * abs(mean[k]))
        return mean to sd
    }

    /** Шаблон из ≥3 записей. Порог по leave-one-out, в пределах 1.2..2.5. */
    fun buildTemplate(prints: List<Print>, now: Long = System.currentTimeMillis()): Template {
        require(prints.size >= MIN_ENROLL_SAMPLES) { "Нужно минимум $MIN_ENROLL_SAMPLES записи голоса" }
        require(prints.all { it.v.size == DIM }) { "Неверная размерность отпечатка" }
        val (mean, sd) = stats(prints)
        var worst = 0.0
        for (i in prints.indices) {
            val (m, s) = stats(prints.filterIndexed { j, _ -> j != i })
            worst = max(worst, dist(prints[i].v, m, s))
        }
        val thr = min(MAX_THRESHOLD, max(MIN_THRESHOLD, worst * 1.3))
        return Template(mean, sd, thr, prints.size, now)
    }

    fun verify(p: Print?, t: Template): Verdict {
        if (p == null) return Verdict(false, Double.POSITIVE_INFINITY, MSG_SILENT)
        val score = dist(p.v, t.mean, t.sd)
        return if (score <= t.distThreshold) Verdict(true, score, null)
        else Verdict(false, score, MSG_FOREIGN)
    }

    // ---------- сериализация шаблона (без org.json, чтобы тестировалось на JVM) ----------

    fun encode(t: Template): String =
        listOf(
            "v1",
            t.distThreshold.toString(),
            t.samples.toString(),
            t.createdAt.toString(),
            t.mean.joinToString(","),
            t.sd.joinToString(","),
        ).joinToString("|")

    /** Строгий разбор: любая порча = null (голос считается не записанным). */
    fun decode(raw: String?): Template? {
        if (raw == null || raw.isEmpty() || raw.length > MAX_SERIALIZED) return null
        val parts = raw.split('|')
        if (parts.size != 6 || parts[0] != "v1") return null
        val thr = parts[1].toDoubleOrNull() ?: return null
        if (!(thr > 0.0 && thr <= MAX_THRESHOLD)) return null
        val samples = parts[2].toIntOrNull() ?: return null
        if (samples < MIN_ENROLL_SAMPLES || samples > 100) return null
        val created = parts[3].toLongOrNull() ?: return null
        val mean = parseVec(parts[4]) ?: return null
        val sd = parseVec(parts[5]) ?: return null
        if (!sd.all { it > 0.0 }) return null
        return Template(mean, sd, thr, samples, created)
    }

    private fun parseVec(s: String): DoubleArray? {
        val items = s.split(',')
        if (items.size != DIM) return null
        val out = DoubleArray(DIM)
        for (i in items.indices) {
            val x = items[i].toDoubleOrNull() ?: return null
            if (x.isNaN() || x.isInfinite()) return null
            out[i] = x
        }
        return out
    }
}
