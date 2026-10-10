package com.svetlana.home.bridge

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Pairing code that the Svetlana 2.0 core must send with every /api request.
 * Format XXXX-XXXX, no ambiguous characters (0/O, 1/I).
 */
object BridgeToken {
    private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    fun generate(random: SecureRandom = SecureRandom()): String {
        val chars = CharArray(8) { ALPHABET[random.nextInt(ALPHABET.length)] }
        return String(chars, 0, 4) + "-" + String(chars, 4, 4)
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
