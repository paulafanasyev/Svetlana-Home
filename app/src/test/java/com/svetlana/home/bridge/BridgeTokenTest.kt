package com.svetlana.home.bridge

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BridgeTokenTest {
    @Test
    fun generatedCodeHasReadableFormat() {
        val code = BridgeToken.generate()
        assertThat(code).matches("^[A-HJ-NP-Z2-9]{4}-[A-HJ-NP-Z2-9]{4}$")
    }

    @Test
    fun codesAreRandom() {
        val codes = (1..50).map { BridgeToken.generate() }.toSet()
        assertThat(codes.size).isGreaterThan(45)
    }

    @Test
    fun matchingIgnoresCaseAndDashes() {
        assertThat(BridgeToken.matches("ABCD-EFGH", "abcd-efgh")).isTrue()
        assertThat(BridgeToken.matches("ABCD-EFGH", "ABCDEFGH")).isTrue()
        assertThat(BridgeToken.matches("ABCD-EFGH", "ABCD-EFGX")).isFalse()
        assertThat(BridgeToken.matches("ABCD-EFGH", null)).isFalse()
        assertThat(BridgeToken.matches("ABCD-EFGH", "")).isFalse()
    }
}
