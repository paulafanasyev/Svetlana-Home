package com.svetlana.home.core

/**
 * Допустимые статусные метки проекта. Используются строго те, что описаны в ТЗ:
 * VERIFIED, CODE VERIFIED, CI VERIFIED, DEVICE VERIFIED, NOT PROVEN, BLOCKED.
 * Запрещено использовать "PASS", если доказательство не соответствует уровню утверждения.
 */
object SvetlanaStatus {
    const val VERIFIED = "VERIFIED"
    const val CODE_VERIFIED = "CODE VERIFIED"
    const val CI_VERIFIED = "CI VERIFIED"
    const val DEVICE_VERIFIED = "DEVICE VERIFIED"
    const val NOT_PROVEN = "NOT PROVEN"
    const val BLOCKED = "BLOCKED"
    const val INCOMPATIBLE = "INCOMPATIBLE"
}

/**
 * Уровни доказательной цепочки Hands.
 * Нельзя считать "команда отправлена" равным "действие выполнено".
 */
enum class ProofStage {
    PLAN,
    TARGET_APP_IDENTIFIED,
    PERMISSION_CHECKED,
    ACTION_ATTEMPTED,
    ACTION_PERFORMED,
    RESULT_VERIFIED
}

/**
 * Результат одного шага доказательной цепочки.
 */
data class ProofStep(
    val stage: ProofStage,
    val status: StepStatus,
    val detail: String,
    val timestampMs: Long = System.currentTimeMillis()
)

enum class StepStatus { PENDING, OK, FAILED, SKIPPED }
