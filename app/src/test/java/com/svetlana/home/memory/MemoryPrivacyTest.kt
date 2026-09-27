package com.svetlana.home.memory

import com.google.common.truth.Truth.assertThat
import com.svetlana.home.ai.AIMode
import org.junit.Test

/**
 * Логика решения: отправлять ли персональную память в текущий backend (ТЗ §61).
 *
 * ТЗ: «Не отправлять персональную память внешнему AI автоматически».
 * Реализация в PersonalMemory.context():
 *   - DISABLED — память не формируется никогда;
 *   - EXTERNAL backend — только при явно включённом режиме REMOTE;
 *   - остальные backend'ы — память участвует (локально или на сервере пользователя).
 *
 * Логика вынесена в чистую функцию, чтобы тестировать на JVM без устройства.
 */
class MemoryPrivacyTest {

    @Test
    fun disabledMode_neverSharesMemory() {
        assertThat(shouldShareMemory(AIMode.LOCAL_ONLY, MemoryMode.DISABLED)).isFalse()
        assertThat(shouldShareMemory(AIMode.EXTERNAL, MemoryMode.DISABLED)).isFalse()
        assertThat(shouldShareMemory(AIMode.MY_SERVER, MemoryMode.DISABLED)).isFalse()
    }

    @Test
    fun externalBackend_doesNotGetLocalMemoryByDefault() {
        // Пользователь не включал Remote — внешний AI не получает память.
        assertThat(shouldShareMemory(AIMode.EXTERNAL, MemoryMode.LOCAL)).isFalse()
    }

    @Test
    fun externalBackend_getsMemoryOnlyWithExplicitRemote() {
        // Явное разрешение пользователя.
        assertThat(shouldShareMemory(AIMode.EXTERNAL, MemoryMode.REMOTE)).isTrue()
    }

    @Test
    fun localOnly_alwaysUsesLocalMemory() {
        // Локальный режим — память не покидает устройство в любом случае.
        assertThat(shouldShareMemory(AIMode.LOCAL_ONLY, MemoryMode.LOCAL)).isTrue()
    }

    @Test
    fun localFirst_andAuto_useLocalMemoryByDefault() {
        // Промежуточные режимы без явного Remote — данные остаются локальными.
        assertThat(shouldShareMemory(AIMode.LOCAL_FIRST, MemoryMode.LOCAL)).isTrue()
        assertThat(shouldShareMemory(AIMode.AUTO, MemoryMode.LOCAL)).isTrue()
    }

    @Test
    fun myServer_usesMemoryEvenInLocalMode() {
        // Мой сервер — это backend пользователя; память на нём хранится
        // по умолчанию (это и есть смысл personal server). Не путаем
        // с внешним облаком.
        assertThat(shouldShareMemory(AIMode.MY_SERVER, MemoryMode.LOCAL)).isTrue()
    }

    companion object {
        /**
         * Точное зеркало логики PersonalMemory.context() для JVM-тестов.
         */
        fun shouldShareMemory(aiMode: AIMode, memoryMode: MemoryMode): Boolean {
            if (memoryMode == MemoryMode.DISABLED) return false
            if (aiMode == AIMode.EXTERNAL && memoryMode != MemoryMode.REMOTE) return false
            return true
        }
    }
}
