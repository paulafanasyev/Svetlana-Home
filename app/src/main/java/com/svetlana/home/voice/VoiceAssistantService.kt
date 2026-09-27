package com.svetlana.home.voice

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.svetlana.home.R
import com.svetlana.home.core.ServiceLocator
import com.svetlana.home.memory.HistoryCategory
import com.svetlana.home.ui.launcher.HomeActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * VoiceAssistantService — фоновый голосовой AI-агент.
 *
 * Foreground-сервис с типом MICROPHONE: слушает слово пробуждения, даже
 * когда приложение свёрнуто или экран выключен, и исполняет команды через
 * VoiceAgent — тот же самый движок диалога, что и на главном экране.
 *
 * Требование: «ИИ-агент должен уметь работать по голосу и в бэкграунде».
 *
 * В этом сервисе нет скрытого доступа к микрофону: foreground-сервис с
 * типом microphone виден пользователю через постоянное уведомление, и
 * пользователь сам включает wake word в настройках. Никаких обходов
 * Android security model.
 */
class VoiceAssistantService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundNotification()
        when (intent?.action) {
            ACTION_STOP -> {
                Log.i(TAG, "Остановлен пользователем")
                ServiceLocator.wakeWord.stop()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                // Цикл прослушивания слова пробуждения живёт в foreground-
                // сервисе, поэтому он работает и в фоне, и при выключенном
                // экране. HomeActivity его не запускает — иначе команды
                // выполнялись бы дважды.
                ServiceLocator.wakeWord.start()
                startCommandLoop()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        ServiceLocator.wakeWord.stop()
        jobActive = false
    }

    /**
     * Подписка на обнаруженные команды. Сам цикл прослушивания запущен
     * в [onStartCommand] — здесь только потребитель.
     */
    private fun startCommandLoop() {
        if (jobActive) return
        jobActive = true
        scope.launch {
            try {
                ServiceLocator.wakeWord.detection.collect { command ->
                    if (command.isNullOrBlank()) return@collect
                    ServiceLocator.wakeWord.consumeDetection()
                    ServiceLocator.historyManager.record(
                        HistoryCategory.COMMANDS, "Голосовая команда: ${command.take(120)}")
                    handleCommand(command)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Цикл команд прерван", t)
            } finally {
                jobActive = false
            }
        }
    }

    /**
     * Исполнить команду через единое ядро диалога.
     * Работает одинаково на экране и в фоне.
     */
    private suspend fun handleCommand(command: String) {
        val outcome = ServiceLocator.voiceAgent.handle(
            text = command,
            speak = true,
            tts = ServiceLocator.tts
        )
        when (outcome) {
            is VoiceAgent.Outcome.Replied ->
                Log.i(TAG, "Команда выполнена: ${outcome.text.take(80)}")
            is VoiceAgent.Outcome.Confirmation -> {
                // ТЗ §58: опасное действие нельзя выполнить в фоне
                // автоматически. Светлана сообщает, что нужно подтверждение,
                // и открывает приложение, где пользователь подтверждает явно.
                ServiceLocator.tts.speak(getString(R.string.voice_agent_confirm_open))
                bringAppToForeground()
            }
        }
    }

    private fun bringAppToForeground() {
        try {
            val intent = Intent(this, HomeActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(intent)
        } catch (t: Throwable) {
            Log.w(TAG, "Не удалось открыть приложение", t)
        }
    }

    private fun startForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(CHANNEL_ID, getString(R.string.voice_agent_channel_name),
                NotificationManager.IMPORTANCE_LOW).apply {
                description = getString(R.string.voice_agent_channel_desc)
            }
            nm.createNotificationChannel(channel)
        }
        val stopIntent = Intent(this, VoiceAssistantService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPi = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.voice_agent_notification_title))
            .setContentText(getString(R.string.voice_agent_notification_text))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(R.drawable.ic_launcher_foreground, "Стоп", stopPi)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIF_ID, notification)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Не удалось запустить foreground-уведомление", t)
        }
    }

    companion object {
        private const val TAG = "VoiceAgent"
        private const val CHANNEL_ID = "svetlana_voice_agent"
        private const val NOTIF_ID = 4202
        private const val ACTION_STOP = "com.svetlana.home.voice.STOP"

        @Volatile
        private var jobActive = false

        /**
         * Запустить фоновый ассистент, если он включён в настройках
         * и есть разрешение на микрофон. Ничего не делает молча: если
         * условий нет — сервис не стартует.
         */
        fun startIfEnabled(context: Context) {
            try {
                if (!ServiceLocator.permissionManager.isGranted(android.Manifest.permission.RECORD_AUDIO)) {
                    Log.i(TAG, "Нет разрешения на микрофон — фоновый ассистент не запущен")
                    return
                }
                val settings = ServiceLocator.settings
                kotlinx.coroutines.runBlocking {
                    if (!settings.wakeWordEnabled.first()) {
                        Log.i(TAG, "Wake word выключен в настройках — фоновый ассистент не запущен")
                        return@runBlocking
                    }
                    val intent = Intent(context, VoiceAssistantService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(intent)
                    } else {
                        context.startService(intent)
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Не удалось запустить фоновый ассистент", t)
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, VoiceAssistantService::class.java))
            } catch (t: Throwable) { /* ignore */ }
        }
    }
}
