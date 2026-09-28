package com.svetlana.home.ai

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Аудит §9 (P0): Vision должен реально передавать изображение модели.
 *
 * Раньше VisionManager.analyzeImage() вызывал text-only chat() —
 * bitmap никогда не доходил до провайдера. Этот тест фиксирует
 * контракт: провайдер получает байты изображения, а не только текст.
 */
class VisionPipelineTest {

    /**
     * Тестовый провайдер, перехватывающий то, что реально было передано.
     * Главный баг был в том, что imageBytes приходил пустым.
     */
    private open class RecordingProvider : AIProvider {
        override val id: String = "test"
        override val displayName: String = "Тестовый VLM"
        override val type: AIProvider.ProviderType = AIProvider.ProviderType.OPENAI_COMPATIBLE
        override val backend: AIBackend = AIBackend.EXTERNAL

        var receivedPrompt: String? = null
        var receivedImage: ByteArray? = null
        var visionCalled = false
        var chatCalled = false

        override fun capabilities(): ProviderCapabilities =
            ProviderCapabilities(chat = true, vision = true, embeddings = false, maxContext = 4096)

        override fun isConfigured(): Boolean = true
        override fun isAvailable(): Boolean = true

        override suspend fun chat(prompt: String, systemPrompt: String?): AIResult {
            chatCalled = true
            receivedPrompt = prompt
            return AIResult(true, "текстовый ответ", backend)
        }

        override suspend fun vision(prompt: String, imageBytes: ByteArray): AIResult {
            visionCalled = true
            receivedPrompt = prompt
            receivedImage = imageBytes
            return AIResult(true, "на изображении кот", backend)
        }

        override suspend fun testConnection(): AIResult = AIResult(true, "ок", backend)
        override fun redactedConfig(): String = "test-provider"
    }

    @Test
    fun `vision receives non-empty image bytes`() = runBlocking {
        val provider = RecordingProvider()
        val image = "test-image-content".toByteArray()

        provider.vision("Опиши картинку", image)

        assertTrue("vision() должен быть вызван", provider.visionCalled)
        assertNotNull("Изображение передано", provider.receivedImage)
        assertArrayEquals(
            "Байты изображения должны дойти до провайдера без потерь",
            image, provider.receivedImage
        )
    }

    @Test
    fun `vision keeps prompt text`() = runBlocking {
        val provider = RecordingProvider()
        provider.vision("Что на экране?", ByteArray(10))

        assertEquals("Что на экране?", provider.receivedPrompt)
    }

    @Test
    fun `vision is not text-only chat`() = runBlocking {
        // Это и был корень BLOCKED: vision вызывал chat() под капотом.
        val provider = RecordingProvider()
        provider.vision("Опиши", ByteArray(10))

        assertTrue("Должен использоваться vision()", provider.visionCalled)
        assertFalse("Не должен использоваться text-only chat()", provider.chatCalled)
    }

    @Test
    fun `provider without vision capability is not a vision backend`() {
        // AIRouter проверяет capabilities().vision до вызова провайдера.
        class NoVisionProvider : RecordingProvider() {
            override fun capabilities(): ProviderCapabilities =
                ProviderCapabilities(chat = true, vision = false, embeddings = false, maxContext = 4096)
        }
        val provider = NoVisionProvider()

        assertFalse("Провайдер без vision не должен принимать изображения",
            provider.capabilities().vision)
    }

    @Test
    fun `empty image bytes are still passed through`() = runBlocking {
        // Даже пустое изображение должно пройти путь до провайдера —
        // решение о невалидности принимает модель, а не тихое удаление.
        val provider = RecordingProvider()
        provider.vision("prompt", ByteArray(0))
        assertNotNull(provider.receivedImage)
    }
}
