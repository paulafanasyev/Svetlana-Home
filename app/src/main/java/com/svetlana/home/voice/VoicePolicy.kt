package com.svetlana.home.voice

/**
 * Откуда пришла команда.
 *  - TEXT — написана или произнесена после нажатия на микрофон в открытом приложении (человек держит телефон);
 *  - BACKGROUND_VOICE — слово пробуждения в фоне. Голос НЕ аутентифицирован: это может быть чужой
 *    человек рядом, запись, телевизор или голос самой Светланы.
 */
enum class CommandOrigin { TEXT, BACKGROUND_VOICE }

/**
 * Что разрешено неаутентифицированному голосу. На Android нет проверки голоса владельца, поэтому:
 *  - любое действие с телефоном из фона только предлагается и подтверждается в открытом приложении
 *    (для этого телефон надо разблокировать);
 *  - режим ИИ и настройки приватности голосом из фона не меняются;
 *  - личная память не подмешивается в запрос (чужой не услышит ваши данные в ответе).
 */
object VoicePolicy {
    fun mustConfirmInApp(origin: CommandOrigin): Boolean = origin == CommandOrigin.BACKGROUND_VOICE
    fun mayChangeSettings(origin: CommandOrigin): Boolean = origin == CommandOrigin.TEXT
    fun mayUsePersonalMemory(origin: CommandOrigin): Boolean = origin == CommandOrigin.TEXT

    const val SETTINGS_REFUSED = "Менять режим ИИ голосом в фоне нельзя. Откройте приложение и скажите или напишите это там."
    const val CONFIRM_IN_APP = "Команда из фона выполняется только после подтверждения в приложении."
    const val SYSTEM_REFUSED = "Это можно сделать только в открытом приложении."

    const val SETTINGS_REFUSED_EN = "The AI mode can't be changed by background voice. Open the app and say or type it there."
    const val CONFIRM_IN_APP_EN = "A background voice command runs only after you confirm it in the app."
    const val SYSTEM_REFUSED_EN = "This can only be done in the open app."

    fun settingsRefused(english: Boolean) = if (english) SETTINGS_REFUSED_EN else SETTINGS_REFUSED
    fun confirmInApp(english: Boolean) = if (english) CONFIRM_IN_APP_EN else CONFIRM_IN_APP
    fun systemRefused(english: Boolean) = if (english) SYSTEM_REFUSED_EN else SYSTEM_REFUSED
}
