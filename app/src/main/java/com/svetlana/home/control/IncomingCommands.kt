package com.svetlana.home.control

/**
 * Команды про входящие (RU/EN), разбираются до [CommandParser]:
 * - «что мне написал Серый», «прочитай сообщения (от Серого)», «есть новые сообщения» → [SvetlanaAction.ReadIncoming];
 * - «ответь ему: буду через час», «ответь Серому буду через час», «ответь: хорошо» → [SvetlanaAction.ReplyIncoming].
 *
 * «Ответь на вопрос…», «ответь мне…» — это вопрос к ИИ, а не ответ в чат: такие фразы не трогаем.
 * Регистр текста ответа сохраняется.
 */
object IncomingCommands {

    private val WAKE = Regex("^(?:света|светочка|светлана|sveta|svetlana)[,!.\\s]+")
    private val POLITE = Regex("^(?:пожалуйста|please)[,\\s]+")
    private val TRAILING = Regex("[\\s.!?…]+$")

    private val READ_FROM = listOf(
        Regex("^что\\s+(?:мне\\s+)?(?:написал[аи]?|пишет|пишут|прислал[аи]?|ответил[аи]?)\\s+(.+?)$"),
        Regex("^what\\s+did\\s+(.+?)\\s+(?:write|send|say|text)(?:\\s+me)?$")
    )
    private val READ = listOf(
        Regex("^(?:прочитай|прочти|зачитай|покажи|какие)(?:\\s+мне)?(?:\\s+(?:все|новые|последние|входящие|непрочитанные))?\\s+(?:сообщения|сообщение|уведомления|смски|смс)(?:\\s+(?:в|из)\\s+[\\p{L}\\p{N}]+)?(?:\\s+от\\s+(.+?))?$"),
        Regex("^(?:read|show)(?:\\s+me)?(?:\\s+my)?(?:\\s+(?:new|latest|recent|unread))?\\s+(?:messages|message|notifications|texts)(?:\\s+(?:in|on)\\s+[\\p{L}\\p{N}]+)?(?:\\s+from\\s+(.+?))?$")
    )
    private val ANY_NEW = listOf(
        Regex("^(?:есть\\s+)?(?:ли\\s+)?(?:новые|какие-нибудь|какие-то)\\s+сообщения(?:\\s+есть)?$"),
        Regex("^(?:do\\s+i\\s+have\\s+|any\\s+)(?:new\\s+|unread\\s+)?messages$")
    )
    private val REPLY_BARE = listOf(
        Regex("^ответь\\s*[:,—–-]\\s*(.+)$"),
        Regex("^reply\\s*[:,—–-]\\s*(.+)$")
    )
    private val REPLY = listOf(
        Regex("^ответь(?:\\s+([\\p{L}\\-]+))?\\s*[,:—–-]?\\s+(?:что\\s+)?(.+)$"),
        Regex("^reply(?:\\s+to)?(?:\\s+([\\p{L}\\-]+))?\\s*[,:—–-]?\\s+(?:that\\s+)?(.+)$")
    )

    /** Слова после «ответь», после которых идёт вопрос к ИИ, а не сообщение собеседнику. */
    private val NOT_A_REPLY = setOf(
        "на", "мне", "нам", "честно", "коротко", "кратко", "подробно", "пожалуйста", "всем",
        "me", "us", "please", "briefly", "honestly"
    )

    fun parse(raw: String): SvetlanaAction? {
        var s = raw.trim()
        var low = s.lowercase()
        // Индексы групп берём из строки в нижнем регистре; если длина поменялась — работаем только с ней.
        if (low.length != s.length) s = low
        WAKE.find(low)?.let { s = s.substring(it.range.last + 1); low = low.substring(it.range.last + 1) }
        POLITE.find(low)?.let { s = s.substring(it.range.last + 1); low = low.substring(it.range.last + 1) }
        TRAILING.find(low)?.let { s = s.substring(0, it.range.first); low = low.substring(0, it.range.first) }
        s = s.trim()
        low = low.trim()
        if (low.isEmpty()) return null

        for (re in READ_FROM) re.matchEntire(low)?.let { m ->
            return SvetlanaAction.ReadIncoming(m.groupValues[1].trim().ifEmpty { null })
        }
        for (re in READ) re.matchEntire(low)?.let { m ->
            return SvetlanaAction.ReadIncoming(m.groups[1]?.value?.trim()?.ifEmpty { null })
        }
        for (re in ANY_NEW) if (re.matches(low)) return SvetlanaAction.ReadIncoming(null)
        for (re in REPLY_BARE) re.matchEntire(low)?.let { m ->
            val text = m.groups[1]?.range?.let { s.substring(it) }?.trim().orEmpty()
            return if (text.isEmpty()) null else SvetlanaAction.ReplyIncoming(null, text)
        }
        for (re in REPLY) re.matchEntire(low)?.let { m ->
            val who = m.groups[1]?.value?.trim()
            if (who != null && who in NOT_A_REPLY) return null
            val text = m.groups[2]?.range?.let { s.substring(it) }?.trim().orEmpty()
            if (text.isEmpty()) return null
            val contact = if (who == null || IncomingLogic.isPronoun(who)) null else who
            return SvetlanaAction.ReplyIncoming(contact, text)
        }
        return null
    }
}
