package com.svetlana.home.voice

import android.content.Context
import android.os.Build
import android.os.SystemClock
import com.svetlana.home.core.ServiceLocator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Единственный вход для голосовых команд: выполняем только то, что сказал ЖИВОЙ верифицированный владелец.
 *
 * 1. Голос не записан → голосовые команды не исполняются (как на ПК: NOT_ENROLLED).
 * 2. Каждая фраза сверяется с отпечатком голоса (Android 13+: сама команда; иначе
 *    контрольная фраза «Светлана, это я»).
 * 3. Защита от записи (Android 13+): если нет доверенной сессии, Светлана просит
 *    произнести 3 случайные цифры. По одному звуку проверяем и цифры (распознавание), и голос.
 *    После этого 2 минуты (с продлением, не больше 10) цифры не спрашиваются.
 * 4. Где защита от записи невозможна (старый Android), решение Allowed(live = false):
 *    VoiceAgent любое действие с телефоном отправляет на подтверждение на экране.
 * 5. 5 неудач подряд (чужой голос или неверные цифры) → пауза 5 минут, переживает перезапуск.
 * 6. Проверки строго по одной (Mutex); решение «разрешено» принимается под замком
 *    хранилища и только если шаблон не менялся за время проверки.
 * Звонки, сообщения, нажатия и ввод текста всё равно подтверждаются на экране (ActionRiskPolicy).
 */
object OwnerVoiceGate {
    const val NOT_ENROLLED = "Голос владельца ещё не записан. Откройте Настройки, раздел «Владелец», и запишите голос. До этого голосовые команды не выполняются, текстом можно пользоваться как обычно."
    const val CHALLENGE = "Подтвердите, что это вы: скажите «Светлана, это я»."
    const val CHANGED = "Голос владельца только что изменили. Повторите команду."
    const val DIGITS_WRONG = "Цифры не совпали. Команда не выполнена."
    const val DIGITS_UNHEARD = "Не расслышала цифры. Команда не выполнена."
    const val CHALLENGE_MS = 3_000

    val lockout = OwnerVoiceLockout()
    private val trust = VoiceTrust()
    private val mutex = Mutex()

    /** Идёт проверка цифрами: фоновое слово пробуждения в это время не слушает. */
    @Volatile
    var challengeActive = false
        private set

    @Volatile
    private var lockRestored = false

    sealed class Decision {
        /** [live]: защита от записи пройдена (цифры или доверенная сессия). */
        data class Allowed(val live: Boolean) : Decision()
        data class Denied(val message: String) : Decision()
    }

    private sealed class Live {
        object Ok : Live()
        object Unavailable : Live()
        data class Failed(val message: String, val countsAsFailure: Boolean) : Live()
    }

    fun lockedMessage(): String {
        val min = ((lockout.remainingMs() + 59_999L) / 60_000L).coerceAtLeast(1L)
        return "Слишком много неудачных попыток. Голосовое управление на паузе ещё $min мин."
    }

    /** Закрыть доверенную сессию (например, при остановке голосового ассистента). */
    fun endTrust() = trust.end()

    private fun registerFailure(context: Context) {
        lockout.onFailure()
        trust.end()
        OwnerVoiceStore.saveLock(context, lockout.snapshot())
    }

