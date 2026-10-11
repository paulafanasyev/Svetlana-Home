package com.svetlana.home.voice

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Единственный вход для голосовых команд: выполняем только то, что сказал верифицированный владелец.
 *
 * 1. Голос не записан → голосовые команды не исполняются (как на ПК: NOT_ENROLLED).
 * 2. Android 13+: проверяется сама фраза команды (аудио из OwnerAudioTap).
 * 3. Иначе (старый Android или слишком тихо) → контрольная фраза «Светлана, это я».
 * 4. 5 чужих голосов подряд → пауза 5 минут; состояние переживает перезапуск приложения.
 * 5. Проверки идут строго по одной (Mutex), пауза перепроверяется перед «разрешено».
 *
 * Честно: отпечаток голоса отсекает чужие голоса, но не отличает живой голос от записи.
 * Поэтому всё, что меняет данные или пишет людям, подтверждается на экране (ActionRiskPolicy).
 */
object OwnerVoiceGate {
    const val NOT_ENROLLED = "Голос владельца ещё не записан. Откройте Настройки, раздел «Владелец», и запишите голос. До этого голосовые команды не выполняются, текстом можно пользоваться как обычно."
    const val CHALLENGE = "Подтвердите, что это вы: скажите «Светлана, это я»."
    const val CHALLENGE_MS = 3_000

    val lockout = OwnerVoiceLockout()
    private val mutex = Mutex()

    @Volatile
    private var lockRestored = false

    sealed class Decision {
        object Allowed : Decision()
        data class Denied(val message: String) : Decision()
    }

    fun lockedMessage(): String {
        val min = ((lockout.remainingMs() + 59_999L) / 60_000L).coerceAtLeast(1L)
        return "Слишком много попыток чужим голосом. Голосовое управление на паузе ещё $min мин."
    }

    /**
     * @param ownerAudio аудио самой команды (Android 13+), null: нужна контрольная фраза.
     * @param tts чем попросить контрольную фразу; без него проверка по фразе невозможна.
     */
    suspend fun authorize(context: Context, ownerAudio: FloatArray?, tts: SvetlanaTts?): Decision =
        withContext(Dispatchers.Default) {
            mutex.withLock {
                if (!lockRestored) {
                    val (fails, until) = OwnerVoiceStore.loadLock(context)
                    lockout.restore(fails, until)
                    lockRestored = true
                }
                if (lockout.isLocked()) return@withLock Decision.Denied(lockedMessage())
                val template = OwnerVoiceStore.load(context) ?: return@withLock Decision.Denied(NOT_ENROLLED)
                var print = ownerAudio?.let { VoicePrint.compute(it, PcmCapture.SAMPLE_RATE) }
                if (print == null) {
                    val audio = challenge(context, tts)
                    print = audio?.let { VoicePrint.compute(it, PcmCapture.SAMPLE_RATE).also { _ -> it.fill(0f) } }
                }
                val verdict = VoicePrint.verify(print, template)
                if (verdict.ok && !lockout.isLocked()) {
                    lockout.onSuccess()
                    OwnerVoiceStore.saveLock(context, lockout.snapshot())
                    Decision.Allowed
                } else {
                    // Тишина не считается попыткой перебора, чужой голос считается.
                    if (print != null && !verdict.ok) {
                        lockout.onFailure()
                        OwnerVoiceStore.saveLock(context, lockout.snapshot())
                    }
                    Decision.Denied(if (lockout.isLocked()) lockedMessage() else verdict.reason ?: VoicePrint.MSG_FOREIGN)
                }
            }
        }

    private suspend fun challenge(context: Context, tts: SvetlanaTts?): FloatArray? {
        if (tts == null || !tts.isAvailable) return null
        tts.speak(CHALLENGE)
        // Ждём, пока Светлана договорит, иначе запишем её же голос.
        withTimeoutOrNull(1_500L) { tts.speaking.first { it } }
        withTimeoutOrNull(8_000L) { tts.speaking.first { !it } }
        delay(250)
        return PcmCapture.record(context, CHALLENGE_MS)
    }
}
