package com.svetlana.home.owner

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import javax.crypto.Cipher

/**
 * Аудит §19: настоящая биометрическая аутентификация владельца.
 *
 * Раньше ключ Keystore существовал, но НЕ требовал аутентификации
 * пользователя — поэтому «owner verified» был просто фактом наличия ключа.
 * Это не BiometricPrompt + user-authentication-required key.
 *
 * Теперь:
 *   1. Ключ владельца генерируется с setUserAuthenticationRequired(true) —
 *      им нельзя пользоваться без разблокировки устройства/биометрии.
 *   2. BiometricPrompt запускается с CryptoObject, привязанным к Cipher,
 *      инициализированному этим ключом. Android разблокирует ключ только
 *      после успешной аутентификации.
 *   3. Успешный колбэк означает: пользователь действительно прошёл
 *      системную аутентификацию — это и есть OWNER VERIFIED.
 *
 * Пароль/PIN Светлана сама не хранит и не запрашивает.
 *
 * Ограничение: ни один статус владельца не обходит Android security model.
 */
class BiometricAuth(private val context: Context) {

    /**
     * Доступна ли системная аутентификация (биометрия ИЛИ PIN/пароль/паттерн).
     * DEVICE_CREDENTIAL покрывает случай, когда биометрии нет на устройстве.
     */
    fun canAuthenticate(): Boolean {
        val bm = BiometricManager.from(context)
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        return bm.canAuthenticate(authenticators) == BiometricManager.BIOMETRIC_SUCCESS
    }

    /**
     * Запускает системную аутентификацию с привязкой к Keystore-ключу.
     *
     * @param activity FragmentActivity — требуется BiometricPrompt API.
     * @param title заголовок диалога.
     * @param onResult вызывается с true, если аутентификация прошла и ключ
     *                 разблокирован (OWNER VERIFIED), false — иначе.
     */
    fun authenticate(
        activity: FragmentActivity,
        title: String,
        onResult: (verified: Boolean, message: String?) -> Unit
    ) {
        if (!canAuthenticate()) {
            onResult(false, "Системная аутентификация недоступна на этом устройстве")
            return
        }

        val ownerIdentity = com.svetlana.home.core.ServiceLocator.ownerIdentity
        // Подготавливаем Cipher, инициализированный auth-bound ключом.
        // BiometricPrompt разблокирует ключ только после аутентификации.
        val cipher = ownerIdentity.prepareAuthCipher()
        if (cipher == null) {
            onResult(false, "Не удалось подготовить ключ владельца")
            return
        }

        val executor = androidx.core.content.ContextCompat.getMainExecutor(context)
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                onResult(false, errString.toString())
            }

            override fun onAuthenticationFailed() {
                // Этот колбэк вызывается на каждой неудачной попытке —
                // не завершаем поток, даём пользователю попробовать снова.
            }

            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                // Ключ разблокирован системой — это настоящая верификация.
                val cryptoObject = result.cryptoObject
                if (cryptoObject?.cipher != null) {
                    ownerIdentity.recordSuccessfulAuth()
                    onResult(true, null)
                } else {
                    onResult(false, "Аутентификация прошла, но ключ не разблокирован")
                }
            }
        }

        val prompt = BiometricPrompt(activity, executor, callback)
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle("Светлана использует этот ключ для защиты настроек")
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            .build()

        prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
    }
}
