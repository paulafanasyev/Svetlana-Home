package com.svetlana.home.voice

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.svetlana.home.core.ServiceLocator

/**
 * Шаблон голоса владельца: только числа (24 средних + 24 разброса + порог),
 * в EncryptedSharedPreferences (ключ в Android Keystore). Аудио не хранится никогда.
 * Ошибка чтения/расшифровки = «не записан»: голосовые команды тогда не исполняются
 * (и такая ошибка не кэшируется: следующая попытка читает заново).
 *
 * Запись и удаление шаблона разрешены только в течение [AUTH_WINDOW_MS] после успешного
 * BiometricPrompt (OwnerIdentity.lastVerifiedAt): проверка живёт здесь, а не только в UI.
 */
object OwnerVoiceStore {
    private const val PREFS = "svetlana_owner_voice"
    private const val K_TEMPLATE = "template_v1"
    private const val K_LOCK = "lock_v1"
    const val AUTH_WINDOW_MS = 3 * 60_000L

    @Volatile
    private var cached: VoicePrint.Template? = null

    @Volatile
    private var loaded = false

    private fun prefs(context: Context): SharedPreferences {
        val app = context.applicationContext
        val key = MasterKey.Builder(app).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        return EncryptedSharedPreferences.create(
            app, PREFS, key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    /** Читает диск/Keystore при первом вызове: не вызывать с main thread. */
    fun load(context: Context): VoicePrint.Template? {
        if (loaded) return cached
        synchronized(this) {
            if (!loaded) {
                val result = runCatching { VoicePrint.decode(prefs(context).getString(K_TEMPLATE, null)) }
                if (result.isSuccess) {
                    cached = result.getOrNull()
                    loaded = true
                } else {
                    return null
                }
            }
            return cached
        }
    }

    fun isEnrolled(context: Context): Boolean = load(context) != null

    /** Без диска: известно ли уже, что голос записан (для main thread). */
    fun isEnrolledCached(): Boolean = loaded && cached != null

    /** Прогреть кэш в фоне, чтобы микрофонный путь не трогал Keystore на main thread. */
    fun prewarm(context: Context) {
        if (loaded) return
        val app = context.applicationContext
        Thread({ runCatching { load(app) } }, "svetlana-voice-prewarm").apply { isDaemon = true }.start()
    }

    private fun ownerRecentlyVerified(): Boolean = runCatching {
        val at = ServiceLocator.ownerIdentity.lastVerifiedAt()
        val age = System.currentTimeMillis() - at
        at > 0L && age in 0L..AUTH_WINDOW_MS
    }.getOrDefault(false)

    /** true: шаблон сохранён. false: нет свежей проверки владельца или ошибка записи. */
    fun save(context: Context, t: VoicePrint.Template): Boolean = synchronized(this) {
        if (!ownerRecentlyVerified()) return false
        val ok = runCatching {
            prefs(context).edit().putString(K_TEMPLATE, VoicePrint.encode(t)).remove(K_LOCK).commit()
        }.getOrDefault(false)
        if (ok) {
            cached = t
            loaded = true
            OwnerVoiceGate.lockout.reset()
        }
        ok
    }

    /** Удалить шаблон (только после свежей проверки владельца, либо [force] для «стереть всё»). */
    fun delete(context: Context, force: Boolean = false): Boolean = synchronized(this) {
        if (!force && !ownerRecentlyVerified()) return false
        val ok = runCatching { prefs(context).edit().clear().commit() }.getOrDefault(false)
        if (ok) {
            cached = null
            loaded = true
            OwnerVoiceGate.lockout.reset()
        }
        ok
    }

    /** Сохранённая пауза после чужих голосов: "fails|until". */
    fun loadLock(context: Context): Pair<Int, Long> = runCatching {
        val raw = prefs(context).getString(K_LOCK, null) ?: return@runCatching 0 to 0L
        val parts = raw.split('|')
        (parts.getOrNull(0)?.toIntOrNull() ?: 0) to (parts.getOrNull(1)?.toLongOrNull() ?: 0L)
    }.getOrDefault(0 to 0L)

    fun saveLock(context: Context, state: Pair<Int, Long>) {
        runCatching { prefs(context).edit().putString(K_LOCK, "${state.first}|${state.second}").commit() }
    }
}
