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
import com.svetlana.home.core.Tr
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
 *
 * Тексты — на языке интерфейса ([Tr]): ресурсы контекста приложения уже
 * переключены на выбранный язык (AppLanguage).
 */
class PermissionManager(private val context: Context) {

    private fun t(ru: String, en: String): String = Tr.t(context, ru, en)

    fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun list(): List<PermissionItem> {
        val accessible = accessibilityEnabled()
        val off = t("Выключено", "Off")
        val allowed = t("Разрешён", "Allowed")
        val notAllowed = t("Не разрешён", "Not allowed")
        return listOf(
            PermissionItem(
                key = KEY_MIC, title = t("Микрофон", "Microphone"),
                description = t("Нужно для голосовых команд и wake word", "Needed for voice commands and the wake word"),
                required = true, kind = PermissionItem.Kind.RUNTIME,
                statusText = if (isGranted(Manifest.permission.RECORD_AUDIO)) allowed else notAllowed,
                granted = isGranted(Manifest.permission.RECORD_AUDIO)
            ),
            PermissionItem(
                key = KEY_CAMERA, title = t("Камера", "Camera"),
                description = t("Нужно для зрения и анализа изображений", "Needed for vision and image analysis"),
                required = false, kind = PermissionItem.Kind.RUNTIME,
                statusText = if (isGranted(Manifest.permission.CAMERA)) t("Разрешена", "Allowed") else t("Не разрешена", "Not allowed"),
                granted = isGranted(Manifest.permission.CAMERA)
            ),
            PermissionItem(
                key = KEY_CONTACTS, title = t("Контакты", "Contacts"),
                description = t("Нужно для звонков и сообщений по имени", "Needed to call and message people by name"),
                required = false, kind = PermissionItem.Kind.RUNTIME,
                statusText = if (isGranted(Manifest.permission.READ_CONTACTS)) t("Разрешены", "Allowed") else t("Не разрешены", "Not allowed"),
                granted = isGranted(Manifest.permission.READ_CONTACTS)
            ),
            PermissionItem(
                key = KEY_PHONE, title = t("Телефон", "Phone"),
                description = t("Нужно для совершения звонков", "Needed to make calls"),
                required = false, kind = PermissionItem.Kind.RUNTIME,
                statusText = if (isGranted(Manifest.permission.CALL_PHONE)) allowed else notAllowed,
                granted = isGranted(Manifest.permission.CALL_PHONE)
            ),
            PermissionItem(
                key = KEY_NOTIFICATIONS, title = t("Уведомления", "Notifications"),
                description = t("Нужно для статуса и ответов", "Needed for status and replies"),
                required = false,
                kind = PermissionItem.Kind.NOTIFICATION,
                statusText = if (notificationsEnabled()) t("Разрешены", "Allowed") else off,
                granted = notificationsEnabled()
            ),
            PermissionItem(
                key = KEY_LOCATION, title = t("Местоположение", "Location"),
                description = t("Необязательно — только если попросите", "Optional — only when you ask"),
                required = false, kind = PermissionItem.Kind.RUNTIME,
                statusText = if (isGranted(Manifest.permission.ACCESS_COARSE_LOCATION)) t("Разрешено", "Allowed") else off,
                granted = isGranted(Manifest.permission.ACCESS_COARSE_LOCATION)
            ),
            PermissionItem(
                key = KEY_HANDS, title = t("Hands / Специальные возможности", "Hands / Accessibility"),
                description = t("Нужно для управления другими приложениями", "Needed to control other apps"),
                required = false, kind = PermissionItem.Kind.ACCESSIBILITY,
                statusText = if (accessible) t("Активен", "Active") else off,
                granted = accessible
            ),
            PermissionItem(
                key = KEY_HOME, title = t("Главный экран", "Home screen"),
                description = t("Светлана назначена главным экраном", "Svetlana is set as the Home screen"),
                required = false, kind = PermissionItem.Kind.ROLE_HOME,
                statusText = if (isHomeLauncher()) t("Светлана", "Svetlana") else t("Не назначен", "Not set"),
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
            if (rm.isRoleAvailable(RoleManager.ROLE_HOME)) {
                return rm.createRequestRoleIntent(RoleManager.ROLE_HOME)
            }
            // Some OEM builds expose HOME selection without a usable RoleManager
            // role. Fall back to the standard launcher settings instead of
            // returning an intent that cannot be handled.
        }
        // Для старых версий и OEM fallback — стандартный выбор launcher.
        return Intent(Settings.ACTION_HOME_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
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
                permissionGranted && servicesOn -> t("Разрешено, геолокация включена", "Allowed, location is on")
                permissionGranted && !servicesOn -> t("Разрешение есть, но системная геолокация выключена", "Permission granted, but system location is off")
                !permissionGranted && servicesOn -> t("Системная геолокация включена, но разрешение Светлане не выдано", "System location is on, but Svetlana has no permission")
                else -> t("Разрешение не выдано и системная геолокация выключена", "No permission, and system location is off")
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
