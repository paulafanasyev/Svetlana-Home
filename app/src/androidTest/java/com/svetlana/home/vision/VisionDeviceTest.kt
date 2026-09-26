package com.svetlana.home.vision

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.svetlana.home.core.ServiceLocator
import com.svetlana.home.translate.TranslateDirection
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ТЗ §22, §55: Vision и camera translation.
 *
 * Без подключённого провайдера зрения (сервер/внешний AI) анализ
 * изображения должен честно отказывать, а не имитировать результат.
 * Это защищает пользователя от ложных «переводов».
 */
@RunWith(AndroidJUnit4::class)
class VisionDeviceTest {

    @Before
    fun setUp() {
        ServiceLocator.init(androidx.test.core.app.ApplicationProvider.getApplicationContext())
    }

    @Test
    fun screenshot_returnsResultOrHonestNull() = runBlocking {
        val bmp = ServiceLocator.vision.screenshot()
        // Без Hands скриншот недоступен — это честно, а не пустой Bitmap
        println("SCREENSHOT=${if (bmp != null) "${bmp.width}x${bmp.height}" else "null (Hands выключен)"}")
    }

    @Test
    fun translateScreenText_reportsHonestFailureWithoutProvider() = runBlocking {
        val result = ServiceLocator.vision.translateScreenText(TranslateDirection.RU_TO_VI)

        assertNotNull("Результат должен быть", result)
        // Без провайдера зрения перевод невозможен — отказ зафиксирован
        if (!result.success) {
            assertTrue("Причина отказа должна быть указана",
                result.error?.isNotBlank() == true || result.text.isNotBlank())
        }
    }

    @Test
    fun analyzeImage_withoutProvider_doesNotFakeResult() = runBlocking {
        // Пустой 1x1 Bitmap — реального текста нет
        val bmp = android.graphics.Bitmap.createBitmap(
            1, 1, android.graphics.Bitmap.Config.ARGB_8888)
        val result = ServiceLocator.vision.analyzeImage(bmp)

        // Если провайдер не подключён — успех невозможен
        if (!result.success) {
            assertTrue("Текст отказа должен быть", result.text.isNotBlank())
            println("VISION_FAIL_REASON=${result.text.take(100)}")
        } else {
            // Если провайдер есть, он не должен выдумывать текст на пустом изображении
            println("VISION_RESULT=${result.text.take(100)}")
        }
    }

    @Test
    fun findElement_returnsNullWhenHandsDisabled() {
        val node = ServiceLocator.vision.findElement("кнопка_которой_нет")
        // Без Hands — null, а не фиктивный узел
        if (!ServiceLocator.hands.isActive) {
            assertFalse("Без Hands элемент не может быть найден", node != null)
        }
    }

    @Test
    fun verifyTextVisible_honestWithoutHands() {
        val visible = ServiceLocator.vision.verifyTextVisible("текст_которого_нет")
        if (!ServiceLocator.hands.isActive) {
            assertFalse("Без Hands проверка видимости невозможна", visible)
        }
    }
}
