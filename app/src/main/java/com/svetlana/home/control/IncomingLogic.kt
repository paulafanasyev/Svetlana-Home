package com.svetlana.home.control

/**
 * Входящее сообщение из уведомления мессенджера (без Android-объектов).
 * [sender] — последний ВХОДЯЩИЙ отправитель (свои ответы сюда не попадают), [chat] — название
 * группы (для личных чатов null), [canReply] — есть рабочая кнопка «Ответить» у известного
 * мессенджера, [answered] — после последнего входящего уже есть мой ответ, [convId] — устойчивый
 * идентификатор беседы от мессенджера (ярлык беседы Android 11+), если он его даёт.
 */
data class IncomingEntry(
    val key: String,
    val pkg: String,
    val app: String,
    val sender: String,
    val text: String,
    val time: Long,
    val canReply: Boolean,
    val chat: String? = null,
    val answered: Boolean = false,
    val convId: String? = null
) {
    /**
     * С кем именно беседа. Ответ уходит только если перед отправкой она всё ещё та же.
     * Идентификатор беседы мессенджера надёжнее названия: две группы «Семья» не спутаются.
     */
    val fingerprint: String get() = "$pkg|" + (convId?.let { "id:$it" } ?: (chat ?: sender).trim().lowercase())
}

/** Сообщение из MessagingStyle; [fromMe] — моё собственное (мессенджер дописал мой ответ). */
data class StyleMessage(val sender: String?, val text: String?, val time: Long, val fromMe: Boolean)

data class Inbound(val sender: String, val text: String, val time: Long, val answered: Boolean)

sealed class IncomingPick {
    data class Found(val entry: IncomingEntry) : IncomingPick()
    data class Ambiguous(val names: List<String>) : IncomingPick()
    object NotFound : IncomingPick()
    /** Нет ни одного сообщения с кнопкой «Ответить». */
    object Empty : IncomingPick()
}

/**
 * Выбор и пересказ входящих (чистая логика, покрыта тестами).
 *
 * Имена сравниваются по правилам [ContactMatch] (сначала как сказано, потом падежные формы).
 * Имя, под которое подходят разные люди ИЛИ разные приложения, не угадывается.
 * «Ответь ему» — последнему написавшему, кому ещё не отвечено. Сообщения с кодами вслух
 * не зачитываются.
 */
object IncomingLogic {

    const val MAX_READ = 5
    private const val MAX_TEXT = 200
    private val PRONOUNS = setOf("ему", "ей", "им", "нему", "ней", "ним", "him", "her", "them")
    private const val CODE_WORDS =
        "(код|code|парол|password|passcode|otp|пин|pin|никому|не сообщайте|подтвержд|verif|вход|login|log in|sign in|use)"
    private val CODE_BEFORE = Regex("$CODE_WORDS[^\\d]{0,30}\\d{4,8}", RegexOption.IGNORE_CASE)
    private val CODE_AFTER = Regex("\\d{4,8}[^\\d]{0,30}$CODE_WORDS", RegexOption.IGNORE_CASE)
    private val BARE_CODE = Regex("(^|[^\\d])\\d{5,8}([^\\d]|$)")
    /** Сообщение целиком из цифр («4829», «482 913») — почти наверняка код. */
    private val DIGITS_ONLY = Regex("^[\\d\\s\\-]{4,12}$")
    private const val SHORT = 40

    private val APP_STEMS = listOf(
        "whatsapp" to listOf("whatsapp", "ватсап", "вотсап", "вацап"),
        "telegram" to listOf("telegram", "телеграм", "телег"),
        "viber" to listOf("viber", "вайбер"),
        "signal" to listOf("signal", "сигнал"),
        "max" to listOf("max", "макс"),
        "vk" to listOf("vk", "вк", "вконтакт"),
    )

    /** «в ватсапе» → "whatsapp", «в телеге» → "telegram"; не мессенджер → null. */
    fun canonicalApp(word: String?): String? {
        val w = word?.trim()?.lowercase()?.ifEmpty { null } ?: return null
        return APP_STEMS.firstOrNull { (_, stems) -> stems.any { w.startsWith(it) } }?.first
    }

    fun appMatches(e: IncomingEntry, app: String?): Boolean =
        app == null || e.app.lowercase().contains(app) || e.pkg.lowercase().contains(app)

    fun isPronoun(s: String?): Boolean = s != null && ContactMatch.norm(s) in PRONOUNS

    /** Сообщение с одноразовым кодом/паролем — вслух не зачитываем. */
    fun looksLikeCode(text: String): Boolean {
        val t = text.trim()
        if (DIGITS_ONLY.matches(t) && t.count { it.isDigit() } in 4..8) return true
        return CODE_BEFORE.containsMatchIn(text) || CODE_AFTER.containsMatchIn(text) ||
            (t.length <= SHORT && BARE_CODE.containsMatchIn(text))
    }

