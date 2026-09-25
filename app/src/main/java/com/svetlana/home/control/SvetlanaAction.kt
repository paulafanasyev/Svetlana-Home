package com.svetlana.home.control

import kotlinx.serialization.Serializable

/**
 * Действия, которые поддерживает App Control Engine (ТЗ §17).
 * Модель не может выполнять произвольные shell-команды.
 */
@Serializable
sealed class SvetlanaAction {
    abstract val name: String

    @Serializable data class OpenApp(val target: String) : SvetlanaAction() { override val name = "OpenApp" }
    @Serializable data class OpenAppScreen(val target: String, val screen: String) : SvetlanaAction() { override val name = "OpenAppScreen" }
    @Serializable data class Click(val target: String, val element: String) : SvetlanaAction() { override val name = "Click" }
    @Serializable data class LongClick(val target: String, val element: String) : SvetlanaAction() { override val name = "LongClick" }
    @Serializable data class Swipe(val target: String, val direction: String) : SvetlanaAction() { override val name = "Swipe" }
    @Serializable data class Scroll(val target: String, val direction: String) : SvetlanaAction() { override val name = "Scroll" }
    @Serializable data class TypeText(val target: String, val element: String, val text: String) : SvetlanaAction() { override val name = "TypeText" }
    @Serializable data class ClearText(val target: String, val element: String) : SvetlanaAction() { override val name = "ClearText" }
    @Serializable data class ReadScreen(val target: String) : SvetlanaAction() { override val name = "ReadScreen" }
    @Serializable data class FindElement(val target: String, val element: String) : SvetlanaAction() { override val name = "FindElement" }
    @Serializable data class TakeScreenshot(val target: String) : SvetlanaAction() { override val name = "TakeScreenshot" }
    @Serializable object PressBack : SvetlanaAction() { override val name = "PressBack" }
    @Serializable object PressHome : SvetlanaAction() { override val name = "PressHome" }
    @Serializable object OpenRecents : SvetlanaAction() { override val name = "OpenRecents" }
    @Serializable object OpenSettings : SvetlanaAction() { override val name = "OpenSettings" }
    @Serializable data class SendMessage(val contact: String, val text: String) : SvetlanaAction() { override val name = "SendMessage" }
    @Serializable data class MakeCall(val contact: String) : SvetlanaAction() { override val name = "MakeCall" }
    @Serializable data class Share(val text: String) : SvetlanaAction() { override val name = "Share" }
    @Serializable data class Translate(val text: String, val direction: String) : SvetlanaAction() { override val name = "Translate" }
}

/**
 * Результат выполнения действия с полной доказательной цепочкой.
 */
data class ActionResult(
    val action: SvetlanaAction,
    val success: Boolean,
    val message: String,
    val proof: List<com.svetlana.home.core.ProofStep> = emptyList(),
    val way: String = "unknown",
    val requiresUserConfirmation: Boolean = false
) {
    fun proofLog(): String = proof.joinToString("\n") { step ->
        "PLAN_${step.stage} ${step.status} ${step.detail}"
    }
}

/**
 * Категория действия. Опасные требуют подтверждения пользователя (ТЗ §58).
 */
enum class ActionRisk { SAFE, MODERATE, DANGEROUS }

/**
 * Приоритет способа управления (ТЗ §16):
 * 1. Android Intent
 * 2. Public API
 * 3. Deep Link
 * 4. PackageManager
 * 5. Accessibility / Hands
 * 6. User confirmation
 */
enum class ControlWay(val priority: Int) {
    INTENT(1), PUBLIC_API(2), DEEP_LINK(3), PACKAGE_MANAGER(4), HANDS(5), USER_CONFIRMATION(6)
}
