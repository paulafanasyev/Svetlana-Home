package com.svetlana.home.ai

import com.svetlana.home.ai.providers.normalizeEndpoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Аудит п.3: «тест не проходит даже для полностью рабочих подключений».
 *
 * Провайдеры OpenAI-compatible принимают запросы по «/v1/chat/completions».
 * Call site строит URL как «normalizedEndpoint()/chat/completions», поэтому
 * функция обязана гарантировать ровно один сегмент «/v1»:
 *
 *  - «https://api.openai.com»     → «https://api.openai.com/v1»
 *  - «https://api.openai.com/v1»  → «https://api.openai.com/v1» (без дубля)
 *  - «https://api.groq.com/openai/v1» → путь /v1 внутри сохраняется
 *
 * Чистая функция — не зависит от Android, полностью покрывается тестами.
 */
class OpenAiCompatibleProviderTest {

    private fun normalize(baseUrl: String): String = normalizeEndpoint(baseUrl)

    @Test
    fun `bare host gets v1 appended`() {
        // Пользователь ввёл только базу — /v1 добавляется автоматически.
        assertEquals("https://api.openai.com/v1", normalize("https://api.openai.com"))
    }

    @Test
    fun `canonical v1 endpoint not doubled`() {
        // Главная regression: пользователь копирует из документации OpenAI.
        // Двойной «/v1/v1/chat/completions» → 404.
        assertEquals("https://api.openai.com/v1", normalize("https://api.openai.com/v1"))
    }

    @Test
    fun `trailing slash handled`() {
        assertEquals("https://api.openai.com/v1", normalize("https://api.openai.com/v1/"))
    }

    @Test
    fun `double v1 collapsed to single`() {
        assertEquals("https://api.openai.com/v1", normalize("https://api.openai.com/v1/v1"))
    }

    @Test
    fun `groq openai path preserved`() {
        // Groq использует /openai/v1/chat/completions — путь /v1 уже внутри.
        assertEquals("https://api.groq.com/openai/v1", normalize("https://api.groq.com/openai/v1/"))
    }

    @Test
    fun `groq bare base gets v1 appended`() {
        assertEquals("https://api.groq.com/openai/v1", normalize("https://api.groq.com/openai"))
    }

    @Test
    fun `custom gateway path gets v1`() {
        // Self-hosted шлюз без версии: добавляем /v1.
        assertEquals("https://gw.example.com/ai/v1", normalize("https://gw.example.com/ai"))
    }

    @Test
    fun `double slashes collapsed, scheme preserved`() {
        assertEquals("https://gw.example.com/ai/v1", normalize("https://gw.example.com//ai/v1"))
    }

    @Test
    fun `localhost llama cpp server works`() {
        // llama.cpp server: http://127.0.0.1:8080/v1/chat/completions
        assertEquals("http://127.0.0.1:8080/v1", normalize("http://127.0.0.1:8080/v1"))
    }

    @Test
    fun `host containing v1 substring still gets path v1`() {
        // «myv1host» содержит «v1» как подстроку, но не сегмент «/v1».
        assertEquals("https://myv1host.com/v1", normalize("https://myv1host.com"))
    }

    @Test
    fun `blank endpoint handled safely`() {
        assertTrue(normalize("  ").isEmpty())
    }
}
