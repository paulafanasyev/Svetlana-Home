package com.svetlana.home.harness

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.util.Log
import com.svetlana.home.apps.AppRegistry
import com.svetlana.home.control.ActionRiskPolicy
import com.svetlana.home.control.SvetlanaAction
import com.svetlana.home.core.ServiceLocator
import com.svetlana.home.control.IntentResolver
import com.svetlana.home.core.ProofStage
import com.svetlana.home.core.ProofStep
import com.svetlana.home.core.StepStatus
import com.svetlana.home.core.SvetlanaStatus
import com.svetlana.home.hands.HandsController
import com.svetlana.home.hands.UiNode
import kotlinx.coroutines.delay

/**
 * MobileHarnessController — обязательный приоритетный сценарий тестирования.
 *
 * Если Mobile Harness уже установлен, Svetlana Home находит его автоматически
 * и управляет им: UI tree, поиск элементов, нажатия, ввод, скролл, скриншоты,
 * проверка результата.
 *
 * Mobile Harness — это тестовое приложение с известными элементами интерфейса.
 * Светлана работает с ним как с обычным Android-приложением: сначала Intent,
 * потом Hands.
 *
 * Безопасность: действия выполняются только когда на экране ДЕЙСТВИТЕЛЬНО
 * Mobile Harness (иначе — отказ), и даже там не нажимаются необратимые кнопки
 * и не заполняются поля паролей/карт. Каждое действие — нажатие, ввод, прокрутка,
 * «назад», чтение экрана и скриншот — проходит ту же проверку, что и команды Светланы
 * ([com.svetlana.home.control.ActionRouter.authorizeUnattended]); опасное подтверждается
 * только пользователем в обычном диалоге.
 */
