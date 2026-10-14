package com.svetlana.home.core

/**
 * Решение «слушать ли сразу» для вызова ассистента (чистая логика, без Android).
 *
 * Экран ассистента экспортирован (иначе система не сможет его открыть), поэтому микрофон
 * включается сам только на настоящий вызов ASSIST и только если пользователь сам выбрал
 * Светлану цифровым помощником. Другое приложение, открывшее экран напрямую, увидит
 * только окно с орбом — без записи голоса.
 */
object AssistLaunch {

    const val ACTION_ASSIST = "android.intent.action.ASSIST"

    fun shouldListen(action: String?, holdsAssistantRole: Boolean): Boolean =
        action == ACTION_ASSIST && holdsAssistantRole

    /** Android 9 и ниже: Settings.Secure «assistant» = «пакет/компонент» выбранного помощника. */
    fun isAssistantSetting(value: String?, packageName: String): Boolean {
        if (value.isNullOrBlank() || packageName.isBlank()) return false
        return value.trim().substringBefore('/') == packageName
    }
}
