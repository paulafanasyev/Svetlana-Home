package com.svetlana.home.hands

/**
 * Когда можно снимать экран. По умолчанию — НЕЛЬЗЯ (default-deny):
 * - своё приложение, настройки и тестовый Mobile Harness — без вопросов;
 * - любое другое приложение — только один раз, в течение минуты и только для ТОГО ЖЕ
 *   экрана, на который пользователь согласился (диалог ActionRouter). Ключ окна ([windowKeyOf]) —
 *   класс и границы корня ПЛЮС отпечаток всего экрана (класс, id, границы, текст и описание
 *   каждого элемента в уже вычищенном виде). Другой экран того же приложения с тем же корнем
 *   (другая полноэкранная активность, другой чат) снят не будет: нужно новое согласие;
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

    /**
     * Ключ окна: класс и границы корневого элемента и отпечаток содержимого всего экрана
     * (тексты уже с вычищенными секретами). Любая смена экрана или его содержимого меняет ключ.
     */
    fun windowKeyOf(tree: UiTree?): String? {
        val root = tree?.root ?: return null
        var hash = 1125899906842597L
        fun mix(s: String) { hash = 31L * hash + s.hashCode() }
        fun mix(i: Int) { hash = 31L * hash + i }
        tree.nodes.forEach { n ->
            mix(n.className); mix(n.id); mix(n.text); mix(n.contentDescription)
            mix(n.bounds.left); mix(n.bounds.top); mix(n.bounds.right); mix(n.bounds.bottom)
            mix(if (n.isPassword) 1 else 0)
        }
        mix(tree.nodes.size)
        return "${root.className}@${root.bounds.left},${root.bounds.top},${root.bounds.right},${root.bounds.bottom}" +
            "#${java.lang.Long.toHexString(hash)}"
    }

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
