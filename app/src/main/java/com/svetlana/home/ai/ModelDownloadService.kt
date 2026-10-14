package com.svetlana.home.ai

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.svetlana.home.R
import com.svetlana.home.core.ServiceLocator
import com.svetlana.home.core.Tr
import com.svetlana.home.memory.HistoryCategory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/**
 * Состояние загрузок локальных моделей — одно на всё приложение, чтобы экран
 * «Локальный ИИ» показывал прогресс даже после того, как его закрыли и открыли снова.
 */
object ModelDownloads {
    sealed class State {
        data class Running(val percent: Int) : State()
        data class Finished(val success: Boolean, val message: String) : State()
    }

    private val _states = MutableStateFlow<Map<String, State>>(emptyMap())
    val states: StateFlow<Map<String, State>> = _states.asStateFlow()

    fun isRunning(modelId: String): Boolean = _states.value[modelId] is State.Running

    internal fun set(modelId: String, state: State) {
        _states.update { it + (modelId to state) }
    }

    /**
     * Запустить загрузку — только по нажатию пользователя (ТЗ §32/§87). Загрузка идёт
     * в foreground-сервисе с уведомлением и не обрывается, если свернуть окно. Повторное нажатие
     * после обрыва продолжает с того же места ([LocalModelManager.install]).
     */
    fun start(context: Context, modelId: String) {
        if (isRunning(modelId)) return
        set(modelId, State.Running(0))
        val intent = Intent(context, ModelDownloadService::class.java)
            .putExtra(ModelDownloadService.EXTRA_MODEL_ID, modelId)
        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (t: Throwable) {
            Log.w("ModelDownloads", "Не удалось запустить сервис загрузки", t)
            set(modelId, State.Finished(false, t.message ?: "service"))
        }
    }
}

/**
 * ModelDownloadService — загрузка локальной модели в фоне (foreground service типа dataSync).
 *
 * Раньше загрузка жила в корутине экрана настроек: стоило свернуть окно, и Android
 * ограничивал сеть/завершал процесс — модель не докачивалась. Все проверки целостности
 * (https, лимит размера, SHA-256 из реестра) остаются в [LocalModelManager.install].
 *
 * Android 15 ограничивает dataSync-сервис шестью часами и зовёт [onTimeout]: сервис должен
 * остановиться за несколько секунд, иначе приложение аварийно завершается. Загрузка тогда
 * ставится на паузу с сохранением частичного файла.
 */
class ModelDownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val active = AtomicInteger(0)
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile
    private var stopRequested = false

    private fun t(ru: String, en: String): String = Tr.t(applicationContext, ru, en)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel()
        stopRequested = false
        val modelId = intent?.getStringExtra(EXTRA_MODEL_ID)
        val model = modelId?.let { ServiceLocator.modelRegistry.byId(it) }
        startInForeground(progressNotification(model?.name ?: "…", 0))
        if (model == null) {
            modelId?.let { ModelDownloads.set(it, ModelDownloads.State.Finished(false, t("Модель не найдена", "Model not found"))) }
            if (active.get() == 0) finish()
            return START_NOT_STICKY
        }
        acquireWakeLock()
        active.incrementAndGet()
        scope.launch {
            var lastPercent = -1
            var lastAt = 0L
            val result = try {
                ServiceLocator.localModelManager.install(model, cancelled = { stopRequested }) { percent ->
                    val now = SystemClock.elapsedRealtime()
                    if (percent != lastPercent && (now - lastAt > 700L || percent >= 100)) {
                        lastPercent = percent
                        lastAt = now
                        ModelDownloads.set(model.id, ModelDownloads.State.Running(percent))
                        notifySafe(progressNotification(model.name, percent))
                    }
                }
            } catch (e: Throwable) {
                Result.failure(e)
            }
            val installed = result.getOrNull()
            val message = if (installed != null) {
                verifyAfterInstall(installed)
            } else {
                result.exceptionOrNull()?.message ?: t("Ошибка загрузки", "Download failed")
            }
            ModelDownloads.set(model.id, ModelDownloads.State.Finished(installed != null, message))
            try {
                ServiceLocator.historyManager.record(HistoryCategory.MODELS,
                    if (installed != null) "Модель ${model.name} загружена: $message"
                    else "Загрузка ${model.name} не удалась: $message")
            } catch (_: Throwable) { }
            notifySafe(doneNotification(model.name, installed != null, message), DONE_ID_BASE + (model.id.hashCode() and 0xFFFF))
            if (active.decrementAndGet() == 0) finish()
        }
        return START_NOT_STICKY
    }

    /** Android 15: лимит времени dataSync исчерпан — пауза с сохранением скачанного и сразу остановка. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "Лимит времени foreground-сервиса — ставим загрузку на паузу")
        stopRequested = true
        finish()
    }

    /** Аудит п.2: «установлена» ≠ «работает» — сразу проверяем модель. */
    private fun verifyAfterInstall(installed: InstalledModel): String = try {
        val rep = ModelVerificationRunner.verify(
            installed.modelId, ServiceLocator.localModelManager, ServiceLocator.modelRegistry,
            ServiceLocator.compositeRuntime, ServiceLocator.device)
        if (rep.inferenceOk) t("модель работает, ${rep.tokensPerSecond} ток/с", "model works, ${rep.tokensPerSecond} tok/s")
        else t("загружена, но проверка не прошла: ${rep.failureReason}",
            "downloaded, but the check failed: ${rep.failureReason}")
    } catch (e: Throwable) {
        t("загружена", "downloaded")
    }

    private fun startInForeground(notification: android.app.Notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(PROGRESS_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(PROGRESS_ID, notification)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "startForeground не удался", e)
        }
    }

    private fun finish() {
        releaseWakeLock()
        try { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) } catch (_: Throwable) { }
        stopSelf()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "svetlana:model-download").apply {
                setReferenceCounted(false)
                acquire(3 * 60 * 60 * 1000L)
            }
        } catch (e: Throwable) { Log.w(TAG, "WakeLock недоступен", e) }
    }

    private fun releaseWakeLock() {
        try { wakeLock?.takeIf { it.isHeld }?.release() } catch (_: Throwable) { }
        wakeLock = null
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(NotificationChannel(
                CHANNEL_ID, t("Загрузка моделей", "Model downloads"), NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun progressNotification(name: String, percent: Int) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(t("Загрузка модели", "Downloading model"))
            .setContentText("$name · $percent%")
            .setProgress(100, percent.coerceIn(0, 100), percent <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun doneNotification(name: String, ok: Boolean, message: String) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(if (ok) t("Модель загружена", "Model downloaded") else t("Загрузка не завершена", "Download not finished"))
            .setContentText("$name: $message")
            .setStyle(NotificationCompat.BigTextStyle().bigText("$name: $message"))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun notifySafe(notification: android.app.Notification, id: Int = PROGRESS_ID) {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(id, notification)
        } catch (_: Throwable) { /* нет разрешения на уведомления — прогресс виден в приложении */ }
    }

    override fun onDestroy() {
        // Сервис уничтожен (системой или по тайм-ауту) — поток загрузки останавливается с сохранением .part.
        stopRequested = true
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_MODEL_ID = "model_id"
        private const val CHANNEL_ID = "svetlana_model_download"
        private const val PROGRESS_ID = 4711
        private const val DONE_ID_BASE = 4800
        private const val TAG = "ModelDownloadService"
    }
}
