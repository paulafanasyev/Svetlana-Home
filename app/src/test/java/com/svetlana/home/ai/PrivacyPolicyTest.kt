package com.svetlana.home.ai

import com.google.common.truth.Truth.assertWithMessage
import com.google.common.truth.Truth.assertThat
import com.svetlana.home.memory.MemoryMode
import org.junit.Test

/**
 * Тесты чистой privacy-логики (ТЗ §49, §50).
 *
 * Это главная защита: LOCAL_ONLY должен физически исключать передачу
 * ЛЮБЫХ данных наружу, а режимы MY_SERVER/EXTERNAL — отправлять только
 * разрешённые типы данных. Логика не зависит от Android, поэтому
 * проверяется полностью на JVM.
 */
class PrivacyPolicyTest {

    @Test
    fun localOnly_blocksEverythingNonLocal() {
        // Все типы данных, все не-LOCAL бэкенды — запрещены.
        for (dataType in PrivacyDataType.entries) {
            for (backend in listOf(AIBackend.EXTERNAL, AIBackend.PERSONAL_SERVER, AIBackend.HYBRID)) {
                val d = PrivacyPolicy.decide(dataType, backend, AIMode.LOCAL_ONLY)
                assertWithMessage("LOCAL_ONLY должен блокировать $dataType → $backend; получил allowed=${d.allowed}, reason=${d.reason}")
                    .that(d.allowed).isFalse()
            }
        }
    }

    @Test
    fun localBackend_alwaysAllowed() {
        // На устройство данные передавать всегда можно
        for (mode in AIMode.entries) {
            val d = PrivacyPolicy.decide(PrivacyDataType.TEXT, AIBackend.LOCAL, mode)
            assertThat(d.allowed).isTrue()
        }
    }

    @Test
    fun localOnly_blockedMessageIsUserFriendly() {
        val msg = PrivacyPolicy.localOnlyBlockedMessage()
        assertThat(msg).contains("Только устройство")
        assertThat(msg).contains("запрещает")
    }

    @Test
    fun myServer_allowsTextAndHeavyData() {
        val text = PrivacyPolicy.decide(PrivacyDataType.TEXT, AIBackend.PERSONAL_SERVER, AIMode.MY_SERVER)
        assertThat(text.allowed).isTrue()

        val screenshot = PrivacyPolicy.decide(PrivacyDataType.SCREENSHOT, AIBackend.PERSONAL_SERVER, AIMode.MY_SERVER)
        assertThat(screenshot.allowed).isTrue()

        val audio = PrivacyPolicy.decide(PrivacyDataType.AUDIO, AIBackend.EXTERNAL, AIMode.MY_SERVER)
        // MY_SERVER перенаправляет тяжёлые данные на персональный сервер
        assertThat(audio.allowed).isTrue()
        assertThat(audio.backend).isEqualTo(AIBackend.PERSONAL_SERVER)
    }

    @Test
    fun localFirst_keepsHeavyDataOnDevice() {
        val screenshot = PrivacyPolicy.decide(PrivacyDataType.SCREENSHOT, AIBackend.EXTERNAL, AIMode.LOCAL_FIRST)
        assertThat(screenshot.allowed).isFalse()
        assertThat(screenshot.backend).isEqualTo(AIBackend.LOCAL)

        // Текст при LOCAL_FIRST тоже остаётся локальным
        val text = PrivacyPolicy.decide(PrivacyDataType.TEXT, AIBackend.EXTERNAL, AIMode.LOCAL_FIRST)
        assertThat(text.allowed).isFalse()
    }

    @Test
    fun historyNeverSentToExternalAutomatically() {
        // ТЗ §61: персональная память не отправляется внешнему AI автоматически
        val d = PrivacyPolicy.decide(PrivacyDataType.HISTORY, AIBackend.EXTERNAL, AIMode.EXTERNAL)
        assertThat(d.allowed).isFalse()
    }

    @Test
    fun historyToPersonalServerOnlyIfRemoteMemoryEnabled() {
        val blocked = PrivacyPolicy.decide(PrivacyDataType.HISTORY, AIBackend.EXTERNAL, AIMode.EXTERNAL,
            memoryMode = MemoryMode.LOCAL)
        assertThat(blocked.allowed).isFalse()

        // REMOTE memory mode + PERSONAL_SERVER backend — разрешено
        val allowed = PrivacyPolicy.decide(PrivacyDataType.HISTORY, AIBackend.PERSONAL_SERVER, AIMode.EXTERNAL,
            memoryMode = MemoryMode.REMOTE)
        assertThat(allowed.allowed).isTrue()
    }

    @Test
    fun externalMode_allowsTextAndHeavyData() {
        val text = PrivacyPolicy.decide(PrivacyDataType.TEXT, AIBackend.EXTERNAL, AIMode.EXTERNAL)
        assertThat(text.allowed).isTrue()

        val image = PrivacyPolicy.decide(PrivacyDataType.IMAGE, AIBackend.EXTERNAL, AIMode.EXTERNAL)
        assertThat(image.allowed).isTrue()
    }
}
