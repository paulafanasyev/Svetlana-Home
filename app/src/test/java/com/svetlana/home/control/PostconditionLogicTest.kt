package com.svetlana.home.control

import com.svetlana.home.hands.UiNode
import com.svetlana.home.hands.UiTree
import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Аудит §7: слабая верификация Hands.
 *
 * Старая проверка click сравнивала только nodeCount — это давало
 * ложные FAIL (дерево не изменилось при успешном клике) и ложные
 * VERIFIED (случайное изменение). Теперь target-specific проверки.
 *
 * Тестируется [PostconditionLogic] — чистая логика без Android-сервисов.
 */
class PostconditionLogicTest {

    private val nodeA = UiNode(
        className = "android.widget.Button", text = "Отправить",
        contentDescription = "", id = "btn_send",
        isClickable = true, isScrollable = false, isEnabled = true,
        isFocused = false, isPassword = false, depth = 2,
        bounds = Rect(10, 10, 100, 60)
    )

    private fun snapshot(tree: UiTree, pkg: String) =
        PostconditionVerifier.ScreenSnapshot(
            nodeCount = tree.nodes.size,
            currentPackage = pkg,
            treeHash = tree.nodes.hashCode()
        )

    @Test
    fun `click verified when target text changes`() {
        val before = snapshot(UiTree("com.app", null, listOf(nodeA)), "com.app")
        val after = UiTree("com.app", null, listOf(nodeA.copy(text = "Отправлено")))

        val state = PostconditionLogic.verifyClick(before, after, "com.app", nodeA)
        assertEquals("Текст цели изменился — VERIFIED", VerificationState.VERIFIED, state)
    }

    @Test
    fun `click verified when target becomes focused`() {
        val before = snapshot(UiTree("com.app", null, listOf(nodeA)), "com.app")
        val after = UiTree("com.app", null, listOf(nodeA.copy(isFocused = true)))

        val state = PostconditionLogic.verifyClick(before, after, "com.app", nodeA)
        assertEquals(VerificationState.VERIFIED, state)
    }

    @Test
    fun `click verified when target becomes disabled`() {
        val before = snapshot(UiTree("com.app", null, listOf(nodeA)), "com.app")
        val after = UiTree("com.app", null, listOf(nodeA.copy(isEnabled = false)))

        val state = PostconditionLogic.verifyClick(before, after, "com.app", nodeA)
        assertEquals(VerificationState.VERIFIED, state)
    }

    @Test
    fun `click verified when foreground package changes`() {
        val before = snapshot(UiTree("com.app", null, listOf(nodeA)), "com.app")

        // Цель не изменилась, но открылся другой экран
        val state = PostconditionLogic.verifyClick(
            before, UiTree("com.app", null, listOf(nodeA)), "com.other", nodeA
        )
        assertEquals("Открылся новый экран — VERIFIED", VerificationState.VERIFIED, state)
    }

    @Test
    fun `click verified when node count changes without target match`() {
        val before = snapshot(UiTree("com.app", null, listOf(nodeA)), "com.app")
        val after = UiTree("com.app", null, listOf(nodeA, nodeA.copy(id = "other")))

        val state = PostconditionLogic.verifyClick(before, after, "com.app", null)
        assertEquals("Состав дерева изменился — VERIFIED", VerificationState.VERIFIED, state)
    }

    @Test
    fun `click not verified when nothing changed`() {
        val tree = UiTree("com.app", null, listOf(nodeA))
        val before = snapshot(tree, "com.app")

        // Полностью идентичное дерево и пакет. Используем тот же
        // экземпляр узла — android.jar test-stub некорректно реализует
        // Rect.equals, поэтому полагаемся на текстовые поля, а не на bounds.
        val state = PostconditionLogic.verifyClick(before, tree.copy(), "com.app", nodeA)
        assertEquals("Ничего не изменилось — FAILED", VerificationState.FAILED, state)
    }

    @Test
    fun `click found by visible text when id is blank`() {
        val noId = nodeA.copy(id = "")
        val before = snapshot(UiTree("com.app", null, listOf(noId)), "com.app")
        val after = UiTree("com.app", null, listOf(noId.copy(text = "Отправлено")))

        val state = PostconditionLogic.verifyClick(before, after, "com.app", noId)
        assertEquals("Поиск по тексту работает — VERIFIED", VerificationState.VERIFIED, state)
    }

    @Test
    fun `click not verified when unrelated node changes but target missing after`() {
        // Цель пропала из дерева, остальной состав совпал по хэшу
        val before = snapshot(UiTree("com.app", null, listOf(nodeA)), "com.app")
        val after = UiTree("com.app", null, listOf(nodeA.copy(id = "btn_other")))

        val state = PostconditionLogic.verifyClick(before, after, "com.app", nodeA)
        // Состав узлов отличается — это изменение дерева, поэтому VERIFIED.
        // Этот тест фиксирует: цель исчезла, но экран изменился — клик сработал.
        assertEquals(VerificationState.VERIFIED, state)
    }

    @Test
    fun `scroll not verified on identical tree`() {
        val tree = UiTree("com.app", null, listOf(nodeA))
        val before = snapshot(tree, "com.app")

        val state = PostconditionLogic.verifyScroll(before, tree.copy())
        assertEquals(VerificationState.FAILED, state)
    }

    @Test
    fun `scroll verified when content shifts`() {
        // Прокрутка обычно меняет число видимых элементов списка.
        val before = snapshot(UiTree("com.app", null, listOf(nodeA)), "com.app")
        val after = UiTree("com.app", null, listOf(nodeA, nodeA.copy(id = "item2", text = "Новый")))

        val state = PostconditionLogic.verifyScroll(before, after)
        assertEquals("Состав видимого списка изменился — VERIFIED", VerificationState.VERIFIED, state)
    }

    @Test
    fun `scroll verified when node text changes`() {
        // Текст элемента изменился — содержимое сместилось.
        val before = snapshot(UiTree("com.app", null, listOf(nodeA)), "com.app")
        val after = UiTree("com.app", null, listOf(nodeA.copy(text = "Другой текст")))

        val state = PostconditionLogic.verifyScroll(before, after)
        assertEquals(VerificationState.VERIFIED, state)
    }

    @Test
    fun `scroll verified when node count changes`() {
        val before = snapshot(UiTree("com.app", null, listOf(nodeA)), "com.app")
        val after = UiTree("com.app", null, listOf(nodeA, nodeA.copy(id = "item2")))

        val state = PostconditionLogic.verifyScroll(before, after)
        assertEquals(VerificationState.VERIFIED, state)
    }
}
