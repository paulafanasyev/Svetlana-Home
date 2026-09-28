package com.svetlana.home.control

import android.graphics.Bitmap
import com.svetlana.home.hands.HandsController
import com.svetlana.home.hands.UiNode

/**
 * PostconditionVerifier — проверка РЕАЛЬНОГО изменения состояния после
 * действия (аудит §5, docs/action-proof.md).
 *
 * Главная проблема старой реализации: `performAction() == true` считался
 * доказательством. Но Android принимает команду, даже если кнопка не
 * сработала. Здесь каждое действие получает собственное постусловие.
 *
 * Снимаем snapshot ДО, выполняем действие, снимаем snapshot ПОСЛЕ —
 * и сравниваем. Только реальное изменение даёт VERIFIED.
 */
class PostconditionVerifier(private val hands: HandsController) {

    /** Снимок состояния «до» действия. */
    data class ScreenSnapshot(
        val nodeCount: Int,
        val currentPackage: String,
        val treeHash: Int
    )

    /** Снимок экрана «до» для последующего сравнения. */
    fun before(): ScreenSnapshot {
        val tree = hands.uiTree()
        return ScreenSnapshot(
            nodeCount = tree?.nodes?.size ?: -1,
            currentPackage = hands.currentPackage(),
            treeHash = tree?.nodes?.hashCode() ?: 0
        )
    }

    /** Открытие приложения: foreground пакет совпадает с целевым. */
    suspend fun verifyOpenApp(targetPackage: String): VerificationState {
        val ok = hands.waitForPackage(targetPackage, timeoutMs = 3000L)
        return if (ok) VerificationState.VERIFIED
        else VerificationState.FAILED
    }

    /** Клик: состояние экрана изменилось (дерево или пакет). */
    fun verifyClick(before: ScreenSnapshot, target: UiNode? = null): VerificationState {
        val after = hands.uiTree() ?: return VerificationState.FAILED
        val changed = after.nodes.size != before.nodeCount ||
                after.nodes.hashCode() != before.treeHash ||
                hands.currentPackage() != before.currentPackage
        // Если дерево не изменилось — клик мог попасть в неактивный элемент.
        return if (changed) VerificationState.VERIFIED else VerificationState.FAILED
    }

    /** Ввод текста: указанный узел содержит ожидаемый текст. */
    fun verifyTypeText(node: UiNode, expected: String): VerificationState {
        val ok = hands.verifyTextEntered(node, expected)
        return if (ok) VerificationState.VERIFIED else VerificationState.FAILED
    }

    /** Очистка поля: текст пуст. */
    fun verifyClearText(node: UiNode): VerificationState {
        val ok = hands.verifyTextEmpty(node)
        return if (ok) VerificationState.VERIFIED else VerificationState.FAILED
    }

    /** Скриншот: bitmap валиден и кодируется. */
    fun verifyScreenshot(bitmap: Bitmap?): VerificationState {
        if (bitmap == null) return VerificationState.FAILED
        if (bitmap.width <= 0 || bitmap.height <= 0) return VerificationState.FAILED
        return try {
            val out = java.io.ByteArrayOutputStream()
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
            if (out.size() > 0) VerificationState.VERIFIED else VerificationState.FAILED
        } catch (t: Throwable) { VerificationState.FAILED }
    }

    /**
     * Прокрутка/свайп: содержимое экрана изменилось.
     * Если дерево осталось идентичным — скролл не произошёл.
     */
    fun verifyScroll(before: ScreenSnapshot): VerificationState {
        val after = hands.uiTree() ?: return VerificationState.FAILED
        val changed = after.nodes.hashCode() != before.treeHash ||
                after.nodes.size != before.nodeCount
        return if (changed) VerificationState.VERIFIED else VerificationState.FAILED
    }

    /**
     * Появление текста на экране (например, после отправки сообщения
     * в мессенджере — сообщение должно появиться в списке).
     */
    fun verifyTextAppeared(text: String): VerificationState {
        val ok = hands.verifyTextVisible(text)
        return if (ok) VerificationState.VERIFIED else VerificationState.FAILED
    }
}
