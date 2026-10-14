package com.svetlana.home.control

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class IncomingLogicTest {

    private fun e(
        key: String, sender: String, text: String, time: Long, canReply: Boolean = true, chat: String? = null,
        app: String = "WhatsApp", pkg: String = "com.whatsapp", answered: Boolean = false
    ) = IncomingEntry(key, pkg, app, sender, text, time, canReply, chat, answered)

    private val inbox = listOf(
        e("1", "Серый", "привет, ты где?", 100),
        e("2", "Маша", "купи хлеб", 200, app = "Telegram", pkg = "org.telegram.messenger"),
        e("3", "Банк", "Ваш код входа 482913", 300, canReply = false)
    )

    @Test
    fun `reading by name with cases`() {
        assertThat(IncomingLogic.summary("серого", inbox, false)).isEqualTo("Серый (WhatsApp): привет, ты где?")
        assertThat(IncomingLogic.summary("Ольга", inbox, false)).isEqualTo("Нет новых сообщений от «Ольга»")
        assertThat(IncomingLogic.summary(null, emptyList(), false)).isEqualTo("Новых сообщений нет")
    }

    @Test
    fun `app filter and latest only`() {
        assertThat(IncomingLogic.summary(null, inbox, false, app = "telegram")).isEqualTo("Маша (Telegram): купи хлеб")
        assertThat(IncomingLogic.summary(null, inbox, false, limit = 1).lines()).hasSize(1)
        assertThat(IncomingLogic.canonicalApp("ватсапе")).isEqualTo("whatsapp")
        assertThat(IncomingLogic.canonicalApp("телеге")).isEqualTo("telegram")
        assertThat(IncomingLogic.canonicalApp("субботу")).isNull()
    }

    @Test
    fun `codes are never read aloud and newest come first`() {
        val all = IncomingLogic.summary(null, inbox, false).lines()
        assertThat(all.first()).isEqualTo("Банк (WhatsApp): [сообщение с кодом скрыто]")
        assertThat(all.joinToString()).doesNotContain("482913")
        assertThat(IncomingLogic.looksLikeCode("Your code is 1234")).isTrue()
        assertThat(IncomingLogic.looksLikeCode("123456")).isTrue()
        assertThat(IncomingLogic.looksLikeCode("Use 123456")).isTrue()
        assertThat(IncomingLogic.looksLikeCode("Никому не сообщайте: 7781")).isTrue()
        assertThat(IncomingLogic.looksLikeCode("буду в 1900")).isFalse()
    }

    @Test
    fun `reply target`() {
        assertThat(IncomingLogic.replyTarget("серому", inbox)).isEqualTo(IncomingPick.Found(inbox[0]))
        // «ему» — последний, кому можно ответить (банк без кнопки «Ответить» не считается)
        assertThat(IncomingLogic.replyTarget("ему", inbox)).isEqualTo(IncomingPick.Found(inbox[1]))
        assertThat(IncomingLogic.replyTarget(null, inbox)).isEqualTo(IncomingPick.Found(inbox[1]))
        assertThat(IncomingLogic.replyTarget(null, inbox, app = "whatsapp")).isEqualTo(IncomingPick.Found(inbox[0]))
        assertThat(IncomingLogic.replyTarget("Ольге", inbox)).isEqualTo(IncomingPick.NotFound)
        assertThat(IncomingLogic.replyTarget(null, listOf(inbox[2]))).isEqualTo(IncomingPick.Empty)
    }

    @Test
    fun `already answered chats are skipped for him`() {
        val list = listOf(e("a", "Серый", "ты где", 10), e("b", "Маша", "ок", 20, answered = true))
        assertThat(IncomingLogic.replyTarget("ему", list)).isEqualTo(IncomingPick.Found(list[0]))
        assertThat(IncomingLogic.replyTarget("ему", listOf(list[1]))).isEqualTo(IncomingPick.Found(list[1]))
    }

    @Test
    fun `own replies never become the sender`() {
        val msgs = listOf(
            StyleMessage("Серый", "привет", 1, fromMe = false),
            StyleMessage("Серый", "ты где?", 2, fromMe = false),
            StyleMessage(null, "еду", 3, fromMe = true)
        )
        val inbound = IncomingLogic.inbound(msgs, "Серый")!!
        assertThat(inbound.sender).isEqualTo("Серый")
        assertThat(inbound.text).isEqualTo("привет / ты где?")
        assertThat(inbound.time).isEqualTo(2)
        assertThat(inbound.answered).isTrue()
        assertThat(IncomingLogic.inbound(listOf(StyleMessage(null, "еду", 3, fromMe = true)), "Я")).isNull()
    }

    @Test
    fun `same name in two apps or two people is ambiguous`() {
        val two = listOf(e("a", "Алекс", "1", 1), e("b", "Алекс Иванов", "2", 2))
        assertThat(IncomingLogic.replyTarget("Алекс", two)).isInstanceOf(IncomingPick.Ambiguous::class.java)
        val apps = listOf(e("w", "Серый", "1", 1), e("t", "Серый", "2", 2, app = "Telegram", pkg = "org.telegram.messenger"))
        assertThat(IncomingLogic.replyTarget("Серому", apps)).isInstanceOf(IncomingPick.Ambiguous::class.java)
        assertThat(IncomingLogic.replyTarget("Серому", apps, app = "telegram")).isEqualTo(IncomingPick.Found(apps[1]))
        val group = listOf(e("g", "Серый", "ужин?", 5, chat = "Семья"))
        assertThat(IncomingLogic.who(group[0])).isEqualTo("Семья — Серый")
        assertThat(IncomingLogic.replyTarget("семье", group)).isEqualTo(IncomingPick.Found(group[0]))
        assertThat(group[0].fingerprint).isEqualTo("com.whatsapp|семья")
    }
}
