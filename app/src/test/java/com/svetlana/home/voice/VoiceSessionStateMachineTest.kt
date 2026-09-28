package com.svetlana.home.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Аудит §18: явная машина состояний голосовой сессии.
 *
 * Проблема: wake-word цикл мог стартовать новую сессию STT, пока
 * предыдущая не завершилась (onResults/onError не пришли). Это
 * гонка — терялись результаты и микрофон «зависал».
 *
 * Теперь переходы строго определены, и非法ные отклоняются.
 */
class VoiceSessionStateMachineTest {

    @Test
    fun `idle can start new session`() {
        val sm = VoiceSessionStateMachine()
        assertTrue("Из IDLE можно стартовать", sm.canStartNewSession)
        assertFalse("Нет активной пользовательской сессии", sm.isUserSessionActive)
    }

    @Test
    fun `idle to starting is allowed`() {
        val sm = VoiceSessionStateMachine()
        assertTrue(sm.transitionTo(VoiceSessionState.STARTING))
        assertEquals(VoiceSessionState.STARTING, sm.state)
    }

    @Test
    fun `starting to listening is allowed`() {
        val sm = VoiceSessionStateMachine().apply {
            transitionTo(VoiceSessionState.STARTING)
        }
        assertTrue(sm.transitionTo(VoiceSessionState.LISTENING))
        assertEquals(VoiceSessionState.LISTENING, sm.state)
    }

    @Test
    fun `listening to result received is allowed`() {
        val sm = VoiceSessionStateMachine().apply {
            transitionTo(VoiceSessionState.STARTING)
            transitionTo(VoiceSessionState.LISTENING)
        }
        assertTrue(sm.transitionTo(VoiceSessionState.RESULT_RECEIVED))
        assertEquals(VoiceSessionState.RESULT_RECEIVED, sm.state)
    }

    @Test
    fun `result received to processing is allowed`() {
        val sm = VoiceSessionStateMachine().apply {
            transitionTo(VoiceSessionState.STARTING)
            transitionTo(VoiceSessionState.LISTENING)
            transitionTo(VoiceSessionState.RESULT_RECEIVED)
        }
        assertTrue(sm.transitionTo(VoiceSessionState.PROCESSING))
    }

    @Test
    fun `processing returns to idle is allowed`() {
        val sm = VoiceSessionStateMachine().apply {
            transitionTo(VoiceSessionState.STARTING)
            transitionTo(VoiceSessionState.LISTENING)
            transitionTo(VoiceSessionState.RESULT_RECEIVED)
            transitionTo(VoiceSessionState.PROCESSING)
        }
        assertTrue(sm.transitionTo(VoiceSessionState.IDLE))
        assertEquals(VoiceSessionState.IDLE, sm.state)
    }

    /**
     * Главная защита: пока пользовательская сессия активна, новая
     * не стартует. Это и устраняет гонку wake-word vs user session.
     */
    @Test
    fun `listening blocks new session`() {
        val sm = VoiceSessionStateMachine().apply {
            transitionTo(VoiceSessionState.STARTING)
            transitionTo(VoiceSessionState.LISTENING)
        }
        assertTrue("Сессия пользователя активна", sm.isUserSessionActive)
        assertFalse("Нельзя стартовать новую", sm.canStartNewSession)
    }

    @Test
    fun `processing blocks new session`() {
        val sm = VoiceSessionStateMachine().apply {
            transitionTo(VoiceSessionState.STARTING)
            transitionTo(VoiceSessionState.LISTENING)
            transitionTo(VoiceSessionState.RESULT_RECEIVED)
            transitionTo(VoiceSessionState.PROCESSING)
        }
        assertTrue(sm.isUserSessionActive)
        assertFalse(sm.canStartNewSession)
    }

    @Test
    fun `illegal idle to listening is rejected`() {
        val sm = VoiceSessionStateMachine()
        // Нельзя перескочить STARTING — это пропускает инициализацию
        assertFalse(sm.transitionTo(VoiceSessionState.LISTENING))
        assertEquals("Состояние не изменилось", VoiceSessionState.IDLE, sm.state)
    }

    @Test
    fun `illegal idle to processing is rejected`() {
        val sm = VoiceSessionStateMachine()
        assertFalse(sm.transitionTo(VoiceSessionState.PROCESSING))
        assertEquals(VoiceSessionState.IDLE, sm.state)
    }

    @Test
    fun `error path stops session`() {
        val sm = VoiceSessionStateMachine().apply {
            transitionTo(VoiceSessionState.STARTING)
            transitionTo(VoiceSessionState.LISTENING)
        }
        // Ошибка во время слушания → STOPPED
        assertTrue(sm.transitionTo(VoiceSessionState.STOPPED))
        assertEquals(VoiceSessionState.STOPPED, sm.state)
        // STOPPED → IDLE снова доступен
        assertTrue(sm.canStartNewSession)
    }

    @Test
    fun `stopped to idle is allowed`() {
        val sm = VoiceSessionStateMachine().apply {
            transitionTo(VoiceSessionState.STARTING)
            transitionTo(VoiceSessionState.STOPPED)
        }
        assertTrue(sm.transitionTo(VoiceSessionState.IDLE))
        assertEquals(VoiceSessionState.IDLE, sm.state)
    }

    @Test
    fun `reset returns to idle`() {
        val sm = VoiceSessionStateMachine().apply {
            transitionTo(VoiceSessionState.STARTING)
            transitionTo(VoiceSessionState.LISTENING)
        }
        sm.reset()
        assertEquals(VoiceSessionState.IDLE, sm.state)
        assertTrue(sm.canStartNewSession)
    }

    @Test
    fun `starting to stopped allowed for immediate cancel`() {
        val sm = VoiceSessionStateMachine().apply {
            transitionTo(VoiceSessionState.STARTING)
        }
        // Отмена во время старта — корректный терминал
        assertTrue(sm.transitionTo(VoiceSessionState.STOPPED))
    }
}
