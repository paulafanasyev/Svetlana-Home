package com.svetlana.home.control

import android.app.ActivityManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.provider.Settings
import com.svetlana.home.hands.HandsController

/**
 * LaunchVerifier — проверяет, действительно ли приложение стало foreground.
 *
 * Доказательное правило (ТЗ §19): startActivity() означает лишь
 * «команда отправлена Android». Это ещё не значит, что приложение реально
 * стало foreground. ACTION_PERFORMED можно выставить только после того,
 * как система подтвердила переход.
 *
 * Источники проверки, по приоритету:
 *  1. Hands (AccessibilityService) — видит текущий foreground-пакет напрямую.
 *  2. UsageStatsManager — если пользователь выдал PACKAGE_USAGE_STATS.
 *  3. ActivityManager.getRunningTasks — только для диагностики, не для proof.
 *
 * Если ни один источник не доступен, результат — UNVERIFIED, и доказательная
 * цепочка обязана это отразить (нельзя писать VERIFIED без проверки).
 */
class LaunchVerifier(
    private val context: Context,
    private val hands: HandsController
) {

    enum class Source { HANDS, USAGE_STATS, NONE }

    data class Result(val foregroundPackage: String?, val source: Source) {
        /** Проверка прошла и пакет подтверждён. */
        val isVerified: Boolean get() = foregroundPackage != null
    }

    /**
     * Ожидает появления целевого пакета в foreground.
     * @return пакет, который реально оказался в foreground, или null.
     */
    suspend fun awaitForeground(targetPackage: String, timeoutMs: Long = 4_000): Result {
        val deadline = System.currentTimeMillis() + timeoutMs
        // 1. Hands — самый точный источник.
        if (hands.isActive) {
            if (hands.waitForPackage(targetPackage, timeoutMs)) {
                return Result(targetPackage, Source.HANDS)
            }
            // Hands не увидел целевой пакет — сообщаем что есть на самом деле.
            val current = hands.currentPackage()
            if (current.isNotBlank()) return Result(current, Source.HANDS)
        }
        // 2. UsageStatsManager — требует специальное разрешение пользователя.
        if (usageStatsGranted()) {
            while (System.currentTimeMillis() < deadline) {
                val pkg = currentViaUsageStats()
                if (pkg != null) {
                    return Result(pkg, if (pkg.equals(targetPackage, ignoreCase = true))
                        Source.USAGE_STATS else Source.USAGE_STATS)
                }
                kotlinx.coroutines.delay(200)
            }
            return Result(currentViaUsageStats(), Source.USAGE_STATS)
        }
        // 3. Ни одного доступного источника — честно сообщаем, что не проверено.
        return Result(null, Source.NONE)
    }

    /** Текущий foreground-пакет без ожидания. */
    fun currentForeground(): String? {
        if (hands.isActive) {
            val pkg = hands.currentPackage()
            if (pkg.isNotBlank()) return pkg
        }
        if (usageStatsGranted()) return currentViaUsageStats()
        return currentViaActivityManager()
    }

    private fun usageStatsGranted(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP_MR1) return false
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(
                    android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(), context.packageName
                )
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(
                    android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(), context.packageName
                )
            }
            mode == android.app.AppOpsManager.MODE_ALLOWED
        } catch (t: Throwable) { false }
    }

    private fun currentViaUsageStats(): String? = try {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        // ussFirst — Bias for usage stats ordering.
        val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_BEST, now - 60_000, now)
        stats?.maxByOrNull { it.lastTimeUsed }?.packageName
    } catch (t: Throwable) { null }

    /**
     * getRunningTasks ограничен Android — возвращает только собственное приложение.
     * Используем только как диагностический сигнал, не как proof.
     */
    private fun currentViaActivityManager(): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return null
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            @Suppress("DEPRECATION")
            am.getRunningTasks(1)?.firstOrNull()?.topActivity?.packageName
        } catch (t: Throwable) { null }
    }

    /**	Intent для запроса PACKAGE_USAGE_STATS пользователем (штатный flow). */
    fun usageStatsSettingsIntent(): android.content.Intent =
        android.content.Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
}
