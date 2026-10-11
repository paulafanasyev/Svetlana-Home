@file:Suppress("UNUSED_PARAMETER")

package com.svetlana.home.control

import android.content.Context
import android.graphics.Bitmap
import com.svetlana.home.R
import com.svetlana.home.apps.AppModel
import com.svetlana.home.core.ProofStage
import com.svetlana.home.core.ProofStep
import com.svetlana.home.core.StepStatus
import com.svetlana.home.hands.HandsController
import com.svetlana.home.hands.SensitiveApps
import com.svetlana.home.permissions.PermissionManager

/**
 * AppControlEngine — исполняет действия над приложениями.
 *
 * Безопасность:
 * - все действия с экраном, звонки, сообщения и «поделиться» требуют [ActionGrant] (выдаёт только
 *   ActionRouter после проверки политики и подтверждений);
 * - каждое действие на экране (нажатие, ввод, очистка, прокрутка, свайп, чтение)
 *   «прикалывается» к пакету целевого приложения ([guard]): неизвестное приложение = отказ,
 *   а пакет проверяется ещё раз НЕПОСРЕДСТВЕННО перед действием — оверлей или переключение
 *   приложения отменяет действие;
 * - звонок только через набор номера, SMS — только черновик.
 *
 * Запрещено: произвольные shell-команды, root, Termux, скрытое управление, автонабор звонка.
 */
