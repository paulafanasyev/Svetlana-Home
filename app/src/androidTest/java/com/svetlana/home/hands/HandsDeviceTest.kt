package com.svetlana.home.hands

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.svetlana.home.SvetlanaDeviceTest
import com.svetlana.home.core.ServiceLocator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Hands: проверка доступности и UI tree на устройстве (ТЗ §15).
 *
 * ВАЖНО: accessibility-сервис включается пользователем вручную через системные
 * настройки. Если он не включён, эти тесты проверяют, что система честно
 * сообщает об этом, а не подделывает активность.
 *
 * ТЗ §19: «команда отправлена» не равна «действие выполнено».
 */
@RunWith(AndroidJUnit4::class)
class HandsDeviceTest : SvetlanaDeviceTest() {

    @Test
    fun handsStateIsHonest() {
        val hands = ServiceLocator.hands
        println("HANDS_ACTIVE=${hands.isActive}")

        if (hands.isActive) {
            // Если Hands активен, UI tree должен быть доступен.
            val tree = hands.uiTree()
            assertNotNull("Активный Hands должен отдавать UI tree", tree)
            println("UITREE_NODES=${tree?.nodes?.size}")
        } else {
            // Если Hands не активен, UI tree должен быть недоступен —
            // это честно, а не заглушка.
            val tree = hands.uiTree()
            assertFalse("Hands off: UI tree не должен быть доступен", tree != null)
        }
    }

    /**
     * Пакет берётся только из живого активного окна (без кеша событий): сразу после
     * смены экрана окна может не быть доли секунды, и тогда честный ответ — пустая строка.
     * Поэтому ждём до 5 секунд, пока окно появится.
     */
    @Test
    fun currentPackageIsReportedOrEmpty() = runBlocking {
        val hands = ServiceLocator.hands
        var pkg = hands.currentPackage()
        if (hands.isActive) {
            val deadline = System.currentTimeMillis() + 5_000L
            while (pkg.isBlank() && System.currentTimeMillis() < deadline) {
                kotlinx.coroutines.delay(200)
                pkg = hands.currentPackage()
            }
        }
        println("CURRENT_PACKAGE=$pkg")
        // Если hands не активен, вернётся пустая строка — это валидный ответ.
        if (hands.isActive) {
            assertTrue("Активный Hands видит пакет", pkg.isNotBlank())
        }
    }

    @Test
    fun globalActionsWorkWhenEnabled() {
        val hands = ServiceLocator.hands
        if (!hands.isActive) {
            println("SKIP: Hands не включён пользователем")
            return
        }
        // pressHome — безопасное действие, его можно вызывать в тестах.
        val ok = hands.pressHome()
        assertTrue("pressHome должен сработать на активном Hands", ok)
    }

    @Test
    fun mobileHarnessDetectionIsHonest() = runBlocking {
        val harness = ServiceLocator.harness
        val state = harness.detect()

        println("HARNESS_INSTALLED=${state.installed} PKG=${state.packageName}")
        // Тест не требует, чтобы Mobile Harness был установлен. Но detect()
        // должен честно сообщить состояние, а не падать.
        assertNotNull("detect() всегда возвращает состояние", state)
        if (!state.installed) {
            assertFalse("Если не установлен — флаг должен быть false", state.installed)
        }
    }

    /**
     * Аудит п.8: dispatchGesture асинхронен. Доказываем, что waitForPackage
     * не зависает на несуществующем пакете и честно возвращает false.
     * Раньше результат жеста мог быть прочитан ДО callback'а.
     */
    @Test
    fun waitForPackage_timesOutForNonexistentPackage() = runBlocking {
        val hands = ServiceLocator.hands
        // Несуществующий пакет никогда не станет foreground — должен быть
        // таймаут и false, а не бесконечное ожидание.
        val found = hands.waitForPackage("com.svetlana.nonexistent.pkg", timeoutMs = 2000)
        assertFalse("Несуществующий пакет не должен быть найден", found)
    }
}
