package com.svetlana.home.hands

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.svetlana.home.permissions.PermissionManager
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** UI-элемент в удобном виде. */
data class UiNode(
    val className: String,
    val text: String,
    val contentDescription: String,
    val id: String,
    val isClickable: Boolean,
    val isScrollable: Boolean,
    val isEnabled: Boolean,
    val isFocused: Boolean,
    val isPassword: Boolean,
    val depth: Int,
    val bounds: Rect
) {
    val visibleText: String get() = text.ifBlank { contentDescription }
}

data class UiTree(
    val packageName: String,
    val root: UiNode?,
    val nodes: List<UiNode>
)

/**
 * HandsController — единая точка управления другими приложениями.
 * Делегирует реальные действия SvetlanaAccessibilityService и всегда
 * проверяет наличие системного доступа перед выполнением.
 */
class HandsController(private val context: Context) {

    private val permissionManager = PermissionManager(context)

    val isActive: Boolean
        get() = permissionManager.accessibilityEnabled() && service() != null

    private fun service(): SvetlanaAccessibilityService? = SvetlanaAccessibilityService.instance

    fun onLowMemory() { /* Hands не кеширует тяжёлые объекты */ }

    fun uiTree(): UiTree? {
        val svc = service() ?: return null
        return svc.snapshotUiTree()
    }

    fun currentPackage(): String = service()?.currentPackage() ?: ""

    fun click(x: Float, y: Float): Boolean = service()?.clickPoint(x, y) ?: false

    /**
     * Поиск «живого» AccessibilityNodeInfo, соответствующего UiNode из снапшота.
     *
     * Аудит п.12: ранее поиск шёл ТОЛЬКО по resource-id. Если у элемента нет
     * id (id пустой) — clickNode/longClick/inputText молча возвращали false,
     * хотя элемент был только что найден по тексту. Теперь используем
     * идентификацию по нескольким признакам: id → текст → contentDescription →
     * класс + bounds.
     */
    private fun findLiveNode(node: UiNode): AccessibilityNodeInfo? {
        val svc = service() ?: return null
        val root = svc.rootInActiveWindow ?: return null
        // 1. По resource-id, если он есть (самый надёжный путь).
        if (node.id.isNotBlank()) {
            findByViewId(root, node.id)?.let { return it }
        }
        // 2. По видимому тексту (то, как элемент был найден в findElement).
        val byText = node.visibleText.takeIf { it.isNotBlank() }?.let { text ->
            findByText(root, text)
        }
        if (byText != null) return byText
        // 3. По классу + положению на экране (последняя надежда).
        return findByClassAndBounds(root, node)
    }