class AppControlEngine(
    private val context: Context,
    private val intentResolver: IntentResolver,
    private val hands: HandsController,
    private val permissionManager: PermissionManager,
    private val launchVerifier: LaunchVerifier = LaunchVerifier(context, hands)
) {

    private val proofBuilder = ProofBuilder()

    private sealed class Pin {
        data class To(val pkg: String) : Pin()
        data class Refused(val why: String) : Pin()
    }

    private class Guard(val pkg: String?, val failure: ActionResult?)

    private suspend fun pin(target: String): Pin {
        val t = target.trim()
        if (t.isEmpty() || t.lowercase() in GENERIC_TARGETS) {
            val current = hands.currentPackage()
            return if (current.isBlank()) Pin.Refused("не удалось определить приложение на экране") else Pin.To(current)
        }
        if (PACKAGE_RE.matches(t)) return Pin.To(t)
        val resolved = intentResolver.resolveApp(t)?.app?.packageName
            ?: return Pin.Refused("приложение «${t.take(40)}» не найдено")
        return Pin.To(resolved)
    }

    private fun onScreen(pkg: String): Boolean = hands.currentPackage().equals(pkg, ignoreCase = true)

    private suspend fun guard(action: SvetlanaAction, proof: ProofBuilder, target: String): Guard =
        when (val p = pin(target)) {
            is Pin.Refused -> Guard(null, ActionResult(action, false, "Действие отменено: ${p.why}",
                proof.failed("target not pinned: ${p.why}"), "hands"))
            is Pin.To -> if (onScreen(p.pkg)) Guard(p.pkg, null)
                else Guard(null, wrongAppResult(action, proof, hands.currentPackage(), p.pkg))
        }

    private fun wrongAppResult(action: SvetlanaAction, proof: ProofBuilder, current: String, expected: String): ActionResult =
        ActionResult(action, false, "На экране другое приложение — действие отменено для безопасности",
            proof.failed("foreground=$current expected=$expected"), "hands")

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
            val verified = launchVerifier.awaitForeground(resolved.app.packageName)
            val reached = verified.foregroundPackage
                ?.equals(resolved.app.packageName, ignoreCase = true) == true
            if (reached) {
                proof.ok(ProofStage.ACTION_PERFORMED, "foreground=${verified.foregroundPackage} src=${verified.source}")
                proof.ok(ProofStage.RESULT_VERIFIED, "PLAN0_STATUS=ACTION_PERFORMED PLAN0_RESULT=VERIFIED")
            } else if (verified.foregroundPackage == null) {
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

    suspend fun click(grant: ActionGrant, target: String, element: String): ActionResult {
        val action = SvetlanaAction.Click(target, element)
        val proof = proofBuilder.start("PLAN0_TARGET=CLICK app=$target element=$element")
        if (!hands.isActive) return handsOff(action, proof)
        val g = guard(action, proof, target); g.failure?.let { return it }
        val pkg = g.pkg!!
        proof.ok(ProofStage.TARGET_APP_IDENTIFIED, pkg)
        proof.ok(ProofStage.PERMISSION_CHECKED, "hands granted")
        proof.ok(ProofStage.ACTION_ATTEMPTED, "find element: $element")
        val node = hands.findElement(element)
            ?: return ActionResult(action, false, "Элемент не найден на экране", proof.failed("element not found"))
        val before = hands.treeFingerprint()
        if (!onScreen(pkg)) return wrongAppResult(action, proof, hands.currentPackage(), pkg)
        val ok = hands.clickNode(node)
        proof.add(ProofStage.ACTION_PERFORMED, if (ok) StepStatus.OK else StepStatus.FAILED,
            if (ok) "click performed" else "click failed")
        val changed = ok && verifyScreenChanged(before, 700)
        proof.add(ProofStage.RESULT_VERIFIED,
            if (changed) StepStatus.OK else if (!ok) StepStatus.FAILED else StepStatus.UNVERIFIED,
            if (changed) "PLAN0_RESULT=VERIFIED" else "PLAN0_RESULT=NOT VERIFIED")
        return ActionResult(action, changed,
            if (changed) "Нажатие выполнено и подтверждено"
            else if (ok) "Команда отправлена, но экран не изменился — результат не подтверждён"
            else "Не удалось нажать",
            proof.build(), "hands")
    }

    suspend fun longClick(grant: ActionGrant, target: String, element: String): ActionResult {
        val action = SvetlanaAction.LongClick(target, element)
        val proof = proofBuilder.start("PLAN0_TARGET=LONG_CLICK app=$target element=$element")
        if (!hands.isActive) return handsOff(action, proof)
        val g = guard(action, proof, target); g.failure?.let { return it }
        val pkg = g.pkg!!
        proof.ok(ProofStage.TARGET_APP_IDENTIFIED, pkg)
        proof.ok(ProofStage.PERMISSION_CHECKED, "hands granted")
        proof.ok(ProofStage.ACTION_ATTEMPTED, "find element: $element")
        val node = hands.findElement(element)
            ?: return ActionResult(action, false, "Элемент не найден", proof.failed("element not found"))
        val before = hands.treeFingerprint()
        if (!onScreen(pkg)) return wrongAppResult(action, proof, hands.currentPackage(), pkg)
        val ok = hands.longClick(node)
        proof.add(ProofStage.ACTION_PERFORMED, if (ok) StepStatus.OK else StepStatus.FAILED,
            if (ok) "long click performed" else "long click failed")
        val verified = ok && verifyScreenChanged(before, 800)
        proof.add(ProofStage.RESULT_VERIFIED,
            if (verified) StepStatus.OK else if (!ok) StepStatus.FAILED else StepStatus.UNVERIFIED,
            if (verified) "PLAN0_RESULT=VERIFIED" else "PLAN0_RESULT=NOT VERIFIED")
        return ActionResult(action, verified,
            if (verified) "Долгое нажатие подтверждено"
            else if (ok) "Команда отправлена, но экран не изменился"
            else "Не удалось",
            proof.build(), "hands")
    }

    suspend fun typeText(grant: ActionGrant, target: String, element: String, text: String): ActionResult {
        val action = SvetlanaAction.TypeText(target, element, text)
        val proof = proofBuilder.start("PLAN0_TARGET=TYPE_TEXT app=$target element=$element")
        if (!hands.isActive) return handsOff(action, proof)
        val g = guard(action, proof, target); g.failure?.let { return it }
        val pkg = g.pkg!!
        proof.ok(ProofStage.TARGET_APP_IDENTIFIED, pkg)
        proof.ok(ProofStage.PERMISSION_CHECKED, "hands granted")
        proof.ok(ProofStage.ACTION_ATTEMPTED, "find element: $element")
        val node = hands.findElement(element)
            ?: return ActionResult(action, false, "Поле ввода не найдено", proof.failed("input field not found"))
        if (!onScreen(pkg)) return wrongAppResult(action, proof, hands.currentPackage(), pkg)
        val ok = hands.inputText(node, text)
        proof.add(ProofStage.ACTION_PERFORMED, if (ok) StepStatus.OK else StepStatus.FAILED,
            if (ok) "input performed" else "input failed")
        val entered = ok && hands.verifyTextEntered(node, text)
        proof.add(ProofStage.RESULT_VERIFIED,
            if (entered) StepStatus.OK else if (!ok) StepStatus.FAILED else StepStatus.UNVERIFIED,
            if (entered) "PLAN0_RESULT=VERIFIED text=\"${text.take(32)}\""
                else "PLAN0_RESULT=NOT VERIFIED")
        return ActionResult(action, entered,
            if (entered) "Текст введён и подтверждён"
            else if (ok) "Команда отправлена, но текст не появился в поле"
            else "Не удалось ввести текст", proof.build(), "hands")
    }

    suspend fun clearText(grant: ActionGrant, target: String, element: String): ActionResult {
        val action = SvetlanaAction.ClearText(target, element)
        val proof = proofBuilder.start("PLAN0_TARGET=CLEAR_TEXT app=$target element=$element")
        if (!hands.isActive) return handsOff(action, proof)
        val g = guard(action, proof, target); g.failure?.let { return it }
        val pkg = g.pkg!!
        proof.ok(ProofStage.TARGET_APP_IDENTIFIED, pkg)
        proof.ok(ProofStage.PERMISSION_CHECKED, "hands granted")
        proof.ok(ProofStage.ACTION_ATTEMPTED, "find element: $element")
        val node = hands.findElement(element)
            ?: return ActionResult(action, false, "Поле ввода не найдено", proof.failed("input field not found"))
        if (!onScreen(pkg)) return wrongAppResult(action, proof, hands.currentPackage(), pkg)
        val ok = hands.clearText(node)
        proof.add(ProofStage.ACTION_PERFORMED, if (ok) StepStatus.OK else StepStatus.FAILED,
            if (ok) "clear performed" else "clear failed")
        val cleared = ok && hands.verifyTextEmpty(node)
        proof.add(ProofStage.RESULT_VERIFIED,
            if (cleared) StepStatus.OK else if (!ok) StepStatus.FAILED else StepStatus.UNVERIFIED,
            if (cleared) "PLAN0_RESULT=VERIFIED" else "PLAN0_RESULT=NOT VERIFIED")
        return ActionResult(action, cleared,
            if (cleared) "Текст очищен и подтверждён"
            else if (ok) "Команда отправлена, но поле не пусто"
            else "Не удалось очистить текст", proof.build(), "hands")
    }

    suspend fun scroll(grant: ActionGrant, target: String, direction: String): ActionResult {
        val action = SvetlanaAction.Scroll(target, direction)
        val proof = proofBuilder.start("PLAN0_TARGET=SCROLL app=$target dir=$direction")
        if (!hands.isActive) return handsOff(action, proof)
        val g = guard(action, proof, target); g.failure?.let { return it }
        val pkg = g.pkg!!
        proof.ok(ProofStage.TARGET_APP_IDENTIFIED, pkg)
        proof.ok(ProofStage.PERMISSION_CHECKED, "hands granted")
        proof.ok(ProofStage.ACTION_ATTEMPTED, "scroll $direction")
        val before = hands.treeFingerprint()
        val down = direction.equals("down", ignoreCase = true) || direction == "вниз"
        if (!onScreen(pkg)) return wrongAppResult(action, proof, hands.currentPackage(), pkg)
        val ok = if (down) hands.scrollForward() else hands.scrollBackward()
        proof.add(ProofStage.ACTION_PERFORMED, if (ok) StepStatus.OK else StepStatus.FAILED,
            if (ok) "scroll performed" else "scroll failed")
        val changed = ok && verifyScreenChanged(before, 600)
        proof.add(ProofStage.RESULT_VERIFIED,
            if (changed) StepStatus.OK else if (!ok) StepStatus.FAILED else StepStatus.UNVERIFIED,
            if (changed) "PLAN0_RESULT=VERIFIED" else "PLAN0_RESULT=NOT VERIFIED")
        return ActionResult(action, changed,
            if (changed) "Прокрутка подтверждена"
            else if (ok) "Команда отправлена, но экран не изменился — возможно, конец списка"
            else "Не удалось прокрутить", proof.build(), "hands")
    }

    suspend fun swipe(grant: ActionGrant, target: String, direction: String): ActionResult {
        val action = SvetlanaAction.Swipe(target, direction)
        val proof = proofBuilder.start("PLAN0_TARGET=SWIPE app=$target dir=$direction")
        if (!hands.isActive) return handsOff(action, proof)
        val g = guard(action, proof, target); g.failure?.let { return it }
        val pkg = g.pkg!!
        proof.ok(ProofStage.TARGET_APP_IDENTIFIED, pkg)
        proof.ok(ProofStage.PERMISSION_CHECKED, "hands granted")
        proof.ok(ProofStage.ACTION_ATTEMPTED, "swipe $direction")
        val before = hands.treeFingerprint()
        val coords = when (direction.lowercase()) {
            "up", "вверх" -> floatArrayOf(540f, 1400f, 540f, 400f)
            "down", "вниз" -> floatArrayOf(540f, 400f, 540f, 1400f)
            "left", "влево" -> floatArrayOf(900f, 900f, 200f, 900f)
            "right", "вправо" -> floatArrayOf(200f, 900f, 900f, 900f)
            else -> null
        }
        if (coords != null && !onScreen(pkg)) return wrongAppResult(action, proof, hands.currentPackage(), pkg)
        val ok = coords != null && hands.swipe(coords[0], coords[1], coords[2], coords[3])
        proof.add(ProofStage.ACTION_PERFORMED, if (ok) StepStatus.OK else StepStatus.FAILED,
            if (ok) "swipe performed" else "swipe failed")
        val changed = ok && verifyScreenChanged(before, 600)
        proof.add(ProofStage.RESULT_VERIFIED,
            if (changed) StepStatus.OK else if (!ok) StepStatus.FAILED else StepStatus.UNVERIFIED,
            if (changed) "PLAN0_RESULT=VERIFIED" else "PLAN0_RESULT=NOT VERIFIED")
        return ActionResult(action, changed,
            if (changed) "Свайп подтверждён"
            else if (ok) "Команда отправлена, но экран не изменился"
            else "Не удалось сделать свайп", proof.build(), "hands")
    }

    private suspend fun verifyScreenChanged(beforeFingerprint: Long, settleMs: Long): Boolean {
        kotlinx.coroutines.delay(settleMs)
        return hands.verifyTreeChanged(beforeFingerprint)
    }

    private fun handsOff(action: SvetlanaAction, proof: ProofBuilder): ActionResult =
        ActionResult(action, false,
            "Для этого действия нужен Hands, но он не включён", proof.failed("hands not active"))

    suspend fun readScreen(grant: ActionGrant, target: String): ActionResult {
        val action = SvetlanaAction.ReadScreen(target)
        val proof = proofBuilder.start("PLAN0_TARGET=READ_SCREEN app=$target")
        if (!hands.isActive) return handsOff(action, proof)
        val g = guard(action, proof, target); g.failure?.let { return it }
        val pkg = g.pkg!!
        if (SensitiveApps.isSensitive(pkg)) {
            return ActionResult(action, false,
                "Экран этого приложения не читаю: банки, платежи и пароли защищены",
                proof.failed("sensitive app"), "hands")
        }
        proof.ok(ProofStage.PERMISSION_CHECKED, "hands granted, app=$pkg")
        proof.ok(ProofStage.ACTION_ATTEMPTED, "snapshot ui tree")
        val tree = hands.uiTree()
            ?: return ActionResult(action, false, "Не удалось прочитать экран", proof.failed("ui tree unavailable"))
        val treePkg = tree.packageName
        if ((treePkg.isNotBlank() && !treePkg.equals(pkg, ignoreCase = true)) || SensitiveApps.isSensitive(treePkg) || !onScreen(pkg)) {
            return wrongAppResult(action, proof, treePkg.ifBlank { hands.currentPackage() }, pkg)
        }
        proof.ok(ProofStage.ACTION_PERFORMED, "${tree.nodes.size} nodes")
        val text = tree.nodes.filterNot { it.isPassword }.mapNotNull { n ->
            n.visibleText.ifBlank { null }
        }.distinct().joinToString("\n")
        proof.ok(ProofStage.RESULT_VERIFIED, "PLAN0_RESULT=VERIFIED ${tree.nodes.size} nodes")
        return ActionResult(action, true,
            if (text.isBlank()) "На экране нет текста" else text, proof.build(), "hands")
    }

    suspend fun takeScreenshot(grant: ActionGrant, target: String): Bitmap? {
        if (!hands.isActive) return null
        return hands.takeScreenshot()
    }

    suspend fun takeScreenshotAction(grant: ActionGrant, target: String): ActionResult {
        val action = SvetlanaAction.TakeScreenshot(target)
        val proof = proofBuilder.start("PLAN0_TARGET=TAKE_SCREENSHOT app=$target")
        if (!hands.isActive) {
            return ActionResult(action, false, "Для скриншота нужен Hands, но он не включён",
                proof.failed("hands not active"), "hands")
        }
        proof.ok(ProofStage.TARGET_APP_IDENTIFIED, target)
        proof.ok(ProofStage.PERMISSION_CHECKED, "hands granted")
        proof.ok(ProofStage.ACTION_ATTEMPTED, "captureScreen")
        return try {
            val bitmap = hands.takeScreenshot()
            val success = bitmap != null
            proof.add(ProofStage.ACTION_PERFORMED,
                if (success) StepStatus.OK else StepStatus.FAILED,
                if (success) "screenshot bitmap received" else "captureScreen returned null")
            proof.add(ProofStage.RESULT_VERIFIED,
                if (success) StepStatus.OK else StepStatus.FAILED,
                if (success) "PLAN0_RESULT=VERIFIED" else "PLAN0_RESULT=NOT VERIFIED")
            ActionResult(action, success,
                if (success) "Скриншот сделан" else "Не удалось сделать скриншот",
                proof.build(), "hands")
        } catch (t: Throwable) {
            ActionResult(action, false,
                "Не удалось сделать скриншот: " + (t.message ?: "неизвестная ошибка"),
                proof.failed("capture exception: " + t.message), "hands")
        }
    }

    suspend fun pressBack(grant: ActionGrant): ActionResult {
        val proof = proofBuilder.start("PLAN0_TARGET=PRESS_BACK")
        if (!hands.isActive) return handsOff(SvetlanaAction.PressBack, proof)
        proof.ok(ProofStage.PERMISSION_CHECKED, "hands granted")
        proof.ok(ProofStage.ACTION_ATTEMPTED, "global action back")
        val before = hands.treeFingerprint()
        val ok = hands.pressBack()
        proof.add(ProofStage.ACTION_PERFORMED, if (ok) StepStatus.OK else StepStatus.FAILED,
            if (ok) "back performed" else "back failed")
        val changed = ok && verifyScreenChanged(before, 600)
        proof.add(ProofStage.RESULT_VERIFIED,
            if (changed) StepStatus.OK else if (!ok) StepStatus.FAILED else StepStatus.UNVERIFIED,
            if (changed) "PLAN0_RESULT=VERIFIED" else "PLAN0_RESULT=NOT VERIFIED")
        return ActionResult(SvetlanaAction.PressBack, changed,
            if (changed) "Назад, подтверждено"
            else if (ok) "Команда отправлена, но экран не изменился"
            else "Не удалось", proof.build(), "hands")
    }

    suspend fun pressHome(grant: ActionGrant): ActionResult {
        val proof = proofBuilder.start("PLAN0_TARGET=PRESS_HOME")
        if (!hands.isActive) return handsOff(SvetlanaAction.PressHome, proof)
        proof.ok(ProofStage.PERMISSION_CHECKED, "hands granted")
        proof.ok(ProofStage.ACTION_ATTEMPTED, "global action home")
        val before = hands.treeFingerprint()
        val ok = hands.pressHome()
        proof.add(ProofStage.ACTION_PERFORMED, if (ok) StepStatus.OK else StepStatus.FAILED,
            if (ok) "home performed" else "home failed")
        val changed = ok && verifyScreenChanged(before, 600)
        proof.add(ProofStage.RESULT_VERIFIED,
            if (changed) StepStatus.OK else if (!ok) StepStatus.FAILED else StepStatus.UNVERIFIED,
            if (changed) "PLAN0_RESULT=VERIFIED" else "PLAN0_RESULT=NOT VERIFIED")
        return ActionResult(SvetlanaAction.PressHome, changed,
            if (changed) "Домой, подтверждено"
            else if (ok) "Команда отправлена, но экран не изменился"
            else "Не удалось", proof.build(), "hands")
    }

    suspend fun openRecents(grant: ActionGrant): ActionResult {
        val proof = proofBuilder.start("PLAN0_TARGET=OPEN_RECENTS")
        if (!hands.isActive) return handsOff(SvetlanaAction.OpenRecents, proof)
        proof.ok(ProofStage.PERMISSION_CHECKED, "hands granted")
        proof.ok(ProofStage.ACTION_ATTEMPTED, "global action recents")
        val before = hands.treeFingerprint()
        val ok = hands.openRecents()
        proof.add(ProofStage.ACTION_PERFORMED, if (ok) StepStatus.OK else StepStatus.FAILED,
            if (ok) "recents performed" else "recents failed")
        val changed = ok && verifyScreenChanged(before, 600)
        proof.add(ProofStage.RESULT_VERIFIED,
            if (changed) StepStatus.OK else if (!ok) StepStatus.FAILED else StepStatus.UNVERIFIED,
            if (changed) "PLAN0_RESULT=VERIFIED" else "PLAN0_RESULT=NOT VERIFIED")
        return ActionResult(SvetlanaAction.OpenRecents, changed,
            if (changed) "Недавние приложения открыты, подтверждено"
            else if (ok) "Команда отправлена, но экран не изменился"
            else "Не удалось", proof.build(), "hands")
    }

    suspend fun openSettings(): ActionResult = openApp("настройки")

    suspend fun openSystemIntent(intent: android.content.Intent): ActionResult {
        val proof = proofBuilder.start("PLAN0_TARGET=OPEN_SYSTEM_SCREEN")
        proof.ok(ProofStage.TARGET_APP_IDENTIFIED, "system intent: ${intent.action}")
        proof.ok(ProofStage.PERMISSION_CHECKED, "system intent, no permission needed")
        return try {
            context.startActivity(intent)
            proof.ok(ProofStage.ACTION_ATTEMPTED, "startActivity sent")
            val opened = launchVerifier.waitForAnyActivity(skipPackage = context.packageName)
            if (opened) {
                proof.ok(ProofStage.ACTION_PERFORMED, "external activity in foreground")
                proof.ok(ProofStage.RESULT_VERIFIED, "PLAN0_STATUS=ACTION_PERFORMED PLAN0_RESULT=VERIFIED")
                ActionResult(SvetlanaAction.OpenApp("system"), true, "Открываю.", proof.build(), "intent")
            } else {
                proof.add(ProofStage.ACTION_PERFORMED, StepStatus.UNVERIFIED, "no foreground change detected")
                proof.add(ProofStage.RESULT_VERIFIED, StepStatus.UNVERIFIED, "PLAN0_RESULT=NOT PROVEN")
                ActionResult(SvetlanaAction.OpenApp("system"), false,
                    context.getString(R.string.reply_action_failed), proof.build(), "intent")
            }
        } catch (t: Throwable) {
            ActionResult(SvetlanaAction.OpenApp("system"), false,
                context.getString(R.string.reply_action_failed),
                proof.failed("startActivity failed: ${t.message}"))
        }
    }

    suspend fun composeMessage(grant: ActionGrant, appTarget: String, contact: String, text: String): ActionResult {
        val action = SvetlanaAction.ComposeMessage(appTarget, contact, text)
        if (!hands.isActive) {
            return ActionResult(action, false,
                "Для этого действия нужен Hands, но он не включён", emptyList())
        }
        val steps = mutableListOf<ProofStep>()
        steps += ProofStep(ProofStage.PLAN, StepStatus.OK,
            "PLAN_TARGET=COMPOSE_MESSAGE app=$appTarget contact=$contact text=${text.take(32)}")
        val moved = { current: String ->
            steps += ProofStep(ProofStage.RESULT_VERIFIED, StepStatus.FAILED, "PLAN_RESULT=NOT VERIFIED foreground=$current")
            ActionResult(action, false, "На экране другое приложение — сообщение не отправлено", steps, "hands")
        }

        val appName = if (appTarget.isBlank()) DEFAULT_MESSENGER else appTarget
        val appPkg = (pin(appName) as? Pin.To)?.pkg
            ?: return ActionResult(action, false, "Приложение «${appName.take(40)}» не найдено", steps, "hands")
        val open = openApp(appName)
        steps += open.proof
        if (!open.success) {
            steps += ProofStep(ProofStage.RESULT_VERIFIED, StepStatus.FAILED, "PLAN_RESULT=NOT VERIFIED: приложение не открыто")
            return ActionResult(action, false, open.message, steps, open.way)
        }
        kotlinx.coroutines.delay(UI_SETTLE_MS)

        val chat = openChatWithContact(contact, appPkg, steps)
        if (!chat.ok) {
            steps += ProofStep(ProofStage.RESULT_VERIFIED, StepStatus.FAILED, "PLAN_RESULT=NOT VERIFIED: чат не открыт")
            return ActionResult(action, false, chat.message, steps, "hands")
        }

        val field = hands.findEditableField()
        if (field == null) {
            steps += ProofStep(ProofStage.ACTION_ATTEMPTED, StepStatus.FAILED, "поле ввода не найдено")
            steps += ProofStep(ProofStage.RESULT_VERIFIED, StepStatus.FAILED, "PLAN_RESULT=NOT VERIFIED")
            return ActionResult(action, false, "Не нашла поле ввода для сообщения", steps, "hands")
        }
        if (!onScreen(appPkg)) return moved(hands.currentPackage())
        val typed = hands.inputText(field, text)
        steps += ProofStep(ProofStage.ACTION_ATTEMPTED, StepStatus.OK, "текст введён: «${text.take(32)}»")
        val textEntered = typed && hands.verifyTextEntered(field, text)
        steps += ProofStep(ProofStage.ACTION_PERFORMED,
            if (textEntered) StepStatus.OK else StepStatus.FAILED,
            if (textEntered) "PLAN_STEP3=TYPE_TEXT PLAN_STEP3_STATUS=ACTION_PERFORMED"
            else "PLAN_STEP3_STATUS=FAILED")
        if (!textEntered) {
            steps += ProofStep(ProofStage.RESULT_VERIFIED, StepStatus.FAILED, "PLAN_RESULT=NOT VERIFIED: текст не введён")
            return ActionResult(action, false, "Не удалось напечатать сообщение", steps, "hands")
        }

        val send = hands.findSendButton()
        if (send == null) {
            steps += ProofStep(ProofStage.ACTION_ATTEMPTED, StepStatus.FAILED, "кнопка отправки не найдена")
            steps += ProofStep(ProofStage.RESULT_VERIFIED, StepStatus.FAILED, "PLAN_RESULT=NOT VERIFIED")
            return ActionResult(action, false, "Не нашла кнопку отправки сообщения", steps, "hands")
        }
        if (!onScreen(appPkg)) return moved(hands.currentPackage())
        val sent = hands.clickNode(send)
        steps += ProofStep(ProofStage.ACTION_ATTEMPTED, StepStatus.OK, "нажата кнопка отправки")

        kotlinx.coroutines.delay(UI_SETTLE_MS)
        val stillThere = onScreen(appPkg)
        val messageVisible = stillThere && hands.verifyTextVisibleInNonEditableNode(text)
        val verified = sent && messageVisible
        steps += ProofStep(ProofStage.ACTION_PERFORMED, if (sent) StepStatus.OK else StepStatus.FAILED,
            "PLAN_STEP4=SEND PLAN_STEP4_STATUS=${if (sent) "ACTION_PERFORMED" else "FAILED"}")
        steps += ProofStep(ProofStage.RESULT_VERIFIED, if (verified) StepStatus.OK else StepStatus.UNVERIFIED,
            if (verified) "PLAN_RESULT=VERIFIED message_visible_non_editable"
            else "PLAN_RESULT=NOT PROVEN message_visible_non_editable=$messageVisible same_app=$stillThere")

        return ActionResult(action, verified,
            if (verified) "Сообщение «${text.take(24)}» отправлено контакту $contact"
            else "Не удалось подтвердить отправку сообщения",
            steps, "hands")
    }

    private suspend fun openChatWithContact(contact: String, appPkg: String, steps: MutableList<ProofStep>): StepResult {
        steps += ProofStep(ProofStage.TARGET_APP_IDENTIFIED, StepStatus.OK, "контакт: $contact app=$appPkg")
        val away = StepResult(false, "На экране другое приложение — действие отменено")

        val direct = hands.findElement(contact)
        if (direct != null && direct.isClickable) {
            if (!onScreen(appPkg)) return away
            val clicked = hands.clickNode(direct)
            kotlinx.coroutines.delay(UI_SETTLE_MS)
            val hasInput = onScreen(appPkg) && hands.findEditableField() != null
            steps += ProofStep(ProofStage.ACTION_ATTEMPTED, StepStatus.OK, "чат найден в списке, нажатие")
            steps += stepPerformed(hasInput, "PLAN_STEP2=OPEN_CHAT")
            if (clicked && hasInput) return StepResult(true, "Чат с $contact открыт")
        }

        val searchEntry = hands.findElement("поиск")
            ?: hands.findElement("search")
            ?: hands.findElement("new chat")
            ?: hands.findElement("новый чат")
        if (searchEntry == null) {
            steps += ProofStep(ProofStage.ACTION_ATTEMPTED, StepStatus.FAILED,
                "чат не найден в списке и нет кнопки поиска")
            return StepResult(false, "Не нашла чат с $contact")
        }
        if (!onScreen(appPkg)) return away
        hands.clickNode(searchEntry)
        kotlinx.coroutines.delay(UI_SETTLE_MS)
        val searchField = hands.findEditableField()
            ?: run {
                steps += ProofStep(ProofStage.ACTION_ATTEMPTED, StepStatus.FAILED, "поле поиска не появилось")
                return StepResult(false, "Не удалось открыть поиск контакта")
            }
        if (!onScreen(appPkg)) return away
        hands.inputText(searchField, contact)
        kotlinx.coroutines.delay(UI_SETTLE_MS)
        val found = hands.findElement(contact)
        if (found == null || !found.isClickable) {
            steps += ProofStep(ProofStage.ACTION_ATTEMPTED, StepStatus.FAILED, "контакт не найден в результатах поиска")
            return StepResult(false, "Контакт $contact не найден")
        }
        if (!onScreen(appPkg)) return away
        val clicked = hands.clickNode(found)
        kotlinx.coroutines.delay(UI_SETTLE_MS)
        val hasInput = onScreen(appPkg) && hands.findEditableField() != null
        steps += stepPerformed(clicked && hasInput, "PLAN_STEP2=OPEN_CHAT")
        return if (clicked && hasInput) StepResult(true, "Чат с $contact открыт")
        else StepResult(false, "Не удалось открыть чат с $contact")
    }

    private fun stepPerformed(ok: Boolean, plan: String): ProofStep =
        ProofStep(ProofStage.ACTION_PERFORMED, if (ok) StepStatus.OK else StepStatus.FAILED,
            "$plan PLAN_STEP2_STATUS=${if (ok) "ACTION_PERFORMED" else "FAILED"}")

    private data class StepResult(val ok: Boolean, val message: String)

    companion object {
        private const val UI_SETTLE_MS = 600L
        private const val DEFAULT_MESSENGER = "whatsapp"
        private val GENERIC_TARGETS = setOf("экран", "текущее", "текущий", "этот", "здесь", "current", "screen", "this")
        private val PACKAGE_RE = Regex("^[a-zA-Z][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+$")
    }

    suspend fun makeCall(grant: ActionGrant, contact: String): ActionResult {
        val action = SvetlanaAction.MakeCall(contact)
        val callPermission = permissionManager.isGranted(android.Manifest.permission.CALL_PHONE)
        val proof = proofBuilder.start("PLAN0_TARGET=MAKE_CALL contact=$contact mode=dial call_permission=$callPermission")
        return try {
            context.startActivity(intentResolver.dial(contact).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            proof.ok(ProofStage.ACTION_ATTEMPTED, "dial intent sent")
            val opened = launchVerifier.waitForAnyActivity(skipPackage = context.packageName)
            proof.add(ProofStage.ACTION_PERFORMED, if (opened) StepStatus.OK else StepStatus.UNVERIFIED,
                if (opened) "dialer in foreground" else "no foreground change detected")
            proof.add(ProofStage.RESULT_VERIFIED, StepStatus.UNVERIFIED,
                "PLAN0_RESULT=NOT PROVEN user places the call in the phone app")
            ActionResult(action, opened,
                if (opened) "Открыла набор номера: $contact. Нажмите вызов"
                else "Не удалось подтвердить, что открылся набор номера",
                proof.build(), "intent")
        } catch (t: Throwable) {
            ActionResult(action, false, "Не удалось открыть набор номера", proof.failed("startActivity failed"))
        }
    }

    suspend fun sendMessage(grant: ActionGrant, contact: String, text: String): ActionResult {
        val action = SvetlanaAction.SendMessage(contact, text)
        val proof = proofBuilder.start("PLAN0_TARGET=SMS_DRAFT contact=$contact")
        return try {
            val intent = intentResolver.sms(contact, text)
            context.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            proof.ok(ProofStage.ACTION_ATTEMPTED, "startActivity sent")
            val opened = launchVerifier.waitForAnyActivity(skipPackage = context.packageName)
            proof.add(ProofStage.ACTION_PERFORMED, if (opened) StepStatus.OK else StepStatus.UNVERIFIED,
                if (opened) "sms app with draft in foreground" else "no foreground change detected")
            proof.add(ProofStage.RESULT_VERIFIED, StepStatus.UNVERIFIED,
                "PLAN0_RESULT=NOT PROVEN message is sent by the user in the SMS app")
            ActionResult(action, opened,
                if (opened) "Сообщение для $contact подготовлено. Нажмите «Отправить»"
                else "Не удалось подтвердить, что открылось приложение SMS",
                proof.build(), "intent")
        } catch (t: Throwable) {
            ActionResult(action, false, "Не удалось подготовить сообщение", proof.failed("startActivity failed"))
        }
    }

    fun share(grant: ActionGrant, text: String): ActionResult = try {
        context.startActivity(intentResolver.shareText(text))
        ActionResult(SvetlanaAction.Share(text), true, "Открыла «Поделиться»: выберите, куда отправить", emptyList(), "intent")
    } catch (t: Throwable) {
        ActionResult(SvetlanaAction.Share(text), false, "Не удалось", emptyList())
    }

    fun riskOf(action: SvetlanaAction): ActionRisk = ActionRiskPolicy.riskOf(action)

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

    fun proof(planId: String, target: String): ActionProof {
        val attempted = steps.any { it.stage == ProofStage.ACTION_ATTEMPTED }
        val platformAccepted = steps.any {
            (it.stage == ProofStage.ACTION_PERFORMED || it.stage == ProofStage.ACTION_ACCEPTED) &&
                it.status == StepStatus.OK
        }
        val post = steps.lastOrNull { it.stage == ProofStage.RESULT_VERIFIED }?.status
        val postState = when (post) {
            StepStatus.OK -> VerificationState.VERIFIED
            StepStatus.FAILED -> VerificationState.FAILED
            StepStatus.UNVERIFIED -> VerificationState.NOT_CHECKED
            else -> VerificationState.NOT_CHECKED
        }
        val result = if (postState == VerificationState.VERIFIED) VerificationState.VERIFIED
                     else VerificationState.FAILED
        return ActionProof(
            planId = planId,
            target = target,
            precondition = VerificationState.VERIFIED,
            attempted = attempted,
            platformAccepted = platformAccepted,
            postcondition = postState,
            result = result
        )
    }

    fun accepted(detail: String): ProofBuilder {
        steps.add(ProofStep(ProofStage.ACTION_ACCEPTED, StepStatus.OK, detail))
        return this
    }

    fun postcondition(state: VerificationState, detail: String): ProofBuilder {
        val status = when (state) {
            VerificationState.VERIFIED -> StepStatus.OK
            VerificationState.FAILED -> StepStatus.FAILED
            else -> StepStatus.UNVERIFIED
        }
        steps.add(ProofStep(ProofStage.POSTCONDITION_CHECKED, status, detail))
        return this
    }
}
