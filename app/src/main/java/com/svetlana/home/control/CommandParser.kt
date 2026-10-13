package com.svetlana.home.control

import com.svetlana.home.apps.AppAliases

/**
 * CommandParser — разбор естественных команд (русских и английских) в доменные действия.
 *
 * Не зависит от Android-контекста, поэтому полностью покрывается unit-тестами.
 * Понимает русские псевдонимы приложений (AppAliases).
 *
 * Язык определяется по тексту: есть кириллица — русский разбор (без изменений),
 * только латиница — сначала английский разбор, затем старые латинские формы.
 *
 * Длинные цепочки: «открой Whatsapp, найди контакт Серый и напиши ему привет»,
 * «открой Telegram, нажми поиск и введи Серый» — шаги через запятую и союзы.
 *
 * Безопасность: английские команды дают те же типы [SvetlanaAction], поэтому звонок,
 * сообщение, «нажми Pay/Delete» и т.п. так же проходят через [ActionRiskPolicy] и
 * требуют подтверждения.
 */
object CommandParser {

    private val wakeWords = listOf("света", "светочка", "светлана", "sveta", "svetochka", "svetlana")

    private val openMarkers = listOf(
        "переключись на", "открой приложение", "открой", "запусти приложение", "запусти",
        "открой приложения", "вернись в", "перейди в"
    )

    /**
     * Главная точка разбора. Сначала пытается разобрать составную команду
     * (действия, соединённые союзом «и» / «and» / запятой), затем — одиночную.
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

        if (body.none { it in '\u0400'..'\u04FF' }) {
            // Английская (латинская) команда.
            parseEnglishCompound(body, original)?.let { return it }
            parseEnglishSingle(body, original)?.let { return it }
            return parseSingle(body, original)
        }

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
    // Английские команды
    // ------------------------------------------------------------------

    private val EN_POLITE = Regex("^((please|can you|could you|would you|kindly)\\s+)+")
    private val EN_PLEASE_END = Regex("\\s+please$")
    private const val EN_COMPOSE_VERBS = "(?:message|text|write to|tell)"

    /** «tell me a joke», «text me later» — вопрос к ИИ, а не сообщение контакту. */
    private val EN_NOT_CONTACTS = setOf(
        "me", "us", "you", "him", "her", "them", "it", "a", "an", "the", "about",
        "what", "how", "why", "when", "where", "who", "something", "anything", "some", "more",
    )

    /**
     * «open WhatsApp, find the contact John and message him hello» →
     * ComposeMessage(whatsapp, john, hello). Приложение необязательно.
     */
    private val EN_FIND_COMPOSE = Regex(
        "(?:(?:open|launch|start|go to)\\s+(.+?)\\s*,?\\s+(?:and\\s+)?(?:then\\s+)?)?" +
            "(?:find|open|select|search for|look for)\\s+(?:the\\s+|my\\s+)?(?:contact|chat|conversation)(?:\\s+with)?\\s+" +
            "(.+?)\\s*,?\\s+(?:and\\s+)?(?:then\\s+)?" +
            "(?:send\\s+(?:him|her|them)\\s+(?:a\\s+)?(?:message|text)|send\\s+(?:a\\s+)?(?:message|text)(?:\\s+to\\s+(?:him|her|them))?|" +
            "message|text|write to|write|tell|send)" +
            "(?:\\s+(?:him|her|them))?(?:\\s+(?:saying|that))?\\s+(.+)"
    )

    private val enConjunctions = listOf(
        ", and then ", ", then ", ", and ", " and then ", " then ", " and ", ", "
    )

    /** Убрать вежливые слова и знаки препинания по краям (результат — подстрока body). */
    private fun cleanEn(body: String): String {
        var s = body.trim().trimEnd('.', '!', '?', ',').trim()
        s = EN_POLITE.replace(s, "")
        s = EN_PLEASE_END.replace(s, "")
        return s.trim()
    }

    private fun enTarget(raw: String): String {
        var t = raw.trim().trim(' ', '.', ',', '!', '?')
        for (p in listOf("the ", "app ", "application ")) if (t.startsWith(p)) t = t.removePrefix(p)
        for (s in listOf(" app", " application")) if (t.endsWith(s)) t = t.removeSuffix(s)
        return t.trim()
    }

    private fun dropLead(text: String): String {
        var t = text.trim()
        for (p in listOf("saying ", "that says ", "that ", ":")) if (t.startsWith(p)) t = t.removePrefix(p).trim()
        return t.trim(' ', '.', ',', '!', '?')
    }

