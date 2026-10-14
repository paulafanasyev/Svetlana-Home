package com.svetlana.home.control

/**
 * ActionRiskPolicy — классификация опасности действий (ТЗ §58, аудит п.27).
 *
 * Чистая функция, не зависящая от Android/Context — поэтому классификация
 * полностью покрывается unit-тестами.
 *
 * Клики и ввод текста оцениваются по цели: «нажми Удалить / Оплатить /
 * Отправить / Установить» и ввод в поле пароля/карты — DANGEROUS, хотя это
 * «просто клик». Иначе подсказка с чужого экрана (prompt injection) могла бы
 * провести необратимое действие без подтверждения.
 *
 * Очистка поля (ClearText) стирает чужой текст без возможности вернуть — всегда MODERATE,
 * то есть только после подтверждения и только для подтверждённого поля.
 *
 * Ответ из уведомления (ReplyIncoming) — это отправка сообщения: DANGEROUS. Чтение входящих —
 * SAFE в открытом приложении; фоновый голос всё равно только предлагает действие (ActionRouter).
 *
 * Одна политика для обоих языков: английские подписи кнопок проверяются наравне с русскими.
 */
object ActionRiskPolicy {

    fun riskOf(action: SvetlanaAction): ActionRisk = when (action) {
        is SvetlanaAction.SendMessage -> ActionRisk.DANGEROUS
        is SvetlanaAction.MakeCall -> ActionRisk.DANGEROUS
        is SvetlanaAction.ComposeMessage -> ActionRisk.DANGEROUS
        is SvetlanaAction.ReplyIncoming -> ActionRisk.DANGEROUS
        is SvetlanaAction.Share -> ActionRisk.MODERATE
        is SvetlanaAction.ClearText -> ActionRisk.MODERATE
        is SvetlanaAction.Click ->
            if (isDangerousLabel(action.element)) ActionRisk.DANGEROUS else ActionRisk.SAFE
        is SvetlanaAction.LongClick ->
            if (isDangerousLabel(action.element)) ActionRisk.DANGEROUS else ActionRisk.SAFE
        is SvetlanaAction.TypeText ->
            if (isSensitiveFieldLabel(action.element) || looksLikeSecret(action.text)) ActionRisk.DANGEROUS
            else ActionRisk.SAFE
        is SvetlanaAction.Compound -> action.steps.map(::riskOf).maxByOrNull { it.ordinal }
            ?: ActionRisk.SAFE
        else -> ActionRisk.SAFE
    }

    fun requiresConfirmation(action: SvetlanaAction): Boolean =
        riskOf(action) in CONFIRM_REQUIRED

    /** Подпись кнопки/элемента, нажатие на который необратимо. */
    fun isDangerousLabel(label: String): Boolean {
        val low = label.lowercase()
        if (low.isBlank()) return false
        return DANGEROUS_STEMS.any { low.contains(it) } || DANGEROUS_WORD_RES.any { it.containsMatchIn(low) }
    }

    /** Поле пароля, PIN, одноразового кода или банковской карты. */
    fun isSensitiveFieldLabel(label: String): Boolean {
        val low = label.lowercase()
        return SENSITIVE_FIELD_STEMS.any { low.contains(it) } || SENSITIVE_FIELD_WORD_RES.any { it.containsMatchIn(low) }
    }

    /** Номер карты (13–19 цифр) — не вводим без подтверждения. */
    fun looksLikeSecret(text: String): Boolean {
        val digits = text.filter { it.isDigit() }
        val onlyDigitsAndSeparators = text.all { it.isDigit() || it == ' ' || it == '-' }
        return onlyDigitsAndSeparators && digits.length in 13..19
    }

    private fun wordRe(word: String) = Regex("(^|[^\\p{L}\\p{N}])${Regex.escape(word)}($|[^\\p{L}\\p{N}])")

    private val DANGEROUS_STEMS = listOf(
        "удал", "оплат", "заплат", "купить", "покуп", "перевест", "отправ", "установ",
        "подтвер", "сброс", "стере", "очистить вс", "выйти из аккаунт", "оформить заказ", "заказать",
        "подписат", "списат", "разреш", "предостав", "позвон", "вызов",
        "delete", "remove", "uninstall", "install", "purchase", "checkout", "transfer", "confirm",
        "submit", "factory reset", "erase", "wipe", "subscribe", "allow", "grant", "sign out", "log out",
        "place order", "approve", "authoriz", "withdraw", "clear all", "clear data",
    )

    private val DANGEROUS_WORDS = listOf(
        "pay", "buy", "send", "order", "call", "reset", "format", "accept", "ok", "dial", "sell",
    )

    private val SENSITIVE_FIELD_STEMS = listOf(
        "парол", "пин-код", "пин код", "код из смс", "код подтверждения", "одноразов", "номер карты",
        "password", "passcode", "pincode", "pin code", "otp", "cvv", "cvc", "card number", "security code",
        "verification code", "one-time code",
    )
    private val SENSITIVE_FIELD_WORDS = listOf("pin", "пин", "2fa", "totp")

    private val DANGEROUS_WORD_RES = DANGEROUS_WORDS.map(::wordRe)
    private val SENSITIVE_FIELD_WORD_RES = SENSITIVE_FIELD_WORDS.map(::wordRe)

    private val CONFIRM_REQUIRED = setOf(ActionRisk.DANGEROUS, ActionRisk.MODERATE)
}
