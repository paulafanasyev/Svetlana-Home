package com.svetlana.home.bridge

import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Bridge protocol v2 — byte-for-byte the scheme of Svetlana 2.0
 * (src/services/security/BridgeCrypto.ts). The pairing code never travels over the network.
 *
 * key = PBKDF2-HMAC-SHA256(normalized code, salt, 100 000 iterations) → AES-256-GCM.
 * The random 16-byte salt is published in /health and changes with every new code.
 * Request body {"v":2,"ts","nonce","iv","ct"}, AAD "svetlana-bridge-v2\nreq\n<route>\n<ts>\n<nonce>".
 * Reply body {"v":2,"iv","ct"}, AAD "svetlana-bridge-v2\nres\n<route>\n<nonce>".
 * A sniffed request can't be read, forged or replayed, and replies are bound to the request,
 * so another device on the Wi-Fi can't impersonate the phone.
 */
object BridgeCrypto {
    const val VERSION = 2
    const val ITERATIONS = 100_000
    const val SALT_BYTES = 16
    const val IV_BYTES = 12
    const val NONCE_BYTES = 16
    private const val TAG_BITS = 128
    private const val LABEL = "svetlana-bridge-v2"

    private val random = SecureRandom()

    /** AES key from the pairing code and the phone's salt; null for an empty code. */
    fun deriveKey(code: String?, salt: ByteArray, iterations: Int = ITERATIONS): ByteArray? {
        val normalized = BridgeToken.normalize(code) ?: return null
        val spec = PBEKeySpec(normalized.toCharArray(), salt, iterations, 256)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    fun newSalt(): ByteArray = ByteArray(SALT_BYTES).also { random.nextBytes(it) }

    fun requestAad(route: String, ts: Long, nonce: String): ByteArray =
        "$LABEL\nreq\n$route\n$ts\n$nonce".toByteArray(Charsets.UTF_8)

    fun responseAad(route: String, nonce: String): ByteArray =
        "$LABEL\nres\n$route\n$nonce".toByteArray(Charsets.UTF_8)

    data class Sealed(val iv: String, val ct: String)

    fun seal(key: ByteArray, plaintext: String, aad: ByteArray, iv: ByteArray = ByteArray(IV_BYTES).also { random.nextBytes(it) }): Sealed {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(aad)
        val ct = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val enc = Base64.getEncoder()
        return Sealed(enc.encodeToString(iv), enc.encodeToString(ct))
    }

    /** Plaintext, or null if the key, AAD or ciphertext is wrong (wrong code, tampering). */
    fun open(key: ByteArray, ivB64: String, ctB64: String, aad: ByteArray): String? = try {
        val iv = decode(ivB64)
        val ct = decode(ctB64)
        if (iv == null || ct == null || iv.size != IV_BYTES || ct.size < TAG_BITS / 8) {
            null
        } else {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(aad)
            String(cipher.doFinal(ct), Charsets.UTF_8)
        }
    } catch (e: GeneralSecurityException) {
        null
    }

    fun decode(b64: String): ByteArray? = try {
        Base64.getDecoder().decode(b64)
    } catch (e: IllegalArgumentException) {
        null
    }

    fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
}

/**
 * Salt + derived key for the current pairing code. A new code gets a new random salt,
 * so keys never repeat across pairings. PBKDF2 runs once per code, not per request.
 */
class BridgeKeyring(
    private val code: () -> String?,
    private val iterations: Int = BridgeCrypto.ITERATIONS,
    private val newSalt: () -> ByteArray = BridgeCrypto::newSalt,
) {
    class Session(val code: String, val salt: String, val key: ByteArray)

    @Volatile
    private var session: Session? = null

    @Synchronized
    fun current(): Session? {
        val normalized = BridgeToken.normalize(code()) ?: return null
        session?.let { if (it.code == normalized) return it }
        val salt = newSalt()
        val key = BridgeCrypto.deriveKey(normalized, salt, iterations) ?: return null
        return Session(normalized, BridgeCrypto.encode(salt), key).also { session = it }
    }
}
