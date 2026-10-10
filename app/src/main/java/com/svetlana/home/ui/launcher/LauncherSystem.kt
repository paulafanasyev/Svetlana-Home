package com.svetlana.home.ui.launcher

import android.annotation.SuppressLint
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetProviderInfo
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.os.Process
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewConfiguration
import kotlin.math.abs
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import android.content.Context
import android.content.SharedPreferences
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json

/** Раскладка рабочего стола на диске (SharedPreferences, JSON). */
internal object HomeLayoutStore {
    private const val PREFS = "svetlana_launcher"
    private const val KEY = "home_layout_v1"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val state = MutableStateFlow(HomeLayout())
    val layout: StateFlow<HomeLayout> = state.asStateFlow()

    @Volatile
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs != null) return
        synchronized(this) {
            if (prefs != null) return
            val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            state.value = try {
                p.getString(KEY, null)?.let { json.decodeFromString(HomeLayout.serializer(), it) } ?: HomeLayout()
            } catch (t: Throwable) {
                Log.w("HomeLayoutStore", "Раскладка повреждена — начинаем заново", t)
                HomeLayout()
            }
            prefs = p
        }
    }

    @Synchronized
    fun update(transform: (HomeLayout) -> HomeLayout) {
        val next = transform(state.value)
        if (next == state.value) return
        state.value = next
        prefs?.edit()?.putString(KEY, json.encodeToString(HomeLayout.serializer(), next))?.apply()
    }

    fun newId(): String = System.currentTimeMillis().toString(36)
}

/**
 * Значки уведомлений на иконках (как в Pixel/One UI). Работают только
 * после того, как пользователь сам выдал доступ к уведомлениям;
 * читаются лишь имена пакетов и количество, текст уведомлений не трогаем.
 */
internal object NotificationDots {
    private val state = MutableStateFlow<Map<String, Int>>(emptyMap())
    val counts: StateFlow<Map<String, Int>> = state.asStateFlow()

    fun publish(list: Array<StatusBarNotification>?) {
        state.value = list.orEmpty()
            .filter { !it.isOngoing }
            .groupingBy { it.packageName }
            .eachCount()
    }

    fun clear() { state.value = emptyMap() }

    fun isEnabled(context: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
}

val LocalNotificationDots = compositionLocalOf<Map<String, Int>> { emptyMap() }

class NotificationDotsService : NotificationListenerService() {
    private fun refresh() {
        try { NotificationDots.publish(activeNotifications) } catch (t: Throwable) { NotificationDots.clear() }
    }
    override fun onListenerConnected() = refresh()
    override fun onListenerDisconnected() = NotificationDots.clear()
    override fun onNotificationPosted(sbn: StatusBarNotification?) = refresh()
    override fun onNotificationRemoved(sbn: StatusBarNotification?) = refresh()
}

/**
 * Хост виджетов launcher. Своя AppWidgetHostView перехватывает долгое
 * нажатие (как Launcher3), чтобы показать меню виджета: размер / удалить.
 */
internal class SvetlanaWidgetHost(context: Context, hostId: Int) : AppWidgetHost(context, hostId) {
    override fun onCreateView(context: Context, appWidgetId: Int, appWidget: AppWidgetProviderInfo?): AppWidgetHostView =
        LongPressWidgetHostView(context)
}

internal class LongPressWidgetHostView(context: Context) : AppWidgetHostView(context) {
    var onLongPress: (() -> Unit)? = null
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var fired = false
    private val longPress = Runnable {
        fired = true
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        onLongPress?.invoke()
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                fired = false
                downX = ev.x
                downY = ev.y
                postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE ->
                if (abs(ev.x - downX) > slop || abs(ev.y - downY) > slop) removeCallbacks(longPress)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> removeCallbacks(longPress)
        }
        // После долгого нажатия жест принадлежит launcher, виджет его не получает.
        return fired
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (fired) {
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) fired = false
            return true
        }
        return super.onTouchEvent(event)
    }
}

internal object LauncherWidgets {
    private const val HOST_ID = 0x5E71
    @Volatile
    private var host: AppWidgetHost? = null

