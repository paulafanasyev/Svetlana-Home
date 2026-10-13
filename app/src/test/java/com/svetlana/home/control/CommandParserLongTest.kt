package com.svetlana.home.control

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Длинные цепочки команд с телефона: запятые, «найди контакт … и напиши ему …».
 * Отправка сообщения по-прежнему опасна и требует подтверждения.
 */
class CommandParserLongTest {

    @Test
    fun `open app find contact and write him`() {
        val a = CommandParser.parse("открой Whatsapp, найди контакт Серый и напиши ему привет")
            as SvetlanaAction.ComposeMessage
        assertThat(a.appTarget).isEqualTo("whatsapp")
        assertThat(a.contact).isEqualTo("серый")
        assertThat(a.text).isEqualTo("привет")
        assertThat(ActionRiskPolicy.requiresConfirmation(a)).isTrue()
    }

    @Test
    fun `voice variant without comma and with wake word`() {
        val a = CommandParser.parse("Света открой WhatsApp найди контакт Серый и напиши ему привет как дела")
            as SvetlanaAction.ComposeMessage
        assertThat(a.appTarget).isEqualTo("whatsapp")
        assertThat(a.contact).isEqualTo("серый")
        assertThat(a.text).isEqualTo("привет как дела")
    }

    @Test
    fun `find contact without app`() {
        val a = CommandParser.parse("найди контакт Серый и напиши ему как дела")
            as SvetlanaAction.ComposeMessage
        assertThat(a.appTarget).isEmpty()
        assertThat(a.contact).isEqualTo("серый")
        assertThat(a.text).isEqualTo("как дела")
    }

    @Test
    fun `english long command`() {
        val a = CommandParser.parse("open WhatsApp, find the contact John and message him hello there")
            as SvetlanaAction.ComposeMessage
        assertThat(a.appTarget).isEqualTo("whatsapp")
        assertThat(a.contact).isEqualTo("john")
        assertThat(a.text).isEqualTo("hello there")
        assertThat(ActionRiskPolicy.requiresConfirmation(a)).isTrue()
    }

    @Test
    fun `three steps with comma`() {
        val a = CommandParser.parse("открой Telegram, нажми поиск и введи Серый") as SvetlanaAction.Compound
        assertThat(a.steps).hasSize(3)
        assertThat(a.steps[0]).isEqualTo(SvetlanaAction.OpenApp("telegram"))
        val click = a.steps[1] as SvetlanaAction.Click
        assertThat(click.target).isEqualTo("telegram")
        val type = a.steps[2] as SvetlanaAction.TypeText
        assertThat(type.target).isEqualTo("telegram")
        assertThat(type.text.lowercase()).isEqualTo("серый")
    }

    @Test
    fun `english three steps`() {
        val a = CommandParser.parse("open Telegram, tap Search and type John") as SvetlanaAction.Compound
        assertThat(a.steps).hasSize(3)
        assertThat((a.steps[1] as SvetlanaAction.Click).target).isEqualTo("telegram")
        assertThat((a.steps[2] as SvetlanaAction.TypeText).target).isEqualTo("telegram")
    }

    @Test
    fun `old two step forms unchanged`() {
        val a = CommandParser.parse("открой Whatsapp и напиши контакту Серый привет как дела")
            as SvetlanaAction.ComposeMessage
        assertThat(a.appTarget).isEqualTo("whatsapp")
        assertThat(a.contact).isEqualTo("серый")
        val b = CommandParser.parse("открой Telegram и нажми поиск") as SvetlanaAction.Compound
        assertThat(b.steps).hasSize(2)
    }
}
