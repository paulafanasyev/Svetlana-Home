package com.svetlana.home.voice

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LivenessChallengeTest {

    private val c742 = LivenessChallenge.Challenge(intArrayOf(7, 4, 2))
    private val c702 = LivenessChallenge.Challenge(intArrayOf(7, 0, 2))

    @Test fun spokenAndCode() {
        assertThat(c742.code).isEqualTo("742")
        assertThat(c742.spoken).isEqualTo("семь, четыре, два")
    }

    @Test fun acceptsTypicalRecognizerOutputs() {
        listOf(
            "7 4 2", "742", "7, 4, 2", "семь четыре два", "Семь, четыре, два.",
            "семьсот сорок два", "семьсот 42", "это 7 4 2", "света 742", "74 2"
        ).forEach { assertThat(LivenessChallenge.matches(it, c742)).isTrue() }
        listOf("702", "7 0 2", "семь ноль два", "семьсот два")
            .forEach { assertThat(LivenessChallenge.matches(it, c702)).isTrue() }
    }

    @Test fun rejectsWrongOrPartialDigits() {
        listOf(
            null, "", "   ", "7 4 3", "7 4", "7 4 2 1", "семь четыре", "семьсот сорок три",
            "сорок два", "светлана это я", "247", "7420", "1 742", "x".repeat(500)
        ).forEach { assertThat(LivenessChallenge.matches(it, c742)).isFalse() }
    }

    @Test fun generatedChallengesAreThreeDigitsWithoutLeadingZero() {
        val rnd = java.util.Random(42)
        repeat(500) {
            val c = LivenessChallenge.generate(rnd)
            assertThat(c.code.length).isEqualTo(3)
            assertThat(c.code[0]).isNotEqualTo('0')
            assertThat(LivenessChallenge.matches(c.code, c)).isTrue()
            assertThat(LivenessChallenge.matches(c.spoken, c)).isTrue()
        }
    }

    @Test fun challengesVary() {
        val codes = (0 until 50).map { LivenessChallenge.generate().code }.toSet()
        assertThat(codes.size).isGreaterThan(20)
    }

    @Test fun trustSessionExpiresAndIsBoundToTemplate() {
        val t = VoiceTrust(idleMs = 100L, maxMs = 300L)
        assertThat(t.isValid(1L, 0L)).isFalse()
        t.start(1L, 0L)
        assertThat(t.isValid(1L, 50L)).isTrue()
        assertThat(t.isValid(2L, 50L)).isFalse() // шаблон сменили
        assertThat(t.isValid(1L, 101L)).isFalse() // простой
        t.touch(90L); t.touch(180L)
        assertThat(t.isValid(1L, 250L)).isTrue()
        t.touch(280L)
        assertThat(t.isValid(1L, 301L)).isFalse() // общий предел
        t.start(1L, 1_000L)
        t.end()
        assertThat(t.isValid(1L, 1_001L)).isFalse()
        assertThat(t.isValid(1L, -5L)).isFalse()
    }
}
