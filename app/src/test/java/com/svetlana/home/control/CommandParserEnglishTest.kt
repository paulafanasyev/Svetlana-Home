package com.svetlana.home.control

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Английские команды: те же действия, что и русские, и та же защита опасных действий.
 */
class CommandParserEnglishTest {

    @Test
    fun `latin wake word stripped`() {
        assertThat(CommandParser.stripWakeWord("Svetlana, open settings")).isEqualTo("open settings")
        assertThat(CommandParser.stripWakeWord("sveta call mom")).isEqualTo("call mom")
    }

    @Test
    fun `open app parses target`() {
        val a = CommandParser.parse("Svetlana, open Telegram") as SvetlanaAction.OpenApp
        assertThat(a.target).isEqualTo("telegram")
        val b = CommandParser.parse("please launch the WhatsApp app") as SvetlanaAction.OpenApp
        assertThat(b.target).isEqualTo("whatsapp")
        val c = CommandParser.parse("switch to YouTube.") as SvetlanaAction.OpenApp
        assertThat(c.target).isEqualTo("youtube")
    }

    @Test
    fun `navigation parses`() {
        assertThat(CommandParser.parse("go back")).isEqualTo(SvetlanaAction.PressBack)
        assertThat(CommandParser.parse("go home")).isEqualTo(SvetlanaAction.PressHome)
        assertThat(CommandParser.parse("go to the home screen")).isEqualTo(SvetlanaAction.PressHome)
        assertThat(CommandParser.parse("show recent apps")).isEqualTo(SvetlanaAction.OpenRecents)
        assertThat(CommandParser.parse("open settings")).isEqualTo(SvetlanaAction.OpenSettings)
    }

    @Test
    fun `screen actions parse`() {
        assertThat(CommandParser.parse("take a screenshot")).isInstanceOf(SvetlanaAction.TakeScreenshot::class.java)
        assertThat(CommandParser.parse("what's on the screen?")).isInstanceOf(SvetlanaAction.ReadScreen::class.java)
        val s = CommandParser.parse("scroll down") as SvetlanaAction.Scroll
        assertThat(s.direction).isEqualTo("down")
        val w = CommandParser.parse("swipe left") as SvetlanaAction.Swipe
        assertThat(w.direction).isEqualTo("left")
    }

    @Test
    fun `tap keeps original case`() {
        val a = CommandParser.parse("tap the Send button") as SvetlanaAction.Click
        assertThat(a.element).isEqualTo("Send")
        assertThat(a.target).isEqualTo("current")
    }

    @Test
    fun `type text parses`() {
        val a = CommandParser.parse("type hello world") as SvetlanaAction.TypeText
        assertThat(a.text).isEqualTo("hello world")
    }

    @Test
    fun `call is dangerous and needs confirmation`() {
        val a = CommandParser.parse("Svetlana, call Mom") as SvetlanaAction.MakeCall
        assertThat(a.contact).isEqualTo("mom")
        assertThat(ActionRiskPolicy.requiresConfirmation(a)).isTrue()
    }

    @Test
    fun `sms is dangerous and needs confirmation`() {
        val a = CommandParser.parse("send a text to Mom saying I'm late") as SvetlanaAction.SendMessage
        assertThat(a.contact).isEqualTo("mom")
        assertThat(a.text).isEqualTo("i'm late")
        assertThat(ActionRiskPolicy.requiresConfirmation(a)).isTrue()
    }

    @Test
    fun `compose with app parses and is dangerous`() {
        val a = CommandParser.parse("open WhatsApp and message John hello there") as SvetlanaAction.ComposeMessage
        assertThat(a.appTarget).isEqualTo("whatsapp")
        assertThat(a.contact).isEqualTo("john")
        assertThat(a.text).isEqualTo("hello there")
        assertThat(ActionRiskPolicy.requiresConfirmation(a)).isTrue()
    }

    @Test
    fun `compose without app strips saying`() {
        val a = CommandParser.parse("message John saying see you soon") as SvetlanaAction.ComposeMessage
        assertThat(a.appTarget).isEmpty()
        assertThat(a.contact).isEqualTo("john")
        assertThat(a.text).isEqualTo("see you soon")
    }

    @Test
    fun `compound open and tap retargets`() {
        val a = CommandParser.parse("open Telegram and tap Search") as SvetlanaAction.Compound
        assertThat(a.steps).hasSize(2)
        assertThat(a.steps[0]).isInstanceOf(SvetlanaAction.OpenApp::class.java)
        val click = a.steps[1] as SvetlanaAction.Click
        assertThat(click.target).isEqualTo("telegram")
        assertThat(click.element).isEqualTo("Search")
    }

    @Test
    fun `compound with dangerous tap needs confirmation`() {
        val a = CommandParser.parse("open the bank app then tap Pay now") as SvetlanaAction.Compound
        assertThat(ActionRiskPolicy.requiresConfirmation(a)).isTrue()
    }

    @Test
    fun `english dangerous labels`() {
        for (label in listOf("Pay", "Delete account", "Send", "Approve", "Withdraw", "Clear all", "Buy now")) {
            assertThat(ActionRiskPolicy.isDangerousLabel(label)).isTrue()
        }
        assertThat(ActionRiskPolicy.isDangerousLabel("Search")).isFalse()
        assertThat(ActionRiskPolicy.isDangerousLabel("Dialog settings")).isFalse()
        assertThat(ActionRiskPolicy.isSensitiveFieldLabel("Verification code")).isTrue()
    }

    @Test
    fun `chat questions are not commands`() {
        assertThat(CommandParser.parse("what's the weather today")).isNull()
        assertThat(CommandParser.parse("tell me a joke")).isNull()
        assertThat(CommandParser.parse("Svetlana")).isNull()
    }

    @Test
    fun `russian commands unchanged`() {
        val a = CommandParser.parse("Света, открой Telegram и нажми поиск") as SvetlanaAction.Compound
        assertThat((a.steps[1] as SvetlanaAction.Click).target).isEqualTo("telegram")
        assertThat(CommandParser.parse("позвони маме")).isInstanceOf(SvetlanaAction.MakeCall::class.java)
    }

    @Test
    fun `english confirmation shows exact parameters`() {
        assertThat(ConfirmationText.question(SvetlanaAction.MakeCall("Mom"), english = true))
            .isEqualTo("Confirm: open the dialer for \u201cMom\u201d?")
        val sms = ConfirmationText.question(SvetlanaAction.SendMessage("+15551234567", "Send 500"), english = true)
        assertThat(sms).contains("+15551234567")
        assertThat(sms).contains("Send 500")
        val secret = ConfirmationText.question(
            SvetlanaAction.TypeText("bank", "PIN", "1234"), secretField = true, english = true)
        assertThat(secret).doesNotContain("1234")
        // Русский по умолчанию не изменился.
        assertThat(ConfirmationText.question(SvetlanaAction.MakeCall("Мама")))
            .isEqualTo("Подтвердите: открыть набор номера «Мама»?")
    }
}
