package com.svetlana.home.bridge

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BridgeTokenTest {
    @Test
    fun generatedCodeHas16ReadableSymbols() {
        val code = BridgeToken.generate()
        assertThat(code).matches("^[A-HJ-NP-Z2-9]{4}(-[A-HJ-NP-Z2-9]{4}){3}$")
        assertThat(BridgeToken.isCurrentFormat(code)).isTrue()
        assertThat(BridgeToken.isCurrentFormat("ABCD-EFGH")).isFalse()
    }

    @Test
    fun codesAreRandom() {
        val codes = (1..200).map { BridgeToken.generate() }.toSet()
        assertThat(codes.size).isEqualTo(200)
    }

    @Test
    fun matchingIgnoresCaseAndDashes() {
        assertThat(BridgeToken.matches("ABCD-EFGH", "abcd-efgh")).isTrue()
        assertThat(BridgeToken.matches("ABCD-EFGH", "ABCDEFGH")).isTrue()
        assertThat(BridgeToken.matches("ABCD-EFGH", "ABCD-EFGX")).isFalse()
        assertThat(BridgeToken.matches("ABCD-EFGH", null)).isFalse()
        assertThat(BridgeToken.matches("ABCD-EFGH", "")).isFalse()
    }

    @Test
    fun normalizationKeepsOnlyAsciiLettersAndDigits() {
        assertThat(BridgeToken.normalize(" ab-cd 23 ")).isEqualTo("ABCD23")
        assertThat(BridgeToken.normalize("АБВ")).isNull()
    }
}
