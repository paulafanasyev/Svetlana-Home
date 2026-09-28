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

    /**
     * Клик: target-specific проверка (аудит §7).
     *
     * Старая версия проверяла только «изменилось число узлов» — это
     * давало ложные FAIL на успешных кликах (дерево не изменилось) и
     * ложные VERIFIED на случайных изменениях. Теперь проверка по
     * убыванию специфичности:
     *   1. изменилось состояние целевого узла (текст/bounds/focus);
     *   2. изменился foreground пакет (открылся новый экран);
     *   3. изменилось дерево в целом.
     */
    fun verifyClick(before: ScreenSnapshot, target: UiNode? = null): VerificationState {
        val after = hands.uiTree() ?: return VerificationState.FAILED
        return PostconditionLogic.verifyClick(before, after, hands.currentPackage(), target)
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
     * Прокрутка/свайп: содержимое экрана изменилось (аудит §7).
     * Сравниваем не только число узлов, но и видимое содержимое —
     * координаты и текст узлов. Скролл мог произойти при том же
     * числе узлов (список прокрутился внутри контейнера).
     */
    fun verifyScroll(before: ScreenSnapshot): VerificationState {
        val after = hands.uiTree() ?: return VerificationState.FAILED
        return PostconditionLogic.verifyScroll(before, after)
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
