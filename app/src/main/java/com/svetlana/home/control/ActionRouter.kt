package com.svetlana.home.control

import android.content.Context
import com.svetlana.home.R
import com.svetlana.home.apps.launcher.AppResolver
import com.svetlana.home.core.ServiceLocator
import com.svetlana.home.hands.ScreenshotPolicy
import com.svetlana.home.memory.HistoryCategory
import com.svetlana.home.memory.HistoryManager
import com.svetlana.home.permissions.PermissionManager
import com.svetlana.home.voice.VoicePolicy

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
 * Команды фонового голоса (неаутентифицированные) никогда не выполняются сразу: только
 * предлагаются на подтверждение в открытом приложении ([route] с unattendedVoice = true).
 *
 * Модель угроз голоса: голосового отпечатка нет. Микрофон в приложении включает человек,
 * который держит разблокированный телефон и видит экран, поэтому такой голос приравнен к
 * тексту из чата (опасное всё равно требует нажатия). Фоновый голос (слово пробуждения)
 * может произнести кто угодно рядом — он только ПРЕДЛАГАЕТ действие.
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

    suspend fun execute(action: SvetlanaAction, confirmed: Boolean = false): ActionResult =
        executeInternal(action, confirmed, chainApproved = false)

    private suspend fun executeInternal(action: SvetlanaAction, confirmed: Boolean, chainApproved: Boolean): ActionResult {
        val policyRisk = ActionRiskPolicy.requiresConfirmation(action) && !chainApproved
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
        if (approved && action is SvetlanaAction.TakeScreenshot && target != null) {
            ScreenshotPolicy.allowOnce(target.shown, ScreenshotPolicy.windowKeyOf(ServiceLocator.hands.uiTree()))
        }
        // Чтение экрана исполняется для ТОГО пакета, о котором спрашивали (движок сверит его до и после).
        val readTarget = target?.shown?.takeIf { it != "unknown" }

        val result = when (action) {
            is SvetlanaAction.OpenApp -> engine.openApp(action.target)
            is SvetlanaAction.OpenAppScreen -> engine.openApp(action.target)
            is SvetlanaAction.Click -> engine.click(RouterGrant, action.target, action.element)
            is SvetlanaAction.LongClick -> engine.longClick(RouterGrant, action.target, action.element)
            is SvetlanaAction.Swipe -> engine.swipe(RouterGrant, action.target, action.direction)
            is SvetlanaAction.Scroll -> engine.scroll(RouterGrant, action.target, action.direction)
            is SvetlanaAction.TypeText -> engine.typeText(RouterGrant, action.target, action.element, action.text)
            is SvetlanaAction.ClearText -> engine.clearText(RouterGrant, action.target, action.element)
            is SvetlanaAction.ReadScreen -> engine.readScreen(RouterGrant, readTarget ?: action.target)
            is SvetlanaAction.FindElement -> engine.readScreen(RouterGrant, readTarget ?: action.target)
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

    @Synchronized
    private fun consumeApproval(key: Approval): Boolean {
        val askedAt = pendingApprovals.remove(key) ?: return false
        return System.currentTimeMillis() - askedAt in 0..APPROVAL_TTL_MS
    }

    @Synchronized
    fun cancelPendingConfirmations() {
        pendingApprovals.clear()
        resumeChain = null
    }

    @Volatile
    private var resumeChain: Pair<SvetlanaAction.Compound, Int>? = null

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

    /** Действия, привязанные к экрану: их цель вычисляется в момент подтверждения, а не из фона. */
    private fun isScreenBound(action: SvetlanaAction): Boolean = when (action) {
        is SvetlanaAction.Click, is SvetlanaAction.LongClick, is SvetlanaAction.TypeText,
        is SvetlanaAction.ReadScreen, is SvetlanaAction.FindElement, is SvetlanaAction.TakeScreenshot -> true
        else -> false
    }

    /**
     * @param unattendedVoice команда фонового слова пробуждения: голос не аутентифицирован, поэтому
     *   ничего не выполняется — действие только предлагается на подтверждение в приложении.
     */
    suspend fun route(command: String, confirmed: Boolean = false, unattendedVoice: Boolean = false): ActionResult? {
        val action = CommandParser.parse(command)
        if (action != null) {
            if (unattendedVoice) return proposeFromVoice(action)
            return execute(action, confirmed)
        }
        val systemIntent = ServiceLocator.appResolver.resolveSystem(command)
        if (systemIntent is AppResolver.ResolveResult.SystemAction) {
            if (unattendedVoice) {
                historyManager.record(HistoryCategory.CONFIRMATIONS, "Голос из фона: системная команда не выполнена")
                return ActionResult(SvetlanaAction.OpenSettings, false, VoicePolicy.SYSTEM_REFUSED, way = "voice")
            }
            return engine.openSystemIntent(systemIntent.intent)
        }
        return null
    }

    /**
     * Действие из фонового голоса: НИЧЕГО не выполняем, только спрашиваем в приложении.
     * Одно нажатие «Подтвердить» в разблокированном приложении = согласие (одноразовое, 60 с).
     * Экранные действия (нажать/ввести/прочитать/скриншот) не получают заранее выданного
     * согласия: при подтверждении цель пересчитывается по живому экрану и, если она опасная,
     * пользователь увидит её и подтвердит отдельно.
     */
    private fun proposeFromVoice(action: SvetlanaAction): ActionResult {
        historyManager.record(HistoryCategory.CONFIRMATIONS, "Голос из фона: ${action.name} ждёт подтверждения в приложении")
        if (!isScreenBound(action) && ActionRiskPolicy.requiresConfirmation(action)) {
            rememberApproval(Approval(action, null))
        }
        val question = ConfirmationText.question(action, null, secretField = false)
        return ActionResult(action, false, "${VoicePolicy.CONFIRM_IN_APP}\n$question",
            way = "voice", requiresUserConfirmation = true)
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
