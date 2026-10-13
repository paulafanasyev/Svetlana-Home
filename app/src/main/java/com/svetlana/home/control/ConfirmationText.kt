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
 *
 * [english] = true — тот же вопрос по-английски (английский интерфейс), с теми же параметрами.
 */
object ConfirmationText {
    private const val MAX = 160
    private val BIDI = Regex("[\\u200E\\u200F\\u202A-\\u202E\\u2066-\\u2069\\u061C]")
    private val SPACES = Regex("\\s+")

    fun quote(raw: String, english: Boolean = false): String {
        val clean = BIDI.replace(raw, "")
            .map { if (it.isISOControl()) ' ' else it }
            .joinToString("")
            .replace(SPACES, " ")
            .trim()
        val cut = if (clean.length > MAX) clean.take(MAX) + "…" else clean
        return if (english) "“${cut.ifEmpty { "(empty)" }}”" else "«${cut.ifEmpty { "(пусто)" }}»"
    }

    /**
     * @param targetShown реальная подпись элемента на экране (если найден) или пакет приложения
     *   на экране (для снимка и чтения экрана)
     * @param secretField ввод идёт в поле пароля/PIN/карты — сам текст не показываем
     */
    fun describe(
        action: SvetlanaAction,
        targetShown: String? = null,
        secretField: Boolean = false,
        english: Boolean = false
    ): String = if (english) describeEn(action, targetShown, secretField) else when (action) {
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

    private fun describeEn(action: SvetlanaAction, targetShown: String?, secretField: Boolean): String {
        fun q(s: String) = quote(s, english = true)
        return when (action) {
            is SvetlanaAction.MakeCall -> "open the dialer for ${q(action.contact)}"
            is SvetlanaAction.SendMessage -> "prepare an SMS to ${q(action.contact)} with text ${q(action.text)}"
            is SvetlanaAction.Share -> "share text ${q(action.text)}"
            is SvetlanaAction.ComposeMessage ->
                "write in ${q(action.appTarget)} to contact ${q(action.contact)}: ${q(action.text)}"
            is SvetlanaAction.Click -> "tap ${q(targetShown ?: action.element)} in ${q(action.target)}"
            is SvetlanaAction.LongClick -> "long-press ${q(targetShown ?: action.element)} in ${q(action.target)}"
            is SvetlanaAction.TypeText -> {
                val text = if (secretField) "hidden text (${action.text.length} chars)" else q(action.text)
                "type $text into field ${q(targetShown ?: action.element)} in ${q(action.target)}"
            }
            is SvetlanaAction.TakeScreenshot -> "take a screenshot of app ${q(targetShown ?: action.target)}"
            is SvetlanaAction.ReadScreen -> "read the text on the screen of app ${q(targetShown ?: action.target)}"
            is SvetlanaAction.FindElement -> "read the screen of app ${q(targetShown ?: action.target)}"
            is SvetlanaAction.Compound ->
                action.steps.mapIndexed { i, s -> "${i + 1}) ${describeEn(s, null, false)}" }.joinToString("; ")
            else -> action.name
        }
    }

    fun question(
        action: SvetlanaAction,
        targetShown: String? = null,
        secretField: Boolean = false,
        english: Boolean = false
    ): String =
        if (english) "Confirm: ${describe(action, targetShown, secretField, english = true)}?"
        else "Подтвердите: ${describe(action, targetShown, secretField)}?"
}
