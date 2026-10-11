package com.svetlana.home.hands

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
 *
 * Безопасность: нажатие/ввод/очистка выполняются ТОЛЬКО на том живом элементе, который
 * точно совпадает со снимком ([findExactNode]: класс, границы, id и текст). Если за это время
 * элемент сдвинули или подменили похожим (другая строка списка с тем же id, кнопка с тем же
 * текстом) — действие не выполняется.
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
     * Поиск «живого» AccessibilityNodeInfo, соответствующего UiNode из снапшота:
     * id → текст → contentDescription → класс + bounds (аудит п.12). Только для ПРОВЕРОК после
     * действия (текст поля уже изменился); для самих действий — [findExactNode].
     */
    private fun findLiveNode(node: UiNode): AccessibilityNodeInfo? {
        val svc = service() ?: return null
        val root = svc.rootInActiveWindow ?: return null
        if (node.id.isNotBlank()) {
            findByViewId(root, node.id)?.let { return it }
        }
        val byText = node.visibleText.takeIf { it.isNotBlank() }?.let { text ->
            findByText(root, text)
        }
        if (byText != null) return byText
        return findByClassAndBounds(root, node)
    }

    /** Живой элемент, совпадающий со снимком во всём: класс, границы, id, текст, описание, пароль. */
    private fun findExactNode(node: UiNode): AccessibilityNodeInfo? {
        val svc = service() ?: return null
        val root = svc.rootInActiveWindow ?: return null
        return findExact(root, node, 0)
    }

    private fun findExact(n: AccessibilityNodeInfo, target: UiNode, depth: Int): AccessibilityNodeInfo? {
        if (depth > MAX_DEPTH) return null
        val bounds = Rect().also { n.getBoundsInScreen(it) }
        if (bounds == target.bounds &&
            n.className?.toString().orEmpty() == target.className &&
            n.viewIdResourceName.orEmpty() == target.id &&
            n.text?.toString().orEmpty() == target.text &&
            n.contentDescription?.toString().orEmpty() == target.contentDescription &&
            n.isPassword == target.isPassword
        ) return n
        for (i in 0 until n.childCount) {
            val child = n.getChild(i) ?: continue
            findExact(child, target, depth + 1)?.let { return it }
        }
        return null
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
        val live = findExactNode(node) ?: return false
        return svc.click(live)
    }

    fun longClick(node: UiNode): Boolean {
        val svc = service() ?: return false
        val live = findExactNode(node) ?: return false
        return svc.longClick(live)
    }

    fun inputText(node: UiNode, text: String): Boolean {
        val svc = service() ?: return false
        val live = findExactNode(node) ?: return false
        return svc.inputText(live, text)
    }

    fun clearText(node: UiNode): Boolean {
        val svc = service() ?: return false
        val live = findExactNode(node) ?: return false
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
     * Скриншот с bounded timeout.
     *
     * Приватность (default-deny, [ScreenshotPolicy]):
     * - снимается только доверенное приложение (своё, настройки, Mobile Harness) или
     *   то окно, на снимок которого пользователь только что дал одноразовое согласие;
     * - банки, платёжки и менеджеры паролей не снимаются никогда;
     * - если за время снимка сменилось приложение или окно, либо прочитанный экран
     *   принадлежит другому пакету — снимок не отдаётся;
     * - поля паролей/PIN/кодов/карт закрашиваются; если закрасить нельзя — не отдаётся.
     * Окна с FLAG_SECURE Android и так отдаёт чёрными.
     */
    suspend fun takeScreenshot(timeoutMs: Long = 8000L): Bitmap? {
        val pkg = currentPackage()
        val window = ScreenshotPolicy.windowKeyOf(uiTree())
        if (!ScreenshotPolicy.mayCapture(pkg, context.packageName, window)) return null
        val raw: Bitmap = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<Bitmap?> { cont ->
                val svc = service()
                if (svc == null) {
                    cont.resume(null)
                    return@suspendCancellableCoroutine
                }
                svc.captureScreen { bitmap ->
                    if (cont.isActive) cont.resume(bitmap)
                }
            }
        } ?: return null
        // Пока делали снимок, на экран могло выйти другое приложение или окно.
        if (!currentPackage().equals(pkg, ignoreCase = true)) return null
        val tree = uiTree() ?: return null
        if (ScreenshotPolicy.windowKeyOf(tree) != window) return null
        return redactSecrets(raw, pkg, tree)
    }

    private fun redactSecrets(raw: Bitmap, expectedPackage: String, tree: UiTree): Bitmap? {
        // Экран принадлежит другому приложению — неизвестно, где секреты.
        if (tree.packageName.isNotBlank() && !tree.packageName.equals(expectedPackage, ignoreCase = true)) return null
        val secretNodes = tree.nodes.filter {
            it.isPassword || it.text.contains(SensitiveText.MASK) ||
                it.contentDescription.contains(SensitiveText.MASK)
        }
        // Секретное поле без координат: закрасить нечего — не отдаём.
        if (secretNodes.any { it.bounds.isEmpty }) return null
        if (secretNodes.isEmpty()) return raw
        val copy = raw.copy(Bitmap.Config.ARGB_8888, true) ?: return null
        val canvas = Canvas(copy)
        val paint = Paint().apply {
            color = Color.BLACK
            style = Paint.Style.FILL
        }
        secretNodes.forEach { canvas.drawRect(it.bounds, paint) }
        return copy
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
     * Поле ввода на текущем экране (для мессенджеров). Поля паролей/PIN/карт сюда
     * не попадают: сообщение туда не печатаем.
     */
    fun findEditableField(): UiNode? {
        val tree = uiTree() ?: return null
        return tree.nodes.firstOrNull { isEditable(it) && !it.isPassword }
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
     * Кнопка отправки на текущем экране (для мессенджеров).
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

    fun verifyPackage(expectedPackage: String): Boolean =
        currentPackage().equals(expectedPackage, ignoreCase = true)

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
     * Строгое пост-условие для отправки сообщения: текст виден в элементе, который
     * не является полем ввода.
     */
    fun verifyTextVisibleInNonEditableNode(text: String): Boolean {
        val needle = text.trim()
        if (needle.isBlank()) return false
        val tree = uiTree() ?: return false
        return tree.nodes.any { node ->
            node.visibleText.contains(needle, ignoreCase = true) && !isEditable(node)
        }
    }

    fun verifyTextEntered(node: UiNode, text: String): Boolean {
        val live = findLiveNode(node) ?: return false
        val current = live.text?.toString() ?: live.contentDescription?.toString().orEmpty()
        return current.contains(text)
    }

    fun verifyTextEmpty(node: UiNode): Boolean {
        val live = findLiveNode(node) ?: return false
        return live.text?.toString().isNullOrBlank()
    }

    /**
     * Стабильный отпечаток текущего UI tree (тексты, позиции, фокус).
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

    fun nodeCount(): Int = uiTree()?.nodes?.size ?: -1

    private companion object { const val MAX_DEPTH = 60 }
}
