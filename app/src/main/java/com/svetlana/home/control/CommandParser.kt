package com.svetlana.home.control

import com.svetlana.home.apps.AppAliases

/**
 * CommandParser — разбор естественных русских команд в доменные действия.
 *
 * Не зависит от Android-контекста, поэтому полностью покрывается unit-тестами.
 * Понимает русские псевдонимы приложений (AppAliases).
 */
object CommandParser {

    private val wakeWords = listOf("света", "светочка", "светлана")

    private val openMarkers = listOf(
        "переключись на", "открой приложение", "открой", "запусти приложение", "запусти",
        "открой приложения", "вернись в", "перейди в"
    )

    /**
     * Главная точка разбора. Сначала пытается разобрать составную команду
     * (действия, соединённые союзом «и»), затем — одиночную.
     */
    fun parse(rawCommand: String): SvetlanaAction? {
        val c = rawCommand.trim()
        if (c.isEmpty()) return null
        val low = c.lowercase().replace("ё", "е")
        val body = stripWakeWord(low).trim()
        if (body.isEmpty()) return null
        // Оригинальный текст нужен для сохранения регистра элементов и фраз
        // (светлана должна нажать именно «Отправить», а не «отправить»).
        val original = stripWakeWord(c).trim()

        // Составные команды: «открой Whatsapp и напиши контакту Серый привет как дела»
        parseCompound(body, original)?.let { return it }

        return parseSingle(body, original)
    }

    /**
     * Разбор одной команды (без союзов).
     */
    private fun parseSingle(body: String, original: String): SvetlanaAction? {

        // Навигация
        when {
            body.matches(Regex(".*(вернись назад|назад|go back).*")) -> return SvetlanaAction.PressBack
            body.matches(Regex(".*(домой|на главный экран|go home).*")) -> return SvetlanaAction.PressHome
            body.matches(Regex(".*(недавние|список приложений|recents|показывай недавние).*")) ->
                return SvetlanaAction.OpenRecents
            body.startsWith("открой настройки") || body == "настройки" || body == "настройки приложения" ->
                return SvetlanaAction.OpenSettings
        }

        // Скриншот
        if (body.matches(Regex(".*(сделай скриншот|снимок экрана|скриншот).*")))
            return SvetlanaAction.TakeScreenshot("current")

        // Прокрутка
        Regex(".*(пролистай|прокрути|пролистай вниз|scroll)\\s*(экран|страницу)?\\s*(вниз|вверх|up|down).*")
            .matchEntire(body)?.let {
                return SvetlanaAction.Scroll("current", it.groupValues[3])
            }

        // Свайп
        Regex(".*(свайпни|смахни|swipe)\\s+(вниз|вверх|влево|вправо|up|down|left|right).*")
            .matchEntire(body)?.let {
                return SvetlanaAction.Swipe("current", it.groupValues[2])
            }

        // Открытие приложений
        when {
            body.matches(Regex(".*(открой|запусти)\\s+(приложение\\s+)?для тестирования.*")) ->
                return SvetlanaAction.OpenApp(HARNESS_NAME)
            body.startsWith("переключись на") -> {
                val target = extractTarget(body, "переключись на")
                if (target.isNotBlank()) return SvetlanaAction.OpenApp(target)
            }
            body.startsWith("открой приложение") || body.startsWith("открой") ||
                    body.startsWith("запусти приложение") || body.startsWith("запусти") -> {
                val target = extractTarget(body)
                if (target.isNotBlank()) return SvetlanaAction.OpenApp(target)
            }
        }

        // Ввод текста
        Regex("^(введи|ввести|введи это значение|type|вставь)\\s+(.*)").matchEntire(body)?.let {
            val value = it.groupValues[2].trim().trim('"', '«', '»')
            if (value.isNotBlank()) return SvetlanaAction.TypeText("current", "input", value)
        }

        // Нажатие
        Regex("^(нажми|кликни|tap|press|нажать)\\s+(на\\s+)?(кнопку\\s+|элемент\\s+)?(.*)")
            .matchEntire(body)?.let {
                val element = it.groups[4]!!.value.trim()
                if (element.isNotBlank()) {
                    val origElement = originalElement(original, body, element)
                    return SvetlanaAction.Click("current", origElement)
                }
            }

        // Чтение экрана
        if (body.matches(Regex(".*(прочитай экран|что на экране|что сейчас на экране|read screen|прочитай что на экране).*")))
            return SvetlanaAction.ReadScreen("current")

        // Поиск элемента
        Regex("^(найди|поищи|find)\\s+(.*)").matchEntire(body)?.let {
            val el = it.groups[2]!!.value.trim()
            if (el.isNotBlank()) {
                val origEl = originalElement(original, body, el)
                return SvetlanaAction.FindElement("current", origEl)
            }
        }

        // Звонок / сообщения
        Regex("^(позвони|звонок|call|набери)\\s+(.*)").matchEntire(body)?.let {
            return SvetlanaAction.MakeCall(it.groupValues[2].trim())
        }
        Regex("^(отправь сообщение|напиши сообщение|смс|sms|send message)\\s+(.*)")
            .matchEntire(body)?.let {
                return SvetlanaAction.SendMessage(it.groupValues[2].trim(), "")
            }

        if (body.matches(Regex(".*(поделись|share|отправь кому).*"))) return SvetlanaAction.Share("")

        return null
    }

