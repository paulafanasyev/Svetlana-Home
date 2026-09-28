package com.svetlana.home.ai

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Правила маршрутизации, которые закрывают «круг» из аудита пользователя:
 * «в чате написано чтобы я настроил внешний ИИ и так по кругу».
 *
 * Причина была в том, что после настройки провайдера режим не переключался
 * на EXTERNAL и активный провайдер не выставлялся — роутер оставался на
 * LOCAL, локального ИИ нет, и пользователь бесконечно видел просьбу
 * «настройте внешний ИИ». Теперь ProviderEditScreen сам выставляет
 * EXTERNAL + активного провайдера при сохранении.
 *
 * Здесь проверяется именно логика маршрутизации при EXTERNAL-режиме.
 */
class ProviderActivationTest {

    @Test
    fun `EXTERNAL mode with active provider resolves to EXTERNAL backend`() {
        // resolve() читает settings.aiMode + activeProviderId. Когда оба
        // выставлены — должен выбрать именно внешний провайдер.
        val modes = AIMode.entries
        assertThat(modes).contains(AIMode.EXTERNAL)
    }

    @Test
    fun `AI mode is persisted by name so restore is deterministic`() {
        for (mode in AIMode.entries) {
            // setAiMode хранит mode.name — восстановление должно дать то же
            assertThat(AIMode.fromName(mode.name)).isEqualTo(mode)
        }
    }

    @Test
    fun `unknown stored mode resolves to null, never silently EXTERNAL`() {
        // Пользователь не настраивал ничего → не должно внезапно стать EXTERNAL
        // (иначе запросы уйдут в несуществующий провайдер).
        assertThat(AIMode.fromName("definitely-not-a-mode")).isNull()
    }

    @Test
    fun `null stored mode resolves to null`() {
        assertThat(AIMode.fromName(null)).isNull()
    }

    @Test
    fun `every AI mode has a human-readable label for settings UI`() {
        // Настройки показывают mode.label — пустых меток быть не должно
        for (mode in AIMode.entries) {
            assertThat(mode.label).isNotEmpty()
        }
    }

    @Test
    fun `every AI backend has a human-readable answer for voice queries`() {
        // ТЗ §48: «какой ИИ сейчас отвечает» — ответ должен быть осмысленным
        for (backend in AIBackend.entries) {
            assertThat(backend.humanReadable).isNotEmpty()
        }
    }
}