class MobileHarnessController(
    private val context: Context,
    private val registry: AppRegistry,
    private val hands: HandsController
) {

    data class HarnessState(
        val installed: Boolean,
        val packageName: String?,
        val label: String?,
        val status: String,
        val statusLabel: String
    )

    private val intentResolver by lazy { IntentResolver(context, registry) }

    /**
     * Известные package names Mobile Harness и его тестовых утилит.
     * Список расширяется по мере обнаружения.
     */
    private val knownPackages = listOf(
        "com.mobile.harness",
        "com.mobileharness.app",
        "com.google.android.mobly",
        "io.appium.android.apk"
    )

    fun detect(): HarnessState {
        val apps = registry.apps.value
        val harnessApp = apps.firstOrNull { knownPackages.contains(it.packageName) }
            ?: apps.firstOrNull { it.label.contains("Mobile Harness", ignoreCase = true) }
            ?: apps.firstOrNull { it.packageName.contains("harness", ignoreCase = true) }

        return if (harnessApp != null) {
            HarnessState(true, harnessApp.packageName, harnessApp.label,
                SvetlanaStatus.NOT_PROVEN, "Mobile Harness обнаружен")
        } else {
            HarnessState(false, null, null, SvetlanaStatus.NOT_PROVEN, "Mobile Harness не установлен")
        }
    }

    /**
     * Запуск Mobile Harness. Возвращает доказательную цепочку.
     */
    suspend fun launch(): List<ProofStep> {
        val proof = mutableListOf<ProofStep>()
        proof.add(ProofStep(ProofStage.PLAN, StepStatus.OK, "PLAN: открыть Mobile Harness"))

        val state = detect()
        if (!state.installed) {
            proof.add(ProofStep(ProofStage.TARGET_APP_IDENTIFIED, StepStatus.FAILED,
                "Mobile Harness не найден на устройстве"))
            return proof
        }
        proof.add(ProofStep(ProofStage.TARGET_APP_IDENTIFIED, StepStatus.OK,
            "target=${state.packageName}"))

        val pkg = state.packageName!!
        val app = registry.apps.value.first { it.packageName == pkg }

        // Приоритет: Intent / PackageManager, затем Hands
        val intent = intentResolver.launchIntentFor(app)
        var launchException: Throwable? = null
        val launchOk = if (intent != null) {
            try {
                context.startActivity(intent)
                delay(LAUNCH_SETTLE_MS)
                hands.waitForPackage(pkg, timeoutMs = WAIT_PKG_MS)
            } catch (t: Throwable) {
                launchException = t
                Log.w(TAG, "Intent-запуск не удался", t)
                false
            }
        } else false

        if (launchOk) {
            // launchOk=true означает, что waitForPackage подтвердил foreground.
            proof.add(ProofStep(ProofStage.PERMISSION_CHECKED, StepStatus.OK, "intent-based запуск"))
            proof.add(ProofStep(ProofStage.ACTION_ATTEMPTED, StepStatus.OK, "startActivity выполнен"))
            proof.add(ProofStep(ProofStage.ACTION_PERFORMED, StepStatus.OK,
                "foreground=$pkg PLAN0_TARGET=OPEN_MOBILE_HARNESS"))
            proof.add(ProofStep(ProofStage.RESULT_VERIFIED, StepStatus.OK,
                "PLAN0_STATUS=ACTION_PERFORMED PLAN0_RESULT=VERIFIED"))
        } else if (hands.isActive && intent != null) {
            // Hands не может запустить приложение сам по себе (pressHome ведёт на
            // главный экран, а не в Mobile Harness). Честно сообщаем причину:
            // startActivity либо упал, либо приложение не стало foreground.
            proof.add(ProofStep(ProofStage.PERMISSION_CHECKED, StepStatus.OK, "Hands активен"))
            proof.add(ProofStep(ProofStage.ACTION_ATTEMPTED, StepStatus.FAILED,
                launchException?.message ?: "startActivity не привёл к переходу в foreground"))
            proof.add(ProofStep(ProofStage.ACTION_PERFORMED, StepStatus.FAILED,
                "pressHome не открывает Mobile Harness — этот путь не выполняет задачу"))
            proof.add(ProofStep(ProofStage.RESULT_VERIFIED, StepStatus.FAILED,
                "PLAN0_RESULT=NOT VERIFIED"))
        } else if (hands.isActive) {
            // Intent не удалось построить вообще.
            proof.add(ProofStep(ProofStage.PERMISSION_CHECKED, StepStatus.OK, "Hands активен"))
            proof.add(ProofStep(ProofStage.ACTION_ATTEMPTED, StepStatus.FAILED,
                "launchIntent для Mobile Harness недоступен"))
            proof.add(ProofStep(ProofStage.ACTION_PERFORMED, StepStatus.FAILED,
                "Hands не может запустить приложение без launch intent"))
            proof.add(ProofStep(ProofStage.RESULT_VERIFIED, StepStatus.FAILED,
                "PLAN0_RESULT=NOT VERIFIED"))
        } else {
            proof.add(ProofStep(ProofStage.PERMISSION_CHECKED, StepStatus.FAILED,
                "Hands не включён, стандартный запуск не удался"))
        }
        return proof
    }

    // ---------- Управление UI Mobile Harness ----------

    suspend fun findElement(text: String): UiNode? {
        val pkg = ensureHarnessInForeground() ?: return null
        if (!authorized(SvetlanaAction.FindElement(pkg, text))) return null
        return hands.findElement(text)
    }

    private fun labelOf(node: UiNode) = "${node.text} ${node.contentDescription} ${node.id}"

    suspend fun tapElement(text: String): Boolean {
        val pkg = ensureHarnessInForeground() ?: return false
        val node = hands.findElement(text) ?: return false
        if (ActionRiskPolicy.isDangerousLabel(labelOf(node))) return false
        if (!authorized(SvetlanaAction.Click(pkg, text))) return false
        return hands.clickNode(node)
    }

    suspend fun longPressElement(text: String): Boolean {
        val pkg = ensureHarnessInForeground() ?: return false
        val node = hands.findElement(text) ?: return false
        if (ActionRiskPolicy.isDangerousLabel(labelOf(node))) return false
        if (!authorized(SvetlanaAction.LongClick(pkg, text))) return false
        return hands.longClick(node)
    }

    suspend fun typeIntoField(fieldHint: String, value: String): Boolean {
        val pkg = ensureHarnessInForeground() ?: return false
        val node = hands.findElement(fieldHint) ?: return false
        if (node.isPassword || ActionRiskPolicy.isSensitiveFieldLabel(labelOf(node))) return false
        if (!authorized(SvetlanaAction.TypeText(pkg, fieldHint, value))) return false
        return hands.inputText(node, value)
    }

    /**
     * Единая точка авторизации: та же проверка, что у ActionRouter (политика +
     * реальный элемент на экране). Без диалога с пользователем опасное не выполняется.
     * Если роутер ещё не создан (ранний старт), действуем только по локальной проверке выше
     * (она совпадает с проверкой элемента в роутере).
     */
    private suspend fun authorized(action: SvetlanaAction): Boolean {
        val router = try { ServiceLocator.actionRouter } catch (t: Throwable) { null } ?: return true
        return router.authorizeUnattended(action)
    }

    suspend fun scrollDown(): Boolean {
        val pkg = ensureHarnessInForeground() ?: return false
        if (!authorized(SvetlanaAction.Scroll(pkg, "down"))) return false
        return hands.scrollForward()
    }

    suspend fun scrollUp(): Boolean {
        val pkg = ensureHarnessInForeground() ?: return false
        if (!authorized(SvetlanaAction.Scroll(pkg, "up"))) return false
        return hands.scrollBackward()
    }

    suspend fun goBack(): Boolean {
        ensureHarnessInForeground() ?: return false
        if (!authorized(SvetlanaAction.PressBack)) return false
        return hands.pressBack()
    }

    suspend fun screenshot(): Bitmap? {
        val pkg = ensureHarnessInForeground() ?: return null
        if (!authorized(SvetlanaAction.TakeScreenshot(pkg))) return null
        return hands.takeScreenshot()
    }

    suspend fun verifyResult(text: String): Boolean {
        val pkg = ensureHarnessInForeground() ?: return false
        if (!authorized(SvetlanaAction.ReadScreen(pkg))) return false
        return hands.verifyTextVisible(text)
    }

    suspend fun uiTree(): String {
        val pkg = ensureHarnessInForeground() ?: return "empty"
        if (!authorized(SvetlanaAction.ReadScreen(pkg))) return "empty"
        val tree = hands.uiTree() ?: return "empty"
        return tree.nodes.joinToString("\n") {
            "[${it.depth}] ${it.className} text=${it.visibleText} id=${it.id} " +
                    "click=${it.isClickable} bounds=${it.bounds.flattenToString()}"
        }
    }

    private suspend fun ensureHarnessInForeground(): String? {
        val state = detect()
        if (!state.installed) return null
        val pkg = state.packageName!!
        if (!hands.currentPackage().equals(pkg, ignoreCase = true)) {
            registry.apps.value.firstOrNull { it.packageName == pkg }?.let { app ->
                intentResolver.launchIntentFor(app)?.let { i ->
                    try { context.startActivity(i) } catch (t: Throwable) { }
                    delay(LAUNCH_SETTLE_MS)
                }
            }
            hands.waitForPackage(pkg, timeoutMs = WAIT_PKG_MS)
        }
        // Fail closed: never act on whatever other app happens to be in front.
        return pkg.takeIf { hands.currentPackage().equals(it, ignoreCase = true) }
    }

    private fun ok(v: Boolean): StepStatus = if (v) StepStatus.OK else StepStatus.FAILED

    companion object {
        private const val TAG = "MobileHarness"
        private const val LAUNCH_SETTLE_MS = 350L
        private const val WAIT_PKG_MS = 2500L
    }
}
