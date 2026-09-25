package com.svetlana.home.store

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.svetlana.home.ai.AIMode
import com.svetlana.home.memory.MemoryMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "svetlana_settings")

/**
 * Настройки пользователя. Хранятся на устройстве.
 */
class SettingsRepository(private val context: Context) {

    private val ds get() = context.dataStore

    val onboardingDone: Flow<Boolean> = ds.data.map { it[KEY_ONBOARDING] ?: false }
    val setupDone: Flow<Boolean> = ds.data.map { it[KEY_SETUP] ?: false }

    val aiMode: Flow<AIMode> = ds.data.map {
        AIMode.fromName(it[KEY_AI_MODE]) ?: AIMode.AUTO
    }

    val activeProviderId: Flow<String?> = ds.data.map { it[KEY_PROVIDER] }
    val fallbackProviderId: Flow<String?> = ds.data.map { it[KEY_FALLBACK_PROVIDER] }
    val activeLocalModelId: Flow<String?> = ds.data.map { it[KEY_LOCAL_MODEL] }

    val memoryMode: Flow<MemoryMode> = ds.data.map {
        MemoryMode.fromName(it[KEY_MEMORY_MODE]) ?: MemoryMode.LOCAL
    }

    val translatorBackend: Flow<String> = ds.data.map { it[KEY_TRANSLATOR_BACKEND] ?: "auto" }
    val wakeWordEnabled: Flow<Boolean> = ds.data.map { it[KEY_WAKE_WORD] ?: true }
    val avatarLevelOverride: Flow<Int> = ds.data.map { it[KEY_AVATAR_LEVEL] ?: -1 }

    suspend fun setOnboardingDone(value: Boolean) { ds.edit { it[KEY_ONBOARDING] = value } }
    suspend fun setSetupDone(value: Boolean) { ds.edit { it[KEY_SETUP] = value } }
    suspend fun setAiMode(mode: AIMode) { ds.edit { it[KEY_AI_MODE] = mode.name } }
    suspend fun setActiveProvider(id: String?) { ds.edit { if (id == null) it.remove(KEY_PROVIDER) else it[KEY_PROVIDER] = id } }
    suspend fun setFallbackProvider(id: String?) { ds.edit { if (id == null) it.remove(KEY_FALLBACK_PROVIDER) else it[KEY_FALLBACK_PROVIDER] = id } }
    suspend fun setActiveLocalModel(id: String?) { ds.edit { if (id == null) it.remove(KEY_LOCAL_MODEL) else it[KEY_LOCAL_MODEL] = id } }
    suspend fun setMemoryMode(mode: MemoryMode) { ds.edit { it[KEY_MEMORY_MODE] = mode.name } }
    suspend fun setTranslatorBackend(value: String) { ds.edit { it[KEY_TRANSLATOR_BACKEND] = value } }
    suspend fun setWakeWordEnabled(value: Boolean) { ds.edit { it[KEY_WAKE_WORD] = value } }
    suspend fun setAvatarLevelOverride(level: Int) { ds.edit { it[KEY_AVATAR_LEVEL] = level } }
    suspend fun setOwnerName(value: String) { ds.edit { it[KEY_OWNER_NAME] = value } }

    suspend fun <T> read(block: (Preferences) -> T): T = block(ds.data.first())
    suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        ds.edit(block)
    }

    companion object {
        private val KEY_ONBOARDING = booleanPreferencesKey("onboarding_done")
        private val KEY_SETUP = booleanPreferencesKey("setup_done")
        private val KEY_AI_MODE = stringPreferencesKey("ai_mode")
        private val KEY_PROVIDER = stringPreferencesKey("active_provider")
        private val KEY_FALLBACK_PROVIDER = stringPreferencesKey("fallback_provider")
        private val KEY_LOCAL_MODEL = stringPreferencesKey("active_local_model")
        private val KEY_MEMORY_MODE = stringPreferencesKey("memory_mode")
        private val KEY_TRANSLATOR_BACKEND = stringPreferencesKey("translator_backend")
        private val KEY_WAKE_WORD = booleanPreferencesKey("wake_word_enabled")
        private val KEY_AVATAR_LEVEL = intPreferencesKey("avatar_level_override")
        private val KEY_OWNER_NAME = stringPreferencesKey("owner_name")
    }
}
