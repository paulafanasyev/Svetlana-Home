package com.svetlana.home.bridge

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BridgeCryptoTest {
    private val salt = BridgeCrypto.decode("AAECAwQFBgcICQoLDA0ODw==")!!

    @Test
    fun matchesTheSvetlana20WebCryptoVectorByteForByte() {
        // Same vector as Svetlana-2.0 src/__tests__/bridge.test.ts ("matches the Kotlin test vector").
        val key = BridgeCrypto.deriveKey("abcd-efgh", salt)!!
        val nonce = BridgeCrypto.encode(ByteArray(16) { 7 })
        assertThat(nonce).isEqualTo("BwcHBwcHBwcHBwcHBwcHBw==")
        val sealed = BridgeCrypto.seal(
            key, "{\"params\":{}}", BridgeCrypto.requestAad("device/info", 1_760_000_000_000L, nonce), ByteArray(12) { 9 },
        )
        assertThat(sealed.iv).isEqualTo("CQkJCQkJCQkJCQkJ")
        assertThat(sealed.ct).isEqualTo("w/IG3LsfvEi6pZsEIe/f2V9TnyBoZPuJl/Tm8DY=")
    }

    @Test
    fun sealOpenRoundTripAndTamperDetection() {
        val key = BridgeCrypto.deriveKey("ABCD-EFGH-JKLM-NPQR", salt, 1_000)!!
        val aad = BridgeCrypto.responseAad("device/info", "n")
        val sealed = BridgeCrypto.seal(key, "привет", aad)
        assertThat(BridgeCrypto.open(key, sealed.iv, sealed.ct, aad)).isEqualTo("привет")
        assertThat(BridgeCrypto.open(key, sealed.iv, sealed.ct, BridgeCrypto.responseAad("contacts/list", "n"))).isNull()
        val other = BridgeCrypto.deriveKey("ZZZZ-EFGH-JKLM-NPQR", salt, 1_000)!!
        assertThat(BridgeCrypto.open(other, sealed.iv, sealed.ct, aad)).isNull()
        assertThat(BridgeCrypto.open(key, "bad", sealed.ct, aad)).isNull()
        assertThat(BridgeCrypto.open(key, sealed.iv, "%%%", aad)).isNull()
        assertThat(BridgeCrypto.seal(key, "x", aad).iv).isNotEqualTo(BridgeCrypto.seal(key, "x", aad).iv)
    }

    @Test
    fun keyringRotatesSaltWithTheCode() {
        var code = "ABCD-EFGH-JKLM-NPQR"
        val keyring = BridgeKeyring({ code }, iterations = 1_000)
        val first = keyring.current()!!
        assertThat(keyring.current()!!.salt).isEqualTo(first.salt)
        code = "ZZZZ-EFGH-JKLM-NPQR"
        val second = keyring.current()!!
        assertThat(second.salt).isNotEqualTo(first.salt)
        assertThat(second.key).isNotEqualTo(first.key)
        assertThat(BridgeCrypto.decode(second.salt)!!.size).isEqualTo(16)
        assertThat(BridgeKeyring({ null }).current()).isNull()
    }
}
