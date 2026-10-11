package com.svetlana.home.control

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** В подтверждении снимка/чтения экрана пользователь видит, КАКОЕ приложение затронет. */
class ConfirmationScreenTargetTest {
    @Test
    fun screenshotQuestionNamesRealApp() {
        val q = ConfirmationText.question(SvetlanaAction.TakeScreenshot("экран"), "org.telegram.messenger")
        assertThat(q).contains("org.telegram.messenger")
        assertThat(q).contains("снимок")
    }

    @Test
    fun readScreenQuestionNamesRealApp() {
        val q = ConfirmationText.question(SvetlanaAction.ReadScreen("экран"), "com.whatsapp")
        assertThat(q).contains("com.whatsapp")
    }

    @Test
    fun callIsDescribedAsDialerOnly() {
        assertThat(ConfirmationText.describe(SvetlanaAction.MakeCall("Иван"))).contains("набор номера")
    }
}