    // ------------------------------------------------------------------
    // Составные команды (длинные Hands-цепочки)
    // ------------------------------------------------------------------

    private val conjunctions = listOf(" и ", " затем ", " потом ", " а потом ")

    /**
     * Разбор составной команды.
     *
     * Поддержанные формы:
     *  - «открой Whatsapp и напиши контакту Серый привет как дела»
     *    → ComposeMessage(app, contact, text)
     *  - «напиши контакту Серый привет как дела»
     *    → ComposeMessage(мессенджер по умолчанию, contact, text)
     *  - «открой Telegram и нажми поиск»
     *    → Compound([OpenApp, Click])
     *  - «открой Telegram и введи привет»
     *    → Compound([OpenApp, TypeText])
     */
    private fun parseCompound(body: String, original: String): SvetlanaAction? {
        // 1. Спецпаттерн: открыть приложение и написать контакту.
        parseComposeWithApp(body, original)?.let { return it }

        // 2. Написать контакту без явного приложения.
        parseComposeNoApp(body, original)?.let { return it }

        // 3. Общий случай: две команды, соединённые союзом.
        for (conj in conjunctions) {
            val idx = body.indexOf(conj)
            if (idx <= 0) continue
            val left = body.substring(0, idx).trim()
            val right = body.substring(idx + conj.length - 1).trim()
            if (left.isEmpty() || right.isEmpty()) continue
            val leftAction = parseSingle(left, originalSubstring(original, body, left)) ?: continue
            val rightAction = parseSingle(right, originalSubstring(original, body, right)) ?: continue

            // Правая часть должна выполняться в контексте приложения из левой:
            // «открой Telegram и нажми поиск» — поиск ищется в Telegram.
            val retargeted = retarget(rightAction, leftAction)
            return SvetlanaAction.Compound(listOf(leftAction, retargeted))
        }
        return null
    }

    /**
     * «открой/запусти <app> и напиши [контакту] <contact> <text>»
     */
    private fun parseComposeWithApp(body: String, original: String): SvetlanaAction? {
        val regex = Regex(
            "^(открой|запусти|перейди в|открой приложение|запусти приложение)\\s+(.+?)\\s+и\\s+" +
                "напиши\\s+(?:контакту\\s+)?(.+)$"
        )
        val m = regex.matchEntire(body) ?: return null
        val app = m.groupValues[2].trim().trim(' ', '.', ',', '!', '?')
        val restLow = m.groupValues[3].trim()
        val restOrig = originalSubstring(original, body, restLow)
        val (contact, text) = splitContactAndText(restLow, restOrig)
        if (contact.isBlank() || text.isBlank()) return null
        return SvetlanaAction.ComposeMessage(app, contact, text)
    }

    /**
     * «напиши [контакту] <contact> <text>» — приложение не названо,
     * значит используем мессенджер по умолчанию (appTarget пустой).
     */
    private fun parseComposeNoApp(body: String, original: String): SvetlanaAction? {
        val regex = Regex("^напиши\\s+(?:контакту\\s+)?(.+)$")
        val m = regex.matchEntire(body) ?: return null
        // «напиши сообщение» / «напиши смс» — это старый шаблон SMS, не чат.
        if (m.groupValues[1].startsWith("сообщение") || m.groupValues[1].startsWith("смс")) return null
        val restLow = m.groupValues[1].trim()
        val restOrig = originalSubstring(original, body, restLow)
        val (contact, text) = splitContactAndText(restLow, restOrig)
        if (contact.isBlank() || text.isBlank()) return null
        return SvetlanaAction.ComposeMessage("", contact, text)
    }

