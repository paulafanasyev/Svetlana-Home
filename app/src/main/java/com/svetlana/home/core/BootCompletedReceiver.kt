package com.svetlana.home.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Обработка загрузки устройства: launcher должен работать после перезагрузки.
 *
 * После загрузки запускается фоновый голосовой ассистент — это видимый
 * пользователю foreground-сервис с уведомлением и кнопкой «Стоп».
 * Запуск происходит только если пользователь включил wake word в настройках
 * и выдал разрешение на микрофон (проверяется внутри startIfEnabled).
 * Никаких скрытых сервисов: всё прозрачно и отключаемо.
 */
class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) {
            android.util.Log.i(TAG, "Устройство загружено. Светлана Home готова к работе.")
            // Microphone foreground services require a visible user-initiated start on modern Android.
            // Do not start the microphone service directly from BOOT_COMPLETED.
        }
    }

    companion object {
        private const val TAG = "SvetlanaBoot"
    }
}
