package com.svetlana.home.control

import android.content.Context
import com.svetlana.home.R
import com.svetlana.home.apps.launcher.AppResolver
import com.svetlana.home.core.ServiceLocator
import com.svetlana.home.memory.HistoryCategory
import com.svetlana.home.memory.HistoryManager
import com.svetlana.home.permissions.PermissionManager

/**
 * ActionRouter — центральная маршрутизация команд.
 *
 * Pipeline ТЗ §18:
 *   Voice/Text → Intent → Target App Resolver → Permission Check
 *   → Action Router → Native API / Hands → Action → Verification → Result
 *
 * Здесь нет доступа к shell, root или скрытым командам.
 */
class ActionRouter(
    private val context: Context,
    private val engine: AppControlEngine,
    private val intentResolver: IntentResolver,
    private val permissionManager: PermissionManager,
    private val historyManager: HistoryManager
) {

    private fun stripWakeWord(low: String): String {
        val words = listOf("света ", "светочка ", "светлана ", "свет,")
        for (w in words) if (low.startsWith(w)) return low.substring(w.length)
        return low
    }

    private fun currentPackageSafe(): String =
        ServiceLocator.hands.currentPackage().ifBlank { "unknown" }

    private fun currentPackageForScreenshot(): String = currentPackageSafe()

    /**
     * Выполнить разобранное действие с проверкой разрешений.
     *
     * Подтверждение привязано к КОНКРЕТНОМУ действию со всеми параметрами и к
     * реальному элементу на экране: `confirmed = true` срабатывает, только если
     * именно этот вопрос был задан пользователю. Если экран подменили между
     * вопросом и ответом (под тем же именем теперь другой элемент) — спрашиваем
     * снова. Вопрос всегда показывает точные параметры ([ConfirmationText]).
     */
    suspend fun execute(action: SvetlanaAction, confirmed: Boolean = false): ActionResult =
        executeInternal(action, confirmed, chainApproved = false)

    private suspend fun executeInternal(action: SvetlanaAction, confirmed: Boolean, chainApproved: Boolean): ActionResult {
        // ТЗ §58: опасные действия (звонки, SMS, публикации) — явное подтверждение.
        // Внутри уже подтверждённой цепочки шаги были показаны пользователю
        // поимённо, поэтому повторно по политике не спрашиваем; но элемент на
        // экране проверяется всегда — его при подтверждении цепочки ещё не было видно.
        val policyRisk = ActionRiskPolicy.requiresConfirmation(action) && !chainApproved
        // Клик/ввод: проверяем РЕАЛЬНЫЙ элемент на экране. «Кнопка» может
        // оказаться «Удалить», а «поле» — полем пароля (prompt injection).
        val target = screenTarget(action)
        val targetRisk = target != null && target.dangerous
        if (policyRisk || targetRisk) {
            val key = Approval(action, target?.label)
            if (!(confirmed && consumeApproval(key))) {
                rememberApproval(key)
                historyManager.record(HistoryCategory.CONFIRMATIONS, "Запрошено подтверждение: ${action.name}")
                val question = ConfirmationText.question(
                    action, target?.shown, secretField = target != null && target.typing && target.dangerous)
                return ActionResult(action, false,
                    "${context.getString(R.string.reply_confirm_dangerous)}\n$question",
                    way = if (target != null) "hands" else "unknown", requiresUserConfirmation = true)
            }
        }

        val result = when (action) {
            is SvetlanaAction.OpenApp -> engine.openApp(action.target)
            is SvetlanaAction.OpenAppScreen -> engine.openApp(action.target)
            is SvetlanaAction.Click -> engine.click(action.target, action.element)
            is SvetlanaAction.LongClick -> engine.longClick(action.target, action.element)
            is SvetlanaAction.Swipe -> engine.swipe(action.target, action.direction)
            is SvetlanaAction.Scroll -> engine.scroll(action.target, action.direction)
            is SvetlanaAction.TypeText -> engine.typeText(action.target, action.element, action.text)
            is SvetlanaAction.ClearText -> engine.clearText(action.target, action.element)
            is SvetlanaAction.ReadScreen -> engine.readScreen(action.target)
            is SvetlanaAction.FindElement -> engine.readScreen(action.target)
            is SvetlanaAction.TakeScreenshot -> engine.takeScreenshotAction(action.target)
            SvetlanaAction.PressBack -> engine.pressBack()
            SvetlanaAction.PressHome -> engine.pressHome()
            SvetlanaAction.OpenRecents -> engine.openRecents()
            SvetlanaAction.OpenSettings -> engine.openSettings()
            is SvetlanaAction.MakeCall -> engine.makeCall(action.contact)
            is SvetlanaAction.SendMessage -> engine.sendMessage(action.contact, action.text)
            is SvetlanaAction.Share -> engine.share(action.text)
            // Длинная многошаговая Hands-цепочка: открыть мессенджер →
            // найти контакт → напечатать → отправить → проверить.
            is SvetlanaAction.ComposeMessage ->
                engine.composeMessage(action.appTarget, action.contact, action.text)
            // Последовательность шагов: каждый выполняется только после
            // успеха предыдущего. Провал любого шага останавливает цепочку.
            is SvetlanaAction.Compound -> executeCompound(action, confirmed)
        }

        if (result.requiresUserConfirmation) return result
        historyManager.record(
            if (result.success) HistoryCategory.COMMANDS else HistoryCategory.ERRORS,
            "${action.name} → ${if (result.success) "OK" else "FAILED"}: ${result.message}"
        )
        return result
    }

    private class ScreenTarget(val label: String, val shown: String, val typing: Boolean, val dangerous: Boolean)

    /** Заданный пользователю вопрос: действие со всеми параметрами + элемент на экране. */
    private data class Approval(val action: SvetlanaAction, val targetLabel: String?)

    /** Вопросы, на которые ждём ответа (несколько окон чата/главного экрана). */
    private val pendingApprovals = LinkedHashSet<Approval>()

    @Synchronized
    private fun rememberApproval(key: Approval) {
        pendingApprovals.remove(key)
        pendingApprovals.add(key)
        while (pendingApprovals.size > MAX_PENDING) pendingApprovals.remove(pendingApprovals.first())
    }

    /** Одноразово: подтверждение нельзя использовать повторно. */
    @Synchronized
    private fun consumeApproval(key: Approval): Boolean = pendingApprovals.remove(key)

    /** Цепочка, остановленная на шаге, который потребовал подтверждения: продолжаем с него, не повторяя выполненные шаги. */
    @Volatile
    private var resumeChain: Pair<SvetlanaAction.Compound, Int>? = null

    /**
     * Реальный элемент на экране для Click/LongClick/TypeText и его опасность:
     * подпись «Удалить/Оплатить/…» или поле пароля/PIN/карты.
     */
    private fun screenTarget(action: SvetlanaAction): ScreenTarget? {
        val (element, typing) = when (action) {
            is SvetlanaAction.Click -> action.element to false
            is SvetlanaAction.LongClick -> action.element to false
            is SvetlanaAction.TypeText -> action.element to true
            else -> return null
        }
        val hands = ServiceLocator.hands
        if (!hands.isActive) return null
        val node = hands.findElement(element) ?: return null
        val label = "${hands.currentPackage()} ${node.text} ${node.contentDescription} ${node.id} ${node.bounds}"
        val semantic = "${node.text} ${node.contentDescription} ${node.id}"
        val dangerous = if (typing) node.isPassword || ActionRiskPolicy.isSensitiveFieldLabel(semantic)
        else ActionRiskPolicy.isDangerousLabel(semantic)
        val shown = node.visibleText.ifBlank { element }.take(40)
        return ScreenTarget(label, shown, typing, dangerous)
    }

    /**
     * Полный цикл: голос/текст → действие → результат.
     * Возвращает результат действия или null, если это не команда управления
     * (значит, нужно передать в AI-чат).
     */
    suspend fun route(command: String, confirmed: Boolean = false): ActionResult? {
        val action = CommandParser.parse(command)
        if (action != null) return execute(action, confirmed)

        // Аудит §7: декларативный каталог системных команд.
        // «Открой настройки Wi-Fi», «открой камеру» и т.д. — это не приложения,
        // а системные intent. CommandParser их не разбирает, здесь — fallback.
        val systemIntent = ServiceLocator.appResolver.resolveSystem(command)
        if (systemIntent is AppResolver.ResolveResult.SystemAction) {
            return engine.openSystemIntent(systemIntent.intent)
        }
        return null
    }

    /**
     * Проверка разрешений, необходимых для действия.
     */
    fun missingPermissionsFor(action: SvetlanaAction): List<String> {
        val needed = mutableListOf<String>()
        when (action) {
            is SvetlanaAction.MakeCall -> needed.add(android.Manifest.permission.CALL_PHONE)
            is SvetlanaAction.SendMessage -> needed.add(android.Manifest.permission.SEND_SMS)
            is SvetlanaAction.TypeText, is SvetlanaAction.Click, is SvetlanaAction.Swipe,
            is SvetlanaAction.Scroll, is SvetlanaAction.TakeScreenshot, is SvetlanaAction.ReadScreen,
            // Длинные Hands-цепочки требуют активного Accessibility
            is SvetlanaAction.ComposeMessage, is SvetlanaAction.Compound -> {
                if (!permissionManager.accessibilityEnabled()) needed.add("hands")
            }
            else -> { /* остальные не требуют runtime-разрешений */ }
        }
        return needed.filterNot { perm ->
            if (perm == "hands") permissionManager.accessibilityEnabled()
            else permissionManager.isGranted(perm)
        }
    }

    /**
     * Доступность способа управления для действия.
     */
    fun availabilityFor(action: SvetlanaAction): Availability {
        return when (action) {
            is SvetlanaAction.OpenApp, is SvetlanaAction.OpenAppScreen -> Availability.AVAILABLE
            is SvetlanaAction.MakeCall, is SvetlanaAction.SendMessage,
            is SvetlanaAction.Share -> Availability.AVAILABLE
            SvetlanaAction.PressBack, SvetlanaAction.PressHome, SvetlanaAction.OpenRecents,
            SvetlanaAction.OpenSettings -> {
                if (ServiceLocator.hands.isActive) Availability.AVAILABLE
                else Availability.NEEDS_HANDS
            }
            else -> if (ServiceLocator.hands.isActive) Availability.AVAILABLE else Availability.NEEDS_HANDS
        }
    }

    /**
     * Выполнение последовательности действий (Compound).
     *
     * Каждый шаг выполняется только после успеха предыдущего — это
     * ключевое правило для длинных запросов: нельзя нажимать «Отправить»,
     * если текст не введён. Доказательная цепочка объединяет все шаги.
     *
     * Если шаг потребовал подтверждения (опасный элемент появился на экране),
     * после ответа пользователя цепочка продолжается С ЭТОГО шага: уже
     * выполненные шаги не повторяются (иначе «Удалить» нажали бы дважды).
     */
    private suspend fun executeCompound(action: SvetlanaAction.Compound, confirmed: Boolean): ActionResult {
        val resume = resumeChain
        val start = if (confirmed && resume != null && resume.first == action) resume.second else 0
        resumeChain = null

        val allProof = mutableListOf<com.svetlana.home.core.ProofStep>()
        allProof += com.svetlana.home.core.ProofStep(
            com.svetlana.home.core.ProofStage.PLAN, com.svetlana.home.core.StepStatus.OK,
            "PLAN_TARGET=COMPOUND steps=${action.steps.size} (${action.steps.joinToString { it.name }})" +
                if (start > 0) " resume_from=${start + 1}" else "")

        val messages = mutableListOf<String>()
        for (index in start until action.steps.size) {
            val step = action.steps[index]
            // Цепочка целиком уже показана и подтверждена (или в ней нет опасных
            // по политике шагов), но элемент на экране проверяется в каждом шаге.
            val stepResult = executeInternal(step, confirmed, chainApproved = true)
            allProof += stepResult.proof
            if (stepResult.requiresUserConfirmation) {
                // Опасный элемент обнаружился только на экране: спрашиваем пользователя
                // и запоминаем место, с которого продолжить после ответа.
                resumeChain = action to index
                if (ActionRiskPolicy.requiresConfirmation(action)) rememberApproval(Approval(action, null))
                return ActionResult(action, false, stepResult.message, allProof, stepResult.way,
                    requiresUserConfirmation = true)
            }
            if (!stepResult.success) {
                allProof += com.svetlana.home.core.ProofStep(
                    com.svetlana.home.core.ProofStage.RESULT_VERIFIED,
                    com.svetlana.home.core.StepStatus.FAILED,
                    "PLAN_RESULT=NOT VERIFIED: шаг ${index + 1} (${step.name}) провален")
                historyManager.record(HistoryCategory.ERRORS,
                    "Compound прерван на шаге ${index + 1}: ${step.name}")
                return ActionResult(action, false,
                    "Цепочка прервана на шаге ${index + 1}: ${stepResult.message}",
                    allProof, stepResult.way)
            }
            messages += stepResult.message
        }
        allProof += com.svetlana.home.core.ProofStep(
            com.svetlana.home.core.ProofStage.RESULT_VERIFIED,
            com.svetlana.home.core.StepStatus.OK,
            "PLAN_RESULT=VERIFIED все ${action.steps.size} шагов выполнены")
        return ActionResult(action, true,
            messages.joinToString(". "), allProof, "hands")
    }

    enum class Availability { AVAILABLE, NEEDS_HANDS, UNAVAILABLE }

    private companion object {
        const val MAX_PENDING = 8
    }
}
