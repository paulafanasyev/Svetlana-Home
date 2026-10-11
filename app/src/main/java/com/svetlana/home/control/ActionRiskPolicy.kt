package com.svetlana.home.control

/**
 * ActionRiskPolicy — классификация опасности действий (ТЗ §58, аудит п.27).
 *
 * Чистая функция, не зависящая от Android/Context — поэтому классификация
 * полностью покрывается unit-тестами. Это критично: если риск действия
 * определён неверно, его можно выполнить без подтверждения пользователя.
 *
 * Принцип владельца: «голос один — проверенный голос владельца». Пока на телефоне
 * нет проверки голоса, любое действие, которое что-то меняет или раскрывает данные
 * чужих приложений (нажатие, ввод и очистка текста, снимок экрана), требует явного
 * подтверждения на экране. Без подтверждения остаются только навигация
 * (открыть приложение, назад/домой, прокрутка) — их последствия видны и обратимы.
 */
object ActionRiskPolicy {

    fun riskOf(action: SvetlanaAction): ActionRisk = when (action) {
        // ТЗ §58: отправка сообщений и звонки — критические действия
        is SvetlanaAction.SendMessage -> ActionRisk.DANGEROUS
        is SvetlanaAction.MakeCall -> ActionRisk.DANGEROUS
        // Многошаговая отправка сообщения в мессенджере — то же самое
        // необратимое действие, выполняемое через Hands.
        is SvetlanaAction.ComposeMessage -> ActionRisk.DANGEROUS
        // Публикации / шеринг — умеренный риск
        is SvetlanaAction.Share -> ActionRisk.MODERATE
        // Нажатия и ввод в чужих приложениях могут подтвердить платёж, удалить данные,
        // ввести текст в банк; снимок экрана раскрывает переписку. Только с подтверждением.
        is SvetlanaAction.Click,
        is SvetlanaAction.LongClick,
        is SvetlanaAction.TypeText,
        is SvetlanaAction.ClearText,
        is SvetlanaAction.TakeScreenshot -> ActionRisk.MODERATE
        // Цепочка: опасность равна самому опасному шагу
        is SvetlanaAction.Compound -> action.steps.map(::riskOf).maxByOrNull { it.ordinal }
            ?: ActionRisk.SAFE
        // Навигация (открыть приложение, назад, домой, недавние, прокрутка) — без подтверждения
        else -> ActionRisk.SAFE
    }

    /**
     * Требует ли действие явного подтверждения пользователя.
     * ТЗ §58: отправка сообщений, звонки, публикации, а также нажатия/ввод/снимки экрана.
     */
    fun requiresConfirmation(action: SvetlanaAction): Boolean =
        riskOf(action) in CONFIRM_REQUIRED

    private val CONFIRM_REQUIRED = setOf(
        ActionRisk.DANGEROUS,
        ActionRisk.MODERATE
    )
}
