package com.svetlana.home.control

import android.content.Context
import com.svetlana.home.R
import com.svetlana.home.apps.launcher.AppResolver
import com.svetlana.home.core.ServiceLocator
import com.svetlana.home.hands.ScreenshotPolicy
import com.svetlana.home.memory.HistoryCategory
import com.svetlana.home.memory.HistoryManager
import com.svetlana.home.permissions.PermissionManager

/**
 * Право на выполнение действия движком ([AppControlEngine]). Выдаётся ТОЛЬКО ActionRouter:
 * единственная реализация — приватный объект в этом файле. Код в обход роутера (а значит,
 * в обход политики и подтверждений) не может вызвать опасные методы движка — он не
 * скомпилируется.
 */
sealed interface ActionGrant

private object RouterGrant : ActionGrant

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
     * именно этот вопрос был задан пользователю не более минуты назад. Если экран
     * подменили между вопросом и ответом (под тем же именем теперь другой элемент) —
     * спрашиваем снова. Вопрос всегда показывает точные параметры ([ConfirmationText]).
     */
    suspend fun execute(action: SvetlanaAction, confirmed: Boolean = false): ActionResult =
        executeInternal(action, confirmed, chainApproved = false)

    private suspend fun executeInternal(action: SvetlanaAction, confirmed: Boolean, chainApproved: Boolean): ActionResult {
        val policyRisk = ActionRiskPolicy.requiresConfirmation(action) && !chainApproved
        // Клик/ввод: проверяем РЕАЛЬНЫЙ элемент на экране. Снимок и чтение чужого приложения —
        // только с согласия пользователя, и в вопросе видно, КАКОЕ приложение на экране.
        val target = screenTarget(action)
        val targetRisk = target != null && target.dangerous
        var approved = false
        if (policyRisk || targetRisk) {
            val key = Approval(action, target?.label)
            approved = confirmed && consumeApproval(key)
            if (!approved) {
                rememberApproval(key)
                historyManager.record(HistoryCategory.CONFIRMATIONS, "Запрошено подтверждение: ${action.name}")
                val question = ConfirmationText.question(
                    action, target?.shown, secretField = target != null && target.typing && target.dangerous)
                return ActionResult(action, false,
                    "${context.getString(R.string.reply_confirm_dangerous)}\n$question",
                    way = if (target != null) "hands" else "unknown", requiresUserConfirmation = true)
            }
        }
        // Одноразовое согласие на снимок именно того приложения и того окна, о которых спрашивали.
        if (approved && action is SvetlanaAction.TakeScreenshot && target != null) {
            ScreenshotPolicy.allowOnce(target.shown, ScreenshotPolicy.windowKeyOf(ServiceLocator.hands.uiTree()))
        }

        val result = when (action) {
            is SvetlanaAction.OpenApp -> engine.openApp(action.target)
            is SvetlanaAction.OpenAppScreen -> engine.openApp(action.target)
            is SvetlanaAction.Click -> engine.click(RouterGrant, action.target, action.element)
            is SvetlanaAction.LongClick -> engine.longClick(RouterGrant, action.target, action.element)
            is SvetlanaAction.Swipe -> engine.swipe(RouterGrant, action.target, action.direction)
            is SvetlanaAction.Scroll -> engine.scroll(RouterGrant, action.target, action.direction)
            is SvetlanaAction.TypeText -> engine.typeText(RouterGrant, action.target, action.element, action.text)
            is SvetlanaAction.ClearText -> engine.clearText(RouterGrant, action.target, action.element)
            is SvetlanaAction.ReadScreen -> engine.readScreen(RouterGrant, action.target)
            is SvetlanaAction.FindElement -> engine.readScreen(RouterGrant, action.target)
            is SvetlanaAction.TakeScreenshot -> engine.takeScreenshotAction(RouterGrant, action.target)
            SvetlanaAction.PressBack -> engine.pressBack(RouterGrant)
            SvetlanaAction.PressHome -> engine.pressHome(RouterGrant)
            SvetlanaAction.OpenRecents -> engine.openRecents(RouterGrant)
            SvetlanaAction.OpenSettings -> engine.openSettings()
            is SvetlanaAction.MakeCall -> engine.makeCall(RouterGrant, action.contact)
            is SvetlanaAction.SendMessage -> engine.sendMessage(RouterGrant, action.contact, action.text)
            is SvetlanaAction.Share -> engine.share(RouterGrant, action.text)
            is SvetlanaAction.ComposeMessage ->
                engine.composeMessage(RouterGrant, action.appTarget, action.contact, action.text)
            is SvetlanaAction.Compound -> executeCompound(action, confirmed)
        }

        if (result.requiresUserConfirmation) return result
        historyManager.record(
            if (result.success) HistoryCategory.COMMANDS else HistoryCategory.ERRORS,
            "${action.name} → ${if (result.success) "OK" else "FAILED"}: ${result.message}"
        )
        return result
    }

    /**
     * Единая проверка для исполнителей без диалога с пользователем (Mobile Harness,
     * автотесты): true — действие безопасно и его можно выполнить сразу.
     * Опасное по политике или по реальному элементу на экране НЕ выполняется.
     */
    suspend fun authorizeUnattended(action: SvetlanaAction): Boolean {
        val target = screenTarget(action)
        val risky = ActionRiskPolicy.requiresConfirmation(action) || (target != null && target.dangerous)
        if (risky) {
            historyManager.record(HistoryCategory.CONFIRMATIONS,
                "Нужно подтверждение (без диалога не выполнено): ${ConfirmationText.question(action, target?.shown, secretField = target != null && target.typing && target.dangerous)}")
        }
        return !risky
    }

    private class ScreenTarget(val label: String, val shown: String, val typing: Boolean, val dangerous: Boolean)

    private data class Approval(val action: SvetlanaAction, val targetLabel: String?)

    private val pendingApprovals = LinkedHashMap<Approval, Long>()

    @Synchronized
    private fun rememberApproval(key: Approval) {
        pendingApprovals.remove(key)
        pendingApprovals[key] = System.currentTimeMillis()
        while (pendingApprovals.size > MAX_PENDING) pendingApprovals.remove(pendingApprovals.keys.first())
    }

    /** Одноразово и только пока вопрос свежий: подтверждение нельзя использовать повторно или позже. */
    @Synchronized
    private fun consumeApproval(key: Approval): Boolean {
        val askedAt = pendingApprovals.remove(key) ?: return false
        return System.currentTimeMillis() - askedAt in 0..APPROVAL_TTL_MS
    }

    /** Пользователь отказался (или окно подтверждения закрыто): все ожидающие ответы аннулируются. */
    @Synchronized
    fun cancelPendingConfirmations() {
        pendingApprovals.clear()
        resumeChain = null
    }

    @Volatile
    private var resumeChain: Pair<SvetlanaAction.Compound, Int>? = null

    /**
     * Реальная цель действия на экране и её опасность:
     * - Click/LongClick/TypeText: элемент с подписью «Удалить/Оплатить/…» или поле пароля/PIN/карты;
     * - TakeScreenshot / ReadScreen / FindElement: приложение на экране не входит в доверенные
     *   ([ScreenshotPolicy]); в вопросе показывается реальный пакет.
     */
    private fun screenTarget(action: SvetlanaAction): ScreenTarget? {
        if (action is SvetlanaAction.TakeScreenshot || action is SvetlanaAction.ReadScreen || action is SvetlanaAction.FindElement) {
            val pkg = currentPackageForScreenshot()
            val trusted = ScreenshotPolicy.isTrusted(pkg, context.packageName)
            val kind = if (action is SvetlanaAction.TakeScreenshot) "screenshot" else "read"
            return ScreenTarget("$kind:$pkg", pkg, typing = false, dangerous = !trusted)
        }
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

    suspend fun route(command: String, confirmed: Boolean = false): ActionResult? {
        val action = CommandParser.parse(command)
        if (action != null) return execute(action, confirmed)
        val systemIntent = ServiceLocator.appResolver.resolveSystem(command)
        if (systemIntent is AppResolver.ResolveResult.SystemAction) {
            return engine.openSystemIntent(systemIntent.intent)
        }
        return null
    }

    fun missingPermissionsFor(action: SvetlanaAction): List<String> {
        val needed = mutableListOf<String>()
        when (action) {
            is SvetlanaAction.MakeCall -> { /* только набор номера — разрешение не нужно */ }
            is SvetlanaAction.SendMessage -> { /* черновик в приложении SMS — разрешение не нужно */ }
            is SvetlanaAction.TypeText, is SvetlanaAction.Click, is SvetlanaAction.Swipe,
            is SvetlanaAction.Scroll, is SvetlanaAction.TakeScreenshot, is SvetlanaAction.ReadScreen,
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
            val stepResult = executeInternal(step, confirmed, chainApproved = true)
            allProof += stepResult.proof
            if (stepResult.requiresUserConfirmation) {
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
        const val APPROVAL_TTL_MS = 60_000L
    }
}
