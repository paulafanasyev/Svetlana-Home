package com.svetlana.home.control

/**
 * Команды про входящие (RU/EN), разбираются до [CommandParser]:
 * - «что мне написал Серый (в ватсапе)», «прочитай сообщения (от Серого)»,
 *   «прочитай последнее сообщение», «есть новые сообщения» → [SvetlanaAction.ReadIncoming];
 * - «ответь ему: буду через час», «ответь Серому буду через час», «ответь Серому в телеге ок»,
 *   «ответь, что буду поздно» → [SvetlanaAction.ReplyIncoming].
 *
 * Вопросы к ИИ («ответь, почему небо голубое», «ответь на вопрос…», «ответь, что такое…»,
 * «reply why…») не трогаем. Регистр текста ответа сохраняется.
 */
object IncomingCommands {

    private val WAKE = Regex("^(?:света|светочка|светлана|sveta|svetlana)[,!.\\s]+")
    private val POLITE = Regex("^(?:пожалуйста|please)[,\\s]+")
    private val TRAILING = Regex("[\\s.!?…]+$")

    private val READ_FROM = listOf(
        Regex("^что\\s+(?:мне\\s+)?(?:написал[аи]?|пишет|пишут|прислал[аи]?|ответил[аи]?)\\s+(.+?)(?:\\s+(?:в|во)\\s+(\\p{L}+))?$"),
        Regex("^what\\s+did\\s+(.+?)\\s+(?:write|send|say|text|reply)(?:\\s+me)?(?:\\s+(?:in|on)\\s+(\\p{L}+))?$")
    )
    // Группы: 1 — уточнение, 2 — существительное, 3/5 — приложение, 4 — от кого.
    private val READ = listOf(
        Regex("^(?:прочитай|прочти|зачитай|покажи|какие)(?:\\s+мне)?(?:\\s+(все|новые|последние|последнее|входящие|непрочитанные))?\\s+(сообщения|сообщение|уведомления|смски|смс)(?:\\s+(?:в|во|из)\\s+([\\p{L}\\p{N}]+))?(?:\\s+от\\s+(.+?))?(?:\\s+(?:в|во|из)\\s+([\\p{L}\\p{N}]+))?$"),
        Regex("^(?:read|show)(?:\\s+me)?(?:\\s+(?:my|the))?(?:\\s+(new|latest|recent|unread|last))?\\s+(messages|message|notifications|texts)(?:\\s+(?:in|on)\\s+([\\p{L}\\p{N}]+))?(?:\\s+from\\s+(.+?))?(?:\\s+(?:in|on)\\s+([\\p{L}\\p{N}]+))?$")
    )
    private val ANY_NEW = listOf(
        Regex("^(?:есть\\s+)?(?:ли\\s+)?(?:новые|какие-нибудь|какие-то)\\s+сообщения(?:\\s+есть)?$"),
        Regex("^(?:do\\s+i\\s+have\\s+|any\\s+)(?:new\\s+|unread\\s+)?messages$")
    )
    private val REPLY_BARE = listOf(
        Regex("^ответь\\s*[:,—–-]\\s*(.+)$"),
        Regex("^reply\\s*[:,—–-]\\s*(.+)$")
    )
    private val REPLY_IN_APP = listOf(
        Regex("^ответь\\s+(?:в|во)\\s+(\\p{L}+)\\s*[,:—–-]?\\s+(.+)$"),
        Regex("^reply\\s+(?:in|on)\\s+(\\p{L}+)\\s*[,:—–-]?\\s+(.+)$")
    )
    private val REPLY_WHO_IN_APP = listOf(
        Regex("^ответь\\s+([\\p{L}\\-]+)\\s+(?:в|во)\\s+(\\p{L}+)\\s*[,:—–-]?\\s+(.+)$"),
        Regex("^reply(?:\\s+to)?\\s+([\\p{L}\\-]+)\\s+(?:in|on)\\s+(\\p{L}+)\\s*[,:—–-]?\\s+(.+)$")
    )
    private val REPLY = listOf(
        Regex("^ответь(?:\\s+([\\p{L}\\-]+))?\\s*[,:—–-]?\\s+(.+)$"),
        Regex("^reply(?:\\s+to)?(?:\\s+([\\p{L}\\-]+))?\\s*[,:—–-]?\\s+(.+)$")
    )

