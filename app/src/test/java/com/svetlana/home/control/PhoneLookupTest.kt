package com.svetlana.home.control

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PhoneLookupTest {

    private val book = listOf(
        ContactPhone(1, "Серый", "+7 912 000-11-22"),
        ContactPhone(2, "Маша", "8 (903) 111-22-33"),
        ContactPhone(2, "Маша", "+7 903 111 22 33"),
        ContactPhone(3, "Иван Петров", "+79990001111"),
        ContactPhone(3, "Иван Петров", "+79990002222", primary = true),
        ContactPhone(4, "Андрей", "+71110000001"),
        ContactPhone(4, "Андрей", "+71110000002"),
        ContactPhone(5, "Алекс", "+72220000001"),
        ContactPhone(6, "Алекс", "+72220000002"),
        ContactPhone(7, "Серыйкин", "+73330000001")
    )

    @Test
    fun `phone numbers are recognised`() {
        assertThat(PhoneLookup.isPhoneNumber("+7 912 000-11-22")).isTrue()
        assertThat(PhoneLookup.isPhoneNumber("112")).isTrue()
        assertThat(PhoneLookup.isPhoneNumber("Серый")).isFalse()
        assertThat(PhoneLookup.isPhoneNumber("12")).isFalse()
        assertThat(PhoneLookup.normalizeNumber("+7 (912) 000-11-22")).isEqualTo("+79120001122")
    }

    @Test
    fun `name in a case finds the contact`() {
        assertThat(PhoneLookup.pick("Серому", book)).isEqualTo(PhonePick.Number("Серый", "+79120001122"))
        assertThat(PhoneLookup.pick("серый", book)).isEqualTo(PhonePick.Number("Серый", "+79120001122"))
    }

    @Test
    fun `same number written twice is one number`() {
        val p = PhoneLookup.pick("Маше", book)
        assertThat(p).isInstanceOf(PhonePick.Number::class.java)
        assertThat((p as PhonePick.Number).name).isEqualTo("Маша")
    }

    @Test
    fun `primary number wins, otherwise the user picks`() {
        assertThat(PhoneLookup.pick("Ивану Петрову", book)).isEqualTo(PhonePick.Number("Иван Петров", "+79990002222"))
        assertThat(PhoneLookup.pick("Андрею", book)).isEqualTo(PhonePick.ManyNumbers("Андрей", 4))
    }

    @Test
    fun `two people with the name are ambiguous`() {
        assertThat(PhoneLookup.pick("Алекс", book)).isEqualTo(PhonePick.Ambiguous(listOf("Алекс")))
    }

    @Test
    fun `no partial or unknown matches`() {
        assertThat(PhoneLookup.pick("Петров", book)).isEqualTo(PhonePick.NotFound)
        assertThat(PhoneLookup.pick("Ольге", book)).isEqualTo(PhonePick.NotFound)
        assertThat(PhoneLookup.pick("", book)).isEqualTo(PhonePick.NotFound)
    }
}
