package com.svetlana.home.hands

/**
 * Когда можно снимать экран. По умолчанию — НЕЛЬЗЯ (default-deny):
 * - своё приложение, настройки и тестовый Mobile Harness — без вопросов;
 * - любое другое приложение — только один раз и только в течение минуты после того, как
 *   пользователь подтвердил скриншот ИМЕННО этого приложения (диалог ActionRouter);
 * - банки, платёжки, менеджеры паролей ([SensitiveApps]) — никогда, даже с согласием.
 */
object ScreenshotPolicy {
    private val TRUSTED = setOf(
        "com.android.settings",
        "com.mobile.harness", "com.mobileharness.app", "com.google.android.mobly", "io.appium.android.apk",
    )
    const val CONSENT_TTL_MS = 60_000L

    private var consentPackage: String? = null
    private var consentAt = 0L

    private fun norm(pkg: String?) = pkg?.trim()?.lowercase().orEmpty()

    /** Доверенное приложение: снимать можно без вопроса. */
    fun isTrusted(pkg: String?, ownPackage: String): Boolean {
        val p = norm(pkg)
        if (SensitiveApps.isSensitive(p)) return false
        return p == norm(ownPackage) || p in TRUSTED
    }

    /** Пользователь подтвердил один скриншот этого приложения. */
    @Synchronized
    fun allowOnce(pkg: String, now: Long = System.currentTimeMillis()) {
        consentPackage = norm(pkg)
        consentAt = now
    }

    /** Можно ли снять экран сейчас. Согласие одноразовое и привязано к пакету. */
    @Synchronized
    fun mayCapture(pkg: String?, ownPackage: String, now: Long = System.currentTimeMillis()): Boolean {
        val p = norm(pkg)
        if (SensitiveApps.isSensitive(p)) return false
        if (isTrusted(p, ownPackage)) return true
        val ok = consentPackage == p && now - consentAt in 0..CONSENT_TTL_MS
        consentPackage = null
        return ok
    }
}
