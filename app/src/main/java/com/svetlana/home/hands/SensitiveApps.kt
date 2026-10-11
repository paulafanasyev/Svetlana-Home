package com.svetlana.home.hands

/**
 * Приложения, где секреты часто рисуются вне Accessibility (Canvas, WebView, картинки):
 * банки, платёжные сервисы, менеджеры паролей, аутентификаторы, госуслуги.
 * Закрасить только поля там нельзя — поэтому скриншот таких экранов вообще не
 * делается (fail closed).
 */
object SensitiveApps {
    private val MARKERS = listOf(
        "bank", "sber", "tinkoff", "tbank", "vtb", "alfa", "raiffeisen", "gazprombank", "psbank",
        "sovcombank", "otpbank", "rosbank", "pochtabank", "mkb", "ozon.fintech", "yoomoney", "qiwi",
        "paypal", "revolut", "wise", "monzo", "wallet", "pay", "crypto", "binance", "metamask", "trustwallet",
        "authenticator", "authy", "1password", "bitwarden", "keepass", "lastpass", "dashlane", "nordpass",
        "enpass", "gosuslugi", "nalog", "mosru",
    )

    /** true — пакет похож на банк/платёжку/менеджер паролей. Пустой пакет считается опасным. */
    fun isSensitive(packageName: String?): Boolean {
        val p = packageName?.lowercase()?.trim().orEmpty()
        if (p.isEmpty()) return true
        return MARKERS.any { p.contains(it) }
    }
}
