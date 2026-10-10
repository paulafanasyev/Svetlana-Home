package com.svetlana.home.ui.launcher

import android.annotation.SuppressLint
import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import android.provider.Settings
import android.provider.Telephony
import android.util.Log
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.core.graphics.drawable.toBitmap
import com.svetlana.home.apps.AppModel
import com.svetlana.home.core.ServiceLocator
import com.svetlana.home.ui.theme.GreenDeep
import com.svetlana.home.ui.theme.MintPrimary
import com.svetlana.home.ui.theme.MintSoft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Кэш иконок: декодируем в фоне один раз, чтобы сетка не дёргалась при прокрутке. */
internal object AppIconCache {
    private const val ICON_PX = 192
    private val cache = LruCache<String, ImageBitmap>(256)

    fun peek(pkg: String): ImageBitmap? = cache.get(pkg)

    fun load(context: Context, pkg: String): ImageBitmap? {
        cache.get(pkg)?.let { return it }
        return try {
            val drawable = context.packageManager.getApplicationIcon(pkg)
            drawable.toBitmap(ICON_PX, ICON_PX).asImageBitmap().also { cache.put(pkg, it) }
        } catch (t: Throwable) {
            null
        }
    }
}

@Composable
internal fun AppIconImage(packageName: String, size: Dp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val icon by produceState(initialValue = AppIconCache.peek(packageName), packageName) {
        if (value == null) {
            value = withContext(Dispatchers.IO) { AppIconCache.load(context.applicationContext, packageName) }
        }
    }
    val bmp = icon
    if (bmp != null) {
        Image(bitmap = bmp, contentDescription = null, modifier = modifier.size(size))
    } else {
        Box(modifier.size(size).background(Color.White.copy(alpha = 0.18f), CircleShape))
    }
}

/** Маленький орб Светланы для поисковой строки, чипов и плиток. */
@Composable
internal fun MiniOrb(size: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(size)
            .background(Brush.radialGradient(listOf(MintSoft, MintPrimary, GreenDeep)), CircleShape)
    )
}

/** Определение приложений по умолчанию для дока через официальные Intent'ы. */
internal object DefaultApps {
    fun resolve(context: Context): Map<DockRole, String?> {
        val pm = context.packageManager
        fun pkgFor(intent: Intent): String? {
            val def = try {
                pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
            } catch (t: Throwable) { null }
            if (def != null && def != "android") return def
            return try {
                pm.queryIntentActivities(intent, 0).firstOrNull()?.activityInfo?.packageName
            } catch (t: Throwable) { null }
        }
        val sms = try { Telephony.Sms.getDefaultSmsPackage(context) } catch (t: Throwable) { null }
        return mapOf(
            DockRole.PHONE to pkgFor(Intent(Intent.ACTION_DIAL)),
            DockRole.MESSAGES to (sms ?: pkgFor(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")))),
            DockRole.BROWSER to pkgFor(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://ya.ru")).addCategory(Intent.CATEGORY_BROWSABLE)
            ),
            DockRole.CAMERA to pkgFor(Intent(MediaStore.ACTION_IMAGE_CAPTURE))
        )
    }
}

/** Системные действия launcher: запуск, сведения, удаление, обои, шторка. */
internal class LauncherActions(private val context: Context) {

    private fun start(intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        Log.w(TAG, "Нет активности для ${intent.action}", e)
        false
    } catch (e: SecurityException) {
        Log.w(TAG, "Нет доступа к ${intent.action}", e)
        false
    }

    fun launch(app: AppModel) {
        val intent = context.packageManager.getLaunchIntentForPackage(app.packageName) ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        if (start(intent)) ServiceLocator.appRepository.markUsed(app.packageName)
    }

    fun appInfo(app: AppModel) {
        start(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun uninstall(app: AppModel) {
        start(
            Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun wallpaper() {
        start(
            Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), "Обои")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun webSearch(query: String) {
        val ok = start(
            Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, query)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        if (!ok) {
            start(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://ya.ru/search/?text=" + Uri.encode(query)))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    fun open(cls: Class<*>) {
        start(Intent(context, cls))
    }

    fun notificationAccess() {
        start(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun requestHomeRole() {
        start(ServiceLocator.permissionManager.homeRoleIntent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Свайп вниз по рабочему столу открывает шторку уведомлений, как в Pixel Launcher. */
    @SuppressLint("WrongConstant", "PrivateApi", "DiscouragedPrivateApi")
    fun expandNotifications() {
        try {
            val service = context.getSystemService("statusbar") ?: return
            service.javaClass.getMethod("expandNotificationsPanel").invoke(service)
        } catch (t: Throwable) {
            Log.w(TAG, "Не удалось открыть шторку", t)
        }
    }

    companion object { private const val TAG = "LauncherActions" }
}
