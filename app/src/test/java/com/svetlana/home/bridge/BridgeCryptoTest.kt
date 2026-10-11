package com.svetlana.home.bridge

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BridgeCryptoTest {
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    @Test
    fun keyDerivationMatchesWebCryptoVector() {
        // Same vector is asserted in Svetlana-2.0 (WebCrypto HKDF).
        val key = BridgeCrypto.deriveKey("abcd-efgh-jklm-npqr")!!
        assertThat(hex(key)).isEqualTo("b020632a7e01826f00f340319aafc7c6e31dcb8a1956da6a85b71ad89eda1266")
    }

    @Test
    fun opensCiphertextProducedByWebCrypto() {
        val key = BridgeCrypto.deriveKey("ABCD-EFGH-JKLM-NPQR")!!
        val aad = BridgeCrypto.requestAad("POST", "/api/device/info", 1_700_000_000_000L, "nonce-nonce-nonce1")
        val plain = BridgeCrypto.open(key, "AAAAAAAAAAAAAAAA", "j5nn3cVa8zbeCneUVkaeJ0S7N6SQKOJdvKT14iM=", aad)
        assertThat(plain).isEqualTo("{\"params\":{}}")
    }

    @Test
    fun sealOpenRoundTripAndTamperDetection() {
        val key = BridgeCrypto.deriveKey("ABCD-EFGH-JKLM-NPQR")!!
        val aad = BridgeCrypto.responseAad("n", 200)
        val sealed = BridgeCrypto.seal(key, "привет", aad)
        assertThat(BridgeCrypto.open(key, sealed.iv, sealed.ct, aad)).isEqualTo("привет")
        assertThat(BridgeCrypto.open(key, sealed.iv, sealed.ct, BridgeCrypto.responseAad("n", 500))).isNull()
        val other = BridgeCrypto.deriveKey("ZZZZ-EFGH-JKLM-NPQR")!!
        assertThat(BridgeCrypto.open(other, sealed.iv, sealed.ct, aad)).isNull()
        assertThat(BridgeCrypto.open(key, "bad", sealed.ct, aad)).isNull()
        assertThat(BridgeCrypto.open(key, sealed.iv, "%%%", aad)).isNull()
        assertThat(BridgeCrypto.seal(key, "x", aad).iv).isNotEqualTo(BridgeCrypto.seal(key, "x", aad).iv)
    }

    @Test
    fun hkdfMatchesRfc5869TestCase1() {
        val ikm = ByteArray(22) { 0x0b }
        val salt = ByteArray(13) { it.toByte() }
        val info = ByteArray(10) { (0xf0 + it).toByte() }
        val okm = BridgeCrypto.hkdfSha256(ikm, salt, info, 42)
        assertThat(hex(okm)).isEqualTo(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
        )
    }
}
