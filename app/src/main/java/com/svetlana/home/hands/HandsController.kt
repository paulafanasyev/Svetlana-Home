package com.svetlana.home.hands

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.svetlana.home.control.ActionRiskPolicy
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
 * Безопасность: нажатие/ввод/очистка выполняются ТОЛЬКО на том живом элементе, который точно
 * совпадает со снимком ([findExactNode]: класс, границы, id, текст, описание — в том
 * же виде, что и в снимке, с вычищенными секретами) и принадлежит ожидаемому приложению
 * (пакет проверяется на самом живом элементе, а передний план — ещё раз непосредственно
 * перед самим действием: оверлей, выскочивший между проверкой и нажатием, не получит
 * действие). Если элемент сдвинули или подменили похожим — действие не выполняется.
 * Если сам элемент не нажимается, нажатие получает ближайший нажимаемый родитель ([clickTarget])
 * — только из того же приложения и только если его собственная подпись не опаснее проверенной
 * (иначе риск оценивали по одной подписи, а нажатие ушло бы другой кнопке). Ввод и очистка
 * идут прямо в этот элемент (ACTION_SET_TEXT) и только когда фокус ввода именно в нём
 * ([focusedExactNode]): текст не уйдёт ни «куда сейчас фокус», ни в поле без фокуса.
 * Прокрутка привязана к приложению: и действие на элементе, и запасной свайп — только если
 * на экране именно ожидаемое приложение.
 * Проверка результата ввода/очистки читает то же самое поле ([findSameField]: класс, id, положение;
 * если таких полей несколько — не подтверждено) и только из того же приложения, в котором было действие.
 */
class HandsController(private val context: Context) {

    private val permissionManager = PermissionManager(context)

    /** Приложение последнего ввода/очистки: из него (и только из него) читается подтверждение. */
    @Volatile
    private var lastInputPackage: String? = null

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

    private fun inPkg(n: AccessibilityNodeInfo, pkg: String?): Boolean =
        pkg == null || n.packageName?.toString().equals(pkg, ignoreCase = true)

    /** На переднем плане ожидаемое приложение (и по событиям сервиса, и по корню активного окна). */
    private fun foregroundIs(pkg: String?): Boolean {
        if (pkg == null) return true
        if (!currentPackage().equals(pkg, ignoreCase = true)) return false
        val root = service()?.rootInActiveWindow ?: return false
        return inPkg(root, pkg)
    }

    /**
     * Поиск «живого» AccessibilityNodeInfo, соответствующего UiNode из снапшота:
     * id → текст → contentDescription → класс + bounds (аудит п.12). Только для прокрутки;
     * для самих действий — [findExactNode], для проверки ввода — [findSameField].
     * Если задан [inPackage] — окно и найденный элемент должны принадлежать этому приложению.
     */
    private fun findLiveNode(node: UiNode, inPackage: String? = null): AccessibilityNodeInfo? {
        val svc = service() ?: return null
        val root = svc.rootInActiveWindow ?: return null
        if (!inPkg(root, inPackage)) return null
        val found = run {
            if (node.id.isNotBlank()) {
                findByViewId(root, node.id)?.let { return@run it }
            }
            node.visibleText.takeIf { it.isNotBlank() }?.let { text -> findByText(root, text) }
                ?: findByClassAndBounds(root, node)
        } ?: return null
        return found.takeIf { inPkg(it, inPackage) }
    }

    /**
     * Живой элемент, совпадающий со снимком во всём: класс, границы, id, текст, описание, пароль.
     * Если задан [inPackage] — и окно, и сам элемент должны принадлежать этому приложению.
     */
    private fun findExactNode(node: UiNode, inPackage: String?): AccessibilityNodeInfo? {
        val svc = service() ?: return null
        val root = svc.rootInActiveWindow ?: return null
        if (!inPkg(root, inPackage)) return null
        val live = findExact(root, node, 0) ?: return null
        return live.takeIf { inPkg(it, inPackage) }
    }

