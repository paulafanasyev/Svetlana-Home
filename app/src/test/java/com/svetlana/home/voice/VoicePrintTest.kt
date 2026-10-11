package com.svetlana.home.voice

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin

class VoicePrintTest {

    private val sr = 16_000

    /** Синтетический «голос»: гармоники f0 с заданной огибающей, «слоги» 3 Гц, слабый шум. */
    private fun voice(f0: Double, weights: DoubleArray, seed: Long, secs: Double = 2.0): FloatArray {
        val n = (sr * secs).roundToInt()
        val rnd = java.util.Random(seed)
        return FloatArray(n) { i ->
            val t = i.toDouble() / sr
            var s = 0.0
            for (h in 1..weights.size) {
                val f = f0 * h
                if (f >= sr / 2) break
                s += weights[h - 1] * sin(2 * PI * f * t + h)
            }
            val env = 0.5 + 0.5 * sin(2 * PI * 3 * t)
            (0.2 * env * s / weights.size * 4 + 0.002 * (rnd.nextDouble() * 2 - 1)).toFloat()
        }
    }

    private val ownerTimbre = DoubleArray(30) { 1.0 / (it + 1) }
    private val strangerTimbre = DoubleArray(30) { val h = it + 1.0; exp(-((h - 12) * (h - 12)) / 18) }

    private fun enroll(): VoicePrint.Template {
        val prints = (0 until 4).map { VoicePrint.compute(voice(120 + it * 1.5, ownerTimbre, 10L + it), sr)!! }
        return VoicePrint.buildTemplate(prints, now = 1L)
    }

    @Test fun silenceAndShortClipsGiveNoPrint() {
        assertThat(VoicePrint.compute(FloatArray(sr * 2), sr)).isNull()
        assertThat(VoicePrint.compute(voice(120.0, ownerTimbre, 1L, secs = 0.3), sr)).isNull()
        assertThat(VoicePrint.compute(voice(120.0, ownerTimbre, 1L), 4000)).isNull()
    }

    @Test fun printHas24Dimensions() {
        val p = VoicePrint.compute(voice(120.0, ownerTimbre, 1L), sr)!!
        assertThat(p.v.size).isEqualTo(VoicePrint.DIM)
        assertThat(p.v.all { it.isFinite() }).isTrue()
    }

    @Test fun ownerAcceptedStrangerRejected() {
        val t = enroll()
        assertThat(t.distThreshold).isAtLeast(VoicePrint.MIN_THRESHOLD)
        assertThat(t.distThreshold).isAtMost(VoicePrint.MAX_THRESHOLD)
        assertThat(VoicePrint.verify(VoicePrint.compute(voice(121.0, ownerTimbre, 99L), sr), t).ok).isTrue()
        assertThat(VoicePrint.verify(VoicePrint.compute(voice(126.0, ownerTimbre, 98L), sr), t).ok).isTrue()
        val stranger = VoicePrint.verify(VoicePrint.compute(voice(220.0, strangerTimbre, 97L), sr), t)
        assertThat(stranger.ok).isFalse()
        assertThat(stranger.reason).isEqualTo(VoicePrint.MSG_FOREIGN)
        assertThat(VoicePrint.verify(VoicePrint.compute(voice(130.0, strangerTimbre, 96L), sr), t).ok).isFalse()
    }

    @Test fun missingPrintIsRejected() {
        val v = VoicePrint.verify(null, enroll())
        assertThat(v.ok).isFalse()
        assertThat(v.reason).isEqualTo(VoicePrint.MSG_SILENT)
    }

    @Test(expected = IllegalArgumentException::class)
    fun tooFewSamplesRefused() {
        val p = VoicePrint.compute(voice(120.0, ownerTimbre, 1L), sr)!!
        VoicePrint.buildTemplate(listOf(p, p))
    }

    @Test fun templateRoundTripsAndRejectsTampering() {
        val t = enroll()
        val raw = VoicePrint.encode(t)
        val back = VoicePrint.decode(raw)!!
        assertThat(back.distThreshold).isEqualTo(t.distThreshold)
        assertThat(back.mean.toList()).isEqualTo(t.mean.toList())
        assertThat(back.sd.toList()).isEqualTo(t.sd.toList())
        assertThat(VoicePrint.decode(null)).isNull()
        assertThat(VoicePrint.decode("")).isNull()
        assertThat(VoicePrint.decode(raw.replaceFirst("v1", "v2"))).isNull()
        // порог выше 2.5 нельзя подсунуть (иначе «пускать всех»)
        val parts = raw.split('|').toMutableList()
        parts[1] = "99.0"
        assertThat(VoicePrint.decode(parts.joinToString("|"))).isNull()
        val zeroSd = raw.split('|').toMutableList()
        zeroSd[5] = List(VoicePrint.DIM) { "0.0" }.joinToString(",")
        assertThat(VoicePrint.decode(zeroSd.joinToString("|"))).isNull()
        val nan = raw.split('|').toMutableList()
        nan[4] = List(VoicePrint.DIM) { "NaN" }.joinToString(",")
        assertThat(VoicePrint.decode(nan.joinToString("|"))).isNull()
        assertThat(VoicePrint.decode("x".repeat(30_000))).isNull()
    }

    @Test fun pcm16Conversion() {
        val f = VoicePrint.pcm16ToFloat(shortArrayOf(0, 16384, -32768, 1), 3)
        assertThat(f.size).isEqualTo(3)
        assertThat(f[1]).isEqualTo(0.5f)
        assertThat(f[2]).isEqualTo(-1f)
    }

    @Test fun lockoutAfterFiveFailures() {
        var now = 1_000L
        val l = OwnerVoiceLockout(maxFails = 5, lockMs = 60_000L, clock = { now })
        repeat(4) { l.onFailure() }
        assertThat(l.isLocked()).isFalse()
        l.onSuccess()
        repeat(4) { l.onFailure() }
        assertThat(l.isLocked()).isFalse()
        l.onFailure()
        assertThat(l.isLocked()).isTrue()
        now += 59_999L
        assertThat(l.isLocked()).isTrue()
        now += 1L
        assertThat(l.isLocked()).isFalse()
    }
}
