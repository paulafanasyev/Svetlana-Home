package com.svetlana.home.ai

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Состояние загрузок локальных моделей — одно на всё приложение, чтобы экран
 * «Локальный ИИ» показывал прогресс даже после того, как его закрыли и открыли снова.
 */
object ModelDownloads {
    sealed class State {
        data class Running(val percent: Int) : State()
        /** Пауза: частичный файл сохранён, «Скачать» продолжит с того же места. Это не ошибка. */
        data class Paused(val percent: Int, val message: String) : State()
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
 * Все проверки целостности (https, лимит размера, SHA-256 из реестра) — в [LocalModelManager.install].
 *
 * У каждой загрузки свой флаг остановки: новое нажатие «Скачать» не сбрасывает остановку
 * чужого потока, а для модели, которая уже качается, второй поток не запускается.
 *
 * Android 15 ограничивает dataSync-сервис шестью часами и зовёт [onTimeout]: все загрузки
 * ставятся на паузу с сохранением частичного файла, сервис сразу останавливается и до нового
 * запуска новые загрузки не принимает.
 */
class ModelDownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())

    /** modelId → флаг остановки ЭТОЙ загрузки. Есть запись — поток загрузки ещё жив. */
    private val tokens = ConcurrentHashMap<String, AtomicBoolean>()
    private val percents = ConcurrentHashMap<String, Int>()
    private var wakeLock: PowerManager.WakeLock? = null

    /** Лимит времени или уничтожение: этот экземпляр больше не качает (не сбрасывается). */
    @Volatile
    private var stopping = false

    @Volatile
    private var lastStartId = 0

    private fun t(ru: String, en: String): String = Tr.t(applicationContext, ru, en)

    private fun pausedText(percent: Int) = t(
        "Загрузка приостановлена ($percent%). Нажмите «Скачать» — она продолжится с того же места.",
        "Download paused ($percent%). Tap “Download” to continue from the same place.")

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        ensureChannel()
        val modelId = intent?.getStringExtra(EXTRA_MODEL_ID)
        val model = modelId?.let { ServiceLocator.modelRegistry.byId(it) }
        val shownPercent = modelId?.let { percents[it] } ?: 0
        startInForeground(progressNotification(model?.name ?: "…", shownPercent))
        if (model == null) {
            modelId?.let { ModelDownloads.set(it, ModelDownloads.State.Finished(false, t("Модель не найдена", "Model not found"))) }
            finishIfIdle()
            return START_NOT_STICKY
        }
        if (stopping) {
            // Сервис уже останавливается (лимит времени) — не оживляем остановленный поток.
            ModelDownloads.set(model.id, ModelDownloads.State.Paused(shownPercent, pausedText(shownPercent)))
            return START_NOT_STICKY
        }
        if (tokens.containsKey(model.id)) {
            // Уже качается: второй поток в тот же .part испортил бы файл.
            ModelDownloads.set(model.id, ModelDownloads.State.Running(shownPercent))
            return START_NOT_STICKY
        }
        launchWorker(model)
        return START_NOT_STICKY
    }

    private fun launchWorker(model: AIModel) {
        val token = AtomicBoolean(false)
        tokens[model.id] = token
        percents[model.id] = 0
        acquireWakeLock()
        scope.launch {
            try {
                runDownload(model, token)
            } finally {
                tokens.remove(model.id, token)
                main.post { finishIfIdle() }
            }
        }
    }

    private fun runDownload(model: AIModel, token: AtomicBoolean) {
        var lastPercent = -1
        var lastAt = 0L
        val result = try {
            ServiceLocator.localModelManager.install(model, cancelled = { token.get() }) { percent ->
                val now = SystemClock.elapsedRealtime()
                if (percent != lastPercent && (now - lastAt > 700L || percent >= 100)) {
                    lastPercent = percent
                    lastAt = now
                    percents[model.id] = percent
                    if (!token.get()) {
                        ModelDownloads.set(model.id, ModelDownloads.State.Running(percent))
                        notifySafe(progressNotification(model.name, percent))
                    }
                }
            }
        } catch (e: Throwable) {
            Result.failure(e)
        }
        val installed = result.getOrNull()
        val error = result.exceptionOrNull()
        val state: ModelDownloads.State = when {
            installed != null -> ModelDownloads.State.Finished(true,
                // Сервис останавливается — долгую проверку модели не запускаем.
                if (token.get()) t("загружена", "downloaded") else verifyAfterInstall(installed))
            error is DownloadPaused -> ModelDownloads.State.Paused(error.percent,
                error.message ?: pausedText(error.percent))
            else -> ModelDownloads.State.Finished(false, error?.message ?: t("Ошибка загрузки", "Download failed"))
        }
        ModelDownloads.set(model.id, state)
        val ok = state is ModelDownloads.State.Finished && state.success
        val paused = state is ModelDownloads.State.Paused
        val message = when (state) {
            is ModelDownloads.State.Finished -> state.message
            is ModelDownloads.State.Paused -> state.message
            is ModelDownloads.State.Running -> ""
        }
        try {
            ServiceLocator.historyManager.record(HistoryCategory.MODELS, when {
                ok -> "Модель ${model.name} загружена: $message"
                paused -> "Загрузка ${model.name} на паузе: $message"
                else -> "Загрузка ${model.name} не удалась: $message"
            })
        } catch (_: Throwable) { }
        notifySafe(doneNotification(model.name, ok, paused, message),
            DONE_ID_BASE + (model.id.hashCode() and 0xFFFF))
    }

    /** Android 15: лимит времени dataSync исчерпан — все загрузки на паузу и сразу остановка. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "Лимит времени foreground-сервиса — ставим загрузки на паузу")
        pauseAll()
        releaseWakeLock()
        try { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) } catch (_: Throwable) { }
        stopSelf()
    }

    /** Остановить все потоки и сразу показать паузу — даже если поток уже не успеет отчитаться. */
    private fun pauseAll() {
        stopping = true
        tokens.forEach { (id, token) ->
            token.set(true)
            val p = percents[id] ?: 0
            ModelDownloads.set(id, ModelDownloads.State.Paused(p, pausedText(p)))
        }
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

    /**
     * Вызывается только в главном потоке (как и onStartCommand), поэтому новая загрузка
     * не потеряет foreground. stopSelf(lastStartId) не остановит сервис, если пришёл более новый запуск.
     */
    private fun finishIfIdle() {
        if (tokens.isNotEmpty()) return
        releaseWakeLock()
        try { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) } catch (_: Throwable) { }
        stopSelf(lastStartId)
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

    private fun doneNotification(name: String, ok: Boolean, paused: Boolean, message: String) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(when {
                ok -> t("Модель загружена", "Model downloaded")
                paused -> t("Загрузка на паузе", "Download paused")
                else -> t("Загрузка не завершена", "Download not finished")
            })
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
        // Сервис уничтожен (системой или по тайм-ауту) — потоки останавливаются с сохранением .part,
        // а экран сразу видит паузу вместо вечного «Загрузка…».
        pauseAll()
        main.removeCallbacksAndMessages(null)
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
