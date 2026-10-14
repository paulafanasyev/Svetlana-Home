package com.svetlana.home.control

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ContactMatchTest {

    @Test
    fun `case and yo do not matter`() {
        assertThat(ContactMatch.matches("Серёга", "серега")).isTrue()
        assertThat(ContactMatch.matches("СЕРЫЙ", "серый")).isTrue()
        assertThat(ContactMatch.matches("  Серый  ", "серый")).isTrue()
    }

    @Test
    fun `name with continuation matches`() {
        assertThat(ContactMatch.matches("Серый Иванов", "серый")).isTrue()
        assertThat(ContactMatch.matches("Серый 🔥", "серый")).isTrue()
    }

    @Test
    fun `other people do not match`() {
        assertThat(ContactMatch.matches("Серыйкин", "серый")).isFalse()
        assertThat(ContactMatch.matches("Иван Серый", "серый")).isFalse()
        assertThat(ContactMatch.matches("Серый", "")).isFalse()
        assertThat(ContactMatch.matches("", "серый")).isFalse()
    }

    @Test
    fun `literal name comes first`() {
        assertThat(ContactMatch.forms("Серому").first()).isEqualTo("серому")
        assertThat(ContactMatch.forms("Серый")).containsExactly("серый")
        assertThat(ContactMatch.forms("")).isEmpty()
        assertThat(ContactMatch.forms("Лу")).containsExactly("лу")
    }

    @Test
    fun `oblique cases give nominative forms`() {
        assertThat(ContactMatch.forms("Серому")).contains("серый")
        assertThat(ContactMatch.forms("Ивану")).contains("иван")
        assertThat(ContactMatch.forms("Маше")).contains("маша")
        assertThat(ContactMatch.forms("Тане")).contains("таня")
        assertThat(ContactMatch.forms("Ольге")).contains("ольга")
        assertThat(ContactMatch.forms("Ольгой")).contains("ольга")
        assertThat(ContactMatch.forms("Андрею")).contains("андрей")
        assertThat(ContactMatch.forms("Сергею")).contains("сергей")
        assertThat(ContactMatch.forms("Сергеем")).contains("сергей")
        assertThat(ContactMatch.forms("Иваном")).contains("иван")
        assertThat(ContactMatch.forms("Марии")).contains("мария")
        assertThat(ContactMatch.forms("Серёге")).contains("серега")
        assertThat(ContactMatch.forms("Ивану Петрову")).contains("иван петров")
    }

    @Test
    fun `forms still match only whole names`() {
        assertThat(ContactMatch.forms("Серому").any { ContactMatch.matches("Серый Иванов", it) }).isTrue()
        assertThat(ContactMatch.forms("Серому").any { ContactMatch.matches("Серыйкин", it) }).isFalse()
        assertThat(ContactMatch.forms("Ивану").any { ContactMatch.matches("Иванов", it) }).isFalse()
    }

    @Test
    fun `search query is the common start of all forms`() {
        assertThat(ContactMatch.searchQuery("Серому")).isEqualTo("сер")
        assertThat(ContactMatch.searchQuery("Серый")).isEqualTo("серый")
        assertThat(ContactMatch.searchQuery("Ивану")).isEqualTo("иван")
        assertThat(ContactMatch.searchQuery("Ивану Петрову")).isEqualTo("иван")
        assertThat(ContactMatch.searchQuery("")).isEqualTo("")
    }
}
