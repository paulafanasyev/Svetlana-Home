package com.svetlana.home.control

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CommandParserTest {

    @Test
    fun `wake word stripped from command`() {
        assertThat(CommandParser.stripWakeWord("света открой телеграм")).isEqualTo("открой телеграм")
        assertThat(CommandParser.stripWakeWord("Светлана, перейди в настройки")).isEqualTo("перейди в настройки")
        assertThat(CommandParser.stripWakeWord("светочка сделай скриншот")).isEqualTo("сделай скриншот")
        assertThat(CommandParser.stripWakeWord("просто фраза")).isEqualTo("просто фраза")
    }

    @Test
    fun `open app parses target`() {
        val action = CommandParser.parse("Света, открой Telegram")
        assertThat(action).isInstanceOf(SvetlanaAction.OpenApp::class.java)
        assertThat((action as SvetlanaAction.OpenApp).target).isEqualTo("telegram")
    }

    @Test
    fun `launch mobile harness parses`() {
        val action = CommandParser.parse("Света, запусти Mobile Harness")
        assertThat(action).isInstanceOf(SvetlanaAction.OpenApp::class.java)
        assertThat((action as SvetlanaAction.OpenApp).target).contains("harness")
    }

    @Test
    fun `open app for testing resolves to harness`() {
        val action = CommandParser.parse("Света, открой приложение для тестирования")
        assertThat((action as SvetlanaAction.OpenApp).target).isEqualTo("mobile harness")
    }

    @Test
    fun `switch to app parses`() {
        val action = CommandParser.parse("Света, переключись на Mobile Harness")
        assertThat((action as SvetlanaAction.OpenApp).target).contains("harness")
    }

    @Test
    fun `back home recents parse`() {
        assertThat(CommandParser.parse("Света, вернись назад")).isEqualTo(SvetlanaAction.PressBack)
        assertThat(CommandParser.parse("Света, домой")).isEqualTo(SvetlanaAction.PressHome)
        assertThat(CommandParser.parse("назад")).isEqualTo(SvetlanaAction.PressBack)
        assertThat(CommandParser.parse("покажи недавние")).isInstanceOf(SvetlanaAction.OpenRecents::class.java)
    }

    @Test
    fun `screenshot parses`() {
        assertThat(CommandParser.parse("Света, сделай скриншот"))
            .isInstanceOf(SvetlanaAction.TakeScreenshot::class.java)
        assertThat(CommandParser.parse("снимок экрана"))
            .isInstanceOf(SvetlanaAction.TakeScreenshot::class.java)
    }

    @Test
    fun `scroll parses direction`() {
        val down = CommandParser.parse("Света, пролистай вниз") as SvetlanaAction.Scroll
        assertThat(down.direction).isEqualTo("вниз")
        val up = CommandParser.parse("прокрути вверх") as SvetlanaAction.Scroll
        assertThat(up.direction).isEqualTo("вверх")
    }

    @Test
    fun `swipe parses direction`() {
        val left = CommandParser.parse("Света, свайпни влево") as SvetlanaAction.Swipe
        assertThat(left.direction).isEqualTo("влево")
    }

    @Test
    fun `click parses element`() {
        val action = CommandParser.parse("Света, нажми кнопку Отправить") as SvetlanaAction.Click
        assertThat(action.element).isEqualTo("Отправить")
        assertThat(action.target).isEqualTo("current")
    }

    @Test
    fun `type text parses value`() {
        val action = CommandParser.parse("Света, введи это значение") as SvetlanaAction.TypeText
        assertThat(action.text).isEqualTo("это значение")
        assertThat(action.element).isEqualTo("input")
    }

    @Test
    fun `read screen parses`() {
        assertThat(CommandParser.parse("Света, прочитай экран"))
            .isInstanceOf(SvetlanaAction.ReadScreen::class.java)
        assertThat(CommandParser.parse("что на экране"))
            .isInstanceOf(SvetlanaAction.ReadScreen::class.java)
    }

    @Test
    fun `find element parses`() {
        val action = CommandParser.parse("Света, найди Поиск") as SvetlanaAction.FindElement
        assertThat(action.element).isEqualTo("Поиск")
    }

    @Test
    fun `translate to vietnamese parses direction`() {
        val action = CommandParser.parse("Света, переведи на вьетнамский «Привет»") as SvetlanaAction.Translate
        assertThat(action.text).isEqualTo("Привет")
        assertThat(action.direction).isEqualTo("ru-vi")
    }

    @Test
    fun `translate to russian direction`() {
        val action = CommandParser.parse("переведи на русский xin chào") as SvetlanaAction.Translate
        assertThat(action.direction).isEqualTo("vi-ru")
    }

    @Test
    fun `call parses contact`() {
        val action = CommandParser.parse("Света, позвони маме") as SvetlanaAction.MakeCall
        assertThat(action.contact).isEqualTo("маме")
    }

    @Test
    fun `sms parses`() {
        val action = CommandParser.parse("Света, отправь сообщение маме")
        assertThat(action).isInstanceOf(SvetlanaAction.SendMessage::class.java)
    }

    @Test
    fun `share parses`() {
        assertThat(CommandParser.parse("поделись")).isInstanceOf(SvetlanaAction.Share::class.java)
    }

    @Test
    fun `non-command returns null`() {
        assertThat(CommandParser.parse("какая сегодня погода")).isNull()
        assertThat(CommandParser.parse("")).isNull()
        assertThat(CommandParser.parse("Света")).isNull()
    }
}
