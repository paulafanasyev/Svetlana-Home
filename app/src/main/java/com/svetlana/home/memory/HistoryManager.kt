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
 */
class HistoryManager(context: Context) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val file: File = File(context.filesDir, "history.json").apply { parentFile?.mkdirs() }

    @Volatile
    private var entries: List<HistoryEntry> = load()

    private fun load(): List<HistoryEntry> = try {
        if (file.exists() && file.length() > 0) json.decodeFromString(ListSerializer(HistoryEntry.serializer()), file.readText()) else emptyList()
    } catch (t: Throwable) { emptyList() }

    private fun save() {
        try { file.writeText(json.encodeToString(ListSerializer(HistoryEntry.serializer()), entries)) } catch (t: Throwable) {
            Log.w(TAG, "Не удалось сохранить историю", t)
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
