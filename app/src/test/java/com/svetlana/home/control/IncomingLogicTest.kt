package com.svetlana.home.control

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class IncomingLogicTest {

    private fun e(key: String, sender: String, text: String, time: Long, canReply: Boolean = true, chat: String? = null, app: String = "WhatsApp") =
        IncomingEntry(key, "com.whatsapp", app, sender, text, time, canReply, chat)

    private val inbox = listOf(
        e("1", "Серый", "привет, ты где?", 100),
        e("2", "Маша", "купи хлеб", 200, app = "Telegram"),
        e("3", "Банк", "Ваш код входа 482913", 300, canReply = false)
    )

    @Test
    fun `reading by name with cases`() {
        assertThat(IncomingLogic.summary("серого", inbox, false)).isEqualTo("Серый (WhatsApp): привет, ты где?")
        assertThat(IncomingLogic.summary("Ольга", inbox, false)).isEqualTo("Нет новых сообщений от «Ольга»")
        assertThat(IncomingLogic.summary(null, emptyList(), false)).isEqualTo("Новых сообщений нет")
    }

    @Test
    fun `codes are never read aloud and newest come first`() {
        val all = IncomingLogic.summary(null, inbox, false).lines()
        assertThat(all.first()).isEqualTo("Банк (WhatsApp): [сообщение с кодом скрыто]")
        assertThat(all.joinToString()).doesNotContain("482913")
        assertThat(IncomingLogic.looksLikeCode("Your code is 1234")).isTrue()
        assertThat(IncomingLogic.looksLikeCode("буду в 1900")).isFalse()
    }

    @Test
    fun `reply target`() {
        assertThat(IncomingLogic.replyTarget("серому", inbox)).isEqualTo(IncomingPick.Found(inbox[0]))
        // «ему» — последний, кому можно ответить (банк без кнопки «Ответить» не считается)
        assertThat(IncomingLogic.replyTarget("ему", inbox)).isEqualTo(IncomingPick.Found(inbox[1]))
        assertThat(IncomingLogic.replyTarget(null, inbox)).isEqualTo(IncomingPick.Found(inbox[1]))
        assertThat(IncomingLogic.replyTarget("Ольге", inbox)).isEqualTo(IncomingPick.NotFound)
        assertThat(IncomingLogic.replyTarget(null, listOf(inbox[2]))).isEqualTo(IncomingPick.Empty)
    }

    @Test
    fun `two people with one name are ambiguous`() {
        val two = listOf(e("a", "Алекс", "1", 1), e("b", "Алекс Иванов", "2", 2))
        assertThat(IncomingLogic.replyTarget("Алекс", two)).isInstanceOf(IncomingPick.Ambiguous::class.java)
        val group = listOf(e("g", "Серый", "ужин?", 5, chat = "Семья"))
        assertThat(IncomingLogic.who(group[0])).isEqualTo("Семья — Серый")
        assertThat(IncomingLogic.replyTarget("семье", group)).isEqualTo(IncomingPick.Found(group[0]))
    }
}
