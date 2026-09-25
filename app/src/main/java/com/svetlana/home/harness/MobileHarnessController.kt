package com.svetlana.home.harness

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.util.Log
import com.svetlana.home.apps.AppRegistry
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
        ensureHarnessInForeground() ?: return null
        return hands.findElement(text)
    }

    suspend fun tapElement(text: String): Boolean {
        ensureHarnessInForeground() ?: return false
        val node = hands.findElement(text) ?: return false
        return hands.clickNode(node)
    }

    suspend fun longPressElement(text: String): Boolean {
        ensureHarnessInForeground() ?: return false
        val node = hands.findElement(text) ?: return false
        return hands.longClick(node)
    }

    suspend fun typeIntoField(fieldHint: String, value: String): Boolean {
        ensureHarnessInForeground() ?: return false
        val node = hands.findElement(fieldHint) ?: return false
        return hands.inputText(node, value)
    }

    suspend fun scrollDown(): Boolean {
        ensureHarnessInForeground() ?: return false
        return hands.scrollForward()
    }

    suspend fun scrollUp(): Boolean {
        ensureHarnessInForeground() ?: return false
        return hands.scrollBackward()
    }

    suspend fun goBack(): Boolean {
        ensureHarnessInForeground() ?: return false
        return hands.pressBack()
    }

    suspend fun screenshot(): Bitmap? {
        ensureHarnessInForeground() ?: return null
        return hands.takeScreenshot()
    }

    suspend fun verifyResult(text: String): Boolean {
        ensureHarnessInForeground() ?: return false
        return hands.verifyTextVisible(text)
    }

    suspend fun uiTree(): String {
        ensureHarnessInForeground() ?: return "empty"
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
        return pkg
    }

    private fun ok(v: Boolean): StepStatus = if (v) StepStatus.OK else StepStatus.FAILED

    companion object {
        private const val TAG = "MobileHarness"
        private const val LAUNCH_SETTLE_MS = 350L
        private const val WAIT_PKG_MS = 2500L
    }
}
