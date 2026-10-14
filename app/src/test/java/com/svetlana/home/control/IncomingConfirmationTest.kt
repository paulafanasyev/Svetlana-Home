package com.svetlana.home.control

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Подтверждение ответа показывает приложение, собеседника, его сообщение и ответ. */
class IncomingConfirmationTest {

    @Test
    fun pinnedReplyShowsEverything() {
        val q = ConfirmationText.question(SvetlanaAction.ReplyIncoming(
            "Серый", "еду", key = "k", app = "WhatsApp", fp = "com.whatsapp|серый", quote = "ты где?"))
        assertThat(q).isEqualTo("Подтвердите: ответить в «WhatsApp» «Серый» на «ты где?»: «еду»?")
    }

    @Test
    fun bidiInSenderNameIsStripped() {
        val q = ConfirmationText.question(SvetlanaAction.ReplyIncoming("Мама\u202Eанк", "ок", key = "k", app = "Telegram", fp = "x"))
        assertThat(q).contains("«Мамаанк»")
        assertThat(q).doesNotContain("\u202E")
    }

    @Test
    fun replyIsDangerousReadIsSafe() {
        assertThat(ActionRiskPolicy.requiresConfirmation(SvetlanaAction.ReplyIncoming(null, "ок"))).isTrue()
        assertThat(ActionRiskPolicy.requiresConfirmation(SvetlanaAction.ReadIncoming(null))).isFalse()
    }
}
