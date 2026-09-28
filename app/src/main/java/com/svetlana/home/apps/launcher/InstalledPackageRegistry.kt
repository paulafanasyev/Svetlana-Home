package com.svetlana.home.apps.launcher

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log

/**
 * Аудит §6 (AOSP Launcher3 как основа): реестр **всех установленных пакетов**.
 *
 * Важно: «все установленные пакеты» ≠ «приложения с launcher icon».
 * Старый AppRegistry смешивал эти понятия, из-за чего App Drawer не
 * выполнял требование показывать все приложения устройства.
 *
 * Здесь собирается полный список пакетов (видимый в пределах
 * Android package visibility — см. <queries> в манифесте).
 */
class InstalledPackageRegistry(private val context: Context) {

    private val pm: PackageManager get() = context.packageManager

    /**
     * Все видимые установленные пакеты.
     * Не фильтруется по launcher activities.
     */
    fun scanAll(): List<InstalledPackage> {
        val result = mutableListOf<InstalledPackage>()
        val seen = HashSet<String>()

        // Основной источник: getInstalledApplications.
        val apps = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getInstalledApplications(0)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "getInstalledApplications не удалось", t)
            emptyList()
        }

        apps.forEach { ai ->
            if (seen.add(ai.packageName)) {
                result.add(
                    InstalledPackage(
                        packageName = ai.packageName,
                        label = try { pm.getApplicationLabel(ai).toString() } catch (t: Throwable) { ai.packageName },
                        uid = ai.uid,
                        systemApp = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                        enabled = ai.enabled,
                        updatedSystemApp = (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0,
                        versionName = safeVersionName(ai.packageName),
                        installer = safeInstaller(ai.packageName)
                    )
                )
            }
        }
        return result
    }

    /**
     * Только пакеты, которые видит launcher: имеют хотя бы одну
     * activity с ACTION_MAIN + CATEGORY_LAUNCHER.
     */
    fun hasLauncherActivity(packageName: String): Boolean = try {
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
            .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
            .setPackage(packageName)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(
                intent,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong())
            ).isNotEmpty()
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, PackageManager.MATCH_ALL).isNotEmpty()
        }
    } catch (t: Throwable) { false }

    private fun safeVersionName(packageName: String): String? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0)).versionName
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(packageName, 0).versionName
        }
    } catch (t: Throwable) { null }

    private fun safeInstaller(packageName: String): String? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            pm.getInstallSourceInfo(packageName).installingPackageName
        } else {
            @Suppress("DEPRECATION")
            pm.getInstallerPackageName(packageName)
        }
    } catch (t: Throwable) { null }

    companion object { private const val TAG = "InstalledPackageRegistry" }
}

/**
 * Запись полного реестра установленных пакетов.
 * Не несёт launcher-специфичных полей (activity, launchIntent).
 */
data class InstalledPackage(
    val packageName: String,
    val label: String,
    val uid: Int,
    val systemApp: Boolean,
    val enabled: Boolean,
    val updatedSystemApp: Boolean,
    val versionName: String?,
    val installer: String?
)
