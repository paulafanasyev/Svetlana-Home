package com.svetlana.home.apps.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.UserHandle
import android.util.Log

/**
 * Аудит §6: отдельный реестр **launchable activities**.
 *
 * Это Activity с ACTION_MAIN + CATEGORY_LAUNCHER — то, что имеет иконку
 * на главном экране. Именно через них происходит запуск приложений.
 *
 * Раньше один класс смешивал установленные пакеты и launchable activities,
 * из-за чего App Drawer либо терял приложения, либо показывал пакеты,
 * которые нельзя запустить. Теперь:
 *   - чем заполнять Drawer — решает InstalledPackageRegistry;
 *   - как запускать — решает LaunchableActivityRegistry.
 */
class LaunchableActivityRegistry(private val context: Context) {

    private val pm: PackageManager get() = context.packageManager

    /**
     * Список launchable activities для всех профилей основного пользователя.
     * Не требует роли launcher — работает всегда (с <queries> в манифесте).
     */
    fun scanLaunchable(): List<LaunchableActivity> {
        val result = mutableListOf<LaunchableActivity>()
        val seen = HashSet<String>()

        // Основной способ: queryIntentActivities по MAIN/LAUNCHER.
        // Работает без роли launcher — критично для ещё не назначенного HOME.
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(intent, 0)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "queryIntentActivities не удалось", t)
            emptyList()
        }
        resolved.forEach { ri ->
            val pkg = ri.activityInfo.packageName
            val component = ComponentName(pkg, ri.activityInfo.name)
            if (seen.add(component.flattenToString())) {
                result.add(
                    LaunchableActivity(
                        packageName = pkg,
                        activityName = ri.activityInfo.name,
                        label = try { ri.loadLabel(pm).toString() } catch (t: Throwable) { pkg },
                        launchIntent = Intent(Intent.ACTION_MAIN)
                            .addCategory(Intent.CATEGORY_LAUNCHER)
                            .setComponent(component)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED),
                        componentName = component
                    )
                )
            }
        }
        return result
    }

    /**
     * Launchable activities через LauncherApps. Уважает visibility пакетов
     * launcher'а и рабочие профили. Используется как дополнение, а не как
     * единственный источник — иначе список пуст, пока роль HOME не выдана.
     */
    fun scanLauncherApps(): List<LaunchableActivity> {
        val result = mutableListOf<LaunchableActivity>()
        val launcherApps = try {
            context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps
        } catch (t: Throwable) { null } ?: return result

        for (profile in profiles()) {
            val activities = try {
                launcherApps.getActivityList(null, profile)
            } catch (t: Throwable) {
                Log.w(TAG, "LauncherApps недоступен для профиля", t)
                null
            }
            activities?.forEach { la ->
                val pkg = la.applicationInfo.packageName
                result.add(
                    LaunchableActivity(
                        packageName = pkg,
                        activityName = la.componentName.className,
                        label = la.label?.toString() ?: pkg,
                        launchIntent = Intent(Intent.ACTION_MAIN)
                            .addCategory(Intent.CATEGORY_LAUNCHER)
                            .setComponent(la.componentName)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED),
                        componentName = la.componentName
                    )
                )
            }
        }
        return result
    }

    private fun profiles(): List<UserHandle> = try {
        val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps
        launcherApps?.profiles?.toList() ?: listOf(android.os.Process.myUserHandle())
    } catch (t: Throwable) {
        listOf(android.os.Process.myUserHandle())
    }

    /** Иконка launchable activity. */
    fun icon(activity: LaunchableActivity): Drawable? = try {
        pm.getApplicationIcon(activity.packageName)
    } catch (t: Throwable) { null }

    companion object { private const val TAG = "LaunchableActivityRegistry" }
}

/**
 * Activity, доступная для запуска с главного экрана.
 * Связана с конкретным пакетом и компонентом.
 */
data class LaunchableActivity(
    val packageName: String,
    val activityName: String,
    val label: String,
    val launchIntent: Intent,
    val componentName: ComponentName
)
