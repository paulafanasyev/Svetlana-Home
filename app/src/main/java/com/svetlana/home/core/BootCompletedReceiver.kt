package com.svetlana.home.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Обработка загрузки устройства: launcher должен работать после перезагрузки.
 *
 * После загрузки launcher готов к работе.
 *
 * Microphone foreground service не запускается из BOOT_COMPLETED: современные
 * версии Android ограничивают background-start и while-in-use microphone FGS.
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