    private fun parseEnglishSingle(body: String, original: String): SvetlanaAction? {
        val s = cleanEn(body)
        if (s.isEmpty()) return null

        // Навигация
        when {
            s.matches(Regex("(go |navigate )?back")) -> return SvetlanaAction.PressBack
            s.matches(Regex("go home|(go )?(to )?(the )?home( screen)?")) -> return SvetlanaAction.PressHome
            s.matches(Regex("(show |open )?(the |my )?(recent apps|recents|recent)")) -> return SvetlanaAction.OpenRecents
            s.matches(Regex("(open |go to )?(the )?settings")) -> return SvetlanaAction.OpenSettings
        }

        if (s.matches(Regex(".*\\b(screenshot|screen shot|capture (the )?screen)\\b.*")))
            return SvetlanaAction.TakeScreenshot("current")

        Regex("scroll\\s+(?:the\\s+)?(?:screen\\s+|page\\s+)?(up|down)").matchEntire(s)?.let {
            return SvetlanaAction.Scroll("current", it.groupValues[1])
        }
        Regex("swipe\\s+(up|down|left|right)").matchEntire(s)?.let {
            return SvetlanaAction.Swipe("current", it.groupValues[1])
        }

        if (s.matches(Regex(".*\\b(read (the |my )?screen|what'?s on (the |my )?screen|what is on (the |my )?screen)\\b.*")))
            return SvetlanaAction.ReadScreen("current")

        // Открытие приложений
        if (s.matches(Regex("(open|launch|start|run)\\s+(the\\s+)?(test(ing)? app|app for testing)")))
            return SvetlanaAction.OpenApp(HARNESS_NAME)
        Regex("(?:switch to|go to)\\s+(.+)").matchEntire(s)?.let {
            val t = enTarget(it.groupValues[1])
            if (t.isNotBlank()) return SvetlanaAction.OpenApp(t)
        }
        Regex("(?:open|launch|start|run)\\s+(.+)").matchEntire(s)?.let {
            val t = enTarget(it.groupValues[1])
            if (t.isNotBlank()) return SvetlanaAction.OpenApp(t)
        }

        // Ввод текста
        Regex("(?:type|enter|input)\\s+(.+)").matchEntire(s)?.let {
            val value = it.groupValues[1].trim().trim('"', '«', '»', '“', '”')
            if (value.isNotBlank()) return SvetlanaAction.TypeText("current", "input", value)
        }

        // Нажатие
        Regex("(?:tap|press|click|hit|select)\\s+(?:on\\s+)?(?:the\\s+)?(?:button\\s+)?(.+?)(?:\\s+button)?").matchEntire(s)?.let {
            val el = it.groupValues[1].trim()
            if (el.isNotBlank()) return SvetlanaAction.Click("current", originalElement(original, body, el))
        }

        // Поиск элемента
        Regex("(?:find|search for|look for|locate)\\s+(?:the\\s+)?(.+)").matchEntire(s)?.let {
            val el = it.groupValues[1].trim()
            if (el.isNotBlank()) return SvetlanaAction.FindElement("current", originalElement(original, body, el))
        }

        // Звонок
        Regex("(?:call|dial|phone|ring)\\s+(.+)").matchEntire(s)?.let {
            val contact = it.groupValues[1].trim()
            if (contact.isNotBlank()) return SvetlanaAction.MakeCall(contact)
        }

        // SMS: «send a text to Mom [saying hi]»
        Regex("send\\s+(?:an?\\s+)?(?:sms|text message|text|message)\\s+(?:to\\s+)?(.+)").matchEntire(s)?.let {
            val rest = it.groupValues[1].trim()
            val idx = rest.indexOf(" saying ")
            return if (idx > 0) SvetlanaAction.SendMessage(rest.substring(0, idx).trim(), dropLead(rest.substring(idx)))
            else SvetlanaAction.SendMessage(rest, "")
        }

        // Сообщение в мессенджер по умолчанию: «message John hello there»
        parseEnglishCompose(s, body, original, app = "")?.let { return it }

        if (s.matches(Regex("share\\b.*"))) return SvetlanaAction.Share("")

        return null
    }

    private fun parseEnglishCompose(s: String, body: String, original: String, app: String): SvetlanaAction? {
        val m = Regex("$EN_COMPOSE_VERBS\\s+(?:to\\s+)?(?:contact\\s+)?(.+)").matchEntire(s) ?: return null
        val restLow = m.groupValues[1].trim()
        val restOrig = originalSubstring(original, body, restLow)
        val (contact, text) = splitContactAndText(restLow, restOrig)
        if (contact.substringBefore(' ') in EN_NOT_CONTACTS) return null
        val cleanText = dropLead(text)
        if (contact.isBlank() || cleanText.isBlank()) return null
        return SvetlanaAction.ComposeMessage(app, contact, cleanText)
    }

