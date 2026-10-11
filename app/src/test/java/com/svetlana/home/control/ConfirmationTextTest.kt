package com.svetlana.home.control

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Подтверждение показывает точные параметры действия (red team round 3, P1). */
class ConfirmationTextTest {

    @Test
    fun dangerousActionsShowExactParameters() {
        assertThat(ConfirmationText.question(SvetlanaAction.MakeCall("Мама")))
            .isEqualTo("Подтвердите: открыть набор номера «Мама»?")
        val sms = ConfirmationText.question(SvetlanaAction.SendMessage("+79001234567", "Переведи 5000"))
        assertThat(sms).contains("«+79001234567»")
        assertThat(sms).contains("«Переведи 5000»")
        val compose = ConfirmationText.question(SvetlanaAction.ComposeMessage("WhatsApp", "Серый", "привет"))
        assertThat(compose).contains("«WhatsApp»")
        assertThat(compose).contains("«Серый»")
        assertThat(compose).contains("«привет»")
        assertThat(ConfirmationText.question(SvetlanaAction.Share("секретный план"))).contains("«секретный план»")
    }

    @Test
    fun clickShowsRealOnScreenLabel() {
        val q = ConfirmationText.question(SvetlanaAction.Click("Telegram", "кнопка"), targetShown = "Удалить чат")
        assertThat(q).contains("«Удалить чат»")
        assertThat(q).contains("«Telegram»")
    }

    @Test
    fun compoundListsEveryStep() {
        val q = ConfirmationText.question(SvetlanaAction.Compound(listOf(
            SvetlanaAction.OpenApp("WhatsApp"),
            SvetlanaAction.SendMessage("Иван", "код 1234"),
        )))
        assertThat(q).contains("1) OpenApp")
        assertThat(q).contains("2) подготовить SMS «Иван» с текстом «код 1234»")
    }

    @Test
    fun secretFieldTextIsHidden() {
        val q = ConfirmationText.question(SvetlanaAction.TypeText("Банк", "пароль", "hunter2"), "Пароль", secretField = true)
        assertThat(q).doesNotContain("hunter2")
        assertThat(q).contains("скрытый текст (7 симв.)")
    }

    @Test
    fun bidiAndControlCharactersCannotDisguiseRecipient() {
        val q = ConfirmationText.quote("Мама\u202E7654321\nok")
        assertThat(q).isEqualTo("«Мама7654321 ok»")
        assertThat(ConfirmationText.quote("   ")).isEqualTo("«(пусто)»")
        assertThat(ConfirmationText.quote("x".repeat(500)).length).isLessThan(200)
    }
}