    private fun findByText(node: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        val nodeText = node.text?.toString().orEmpty()
        val nodeDesc = node.contentDescription?.toString().orEmpty()
        if (nodeText == text || nodeDesc == text) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findByText(child, text)?.let { return it }
        }
        return null
    }

    private fun findByClassAndBounds(node: AccessibilityNodeInfo, target: UiNode): AccessibilityNodeInfo? {
        val bounds = android.graphics.Rect().also { node.getBoundsInScreen(it) }
        val sameClass = node.className?.toString().orEmpty() == target.className
        if (sameClass && bounds == target.bounds) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findByClassAndBounds(child, target)?.let { return it }
        }
        return null
    }

    fun clickNode(node: UiNode): Boolean {
        val svc = service() ?: return false
        val live = findLiveNode(node) ?: return false
        return svc.click(live)
    }

    fun longClick(node: UiNode): Boolean {
        val svc = service() ?: return false
        val live = findLiveNode(node) ?: return false
        return svc.longClick(live)
    }

    fun inputText(node: UiNode, text: String): Boolean {
        val svc = service() ?: return false
        val live = findLiveNode(node) ?: return false
        return svc.inputText(live, text)
    }

    fun clearText(node: UiNode): Boolean {
        val svc = service() ?: return false
        val live = findLiveNode(node) ?: return false
        return svc.clearText(live)
    }

    fun scrollForward(): Boolean {
        val svc = service() ?: return false
        val scrollable = svc.snapshotUiTree()?.nodes?.lastOrNull { it.isScrollable }
        val live = scrollable?.let { findLiveNode(it) }
        return if (live != null) svc.scrollForward(live) else svc.swipe(540f, 1400f, 540f, 400f)
    }

    fun scrollBackward(): Boolean {
        val svc = service() ?: return false
        val scrollable = svc.snapshotUiTree()?.nodes?.lastOrNull { it.isScrollable }
        val live = scrollable?.let { findLiveNode(it) }
        return if (live != null) svc.scrollBackward(live) else svc.swipe(540f, 400f, 540f, 1400f)
    }

    fun swipe(startX: Float, startY: Float, endX: Float, endY: Float): Boolean =
        service()?.swipe(startX, startY, endX, endY) ?: false

    fun pressBack(): Boolean = service()?.pressBack() ?: false
    fun pressHome(): Boolean = service()?.pressHome() ?: false
    fun openRecents(): Boolean = service()?.openRecents() ?: false

    /**
     * Скриншот имеет bounded timeout: Accessibility API обязан ответить
     * callback-ом, иначе action не должен оставлять корутину навсегда.
     */
    suspend fun takeScreenshot(timeoutMs: Long = 8000L): Bitmap? =
        withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val svc = service()
                if (svc == null) {
                    cont.resume(null)
                    return@suspendCancellableCoroutine
                }
                svc.captureScreen { bitmap ->
                    if (cont.isActive) cont.resume(bitmap)
                }
            }
        }

    /**
     * Поиск элемента в текущем UI tree.
     * @param query текст, contentDescription, id или resource id
     */
    fun findElement(query: String): UiNode? {
        val tree = uiTree() ?: return null
        val q = query.trim().lowercase()
        return tree.nodes.firstOrNull { it.visibleText.lowercase() == q && it.isClickable }
            ?: tree.nodes.firstOrNull { it.visibleText.lowercase().contains(q) }
            ?: tree.nodes.firstOrNull { it.id.equals(query, ignoreCase = true) }
            ?: tree.nodes.firstOrNull { it.className.lowercase().contains(q) }
    }

    fun findNodeById(id: String): AccessibilityNodeInfo? {
        val root = service()?.rootInActiveWindow ?: return null
        return findByViewId(root, id)
    }

    /**
     * Поиск поля ввода на текущем экране. Используется в длинных
     * Hands-цепочках (написать сообщение в мессенджере): поле ввода
     * определяется по классу EditText и доступности для правки.
     */
    fun findEditableField(): UiNode? {
        val tree = uiTree() ?: return null
        return tree.nodes.firstOrNull { isEditable(it) }
    }

    private fun isEditable(node: UiNode): Boolean {
        if (!node.isEnabled) return false
        if (node.isPassword) return true
        val cls = node.className.lowercase()
        if (!cls.contains("edittext") && !cls.contains("textview") &&
            !cls.contains("autofill") && !cls.contains("input")) return false
        return node.isClickable || node.isFocused || node.text.isNotBlank()
    }

    /**
     * Поиск кнопки отправки на текущем экране (для мессенджеров):
     * кликабельный элемент, чей id / contentDescription / текст
     * содержит типичные признаки send-кнопки.
     */
    fun findSendButton(): UiNode? {
        val tree = uiTree() ?: return null
        return tree.nodes.firstOrNull { node ->
            if (!node.isClickable || !node.isEnabled) false
            else SEND_MARKERS.any { marker ->
                node.id.contains(marker, ignoreCase = true) ||
                    node.contentDescription.contains(marker, ignoreCase = true) ||
                    node.text.contains(marker, ignoreCase = true)
            }
        }
    }

    private val SEND_MARKERS = listOf(
        "send", "отправить", "отправка", "sendmessage", "submit_message",
        "action_send", "btn_send", "ui_send"
    )

    private fun findByViewId(node: AccessibilityNodeInfo, id: String): AccessibilityNodeInfo? {
        if (node.viewIdResourceName?.equals(id, ignoreCase = true) == true) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findByViewId(child, id)?.let { return it }
        }
        return null
    }

    /**
     * Проверка результата: действительно ли изменилось состояние экрана.
     * Используется в доказательной цепочке как RESULT_VERIFIED.
     */
    fun verifyPackage(expectedPackage: String): Boolean =
        currentPackage().equals(expectedPackage, ignoreCase = true)

    /**
     * Ожидание перехода на указанный пакет. Poll по UI tree, без скрытых механизмов.
     */
    suspend fun waitForPackage(packageName: String, timeoutMs: Long = 3000L): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (currentPackage().equals(packageName, ignoreCase = true)) return true
            kotlinx.coroutines.delay(150)
        }
        return currentPackage().equals(packageName, ignoreCase = true)
    }

    fun verifyTextVisible(text: String): Boolean {
        val tree = uiTree() ?: return false
        return tree.nodes.any { it.visibleText.contains(text, ignoreCase = true) }
    }

    /**
     * Строгое пост-условие для отправки сообщения: текст должен быть виден
     * в элементе, который не определяется как поле ввода. Наличие того же
     * текста в EditText после клика «Отправить» не доказывает отправку.
     */
    fun verifyTextVisibleInNonEditableNode(text: String): Boolean {
        val needle = text.trim()
        if (needle.isBlank()) return false
        val tree = uiTree() ?: return false
        return tree.nodes.any { node ->
            node.visibleText.contains(needle, ignoreCase = true) && !isEditable(node)
        }
    }

    /**
     * Пост-условие для typeText: указанный узел теперь содержит введённый текст.
     * Это настоящая RESULT_VERIFIED-проверка, а не «команда отправлена».
     */
    fun verifyTextEntered(node: UiNode, text: String): Boolean {
        val live = findLiveNode(node) ?: return false
        val current = live.text?.toString() ?: live.contentDescription?.toString().orEmpty()
        return current.contains(text)
    }

    /**
     * Пост-условие для clearText: поле ввода действительно пусто.
     */
    fun verifyTextEmpty(node: UiNode): Boolean {
        val live = findLiveNode(node) ?: return false
        return live.text?.toString().isNullOrBlank()
    }

    /**
     * Проверка, что элемент всё ещё присутствует на экране (для swipe/scroll —
     * содержимое могло измениться, но дерево осталось валидным).
     */
    /**
     * Стабильный отпечаток текущего UI tree.
     *
     * Количество узлов недостаточно: после scroll/swipe/bаck/home дерево
     * часто имеет то же число элементов, хотя тексты/позиции изменились.
     */
    fun treeFingerprint(): Long {
        val tree = uiTree() ?: return 0L
        var hash = 1125899906842597L
        tree.nodes.forEach { node ->
            hash = 31L * hash + node.className.hashCode()
            hash = 31L * hash + node.visibleText.hashCode()
            hash = 31L * hash + node.id.hashCode()
            hash = 31L * hash + node.bounds.left
            hash = 31L * hash + node.bounds.top
            hash = 31L * hash + node.bounds.right
            hash = 31L * hash + node.bounds.bottom
            hash = 31L * hash + if (node.isFocused) 1 else 0
            hash = 31L * hash + if (node.isEnabled) 1 else 0
        }
        return hash
    }

    fun verifyTreeChanged(beforeFingerprint: Long): Boolean {
        val after = treeFingerprint()
        return after != 0L && after != beforeFingerprint
    }

    /**
     * Снимок количества узлов — оставлен для диагностических тестов.
     */
    fun nodeCount(): Int = uiTree()?.nodes?.size ?: -1
}