    /** Слова после «ответь», после которых идёт вопрос к ИИ, а не сообщение собеседнику. */
    private val NOT_A_REPLY = setOf(
        "на", "мне", "нам", "честно", "коротко", "кратко", "подробно", "пожалуйста", "всем",
        "почему", "зачем", "как", "кто", "где", "когда", "сколько", "какой", "какая", "какое", "какие",
        "чем", "куда", "откуда", "ли", "в", "во",
        "me", "us", "please", "briefly", "honestly", "why", "how", "what", "who", "where", "when", "which",
        "whether", "if", "in", "on"
    )
    private val CONJUNCTIONS = setOf("что", "that")
    /** «ответь, что такое ИИ» — вопрос, «ответь, что буду поздно» — ответ. */
    private val QUESTION_AFTER_CHTO = listOf(
        "такое", "такой", "значит", "лучше", "делать", "думаешь", "ты ", "это ",
        "is ", "are ", "does ", "do ", "you "
    )
    private val LEADING_CONJ = Regex("^(?:что|that)\\s+", RegexOption.IGNORE_CASE)

    fun parse(raw: String): SvetlanaAction? {
        var s = raw.trim()
        var low = s.lowercase()
        // Индексы групп берём из строки в нижнем регистре; если длина поменялась — работаем только с ней.
        if (low.length != s.length) s = low
        WAKE.find(low)?.let { s = s.substring(it.range.last + 1); low = low.substring(it.range.last + 1) }
        POLITE.find(low)?.let { s = s.substring(it.range.last + 1); low = low.substring(it.range.last + 1) }
        TRAILING.find(low)?.let { s = s.substring(0, it.range.first); low = low.substring(0, it.range.first) }
        if (s.length != low.length) return null
        val lead = low.length - low.trimStart().length
        s = s.substring(lead).trimEnd()
        low = low.substring(lead).trimEnd()
        if (low.isEmpty()) return null

        for (re in READ_FROM) re.matchEntire(low)?.let { m ->
            val who = m.groupValues[1].trim()
            val appWord = m.groups[2]?.value
            val app = IncomingLogic.canonicalApp(appWord)
            // «что ответил Пётр в ответ»: «в …» — не приложение, значит часть имени/фразы.
            val contact = if (appWord != null && app == null) low.substring(m.groups[1]!!.range.first).trim() else who
            return SvetlanaAction.ReadIncoming(contact.ifEmpty { null }, app)
        }
        for (re in READ) re.matchEntire(low)?.let { m ->
            val modifier = m.groups[1]?.value
            val noun = m.groups[2]?.value
            val app = IncomingLogic.canonicalApp(m.groups[3]?.value ?: m.groups[5]?.value)
            val latestOnly = modifier in setOf("последнее", "last", "latest") && noun in setOf("сообщение", "message")
            return SvetlanaAction.ReadIncoming(m.groups[4]?.value?.trim()?.ifEmpty { null }, app, latestOnly)
        }
        for (re in ANY_NEW) if (re.matches(low)) return SvetlanaAction.ReadIncoming(null)
        for (re in REPLY_BARE) re.matchEntire(low)?.let { m ->
            return reply(s, null, m.groups[1]!!.range, null)
        }
        for (re in REPLY_IN_APP) re.matchEntire(low)?.let { m ->
            val app = IncomingLogic.canonicalApp(m.groups[1]?.value)
            if (app != null) return reply(s, null, m.groups[2]!!.range, app)
        }
        for (re in REPLY_WHO_IN_APP) re.matchEntire(low)?.let { m ->
            val app = IncomingLogic.canonicalApp(m.groups[2]?.value)
            if (app != null) return reply(s, m.groups[1]?.value, m.groups[3]!!.range, app)
        }
        for (re in REPLY) re.matchEntire(low)?.let { m ->
            return reply(s, m.groups[1]?.value, m.groups[2]!!.range, null)
        }
        return null
    }

    private fun reply(s: String, whoRaw: String?, textRange: IntRange, app: String?): SvetlanaAction? {
        var who = whoRaw?.trim()?.lowercase()
        var text = s.substring(textRange).trim()
        if (text.isEmpty()) return null
        if (who != null && who in NOT_A_REPLY) return null
        if (who != null && who in CONJUNCTIONS) {
            val lowText = text.lowercase()
            if (QUESTION_AFTER_CHTO.any { lowText.startsWith(it) }) return null
            who = null
        }
        if (who != null && IncomingLogic.isPronoun(who)) who = null
        // «ответь Серому, что буду поздно» → текст «буду поздно».
        text = text.replace(LEADING_CONJ, "").trim()
        if (text.isEmpty()) return null
        return SvetlanaAction.ReplyIncoming(who, text, app = app)
    }
}