    /**
     * Моё ли сообщение. По правилам MessagingStyle сообщения пользователя идут без Person, но
     * некоторые мессенджеры вообще не указывают отправителей: если Person нет ни у одного
     * сообщения ([allAnonymous]), считаем их входящими (отправитель — заголовок уведомления).
     *
     * У Person есть только ключи — надёжно сравниваем их. Имя само по себе ничего не доказывает:
     * собеседник тёзка «Павел» не должен стать «мной», поэтому без ключей сообщение с Person — входящее.
     */
    fun isFromMe(
        personKey: String?,
        personName: String?,
        userKey: String?,
        userName: String?,
        hasPerson: Boolean,
        allAnonymous: Boolean
    ): Boolean {
        if (!hasPerson) return !allAnonymous
        return personKey != null && userKey != null && personKey == userKey
    }

    /**
     * Последнее ВХОДЯЩЕЕ из беседы: мои собственные ответы, которые мессенджер
     * дописывает в уведомление, не делают меня «отправителем» и не сдвигают время. null — входящих нет.
     */
    fun inbound(messages: List<StyleMessage>, fallbackSender: String): Inbound? {
        val lastIn = messages.indexOfLast { !it.fromMe && !it.text.isNullOrBlank() }
        if (lastIn < 0) return null
        val last = messages[lastIn]
        val sender = last.sender?.trim()?.ifEmpty { null } ?: fallbackSender.trim().ifEmpty { null } ?: return null
        val texts = messages.subList(0, lastIn + 1)
            .filter { !it.fromMe && !it.text.isNullOrBlank() }
            .takeLast(3)
            .map { it.text.orEmpty().trim() }
        val answered = messages.drop(lastIn + 1).any { it.fromMe }
        return Inbound(sender, texts.joinToString(" / "), last.time, answered)
    }

    /** Как назвать собеседника: «Группа — Серый» или просто «Серый». */
    fun who(e: IncomingEntry): String = if (e.chat != null) "${e.chat} — ${e.sender}" else e.sender

    private fun nameMatches(e: IncomingEntry, form: String): Boolean =
        ContactMatch.matches(e.sender, form) || (e.chat != null && ContactMatch.matches(e.chat, form))

    fun matching(contact: String?, entries: List<IncomingEntry>, app: String? = null): List<IncomingEntry> {
        val inApp = entries.filter { appMatches(it, app) }
        if (contact == null || isPronoun(contact)) return inApp
        val forms = ContactMatch.forms(contact)
        if (forms.isEmpty()) return inApp
        val literal = inApp.filter { nameMatches(it, forms[0]) }
        if (literal.isNotEmpty()) return literal
        val derived = forms.drop(1)
        return inApp.filter { e -> derived.any { nameMatches(e, it) } }
    }

    fun replyTarget(contact: String?, entries: List<IncomingEntry>, app: String? = null): IncomingPick {
        val replyable = entries.filter { it.canReply && appMatches(it, app) }
        if (replyable.isEmpty()) return IncomingPick.Empty
        val hits = matching(contact, replyable)
        if (hits.isEmpty()) return IncomingPick.NotFound
        if (contact != null && !isPronoun(contact)) {
            // Разные люди, один человек в разных приложениях или разные беседы с одним именем — переспрашиваем.
            val conversations = hits.map { it.fingerprint }.distinct()
            if (conversations.size > 1) return IncomingPick.Ambiguous(hits.map { "${who(it)} (${it.app})" }.distinct().take(5))
            return IncomingPick.Found(hits.maxByOrNull { it.time }!!)
        }
        // «Ответь ему»: последний, кому ещё не отвечено; если отвечено всем — просто последний.
        val fresh = hits.filter { !it.answered }.ifEmpty { hits }
        return IncomingPick.Found(fresh.maxByOrNull { it.time }!!)
    }

    fun safeText(text: String, english: Boolean): String =
        if (looksLikeCode(text)) (if (english) "[message with a code hidden]" else "[сообщение с кодом скрыто]")
        else if (text.length > MAX_TEXT) text.take(MAX_TEXT) + "…" else text

    /** Текст для ответа пользователю: последние сообщения, свежие сверху. */
    fun summary(
        contact: String?,
        entries: List<IncomingEntry>,
        english: Boolean,
        app: String? = null,
        limit: Int = MAX_READ
    ): String {
        val named = contact != null && !isPronoun(contact)
        val hits = matching(contact, entries, app).sortedByDescending { it.time }
        if (hits.isEmpty()) {
            return if (named) {
                if (english) "No new messages from “$contact”" else "Нет новых сообщений от «$contact»"
            } else {
                if (english) "No new messages" else "Новых сообщений нет"
            }
        }
        return hits.take(limit.coerceIn(1, MAX_READ))
            .joinToString("\n") { e -> "${who(e)} (${e.app}): ${safeText(e.text, english)}" }
    }
}
