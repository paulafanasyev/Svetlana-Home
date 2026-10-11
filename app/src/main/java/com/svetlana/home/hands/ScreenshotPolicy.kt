package com.svetlana.home.hands

/**
 * Когда можно снимать экран. По умолчанию — НЕЛЬЗЯ (default-deny):
 * - своё приложение, настройки и тестовый Mobile Harness — без вопросов;
 * - любое другое приложение — только один раз, в течение минуты и только для того же
 *   окна (пакет + класс и размер корневого элемента), на которое пользователь согласился
 *   (диалог ActionRouter). Подменить окно того же приложения другим не получится;
 * - банки, платёжки, менеджеры паролей ([SensitiveApps]) — никогда, даже с согласием.
 */
object ScreenshotPolicy {
    private val TRUSTED = setOf(
        "com.android.settings",
        "com.mobile.harness", "com.mobileharness.app", "com.google.android.mobly", "io.appium.android.apk",
    )
    const val CONSENT_TTL_MS = 60_000L

    private var consentPackage: String? = null
    private var consentWindow: String? = null
    private var consentAt = 0L

    private fun norm(pkg: String?) = pkg?.trim()?.lowercase().orEmpty()

    /** Ключ окна: класс и границы корневого элемента текущего экрана. */
    fun windowKeyOf(tree: UiTree?): String? =
        tree?.root?.let { "${it.className}@${it.bounds.left},${it.bounds.top},${it.bounds.right},${it.bounds.bottom}" }

    /** Доверенное приложение: снимать можно без вопроса. */
    fun isTrusted(pkg: String?, ownPackage: String): Boolean {
        val p = norm(pkg)
        if (SensitiveApps.isSensitive(p)) return false
        return p == norm(ownPackage) || p in TRUSTED
    }

    /** Пользователь подтвердил один скриншот этого приложения (и этого окна, если известно). */
    @Synchronized
    fun allowOnce(pkg: String, window: String? = null, now: Long = System.currentTimeMillis()) {
        consentPackage = norm(pkg)
        consentWindow = window
        consentAt = now
    }

    /** Можно ли снять экран сейчас. Согласие одноразовое и привязано к пакету и окну. */
    @Synchronized
    fun mayCapture(pkg: String?, ownPackage: String, window: String? = null, now: Long = System.currentTimeMillis()): Boolean {
        val p = norm(pkg)
        if (SensitiveApps.isSensitive(p)) return false
        if (isTrusted(p, ownPackage)) return true
        val expectedWindow = consentWindow
        val ok = consentPackage == p && now - consentAt in 0..CONSENT_TTL_MS &&
            (expectedWindow == null || expectedWindow == window)
        consentPackage = null
        consentWindow = null
        return ok
    }
}