    /**
     * Точный живой элемент ([findExactNode]), в котором стоит фокус ввода. Если фокуса в нём нет —
     * ставим его сами (ACTION_FOCUS) и перечитываем состояние: элемент должен остаться тем же
     * (снимок, пакет) и действительно получить фокус, а на переднем плане должно остаться
     * то же приложение (корень активного окна перечитывается заново). Иначе — ничего не вводим.
     */
    private fun focusedExactNode(node: UiNode, inPackage: String?): AccessibilityNodeInfo? {
        val live = findExactNode(node, inPackage) ?: return null
        if (live.isFocused) return live.takeIf { foregroundIs(inPackage) }
        if (!live.performAction(AccessibilityNodeInfo.ACTION_FOCUS)) return null
        if (!live.refresh()) return null
        if (!(live.isFocused && inPkg(live, inPackage) && sameAsSnapshot(live, node))) return null
        return live.takeIf { foregroundIs(inPackage) }
    }

    /**
     * Что реально получит нажатие: сам проверенный элемент или, если он не нажимается,
     * ближайший нажимаемый родитель (не дальше [MAX_CLICK_HOPS] уровней). Родитель должен быть
     * из того же приложения, а его собственная подпись (текст, описание, id) — не опасной,
     * если подпись проверенного элемента безопасна: иначе риск и согласие считались по
     * безобидной подписи, а нажатие ушло бы «опасной» кнопке — отказ.
     */
    private fun clickTarget(live: AccessibilityNodeInfo, inPackage: String?): AccessibilityNodeInfo? {
        if (live.isClickable) return live
        var target: AccessibilityNodeInfo? = live.parent
        var hops = 1
        while (target != null && !target.isClickable) {
            if (++hops > MAX_CLICK_HOPS) return null
            target = target.parent
        }
        if (target == null || !inPkg(target, inPackage)) return null
        val escalates = ActionRiskPolicy.isDangerousLabel(labelOf(target)) &&
            !ActionRiskPolicy.isDangerousLabel(labelOf(live))
        return target.takeIf { !escalates }
    }

    private fun labelOf(n: AccessibilityNodeInfo): String =
        "${n.text ?: ""} ${n.contentDescription ?: ""} ${n.viewIdResourceName ?: ""}"

    /**
     * Живое поле для ПРОВЕРКИ после ввода/очистки: тот же класс и id и те же левая/правая
     * границы (поле может вырасти по высоте от длинного текста), а без id — те же класс и
     * границы целиком. Текст не сравнивается (он уже изменился). Подходящее поле должно
     * быть ЕДИНСТВЕННЫМ: если таких несколько (список одинаковых полей), нельзя сказать,
     * какое из них наше, — результат не подтверждается. Окно и поле — из [inPackage].
     */
    private fun findSameField(node: UiNode, inPackage: String?): AccessibilityNodeInfo? {
        val svc = service() ?: return null
        val root = svc.rootInActiveWindow ?: return null
        if (!inPkg(root, inPackage)) return null
        val found = mutableListOf<AccessibilityNodeInfo>()
        collectFields(root, node, 0, found)
        return found.singleOrNull()?.takeIf { inPkg(it, inPackage) }
    }

    private fun collectFields(n: AccessibilityNodeInfo, target: UiNode, depth: Int, out: MutableList<AccessibilityNodeInfo>) {
        if (depth > MAX_DEPTH || out.size > 1) return
        if (n.className?.toString().orEmpty() == target.className && (n.viewIdResourceName ?: "") == target.id) {
            val b = Rect().also { n.getBoundsInScreen(it) }
            val samePlace = if (target.id.isNotBlank()) b.left == target.bounds.left && b.right == target.bounds.right
                else b == target.bounds
            if (samePlace) out += n
        }
        for (i in 0 until n.childCount) {
            val child = n.getChild(i) ?: continue
            collectFields(child, target, depth + 1, out)
        }
    }

    /** Сравнение в том же виде, что и в снимке (текст паролей/кодов вычищен одинаково). */
    private fun sameAsSnapshot(n: AccessibilityNodeInfo, target: UiNode): Boolean {
        val bounds = Rect().also { n.getBoundsInScreen(it) }
        if (bounds != target.bounds) return false
        if (n.className?.toString().orEmpty() != target.className) return false
        val id = n.viewIdResourceName ?: ""
        if (id != target.id) return false
        val description = n.contentDescription?.toString() ?: ""
        val hint = n.hintText?.toString() ?: ""
        val password = n.isPassword || SensitiveText.isSensitiveField(false, id, description, hint)
        if (password != target.isPassword) return false
        if (SensitiveText.maskSecrets(description) != target.contentDescription) return false
        return SensitiveText.redact(n.text?.toString() ?: "", n.isPassword, id, description, hint) == target.text
    }

