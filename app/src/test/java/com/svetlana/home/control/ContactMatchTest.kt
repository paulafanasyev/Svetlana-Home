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
}
