package com.svetlana.home.bridge

import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Bridge protocol v2: the pairing code never travels over the network.
 *
 * key = HKDF-SHA256(ikm = normalized pairing code, salt = "svetlana-bridge-v2",
 *                   info = "svetlana-bridge-v2/aes-256-gcm", 32 bytes)
 *
 * Every /api request body is {"v":2,"iv":b64(12 bytes),"ct":b64(ciphertext||tag)}
 * encrypted with AES-256-GCM, AAD = "METHOD\npath\nts\nnonce" (binds the body to the
 * route, the timestamp and the one-time nonce). The reply is encrypted the same way
 * with AAD = "resp\nnonce\nstatus", so a LAN observer sees neither contacts nor the
 * code, and cannot replay or splice requests. The same scheme is implemented with
 * WebCrypto in Svetlana-2.0 (src/services/BridgeCrypto.ts).
 */
object BridgeCrypto {
    const val VERSION = 2
    private const val SALT = "svetlana-bridge-v2"
    private const val INFO = "svetlana-bridge-v2/aes-256-gcm"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    private val random = SecureRandom()

    /** Derives the AES key from the pairing code; null for an empty code. */
    fun deriveKey(code: String): ByteArray? {
        val normalized = BridgeToken.normalize(code) ?: return null
        return hkdfSha256(
            ikm = normalized.toByteArray(Charsets.US_ASCII),
            salt = SALT.toByteArray(Charsets.UTF_8),
            info = INFO.toByteArray(Charsets.UTF_8),
            length = 32,
        )
    }

    fun requestAad(method: String, path: String, ts: Long, nonce: String): ByteArray =
        "${method.uppercase()}\n$path\n$ts\n$nonce".toByteArray(Charsets.UTF_8)

    fun responseAad(nonce: String, status: Int): ByteArray =
        "resp\n$nonce\n$status".toByteArray(Charsets.UTF_8)

    data class Sealed(val iv: String, val ct: String)

    fun seal(key: ByteArray, plaintext: String, aad: ByteArray): Sealed {
        val iv = ByteArray(IV_BYTES).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(aad)
        val ct = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val enc = Base64.getEncoder()
        return Sealed(enc.encodeToString(iv), enc.encodeToString(ct))
    }

    /** Returns the plaintext, or null if the key, AAD or ciphertext is wrong (tampering, wrong code). */
    fun open(key: ByteArray, ivB64: String, ctB64: String, aad: ByteArray): String? = try {
        val dec = Base64.getDecoder()
        val iv = dec.decode(ivB64)
        val ct = dec.decode(ctB64)
        if (iv.size != IV_BYTES || ct.size < TAG_BITS / 8) {
            null
        } else {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(aad)
            String(cipher.doFinal(ct), Charsets.UTF_8)
        }
    } catch (e: IllegalArgumentException) {
        null
    } catch (e: GeneralSecurityException) {
        null
    }

    /** RFC 5869 HKDF with HMAC-SHA256. */
    fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..255 * 32)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(if (salt.isEmpty()) ByteArray(32) else salt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        val out = ByteArray(length)
        var previous = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < length) {
            mac.update(previous)
            mac.update(info)
            mac.update(counter.toByte())
            previous = mac.doFinal()
            val n = minOf(previous.size, length - offset)
            System.arraycopy(previous, 0, out, offset, n)
            offset += n
            counter++
        }
        return out
    }
}
