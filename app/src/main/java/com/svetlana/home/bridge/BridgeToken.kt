package com.svetlana.home.bridge

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Pairing code shared by the phone and the Svetlana 2.0 core. It is never sent
 * over the network: both sides derive an AES key from it ([BridgeCrypto]).
 * Format XXXX-XXXX-XXXX-XXXX (16 symbols, 80 bits), no ambiguous characters (0/O, 1/I).
 */
object BridgeToken {
    const val LENGTH = 16
    private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    fun generate(random: SecureRandom = SecureRandom()): String {
        val chars = CharArray(LENGTH) { ALPHABET[random.nextInt(ALPHABET.length)] }
        return String(chars).chunked(4).joinToString("-")
    }

    /** Upper-case, letters and digits only: "abcd-efgh" and "ABCDEFGH" are the same code. */
    fun normalize(raw: String?): String? =
        raw?.uppercase()?.filter { it in 'A'..'Z' || it in '0'..'9' }?.takeIf { it.isNotEmpty() }

    /** True for codes created by this version (older 8-symbol codes must be rotated). */
    fun isCurrentFormat(code: String?): Boolean = normalize(code)?.length == LENGTH

    /** Constant-time comparison of the expected and provided codes. */
    fun matches(expected: String, provided: String?): Boolean {
        val a = normalize(expected) ?: return false
        val b = normalize(provided) ?: return false
        return MessageDigest.isEqual(a.toByteArray(Charsets.US_ASCII), b.toByteArray(Charsets.US_ASCII))
    }
}