    fun host(context: Context): AppWidgetHost =
        host ?: synchronized(this) {
            host ?: SvetlanaWidgetHost(context.applicationContext, HOST_ID).also { host = it }
        }

    fun startListening(context: Context) {
        try { host(context).startListening() } catch (t: Throwable) { Log.w("LauncherWidgets", "startListening", t) }
    }

    fun stopListening(context: Context) {
        try { host(context).stopListening() } catch (t: Throwable) { Log.w("LauncherWidgets", "stopListening", t) }
    }
}

/** Ярлыки приложений (App shortcuts) в меню иконки — доступны launcher по умолчанию. */
internal object AppShortcuts {
    data class Item(val id: String, val pkg: String, val label: String, val info: ShortcutInfo)

    fun query(context: Context, pkg: String): List<Item> {
        val la = context.getSystemService(LauncherApps::class.java) ?: return emptyList()
        return try {
            if (!la.hasShortcutHostPermission()) return emptyList()
            val q = LauncherApps.ShortcutQuery()
                .setPackage(pkg)
                .setQueryFlags(
                    LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or
                        LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or
                        LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED
                )
            la.getShortcuts(q, Process.myUserHandle()).orEmpty()
                .filter { it.isEnabled }
                .sortedBy { it.rank }
                .take(4)
                .map { Item(it.id, pkg, (it.shortLabel ?: it.longLabel ?: it.id).toString(), it) }
        } catch (t: Throwable) {
            emptyList()
        }
    }

    fun icon(context: Context, item: Item): ImageBitmap? = try {
        context.getSystemService(LauncherApps::class.java)
            ?.getShortcutIconDrawable(item.info, context.resources.displayMetrics.densityDpi)
            ?.toBitmap(96, 96)?.asImageBitmap()
    } catch (t: Throwable) {
        null
    }

    fun start(context: Context, item: Item) {
        try {
            context.getSystemService(LauncherApps::class.java)
                ?.startShortcut(item.pkg, item.id, null, null, Process.myUserHandle())
        } catch (t: Throwable) {
            Log.w("AppShortcuts", "Не удалось открыть ярлык", t)
        }
    }
}

/** Перетаскивание иконки по рабочему столу. Координаты — в системе корня окна. */
internal class TileDrag(
    val onStart: (Offset) -> Unit,
    val onMove: (Offset) -> Unit,
    val onEnd: () -> Unit,
    val onCancel: () -> Unit,
    val onPositioned: (Rect) -> Unit
)

/**
 * Жесты иконки как в стандартном launcher: касание — запуск, долгое
 * нажатие — меню, долгое нажатие и перетаскивание — перенос/папка.
 * [origin] — текущий левый верхний угол иконки в координатах окна.
 */
internal fun Modifier.launcherTileGestures(
    key: Any,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    drag: TileDrag?,
    origin: () -> Offset,
    onHold: () -> Unit = {}
): Modifier = pointerInput(key, drag != null) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        down.consume()
        var outcome = 0 // 0 — долгое нажатие, 1 — касание, 2 — жест забрал родитель
        withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            val up = waitForUpOrCancellation()
            outcome = if (up != null) { up.consume(); 1 } else 2
        }
        when (outcome) {
            1 -> onTap()
            0 -> {
                // Без перетаскивания меню открывается сразу; с перетаскиванием —
                // после отпускания, если палец не сдвинулся (как Pixel Launcher).
                onHold()
                if (drag == null) onLongPress()
                var position = origin() + down.position
                var travelled = 0f
                var active = false
                var ended = false
                try {
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) {
                        change.consume()
                        ended = true
                        if (active) drag?.onEnd?.invoke() else if (drag != null) onLongPress()
                        break
                    }
                    val delta = change.positionChange()
                    if (delta != Offset.Zero) {
                        change.consume()
                        position += delta
                        travelled += delta.getDistance()
                        if (drag != null && !active && travelled > viewConfiguration.touchSlop) {
                            active = true
                            drag.onStart(position)
                        } else if (active) {
                            drag?.onMove?.invoke(delta)
                        }
                    }
                }
                } finally {
                    // Иконку убрали с экрана посреди переноса (страница пересоздана) — отменяем перенос.
                    if (active && !ended) drag?.onCancel?.invoke()
                }
            }
        }
    }
}