    private fun findExact(n: AccessibilityNodeInfo, target: UiNode, depth: Int): AccessibilityNodeInfo? {
        if (depth > MAX_DEPTH) return null
        if (sameAsSnapshot(n, target)) return n
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

    // Каждое действие ниже: поиск точного живого элемента → проверка переднего плана прямо перед
    // вызовом сервиса (между ними больше ничего не делается) → действие.

    fun clickNode(node: UiNode, inPackage: String? = null): Boolean {
        val svc = service() ?: return false
        val live = findExactNode(node, inPackage) ?: return false
        val target = clickTarget(live, inPackage) ?: return false
        if (!foregroundIs(inPackage)) return false
        return svc.click(target)
    }

    fun longClick(node: UiNode, inPackage: String? = null): Boolean {
        val svc = service() ?: return false
        val live = findExactNode(node, inPackage) ?: return false
        if (!foregroundIs(inPackage)) return false
        return svc.longClick(live)
    }

    fun inputText(node: UiNode, text: String, inPackage: String? = null): Boolean {
        lastInputPackage = inPackage
        val svc = service() ?: return false
        val live = focusedExactNode(node, inPackage) ?: return false
        if (!foregroundIs(inPackage)) return false
        return svc.inputText(live, text)
    }

    fun clearText(node: UiNode, inPackage: String? = null): Boolean {
        lastInputPackage = inPackage
        val svc = service() ?: return false
        val live = focusedExactNode(node, inPackage) ?: return false
        if (!foregroundIs(inPackage)) return false
        return svc.clearText(live)
    }

    /**
     * Прокрутка только в [inPackage] (вызывающие передают закреплённый пакет); без него — в
     * приложении, которое было на экране в момент вызова.
     */
    fun scrollForward(inPackage: String? = null): Boolean = scroll(inPackage, forward = true)

    fun scrollBackward(inPackage: String? = null): Boolean = scroll(inPackage, forward = false)

    /**
     * Снимок должен быть из того же приложения, живой прокручиваемый элемент и окно — тоже;
     * передний план проверяется прямо перед прокруткой и перед слепым свайпом-запаской.
     */
    private fun scroll(inPackage: String?, forward: Boolean): Boolean {
        val svc = service() ?: return false
        val pkg = inPackage ?: currentPackage().takeIf { it.isNotBlank() } ?: return false
        if (!foregroundIs(pkg)) return false
        val tree = svc.snapshotUiTree()
        if (tree != null && tree.packageName.isNotBlank() && !tree.packageName.equals(pkg, ignoreCase = true)) return false
        val scrollable = tree?.nodes?.lastOrNull { it.isScrollable }
        val live = scrollable?.let { findLiveNode(it, pkg) }
        if (!foregroundIs(pkg)) return false
        if (live != null) return if (forward) svc.scrollForward(live) else svc.scrollBackward(live)
        return if (forward) svc.swipe(540f, 1400f, 540f, 400f) else svc.swipe(540f, 400f, 540f, 1400f)
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

    /** Текст действительно в ТОМ ЖЕ поле — читается только из того приложения, в котором был ввод. */
    fun verifyTextEntered(node: UiNode, text: String, inPackage: String? = lastInputPackage): Boolean {
        val live = findSameField(node, inPackage) ?: return false
        val current = live.text?.toString() ?: live.contentDescription?.toString().orEmpty()
        return current.contains(text)
    }

    /** ТО ЖЕ поле пусто — читается только из того приложения, в котором была очистка. */
    fun verifyTextEmpty(node: UiNode, inPackage: String? = lastInputPackage): Boolean {
        val live = findSameField(node, inPackage) ?: return false
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

    private companion object {
        const val MAX_DEPTH = 60
        /** Насколько высоко можно подняться к нажимаемому родителю от проверенного элемента. */
        const val MAX_CLICK_HOPS = 8
    }
}
