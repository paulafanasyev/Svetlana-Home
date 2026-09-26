package com.svetlana.home.control

/**
 * ActionRiskPolicy — классификация опасности действий (ТЗ §58, аудит п.27).
 *
 * Чистая функция, не зависящая от Android/Context — поэтому классификация
 * полностью покрывается unit-тестами. Это критично: если риск действия
 * определён неверно, его можно выполнить без подтверждения пользователя.
 *
 * Опасные действия (отправка сообщений, звонки) требуют явного
 * подтверждения. ActionRouter не выполняет их, пока confirmed != true.
 */
object ActionRiskPolicy {

    fun riskOf(action: SvetlanaAction): ActionRisk = when (action) {
        // ТЗ §58: отправка сообщений и звонки — критические действия
        is SvetlanaAction.SendMessage -> ActionRisk.DANGEROUS
        is SvetlanaAction.MakeCall -> ActionRisk.DANGEROUS
        // Публикации / шеринг — умеренный риск
        is SvetlanaAction.Share -> ActionRisk.MODERATE
        // Остальное управление телефоном — безопасно (выполняется в рамках
        // выданных разрешений и не имеет необратимых последствий)
        else -> ActionRisk.SAFE
    }

    /**
     * Требует ли действие явного подтверждения пользователя.
     * ТЗ §58: отправка сообщений, звонки, публикации — требуют.
     */
    fun requiresConfirmation(action: SvetlanaAction): Boolean =
        riskOf(action) in CONFIRM_REQUIRED

    private val CONFIRM_REQUIRED = setOf(
        ActionRisk.DANGEROUS,
        // ТЗ §58: публикации/шеринг требуют подтверждения
        ActionRisk.MODERATE
    )
}