    /**
     * @param ownerAudio аудио самой команды (Android 13+), null: нужна контрольная фраза.
     * @param tts чем попросить контрольную фразу или цифры; без него такие проверки невозможны.
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
                val (template, gen) = OwnerVoiceStore.loadWithGeneration(context)
                if (template == null) return@withLock Decision.Denied(NOT_ENROLLED)
                var print = ownerAudio?.let { VoicePrint.compute(it, PcmCapture.SAMPLE_RATE) }
                if (print == null) {
                    val audio = challenge(context, tts)
                    print = audio?.let { VoicePrint.compute(it, PcmCapture.SAMPLE_RATE).also { _ -> it.fill(0f) } }
                }
                val verdict = VoicePrint.verify(print, template)
                val passed = synchronized(OwnerVoiceStore) {
                    OwnerVoiceStore.generation == gen && verdict.ok && !lockout.isLocked()
                }
                if (!passed) {
                    if (OwnerVoiceStore.generation != gen) return@withLock Decision.Denied(CHANGED)
                    if (print != null && !verdict.ok) registerFailure(context)
                    return@withLock Decision.Denied(
                        if (lockout.isLocked()) lockedMessage() else verdict.reason ?: VoicePrint.MSG_FOREIGN
                    )
                }

                // Голос владельца. Теперь защита от записи.
                if (trust.isValid(gen, SystemClock.elapsedRealtime())) {
                    trust.touch(SystemClock.elapsedRealtime())
                    lockout.onSuccess()
                    OwnerVoiceStore.saveLock(context, lockout.snapshot())
                    return@withLock Decision.Allowed(live = true)
                }
                when (val live = liveness(tts, template)) {
                    Live.Ok -> synchronized(OwnerVoiceStore) {
                        if (OwnerVoiceStore.generation != gen || lockout.isLocked()) {
                            Decision.Denied(CHANGED)
                        } else {
                            lockout.onSuccess()
                            OwnerVoiceStore.saveLock(context, lockout.snapshot())
                            trust.start(gen, SystemClock.elapsedRealtime())
                            Decision.Allowed(live = true)
                        }
                    }
                    Live.Unavailable -> synchronized(OwnerVoiceStore) {
                        if (OwnerVoiceStore.generation != gen) Decision.Denied(CHANGED)
                        else {
                            lockout.onSuccess()
                            OwnerVoiceStore.saveLock(context, lockout.snapshot())
                            Decision.Allowed(live = false)
                        }
                    }
                    is Live.Failed -> {
                        if (live.countsAsFailure) registerFailure(context)
                        Decision.Denied(if (lockout.isLocked()) lockedMessage() else live.message)
                    }
                }
            }
        }

    /**
     * Случайные цифры: Android 13+, распознаватель с нашим микрофоном (OwnerAudioTap), есть TTS.
     * Текст и отпечаток считаются по ОДНОМУ звуку, подсунуть цифры чужим голосом нельзя.
     */
    private suspend fun liveness(tts: SvetlanaTts?, template: VoicePrint.Template): Live {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return Live.Unavailable
        if (tts == null || !tts.isAvailable) return Live.Unavailable
        val recognizer = runCatching { ServiceLocator.speechRecognizer }.getOrNull() ?: return Live.Unavailable
        if (!recognizer.passiveOwnerCheck || !recognizer.isAvailable) return Live.Unavailable
        val ch = LivenessChallenge.generate()
        challengeActive = true
        try {
            // Распознаватель один на всё приложение: ждём, пока фоновое окно wake word его отпустит.
            val wake = runCatching { ServiceLocator.wakeWord }.getOrNull()
            withTimeoutOrNull(7_000L) { while (wake?.windowActive == true) delay(100) }
            if (wake?.windowActive == true) return Live.Failed(DIGITS_UNHEARD, countsAsFailure = false)
            tts.speak("Для подтверждения скажите цифры: ${ch.spoken}.")
            waitTts(tts)
            recognizer.finishSession()
            delay(150)
            recognizer.startListening()
            var result = recognizer.awaitResult(7_000L)
            if (result == null) {
                recognizer.stopListening()
                result = recognizer.awaitResult(1_500L)
            }
            recognizer.finishSession()
            if (result !is SvetlanaSpeechRecognizer.SttResult.Success) {
                return Live.Failed(DIGITS_UNHEARD, countsAsFailure = false)
            }
            val audio = result.ownerAudio ?: return Live.Unavailable
            val print = VoicePrint.compute(audio, PcmCapture.SAMPLE_RATE)
            audio.fill(0f)
            val voice = VoicePrint.verify(print, template)
            val digits = LivenessChallenge.matches(result.text, ch)
            return when {
                print == null -> Live.Failed(DIGITS_UNHEARD, countsAsFailure = false)
                !voice.ok -> Live.Failed(voice.reason ?: VoicePrint.MSG_FOREIGN, countsAsFailure = true)
                !digits -> Live.Failed(DIGITS_WRONG, countsAsFailure = true)
                else -> Live.Ok
            }
        } finally {
            challengeActive = false
        }
    }

    private suspend fun waitTts(tts: SvetlanaTts) {
        // Ждём, пока Светлана договорит, иначе запишем её же голос.
        withTimeoutOrNull(1_500L) { tts.speaking.first { it } }
        withTimeoutOrNull(10_000L) { tts.speaking.first { !it } }
        delay(250)
    }

    private suspend fun challenge(context: Context, tts: SvetlanaTts?): FloatArray? {
        if (tts == null || !tts.isAvailable) return null
        tts.speak(CHALLENGE)
        waitTts(tts)
        return PcmCapture.record(context, CHALLENGE_MS)
    }
}
