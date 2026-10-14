package com.svetlana.home.control

/**
 * Входящее сообщение из уведомления мессенджера (без Android-объектов).
 * [chat] — название группы (для личных чатов null), [canReply] — в уведомлении есть кнопка «Ответить».
 */
data class IncomingEntry(
    val key: String,
    val pkg: String,
    val app: String,
    val sender: String,
    val text: String,
    val time: Long,
    val canReply: Boolean,
    val chat: String? = null
)

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
 * Имена сравниваются по правилам [ContactMatch] (сначала как сказано, потом падежные формы):
 * «что написал Серого» найдёт «Серый». Ответ по имени, под которое подходят РАЗНЫЕ люди, не угадывается.
 * «Ответь ему» — последнему написавшему; кому именно — пользователь видит в подтверждении.
 * Сообщения с кодами (вход, оплата) вслух не зачитываются.
 */
object IncomingLogic {

    private const val MAX_READ = 5
    private const val MAX_TEXT = 200
    private val PRONOUNS = setOf("ему", "ей", "им", "нему", "ней", "ним", "him", "her", "them")
    private val CODE_BEFORE = Regex("(код|code|парол|password|passcode|otp|пин|pin)[^\\d]{0,30}\\d{4,8}", RegexOption.IGNORE_CASE)
    private val CODE_AFTER = Regex("\\d{4,8}[^\\d]{0,30}(код|code|парол|password|otp)", RegexOption.IGNORE_CASE)

    fun isPronoun(s: String?): Boolean = s != null && ContactMatch.norm(s) in PRONOUNS

    /** Сообщение с одноразовым кодом/паролем — вслух не зачитываем. */
    fun looksLikeCode(text: String): Boolean = CODE_BEFORE.containsMatchIn(text) || CODE_AFTER.containsMatchIn(text)

    /** Как назвать собеседника: «Группа — Серый» или просто «Серый». */
    fun who(e: IncomingEntry): String = if (e.chat != null) "${e.chat} — ${e.sender}" else e.sender

    private fun nameMatches(e: IncomingEntry, form: String): Boolean =
        ContactMatch.matches(e.sender, form) || (e.chat != null && ContactMatch.matches(e.chat, form))

    fun matching(contact: String?, entries: List<IncomingEntry>): List<IncomingEntry> {
        if (contact == null || isPronoun(contact)) return entries
        val forms = ContactMatch.forms(contact)
        if (forms.isEmpty()) return entries
        val literal = entries.filter { nameMatches(it, forms[0]) }
        if (literal.isNotEmpty()) return literal
        val derived = forms.drop(1)
        return entries.filter { e -> derived.any { nameMatches(e, it) } }
    }

    fun replyTarget(contact: String?, entries: List<IncomingEntry>): IncomingPick {
        val replyable = entries.filter { it.canReply }
        if (replyable.isEmpty()) return IncomingPick.Empty
        val hits = matching(contact, replyable)
        if (hits.isEmpty()) return IncomingPick.NotFound
        if (contact != null && !isPronoun(contact)) {
            val people = hits.map { ContactMatch.norm(who(it)) }.distinct()
            if (people.size > 1) return IncomingPick.Ambiguous(hits.map { who(it) }.distinct().take(5))
        }
        return IncomingPick.Found(hits.maxByOrNull { it.time }!!)
    }

    fun safeText(text: String, english: Boolean): String =
        if (looksLikeCode(text)) (if (english) "[message with a code hidden]" else "[сообщение с кодом скрыто]")
        else if (text.length > MAX_TEXT) text.take(MAX_TEXT) + "…" else text

    /** Текст для ответа пользователю: последние сообщения, свежие сверху. */
    fun summary(contact: String?, entries: List<IncomingEntry>, english: Boolean): String {
        val named = contact != null && !isPronoun(contact)
        val hits = matching(contact, entries).sortedByDescending { it.time }
        if (hits.isEmpty()) {
            return if (named) {
                if (english) "No new messages from “$contact”" else "Нет новых сообщений от «$contact»"
            } else {
                if (english) "No new messages" else "Новых сообщений нет"
            }
        }
        return hits.take(MAX_READ).joinToString("\n") { e -> "${who(e)} (${e.app}): ${safeText(e.text, english)}" }
    }
}
