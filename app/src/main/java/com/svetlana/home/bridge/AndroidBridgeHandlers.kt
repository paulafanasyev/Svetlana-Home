package com.svetlana.home.bridge

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.ContactsContract
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
        // Rows of one contact are adjacent thanks to the secondary sort by CONTACT_ID,
        // so we can stream: skip [offset] contacts, collect [limit], stop early.
        val page = ArrayList<Pair<Long, ContactRow>>()
        val cursor = context.contentResolver.query(
            Phone.CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            "${Phone.DISPLAY_NAME} COLLATE LOCALIZED ASC, ${Phone.CONTACT_ID} ASC",
        ) ?: return BridgeResult.Error("INTERNAL", "Contacts provider unavailable")
        cursor.use { c ->
            val idIndex = c.getColumnIndexOrThrow(Phone.CONTACT_ID)
            val nameIndex = c.getColumnIndexOrThrow(Phone.DISPLAY_NAME)
            val numberIndex = c.getColumnIndexOrThrow(Phone.NUMBER)
            var seen = 0
            var currentId = Long.MIN_VALUE
            var current: ContactRow? = null
            while (c.moveToNext()) {
                val number = c.getString(numberIndex)
                if (number.isNullOrBlank()) continue
                val id = c.getLong(idIndex)
                if (id != currentId) {
                    currentId = id
                    seen++
                    if (seen > offset + limit) break
                    current = if (seen > offset) {
                        ContactRow(c.getString(nameIndex).orEmpty()).also { page.add(id to it) }
                    } else {
                        null
                    }
                }
                val row = current
                if (row != null && number !in row.phones) row.phones.add(number)
            }
        }
        val total = countContacts(query)
        val data = buildJsonObject {
            put("total", total)
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
        } catch (e: SecurityException) {
            // Android 10+ may block activity starts from the background.
            BridgeResult.Error("PERMISSION_DENIED", "Android не дал открыть $packageName из фона")
        }
    }

    /** Number of contacts that have at least one phone number (cheap: one row per contact). */
    private fun countContacts(query: String?): Int {
        val base = "${ContactsContract.Contacts.HAS_PHONE_NUMBER} = 1"
        val selection = if (query == null) base else "$base AND ${ContactsContract.Contacts.DISPLAY_NAME} LIKE ?"
        val args = if (query == null) null else arrayOf("%$query%")
        val cursor = context.contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(ContactsContract.Contacts._ID),
            selection,
            args,
            null,
        ) ?: return 0
        return cursor.use { it.count }
    }

    private class ContactRow(val name: String) {
        val phones = mutableListOf<String>()
    }
}
