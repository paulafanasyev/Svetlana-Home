package com.svetlana.home.hands

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SensitiveTextTest {
    @Test
    fun passwordFieldTextIsNeverExposed() {
        assertThat(SensitiveText.redact("hunter2", isPassword = true, id = "", contentDescription = ""))
            .isEqualTo(SensitiveText.MASK)
    }

    @Test
    fun otpPinAndCardFieldsAreRedactedByLabel() {
        assertThat(SensitiveText.redact("482913", false, "com.bank:id/otp_input", "")).isEqualTo(SensitiveText.MASK)
        assertThat(SensitiveText.redact("1234", false, "", "Пароль")).isEqualTo(SensitiveText.MASK)
        assertThat(SensitiveText.redact("5555", false, "", "", hint = "Код из СМС")).isEqualTo(SensitiveText.MASK)
        assertThat(SensitiveText.redact("123", false, "pay:id/cvv", "")).isEqualTo(SensitiveText.MASK)
    }

    @Test
    fun cardNumbersAreMaskedInOrdinaryText() {
        val out = SensitiveText.redact("Карта 4111 1111 1111 1111 привязана", false, "msg", "")
        assertThat(out).doesNotContain("4111 1111")
        assertThat(out).contains("1111 привязана")
        assertThat(out).contains(SensitiveText.MASK)
    }

    @Test
    fun ordinaryTextIsUntouched() {
        assertThat(SensitiveText.redact("Привет, как дела?", false, "message_text", "")).isEqualTo("Привет, как дела?")
        assertThat(SensitiveText.redact("Заказ 1234567890123", false, "", "")).isEqualTo("Заказ 1234567890123")
        assertThat(SensitiveText.redact("", true, "", "")).isEqualTo("")
    }

    @Test
    fun oneTimeCodesInMessagesAreMasked() {
        assertThat(SensitiveText.maskSecrets("Ваш код подтверждения: 482913. Никому не сообщайте"))
            .isEqualTo("Ваш код подтверждения: ${SensitiveText.MASK}. Никому не сообщайте")
        assertThat(SensitiveText.maskSecrets("Your code is 1234")).isEqualTo("Your code is ${SensitiveText.MASK}")
        assertThat(SensitiveText.maskSecrets("PIN 4321")).isEqualTo("PIN ${SensitiveText.MASK}")
        // No false positives on ordinary words and numbers.
        assertThat(SensitiveText.maskSecrets("Пинск 2024")).isEqualTo("Пинск 2024")
        assertThat(SensitiveText.maskSecrets("Barcode 12345")).isEqualTo("Barcode 12345")
        assertThat(SensitiveText.maskSecrets("Встреча в 12:30")).isEqualTo("Встреча в 12:30")
    }
}