    private fun parseEnglishFindAndCompose(s: String, sOrig: String): SvetlanaAction? {
        val m = EN_FIND_COMPOSE.matchEntire(s) ?: return null
        val app = m.groups[1]?.value?.let { enTarget(it) } ?: ""
        val contact = cleanContact(m.groupValues[2])
        val textRange = m.groups[3]!!.range
        val text = dropLead(slice(s, sOrig, textRange.first, textRange.last + 1).second)
            .trim('"', '«', '»', '“', '”')
        if (contact.isBlank() || text.isBlank()) return null
        if (contact.substringBefore(' ') in EN_NOT_CONTACTS) return null
        return SvetlanaAction.ComposeMessage(app, contact, text)
    }

    private fun parseEnglishCompound(body: String, original: String): SvetlanaAction? {
        val s = cleanEn(body)
        if (s.isEmpty()) return null
        val sOrig = originalSubstring(original, body, s)
        // «open WhatsApp, find contact John and message him hello»
        parseEnglishFindAndCompose(s, sOrig)?.let { return it }
        // «open WhatsApp and message John hello there»
        Regex("(?:open|launch|start|go to)\\s+(.+?)\\s*,?\\s+(?:and\\s+)?(?:then\\s+)?($EN_COMPOSE_VERBS\\s+.+)").matchEntire(s)?.let {
            val app = enTarget(it.groupValues[1])
            val composePart = it.groupValues[2]
            if (app.isNotBlank()) {
                parseEnglishCompose(composePart, body, original, app)?.let { action -> return action }
            }
        }
        return splitChain(s, sOrig, enConjunctions, ::parseEnglishSingle, ::parseEnglishCompound)
    }

    // ------------------------------------------------------------------
    // Составные команды (длинные Hands-цепочки)
    // ------------------------------------------------------------------

    private val conjunctions = listOf(
        ", а потом ", ", затем ", ", потом ", ", и ",
        " а потом ", " затем ", " потом ", " и ", ", "
    )

    /** «ему привет» — местоимение вместо имени, это не контакт. */
    private val RU_PRONOUNS = setOf("ему", "ей", "им", "сообщение", "туда", "смс")

    /**
     * «[открой Whatsapp,] найди контакт Серый и напиши ему привет»
     */
    private val RU_FIND_COMPOSE = Regex(
        "^(?:(?:открой|запусти|перейди в)\\s+(?:приложение\\s+)?(.+?)\\s*,?\\s+(?:и\\s+|а\\s+)?(?:потом\\s+|затем\\s+)?)?" +
            "(?:(?:найди|выбери)\\s+(?:(?:контакт|чат|диалог|переписку)(?:\\s+с)?\\s+)?|" +
            "открой\\s+(?:контакт|чат|диалог|переписку)(?:\\s+с)?\\s+)" +
            "(.+?)\\s*,?\\s+(?:и\\s+|а\\s+)?(?:потом\\s+|затем\\s+)?" +
            "(?:напиши|отправь)(?:\\s+(?:ему|ей|им|сообщение|туда))*\\s+(.+)$"
    )

    /**
     * Разбор составной команды.
     *
     * Поддержанные формы:
     *  - «открой Whatsapp, найди контакт Серый и напиши ему привет»
     *    → ComposeMessage(app, contact, text)
     *  - «открой Whatsapp и напиши контакту Серый привет как дела»
     *    → ComposeMessage(app, contact, text)
     *  - «напиши контакту Серый привет как дела»
     *    → ComposeMessage(мессенджер по умолчанию, contact, text)
     *  - «открой Telegram и нажми поиск» → Compound([OpenApp, Click])
     *  - «открой Telegram, нажми поиск и введи Серый» → Compound из 3 шагов
     */
    private fun parseCompound(body: String, original: String): SvetlanaAction? {
        // 0. «найди контакт X и напиши ему Y» (с приложением или без).
        parseFindAndCompose(body, original)?.let { return it }

        // 1. Спецпаттерн: открыть приложение и написать контакту.
        parseComposeWithApp(body, original)?.let { return it }

        // 2. Написать контакту без явного приложения.
        parseComposeNoApp(body, original)?.let { return it }

        // 3. Общий случай: цепочка команд через союзы и запятые.
        return splitChain(body, original, conjunctions, ::parseSingle, ::parseCompound)
    }

