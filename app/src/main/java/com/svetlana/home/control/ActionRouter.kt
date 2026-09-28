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
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun execute(action: SvetlanaAction, confirmed: Boolean = false): ActionResult {
        // ТЗ §58: опасные действия (звонки, SMS) и публикации/шеринг
        // требуют явного подтверждения пользователя.
        if (ActionRiskPolicy.requiresConfirmation(action) && !confirmed) {
            historyManager.record(HistoryCategory.CONFIRMATIONS,
                "Запрошено подтверждение: ${action.name}")
            return ActionResult(action, false, context.getString(R.string.reply_confirm_dangerous),
                requiresUserConfirmation = true)
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
            is SvetlanaAction.TakeScreenshot -> {
                val bmp = engine.takeScreenshot(action.target)
                ActionResult(action, bmp != null,
                    if (bmp != null) "Скриншот сделан" else "Не удалось сделать скриншот",
                    emptyList(), "hands")
            }
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

        historyManager.record(
            if (result.success) HistoryCategory.COMMANDS else HistoryCategory.ERRORS,
            "${action.name} → ${if (result.success) "OK" else "FAILED"}: ${result.message}"
        )
        return result
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
     */
    private suspend fun executeCompound(action: SvetlanaAction.Compound, confirmed: Boolean): ActionResult {
        val allProof = mutableListOf<com.svetlana.home.core.ProofStep>()
        allProof += com.svetlana.home.core.ProofStep(
            com.svetlana.home.core.ProofStage.PLAN, com.svetlana.home.core.StepStatus.OK,
            "PLAN_TARGET=COMPOUND steps=${action.steps.size} (${action.steps.joinToString { it.name }})")

        val messages = mutableListOf<String>()
        action.steps.forEachIndexed { index, step ->
            // Опасные шаги внутри цепочки (например, отправка сообщения)
            // требуют подтверждения — вся цепочка уже была подтверждена
            // целиком на уровне execute(), поэтому передаём confirmed дальше.
            val stepResult = execute(step, confirmed)
            allProof += stepResult.proof
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
}
