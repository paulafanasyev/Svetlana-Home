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
 * один раз, пересохраняется зашифрованно и удаляется только после успешной записи. Если
 * удалить его не удалось, флаг [legacyPending] остаётся и удаление повторяется при каждой
 * записи и запуске (с записью в лог). Если шифрование недоступно — новая история живёт
 * только в памяти, в открытом виде на диск не пишется никогда.
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

    /** Старый открытый файл ещё лежит на диске (ждёт удаления). */
    @Volatile
    var legacyPending: Boolean = false
        private set

    init {
        tmp.delete()
        if (legacy.exists()) {
            if (save()) dropLegacy()
            else {
                legacyPending = true
                Log.w(TAG, "Шифрование недоступно — перенос старой истории отложен")
            }
        }
    }

    /** Удаляет старый открытый файл и проверяет, что его больше нет; иначе оставляет флаг и пишет в лог. */
    private fun dropLegacy(): Boolean {
        if (legacy.exists()) legacy.delete()
        val gone = !legacy.exists()
        legacyPending = !gone
        if (!gone) Log.w(TAG, "Не удалось удалить старую незашифрованную историю — повторю при следующей записи")
        return gone
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
        if (save() && (legacyPending || legacy.exists())) dropLegacy()
    }

    fun all(): List<HistoryEntry> = entries.sortedByDescending { it.timestampMs }

    fun byCategory(category: HistoryCategory): List<HistoryEntry> =
        entries.filter { it.category == category }.sortedByDescending { it.timestampMs }

    fun clear() {
        entries = emptyList()
        dropLegacy()
        saveAfterRemoval()
    }

    fun clearCategory(category: HistoryCategory) {
        entries = entries.filterNot { it.category == category }
        // Старый открытый файл тоже содержит удалённую категорию.
        dropLegacy()
        saveAfterRemoval()
    }

    companion object {
        private const val TAG = "History"
        private const val MAX_ENTRIES = 500
    }
}
