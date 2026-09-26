package com.svetlana.home.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Логика проверки модели: если файла нет — все этапы Load/Context/Inference
 * проваливаются с причиной, а не заявляют «работает» (аудит п.2).
 */
class ModelVerificationRunnerTest {

    @Test
    fun `summary содержит причину когда inference не запущен`() {
        val report = ModelVerificationRunner.Report(
            modelId = "test",
            stages = listOf(
                ModelVerificationRunner.Stage("Файл найден", true, "100 МБ"),
                ModelVerificationRunner.Stage("Runtime", false, "недоступен"),
                ModelVerificationRunner.Stage("Load", false, "runtime недоступен"),
                ModelVerificationRunner.Stage("Context", false, "runtime недоступен"),
                ModelVerificationRunner.Stage("Inference", false, "runtime недоступен")
            ),
            firstTokenMs = 0,
            tokensPerSecond = 0.0,
            ramUsedMb = 0,
            inferenceOk = false,
            failureReason = "runtime недоступен"
        )
        val text = report.summary()
        assertTrue("сводка показывает проваленные этапы", text.contains("✕ Runtime"))
        assertTrue("сводка показывает причину", text.contains("Причина: runtime недоступен"))
        assertFalse("нет ложного заявления об inference", text.contains("ток/с"))
    }

    @Test
    fun `summary показывает метиски при успешном inference`() {
        val report = ModelVerificationRunner.Report(
            modelId = "test",
            stages = listOf(
                ModelVerificationRunner.Stage("Файл найден", true, "100 МБ"),
                ModelVerificationRunner.Stage("Совместимость", true, "q4"),
                ModelVerificationRunner.Stage("Runtime", true, "llama.cpp готов"),
                ModelVerificationRunner.Stage("Load", true, "загружена"),
                ModelVerificationRunner.Stage("Context", true, "создан"),
                ModelVerificationRunner.Stage("Inference", true, "ответ получен")
            ),
            firstTokenMs = 2800,
            tokensPerSecond = 4.1,
            ramUsedMb = 3200,
            inferenceOk = true,
            failureReason = null
        )
        val text = report.summary()
        assertTrue(text.contains("Первый токен: 2.8 сек"))
        assertTrue(text.contains("4.1 ток/с"))
        assertTrue(text.contains("RAM: 3200 МБ"))
        assertFalse(text.contains("Inference не запущен"))
    }

    @Test
    fun `отчёт без причины не падает на null-форматировании`() {
        val report = ModelVerificationRunner.Report(
            modelId = "test",
            stages = emptyList(),
            firstTokenMs = 0,
            tokensPerSecond = 0.0,
            ramUsedMb = 0,
            inferenceOk = false,
            failureReason = null
        )
        // null-безопасность: failureReason обрабатывается корректно
        assertNull(report.failureReason)
        assertNotNull(report.summary())
    }
}
