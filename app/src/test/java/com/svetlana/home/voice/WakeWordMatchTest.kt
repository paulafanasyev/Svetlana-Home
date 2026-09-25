package com.svetlana.home.voice

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Wake word: логика определения слова пробуждения (ТЗ §21).
 *
 * ОГРАНИЧЕНИЕ (аудит п.12): схема прослушивания — polling системного STT
 * короткими окнами, а не always-on low-power аппаратный детектор.
 * Эти тесты покрывают ЛОГИКУ сопоставления слова (чистую функцию);
 * само прослушивание — DEVICE-тест, требующий реального микрофона.
 */
class WakeWordMatchTest {

    @Test
    fun exactWakeWord_returnsNullCommand() {
        // Просто «Света» без команды — команду передавать не из чего
        assertThat(WakeWordMatcher.matchWakeWord("Света")).isNull()
        assertThat(WakeWordMatcher.matchWakeWord("светлана")).isNull()
        assertThat(WakeWordMatcher.matchWakeWord("Светочка")).isNull()
        // Но слово пробуждения распознано
        assertThat(WakeWordMatcher.containsWakeWord("Света")).isTrue()
    }

    @Test
    fun wakeWordWithCommand_returnsCommand() {
        assertThat(WakeWordMatcher.matchWakeWord("Света, открой Telegram"))
            .isEqualTo("открой telegram")
        assertThat(WakeWordMatcher.matchWakeWord("Света открой настройки"))
            .isEqualTo("открой настройки")
    }

    @Test
    fun wakeWordCaseInsensitive() {
        assertThat(WakeWordMatcher.matchWakeWord("СВЕТА открой")).isEqualTo("открой")
        assertThat(WakeWordMatcher.matchWakeWord("СвЕтОчКа, переведи")).isEqualTo("переведи")
    }

    @Test
    fun noWakeWord_returnsNull() {
        assertThat(WakeWordMatcher.matchWakeWord("открой telegram")).isNull()
        assertThat(WakeWordMatcher.matchWakeWord("привет")).isNull()
        assertThat(WakeWordMatcher.matchWakeWord("")).isNull()
        assertThat(WakeWordMatcher.containsWakeWord("открой telegram")).isFalse()
    }

    @Test
    fun wakeWordWithComma() {
        assertThat(WakeWordMatcher.matchWakeWord("Света, сделай скриншот"))
            .isEqualTo("сделай скриншот")
    }

    @Test
    fun subsetWordIsNotMatched() {
        // «Свет» — это не «Света», не должно срабатывать
        assertThat(WakeWordMatcher.containsWakeWord("Свет")).isFalse()
        assertThat(WakeWordMatcher.containsWakeWord("освещение")).isFalse()
    }

    @Test
    fun allThreeWakeWordsRecognized() {
        // ТЗ §21: все три wake word должны работать
        assertThat(WakeWordMatcher.containsWakeWord("Света")).isTrue()
        assertThat(WakeWordMatcher.containsWakeWord("Светочка")).isTrue()
        assertThat(WakeWordMatcher.containsWakeWord("Светлана")).isTrue()
    }
}
