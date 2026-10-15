package com.svetlana.home.control

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Второй и третий круг критики входящих. */
class IncomingRound2Test {

    private fun e(key: String, sender: String, chat: String?, convId: String?, time: Long) =
        IncomingEntry(key, "com.whatsapp", "WhatsApp", sender, "текст", time, true, chat, false, convId)

    @Test
    fun replyKeepsTrailingPunctuation() {
        assertThat(IncomingCommands.parse("Ответь ему: Буду через час!")).isEqualTo(SvetlanaAction.ReplyIncoming(null, "Буду через час!"))
        assertThat(IncomingCommands.parse("ответь Серому ты где?")).isEqualTo(SvetlanaAction.ReplyIncoming("серому", "ты где?"))
        // Для чтения знаки в конце по-прежнему не мешают.
        assertThat(IncomingCommands.parse("Что мне написал Серый?!")).isEqualTo(SvetlanaAction.ReadIncoming("серый"))
    }

    @Test
    fun digitOnlyMessagesAreCodes() {
        assertThat(IncomingLogic.looksLikeCode("4829")).isTrue()
        assertThat(IncomingLogic.looksLikeCode(" 482 913 ")).isTrue()
        assertThat(IncomingLogic.looksLikeCode("буду в 1900")).isFalse()
        assertThat(IncomingLogic.looksLikeCode("123")).isFalse()
    }

    @Test
    fun fromMeDetection() {
        // Обычный MessagingStyle: мои сообщения без Person.
        assertThat(IncomingLogic.isFromMe(null, null, null, "Я", hasPerson = false, allAnonymous = false)).isTrue()
        // Мессенджер вообще не указывает отправителей — это входящие, а не мои.
        assertThat(IncomingLogic.isFromMe(null, null, null, "Я", hasPerson = false, allAnonymous = true)).isFalse()
        assertThat(IncomingLogic.isFromMe("u1", "Серый", "me", "Я", hasPerson = true, allAnonymous = false)).isFalse()
        assertThat(IncomingLogic.isFromMe("me", "Я", "me", "Я", hasPerson = true, allAnonymous = false)).isTrue()
        assertThat(IncomingLogic.isFromMe(null, "Серый", null, null, hasPerson = true, allAnonymous = false)).isFalse()
    }

    @Test
    fun namesakeOfUserIsNotMe() {
        // Собеседник тёзка «Павел» без ключей — входящее, а не мой ответ.
        assertThat(IncomingLogic.isFromMe(null, "Павел", null, "Павел", hasPerson = true, allAnonymous = false)).isFalse()
        assertThat(IncomingLogic.isFromMe("c1", "Павел", null, "Павел", hasPerson = true, allAnonymous = false)).isFalse()
    }

    @Test
    fun twoGroupsWithOneTitleAreDifferentConversations() {
        val a = e("a", "Серый", "Семья", "chat-1", 1)
        val b = e("b", "Маша", "Семья", "chat-2", 2)
        assertThat(a.fingerprint).isNotEqualTo(b.fingerprint)
        assertThat(IncomingLogic.replyTarget("семье", listOf(a, b))).isInstanceOf(IncomingPick.Ambiguous::class.java)
        // Без идентификатора беседы — как раньше, по названию.
        assertThat(e("c", "Серый", "Семья", null, 3).fingerprint).isEqualTo("com.whatsapp|семья")
    }

    @Test
    fun replyAllowedOnlyForKnownMessengers() {
        assertThat(IncomingInbox.REPLY_PACKAGES).contains("com.whatsapp")
        assertThat(IncomingInbox.REPLY_PACKAGES).contains("org.telegram.messenger")
        assertThat(IncomingInbox.REPLY_PACKAGES).doesNotContain("com.whatsapp.fake")
        assertThat(IncomingInbox.REPLY_PACKAGES).doesNotContain("com.example.app")
    }
}
