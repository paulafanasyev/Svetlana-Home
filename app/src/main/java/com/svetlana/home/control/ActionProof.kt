package com.svetlana.home.control

/**
 * ActionProof — доказательная модель выполнения (аудит §28, docs/action-proof.md).
 *
 * Главный концептуальный дефект старой реализации: `performAction() == true`
 * считался доказательством действия. Здесь это разделено:
 *
 *   platformAccepted  — Android API вернул true (команда принята)
 *   postcondition     — сняли snapshot, сравнили состояние
 *   result            — только VERIFIED разрешает ACTION_PERFORMED = true
 *
 * `platformAccepted` **никогда** не означает `performed`.
 */
data class ActionProof(
    val planId: String,
    val target: String,
    val precondition: VerificationState,
    val attempted: Boolean,
    val platformAccepted: Boolean,
    val postcondition: VerificationState,
    val result: VerificationState
) {
    /**
     * Действие считается выполненным только при VERIFIED postcondition.
     */
    val performed: Boolean
        get() = attempted && platformAccepted &&
                postcondition == VerificationState.VERIFIED &&
                result == VerificationState.VERIFIED

    companion object {
        fun notAttempted(planId: String, target: String, reason: String) = ActionProof(
            planId = planId,
            target = target,
            precondition = VerificationState.NOT_CHECKED,
            attempted = false,
            platformAccepted = false,
            postcondition = VerificationState.NOT_CHECKED,
            result = VerificationState.FAILED
        )
    }
}

/**
 * Состояние проверки условия/постусловия.
 */
enum class VerificationState { NOT_CHECKED, CHECKING, VERIFIED, FAILED, UNSUPPORTED }
