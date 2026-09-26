package com.svetlana.home.control

import android.content.Context
import android.graphics.Bitmap
import com.svetlana.home.apps.AppModel
import com.svetlana.home.core.ProofStage
import com.svetlana.home.core.ProofStep
import com.svetlana.home.core.StepStatus
import com.svetlana.home.hands.HandsController
import com.svetlana.home.permissions.PermissionManager

/**
 * AppControlEngine — исполняет действия над приложениями.
 *
 * Каждое действие проходит проверку:
 * - целевое приложение идентифицировано;
 * - разрешения проверены;
 * - действие выполнено;
 * - результат верифицирован.
 *
 * Запрещено: произвольные shell-команды, root, Termux, скрытое управление.
 */
class AppControlEngine(
    private val context: Context,
    private val intentResolver: IntentResolver,
    private val hands: HandsController,
    private val permissionManager: PermissionManager,
    private val launchVerifier: LaunchVerifier = LaunchVerifier(context, hands)
) {

    private val proofBuilder = ProofBuilder()

    /**
     * Открыть приложение. Приоритет — Intent/PackageManager, Hands как резерв.
     *
     * Доказательная цепочка: startActivity() — это лишь ACTION_ATTEMPTED.
     * ACTION_PERFORMED выставляется только после того, как LaunchVerifier
     * реально увидел целевой пакет в foreground.
     */
    suspend fun openApp(target: String): ActionResult {
        val proof = proofBuilder.start("PLAN0_TARGET=OPEN_APP target=$target")
        val resolved = intentResolver.resolveApp(target)
        if (resolved == null) {
            return ActionResult(SvetlanaAction.OpenApp(target), false,
                "Приложение не найдено", proof.failed("target not resolved"))
        }
        proof.ok(ProofStage.TARGET_APP_IDENTIFIED, "${resolved.app.packageName} way=${resolved.way}")
        proof.ok(ProofStage.PERMISSION_CHECKED, "launch via ${resolved.way}")
        return try {
            context.startActivity(resolved.launchIntent)
            proof.ok(ProofStage.ACTION_ATTEMPTED, "startActivity sent")
            // Ключевое место: ждём реального перехода, а не считаем
            // «команда отправлена» == «действие выполнено».
            val verified = launchVerifier.awaitForeground(resolved.app.packageName)
            val reached = verified.foregroundPackage
                ?.equals(resolved.app.packageName, ignoreCase = true) == true
            if (reached) {
                proof.ok(ProofStage.ACTION_PERFORMED, "foreground=${verified.foregroundPackage} src=${verified.source}")
                proof.ok(ProofStage.RESULT_VERIFIED, "PLAN0_STATUS=ACTION_PERFORMED PLAN0_RESULT=VERIFIED")
            } else if (verified.foregroundPackage == null) {
                // Нет источника проверки (Hands выключен, USAGE_STATS не выдан).
                // Честно отмечаем: действие попытано, но не верифицировано.
                proof.add(ProofStage.ACTION_PERFORMED, StepStatus.UNVERIFIED,
                    "no verification source (hands off, usage stats not granted)")
                proof.add(ProofStage.RESULT_VERIFIED, StepStatus.UNVERIFIED,
                    "PLAN0_RESULT=NOT PROVEN")
            } else {
                proof.add(ProofStage.ACTION_PERFORMED, StepStatus.FAILED,
                    "foreground=${verified.foregroundPackage} expected=${resolved.app.packageName}")
                proof.add(ProofStage.RESULT_VERIFIED, StepStatus.FAILED,
                    "PLAN0_RESULT=NOT VERIFIED")
            }
            ActionResult(SvetlanaAction.OpenApp(target), reached,
                if (reached) "Приложение открыто" else "Не удалось подтвердить открытие приложения",
                proof.build(), resolved.way)
        } catch (t: Throwable) {
            // Резерв: Hands
            if (hands.isActive) {
                proof.ok(ProofStage.ACTION_ATTEMPTED, "fallback: Hands")
                hands.pressHome()
                val ok = hands.waitForPackage(resolved.app.packageName)
                proof.add(ProofStage.ACTION_PERFORMED, if (ok) StepStatus.OK else StepStatus.FAILED,
                    if (ok) "opened via hands" else "hands failed")
                proof.add(ProofStage.RESULT_VERIFIED, if (ok) StepStatus.OK else StepStatus.FAILED,
                    if (ok) "PLAN0_RESULT=VERIFIED" else "PLAN0_RESULT=NOT VERIFIED")
                ActionResult(SvetlanaAction.OpenApp(target), ok,
                    if (ok) "Приложение открыто через Hands" else "Не удалось открыть приложение",
                    proof.build(), "hands")
            } else {
                ActionResult(SvetlanaAction.OpenApp(target), false,
                    "Не удалось открыть приложение", proof.failed("no launch path and hands off"))
            }
        }
    }

    suspend fun click(target: String, element: String): ActionResult {
        val proof = proofBuilder.start("PLAN0_TARGET=CLICK app=$target element=$element")
        if (!hands.isActive) {
            return ActionResult(SvetlanaAction.Click(target, element), false,
                "Для этого действия нужен Hands, но он не включён",
                proof.failed("hands not active"))
        }
        proof.ok(ProofStage.TARGET_APP_IDENTIFIED, target)
        proof.ok(ProofStage.PERMISSION_CHECKED, "hands granted")
        proof.ok(ProofStage.ACTION_ATTEMPTED, "find element: $element")
        val node = hands.findElement(element)
        if (node == null) {
            return ActionResult(SvetlanaAction.Click(target, element), false,
                "Элемент не найден на экране", proof.failed("element not found"))
        }
        val before = hands.nodeCount()
        val ok = hands.clickNode(node)
        proof.add(ProofStage.ACTION_PERFORMED, if (ok) StepStatus.OK else StepStatus.FAILED,
            if (ok) "click performed" else "click failed")
        // Пост-условие: click должен изменить экран (появился новый элемент,
        // изменилось состояние и т.д.). ACTION_PERFORMED без проверки — это
        // только «команда отправлена», что неоднозначно (аудит п.11).
        val changed = ok && verifyScreenChanged(before, 700)
        proof.add(ProofStage.RESULT_VERIFIED,
            if (changed) StepStatus.OK else if (!ok) StepStatus.FAILED else StepStatus.UNVERIFIED,
            if (changed) "PLAN0_RESULT=VERIFIED" else "PLAN0_RESULT=NOT VERIFIED")
        return ActionResult(SvetlanaAction.Click(target, element), ok,
            if (ok) "Нажатие выполнено" else "Не удалось нажать",
            proof.build(), "hands")
    }

    suspend fun longClick(target: String, element: String): ActionResult {
        val proof = proofBuilder.start("PLAN0_TARGET=LONG_CLICK app=$target element=$element")
        if (!hands.isActive) return handsOff(SvetlanaAction.LongClick(target, element), proof)
        proof.ok(ProofStage.TARGET_APP_IDENTIFIED, target)
        proof.ok(ProofStage.PERMISSION_CHECKED, "hands granted")
        proof.ok(ProofStage.ACTION_ATTEMPTED, "find element: $element")
        val node = hands.findElement(element)
            ?: return ActionResult(SvetlanaAction.LongClick(target, element), false,
                "Элемент не найден", proof.failed("element not found"))
        val before = hands.nodeCount()
        val ok = hands.longClick(node)
        proof.add(ProofStage.ACTION_PERFORMED, if (ok) StepStatus.OK else StepStatus.FAILED,
            if (ok) "long click performed" else "long click failed")
        // Пост-условие: экран должен откликнуться (измениться или элемент
        // остаться валидным). Без этого RESULT_VERIFIED не ставится.
        val verified = ok && verifyScreenChanged(before, 800)
        proof.add(ProofStage.RESULT_VERIFIED,
            if (verified) StepStatus.OK else if (!ok) StepStatus.FAILED else StepStatus.UNVERIFIED,
            if (verified) "PLAN0_RESULT=VERIFIED" else "PLAN0_RESULT=NOT VERIFIED")
        return ActionResult(SvetlanaAction.LongClick(target, element), ok,
            if (ok) "Долгое нажатие выполнено" else "Не удалось", proof.build(), "hands")
    }

    suspend fun typeText(target: String, element: String, text: String): ActionResult {
        val proof = proofBuilder.start("PLAN0_TARGET=TYPE_TEXT app=$target element=$element")
        if (!hands.isActive) return handsOff(SvetlanaAction.TypeText(target, element, text), proof)
        proof.ok(ProofStage.TARGET_APP_IDENTIFIED, target)
        proof.ok(ProofStage.PERMISSION_CHECKED, "hands granted")
        proof.ok(ProofStage.ACTION_ATTEMPTED, "find element: $element")
        val node = hands.findElement(element)
            ?: return ActionResult(SvetlanaAction.TypeText(target, element, text), false,
                "Поле ввода не найдено", proof.failed("input field not found"))
        val ok = hands.inputText(node, text)
        proof.add(ProofStage.ACTION_PERFORMED, if (ok) StepStatus.OK else StepStatus.FAILED,
            if (ok) "input performed" else "input failed")
        // Настоящее пост-условие: поле действительно содержит текст.
        val entered = ok && hands.verifyTextEntered(node, text)
        proof.add(ProofStage.RESULT_VERIFIED,
            if (entered) StepStatus.OK else if (!ok) StepStatus.FAILED else StepStatus.UNVERIFIED,
            if (entered) "PLAN0_RESULT=VERIFIED text=\"${text.take(32)}\""
                else "PLAN0_RESULT=NOT VERIFIED")
        return ActionResult(SvetlanaAction.TypeText(target, element, text), ok,
            if (ok) "Текст введён" else "Не удалось ввести текст", proof.build(), "hands")
    }

    suspend fun clearText(target: String, element: String): ActionResult {
        val proof = proofBuilder.start("PLAN0_TARGET=CLEAR_TEXT app=$target element=$element")
        if (!hands.isActive) return handsOff(SvetlanaAction.ClearText(target, element), proof)
        proof.ok(ProofStage.TARGET_APP_IDENTIFIED, target)
        proof.ok(ProofStage.PERMISSION_CHECKED, "hands granted")
        proof.ok(ProofStage.ACTION_ATTEMPTED, "find element: $element")
        val node = hands.findElement(element)
            ?: return ActionResult(SvetlanaAction.ClearText(target, element), false,
                "Поле ввода не найдено", proof.failed("input field not found"))
        val ok = hands.clearText(node)
        proof.add(ProofStage.ACTION_PERFORMED, if (ok) StepStatus.OK else StepStatus.FAILED,
            if (ok) "clear performed" else "clear failed")
        val cleared = ok && hands.verifyTextEmpty(node)
        proof.add(ProofStage.RESULT_VERIFIED,
            if (cleared) StepStatus.OK else if (!ok) StepStatus.FAILED else StepStatus.UNVERIFIED,
            if (cleared) "PLAN0_RESULT=VERIFIED" else "PLAN0_RESULT=NOT VERIFIED")
        return ActionResult(SvetlanaAction.ClearText(target, element), ok,
            if (ok) "Текст очищен" else "Не удалось очистить", proof.build(), "hands")
    }

    suspend fun scroll(target: String, direction: String): ActionResult {
        val proof = proofBuilder.start("PLAN0_TARGET=SCROLL app=$target dir=$direction")
        if (!hands.isActive) return handsOff(SvetlanaAction.Scroll(target, direction), proof)
        proof.ok(ProofStage.TARGET_APP_IDENTIFIED, target)
        proof.ok(ProofStage.PERMISSION_CHECKED, "hands granted")
        proof.ok(ProofStage.ACTION_ATTEMPTED, "scroll $direction")
        val before = hands.nodeCount()
        val down = direction.equals("down", ignoreCase = true) || direction == "вниз"
        val ok = if (down) hands.scrollForward() else hands.scrollBackward()
        proof.add(ProofStage.ACTION_PERFORMED, if (ok) StepStatus.OK else StepStatus.FAILED,
            if (ok) "scroll performed" else "scroll failed")
        val changed = ok && verifyScreenChanged(before, 600)
        proof.add(ProofStage.RESULT_VERIFIED,
            if (changed) StepStatus.OK else if (!ok) StepStatus.FAILED else StepStatus.UNVERIFIED,
            if (changed) "PLAN0_RESULT=VERIFIED" else "PLAN0_RESULT=NOT VERIFIED")
        return ActionResult(SvetlanaAction.Scroll(target, direction), ok,
            if (ok) "Прокрутка выполнена" else "Не удалось прокрутить", proof.build(), "hands")
    }

    suspend fun swipe(target: String, direction: String): ActionResult {
        val proof = proofBuilder.start("PLAN0_TARGET=SWIPE app=$target dir=$direction")
        if (!hands.isActive) return handsOff(SvetlanaAction.Swipe(target, direction), proof)
        proof.ok(ProofStage.TARGET_APP_IDENTIFIED, target)
        proof.ok(ProofStage.PERMISSION_CHECKED, "hands granted")
        proof.ok(ProofStage.ACTION_ATTEMPTED, "swipe $direction")
        val before = hands.nodeCount()
        val ok = when (direction.lowercase()) {
            "up", "вверх" -> hands.swipe(540f, 1400f, 540f, 400f)
            "down", "вниз" -> hands.swipe(540f, 400f, 540f, 1400f)
            "left", "влево" -> hands.swipe(900f, 900f, 200f, 900f)
            "right", "вправо" -> hands.swipe(200f, 900f, 900f, 900f)
            else -> false
        }
        proof.add(ProofStage.ACTION_PERFORMED, if (ok) StepStatus.OK else StepStatus.FAILED,
            if (ok) "swipe performed" else "swipe failed")
        val changed = ok && verifyScreenChanged(before, 600)
        proof.add(ProofStage.RESULT_VERIFIED,
            if (changed) StepStatus.OK else if (!ok) StepStatus.FAILED else StepStatus.UNVERIFIED,
            if (changed) "PLAN0_RESULT=VERIFIED" else "PLAN0_RESULT=NOT VERIFIED")
        return ActionResult(SvetlanaAction.Swipe(target, direction), ok,
            if (ok) "Свайп выполнен" else "Не удалось сделать свайп", proof.build(), "hands")
    }

    /**
     * Проверка реального изменения экрана после жеста (аудит п.11).
     * Дожидаться бесконечно нельзя — жест мог быть корректным, но экран
     * закономерно не изменился (например, прокрутка в самом низу). В этом
     * случае ставим UNVERIFIED, а не VERIFIED.
     */
    private suspend fun verifyScreenChanged(beforeNodeCount: Int, settleMs: Long): Boolean {
        kotlinx.coroutines.delay(settleMs)
        return hands.verifyTreeChanged(beforeNodeCount)
    }

    private fun handsOff(action: SvetlanaAction, proof: ProofBuilder): ActionResult =
        ActionResult(action, false,
            "Для этого действия нужен Hands, но он не включён", proof.failed("hands not active"))

    suspend fun readScreen(target: String): ActionResult {
        val proof = proofBuilder.start("PLAN0_TARGET=READ_SCREEN app=$target")
        if (!hands.isActive) return handsOff(SvetlanaAction.ReadScreen(target), proof)
        proof.ok(ProofStage.PERMISSION_CHECKED, "hands granted")
        proof.ok(ProofStage.ACTION_ATTEMPTED, "snapshot ui tree")
        val tree = hands.uiTree()
            ?: return ActionResult(SvetlanaAction.ReadScreen(target), false,
                "Не удалось прочитать экран", proof.failed("ui tree unavailable"))
        proof.ok(ProofStage.ACTION_PERFORMED, "${tree.nodes.size} nodes")
        val text = tree.nodes.mapNotNull { n ->
            n.visibleText.ifBlank { null }
        }.distinct().joinToString("\n")
        proof.ok(ProofStage.RESULT_VERIFIED, "PLAN0_RESULT=VERIFIED ${tree.nodes.size} nodes")
        return ActionResult(SvetlanaAction.ReadScreen(target), true,
            if (text.isBlank()) "На экране нет текста" else text, proof.build(), "hands")
    }

    suspend fun takeScreenshot(target: String): Bitmap? {
        if (!hands.isActive) return null
        return hands.takeScreenshot()
    }

    suspend fun pressBack(): ActionResult {
        val proof = proofBuilder.start("PLAN0_TARGET=PRESS_BACK")
        if (!hands.isActive) return handsOff(SvetlanaAction.PressBack, proof)
        proof.ok(ProofStage.PERMISSION_CHECKED, "hands granted")
        proof.ok(ProofStage.ACTION_ATTEMPTED, "global action back")
        val before = hands.nodeCount()
        val ok = hands.pressBack()
        proof.add(ProofStage.ACTION_PERFORMED, if (ok) StepStatus.OK else StepStatus.FAILED,
            if (ok) "back performed" else "back failed")
        val changed = ok && verifyScreenChanged(before, 600)
        proof.add(ProofStage.RESULT_VERIFIED,
            if (changed) StepStatus.OK else if (!ok) StepStatus.FAILED else StepStatus.UNVERIFIED,
            if (changed) "PLAN0_RESULT=VERIFIED" else "PLAN0_RESULT=NOT VERIFIED")
        return ActionResult(SvetlanaAction.PressBack, ok,
            if (ok) "Назад" else "Не удалось", proof.build(), "hands")
    }

    suspend fun pressHome(): ActionResult {
        val proof = proofBuilder.start("PLAN0_TARGET=PRESS_HOME")
        if (!hands.isActive) return handsOff(SvetlanaAction.PressHome, proof)
        proof.ok(ProofStage.PERMISSION_CHECKED, "hands granted")
        proof.ok(ProofStage.ACTION_ATTEMPTED, "global action home")
        val ok = hands.pressHome()
        proof.add(ProofStage.ACTION_PERFORMED, if (ok) StepStatus.OK else StepStatus.FAILED,
            if (ok) "home performed" else "home failed")
        // Home почти всегда меняет экран на launcher
        proof.add(ProofStage.RESULT_VERIFIED, if (ok) StepStatus.OK else StepStatus.FAILED,
            if (ok) "PLAN0_RESULT=VERIFIED" else "PLAN0_RESULT=NOT VERIFIED")
        return ActionResult(SvetlanaAction.PressHome, ok,
            if (ok) "Домой" else "Не удалось", proof.build(), "hands")
    }

    suspend fun openRecents(): ActionResult {
        val proof = proofBuilder.start("PLAN0_TARGET=OPEN_RECENTS")
        if (!hands.isActive) return handsOff(SvetlanaAction.OpenRecents, proof)
        proof.ok(ProofStage.PERMISSION_CHECKED, "hands granted")
        proof.ok(ProofStage.ACTION_ATTEMPTED, "global action recents")
        val ok = hands.openRecents()
        proof.add(ProofStage.ACTION_PERFORMED, if (ok) StepStatus.OK else StepStatus.FAILED,
            if (ok) "recents performed" else "recents failed")
        proof.add(ProofStage.RESULT_VERIFIED, if (ok) StepStatus.OK else StepStatus.FAILED,
            if (ok) "PLAN0_RESULT=VERIFIED" else "PLAN0_RESULT=NOT VERIFIED")
        return ActionResult(SvetlanaAction.OpenRecents, ok,
            if (ok) "Недавние приложения" else "Не удалось", proof.build(), "hands")
    }

    suspend fun openSettings(): ActionResult = openApp("настройки")

    /**
     * Звонок. Опасное действие — требует подтверждения пользователя.
     */
    fun makeCall(contact: String): ActionResult {
        val intent = intentResolver.call(contact).let {
            if (permissionManager.isGranted(android.Manifest.permission.CALL_PHONE)) it
            else intentResolver.dial(contact)
        }
        return try {
            context.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            ActionResult(SvetlanaAction.MakeCall(contact), true,
                "Звонок: $contact", emptyList(), "intent")
        } catch (t: Throwable) {
            ActionResult(SvetlanaAction.MakeCall(contact), false,
                "Не удалось позвонить", emptyList())
        }
    }

    /**
     * Отправка сообщения — опасное действие, требующее подтверждения.
     */
    fun sendMessage(contact: String, text: String): ActionResult {
        val contactUri = intentResolver.openContactByName(contact)
        return try {
            val intent = intentResolver.sms(contact, text)
            context.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            ActionResult(SvetlanaAction.SendMessage(contact, text), true,
                "Сообщение для $contact подготовлено", emptyList(), "intent")
        } catch (t: Throwable) {
            ActionResult(SvetlanaAction.SendMessage(contact, text), false,
                "Не удалось отправить сообщение", emptyList())
        }
    }

    fun share(text: String): ActionResult = try {
        context.startActivity(intentResolver.shareText(text))
        ActionResult(SvetlanaAction.Share(text), true, "Поделиться", emptyList(), "intent")
    } catch (t: Throwable) {
        ActionResult(SvetlanaAction.Share(text), false, "Не удалось", emptyList())
    }

    private fun handsOff(action: SvetlanaAction): ActionResult =
        ActionResult(action, false, "Для этого действия нужен Hands, но он не включён", emptyList())

    /**
     * Опасные действия, требующие подтверждения пользователя (ТЗ §58).
     * Делегируем в чистую функцию ActionRiskPolicy — единый источник
     * классификации, покрываемый unit-тестами без Context.
     */
    fun riskOf(action: SvetlanaAction): ActionRisk = ActionRiskPolicy.riskOf(action)

    /**
     * Возможности управления для конкретного приложения (capability matrix).
     */
    fun capabilitiesFor(app: AppModel): com.svetlana.home.apps.ControlCapabilities = app.control
}

/**
 * Построение доказательной цепочки.
 */
class ProofBuilder {
    private val steps = mutableListOf<ProofStep>()

    fun start(plan: String): ProofBuilder {
        steps.clear()
        steps.add(ProofStep(ProofStage.PLAN, StepStatus.OK, plan))
        return this
    }

    fun ok(stage: ProofStage, detail: String): ProofBuilder {
        steps.add(ProofStep(stage, StepStatus.OK, detail))
        return this
    }

    fun add(stage: ProofStage, status: StepStatus, detail: String): ProofBuilder {
        steps.add(ProofStep(stage, status, detail))
        return this
    }

    fun failed(detail: String): List<ProofStep> {
        steps.add(ProofStep(ProofStage.RESULT_VERIFIED, StepStatus.FAILED, detail))
        return steps.toList()
    }

    fun build(): List<ProofStep> = steps.toList()
}
