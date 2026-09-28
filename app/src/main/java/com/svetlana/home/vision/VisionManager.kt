package com.svetlana.home.vision

import android.content.Context
import android.graphics.Bitmap
import com.svetlana.home.hands.HandsController

/**
 * VisionManager — зрение Светланы (ТЗ §22).
 *
 * Поддерживается:
 *  - screenshots (через Hands);
 *  - чтение текста с экрана (UI tree — доступно всегда, когда включён Hands);
 *  - анализ изображения через провайдера (VLM на сервере/внешнем AI).
 *
 * OCR на устройстве: используется текст из UI tree (что уже содержит
 * распознанные системой строки), а также системный TextClassifier.
 * Подключение полноценного офлайн OCR — отдельная модель по желанию пользователя.
 */
class VisionManager(
    private val context: Context,
    private val hands: HandsController
) {

    data class ScreenText(
        val packageName: String,
        val lines: List<String>,
        val raw: String
    )

    val isAvailable: Boolean get() = hands.isActive

    /**
     * Прочитать текст с экрана через UI tree (не требует OCR).
     */
    fun readScreen(): ScreenText? {
        if (!hands.isActive) return null
        val tree = hands.uiTree() ?: return null
        val lines = tree.nodes.mapNotNull { n -> n.visibleText.ifBlank { null } }
            .filter { it.isNotBlank() }
        return ScreenText(tree.packageName, lines.distinct(), lines.joinToString("\n"))
    }

    /**
     * Сделать скриншот экрана (Hands, Android R+).
     */
    suspend fun screenshot(): Bitmap? = hands.takeScreenshot()

    /**
     * Найти элемент на экране и вернуть его координаты.
     */
    fun findElement(query: String): com.svetlana.home.hands.UiNode? = hands.findElement(query)

    /**
     * Визуальная верификация результата: текст появился на экране?
     */
    fun verifyTextVisible(text: String): Boolean = hands.verifyTextVisible(text)

    /**
     * Анализ изображения через VLM-провайдера (аудит §9, P0).
     *
     * Раньше здесь вызывался text-only chat() — Bitmap фактически не
     * передавался модели, и «Vision» был BLOCKED. Теперь изображение
     * кодируется в JPEG и уходит через [com.svetlana.home.ai.AIRouter.vision],
     * который прогоняет его через privacy-проверку (PrivacyDataType.IMAGE):
     * в LOCAL_ONLY картинка не покинет устройство.
     *
     * Сжатие до 1024px по длинной стороне — достаточно для понимания
     * UI/сцены и сильно экономит токены и трафик.
     */
    suspend fun analyzeImage(image: Bitmap): com.svetlana.home.ai.AIResult {
        val router = com.svetlana.home.core.ServiceLocator.aiRouter
        val bytes = encodeForVlm(image)
            ?: return com.svetlana.home.ai.AIResult(
                false, "Не удалось подготовить изображение",
                com.svetlana.home.ai.AIBackend.NONE
            )
        return router.vision(
            "Проанализируй изображение и опиши, что на нём видно. Ответ на русском.",
            bytes
        )
    }

    /**
     * Сжатие и кодирование Bitmap в JPEG для multimodal inference.
     * Возвращает null, если bitmap пуст или кодировка не удалась.
     */
    private fun encodeForVlm(image: Bitmap): ByteArray? {
        if (image.width <= 0 || image.height <= 0) return null
        val scaled = if (maxOf(image.width, image.height) > MAX_VISION_SIDE) {
            val scale = MAX_VISION_SIDE.toFloat() / maxOf(image.width, image.height)
            Bitmap.createScaledBitmap(
                image,
                (image.width * scale).toInt().coerceAtLeast(1),
                (image.height * scale).toInt().coerceAtLeast(1),
                true
            )
        } else image

        val out = java.io.ByteArrayOutputStream()
        val ok = scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        return if (ok) out.toByteArray() else null
    }

    companion object {
        private const val MAX_VISION_SIDE = 1024
        private const val JPEG_QUALITY = 80
    }
}
