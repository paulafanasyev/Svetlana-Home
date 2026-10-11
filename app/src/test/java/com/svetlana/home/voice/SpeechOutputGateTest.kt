package com.svetlana.home.voice

import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test

class SpeechOutputGateTest {
    private var now = 1_000_000L

    @Before fun setUp() { SpeechOutputGate.clock = { now }; SpeechOutputGate.reset(); now += 10_000 }
    @After fun tearDown() { SpeechOutputGate.clock = { System.nanoTime() / 1_000_000 }; SpeechOutputGate.reset() }

    @Test fun quietOnlyAfterSpeechAndTail() {
        assertThat(SpeechOutputGate.isQuiet()).isTrue()
        val windowStart = now
        SpeechOutputGate.began()
        assertThat(SpeechOutputGate.isQuiet()).isFalse()
        assertThat(SpeechOutputGate.isSpeaking).isTrue()
        now += 2_000
        SpeechOutputGate.ended()
        assertThat(SpeechOutputGate.isQuiet()).isFalse() // хвост / эхо
        assertThat(SpeechOutputGate.spokeSince(windowStart)).isTrue()
        now += SpeechOutputGate.TAIL_MS
        assertThat(SpeechOutputGate.isQuiet()).isTrue()
        assertThat(SpeechOutputGate.spokeSince(now)).isFalse()
    }

    @Test fun overlappingUtterancesKeepItBusy() {
        SpeechOutputGate.began(); SpeechOutputGate.began()
        SpeechOutputGate.ended()
        assertThat(SpeechOutputGate.isSpeaking).isTrue()
        SpeechOutputGate.ended(); SpeechOutputGate.ended() // лишний ended не уводит в минус
        assertThat(SpeechOutputGate.isSpeaking).isFalse()
        SpeechOutputGate.began()
        assertThat(SpeechOutputGate.isSpeaking).isTrue()
    }

    @Test fun lostCallbackDoesNotBlockMicrophoneForever() {
        SpeechOutputGate.began()
        now += SpeechOutputGate.STUCK_MS + 1
        assertThat(SpeechOutputGate.isSpeaking).isFalse()
        now += SpeechOutputGate.TAIL_MS
        assertThat(SpeechOutputGate.isQuiet()).isTrue()
    }

    @Test fun backgroundVoiceIsNeverTrustedLikeText() {
        assertThat(VoicePolicy.mustConfirmInApp(CommandOrigin.BACKGROUND_VOICE)).isTrue()
        assertThat(VoicePolicy.mayChangeSettings(CommandOrigin.BACKGROUND_VOICE)).isFalse()
        assertThat(VoicePolicy.mayUsePersonalMemory(CommandOrigin.BACKGROUND_VOICE)).isFalse()
        assertThat(VoicePolicy.mustConfirmInApp(CommandOrigin.TEXT)).isFalse()
        assertThat(VoicePolicy.mayChangeSettings(CommandOrigin.TEXT)).isTrue()
    }
}
