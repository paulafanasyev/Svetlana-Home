package com.svetlana.home.control

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class IncomingCommandsTest {

    @Test
    fun `read commands`() {
        assertThat(IncomingCommands.parse("Что мне написал Серый?")).isEqualTo(SvetlanaAction.ReadIncoming("серый"))
        assertThat(IncomingCommands.parse("что написала Маша")).isEqualTo(SvetlanaAction.ReadIncoming("маша"))
        assertThat(IncomingCommands.parse("Света, прочитай сообщения")).isEqualTo(SvetlanaAction.ReadIncoming(null))
        assertThat(IncomingCommands.parse("прочитай новые сообщения от Серого")).isEqualTo(SvetlanaAction.ReadIncoming("серого"))
        assertThat(IncomingCommands.parse("есть новые сообщения?")).isEqualTo(SvetlanaAction.ReadIncoming(null))
        assertThat(IncomingCommands.parse("What did John write?")).isEqualTo(SvetlanaAction.ReadIncoming("john"))
        assertThat(IncomingCommands.parse("read my messages")).isEqualTo(SvetlanaAction.ReadIncoming(null))
        assertThat(IncomingCommands.parse("any new messages")).isEqualTo(SvetlanaAction.ReadIncoming(null))
    }

    @Test
    fun `read with app filter and latest only`() {
        assertThat(IncomingCommands.parse("прочитай сообщения в WhatsApp")).isEqualTo(SvetlanaAction.ReadIncoming(null, "whatsapp"))
        assertThat(IncomingCommands.parse("что написал Серый в ватсапе")).isEqualTo(SvetlanaAction.ReadIncoming("серый", "whatsapp"))
        assertThat(IncomingCommands.parse("прочитай сообщения от Маши в телеге")).isEqualTo(SvetlanaAction.ReadIncoming("маши", "telegram"))
        assertThat(IncomingCommands.parse("что ответил Петр в ответ")).isEqualTo(SvetlanaAction.ReadIncoming("петр в ответ"))
        assertThat(IncomingCommands.parse("прочитай последнее сообщение")).isEqualTo(SvetlanaAction.ReadIncoming(null, null, latestOnly = true))
        assertThat(IncomingCommands.parse("read the last message")).isEqualTo(SvetlanaAction.ReadIncoming(null, null, latestOnly = true))
        assertThat(IncomingCommands.parse("прочитай последние сообщения")).isEqualTo(SvetlanaAction.ReadIncoming(null))
    }

    @Test
    fun `reply commands keep the text case`() {
        assertThat(IncomingCommands.parse("Ответь ему: Буду через час")).isEqualTo(SvetlanaAction.ReplyIncoming(null, "Буду через час"))
        assertThat(IncomingCommands.parse("ответь Серому буду через час")).isEqualTo(SvetlanaAction.ReplyIncoming("серому", "буду через час"))
        assertThat(IncomingCommands.parse("ответь Серому, что буду поздно")).isEqualTo(SvetlanaAction.ReplyIncoming("серому", "буду поздно"))
        assertThat(IncomingCommands.parse("ответь, что буду поздно")).isEqualTo(SvetlanaAction.ReplyIncoming(null, "буду поздно"))
        assertThat(IncomingCommands.parse("ответь что буду поздно")).isEqualTo(SvetlanaAction.ReplyIncoming(null, "буду поздно"))
        assertThat(IncomingCommands.parse("ответь: Хорошо")).isEqualTo(SvetlanaAction.ReplyIncoming(null, "Хорошо"))
        assertThat(IncomingCommands.parse("ответь хорошо")).isEqualTo(SvetlanaAction.ReplyIncoming(null, "хорошо"))
        assertThat(IncomingCommands.parse("Reply to Sam I'll be late")).isEqualTo(SvetlanaAction.ReplyIncoming("sam", "I'll be late"))
        assertThat(IncomingCommands.parse("reply that I'm late")).isEqualTo(SvetlanaAction.ReplyIncoming(null, "I'm late"))
        assertThat(IncomingCommands.parse("reply: ok")).isEqualTo(SvetlanaAction.ReplyIncoming(null, "ok"))
    }

    @Test
    fun `reply with app`() {
        assertThat(IncomingCommands.parse("ответь Серому в телеге ок")).isEqualTo(SvetlanaAction.ReplyIncoming("серому", "ок", app = "telegram"))
        assertThat(IncomingCommands.parse("ответь в ватсапе ок")).isEqualTo(SvetlanaAction.ReplyIncoming(null, "ок", app = "whatsapp"))
        // «в субботу» — не приложение, а часть текста.
        assertThat(IncomingCommands.parse("ответь Серому в субботу приду")).isEqualTo(SvetlanaAction.ReplyIncoming("серому", "в субботу приду"))
    }

    @Test
    fun `questions to the AI and other commands are not touched`() {
        assertThat(IncomingCommands.parse("ответь на вопрос что такое ИИ")).isNull()
        assertThat(IncomingCommands.parse("ответь почему небо голубое")).isNull()
        assertThat(IncomingCommands.parse("ответь, как работает двигатель")).isNull()
        assertThat(IncomingCommands.parse("ответь что такое ИИ")).isNull()
        assertThat(IncomingCommands.parse("ответь мне честно")).isNull()
        assertThat(IncomingCommands.parse("reply why is the sky blue")).isNull()
        assertThat(IncomingCommands.parse("прочитай экран")).isNull()
        assertThat(IncomingCommands.parse("открой WhatsApp")).isNull()
        assertThat(IncomingCommands.parse("")).isNull()
    }
}
