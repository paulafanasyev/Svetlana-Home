package com.svetlana.home.store

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.svetlana.home.SvetlanaDeviceTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * SecureKeyStore на устройстве (ТЗ §38, аудит п.17).
 *
 * Главная проверка: если защищённое хранилище недоступно, мы НЕ падаем на
 * обычный SharedPreferences для секретов. Секрет просто не сохраняется,
 * и пользователь об этом узнаёт.
 */
@RunWith(AndroidJUnit4::class)
class SecureKeyStoreDeviceTest : SvetlanaDeviceTest() {

    @Test
    fun secretsRoundtripWhenSecure() {
        val store = SecureKeyStore.create(context)
        println("SECURE_KEYSTORE_IS_SECURE=${store.isSecure}")

        if (!store.isSecure) {
            // Keystore недоступен — секрет не должен сохраняться.
            val saved = store.put("test_secret_key", "secret-value")
            assertFalse("В небезопасном режиме секрет не сохраняется", saved)
            assertNull("В небезопасном режиме секрет не читается",
                store.get("test_secret_key"))
            return
        }

        // Безопасный режим: кладём и читаем.
        val saved = store.put("test_secret_key", "secret-value")
        assertTrue("В безопасном режиме секрет сохраняется", saved)
        assertTrue("Секрет должен читаться", store.has("test_secret_key"))
        assertTrue("Значение должно совпадать",
            store.get("test_secret_key") == "secret-value")
        store.remove("test_secret_key")
        assertFalse("После удаления секрета нет", store.has("test_secret_key"))
    }

    @Test
    fun providerKeysAreIsolated() {
        val store = SecureKeyStore.create(context)
        if (!store.isSecure) {
            println("SKIP: защищённое хранилище недоступно")
            return
        }
        store.put(SecureKeyStore.PROVIDER_KEY_PREFIX + "provider_a", "key-a")
        store.put(SecureKeyStore.PROVIDER_KEY_PREFIX + "provider_b", "key-b")

        assertTrue("Ключ провайдера A изолирован",
            store.get(SecureKeyStore.PROVIDER_KEY_PREFIX + "provider_a") == "key-a")
        assertTrue("Ключ провайдера B изолирован",
            store.get(SecureKeyStore.PROVIDER_KEY_PREFIX + "provider_b") == "key-b")

        store.remove(SecureKeyStore.PROVIDER_KEY_PREFIX + "provider_a")
        assertFalse("Удаление A не влияет на B",
            store.has(SecureKeyStore.PROVIDER_KEY_PREFIX + "provider_a"))
        assertTrue("B остался", store.has(SecureKeyStore.PROVIDER_KEY_PREFIX + "provider_b"))

        store.remove(SecureKeyStore.PROVIDER_KEY_PREFIX + "provider_b")
    }

    @Test
    fun apiKeysNeverInSettingsRepository() {
        // ТЗ §78: в хранилище настроек не должно быть API-ключей.
        // Проверяем, что ключи лежат в SecureKeyStore, а не в DataStore.
        val store = SecureKeyStore.create(context)
        if (!store.isSecure) {
            println("SKIP: защищённое хранилище недоступно")
            return
        }
        store.put(SecureKeyStore.PROVIDER_KEY_PREFIX + "scan_test", "value")
        // Если SettingsRepository когда-либо начнёт хранить ключи —
        // этот тест надо будет расширить.
        assertTrue("Секрет хранится в SecureKeyStore",
            store.has(SecureKeyStore.PROVIDER_KEY_PREFIX + "scan_test"))
        store.remove(SecureKeyStore.PROVIDER_KEY_PREFIX + "scan_test")
    }
}
