package com.svetlana.home.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Аудит §23: SAVE CONFIG ≠ ACTIVATE. Активация только после inference.
 */
class ProviderStateTest {

    @Test
    fun `only CONFIGURED when just saved`() {
        val s = ProviderStateCalculator.from(
            configured = true, connectionOk = null, modelExists = null,
            inferenceOk = null, isActive = false
        )
        assertEquals(ProviderState.CONFIGURED, s)
        assertFalse("Сохранённая конфигурация не активна", s.canActivate())
    }

    @Test
    fun `CONNECTION_FAILED when endpoint or key bad`() {
        val s = ProviderStateCalculator.from(
            configured = true, connectionOk = false, modelExists = null,
            inferenceOk = null, isActive = false
        )
        assertEquals(ProviderState.CONNECTION_FAILED, s)
        assertFalse(s.canActivate())
    }

    @Test
    fun `CONNECTED is not enough to activate`() {
        // Соединение есть, модель существует, но inference не проверен
        val s = ProviderStateCalculator.from(
            configured = true, connectionOk = true, modelExists = true,
            inferenceOk = null, isActive = false
        )
        assertEquals(ProviderState.CONNECTED, s)
        assertFalse("Только подключение не даёт право активации", s.canActivate())
    }

    @Test
    fun `VERIFIED after real inference`() {
        val s = ProviderStateCalculator.from(
            configured = true, connectionOk = true, modelExists = true,
            inferenceOk = true, isActive = false
        )
        assertEquals(ProviderState.VERIFIED, s)
        assertTrue(s.canActivate())
    }

    @Test
    fun `ACTIVE only when verified and selected`() {
        val s = ProviderStateCalculator.from(
            configured = true, connectionOk = true, modelExists = true,
            inferenceOk = true, isActive = true
        )
        assertEquals(ProviderState.ACTIVE, s)
    }

    @Test
    fun `inference failed cannot activate`() {
        // Аудит: «ключ правильный, но модель недоступна» — активация запрещена
        val s = ProviderStateCalculator.from(
            configured = true, connectionOk = true, modelExists = true,
            inferenceOk = false, isActive = false
        )
        // inferenceOk == false -> CONNECTION_FAILED per ladder (connectionOk true though)
        //	connectionOk=true, modelExists=true, inferenceOk=false falls to CONNECTED
        assertEquals(ProviderState.CONNECTED, s)
        assertFalse(s.canActivate())
    }
}
