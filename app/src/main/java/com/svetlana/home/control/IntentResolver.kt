package com.svetlana.home.control

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import com.svetlana.home.apps.AppModel
import com.svetlana.home.apps.AppRegistry

/**
 * IntentResolver — определяет лучший стандартный способ выполнить действие
 * без использования Hands. Приоритет: Intent → Public API → Deep Link → PackageManager.
 *
 * Главная идея ТЗ: не использовать Accessibility, если стандартный Android API
 * позволяет выполнить действие надёжнее.
 */
class IntentResolver(
    private val context: Context,
    private val registry: AppRegistry
) {

    data class ResolvedApp(
        val app: AppModel,
        val launchIntent: Intent,
        val way: String
    )

    /** Найти целевое приложение по голосовой команде. */
    fun resolveApp(spokenName: String): ResolvedApp? {
        val app = registry.resolve(spokenName) ?: return null
        val intent = launchIntentFor(app) ?: return null
        return ResolvedApp(app, intent, app.control.preferredWay())
    }

    fun launchIntentFor(app: AppModel): Intent? = try {
        val pm = context.packageManager
        val base = pm.getLaunchIntentForPackage(app.packageName)
        if (base != null) {
            base.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        } else {
            // Некоторые системные приложения открываются через явный component
            Intent().apply {
                component = ComponentName(app.packageName, guessLauncherActivity(app.packageName))
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
    } catch (t: Throwable) { null }

    private fun guessLauncherActivity(pkg: String): String {
        return when (pkg) {
            "com.android.settings" -> "com.android.settings.Settings"
            "com.android.dialer" -> "com.android.dialer.DialtactsActivity"
            "com.android.contacts" -> "com.android.contacts.activities.PeopleActivity"
            else -> "$pkg.MainActivity"
        }
    }

    // ---------- Стандартные публичные intent ----------

    fun openUrl(url: String): Intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun dial(phone: String): Intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun call(phone: String): Intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$phone"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun sms(to: String, body: String = ""): Intent =
        Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$to"))
            .putExtra("sms_body", body)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun shareText(text: String): Intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun openAppSettings(pkg: String): Intent = Intent(
        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", pkg, null)
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun openContactByName(name: String): Intent? {
        val cr = context.contentResolver
        val cursor = try {
            cr.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(ContactsContract.Contacts._ID),
                "${ContactsContract.Contacts.DISPLAY_NAME} LIKE ?",
                arrayOf("%$name%"),
                null
            )
        } catch (t: Throwable) { return null }
        cursor?.use {
            if (it.moveToFirst()) {
                val id = it.getLong(0)
                val uri = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_URI, id.toString())
                return Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        return null
    }
}