    private fun parseFindAndCompose(body: String, original: String): SvetlanaAction? {
        val m = RU_FIND_COMPOSE.matchEntire(body) ?: return null
        val app = m.groups[1]?.value?.trim()?.trim(' ', '.', ',', '!', '?') ?: ""
        val contact = cleanContact(m.groupValues[2])
        val textRange = m.groups[3]!!.range
        val text = slice(body, original, textRange.first, textRange.last + 1).second
            .trim(' ', '.', ',', '!', '?').trim('"', '«', '»')
        if (contact.isBlank() || text.isBlank() || contact in RU_PRONOUNS) return null
        return SvetlanaAction.ComposeMessage(app, contact, text)
    }

    /**
     * Разбить цепочку по самому левому союзу/запятой, при котором обе части
     * разбираются. Правая часть сама может быть цепочкой — шаги «расплющиваются»
     * и выполняются в приложении из первого шага.
     */
    private fun splitChain(
        body: String,
        original: String,
        conjs: List<String>,
        single: (String, String) -> SvetlanaAction?,
        compound: (String, String) -> SvetlanaAction?,
    ): SvetlanaAction? {
        val points = mutableListOf<Pair<Int, String>>()
        for (conj in conjs) {
            var i = body.indexOf(conj)
            while (i >= 0) {
                points += i to conj
                i = body.indexOf(conj, i + 1)
            }
        }
        points.sortWith(compareBy<Pair<Int, String>>({ it.first }, { -it.second.length }))
        for ((idx, conj) in points) {
            if (idx <= 0) continue
            val (left, leftOrig) = slice(body, original, 0, idx)
            val (right, rightOrig) = slice(body, original, idx + conj.length, body.length)
            if (left.isEmpty() || right.isEmpty()) continue
            val leftAction = single(left, leftOrig) ?: continue
            if (leftAction is SvetlanaAction.Compound) continue
            val rightAction = compound(right, rightOrig) ?: single(right, rightOrig) ?: continue
            return chain(leftAction, rightAction)
        }
        return null
    }

    /**
     * Склеить шаг и остаток цепочки. «открой X» + «напиши Y» без приложения
     * превращается в одно ComposeMessage(X, …), остальные шаги перенаправляются
     * на приложение из первого шага.
     */
    private fun chain(left: SvetlanaAction, right: SvetlanaAction): SvetlanaAction {
        if (left is SvetlanaAction.OpenApp && right is SvetlanaAction.ComposeMessage && right.appTarget.isBlank()) {
            return right.copy(appTarget = left.target)
        }
        val rest = if (right is SvetlanaAction.Compound) right.steps else listOf(right)
        return SvetlanaAction.Compound(listOf(left) + rest.map { retarget(it, left) })
    }

    /**
     * Срез body[start, end) и соответствующий кусок original без пробелов и
     * запятых по краям.
     */
    private fun slice(body: String, original: String, start: Int, end: Int): Pair<String, String> {
        var s = start.coerceIn(0, body.length)
        var e = end.coerceIn(s, body.length)
        while (s < e && (body[s].isWhitespace() || body[s] == ',')) s++
        while (e > s && (body[e - 1].isWhitespace() || body[e - 1] == ',')) e--
        val low = body.substring(s, e)
        val orig = if (original.length == body.length) original.substring(s, e)
        else originalSubstring(original, body, low)
        return low to orig
    }

    private fun cleanContact(raw: String): String =
        raw.trim().trim(' ', '.', ',', '!', '?').trim('"', '«', '»', '“', '”').trim()

    /**
     * «открой/запусти <app> и напиши [контакту] <contact> <text>»
     */
    private fun parseComposeWithApp(body: String, original: String): SvetlanaAction? {
        val regex = Regex(
            "^(открой|запусти|перейди в|открой приложение|запусти приложение)\\s+(.+?)\\s*,?\\s+и\\s+" +
                "напиши\\s+(?:контакту\\s+)?(.+)$"
        )
        val m = regex.matchEntire(body) ?: return null
        val app = m.groupValues[2].trim().trim(' ', '.', ',', '!', '?')
        // «открой X, найди Y и напиши» — это цепочка, а не имя приложения.
        if (app.contains(',')) return null
        val restLow = m.groupValues[3].trim()
        val restOrig = originalSubstring(original, body, restLow)
        val (contact, text) = splitContactAndText(restLow, restOrig)
        if (contact.isBlank() || text.isBlank() || contact in RU_PRONOUNS) return null
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
        if (contact.isBlank() || text.isBlank() || contact in RU_PRONOUNS) return null
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
            is SvetlanaAction.ComposeMessage ->
                if (action.appTarget.isBlank()) action.copy(appTarget = app) else action
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
