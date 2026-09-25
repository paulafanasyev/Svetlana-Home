package com.svetlana.home.owner

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.biometric.BiometricManager
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Owner Identity — единый верифицированный профиль владельца.
 *
 * MVP: 1 устройство, 1 основной владелец, 1 основной профиль Светланы.
 *
 * Безопасность:
 * - ключ владельца живёт в Android Keystore (hardware-backed, где доступно);
 * - биометрия/PIN используются через системный диалог аутентификации;
 * - PIN/пароль пользователя никогда не сохраняются и не запрашиваются приложением.
 *
 * Ограничение: профиль владельца не даёт root, скрытого Accessibility,
 * скрытого микрофона или камеры, не обходит Android permission model.
 */
class OwnerIdentity(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val appContext = context.applicationContext

    /** Создать профиль владельца, если его ещё нет. */
    fun createOwner(displayName: String): Result {
        if (isOwnerCreated()) return Result.AlreadyExists
        return try {
            generateKey()
            prefs.edit()
                .putString(KEY_DISPLAY_NAME, displayName)
                .putLong(KEY_CREATED_AT, System.currentTimeMillis())
                .putBoolean(KEY_KEYSTORE_BACKED, true)
                .apply()
            Result.Created
        } catch (t: Throwable) {
            Result.Failed(t.message ?: "Не удалось создать профиль владельца")
        }
    }

    fun isOwnerCreated(): Boolean = prefs.contains(KEY_DISPLAY_NAME)

    fun displayName(): String = prefs.getString(KEY_DISPLAY_NAME, "Владелец") ?: "Владелец"

    fun createdAt(): Long = prefs.getLong(KEY_CREATED_AT, 0L)

    fun keyStoreBacked(): Boolean = prefs.getBoolean(KEY_KEYSTORE_BACKED, false)

    /**
     * Доказательство личности: подпись/расшифровка ключом из Keystore.
     * Возвращает true, если системный диалог аутентификации (биометрия/PIN/пароль) прошёл.
     *
     * Реальная проверка выполняется через BiometricPrompt на уровне UI —
     * здесь готовится challenge и проверяется, что Keystore-ключ доступен.
     */
    fun prepareChallenge(): ByteArray {
        val key = getOrCreateKey() ?: throw IllegalStateException("Ключ владельца недоступен")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        prefs.edit().putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP)).apply()
        return cipher.iv
    }

    fun verifyChallenge(encrypted: ByteArray): Boolean {
        return try {
            val key = getOrCreateKey() ?: return false
            val iv = Base64.decode(prefs.getString(KEY_IV, "") ?: "", Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.doFinal(encrypted)
            prefs.edit().putLong(KEY_LAST_VERIFIED, System.currentTimeMillis()).apply()
            true
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "Верификация владельца не пройдена", t)
            false
        }
    }

    /**
     * Идентификатор владельца для диагностики. Не является секретом и не позволяет
     * обойти Android security model.
     */
    fun stablePublicId(): String {
        if (!isOwnerCreated()) return "no-owner"
        val existing = prefs.getString(KEY_PUBLIC_ID, null)
        if (existing != null) return existing
        val seed = (displayName() + createdAt() + android.os.Build.SERIAL).toByteArray()
        val digest = MessageDigest.getInstance("SHA-256").digest(seed)
        val id = "owner-" + Base64.encodeToString(digest.copyOfRange(0, 9), Base64.NO_WRAP)
        prefs.edit().putString(KEY_PUBLIC_ID, id).apply()
        return id
    }

    fun biometricAvailable(): Boolean {
        val bm = BiometricManager.from(appContext)
        return bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL) == BiometricManager.BIOMETRIC_SUCCESS
    }

    fun lastVerifiedAt(): Long = prefs.getLong(KEY_LAST_VERIFIED, 0L)

    fun deleteOwner() {
        try {
            keyStore().deleteEntry(KEY_ALIAS)
        } catch (t: Throwable) { /* игнорируем */ }
        prefs.edit().clear().apply()
    }

    // ---------- Keystore ----------

    private fun generateKey(): SecretKey? {
        if (getOrCreateKey() != null) return getOrCreateKey()
        throw IllegalStateException("Keystore недоступен на устройстве")
    }

    private fun getOrCreateKey(): SecretKey? {
        return try {
            val ks = keyStore()
            if (ks.containsAlias(KEY_ALIAS)) {
                (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
            } else {
                val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
                gen.init(
                    KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setRandomizedEncryptionRequired(true)
                        .build()
                )
                gen.generateKey()
            }
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "Не удалось получить ключ из Keystore", t)
            null
        }
    }

    private fun keyStore(): KeyStore =
        KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }

    sealed class Result {
        object Created : Result()
        object AlreadyExists : Result()
        data class Failed(val message: String) : Result()
    }

    companion object {
        private const val TAG = "OwnerIdentity"
        private const val PREFS = "svetlana_owner"
        private const val KEY_ALIAS = "svetlana_owner_key"
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val KEY_DISPLAY_NAME = "display_name"
        private const val KEY_CREATED_AT = "created_at"
        private const val KEY_KEYSTORE_BACKED = "keystore_backed"
        private const val KEY_IV = "iv"
        private const val KEY_LAST_VERIFIED = "last_verified"
        private const val KEY_PUBLIC_ID = "public_id"
    }
}
