package com.svetlana.home.avatar

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ТЗ §9, §74: адаптивный выбор аватара и resource-based fallback.
 *
 * Аудит п.24: AvatarEngine не должен выбирать уровень, renderer для
 * которого не зарегистрирован. Логика вынесена в [AvatarFallback] —
 * чистую функцию без Android-зависимостей.
 *
 * Цепочка деградации: Real Avatar → Light Avatar → Living Orb.
 */
class AvatarEngineFallbackTest {

    @After
    fun tearDown() {
        AvatarRendererRegistry.reset()
    }

    @Test
    fun requestL3_withoutRenderer_degradesToOrb() {
        AvatarRendererRegistry.reset()
        // В реестре только L0_LIVING_ORB
        val decision = AvatarFallback.decide(AvatarLevel.L3_FULL_REAL_AVATAR)

        assertEquals("Запрошен L3", AvatarLevel.L3_FULL_REAL_AVATAR, decision.requestedLevel)
        assertFalse("L3 renderer не доступен", decision.rendererAvailable)
        assertEquals("Деградация до Orb", AvatarLevel.L0_LIVING_ORB, decision.selectedLevel)
        assertTrue("Причина указана", decision.reason.isNotBlank())
    }

    @Test
    fun requestL1_withL1Renderer_selectsL1() {
        AvatarRendererRegistry.reset()
        AvatarRendererRegistry.register(AvatarLevel.L1_LIGHT_AVATAR)

        val decision = AvatarFallback.decide(AvatarLevel.L1_LIGHT_AVATAR)

        assertTrue("L1 renderer доступен", decision.rendererAvailable)
        assertEquals("Выбран L1", AvatarLevel.L1_LIGHT_AVATAR, decision.selectedLevel)
    }

    @Test
    fun requestL3_withL1Only_degradesToL1() {
        // Real → Light → Orb: L3 недоступен, L1 есть → L1
        AvatarRendererRegistry.reset()
        AvatarRendererRegistry.register(AvatarLevel.L1_LIGHT_AVATAR)

        val decision = AvatarFallback.decide(AvatarLevel.L3_FULL_REAL_AVATAR)

        assertFalse("L3 не доступен", decision.rendererAvailable)
        assertEquals("Деградация до L1", AvatarLevel.L1_LIGHT_AVATAR, decision.selectedLevel)
    }

    @Test
    fun requestL2_withL1Only_degradesToL1() {
        AvatarRendererRegistry.reset()
        AvatarRendererRegistry.register(AvatarLevel.L1_LIGHT_AVATAR)

        val decision = AvatarFallback.decide(AvatarLevel.L2_REALISTIC_AVATAR)

        assertEquals("Деградация до L1", AvatarLevel.L1_LIGHT_AVATAR, decision.selectedLevel)
    }

    @Test
    fun requestL0_alwaysAvailable() {
        AvatarRendererRegistry.reset()

        val decision = AvatarFallback.decide(AvatarLevel.L0_LIVING_ORB)

        assertTrue("Orb всегда доступен", decision.rendererAvailable)
        assertEquals("Выбран Orb", AvatarLevel.L0_LIVING_ORB, decision.selectedLevel)
    }

    @Test
    fun unavailableLevels_neverReportedAsAvailable() {
        AvatarRendererRegistry.reset()
        // Базовая сборка: L2/L3 не зарегистрированы
        assertFalse("L2 не доступен в базовой сборке",
            AvatarRendererRegistry.isAvailable(AvatarLevel.L2_REALISTIC_AVATAR))
        assertFalse("L3 не доступен в базовой сборке",
            AvatarRendererRegistry.isAvailable(AvatarLevel.L3_FULL_REAL_AVATAR))
        assertTrue("L0 доступен всегда",
            AvatarRendererRegistry.isAvailable(AvatarLevel.L0_LIVING_ORB))
    }

    @Test
    fun fallbackChain_alwaysEndsAtAvailableLevel() {
        // Для любого запрошенного уровня результат — всегда доступный уровень.
        // Это и есть защита от false capability (аудит п.24).
        AvatarRendererRegistry.reset()
        AvatarRendererRegistry.register(AvatarLevel.L1_LIGHT_AVATAR)

        for (level in AvatarLevel.values()) {
            val decision = AvatarFallback.decide(level)
            assertTrue("Для ${level.label} выбран доступный renderer",
                AvatarRendererRegistry.isAvailable(decision.selectedLevel))
        }
    }

    @Test
    fun unregister_L1_fallsBackToOrb() {
        // Если renderer L1 убрали — выбирается Orb
        AvatarRendererRegistry.reset()
        AvatarRendererRegistry.register(AvatarLevel.L1_LIGHT_AVATAR)
        AvatarRendererRegistry.unregister(AvatarLevel.L1_LIGHT_AVATAR)

        val decision = AvatarFallback.decide(AvatarLevel.L1_LIGHT_AVATAR)
        assertEquals("L1 удалён → Orb", AvatarLevel.L0_LIVING_ORB, decision.selectedLevel)
        assertFalse("L1 больше не доступен", decision.rendererAvailable)
    }
}
