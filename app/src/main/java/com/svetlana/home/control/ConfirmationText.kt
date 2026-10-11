package com.svetlana.home.control

/**
 * Точный текст подтверждения: пользователь видит, ЧТО именно произойдёт
 * (кому звонок, кому и какое сообщение, какую кнопку нажмут, какое приложение снимут
 * или прочитают), а не общее «подтвердите опасное действие». Prompt injection не может
 * спрятать параметры за безликим вопросом.
 *
 * Текст очищается от управляющих и bidi-символов (иначе можно визуально
 * переставить «Иван» и номер) и обрезается, чтобы длинная строка не вытеснила
 * главное. Текст, вводимый в поле пароля/PIN/карты, не показывается.
 */
object ConfirmationText {
    private const val MAX = 160
    private val BIDI = Regex("[\\u200E\\u200F\\u202A-\\u202E\\u2066-\\u2069\\u061C]")
    private val SPACES = Regex("\\s+")

    fun quote(raw: String): String {
        val clean = BIDI.replace(raw, "")
            .map { if (it.isISOControl()) ' ' else it }
            .joinToString("")
            .replace(SPACES, " ")
            .trim()
        val cut = if (clean.length > MAX) clean.take(MAX) + "…" else clean
        return "«${cut.ifEmpty { "(пусто)" }}»"
    }

    /**
     * @param targetShown реальная подпись элемента на экране (если найден) или пакет приложения
     *   на экране (для снимка и чтения экрана)
     * @param secretField ввод идёт в поле пароля/PIN/карты — сам текст не показываем
     */
    fun describe(action: SvetlanaAction, targetShown: String? = null, secretField: Boolean = false): String = when (action) {
        is SvetlanaAction.MakeCall -> "открыть набор номера ${quote(action.contact)}"
        is SvetlanaAction.SendMessage -> "подготовить SMS ${quote(action.contact)} с текстом ${quote(action.text)}"
        is SvetlanaAction.Share -> "поделиться текстом ${quote(action.text)}"
        is SvetlanaAction.ComposeMessage ->
            "написать в ${quote(action.appTarget)} контакту ${quote(action.contact)}: ${quote(action.text)}"
        is SvetlanaAction.Click -> "нажать ${quote(targetShown ?: action.element)} в ${quote(action.target)}"
        is SvetlanaAction.LongClick -> "долго нажать ${quote(targetShown ?: action.element)} в ${quote(action.target)}"
        is SvetlanaAction.TypeText -> {
            val text = if (secretField) "скрытый текст (${action.text.length} симв.)" else quote(action.text)
            "ввести $text в поле ${quote(targetShown ?: action.element)} в ${quote(action.target)}"
        }
        is SvetlanaAction.TakeScreenshot -> "сделать снимок экрана приложения ${quote(targetShown ?: action.target)}"
        is SvetlanaAction.ReadScreen -> "прочитать текст на экране приложения ${quote(targetShown ?: action.target)}"
        is SvetlanaAction.FindElement -> "прочитать экран приложения ${quote(targetShown ?: action.target)}"
        is SvetlanaAction.Compound -> action.steps.mapIndexed { i, s -> "${i + 1}) ${describe(s)}" }.joinToString("; ")
        else -> action.name
    }

    fun question(action: SvetlanaAction, targetShown: String? = null, secretField: Boolean = false): String =
        "Подтвердите: ${describe(action, targetShown, secretField)}?"
}
