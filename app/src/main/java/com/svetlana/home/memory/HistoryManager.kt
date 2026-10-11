package com.svetlana.home.memory

import android.content.Context
import android.util.Log
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * HistoryManager — история Светланы.
 * Пользователь может удалить историю целиком.
 *
 * Приватность: история (команды, контакты, результаты) на диске только зашифрована
 * ключом из Android Keystore ([HistoryCipher]). Старый незашифрованный history.json читается
 * один раз, пересохраняется зашифрованно и удаляется. Если шифрование недоступно —
 * история живёт только в памяти, в открытом виде на диск не пишется никогда.
 */
class HistoryManager(context: Context) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val dir: File = context.filesDir.apply { mkdirs() }
    private val file: File = File(dir, "history.bin")
    private val legacy: File = File(dir, "history.json")

    @Volatile
    private var entries: List<HistoryEntry> = load()

    init {
        if (legacy.exists()) {
            save()
            if (!legacy.delete()) Log.w(TAG, "Не удалось удалить старую незашифрованную историю")
        }
    }

    private fun decode(text: String): List<HistoryEntry> =
        json.decodeFromString(ListSerializer(HistoryEntry.serializer()), text)

    private fun load(): List<HistoryEntry> {
        val encrypted = try {
            if (file.exists() && file.length() > 0) decode(String(HistoryCipher.decrypt(file.readBytes()), Charsets.UTF_8)) else null
        } catch (t: Throwable) {
            Log.w(TAG, "История не расшифрована — начинаю заново")
            null
        }
        if (encrypted != null) return encrypted
        return try {
            if (legacy.exists() && legacy.length() > 0) decode(legacy.readText()) else emptyList()
        } catch (t: Throwable) { emptyList() }
    }

    private fun save() {
        try {
            val plain = json.encodeToString(ListSerializer(HistoryEntry.serializer()), entries).toByteArray(Charsets.UTF_8)
            val tmp = File(dir, "history.bin.tmp")
            tmp.writeBytes(HistoryCipher.encrypt(plain))
            if (!tmp.renameTo(file)) {
                file.delete()
                if (!tmp.renameTo(file)) tmp.delete()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Не удалось сохранить историю (в открытом виде не пишу)", t)
        }
    }

    fun record(category: HistoryCategory, title: String, detail: String = "") {
        entries = (entries + HistoryEntry(UUID.randomUUID().toString(), category, title, detail,
            System.currentTimeMillis())).takeLast(MAX_ENTRIES)
        save()
    }

    fun all(): List<HistoryEntry> = entries.sortedByDescending { it.timestampMs }

    fun byCategory(category: HistoryCategory): List<HistoryEntry> =
        entries.filter { it.category == category }.sortedByDescending { it.timestampMs }

    fun clear() {
        entries = emptyList()
        save()
        legacy.delete()
    }

    fun clearCategory(category: HistoryCategory) {
        entries = entries.filterNot { it.category == category }
        save()
    }

    companion object {
        private const val TAG = "History"
        private const val MAX_ENTRIES = 500
    }
}