    /**
     * Разделение «контакт | текст сообщения».
     *
     * Контакт — это 1–3 слова в начале. Если контакт не в кавычках, то
     * границу определяем по регистру оригинала: имя контакта может быть
     * «Серый» или «Серый Друг» (оба слова с заглавной), а сообщение
     * («привет как дела») продолжается со строчной.
     */
    private fun splitContactAndText(restLow: String, restOrig: String): Pair<String, String> {
        // Кавычки: «напиши контакту "Серый Друг" привет»
        extractQuoted(restOrig)?.let { quoted ->
            val text = restOrig.substringAfter('"').substringAfter('"').trim()
                .ifBlank { restOrig.substringAfter('«').substringAfter('»').trim() }
            val cleanText = text.trim(' ', '.', ',', '!', '?')
            if (cleanText.isNotBlank()) return quoted to cleanText
        }
        val lowTokens = restLow.split(Regex("\\s+")).filter { it.isNotBlank() }
        val origTokens = restOrig.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (lowTokens.isEmpty()) return "" to ""
        var n = 1
        while (n < lowTokens.size - 1 && n < 3) {
            val orig = origTokens.getOrNull(n) ?: break
            // Имя контакта продолжается, только если слово с заглавной буквы
            // и не является типичным началом сообщения.
            if (orig.first().isUpperCase() && orig.length > 1) n++ else break
        }
        val contact = lowTokens.take(n).joinToString(" ")
        val text = lowTokens.drop(n).joinToString(" ")
        return contact to text
    }

    /**
     * Перенаправить действие на приложение из левой части составной команды.
     * «открой Telegram и нажми поиск» → Click должен искать «поиск» в Telegram,
     * а не в «current».
     */
    private fun retarget(action: SvetlanaAction, context: SvetlanaAction): SvetlanaAction {
        val app = (context as? SvetlanaAction.OpenApp)?.target ?: return action
        return when (action) {
            is SvetlanaAction.Click -> action.copy(target = app)
            is SvetlanaAction.LongClick -> action.copy(target = app)
            is SvetlanaAction.TypeText -> action.copy(target = app)
            is SvetlanaAction.ClearText -> action.copy(target = app)
            is SvetlanaAction.ReadScreen -> action.copy(target = app)
            is SvetlanaAction.FindElement -> action.copy(target = app)
            is SvetlanaAction.TakeScreenshot -> action.copy(target = app)
            is SvetlanaAction.Scroll -> action.copy(target = app)
            is SvetlanaAction.Swipe -> action.copy(target = app)
            else -> action
        }
    }

    /**
     * Вырезать из [original] подстроку, соответствующую [lowPart] из [body].
     */
    private fun originalSubstring(original: String, body: String, lowPart: String): String {
        val idx = body.indexOf(lowPart)
        if (idx < 0 || idx + lowPart.length > original.length) return original
        return original.substring(idx, idx + lowPart.length)
    }


    fun stripWakeWord(command: String): String {
        val low = command.lowercase().replace("ё", "е")
        for (w in wakeWords) {
            if (low == w) return ""
            if (low.startsWith("$w ") || low.startsWith("$w,") || low.startsWith("$w:")) {
                return command.substring(w.length).trimStart(' ', ',', ':')
            }
        }
        return command
    }

    /**
     * Возвращает [needle] в оригинальном регистре, ища его в [original].
     * body и original совпадают посимвольно, отличаются только регистром.
     */
    private fun originalElement(original: String, body: String, needle: String): String {
        val idx = body.indexOf(needle)
        if (idx < 0 || idx + needle.length > original.length) return needle
        return original.substring(idx, idx + needle.length)
    }

    private fun extractTarget(body: String, explicitMarker: String? = null): String {
        val marker = explicitMarker ?: openMarkers.firstOrNull { body.startsWith(it) }
        if (marker == null) return ""
        var target = body.removePrefix(marker).trim()
        target = target.trim(' ', '.', ',', '!', '?')
        // Убираем связки в начале
        val fillers = listOf("приложение ", "программу ", "это ")
        for (f in fillers) if (target.startsWith(f)) target = target.removePrefix(f)
        return target.trim()
    }

    private fun extractQuoted(rest: String): String? {
        if (rest.contains('"')) return rest.substringAfter('"').substringBefore('"')
        if (rest.contains('«')) return rest.substringAfter('«').substringBefore('»')
        return null
    }

    /**
     * Известные имена Mobile Harness для разрешения команд.
     */
    const val HARNESS_NAME = "mobile harness"
}
