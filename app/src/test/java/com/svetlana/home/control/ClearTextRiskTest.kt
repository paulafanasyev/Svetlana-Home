package com.svetlana.home.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Раунд 16: очистка поля — необратимое действие, только с подтверждением. */
class ClearTextRiskTest {

    @Test
    fun clearTextAlwaysNeedsConfirmation() {
        val clear = SvetlanaAction.ClearText("notes", "заметка")
        assertEquals(ActionRisk.MODERATE, ActionRiskPolicy.riskOf(clear))
        assertTrue(ActionRiskPolicy.requiresConfirmation(clear))
    }

    @Test
    fun chainWithClearTextNeedsConfirmation() {
        val chain = SvetlanaAction.Compound(listOf(
            SvetlanaAction.ClearText("notes", "заметка"),
            SvetlanaAction.TypeText("notes", "заметка", "привет"),
        ))
        assertTrue(ActionRiskPolicy.requiresConfirmation(chain))
    }
}
