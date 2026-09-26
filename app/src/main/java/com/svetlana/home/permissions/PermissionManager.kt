package com.svetlana.home.permissions

import android.Manifest
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.svetlana.home.apps.AppAliases
import com.svetlana.home.hands.SvetlanaAccessibilityService

/**
 * Разрешение в Permission Center.
 */
data class PermissionItem(
    val key: String,
    val title: String,
    val description: String,
    val required: Boolean,
    val kind: Kind,
    val statusText: String,
    val granted: Boolean
) {
    enum class Kind { RUNTIME, SETTINGS, ROLE_HOME, ACCESSIBILITY, NOTIFICATION }
}

/**
 * Единый мастер разрешений и Permission Center.
 *
 * ВАЖНО: permission manager не запрашивает ничего лишнего и не обходить Android.
 * Если Android требует повторное системное подтверждение — пользователь проходит штатный flow.
 */
class PermissionManager(private val context: Context) {

    fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun list(): List<PermissionItem> {
        val accessible = accessibilityEnabled()
        return listOf(
            PermissionItem(
                key = KEY_MIC, title = "Микрофон",
                description = "Нужно для голосовых команд и перевода",
                required = true, kind = PermissionItem.Kind.RUNTIME,
                statusText = if (isGranted(Manifest.permission.RECORD_AUDIO)) "Разрешён" else "Не разрешён",
                granted = isGranted(Manifest.permission.RECORD_AUDIO)
            ),
            PermissionItem(
                key = KEY_CAMERA, title = "Камера",
                description = "Нужно для зрения и перевода через камеру",
                required = false, kind = PermissionItem.Kind.RUNTIME,
                statusText = if (isGranted(Manifest.permission.CAMERA)) "Разрешена" else "Не разрешена",
                granted = isGranted(Manifest.permission.CAMERA)
            ),
            PermissionItem(
                key = KEY_CONTACTS, title = "Контакты",
                description = "Нужно для звонков и сообщений по имени",
                required = false, kind = PermissionItem.Kind.RUNTIME,
                statusText = if (isGranted(Manifest.permission.READ_CONTACTS)) "Разрешены" else "Не разрешены",
                granted = isGranted(Manifest.permission.READ_CONTACTS)
            ),
            PermissionItem(
                key = KEY_PHONE, title = "Телефон",
                description = "Нужно для совершения звонков",
                required = false, kind = PermissionItem.Kind.RUNTIME,
                statusText = if (isGranted(Manifest.permission.CALL_PHONE)) "Разрешён" else "Не разрешён",
                granted = isGranted(Manifest.permission.CALL_PHONE)
            ),
            PermissionItem(
                key = KEY_NOTIFICATIONS, title = "Уведомления",
                description = "Нужно для статуса и ответов",
                required = false,
                kind = PermissionItem.Kind.NOTIFICATION,
                statusText = if (notificationsEnabled()) "Разрешены" else "Выключено",
                granted = notificationsEnabled()
            ),
            PermissionItem(
                key = KEY_LOCATION, title = "Местоположение",
                description = "Необязательно — только если попросите",
                required = false, kind = PermissionItem.Kind.RUNTIME,
                statusText = if (isGranted(Manifest.permission.ACCESS_COARSE_LOCATION)) "Разрешено" else "Выключено",
                granted = isGranted(Manifest.permission.ACCESS_COARSE_LOCATION)
            ),
            PermissionItem(
                key = KEY_HANDS, title = "Hands / Специальные возможности",
                description = "Нужно для управления другими приложениями",
                required = false, kind = PermissionItem.Kind.ACCESSIBILITY,
                statusText = if (accessible) "Активен" else "Выключено",
                granted = accessible
            ),
            PermissionItem(
                key = KEY_HOME, title = "Главный экран",
                description = "Светлана назначена главным экраном",
                required = false, kind = PermissionItem.Kind.ROLE_HOME,
                statusText = if (isHomeLauncher()) "Светлана" else "Не назначен",
                granted = isHomeLauncher()
            )
        )
    }

    fun missing(): List<PermissionItem> = list().filter { it.required && !it.granted }

