package com.svetlana.home.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Обработка загрузки устройства: launcher должен работать после перезагрузки.
 * Здесь нет автозапуска скрытых сервисов — только лог факта загрузки для диагностики.
 */
class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) {
            val pm = context.getSystemService(Context.POWER_SERVICE)
            android.util.Log.i(TAG, "Устройство загружено. Светлана Home готова к работе.")
        }
    }

    companion object {
        private const val TAG = "SvetlanaBoot"
    }
}
