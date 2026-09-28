package com.svetlana.home.control

import com.svetlana.home.hands.UiNode
import com.svetlana.home.hands.UiTree

/**
 * Чистая логика postcondition-проверок (аудит §5, §7).
 *
 * Не зависит от Android-сервисов — принимает уже снятые снимки
 * состояния, поэтому полноценно покрывается unit-тестами.
 * [PostconditionVerifier] делегирует сюда реальные проверки.
 *
 * Принцип: platformAccepted ≠ performed. Только реальное изменение
 * состояния даёт VERIFIED.
 */
object PostconditionLogic {

    /**
     * Клик: target-specific проверка.
     *
     * Старая версия проверяла только «изменилось число узлов» — это
     * давало ложные FAIL на успешных кликах (дерево не изменилось) и
     * ложные VERIFIED на случайных изменениях. Теперь проверка по
     * убыванию специфичности:
     *   1. изменилось состояние целевого узла (текст/bounds/focus);
     *   2. изменился foreground пакет (открылся новый экран);
     *   3. изменилось дерево в целом.
     */
    fun verifyClick(
        before: PostconditionVerifier.ScreenSnapshot,
        after: UiTree,
        currentPackage: String,
        target: UiNode?
    ): VerificationState {
        // 1. Target-specific: узел с тем же id/текстом изменил состояние.
        if (target != null) {
            val live = after.nodes.firstOrNull { it.id == target.id && it.id.isNotBlank() }
                ?: after.nodes.firstOrNull { it.visibleText == target.visibleText && it.visibleText.isNotBlank() }
            if (live != null && targetStateChanged(target, live)) {
                return VerificationState.VERIFIED
            }
        }

        // 2. Foreground пакет изменился — открылся новый экран.
        if (currentPackage != before.currentPackage) return VerificationState.VERIFIED

        // 3. Специфичное содержимое изменилось.
        if (after.nodes.hashCode() != before.treeHash) return VerificationState.VERIFIED

        // 4. Число узлов изменилось.
        if (after.nodes.size != before.nodeCount) return VerificationState.VERIFIED

        // Ничего не изменилось — клик не возымел эффекта.
        return VerificationState.FAILED
    }

    /** Изменилось ли состояние целевого узла после действия. */
    private fun targetStateChanged(before: UiNode, after: UiNode): Boolean =
        after.text != before.text ||
            after.contentDescription != before.contentDescription ||
            boundsChanged(before.bounds, after.bounds) ||
            after.isFocused != before.isFocused ||
            after.isEnabled != before.isEnabled

    /**
     * Сравнение bounds по координатам, а не через Rect.equals().
     * Прямой equals ненадёжен в тестовом окружении (mockable android.jar
     * всегда возвращает false) — координатное сравнение работает везде.
     */
    private fun boundsChanged(a: android.graphics.Rect, b: android.graphics.Rect): Boolean =
        a.left != b.left || a.top != b.top || a.right != b.right || a.bottom != b.bottom

    /**
     * Прокрутка/свайп: содержимое экрана изменилось.
     * Сравниваем хэш видимого дерева и число узлов. Скролл мог
     * произойти при том же числе узлов (список прокрутился внутри
     * контейнера) — поэтому хэш важнее count.
     */
    fun verifyScroll(
        before: PostconditionVerifier.ScreenSnapshot,
        after: UiTree
    ): VerificationState {
        if (after.nodes.hashCode() != before.treeHash) return VerificationState.VERIFIED
        if (after.nodes.size != before.nodeCount) return VerificationState.VERIFIED
        return VerificationState.FAILED
    }
}