    fun runtimePermissionFor(key: String): String? = when (key) {
        KEY_MIC -> Manifest.permission.RECORD_AUDIO
        KEY_CAMERA -> Manifest.permission.CAMERA
        KEY_CONTACTS -> Manifest.permission.READ_CONTACTS
        KEY_PHONE -> Manifest.permission.CALL_PHONE
        KEY_LOCATION -> Manifest.permission.ACCESS_COARSE_LOCATION
        else -> null
    }

    fun settingsIntentFor(key: String): Intent = when (key) {
        KEY_HANDS -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        KEY_NOTIFICATIONS -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        KEY_HOME -> homeRoleIntent()
        else -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /**
     * Запрос ROLE_HOME через официальный RoleManager.
     */
    fun homeRoleIntent(): Intent {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val rm = context.getSystemService(Context.ROLE_SERVICE) as RoleManager
            rm.isRoleAvailable(RoleManager.ROLE_HOME)
            return rm.createRequestRoleIntent(RoleManager.ROLE_HOME)
        }
        // Для старых версий — стандартный выбор launcher
        return Intent(Settings.ACTION_HOME_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    }

    fun isHomeLauncher(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val rm = context.getSystemService(Context.ROLE_SERVICE) as RoleManager
            return rm.isRoleHeld(RoleManager.ROLE_HOME)
        }
        return try {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val resolved = context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            resolved?.activityInfo?.packageName == context.packageName
        } catch (t: Throwable) { false }
    }

    /**
     * Аудит п.7: состояние геолокации — это ДВА независимых состояния.
     *   1. Разрешение Светлане (runtime permission)
     *   2. Системная геолокация Android (Location Services)
     *
     * Нельзя показывать одно общее «Выключено»: пользователь должен понимать,
     * что именно нужно включить.
     */
    fun locationDiagnostics(): LocationDiagnostics {
        val permissionGranted = isGranted(Manifest.permission.ACCESS_COARSE_LOCATION)
        val servicesOn = systemLocationEnabled()
        return LocationDiagnostics(
            permissionGranted = permissionGranted,
            locationServicesEnabled = servicesOn,
            summary = when {
                permissionGranted && servicesOn -> "Разрешено, геолокация включена"
                permissionGranted && !servicesOn -> "Разрешение есть, но системная геолокация выключена"
                !permissionGranted && servicesOn -> "Системная геолокация включена, но разрешение Светлане не выдано"
                else -> "Разрешение не выдано и системная геолокация выключена"
            }
        )
    }

    data class LocationDiagnostics(
        val permissionGranted: Boolean,
        val locationServicesEnabled: Boolean,
        val summary: String
    )

    /**
     * Системная геолокация Android (Location Services), а не разрешение приложения.
     */
    private fun systemLocationEnabled(): Boolean = try {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) lm.isLocationEnabled
        else lm.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER) ||
                lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)
    } catch (t: Throwable) { false }

    /**
     * Куда вести пользователя для включения системной геолокации.
     */
    fun locationServicesIntent(): Intent =
        Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    /**
     * Проверка, включён ли Hands (AccessibilityService), через Settings.Secure.
     */
    fun accessibilityEnabled(): Boolean {
        val expected = ComponentName(context, SvetlanaAccessibilityService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val splitter = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ':' else ';'
        return enabled.split(splitter).any { it.equals(expected, ignoreCase = true) }
    }

    private fun notificationsEnabled(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.areNotificationsEnabled()
        } else true
    }

    /**
     * Сводный отчёт для Permission Center.
     */
    fun report(): String = buildString {
        list().forEach {
            appendLine("${it.title}: ${it.statusText}")
        }
    }

    companion object {
        const val KEY_MIC = "mic"
        const val KEY_CAMERA = "camera"
        const val KEY_CONTACTS = "contacts"
        const val KEY_PHONE = "phone"
        const val KEY_NOTIFICATIONS = "notifications"
        const val KEY_LOCATION = "location"
        const val KEY_HANDS = "hands"
        const val KEY_HOME = "home"
    }
}
