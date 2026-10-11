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
 * один раз, пересохраняется зашифрованно и удаляется только после успешной записи (иначе
 * попытка повторится при следующей записи или запуске). Если шифрование недоступно —
 * новая история живёт только в памяти, в открытом виде на диск не пишется никогда.
 * Удаление (clear/clearCategory) надёжно: если перезаписать файл не удалось, старый зашифрованный
 * файл удаляется целиком (удалённые записи не остаются на диске).
 */
class HistoryManager(context: Context) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val dir: File = context.filesDir.apply { mkdirs() }
    private val file: File = File(dir, "history.bin")
    private val tmp: File = File(dir, "history.bin.tmp")
    private val legacy: File = File(dir, "history.json")

    @Volatile
    private var entries: List<HistoryEntry> = load()

    init {
        tmp.delete()
        if (legacy.exists()) migrateLegacy()
    }

    /** Старый открытый файл удаляется только после того, как зашифрованная копия записана. */
    private fun migrateLegacy() {
        if (save()) {
            if (!legacy.delete()) Log.w(TAG, "Не удалось удалить старую незашифрованную историю")
        } else {
            Log.w(TAG, "Шифрование недоступно — перенос старой истории отложен")
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

    /** Зашифрованная запись через временный файл; true — только если history.bin теперь содержит текущий список. */
    @Synchronized
    private fun save(): Boolean {
        return try {
            val plain = json.encodeToString(ListSerializer(HistoryEntry.serializer()), entries).toByteArray(Charsets.UTF_8)
            tmp.writeBytes(HistoryCipher.encrypt(plain))
            if (tmp.renameTo(file)) return true
            file.delete()
            if (tmp.renameTo(file)) true else { tmp.delete(); false }
        } catch (t: Throwable) {
            tmp.delete()
            Log.w(TAG, "Не удалось сохранить историю (в открытом виде не пишу)", t)
            false
        }
    }

    /** После удаления записей: если перезаписать не вышло, старый файл с удалёнными записями стирается. */
    private fun saveAfterRemoval() {
        if (!save()) {
            if (file.exists() && !file.delete()) Log.w(TAG, "Не удалось стереть файл истории")
        }
    }

    fun record(category: HistoryCategory, title: String, detail: String = "") {
        entries = (entries + HistoryEntry(UUID.randomUUID().toString(), category, title, detail,
            System.currentTimeMillis())).takeLast(MAX_ENTRIES)
        if (save() && legacy.exists()) legacy.delete()
    }

    fun all(): List<HistoryEntry> = entries.sortedByDescending { it.timestampMs }

    fun byCategory(category: HistoryCategory): List<HistoryEntry> =
        entries.filter { it.category == category }.sortedByDescending { it.timestampMs }

    fun clear() {
        entries = emptyList()
        legacy.delete()
        saveAfterRemoval()
    }

    fun clearCategory(category: HistoryCategory) {
        entries = entries.filterNot { it.category == category }
        // Старый открытый файл тоже содержит удалённую категорию.
        legacy.delete()
        saveAfterRemoval()
    }

    companion object {
        private const val TAG = "History"
        private const val MAX_ENTRIES = 500
    }
}
