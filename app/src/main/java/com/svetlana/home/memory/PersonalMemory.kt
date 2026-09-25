package com.svetlana.home.memory

import android.content.Context
import android.util.Log
import com.svetlana.home.store.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * PersonalMemory — персональная память Светланы (ТЗ §61).
 *
 * Режимы: Local / Remote / Disabled.
 * Персональная память НЕ отправляется внешнему AI автоматически —
 * только на сервер пользователя, если выбран режим Remote (и то через PrivacyRouter).
 */
class PersonalMemory(
    context: Context,
    private val settings: SettingsRepository
) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val file: File = File(context.filesDir, "memory.json").apply { parentFile?.mkdirs() }

    @Volatile
    private var facts: List<MemoryFact> = load()

    private fun load(): List<MemoryFact> = try {
        if (file.exists() && file.length() > 0)
            json.decodeFromString(ListSerializer(MemoryFact.serializer()), file.readText())
        else emptyList()
    } catch (t: Throwable) { emptyList() }

    private fun save() {
        try {
            file.writeText(json.encodeToString(ListSerializer(MemoryFact.serializer()), facts))
        } catch (t: Throwable) {
            Log.w(TAG, "Не удалось сохранить память", t)
        }
    }

    suspend fun remember(text: String) {
        val mode = settings.memoryMode.first()
        if (mode == MemoryMode.DISABLED || text.isBlank()) return
        facts = facts + MemoryFact(
            java.util.UUID.randomUUID().toString(),
            text.trim(),
            System.currentTimeMillis()
        )
        save()
    }

    fun facts(): List<MemoryFact> = facts

    suspend fun context(maxItems: Int = 8): String {
        if (facts.isEmpty()) return ""
        return facts.takeLast(maxItems).joinToString("\n") { "- ${it.text}" }
    }

    fun forgetAll() {
        facts = emptyList()
        save()
    }

    companion object { private const val TAG = "PersonalMemory" }
}

@kotlinx.serialization.Serializable
data class MemoryFact(val id: String, val text: String, val createdAt: Long)
