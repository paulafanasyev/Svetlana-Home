package com.svetlana.home.hands

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.NotificationChannel
import android.app.NotificationManager
import android.graphics.Bitmap
import android.graphics.Path
import android.hardware.display.DisplayManager
import android.view.Display
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.svetlana.home.R
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * SvetlanaAccessibilityService — Hands.
 *
 * Используется для управления другими приложениями ТОЛЬКО при наличии
 * необходимого системного доступа, который предоставляет сам пользователь
 * через системные настройки Android.
 *
 * Здесь нет ничего, что обходило бы Android: ни скрытого включения,
 * ни автоматического подтверждения опасных действий.
 */
class SvetlanaAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        startForegroundNotification()
        Log.i(TAG, "Hands активирован пользователем")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString() ?: return
        lastPackage = pkg
        _events.tryEmit(pkg)
    }

    override fun onInterrupt() {
        Log.w(TAG, "Hands прерван системой")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    // ---------- API, доступное HandsController ----------

    /** Снимок UI tree текущего окна. */
    fun snapshotUiTree(): UiTree {
        val root = rootInActiveWindow ?: return UiTree(currentPackage(), null, emptyList())
        return UiTree(currentPackage(), mapNode(root), flatten(root))
    }

    private fun mapNode(node: AccessibilityNodeInfo, depth: Int = 0): UiNode {
        return UiNode(
            className = node.className?.toString() ?: "",
            text = node.text?.toString() ?: "",
            contentDescription = node.contentDescription?.toString() ?: "",
            id = node.viewIdResourceName ?: "",
            isClickable = node.isClickable,
            isScrollable = node.isScrollable,
            isEnabled = node.isEnabled,
            isFocused = node.isFocused,
            isPassword = node.isPassword,
            depth = depth,
            bounds = android.graphics.Rect().also { node.getBoundsInScreen(it) }
        )
    }

    private fun flatten(root: AccessibilityNodeInfo): List<UiNode> {
        val out = mutableListOf<UiNode>()
        fun walk(node: AccessibilityNodeInfo, depth: Int) {
            out.add(mapNode(node, depth))
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { walk(it, depth + 1) }
            }
        }
        walk(root, 0)
        return out
    }

    fun currentPackage(): String = lastPackage ?: ""

    /** Клик по элементу. Возвращает true, только если действие реально выполнено. */
    fun click(node: AccessibilityNodeInfo): Boolean {
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) target = target.parent
        return target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
    }

    fun clickPoint(x: Float, y: Float): Boolean {
        return performGesture(GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(Path().apply { moveTo(x, y); lineTo(x + 0.1f, y) }, 0, 60))
            .build())
    }

    fun longClick(node: AccessibilityNodeInfo): Boolean {
        return node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
    }

    fun inputText(node: AccessibilityNodeInfo, text: String): Boolean {
        val args = android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun clearText(node: AccessibilityNodeInfo): Boolean {
        val args = android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun scrollForward(node: AccessibilityNodeInfo): Boolean =
        node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)

    fun scrollBackward(node: AccessibilityNodeInfo): Boolean =
        node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)

    /** Свайп через GestureDescription. */
    fun swipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long = 300): Boolean {
        val path = Path().apply { moveTo(startX, startY); lineTo(endX, endY) }
        return performGesture(GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build())
    }

    /**
     * Жёсткое правило доказательной цепочки:GestureResultCallback вызывается
     * асинхронно, поэтому нельзя вернуть результат сразу после dispatchGesture().
     * Ждём реального onCompleted/onCancelled с таймаутом — иначе ACTION_PERFORMED
     * мог бы быть выставлен до того, как жест реально выполнен.
     */
    private fun performGesture(gesture: GestureDescription): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val latch = java.util.concurrent.CountDownLatch(1)
        val succeeded = AtomicBoolean(false)
        val callback = object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) {
                succeeded.set(true)
                latch.countDown()
            }
            override fun onCancelled(g: GestureDescription?) {
                succeeded.set(false)
                latch.countDown()
            }
        }
        val dispatched = try {
            dispatchGesture(gesture, callback, null)
        } catch (t: Throwable) {
            Log.w(TAG, "dispatchGesture не принят системой", t)
            return false
        }
        if (!dispatched) return false
        // Система сама вызывает callback в main thread; ждём с таймаутом,
        // чтобы не повиснуть, если Android не ответил.
        try {
            latch.await(GESTURE_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return false
        }
        return succeeded.get()
    }

    fun pressBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
    fun pressHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)
    fun openRecents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)
    fun openNotifications(): Boolean = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
    fun openQuickSettings(): Boolean = performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)

    /**
     * Скриншот экрана. Доступно на Android R+ с разрешённым canTakeScreenshots.
     */
    fun captureScreen(onResult: (Bitmap?) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) { onResult(null); return }
        val displayId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            (getSystemService(DisplayManager::class.java)).getDisplay(Display.DEFAULT_DISPLAY)?.displayId
                ?: Display.DEFAULT_DISPLAY
        } else Display.DEFAULT_DISPLAY
        takeScreenshot(displayId, ContextCompat.getMainExecutor(this), object : TakeScreenshotCallback {
            override fun onSuccess(screenshot: ScreenshotResult) {
                val bitmap = Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                onResult(bitmap)
            }
            override fun onFailure(errorCode: Int) { onResult(null) }
        })
    }

    private fun startForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(CHANNEL_ID, getString(R.string.hands_channel_name),
                NotificationManager.IMPORTANCE_LOW).apply {
                description = getString(R.string.hands_channel_desc)
            }
            nm.createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.hands_notification_title))
            .setContentText(getString(R.string.hands_notification_text))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIF_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIF_ID, notification)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Не удалось запустить foreground-уведомление Hands", t)
        }
    }

    companion object {
        private const val TAG = "SvetlanaHands"
        private const val CHANNEL_ID = "svetlana_hands"
        private const val NOTIF_ID = 4201
        private const val GESTURE_TIMEOUT_MS = 5_000L

        @Volatile
        var instance: SvetlanaAccessibilityService? = null
            private set

        @Volatile
        private var lastPackage: String? = null

        private val _events = MutableSharedFlow<String>(extraBufferCapacity = 16)
        val events: SharedFlow<String> = _events.asSharedFlow()
    }
}
