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
        assertThat(IncomingCommands.parse("прочитай сообщения в WhatsApp")).isEqualTo(SvetlanaAction.ReadIncoming(null))
        assertThat(IncomingCommands.parse("есть новые сообщения?")).isEqualTo(SvetlanaAction.ReadIncoming(null))
        assertThat(IncomingCommands.parse("What did John write?")).isEqualTo(SvetlanaAction.ReadIncoming("john"))
        assertThat(IncomingCommands.parse("read my messages")).isEqualTo(SvetlanaAction.ReadIncoming(null))
        assertThat(IncomingCommands.parse("any new messages")).isEqualTo(SvetlanaAction.ReadIncoming(null))
    }

    @Test
    fun `reply commands keep the text case`() {
        assertThat(IncomingCommands.parse("Ответь ему: Буду через час")).isEqualTo(SvetlanaAction.ReplyIncoming(null, "Буду через час"))
        assertThat(IncomingCommands.parse("ответь Серому буду через час")).isEqualTo(SvetlanaAction.ReplyIncoming("серому", "буду через час"))
        assertThat(IncomingCommands.parse("ответь: Хорошо")).isEqualTo(SvetlanaAction.ReplyIncoming(null, "Хорошо"))
        assertThat(IncomingCommands.parse("ответь хорошо")).isEqualTo(SvetlanaAction.ReplyIncoming(null, "хорошо"))
        assertThat(IncomingCommands.parse("Reply to Sam I'll be late")).isEqualTo(SvetlanaAction.ReplyIncoming("sam", "I'll be late"))
        assertThat(IncomingCommands.parse("reply: ok")).isEqualTo(SvetlanaAction.ReplyIncoming(null, "ok"))
    }

    @Test
    fun `questions to the AI and other commands are not touched`() {
        assertThat(IncomingCommands.parse("ответь на вопрос что такое ИИ")).isNull()
        assertThat(IncomingCommands.parse("ответь мне честно")).isNull()
        assertThat(IncomingCommands.parse("прочитай экран")).isNull()
        assertThat(IncomingCommands.parse("открой WhatsApp")).isNull()
        assertThat(IncomingCommands.parse("")).isNull()
    }
}
