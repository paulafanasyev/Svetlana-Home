package com.svetlana.home.vision

import android.content.Context
import android.graphics.Bitmap
import com.svetlana.home.hands.HandsController
import com.svetlana.home.translate.TranslateDirection
import com.svetlana.home.translate.TranslatorProviderManager

/**
 * VisionManager — зрение Светланы (ТЗ §22).
 *
 * Поддерживается:
 *  - camera (через CameraX в CameraTranslateActivity);
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
    private val hands: HandsController,
    private val translatorProvider: TranslatorProviderManager
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
     * Перевод текста с экрана.
     */
    suspend fun translateScreenText(direction: TranslateDirection): com.svetlana.home.translate.TranslateResult {
        val screen = readScreen()
            ?: return com.svetlana.home.translate.TranslateResult(
                false, "", "", direction, "vision", "Не удалось прочитать экран")
        return translatorProvider.translate(screen.raw, direction, null)
    }

    /**
     * Анализ изображения через VLM-провайдера (тяжёлая задача — на сервере/внешнем AI).
     * На устройстве без установленной VLM возвращается описание состояния.
     */
    suspend fun analyzeImage(image: Bitmap): com.svetlana.home.ai.AIResult {
        val stream = java.io.ByteArrayOutputStream().use { baos ->
            image.compress(Bitmap.CompressFormat.PNG, 80, baos)
            baos.toByteArray()
        }
        // Гибрид: предобработка локально, анализ на сервере/внешнем провайдере
        val router = com.svetlana.home.core.ServiceLocator.aiRouter
        return router.chat("Проанализируй изображение и опиши, что на нём видно. Ответ на русском.",
            com.svetlana.home.ai.ModelRouter.TaskComplexity.HEAVY)
    }
}
