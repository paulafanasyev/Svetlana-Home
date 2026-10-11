package com.svetlana.home.bridge

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Pairing code for the Svetlana 2.0 core. Format XXXX-XXXX-XXXX (12 symbols of 32 ≈ 60 bits),
 * no ambiguous characters (0/O, 1/I). With PBKDF2 (100 000 iterations) an offline guess of the code
 * from a sniffed request is out of reach; online guessing is rate limited.
 */
object BridgeToken {
    private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    const val LENGTH = 12

    fun generate(random: SecureRandom = SecureRandom()): String {
        val chars = CharArray(LENGTH) { ALPHABET[random.nextInt(ALPHABET.length)] }
        return String(chars).chunked(4).joinToString("-")
    }

    /** Upper-case, letters and digits only: "abcd-efgh" and "ABCDEFGH" are the same code. */
    fun normalize(raw: String?): String? =
        raw?.uppercase()?.filter { it.isLetterOrDigit() }?.takeIf { it.isNotEmpty() }

    /** Constant-time comparison of the expected and provided codes. */
    fun matches(expected: String, provided: String?): Boolean {
        val a = normalize(expected) ?: return false
        val b = normalize(provided) ?: return false
        return MessageDigest.isEqual(a.toByteArray(Charsets.US_ASCII), b.toByteArray(Charsets.US_ASCII))
    }
}
