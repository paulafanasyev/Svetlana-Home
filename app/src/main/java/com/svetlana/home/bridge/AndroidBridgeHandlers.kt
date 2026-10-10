package com.svetlana.home.bridge

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.ContactsContract.CommonDataKinds.Phone
import androidx.core.content.ContextCompat
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Real device operations behind the bridge. */
class AndroidBridgeHandlers(private val context: Context) : BridgeHandlers {

    override fun deviceInfo(): JsonObject {
        val metrics = context.resources.displayMetrics
        return buildJsonObject {
            put("platform", "android")
            put("model", "${Build.MANUFACTURER} ${Build.MODEL}")
            put("osVersion", Build.VERSION.RELEASE)
            put("screenWidth", metrics.widthPixels)
            put("screenHeight", metrics.heightPixels)
            put("density", metrics.density)
        }
    }

    override fun listContacts(query: String?, limit: Int, offset: Int): BridgeResult {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            return BridgeResult.Error(
                "PERMISSION_DENIED",
                "Нет доступа к контактам. Разрешите его в «Настроить Светлану» или в настройках Android.",
            )
        }
        val projection = arrayOf(Phone.CONTACT_ID, Phone.DISPLAY_NAME, Phone.NUMBER)
        val selection = if (query == null) null else "${Phone.DISPLAY_NAME} LIKE ?"
        val selectionArgs = if (query == null) null else arrayOf("%$query%")
        val contacts = LinkedHashMap<Long, ContactRow>()
        val cursor = context.contentResolver.query(
            Phone.CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            "${Phone.DISPLAY_NAME} COLLATE LOCALIZED ASC",
        ) ?: return BridgeResult.Error("INTERNAL", "Contacts provider unavailable")
        cursor.use { c ->
            val idIndex = c.getColumnIndexOrThrow(Phone.CONTACT_ID)
            val nameIndex = c.getColumnIndexOrThrow(Phone.DISPLAY_NAME)
            val numberIndex = c.getColumnIndexOrThrow(Phone.NUMBER)
            while (c.moveToNext()) {
                val number = c.getString(numberIndex)
                if (!number.isNullOrBlank()) {
                    val row = contacts.getOrPut(c.getLong(idIndex)) { ContactRow(c.getString(nameIndex).orEmpty()) }
                    if (number !in row.phones) row.phones.add(number)
                }
            }
        }
        val page = contacts.entries.drop(offset).take(limit)
        val data = buildJsonObject {
            put("total", contacts.size)
            put("offset", offset)
            put("contacts", buildJsonArray {
                for ((id, row) in page) {
                    add(buildJsonObject {
                        put("id", id.toString())
                        put("name", row.name)
                        put("phones", buildJsonArray { row.phones.forEach { add(JsonPrimitive(it)) } })
                    })
                }
            })
        }
        return BridgeResult.Ok(data)
    }

    override fun launchApp(packageName: String): BridgeResult {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: return BridgeResult.Error("NOT_FOUND", "Приложение не установлено: $packageName")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            BridgeResult.Ok()
        } catch (e: ActivityNotFoundException) {
            BridgeResult.Error("NOT_FOUND", "Не удалось открыть $packageName")
        }
    }

    private class ContactRow(val name: String) {
        val phones = mutableListOf<String>()
    }
}
