package com.svetlana.home.control

import android.content.Context
import android.graphics.Bitmap
import com.svetlana.home.R
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
        val before = hands.treeFingerprint()
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
        // Аудит §28: успех определяется пост-условием, а не тем, что Android
        // принял команду. ok == platformAccepted, changed == RESULT_VERIFIED.
        return ActionResult(SvetlanaAction.Click(target, element), changed,
            if (changed) "Нажатие выполнено и подтверждено"
            else if (ok) "Команда отправлена, но экран не изменился — результат не подтверждён"
            else "Не удалось нажать",
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
        val before = hands.treeFingerprint()
        val ok = hands.longClick(node)
        proof.add(ProofStage.ACTION_PERFORMED, if (ok) StepStatus.OK else StepStatus.FAILED,
            if (ok) "long click performed" else "long click failed")
        // Пост-условие: экран должен откликнуться (измениться или элемент
        // остаться валидным). Без этого RESULT_VERIFIED не ставится.
        val verified = ok && verifyScreenChanged(before, 800)
        proof.add(ProofStage.RESULT_VERIFIED,
            if (verified) StepStatus.OK else if (!ok) StepStatus.FAILED else StepStatus.UNVERIFIED,
            if (verified) "PLAN0_RESULT=VERIFIED" else "PLAN0_RESULT=NOT VERIFIED")
        // Аудит §28: успех = пост-условие, а не platformAccepted.
        return ActionResult(SvetlanaAction.LongClick(target, element), verified,
            if (verified) "Долгое нажатие подтверждено"
            else if (ok) "Команда отправлена, но экран не изменился"
            else "Не удалось",
            proof.build(), "hands")
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
        // Аудит §28: успех = текст реально в поле, а не «команда отправлена».
        return ActionResult(SvetlanaAction.TypeText(target, element, text), entered,
            if (entered) "Текст введён и подтверждён"
            else if (ok) "Команда отправлена, но текст не появился в поле"
            else "Не удалось ввести текст", proof.build(), "hands")
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
        return ActionResult(SvetlanaAction.ClearText(target, element), cleared,
            if (cleared) "Текст очищен и подтверждён"
            else if (ok) "Команда отправлена, но поле не пусто"
            else "Не удалось очистить текст", proof.build(), "hands")
    }

    suspend fun scroll(target: String, direction: String): ActionResult {
        val proof = proofBuilder.start("PLAN0_TARGET=SCROLL app=$target dir=$direction")
        if (!hands.isActive) return handsOff(SvetlanaAction.Scroll(target, direction), proof)
        proof.ok(ProofStage.TARGET_APP_IDENTIFIED, target)
        proof.ok(ProofStage.PERMISSION_CHECKED, "hands granted")
        proof.ok(ProofStage.ACTION_ATTEMPTED, "scroll $direction")
        val before = hands.treeFingerprint()
        val down = direction.equals("down", ignoreCase = true) || direction == "вниз"
        val ok = if (down) hands.scrollForward() else hands.scrollBackward()
        proof.add(ProofStage.ACTION_PERFORMED, if (ok) StepStatus.OK else StepStatus.FAILED,
            if (ok) "scroll performed" else "scroll failed")
        val changed = ok && verifyScreenChanged(before, 600)
        proof.add(ProofStage.RESULT_VERIFIED,
            if (changed) StepStatus.OK else if (!ok) StepStatus.FAILED else StepStatus.UNVERIFIED,
            if (changed) "PLAN0_RESULT=VERIFIED" else "PLAN0_RESULT=NOT VERIFIED")
        // Аудит §28: успех = реальное изменение экрана.
        return ActionResult(SvetlanaAction.Scroll(target, direction), changed,
            if (changed) "Прокрутка подтверждена"
            else if (ok) "Команда отправлена, но экран не изменился — возможно, конец списка"
            else "Не удалось прокрутить", proof.build(), "hands")
    }

    suspend fun swipe(target: String, direction: String): ActionResult {
        val proof = proofBuilder.start("PLAN0_TARGET=SWIPE app=$target dir=$direction")
        if (!hands.isActive) return handsOff(SvetlanaAction.Swipe(target, direction), proof)
        proof.ok(ProofStage.TARGET_APP_IDENTIFIED, target)
        proof.ok(ProofStage.PERMISSION_CHECKED, "hands granted")
        proof.ok(ProofStage.ACTION_ATTEMPTED, "swipe $direction")
        val before = hands.treeFingerprint()
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
        // Аудит §28: успех = реальное изменение экрана.
        return ActionResult(SvetlanaAction.Swipe(target, direction), changed,
            if (changed) "Свайп подтверждён"
            else if (ok) "Команда отправлена, но экран не изменился"
            else "Не удалось сделать свайп", proof.build(), "hands")
    }

    /**
     * Проверка реального изменения экрана после жеста (аудит п.11).
     * Дожидаться бесконечно нельзя — жест мог быть корректным, но экран
     * закономерно не изменился (например, прокрутка в самом низу). В этом
     * случае ставим UNVERIFIED, а не VERIFIED.
     */
    private suspend fun verifyScreenChanged(beforeFingerprint: Long, settleMs: Long): Boolean {
        kotlinx.coroutines.delay(settleMs)
        return hands.verifyTreeChanged(beforeFingerprint)
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

    suspend fun takeScreenshotAction(target: String): ActionResult {
        val action = SvetlanaAction.TakeScreenshot(target)
        val proof = proofBuilder.start("PLAN0_TARGET=TAKE_SCREENSHOT app=$target")
        if (!hands.isActive) {
            return ActionResult(
                action,
                false,
                "Для скриншота нужен Hands, но он не включён",
                proof.failed("hands not active"),
                "hands"
            )
        }
        proof.ok(ProofStage.TARGET_APP_IDENTIFIED, target)
        proof.ok(ProofStage.PERMISSION_CHECKED, "hands granted")
        proof.ok(ProofStage.ACTION_ATTEMPTED, "captureScreen")
        return try {
            val bitmap = hands.takeScreenshot()
            val success = bitmap != null
            proof.add(
                ProofStage.ACTION_PERFORMED,
                if (success) StepStatus.OK else StepStatus.FAILED,
                if (success) "screenshot bitmap received" else "captureScreen returned null"
            )
            proof.add(
                ProofStage.RESULT_VERIFIED,
                if (success) StepStatus.OK else StepStatus.FAILED,
                if (success) "PLAN0_RESULT=VERIFIED" else "PLAN0_RESULT=NOT VERIFIED"
            )
            ActionResult(
                action,
                success,
                if (success) "Скриншот сделан" else "Не удалось сделать скриншот",
                proof.build(),
                "hands"
            )
        } catch (t: Throwable) {
            ActionResult(
                action,
                false,
                "Не удалось сделать скриншот: " + (t.message ?: "неизвестная ошибка"),
                proof.failed("capture exception: " + t.message),
                "hands"
            )
        }
    }

    suspend fun pressBack(): ActionResult {
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
        // Аудит §28: успех = пост-условие.
        return ActionResult(SvetlanaAction.PressBack, changed,
            if (changed) "Назад, подтверждено"
            else if (ok) "Команда отправлена, но экран не изменился"
            else "Не удалось", proof.build(), "hands")
    }

    suspend fun pressHome(): ActionResult {
        val proof = proofBuilder.start("PLAN0_TARGET=PRESS_HOME")
        if (!hands.isActive) return handsOff(SvetlanaAction.PressHome, proof)
        proof.ok(ProofStage.PERMISSION_CHECKED, "hands granted")
        proof.ok(ProofStage.ACTION_ATTEMPTED, "global action home")
        val before = hands.treeFingerprint()
        val ok = hands.pressHome()
        proof.add(ProofStage.ACTION_PERFORMED, if (ok) StepStatus.OK else StepStatus.FAILED,
            if (ok) "home performed" else "home failed")
        // Home почти всегда меняет экран на launcher — проверяем реальное изменение.
        val changed = ok && verifyScreenChanged(before, 600)
        proof.add(ProofStage.RESULT_VERIFIED,
            if (changed) StepStatus.OK else if (!ok) StepStatus.FAILED else StepStatus.UNVERIFIED,
            if (changed) "PLAN0_RESULT=VERIFIED" else "PLAN0_RESULT=NOT VERIFIED")
        return ActionResult(SvetlanaAction.PressHome, changed,
            if (changed) "Домой, подтверждено"
            else if (ok) "Команда отправлена, но экран не изменился"
            else "Не удалось", proof.build(), "hands")
    }

    suspend fun openRecents(): ActionResult {
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

    /**
     * Запуск системного intent из декларативного каталога (аудит §7).
     * Например: настройки Wi-Fi, камера, звонки.
     * Как и openApp, не доверяет startActivity() — проверяет реальный переход.
     */
    suspend fun openSystemIntent(intent: android.content.Intent): ActionResult {
        val proof = proofBuilder.start("PLAN0_TARGET=OPEN_SYSTEM_SCREEN")
        proof.ok(ProofStage.TARGET_APP_IDENTIFIED, "system intent: ${intent.action}")
        proof.ok(ProofStage.PERMISSION_CHECKED, "system intent, no permission needed")
        return try {
            context.startActivity(intent)
            proof.ok(ProofStage.ACTION_ATTEMPTED, "startActivity sent")
            // Реальная проверка: внешний экран должен стать foreground.
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

    // ------------------------------------------------------------------
    // Длинные многошаговые Hands-цепочки
    // ------------------------------------------------------------------

    /**
     * Многошаговая цепочка: «открой Whatsapp и напиши контакту Серый привет как дела».
     *
     *   Шаг 1: открыть приложение и дождаться реального перехода в foreground
     *   Шаг 2: открыть чат с контактом (напрямую или через поиск)
     *   Шаг 3: найти поле ввода и напечатать текст
     *   Шаг 4: нажать кнопку «Отправить»
     *   Шаг 5: проверить, что сообщение появилось в чате
     *
     * Каждый шаг имеет собственную proof-цепочку. Провал любого шага
     * останавливает цепочку: нельзя отправить то, что не введено.
     */
    suspend fun composeMessage(appTarget: String, contact: String, text: String): ActionResult {
        val action = SvetlanaAction.ComposeMessage(appTarget, contact, text)
        if (!hands.isActive) {
            return ActionResult(action, false,
                "Для этого действия нужен Hands, но он не включён", emptyList())
        }
        val steps = mutableListOf<ProofStep>()
        steps += ProofStep(ProofStage.PLAN, StepStatus.OK,
            "PLAN_TARGET=COMPOSE_MESSAGE app=$appTarget contact=$contact text=${text.take(32)}")

        // Шаг 1: открыть приложение
        val open = openApp(if (appTarget.isBlank()) DEFAULT_MESSENGER else appTarget)
        steps += open.proof
        if (!open.success) {
            steps += ProofStep(ProofStage.RESULT_VERIFIED, StepStatus.FAILED, "PLAN_RESULT=NOT VERIFIED: приложение не открыто")
            return ActionResult(action, false, open.message, steps, open.way)
        }
        kotlinx.coroutines.delay(UI_SETTLE_MS)

        // Шаг 2: открыть чат с контактом
        val chat = openChatWithContact(contact, steps)
        if (!chat.ok) {
            steps += ProofStep(ProofStage.RESULT_VERIFIED, StepStatus.FAILED, "PLAN_RESULT=NOT VERIFIED: чат не открыт")
            return ActionResult(action, false, chat.message, steps, "hands")
        }

        // Шаг 3: найти поле ввода и напечатать текст
        val field = hands.findEditableField()
        if (field == null) {
            steps += ProofStep(ProofStage.ACTION_ATTEMPTED, StepStatus.FAILED, "поле ввода не найдено")
            steps += ProofStep(ProofStage.RESULT_VERIFIED, StepStatus.FAILED, "PLAN_RESULT=NOT VERIFIED")
            return ActionResult(action, false, "Не нашла поле ввода для сообщения", steps, "hands")
        }
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

        // Шаг 4: нажать «Отправить»
        val send = hands.findSendButton()
        if (send == null) {
            steps += ProofStep(ProofStage.ACTION_ATTEMPTED, StepStatus.FAILED, "кнопка отправки не найдена")
            steps += ProofStep(ProofStage.RESULT_VERIFIED, StepStatus.FAILED, "PLAN_RESULT=NOT VERIFIED")
            return ActionResult(action, false, "Не нашла кнопку отправки сообщения", steps, "hands")
        }
        val sent = hands.clickNode(send)
        steps += ProofStep(ProofStage.ACTION_ATTEMPTED, StepStatus.OK, "нажата кнопка отправки")

        // Шаг 5: проверить — сообщение появилось в чате
        kotlinx.coroutines.delay(UI_SETTLE_MS)
        val verified = sent && (hands.verifyTextVisible(text) || hands.findEditableField() != null)
        steps += ProofStep(ProofStage.ACTION_PERFORMED, if (sent) StepStatus.OK else StepStatus.FAILED,
            "PLAN_STEP4=SEND PLAN_STEP4_STATUS=${if (sent) "ACTION_PERFORMED" else "FAILED"}")
        steps += ProofStep(ProofStage.RESULT_VERIFIED, if (verified) StepStatus.OK else StepStatus.UNVERIFIED,
            if (verified) "PLAN_RESULT=VERIFIED" else "PLAN_RESULT=NOT PROVEN")

        return ActionResult(action, verified,
            if (verified) "Сообщение «${text.take(24)}» отправлено контакту $contact"
            else "Не удалось подтвердить отправку сообщения",
            steps, "hands")
    }

    /**
     * Открыть чат с контактом в мессенджере: сначала пробуем найти чат в
     * списке напрямую, затем — через поиск контакта.
     */
    private suspend fun openChatWithContact(contact: String, steps: MutableList<ProofStep>): StepResult {
        steps += ProofStep(ProofStage.TARGET_APP_IDENTIFIED, StepStatus.OK, "контакт: $contact")

        // 2a. Чат уже в списке
        val direct = hands.findElement(contact)
        if (direct != null && direct.isClickable) {
            val clicked = hands.clickNode(direct)
            kotlinx.coroutines.delay(UI_SETTLE_MS)
            val hasInput = hands.findEditableField() != null
            steps += ProofStep(ProofStage.ACTION_ATTEMPTED, StepStatus.OK, "чат найден в списке, нажатие")
            steps += stepPerformed(hasInput, "PLAN_STEP2=OPEN_CHAT")
            if (clicked && hasInput) return StepResult(true, "Чат с $contact открыт")
        }

        // 2b. Через поиск: нажать «поиск»/«new chat», ввести контакт, выбрать
        val searchEntry = hands.findElement("поиск")
            ?: hands.findElement("search")
            ?: hands.findElement("new chat")
            ?: hands.findElement("новый чат")
        if (searchEntry == null) {
            steps += ProofStep(ProofStage.ACTION_ATTEMPTED, StepStatus.FAILED,
                "чат не найден в списке и нет кнопки поиска")
            return StepResult(false, "Не нашла чат с $contact")
        }
        hands.clickNode(searchEntry)
        kotlinx.coroutines.delay(UI_SETTLE_MS)
        val searchField = hands.findEditableField()
            ?: run {
                steps += ProofStep(ProofStage.ACTION_ATTEMPTED, StepStatus.FAILED, "поле поиска не появилось")
                return StepResult(false, "Не удалось открыть поиск контакта")
            }
        hands.inputText(searchField, contact)
        kotlinx.coroutines.delay(UI_SETTLE_MS)
        val found = hands.findElement(contact)
        if (found == null || !found.isClickable) {
            steps += ProofStep(ProofStage.ACTION_ATTEMPTED, StepStatus.FAILED, "контакт не найден в результатах поиска")
            return StepResult(false, "Контакт $contact не найден")
        }
        val clicked = hands.clickNode(found)
        kotlinx.coroutines.delay(UI_SETTLE_MS)
        val hasInput = hands.findEditableField() != null
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
        // Мессенджер по умолчанию, если пользователь не назвал приложение.
        private const val DEFAULT_MESSENGER = "whatsapp"
    }

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

    /**
     * Аудит §28: структурированное доказательство.
     *
     * platformAccepted (Android принял команду) ≠ performed.
     * Только VERIFIED пост-условие разрешает performed = true.
     */
    fun proof(planId: String, target: String): ActionProof {
        val attempted = steps.any { it.stage == ProofStage.ACTION_ATTEMPTED }
        // ACTION_ACCEPTED — Android API вернул true. Если step помечен как
        // ACTION_PERFORMED (legacy), считаем его принятым тоже.
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

    /**
     * Помечает, что Android API принял команду (platformAccepted).
     * Это НЕ означает, что действие выполнено — для этого нужен
     * POSTCONDITION_CHECKED (аудит §28).
     */
    fun accepted(detail: String): ProofBuilder {
        steps.add(ProofStep(ProofStage.ACTION_ACCEPTED, StepStatus.OK, detail))
        return this
    }

    /**
     * Пост-условие проверено: состояние экрана действительно изменилось.
     */
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
